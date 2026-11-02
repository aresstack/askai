package com.aresstack.askai.groupchat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Binary wire codec for all Partying transport payloads.
 *
 * <p>Uses {@link DataOutputStream}/{@link DataInputStream} over byte arrays. Short strings use
 * {@code writeUTF}; the potentially large Markdown body is written as a length-prefixed UTF-8
 * byte block because {@code writeUTF} is limited to 64&nbsp;KB. Nullable fields are prefixed with
 * a presence boolean. All decoders throw {@link IOException} on malformed input.</p>
 */
public final class GroupChatWire {

    /** Wire protocol version; envelopes with a different version are rejected. */
    public static final int PROTOCOL_VERSION = 1;

    /** Envelope payload type: a {@link GroupChatMessage}. */
    public static final int TYPE_MESSAGE = 1;
    /** Envelope payload type: a {@link Participant} profile announcement. */
    public static final int TYPE_PROFILE = 2;
    /** Envelope payload type: a request for the peers' profiles. */
    public static final int TYPE_PROFILE_REQUEST = 3;
    /** Envelope payload type: a history request ({@code sinceMillis}). */
    public static final int TYPE_HISTORY_REQUEST = 4;
    /** Envelope payload type: a history response (message list). */
    public static final int TYPE_HISTORY_RESPONSE = 5;
    /** Envelope payload type: a replicated {@link ColorMap}. */
    public static final int TYPE_COLOR_MAP = 6;
    /** Envelope payload type: a {@link BotClaim}. */
    public static final int TYPE_BOT_CLAIM = 7;
    /** Envelope payload type: an explicit leave announcement. */
    public static final int TYPE_LEAVE = 8;

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** Sanity cap for length-prefixed blocks to avoid absurd allocations on corrupt input. */
    private static final int MAX_BLOCK_BYTES = 32 * 1024 * 1024;

    private GroupChatWire() {
    }

    // ------------------------------------------------------------------ envelope

    /** Encode an envelope to bytes. */
    public static byte[] encodeEnvelope(WireEnvelope envelope) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeInt(envelope.getProtocolVersion());
            out.writeInt(envelope.getType());
            out.writeUTF(envelope.getRoomId());
            out.writeLong(envelope.getRoomEpoch());
            writeBlock(out, envelope.getPayload());
            writeBlock(out, envelope.getSignature());
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /**
     * Decode an envelope.
     *
     * @throws IOException on malformed bytes or an unsupported protocol version
     */
    public static WireEnvelope decodeEnvelope(byte[] data) throws IOException {
        DataInputStream in = input(data);
        try {
            int protocolVersion = in.readInt();
            if (protocolVersion != PROTOCOL_VERSION) {
                throw new IOException("Unsupported protocol version: " + protocolVersion);
            }
            int type = in.readInt();
            String roomId = in.readUTF();
            long roomEpoch = in.readLong();
            byte[] payload = readBlock(in);
            byte[] signature = readBlock(in);
            return new WireEnvelope(protocolVersion, type, roomId, roomEpoch, payload, signature);
        } catch (RuntimeException e) {
            throw new IOException("Malformed envelope", e);
        }
    }

    // ------------------------------------------------------------------ message

