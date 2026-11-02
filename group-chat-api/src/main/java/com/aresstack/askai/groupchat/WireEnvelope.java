package com.aresstack.askai.groupchat;

/**
 * Immutable transport envelope wrapping one wire payload.
 *
 * <p>Encoded and decoded by {@link GroupChatWire}. The {@code type} selects which payload codec
 * applies; the optional signature authenticates the payload against the room secret (an empty
 * array means unsigned).</p>
 */
public final class WireEnvelope {

    private static final byte[] EMPTY = new byte[0];

    private final int protocolVersion;
    private final int type;
    private final String roomId;
    private final long roomEpoch;
    private final byte[] payload;
    private final byte[] signature;

    /**
     * @param protocolVersion wire protocol version ({@link GroupChatWire#PROTOCOL_VERSION})
     * @param type            payload type, one of the {@code GroupChatWire.TYPE_*} constants
     * @param roomId          target room ID (must not be blank)
     * @param roomEpoch       room epoch the sender was in when publishing
     * @param payload         encoded payload bytes ({@code null} treated as empty)
     * @param signature       payload signature ({@code null} treated as empty)
     */
    public WireEnvelope(int protocolVersion, int type, String roomId, long roomEpoch,
                        byte[] payload, byte[] signature) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        this.protocolVersion = protocolVersion;
        this.type = type;
        this.roomId = roomId;
        this.roomEpoch = roomEpoch;
        this.payload = payload != null ? payload.clone() : EMPTY;
        this.signature = signature != null ? signature.clone() : EMPTY;
    }

    /** Wire protocol version this envelope was encoded with. */
    public int getProtocolVersion() {
        return protocolVersion;
    }

    /** Payload type, one of the {@code GroupChatWire.TYPE_*} constants. */
    public int getType() {
        return type;
    }

    /** Target room ID. */
    public String getRoomId() {
        return roomId;
    }

    /** Room epoch the sender was in when publishing. */
    public long getRoomEpoch() {
        return roomEpoch;
    }

    /** Encoded payload bytes; never {@code null}. */
    public byte[] getPayload() {
        return payload.clone();
    }

    /** Payload signature; empty when unsigned, never {@code null}. */
    public byte[] getSignature() {
        return signature.clone();
    }

    @Override
    public String toString() {
        return "WireEnvelope{v=" + protocolVersion + ", type=" + type + ", room=" + roomId
                + ", epoch=" + roomEpoch + ", payload=" + payload.length + "b}";
    }
}
