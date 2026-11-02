package com.aresstack.askai.groupchat;

/**
 * Immutable identity record for a single Partying participant.
 *
 * <p>A participant is identified by a stable {@link #getParticipantId() participantId} that is
 * persisted locally and does NOT change when the IP address changes. The display name and preferred
 * color are mutable profile attributes that can be updated in Settings.</p>
 *
 * <p>The {@link #getMentionHandle() mentionHandle} is the compact, space-free token used after
 * {@code @} in messages (e.g. {@code @AliceSmith} for display name "Alice Smith"). It is unique
 * within a room and is derived at join time using
 * {@link MentionParser#computeUniqueHandle}.</p>
 */
public final class Participant {

    private final String participantId;
    private final String displayName;
    private final String mentionHandle;
    private final String preferredColor;
    private final boolean botCapable;
    private final boolean botReady;

    /**
     * Construct a participant with an explicit mention handle and bot capability flags.
     *
     * @param participantId  stable UUID-based identity (must not be blank)
     * @param displayName    human-readable display name; falls back to {@code participantId} if null
     * @param mentionHandle  unique, space-free @-mention token; falls back to a derived token if null
     * @param preferredColor preferred palette color token, or {@code null}
     * @param botCapable     whether this peer could host the logical room bot
     * @param botReady       whether this peer is currently ready to host the bot (model reachable)
     */
    public Participant(String participantId, String displayName, String mentionHandle,
                       String preferredColor, boolean botCapable, boolean botReady) {
        if (participantId == null || participantId.trim().isEmpty()) {
            throw new IllegalArgumentException("participantId must not be blank");
        }
        this.participantId = participantId;
        this.displayName = displayName != null ? displayName : participantId;
        String derived = (displayName != null ? displayName : participantId)
                .replaceAll("[^A-Za-z0-9_]", "");
        if (derived.isEmpty()) {
            derived = "User";
        }
        this.mentionHandle = (mentionHandle != null && !mentionHandle.trim().isEmpty())
                ? mentionHandle
                : derived;
        this.preferredColor = preferredColor;
        this.botCapable = botCapable;
        this.botReady = botReady;
    }

    /** Construct a non-bot-capable participant with an explicit mention handle. */
    public Participant(String participantId, String displayName, String mentionHandle, String preferredColor) {
        this(participantId, displayName, mentionHandle, preferredColor, false, false);
    }

    /**
     * Construct a participant without an explicit mention handle; the handle is derived from the
     * display name by stripping non-alphanumeric characters.  Use
     * {@link MentionParser#computeUniqueHandle} to guarantee uniqueness across a room.
     */
    public Participant(String participantId, String displayName, String preferredColor) {
        this(participantId, displayName, null, preferredColor, false, false);
    }

    /** Stable locally-persisted identity; must not be an IP address. */
    public String getParticipantId() {
        return participantId;
    }

    /** Human-readable display name shown in the transcript and participant list. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Compact, space-free token used after {@code @} in messages.  Always non-null and non-empty.
     * Example: display name "Alice Smith" → handle {@code "AliceSmith"}.
     */
    public String getMentionHandle() {
        return mentionHandle;
    }

    /**
     * Preferred palette color token (e.g. {@code "violet"}).  May be {@code null} if no preference
     * has been set.  Actual color assignment is determined by the room's color-map, not this field
     * alone.
     */
    public String getPreferredColor() {
        return preferredColor;
    }

    /** Whether this peer could host the logical room bot at all. */
    public boolean isBotCapable() {
        return botCapable;
    }

    /** Whether this peer is currently ready to host the bot (its model runtime is reachable). */
    public boolean isBotReady() {
        return botReady;
    }

    /** @return a copy of this participant with updated bot flags. */
    public Participant withBotFlags(boolean capable, boolean ready) {
        return new Participant(participantId, displayName, mentionHandle, preferredColor, capable, ready);
    }

    @Override
    public String toString() {
        return "Participant{id=" + participantId + ", name=" + displayName + ", handle=" + mentionHandle + "}";
    }
}
