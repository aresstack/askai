package com.aresstack.askai.groupchat;

/**
 * Typed snapshot of the transport connection state emitted via
 * {@link GroupChatListener#onConnectionStateChanged}.
 *
 * <p>The application UI derives the text it shows the user from this value; the transport never
 * emits human-readable strings so the display policy lives in one place.</p>
 */
public final class GroupChatConnectionState {

    private final boolean connected;
    private final int memberCount;
    private final String errorMessage;

    /**
     * @param connected    {@code true} when the transport is joined and healthy
     * @param memberCount  number of participants currently known in the room (≥ 0)
     * @param errorMessage non-{@code null} when the transport encountered an error, {@code null} otherwise
     */
    public GroupChatConnectionState(boolean connected, int memberCount, String errorMessage) {
        if (memberCount < 0) {
            throw new IllegalArgumentException("memberCount must be >= 0");
        }
        this.connected = connected;
        this.memberCount = memberCount;
        this.errorMessage = errorMessage;
    }

    /** Convenience factory for a healthy connected state. */
    public static GroupChatConnectionState connected(int memberCount) {
        return new GroupChatConnectionState(true, memberCount, null);
    }

    /** Convenience factory for a disconnected/error state. */
    public static GroupChatConnectionState disconnected(String errorMessage) {
        return new GroupChatConnectionState(false, 0, errorMessage);
    }

    /** {@code true} when the transport is joined and healthy. */
    public boolean isConnected() {
        return connected;
    }

    /** Number of participants currently known in the room; 0 when not connected. */
    public int getMemberCount() {
        return memberCount;
    }

    /**
     * A short error description, or {@code null} when there is no error.
     * The UI may display this to the user as supplementary detail.
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** {@code true} when an error description is present. */
    public boolean hasError() {
        return errorMessage != null;
    }

    @Override
    public String toString() {
        return "GroupChatConnectionState{connected=" + connected
                + ", members=" + memberCount
                + (errorMessage != null ? ", error=" + errorMessage : "")
                + "}";
    }
}
