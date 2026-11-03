package com.aresstack.askai.party;

import com.aresstack.askai.groupchat.GroupChatConnectionState;
import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.GroupChatRoom;
import com.aresstack.askai.groupchat.Participant;
import com.aresstack.askai.groupchat.jgroups.JGroupsGroupChatTransport;
import com.aresstack.askai.groupchat.jgroups.JGroupsTransportConfig;
import org.jgroups.protocols.SHARED_LOOPBACK;
import org.jgroups.protocols.SHARED_LOOPBACK_PING;
import org.jgroups.stack.Protocol;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Regression tests for the P0 deadlock between {@link PartySession} and the real JGroups transport
 * (party audit, DeadlockRepro): PartySession and {@link JGroupsGroupChatTransport} wired together
 * exactly as in the app, over JGroups' in-JVM SHARED_LOOPBACK. Every scenario fails if any thread
 * ends up in a monitor deadlock or a step does not finish within its bound.
 */
public class PartySessionJGroupsDeadlockTest {

    private static final long STEP_TIMEOUT_MS = 30_000L;

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    @Rule
    public final Timeout globalTimeout = Timeout.seconds(180);

    private final List<PartySession> sessions = Collections.synchronizedList(new ArrayList<PartySession>());
    private GroupChatRoom room;

    @Before
    public void setUp() {
        System.setProperty("java.net.preferIPv4Stack", "true");
        room = new GroupChatRoom("deadlock-" + UUID.randomUUID(), "Deadlock", "test-secret");
    }

