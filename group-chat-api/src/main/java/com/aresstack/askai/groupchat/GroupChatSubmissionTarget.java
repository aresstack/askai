package com.aresstack.askai.groupchat;

/**
 * Port through which the UI submits messages in Partying mode.
 *
 * <p>Implementations must not expose JGroups types in their signatures.  The in-memory
 * implementation is used during G1; a JGroups-backed implementation ships with G2.</p>
 */
public interface GroupChatSubmissionTarget {

    /**
     * Submit a user-authored message to the current room.
     *
     * @param markdown the raw Markdown text as entered in the composer
     * @return {@code true} when the message was accepted (queued or sent); {@code false} if the
     *         target is not ready (e.g. not yet joined).  The composer must NOT be cleared unless
     *         this method returns {@code true}.
     */
    boolean submitMessage(String markdown);

    /**
     * Returns {@code true} when this target is ready to accept messages (i.e. the participant has
     * joined a room and the transport is connected).
     */
    boolean isReady();
}