    /** Encode a message (all fields including nullable optionals). */
    public static byte[] encodeMessage(GroupChatMessage message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeUTF(message.getMessageId());
            out.writeUTF(message.getRoomId());
            out.writeUTF(message.getSenderParticipantId());
            out.writeLong(message.getSenderSequence());
            out.writeLong(message.getCreatedAt());
            writeNullableString(out, message.getReplyToMessageId());
            List<String> mentions = message.getMentionedParticipantIds();
            out.writeInt(mentions.size());
            for (String mention : mentions) {
                out.writeUTF(mention);
            }
            writeBlock(out, message.getMarkdown().getBytes(UTF_8));
            writeNullableString(out, message.getBotHostParticipantId());
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a message. @throws IOException on malformed bytes */
    public static GroupChatMessage decodeMessage(byte[] data) throws IOException {
        DataInputStream in = input(data);
        try {
            GroupChatMessage.Builder builder = new GroupChatMessage.Builder()
                    .messageId(in.readUTF())
                    .roomId(in.readUTF())
                    .senderParticipantId(in.readUTF())
                    .senderSequence(in.readLong())
                    .createdAt(in.readLong())
                    .replyToMessageId(readNullableString(in));
            int mentionCount = in.readInt();
            if (mentionCount < 0) {
                throw new IOException("Negative mention count: " + mentionCount);
            }
            List<String> mentions = new ArrayList<String>(Math.min(mentionCount, 1024));
            for (int i = 0; i < mentionCount; i++) {
                mentions.add(in.readUTF());
            }
            builder.mentionedParticipantIds(mentions);
            builder.markdown(new String(readBlock(in), UTF_8));
            builder.botHostParticipantId(readNullableString(in));
            return builder.build();
        } catch (RuntimeException e) {
            throw new IOException("Malformed message", e);
        }
    }

    // ------------------------------------------------------------------ participant

    /** Encode a participant profile including bot flags. */
    public static byte[] encodeParticipant(Participant participant) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeUTF(participant.getParticipantId());
            out.writeUTF(participant.getDisplayName());
            out.writeUTF(participant.getMentionHandle());
            writeNullableString(out, participant.getPreferredColor());
            out.writeBoolean(participant.isBotCapable());
            out.writeBoolean(participant.isBotReady());
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a participant profile. @throws IOException on malformed bytes */
    public static Participant decodeParticipant(byte[] data) throws IOException {
        DataInputStream in = input(data);
        try {
            String participantId = in.readUTF();
            String displayName = in.readUTF();
            String mentionHandle = in.readUTF();
            String preferredColor = readNullableString(in);
            boolean botCapable = in.readBoolean();
            boolean botReady = in.readBoolean();
            return new Participant(participantId, displayName, mentionHandle, preferredColor,
                    botCapable, botReady);
        } catch (RuntimeException e) {
            throw new IOException("Malformed participant", e);
        }
    }

    // ------------------------------------------------------------------ color map

    /** Encode a color map including leases. */
    public static byte[] encodeColorMap(ColorMap colorMap) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeLong(colorMap.getVersion());
            out.writeInt(colorMap.getPaletteVersion());
            Map<String, String> assignments = colorMap.getAssignments();
            out.writeInt(assignments.size());
            for (Map.Entry<String, String> entry : assignments.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue());
            }
            Map<String, ColorLease> leases = colorMap.getLeases();
            out.writeInt(leases.size());
            for (Map.Entry<String, ColorLease> entry : leases.entrySet()) {
                out.writeUTF(entry.getKey());
                out.writeUTF(entry.getValue().getParticipantId());
                out.writeLong(entry.getValue().getExpiresAtMillis());
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a color map. @throws IOException on malformed bytes */
    public static ColorMap decodeColorMap(byte[] data) throws IOException {
        DataInputStream in = input(data);
        try {
            long version = in.readLong();
            int paletteVersion = in.readInt();
            int assignmentCount = in.readInt();
            if (assignmentCount < 0) {
                throw new IOException("Negative assignment count: " + assignmentCount);
            }
            Map<String, String> assignments = new LinkedHashMap<String, String>();
            for (int i = 0; i < assignmentCount; i++) {
                assignments.put(in.readUTF(), in.readUTF());
            }
            int leaseCount = in.readInt();
            if (leaseCount < 0) {
                throw new IOException("Negative lease count: " + leaseCount);
            }
            Map<String, ColorLease> leases = new LinkedHashMap<String, ColorLease>();
            for (int i = 0; i < leaseCount; i++) {
                String colorToken = in.readUTF();
                leases.put(colorToken, new ColorLease(in.readUTF(), in.readLong()));
            }
            return new ColorMap(version, paletteVersion, assignments, leases);
        } catch (RuntimeException e) {
            throw new IOException("Malformed color map", e);
        }
    }

    // ------------------------------------------------------------------ bot claim

    /** Encode a bot claim. */
    public static byte[] encodeBotClaim(BotClaim claim) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeUTF(claim.getClaimId());
            out.writeUTF(claim.getAddressedMessageId());
            out.writeUTF(claim.getMembershipViewId());
            out.writeUTF(claim.getBotHostParticipantId());
            out.writeLong(claim.getCreatedAt());
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a bot claim. @throws IOException on malformed bytes */
    public static BotClaim decodeBotClaim(byte[] data) throws IOException {
        DataInputStream in = input(data);
        try {
            return new BotClaim(in.readUTF(), in.readUTF(), in.readUTF(), in.readUTF(), in.readLong());
        } catch (RuntimeException e) {
            throw new IOException("Malformed bot claim", e);
        }
    }

    // ------------------------------------------------------------------ history

    /** Encode a history request. */
    public static byte[] encodeHistoryRequest(long sinceMillis) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            out.writeLong(sinceMillis);
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a history request. @throws IOException on malformed bytes */
    public static long decodeHistoryRequest(byte[] data) throws IOException {
        return input(data).readLong();
    }

    /** Encode a list of messages (history response). */
    public static byte[] encodeMessageList(List<GroupChatMessage> messages) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        try {
            List<GroupChatMessage> safe = messages != null
                    ? messages
                    : new ArrayList<GroupChatMessage>();
            out.writeInt(safe.size());
            for (GroupChatMessage message : safe) {
                writeBlock(out, encodeMessage(message));
            }
            out.flush();
        } catch (IOException e) {
            throw new IllegalStateException("in-memory encode failed", e);
        }
        return bytes.toByteArray();
    }

    /** Decode a list of messages. @throws IOException on malformed bytes */
    public static List<GroupChatMessage> decodeMessageList(byte[] data) throws IOException {
        DataInputStream in = input(data);
        try {
            int count = in.readInt();
            if (count < 0) {
                throw new IOException("Negative message count: " + count);
            }
            List<GroupChatMessage> messages = new ArrayList<GroupChatMessage>(Math.min(count, 1024));
            for (int i = 0; i < count; i++) {
                messages.add(decodeMessage(readBlock(in)));
            }
            return messages;
        } catch (RuntimeException e) {
            throw new IOException("Malformed message list", e);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static DataInputStream input(byte[] data) throws IOException {
        if (data == null) {
            throw new IOException("Null wire data");
        }
        return new DataInputStream(new ByteArrayInputStream(data));
    }

    /** Write a presence-prefixed nullable string. */
    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            out.writeUTF(value);
        }
    }

    /** Read a presence-prefixed nullable string. */
    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    /** Write a length-prefixed byte block. */
    private static void writeBlock(DataOutputStream out, byte[] block) throws IOException {
        out.writeInt(block.length);
        out.write(block);
    }

    /** Read a length-prefixed byte block. */
    private static byte[] readBlock(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_BLOCK_BYTES) {
            throw new IOException("Invalid block length: " + length);
        }
        byte[] block = new byte[length];
        in.readFully(block);
        return block;
    }
}
