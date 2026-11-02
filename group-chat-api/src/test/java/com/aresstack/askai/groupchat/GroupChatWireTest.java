package com.aresstack.askai.groupchat;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class GroupChatWireTest {

    // ------------------------------------------------------------------ envelope

    @Test
    public void envelopeRoundTrip() throws IOException {
        byte[] payload = new byte[]{1, 2, 3, 4};
        byte[] signature = new byte[]{9, 8};
        WireEnvelope envelope = new WireEnvelope(GroupChatWire.PROTOCOL_VERSION,
                GroupChatWire.TYPE_MESSAGE, "room-1", 42L, payload, signature);
        WireEnvelope decoded = GroupChatWire.decodeEnvelope(GroupChatWire.encodeEnvelope(envelope));
        assertEquals(GroupChatWire.PROTOCOL_VERSION, decoded.getProtocolVersion());
        assertEquals(GroupChatWire.TYPE_MESSAGE, decoded.getType());
        assertEquals("room-1", decoded.getRoomId());
        assertEquals(42L, decoded.getRoomEpoch());
        assertArrayEquals(payload, decoded.getPayload());
        assertArrayEquals(signature, decoded.getSignature());
    }

    @Test
    public void envelopeWithNullSignatureBecomesEmpty() throws IOException {
        WireEnvelope envelope = new WireEnvelope(GroupChatWire.PROTOCOL_VERSION,
                GroupChatWire.TYPE_LEAVE, "room-1", 0L, null, null);
        WireEnvelope decoded = GroupChatWire.decodeEnvelope(GroupChatWire.encodeEnvelope(envelope));
        assertEquals(0, decoded.getPayload().length);
        assertEquals(0, decoded.getSignature().length);
    }

    @Test(expected = IOException.class)
    public void envelopeUnsupportedProtocolVersionThrows() throws IOException {
        byte[] bytes = GroupChatWire.encodeEnvelope(new WireEnvelope(GroupChatWire.PROTOCOL_VERSION,
                GroupChatWire.TYPE_MESSAGE, "room-1", 0L, null, null));
        bytes[3] = 99; // patch the protocol version int (big-endian low byte)
        GroupChatWire.decodeEnvelope(bytes);
    }

    @Test(expected = IOException.class)
    public void envelopeMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeEnvelope(new byte[]{0, 0, 0, 1, 7});
    }

    // ------------------------------------------------------------------ message

    @Test
    public void messageRoundTripAllFields() throws IOException {
        GroupChatMessage message = new GroupChatMessage.Builder()
                .messageId("m1").roomId("room-1").senderParticipantId(GroupChatBot.PARTICIPANT_ID)
                .senderSequence(7).createdAt(123456789L)
                .replyToMessageId("m0")
                .mentionedParticipantIds(Arrays.asList("alice", "bob"))
                .markdown("**Hello** _world_")
                .botHostParticipantId("host-1")
                .build();
        GroupChatMessage decoded = GroupChatWire.decodeMessage(GroupChatWire.encodeMessage(message));
        assertEquals("m1", decoded.getMessageId());
        assertEquals("room-1", decoded.getRoomId());
        assertEquals(GroupChatBot.PARTICIPANT_ID, decoded.getSenderParticipantId());
        assertEquals(7, decoded.getSenderSequence());
        assertEquals(123456789L, decoded.getCreatedAt());
        assertEquals("m0", decoded.getReplyToMessageId());
        assertEquals(Arrays.asList("alice", "bob"), decoded.getMentionedParticipantIds());
        assertEquals("**Hello** _world_", decoded.getMarkdown());
        assertEquals("host-1", decoded.getBotHostParticipantId());
        assertTrue(decoded.isBotMessage());
    }

    @Test
    public void messageRoundTripNullOptionalsAndEmptyMentions() throws IOException {
        GroupChatMessage message = new GroupChatMessage.Builder()
                .messageId("m1").roomId("room-1").senderParticipantId("alice")
                .senderSequence(1).createdAt(1L).markdown("")
                .build();
        GroupChatMessage decoded = GroupChatWire.decodeMessage(GroupChatWire.encodeMessage(message));
        assertNull(decoded.getReplyToMessageId());
        assertNull(decoded.getBotHostParticipantId());
        assertTrue(decoded.getMentionedParticipantIds().isEmpty());
        assertEquals("", decoded.getMarkdown());
    }

    @Test
    public void messageRoundTripMarkdownOver64Kb() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 10_000; i++) {
            body.append("0123456789 äöü € "); // multi-byte UTF-8 well past the 64 KB writeUTF limit
        }
        String markdown = body.toString();
        assertTrue(markdown.getBytes("UTF-8").length > 64 * 1024);
        GroupChatMessage message = new GroupChatMessage.Builder()
                .messageId("m-big").roomId("room-1").senderParticipantId("alice")
                .senderSequence(1).createdAt(1L).markdown(markdown)
                .build();
        GroupChatMessage decoded = GroupChatWire.decodeMessage(GroupChatWire.encodeMessage(message));
        assertEquals(markdown, decoded.getMarkdown());
    }

    @Test(expected = IOException.class)
    public void messageMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeMessage(new byte[]{1, 2, 3});
    }

    // ------------------------------------------------------------------ participant

    @Test
    public void participantRoundTripAllFields() throws IOException {
        Participant participant = new Participant("alice-id", "Alice Smith", "AliceSmith",
                "violet", true, true);
        Participant decoded = GroupChatWire.decodeParticipant(GroupChatWire.encodeParticipant(participant));
        assertEquals("alice-id", decoded.getParticipantId());
        assertEquals("Alice Smith", decoded.getDisplayName());
        assertEquals("AliceSmith", decoded.getMentionHandle());
        assertEquals("violet", decoded.getPreferredColor());
        assertTrue(decoded.isBotCapable());
        assertTrue(decoded.isBotReady());
    }

    @Test
    public void participantRoundTripNullPreferredColor() throws IOException {
        Participant participant = new Participant("bob-id", "Bob", "Bob", null, false, false);
        Participant decoded = GroupChatWire.decodeParticipant(GroupChatWire.encodeParticipant(participant));
        assertNull(decoded.getPreferredColor());
        assertFalse(decoded.isBotCapable());
        assertFalse(decoded.isBotReady());
    }

    @Test(expected = IOException.class)
    public void participantMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeParticipant(new byte[]{0});
    }

    // ------------------------------------------------------------------ color map

    @Test
    public void colorMapRoundTripWithLeases() throws IOException {
        Map<String, String> assignments = new LinkedHashMap<String, String>();
        assignments.put("alice", "green");
        assignments.put("bob", "violet");
        Map<String, ColorLease> leases = new LinkedHashMap<String, ColorLease>();
        leases.put("red", new ColorLease("carol", 999L));
        ColorMap map = new ColorMap(7, ParticipantColorPalette.VERSION, assignments, leases);
        ColorMap decoded = GroupChatWire.decodeColorMap(GroupChatWire.encodeColorMap(map));
        assertEquals(7, decoded.getVersion());
        assertEquals(ParticipantColorPalette.VERSION, decoded.getPaletteVersion());
        assertEquals(assignments, decoded.getAssignments());
        assertEquals("carol", decoded.getLeases().get("red").getParticipantId());
        assertEquals(999L, decoded.getLeases().get("red").getExpiresAtMillis());
    }

    @Test
    public void colorMapRoundTripEmpty() throws IOException {
        ColorMap decoded = GroupChatWire.decodeColorMap(GroupChatWire.encodeColorMap(ColorMap.EMPTY));
        assertEquals(0, decoded.getVersion());
        assertTrue(decoded.getAssignments().isEmpty());
        assertTrue(decoded.getLeases().isEmpty());
    }

    @Test(expected = IOException.class)
    public void colorMapMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeColorMap(new byte[]{5, 5});
    }

    // ------------------------------------------------------------------ bot claim

    @Test
    public void botClaimRoundTrip() throws IOException {
        BotClaim claim = new BotClaim("c1", "m1", "alice,bob", "alice", 555L);
        BotClaim decoded = GroupChatWire.decodeBotClaim(GroupChatWire.encodeBotClaim(claim));
        assertEquals("c1", decoded.getClaimId());
        assertEquals("m1", decoded.getAddressedMessageId());
        assertEquals("alice,bob", decoded.getMembershipViewId());
        assertEquals("alice", decoded.getBotHostParticipantId());
        assertEquals(555L, decoded.getCreatedAt());
    }

    @Test(expected = IOException.class)
    public void botClaimMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeBotClaim(new byte[]{1});
    }

    // ------------------------------------------------------------------ history

    @Test
    public void historyRequestRoundTrip() throws IOException {
        assertEquals(123456789L,
                GroupChatWire.decodeHistoryRequest(GroupChatWire.encodeHistoryRequest(123456789L)));
        assertEquals(0L, GroupChatWire.decodeHistoryRequest(GroupChatWire.encodeHistoryRequest(0L)));
    }

    @Test(expected = IOException.class)
    public void historyRequestMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeHistoryRequest(new byte[]{1, 2});
    }

    @Test
    public void messageListRoundTrip() throws IOException {
        GroupChatMessage m1 = new GroupChatMessage.Builder()
                .messageId("m1").roomId("room-1").senderParticipantId("alice")
                .senderSequence(1).createdAt(100L).markdown("one").build();
        GroupChatMessage m2 = new GroupChatMessage.Builder()
                .messageId("m2").roomId("room-1").senderParticipantId("bob")
                .senderSequence(1).createdAt(200L).replyToMessageId("m1").markdown("two").build();
        List<GroupChatMessage> decoded = GroupChatWire.decodeMessageList(
                GroupChatWire.encodeMessageList(Arrays.asList(m1, m2)));
        assertEquals(2, decoded.size());
        assertEquals("m1", decoded.get(0).getMessageId());
        assertEquals("m2", decoded.get(1).getMessageId());
        assertEquals("m1", decoded.get(1).getReplyToMessageId());
    }

    @Test
    public void messageListRoundTripEmpty() throws IOException {
        assertTrue(GroupChatWire.decodeMessageList(
                GroupChatWire.encodeMessageList(Collections.<GroupChatMessage>emptyList())).isEmpty());
    }

    @Test(expected = IOException.class)
    public void messageListMalformedBytesThrow() throws IOException {
        GroupChatWire.decodeMessageList(new byte[]{0, 0, 0, 5, 1});
    }
}
