package com.aresstack.askai.party;

import com.aresstack.askai.groupchat.BotClaim;
import com.aresstack.askai.groupchat.BotElection;
import com.aresstack.askai.groupchat.BotResponseArbiter;
import com.aresstack.askai.groupchat.ColorMap;
import com.aresstack.askai.groupchat.GroupChatBot;
import com.aresstack.askai.groupchat.GroupChatConnectionState;
import com.aresstack.askai.groupchat.GroupChatListener;
import com.aresstack.askai.groupchat.GroupChatMessage;
import com.aresstack.askai.groupchat.GroupChatRoom;
import com.aresstack.askai.groupchat.GroupChatSubmissionTarget;
import com.aresstack.askai.groupchat.GroupChatTransport;
import com.aresstack.askai.groupchat.MentionParser;
import com.aresstack.askai.groupchat.Participant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Swing-free controller for one Partying room membership.
 *
 * <p>Owns the transport lifecycle, replays local history on join, filters duplicate bot
 * responses through the {@link BotResponseArbiter}, hosts the logical {@code @AskAI} bot when
 * this peer wins the deterministic election, and exposes the composer-facing
 * {@link GroupChatSubmissionTarget}. All callbacks into {@link Ui} happen on transport threads;
 * the UI layer dispatches to the EDT.</p>
 *
 * <p>Locking rule (P0 deadlock fix): the session monitor guards only the session's own fields.
 * No transport method is ever called while holding it, so the transport (which delivers its
 * events from its own delivery queue, never under its lock) and the session can never wait on
 * each other.</p>
 */
public final class PartySession implements GroupChatSubmissionTarget {

    /** Bounded number of recent messages kept as bot conversation context. */
    private static final int RECENT_LIMIT = 200;

    /** Callbacks toward the UI layer; invoked on transport threads. */
    public interface Ui {
        /** A renderable room message (deduplicated; at most one bot response per mention). */
        void onPartyMessage(PartyMessageView view);

        /** A muted transcript info line ("Maria left the party"). */
        void onInfoLine(String text);

        /** Typed connection state for the status line. */
        void onStatus(GroupChatConnectionState state);

        /** The set of completable mention handles changed. */
        void onHandlesChanged(List<String> handles);

        /**
         * Streaming thinking output while this peer hosts a bot answer — for the same
         * disappearing thought-bubble visualization as in the normal chat.
         */
        void onBotThinkingDelta(String delta);

        /** The bot attempt finished (answer, silence or failure); close the thought bubble. */
        void onBotThinkingDone();
    }

    /** One message prepared for rendering, with resolved sender metadata and color token. */
    public static final class PartyMessageView {
        private final GroupChatMessage message;
        private final String senderDisplayName;
        private final String senderHandle;
        private final String colorToken;
        private final boolean bot;
        private final boolean local;

        PartyMessageView(GroupChatMessage message, String senderDisplayName, String senderHandle,
                         String colorToken, boolean bot, boolean local) {
            this.message = message;
            this.senderDisplayName = senderDisplayName;
            this.senderHandle = senderHandle;
            this.colorToken = colorToken;
            this.bot = bot;
            this.local = local;
        }

        public GroupChatMessage getMessage() { return message; }
        public String getSenderDisplayName() { return senderDisplayName; }
        public String getSenderHandle() { return senderHandle; }
        /** Palette color token for the sender, or {@code null} when none is assigned yet. */
        public String getColorToken() { return colorToken; }
        public boolean isBot() { return bot; }
        /** {@code true} when the local participant authored the message. */
        public boolean isLocal() { return local; }
    }

    private final GroupChatTransport transport;
    private final GroupChatRoom room;
    private final Ui ui;
    private final java.util.function.Supplier<String> botPolicy;
    private final BotResponder botResponder;
    private final BotResponseArbiter arbiter = new BotResponseArbiter();
    private final List<GroupChatMessage> recentMessages = new ArrayList<GroupChatMessage>();
    /** addressedMessageId → bot host that currently holds the claim (for failover). */
    private final Map<String, String> pendingBotWork = new LinkedHashMap<String, String>();

