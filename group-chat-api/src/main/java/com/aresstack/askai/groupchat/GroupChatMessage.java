package com.aresstack.askai.groupchat;

import java.util.Collections;
import java.util.List;

/**
 * Immutable group-chat message exchanged between participants.
 *
 * <p>Every message has a stable {@link #getMessageId() messageId} so duplicates can be detected
 * and discarded.  The sender's sequence number allows per-sender ordering to be reconstructed
 * after network partitions.</p>
 */
public final class GroupChatMessage {

    private final String messageId;
    private final String roomId;
    private final String senderParticipantId;
    private final long senderSequence;
    private final long createdAt;
    private final String replyToMessageId;
    private final List<String> mentionedParticipantIds;
    private final String markdown;
    private final String botHostParticipantId;

    private GroupChatMessage(Builder builder) {
        this.messageId = builder.messageId;
        this.roomId = builder.roomId;
        this.senderParticipantId = builder.senderParticipantId;
        this.senderSequence = builder.senderSequence;
        this.createdAt = builder.createdAt;
        this.replyToMessageId = builder.replyToMessageId;
        this.mentionedParticipantIds = builder.mentionedParticipantIds != null
                ? Collections.unmodifiableList(new java.util.ArrayList<String>(builder.mentionedParticipantIds))
                : Collections.<String>emptyList();
        this.markdown = builder.markdown != null ? builder.markdown : "";
        this.botHostParticipantId = builder.botHostParticipantId;
    }

    public String getMessageId() { return messageId; }
    public String getRoomId() { return roomId; }
    public String getSenderParticipantId() { return senderParticipantId; }
    public long getSenderSequence() { return senderSequence; }
    public long getCreatedAt() { return createdAt; }

    /** The message being replied to, or {@code null} if this is a top-level message. */
    public String getReplyToMessageId() { return replyToMessageId; }

    /** Participant IDs explicitly @-mentioned in the message body. */
    public List<String> getMentionedParticipantIds() { return mentionedParticipantIds; }

    /** Message body in Markdown. */
    public String getMarkdown() { return markdown; }

    /**
     * For messages authored by the logical room bot ({@link GroupChatBot#PARTICIPANT_ID}): the
     * physical peer that executed the model request.  {@code null} for human messages.
     */
    public String getBotHostParticipantId() { return botHostParticipantId; }

    /** @return {@code true} when this message was authored by the logical room bot. */
    public boolean isBotMessage() { return GroupChatBot.PARTICIPANT_ID.equals(senderParticipantId); }

    @Override
    public String toString() {
        return "GroupChatMessage{id=" + messageId + ", sender=" + senderParticipantId + "}";
    }

    /** Fluent builder for {@link GroupChatMessage}. */
    public static final class Builder {
        private String messageId;
        private String roomId;
        private String senderParticipantId;
        private long senderSequence;
        private long createdAt = System.currentTimeMillis();
        private String replyToMessageId;
        private List<String> mentionedParticipantIds;
        private String markdown;
        private String botHostParticipantId;

        public Builder messageId(String messageId) { this.messageId = messageId; return this; }
        public Builder roomId(String roomId) { this.roomId = roomId; return this; }
        public Builder senderParticipantId(String id) { this.senderParticipantId = id; return this; }
        public Builder senderSequence(long seq) { this.senderSequence = seq; return this; }
        public Builder createdAt(long millis) { this.createdAt = millis; return this; }
        public Builder replyToMessageId(String id) { this.replyToMessageId = id; return this; }
        public Builder mentionedParticipantIds(List<String> ids) { this.mentionedParticipantIds = ids; return this; }
        public Builder markdown(String md) { this.markdown = md; return this; }
        public Builder botHostParticipantId(String id) { this.botHostParticipantId = id; return this; }

        public GroupChatMessage build() {
            if (messageId == null || messageId.trim().isEmpty()) {
                throw new IllegalStateException("messageId is required");
            }
            if (roomId == null || roomId.trim().isEmpty()) {
                throw new IllegalStateException("roomId is required");
            }
            if (senderParticipantId == null || senderParticipantId.trim().isEmpty()) {
                throw new IllegalStateException("senderParticipantId is required");
            }
            return new GroupChatMessage(this);
        }
    }
}
