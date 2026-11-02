package com.aresstack.askai.groupchat;

/**
 * Constants for the logical room bot.
 *
 * <p>The bot is one logical participant regardless of which physical peer hosts it; bot messages
 * are always sent with {@link #PARTICIPANT_ID} as sender and carry the executing peer in
 * {@link GroupChatMessage#getBotHostParticipantId()}.</p>
 */
public final class GroupChatBot {

    /** Stable sender ID for all messages authored by the logical room bot. */
    public static final String PARTICIPANT_ID = "bot.askai";

    /** Display name of the logical bot. */
    public static final String DISPLAY_NAME = "AskAI";

    private GroupChatBot() {
    }
}
