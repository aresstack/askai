package com.aresstack.askai.party;

import com.aresstack.askai.groupchat.GroupChatConnectionState;
import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.GroupChatRoom;
import com.aresstack.askai.groupchat.InMemoryGroupChatTransport;
import com.aresstack.askai.groupchat.MentionParser;
import com.aresstack.askai.groupchat.Participant;
import org.junit.After;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Behavioral tests for {@link PartySession} over the in-memory transport: routing, lossless
 * submission, mention completion handles, and the exactly-one-bot-response guarantee.
 */
public class PartySessionTest {

    private final List<String> roomsToClear = new ArrayList<String>();
    private final List<PartySession> sessions = new ArrayList<PartySession>();

    @After
    public void tearDown() {
        for (PartySession session : sessions) {
            session.leave();
        }
        for (String roomId : roomsToClear) {
            InMemoryGroupChatTransport.clearRoom(roomId);
        }
    }

    /** Records everything the session reports toward the UI. */
    private static final class RecordingUi implements PartySession.Ui {
        final List<PartySession.PartyMessageView> messages =
                Collections.synchronizedList(new ArrayList<PartySession.PartyMessageView>());
        final List<String> infoLines = Collections.synchronizedList(new ArrayList<String>());
        final List<GroupChatConnectionState> states =
                Collections.synchronizedList(new ArrayList<GroupChatConnectionState>());
        volatile List<String> handles = Collections.emptyList();

        public void onPartyMessage(PartySession.PartyMessageView view) {
            messages.add(view);
        }

        public void onInfoLine(String text) {
            infoLines.add(text);
        }

        public void onStatus(GroupChatConnectionState state) {
            states.add(state);
        }

        public void onHandlesChanged(List<String> handles) {
            this.handles = handles;
        }

        public void onBotThinkingDelta(String delta) {
        }

        public void onBotThinkingDone() {
        }

        int botMessageCount() {
            int count = 0;
            synchronized (messages) {
                for (PartySession.PartyMessageView view : messages) {
                    if (view.isBot()) {
                        count++;
                    }
                }
            }
            return count;
        }
    }

    /** Synchronous fake bot backend; optionally held back to simulate a slow model. */
    private static final class FakeResponder implements BotResponder {
        volatile boolean ready = true;
        volatile boolean answer = true;
        volatile boolean silent;
        volatile int calls;
        volatile String lastRequestedModel;
        volatile List<String> models = Collections.emptyList();

        public boolean isReady() {
            return ready;
        }

        public List<String> modelMentionHandles() {
            return models;
        }

