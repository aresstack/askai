package com.aresstack.askai.groupchat;

import java.util.List;

/**
 * Callback interface for incoming group-chat events.
 *
 * <p>All methods are invoked on the transport thread; callers must dispatch to the EDT before
 * touching Swing components.</p>
 */
public interface GroupChatListener {

    /** A new message has arrived (or been replayed from history). */
    void onMessage(GroupChatMessage message);

    /** A participant has joined the room. */
    void onParticipantJoined(Participant participant);

    /** A participant has left the room. */
    void onParticipantLeft(Participant participant);

    /** The current set of participants has changed (e.g. after a reconnect). */
    void onParticipantsChanged(List<Participant> participants);

    /**
     * The transport connection state has changed.
     *
     * <p>The UI derives the text it shows from the structured state rather than from a
     * human-readable string supplied by the transport, keeping display policy in the UI layer.</p>
     */
    void onConnectionStateChanged(GroupChatConnectionState state);

    /**
     * The replicated room color map changed (membership change, preference update or a merge).
     * The default implementation ignores the event.
     */
    default void onColorMapChanged(ColorMap colorMap) {
    }

    /**
     * A peer published a claim to produce the bot response for an addressed message.
     * The default implementation ignores the event.
     */
    default void onBotClaim(BotClaim claim) {
    }
}