    @After
    public void tearDown() {
        for (PartySession session : new ArrayList<PartySession>(sessions)) {
            try {
                session.leave();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ scenarios

    /** Audit repro: the first submit right after a second peer joined hung forever. */
    @Test
    public void firstSubmitAfterSecondPeerJoinDoesNotHang() throws Exception {
        RecordingUi uiA = new RecordingUi();
        RecordingUi uiB = new RecordingUi();
        PartySession a = session("a", uiA, null);
        PartySession b = session("b", uiB, null);
        a.join();
        b.join();

        runBounded("first submit after join", () -> assertTrue(a.submitMessage("hello right after join")));
        runBounded("second submit after join", () -> assertTrue(b.submitMessage("hello back")));

        assertNoDeadlock();
        uiB.awaitMessages(1);
        uiA.awaitMessages(2);
    }

    /** Both participants send at the same time; every message reaches both sides. */
    @Test
    public void concurrentSendsFromTwoParticipants() throws Exception {
        RecordingUi uiA = new RecordingUi();
        RecordingUi uiB = new RecordingUi();
        PartySession a = session("a", uiA, null);
        PartySession b = session("b", uiB, null);
        a.join();
        b.join();
        awaitMembers(a, 2);
        awaitMembers(b, 2);

        final int perSender = 300;
        Thread senderA = sender(a, "a", perSender);
        Thread senderB = sender(b, "b", perSender);
        senderA.start();
        senderB.start();
        joinBounded(senderA);
        joinBounded(senderB);

        assertNoDeadlock();
        uiA.awaitMessages(2 * perSender);
        uiB.awaitMessages(2 * perSender);
    }

    /**
     * Further participants join and leave repeatedly while two peers keep sending messages and
     * re-announcing profiles (bot readiness flips) in parallel.
     */
    @Test
    public void repeatedJoinsWhileMessagesAndProfilesAreExchanged() throws Exception {
        ToggleBot botA = new ToggleBot();
        ToggleBot botB = new ToggleBot();
        RecordingUi uiA = new RecordingUi();
        RecordingUi uiB = new RecordingUi();
        PartySession a = session("a", uiA, botA);
        PartySession b = session("b", uiB, botB);
        a.join();
        b.join();

        final AtomicBoolean running = new AtomicBoolean(true);
        final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
        List<Thread> background = new ArrayList<Thread>();
        background.add(loop("chatter-a", running, failure, i ->
                a.submitMessage(i % 10 == 0 ? "@AskAI question " + i : "a" + i)));
        background.add(loop("chatter-b", running, failure, i -> b.submitMessage("b" + i)));
        background.add(loop("profiles-a", running, failure, i -> {
            botA.ready = !botA.ready;
            a.updateBotReadiness();
        }));
        background.add(loop("profiles-b", running, failure, i -> {
            botB.ready = !botB.ready;
            b.updateBotReadiness();
        }));
        for (Thread t : background) {
            t.start();
        }
        try {
            for (int round = 0; round < 12; round++) {
                RecordingUi uiC = new RecordingUi();
                PartySession c = session("c" + round, uiC, null);
                runBounded("join of c" + round, c::join);
                runBounded("submit of c" + round, () -> c.submitMessage("c says hi"));
                runBounded("leave of c" + round, c::leave);
                sessions.remove(c);
                assertNoDeadlock();
            }
        } finally {
            running.set(false);
            for (Thread t : background) {
                joinBounded(t);
            }
        }
        assertNull("background thread failed: " + failure.get(), failure.get());
        assertNoDeadlock();
        assertTrue(a.isConnected());
        assertTrue(b.isConnected());
    }

    // ------------------------------------------------------------------ helpers

    private PartySession session(String id, RecordingUi ui, BotResponder bot) throws Exception {
        File dir = tmp.newFolder("history-" + id + "-" + UUID.randomUUID());
        JGroupsGroupChatTransport transport = new JGroupsGroupChatTransport(
                JGroupsTransportConfig.builder().historyDirectory(dir).build()) {
            @Override
            protected List<Protocol> createTransportAndDiscovery() {
                return Arrays.<Protocol>asList(new SHARED_LOOPBACK(), new SHARED_LOOPBACK_PING());
            }
        };
        Participant self = new Participant(id, id, id, null, bot != null, false);
        String policy = bot == null ? PartySettings.BOT_POLICY_OFF : PartySettings.BOT_POLICY_MENTION;
        PartySession session = new PartySession(transport, room, self, () -> policy, bot, ui);
        sessions.add(session);
        return session;
    }

    private interface Step {
        void run() throws Exception;
    }

    private interface IndexedStep {
        void run(int i) throws Exception;
    }

    /** Runs {@code step} on its own thread and fails (with thread dump) when it does not finish. */
    private static void runBounded(String name, Step step) throws Exception {
        final AtomicReference<Throwable> error = new AtomicReference<Throwable>();
        Thread t = new Thread(() -> {
            try {
                step.run();
            } catch (Throwable e) {
                error.set(e);
            }
        }, "step-" + name);
        t.setDaemon(true);
        t.start();
        t.join(STEP_TIMEOUT_MS);
        if (t.isAlive()) {
            fail("'" + name + "' still blocked after " + STEP_TIMEOUT_MS + " ms\n" + dumpAll());
        }
        if (error.get() != null) {
            throw new AssertionError("'" + name + "' failed", error.get());
        }
    }

    private static Thread sender(PartySession session, String prefix, int count) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < count; i++) {
                if (!session.submitMessage(prefix + i)) {
                    throw new IllegalStateException("submit rejected at " + i);
                }
            }
        }, "sender-" + prefix);
        t.setDaemon(true);
        return t;
    }

    private static Thread loop(String name, AtomicBoolean running, AtomicReference<Throwable> failure,
                               IndexedStep step) {
        Thread t = new Thread(() -> {
            int i = 0;
            while (running.get()) {
                try {
                    step.run(i++);
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    return;
                } catch (Throwable e) {
                    failure.compareAndSet(null, e);
                    return;
                }
            }
        }, name);
        t.setDaemon(true);
        return t;
    }

    private static void joinBounded(Thread t) throws InterruptedException {
        t.join(STEP_TIMEOUT_MS);
        if (t.isAlive()) {
            fail("thread " + t.getName() + " still blocked after " + STEP_TIMEOUT_MS + " ms\n" + dumpAll());
        }
    }

    private static void awaitMembers(PartySession session, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
        while (session.participants().size() < count) {
            if (System.currentTimeMillis() > deadline) {
                fail("only " + session.participants().size() + " of " + count + " participants visible");
            }
            Thread.sleep(20);
        }
    }

    private static void assertNoDeadlock() {
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.findDeadlockedThreads();
        assertFalse("deadlocked threads:\n" + dumpAll(), ids != null && ids.length > 0);
    }

    private static String dumpAll() {
        StringBuilder sb = new StringBuilder();
        for (ThreadInfo info : ManagementFactory.getThreadMXBean().dumpAllThreads(true, true)) {
            sb.append(info);
        }
        return sb.toString();
    }

    /** Records rendered party messages (by id) for delivery assertions. */
    private static final class RecordingUi implements PartySession.Ui {
        final Set<String> messageIds = ConcurrentHashMap.newKeySet();

        public void onPartyMessage(PartySession.PartyMessageView view) {
            if (!view.isBot()) {
                messageIds.add(view.getMessage().getMessageId());
            }
        }

        public void onInfoLine(String text) {
        }

        public void onStatus(GroupChatConnectionState state) {
        }

        public void onHandlesChanged(List<String> handles) {
        }

        public void onBotThinkingDelta(String delta) {
        }

        public void onBotThinkingDone() {
        }

        void awaitMessages(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MS;
            while (messageIds.size() < count) {
                if (System.currentTimeMillis() > deadline) {
                    assertEquals("messages delivered", count, messageIds.size());
                }
                Thread.sleep(20);
            }
        }
    }

    /** Bot stub whose readiness flips (profile re-announcements) and that answers immediately. */
    private static final class ToggleBot implements BotResponder {
        volatile boolean ready = true;

        public boolean isReady() {
            return ready;
        }

        public List<String> modelMentionHandles() {
            return Collections.emptyList();
        }

        public void respond(List<GroupChatMessage> context, GroupChatMessage addressed,
                            Map<String, Participant> profiles, String requestedModel, Callback callback) {
            callback.onResponse("answer to " + addressed.getMessageId());
        }
    }
}