    /** Message IDs already handed to the UI (local replay and live delivery may overlap). */
    private final java.util.Set<String> seenMessageIds = new java.util.HashSet<String>();
    private Participant self;
    private long nextSequence = 1;
    private volatile boolean joined;

    public PartySession(GroupChatTransport transport, GroupChatRoom room, Participant self,
                        java.util.function.Supplier<String> botPolicy, BotResponder botResponder, Ui ui) {
        this.transport = transport;
        this.room = room;
        this.self = self;
        this.botPolicy = botPolicy;
        this.botResponder = botResponder;
        this.ui = ui;
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Join the room and replay the locally stored history.  Blocking (network discovery); call
     * off the EDT.
     */
    public void join() {
        Participant joiningAs;
        synchronized (this) {
            joiningAs = self;
        }
        transport.join(room, joiningAs, new SessionListener());
        joined = true;
        List<GroupChatMessage> history = transport.localHistory();
        for (GroupChatMessage message : history) {
            synchronized (this) {
                if (self.getParticipantId().equals(message.getSenderParticipantId())
                        && message.getSenderSequence() >= nextSequence) {
                    nextSequence = message.getSenderSequence() + 1;
                }
            }
            handleIncoming(message, true);
        }
        ui.onHandlesChanged(mentionHandles());
    }

    /** Leave the room and release transport resources; idempotent. */
    public void leave() {
        boolean wasJoined;
        synchronized (this) {
            wasJoined = joined;
            joined = false;
        }
        if (wasJoined) {
            transport.leave();
        }
    }

    public boolean isConnected() {
        return joined && transport.isConnected();
    }

    /** Delete this room's locally persisted history (the on-disk log). */
    public void clearHistory() {
        transport.clearHistory();
    }

    /** Update this peer's bot readiness (model became available/unavailable) and announce it. */
    public void updateBotReadiness() {
        boolean capable = botResponder != null && !PartySettings.BOT_POLICY_OFF.equals(botPolicy.get());
        boolean ready = capable && botResponder.isReady();
        Participant updated;
        synchronized (this) {
            if (self.isBotCapable() == capable && self.isBotReady() == ready) {
                return;
            }
            self = self.withBotFlags(capable, ready);
            updated = self;
        }
        if (joined) {
            transport.updateSelf(updated);
        }
    }

    // ------------------------------------------------------------------ submission target

    @Override
    public boolean submitMessage(String markdown) {
        if (!isConnected()) {
            return false;
        }
        List<Participant> members = transport.getParticipants();
        GroupChatMessage message;
        synchronized (this) {
            message = new GroupChatMessage.Builder()
                    .messageId(UUID.randomUUID().toString())
                    .roomId(room.getRoomId())
                    .senderParticipantId(self.getParticipantId())
                    .senderSequence(nextSequence++)
                    .mentionedParticipantIds(
                            MentionParser.extractMentionedIds(markdown, members))
                    .markdown(markdown)
                    .build();
        }
        try {
            transport.send(message);
        } catch (IllegalStateException notSent) {
            return false; // disconnected in between: the caller keeps the text (lossless)
        }
        return true;
    }

    @Override
    public boolean isReady() {
        return isConnected();
    }

    // ------------------------------------------------------------------ queries

    public List<Participant> participants() {
        return transport.getParticipants();
    }

    /** Handles offered by {@code @} completion: all current participants plus the room bot. */
    public List<String> mentionHandles() {
        List<String> handles = new ArrayList<String>();
        for (Participant participant : transport.getParticipants()) {
            if (!handles.contains(participant.getMentionHandle())) {
                handles.add(participant.getMentionHandle());
            }
        }
        if (!handles.contains(MentionParser.BOT_HANDLE)) {
            handles.add(MentionParser.BOT_HANDLE);
        }
        if (botResponder != null) {
            for (String model : botResponder.modelMentionHandles()) {
                if (!handles.contains(model)) {
                    handles.add(model);
                }
            }
        }
        return handles;
    }

    /** @return the palette color token assigned to {@code participantId}, or {@code null}. */
    public String colorTokenOf(String participantId) {
        return transport.getColorMap().colorOf(participantId);
    }

    // ------------------------------------------------------------------ event handling

    private void handleIncoming(GroupChatMessage message, boolean fromHistory) {
        synchronized (this) {
            if (!seenMessageIds.add(message.getMessageId())) {
                return; // already shown (history replay overlapping queued live delivery)
            }
        }
        if (message.isBotMessage()) {
            String addressed = message.getReplyToMessageId();
            if (addressed != null
                    && !arbiter.acceptResponse(addressed, message.getBotHostParticipantId())) {
                return; // duplicate/late bot response — exactly one survives
            }
            synchronized (this) {
                if (addressed != null) {
                    pendingBotWork.remove(addressed);
                }
                remember(message);
            }
            ui.onPartyMessage(view(message));
            return;
        }
        synchronized (this) {
            remember(message);
        }
        ui.onPartyMessage(view(message));
        if (!fromHistory) {
            maybeHostBot(message);
        }
    }

    private synchronized void remember(GroupChatMessage message) {
        recentMessages.add(message);
        if (recentMessages.size() > RECENT_LIMIT) {
            recentMessages.remove(0);
        }
    }

    private PartyMessageView view(GroupChatMessage message) {
        String senderId = message.getSenderParticipantId();
        if (message.isBotMessage()) {
            return new PartyMessageView(message, GroupChatBot.DISPLAY_NAME,
                    MentionParser.BOT_HANDLE, null, true, false);
        }
        String displayName = senderId;
        String handle = senderId;
        for (Participant participant : transport.getParticipants()) {
            if (participant.getParticipantId().equals(senderId)) {
                displayName = participant.getDisplayName();
                handle = participant.getMentionHandle();
                break;
            }
        }
        boolean local;
        synchronized (this) {
            local = self.getParticipantId().equals(senderId);
            if (local) {
                displayName = self.getDisplayName();
                handle = self.getMentionHandle();
            }
        }
        return new PartyMessageView(message, displayName, handle,
                transport.getColorMap().colorOf(senderId), false, local);
    }

    // ------------------------------------------------------------------ bot hosting

    private void maybeHostBot(GroupChatMessage message) {
        if (botResponder == null || PartySettings.BOT_POLICY_OFF.equals(botPolicy.get())) {
            return; // "off" never answers
        }
        String requestedModel = mentionedModel(message.getMarkdown());
        boolean always = PartySettings.BOT_POLICY_ALWAYS.equals(botPolicy.get());
        // Default policy: only explicit mentions; "always" sees every message and the model
        // itself decides (it may decline with the silent marker → onNoAnswer).
        if (!always && !MentionParser.mentionsBot(message.getMarkdown()) && requestedModel == null) {
            return;
        }
        List<Participant> members = transport.getParticipants();
        String elected = BotElection.electBotHost(members);
        synchronized (this) {
            pendingBotWork.put(message.getMessageId(), elected);
        }
        String selfId;
        synchronized (this) {
            selfId = self.getParticipantId();
        }
        if (selfId.equals(elected) && botResponder.isReady()) {
            claimAndRespond(message, members, requestedModel);
        }
    }

    /**
     * A specific model addressed by name ({@code @gemma4:e2b} style), or {@code null} when the
     * message only mentions {@code @AskAI} (or no bot at all).
     */
    private String mentionedModel(String markdown) {
        if (botResponder == null) {
            return null;
        }
        List<String> models = botResponder.modelMentionHandles();
        if (models.isEmpty()) {
            return null;
        }
        for (String token : MentionParser.mentionTokens(markdown)) {
            String name = token.substring(1);
            for (String model : models) {
                if (model.equalsIgnoreCase(name)) {
                    return model;
                }
            }
        }
        return null;
    }

    private void claimAndRespond(final GroupChatMessage addressed, List<Participant> members,
                                 String requestedModel) {
        final String selfId;
        synchronized (this) {
            selfId = self.getParticipantId();
        }
        BotClaim claim = new BotClaim(UUID.randomUUID().toString(), addressed.getMessageId(),
                BotElection.viewId(members), selfId, System.currentTimeMillis());
        if (!arbiter.acceptClaim(claim, selfId)) {
            return; // someone else already claimed this message
        }
        transport.publishBotClaim(claim);
        List<GroupChatMessage> context;
        Map<String, Participant> profiles = new HashMap<String, Participant>();
        synchronized (this) {
            context = new ArrayList<GroupChatMessage>(recentMessages);
        }
        for (Participant participant : transport.getParticipants()) {
            profiles.put(participant.getParticipantId(), participant);
        }
        botResponder.respond(context, addressed, profiles, requestedModel, new BotResponder.Callback() {
            public void onThinkingDelta(String delta) {
                ui.onBotThinkingDelta(delta);
            }

            public void onResponse(String markdown) {
                ui.onBotThinkingDone();
                if (arbiter.hasResponse(addressed.getMessageId())) {
                    return; // a merge delivered another host's answer first
                }
                GroupChatMessage response = new GroupChatMessage.Builder()
                        .messageId(UUID.randomUUID().toString())
                        .roomId(room.getRoomId())
                        .senderParticipantId(GroupChatBot.PARTICIPANT_ID)
                        .senderSequence(System.currentTimeMillis())
                        .replyToMessageId(addressed.getMessageId())
                        .botHostParticipantId(selfId)
                        .markdown(markdown)
                        .build();
                transport.send(response);
            }

            public void onNoAnswer() {
                // The model deliberately stayed silent (always policy); nothing to broadcast and
                // no failover retry needed.
                ui.onBotThinkingDone();
                synchronized (PartySession.this) {
                    pendingBotWork.remove(addressed.getMessageId());
                }
            }

            public void onFailure(Exception error) {
                ui.onBotThinkingDone();
                ui.onInfoLine("@" + GroupChatBot.DISPLAY_NAME + " could not answer: "
                        + (error.getMessage() == null ? error.toString() : error.getMessage()));
            }
        });
    }

    /** Re-elect and take over unanswered bot work whose claim holder disappeared. */
    private void failoverBotWork(Participant departed) {
        List<Map.Entry<String, String>> pending;
        synchronized (this) {
            pending = new ArrayList<Map.Entry<String, String>>(pendingBotWork.entrySet());
        }
        for (Map.Entry<String, String> entry : pending) {
            String addressedId = entry.getKey();
            String claimHost = entry.getValue();
            if (claimHost == null || !claimHost.equals(departed.getParticipantId())
                    || arbiter.hasResponse(addressedId)) {
                continue;
            }
            arbiter.releaseClaim(addressedId, claimHost);
            List<Participant> members = transport.getParticipants();
            String elected = BotElection.electBotHost(members);
            synchronized (this) {
                pendingBotWork.put(addressedId, elected);
            }
            String selfId;
            synchronized (this) {
                selfId = self.getParticipantId();
            }
            if (selfId.equals(elected) && botResponder != null && botResponder.isReady()) {
                GroupChatMessage addressed = findRecent(addressedId);
                if (addressed != null) {
                    claimAndRespond(addressed, members, mentionedModel(addressed.getMarkdown()));
                }
            }
        }
    }

    private synchronized GroupChatMessage findRecent(String messageId) {
        for (GroupChatMessage message : recentMessages) {
            if (message.getMessageId().equals(messageId)) {
                return message;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ transport listener

    private final class SessionListener implements GroupChatListener {
        public void onMessage(GroupChatMessage message) {
            handleIncoming(message, false);
        }

        public void onParticipantJoined(Participant participant) {
            ui.onInfoLine("@" + participant.getMentionHandle() + " joined the party");
        }

        public void onParticipantLeft(Participant participant) {
            ui.onInfoLine(participant.getDisplayName() + " left the party");
            failoverBotWork(participant);
        }

        public void onParticipantsChanged(List<Participant> participants) {
            ui.onHandlesChanged(mentionHandles());
        }

        public void onConnectionStateChanged(GroupChatConnectionState state) {
            ui.onStatus(state);
        }

        @Override
        public void onColorMapChanged(ColorMap colorMap) {
            // Colors apply to newly rendered messages; existing bubbles keep their color.
            ui.onHandlesChanged(mentionHandles());
        }

        @Override
        public void onBotClaim(BotClaim claim) {
            List<Participant> members = transport.getParticipants();
            String elected = BotElection.electBotHost(members);
            if (arbiter.acceptClaim(claim, elected)) {
                synchronized (PartySession.this) {
                    pendingBotWork.put(claim.getAddressedMessageId(), claim.getBotHostParticipantId());
                }
            }
        }
    }
}
