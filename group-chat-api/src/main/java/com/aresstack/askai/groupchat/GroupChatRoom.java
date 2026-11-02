package com.aresstack.askai.groupchat;

/**
 * Immutable identity record for a group-chat room.
 *
 * <p>The {@link #getRoomSecret() roomSecret} must be known to join; knowing the roomId alone is
 * not sufficient.</p>
 */
public final class GroupChatRoom {

    private final String roomId;
    private final String displayName;
    private final String roomSecret;

    public GroupChatRoom(String roomId, String displayName, String roomSecret) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        this.roomId = roomId;
        this.displayName = displayName != null ? displayName : roomId;
        this.roomSecret = roomSecret;
    }

    /** Stable room identifier used in message routing. */
    public String getRoomId() {
        return roomId;
    }

    /** Human-readable room name shown in the UI. */
    public String getDisplayName() {
        return displayName;
    }

    /**
     * Invitation / room secret required to join.  May be {@code null} for unsecured rooms, but
     * production implementations should always set this.
     */
    public String getRoomSecret() {
        return roomSecret;
    }

    @Override
    public String toString() {
        return "GroupChatRoom{id=" + roomId + ", name=" + displayName + "}";
    }
}
