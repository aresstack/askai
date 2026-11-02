package com.aresstack.askai.groupchat;

import java.util.List;

/**
 * Transport-layer abstraction for the Partying mode.
 *
 * <p>Implementations (in-memory for G1, JGroups for G2) must not leak their internal types
 * through this interface.</p>
 */
public interface GroupChatTransport {

    /**
     * Join the given room as the given participant.  The listener receives all subsequent events.
     * Calling join on an already-joined transport reconnects cleanly.
     */
    void join(GroupChatRoom room, Participant self, GroupChatListener listener);

    /** Send a message to all participants in the currently joined room. */
    void send(GroupChatMessage message);

    /** Leave the current room and release transport resources. */
    void leave();

    /** @return the current list of known participants, or an empty list if not joined. */
    List<Participant> getParticipants();

    /** @return {@code true} when joined and the transport considers itself connected. */
    boolean isConnected();

    /**
     * Publish a bot claim to all peers ahead of executing the model request.
     * The default implementation drops the claim (transport without bot support).
     */
    default void publishBotClaim(BotClaim claim) {
    }

    /**
     * Replace this peer's own participant profile (display name, preferred color, bot flags) and
     * announce the change to the room. The default implementation ignores the update.
     */
    default void updateSelf(Participant self) {
    }

    /** @return the latest replicated room color map; {@link ColorMap#EMPTY} when not joined. */
    default ColorMap getColorMap() {
        return ColorMap.EMPTY;
    }

    /**
     * The locally stored room history (append-only log), oldest first.  Transports without
     * persistence return an empty list.  The UI replays this after joining; live events continue
     * seamlessly because duplicates are discarded by message ID.
     */
    default List<GroupChatMessage> localHistory() {
        return java.util.Collections.emptyList();
    }

    /** Delete this room's locally persisted history; no-op when there is no persistence. */
    default void clearHistory() {
    }
}