        public void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                            Map<String, Participant> profiles, String requestedModel,
                            Callback callback) {
            calls++;
            lastRequestedModel = requestedModel;
            if (silent) {
                callback.onNoAnswer();
            } else if (answer) {
                callback.onResponse("Answer to: " + addressed.getMarkdown());
            }
        }
    }

    private PartySession session(String roomId, String participantId, String name,
                                 FakeResponder responder, RecordingUi ui) {
        if (!roomsToClear.contains(roomId)) {
            roomsToClear.add(roomId);
        }
        Participant self = new Participant(participantId, name, name, null,
                responder != null, responder != null && responder.isReady());
        GroupChatRoom room = new GroupChatRoom(roomId, roomId, "secret");
        PartySession session = new PartySession(new InMemoryGroupChatTransport(), room, self,
                () -> PartySettings.BOT_POLICY_MENTION, responder, ui);
        sessions.add(session);
        return session;
    }

    @Test
    public void submitDeliversToAllParticipantsWithLocalFlag() {
        RecordingUi uiA = new RecordingUi();
        RecordingUi uiB = new RecordingUi();
        PartySession a = session("room.submit", "aaa", "Alice", null, uiA);
        PartySession b = session("room.submit", "bbb", "Bob", null, uiB);
        a.join();
        b.join();

        assertTrue(a.submitMessage("Hello party"));

        assertEquals(1, uiA.messages.size());
        assertEquals(1, uiB.messages.size());
        assertTrue(uiA.messages.get(0).isLocal());
        assertFalse(uiB.messages.get(0).isLocal());
        assertEquals("Alice", uiB.messages.get(0).getSenderDisplayName());
        assertEquals("Hello party", uiB.messages.get(0).getMessage().getMarkdown());
    }

    @Test
    public void submitIsRejectedWhileNotJoined() {
        RecordingUi ui = new RecordingUi();
        PartySession session = session("room.lossless", "aaa", "Alice", null, ui);
        assertFalse("composer must keep the text when not connected", session.submitMessage("draft"));
        assertTrue(ui.messages.isEmpty());
    }

    @Test
    public void handlesListParticipantsAndBot() {
        RecordingUi uiA = new RecordingUi();
        PartySession a = session("room.handles", "aaa", "Alice", null, uiA);
        a.join();
        session("room.handles", "bbb", "Bob", null, new RecordingUi()).join();

        List<String> handles = a.mentionHandles();
        assertTrue(handles.contains("Alice"));
        assertTrue(handles.contains("Bob"));
        assertTrue(handles.contains(MentionParser.BOT_HANDLE));
    }

    @Test
    public void botMentionProducesExactlyOneResponseAcrossTwoCapableHosts() {
        RecordingUi uiA = new RecordingUi();
        RecordingUi uiB = new RecordingUi();
        FakeResponder responderA = new FakeResponder();
        FakeResponder responderB = new FakeResponder();
        PartySession a = session("room.bot", "aaa", "Alice", responderA, uiA);
        PartySession b = session("room.bot", "bbb", "Bob", responderB, uiB);
        a.join();
        b.join();

        assertTrue(b.submitMessage("@AskAI what is up?"));

        assertEquals("exactly one logical bot response", 1, uiA.botMessageCount());
        assertEquals("exactly one logical bot response", 1, uiB.botMessageCount());
        assertEquals("only the elected host may run the model", 1, responderA.calls + responderB.calls);
        assertEquals("election is deterministic (lowest ready id)", 1, responderA.calls);
    }

    @Test
    public void modelMentionInvokesBotWithRequestedModel() {
        RecordingUi ui = new RecordingUi();
        FakeResponder responder = new FakeResponder();
        responder.models = java.util.Arrays.asList("gemma4:e2b", "llama3.1:8b");
        PartySession session = session("room.model", "aaa", "Alice", responder, ui);
        session.join();

        assertTrue(session.mentionHandles().contains("gemma4:e2b"));
        assertTrue(session.submitMessage("@gemma4:e2b was sind enten?"));

        assertEquals(1, responder.calls);
        assertEquals("gemma4:e2b", responder.lastRequestedModel);
        assertEquals(1, ui.botMessageCount());
    }

    @Test
    public void askAiMentionCarriesNoRequestedModel() {
        RecordingUi ui = new RecordingUi();
        FakeResponder responder = new FakeResponder();
        responder.models = java.util.Arrays.asList("gemma4:e2b");
        PartySession session = session("room.nomodel", "aaa", "Alice", responder, ui);
        session.join();

        assertTrue(session.submitMessage("@AskAI hallo"));

        assertEquals(1, responder.calls);
        assertEquals(null, responder.lastRequestedModel);
    }

    @Test
    public void humanMentionDoesNotInvokeBot() {
        RecordingUi uiA = new RecordingUi();
        FakeResponder responder = new FakeResponder();
        PartySession a = session("room.human", "aaa", "Alice", responder, uiA);
        a.join();

        assertTrue(a.submitMessage("@Alice are you there?"));

        assertEquals(0, responder.calls);
        assertEquals(0, uiA.botMessageCount());
    }

    @Test
    public void botPolicyOffNeverAnswers() {
        RecordingUi ui = new RecordingUi();
        FakeResponder responder = new FakeResponder();
        String roomId = "room.off";
        roomsToClear.add(roomId);
        Participant self = new Participant("aaa", "Alice", "Alice", null, true, true);
        PartySession session = new PartySession(new InMemoryGroupChatTransport(),
                new GroupChatRoom(roomId, roomId, "secret"), self,
                () -> PartySettings.BOT_POLICY_OFF, responder, ui);
        sessions.add(session);
        session.join();

        assertTrue(session.submitMessage("@AskAI hello?"));

        assertEquals(0, responder.calls);
    }

    @Test
    public void alwaysPolicySeesUnmentionedMessagesAndCanStaySilent() {
        RecordingUi ui = new RecordingUi();
        FakeResponder responder = new FakeResponder();
        String roomId = "room.always";
        roomsToClear.add(roomId);
        Participant self = new Participant("aaa", "Alice", "Alice", null, true, true);
        PartySession session = new PartySession(new InMemoryGroupChatTransport(),
                new GroupChatRoom(roomId, roomId, "secret"), self,
                () -> PartySettings.BOT_POLICY_ALWAYS, responder, ui);
        sessions.add(session);
        session.join();

        assertTrue(session.submitMessage("no mention here"));
        assertEquals("always policy considers every message", 1, responder.calls);
        assertEquals(1, ui.botMessageCount());

        responder.answer = false;
        responder.silent = true; // the model declines with the silent marker
        assertTrue(session.submitMessage("just chatting"));
        assertEquals(2, responder.calls);
        assertEquals("silent decline broadcasts nothing", 1, ui.botMessageCount());
        assertTrue("silence is not an error", ui.infoLines.isEmpty());
    }

    @Test
    public void botFailoverElectsReplacementWhenHostLeaves() {
        RecordingUi uiA = new RecordingUi();
        RecordingUi uiB = new RecordingUi();
        FakeResponder responderA = new FakeResponder();
        responderA.answer = false; // the elected host stalls and never answers
        FakeResponder responderB = new FakeResponder();
        PartySession a = session("room.failover", "aaa", "Alice", responderA, uiA);
        PartySession b = session("room.failover", "bbb", "Bob", responderB, uiB);
        a.join();
        b.join();

        assertTrue(b.submitMessage("@AskAI ping"));
        assertEquals(1, responderA.calls);
        assertEquals(0, uiB.botMessageCount());

        a.leave(); // the claim holder disappears → B re-elects itself and answers

        assertEquals(1, responderB.calls);
        assertEquals("exactly one bot response after failover", 1, uiB.botMessageCount());
    }

    @Test
    public void duplicateBotResponsesAreDiscarded() {
        RecordingUi uiA = new RecordingUi();
        FakeResponder responderA = new FakeResponder();
        PartySession a = session("room.dupbot", "aaa", "Alice", responderA, uiA);
        a.join();

        assertTrue(a.submitMessage("@AskAI once please"));

        assertEquals(1, uiA.botMessageCount());
        assertEquals(1, responderA.calls);
    }
}
