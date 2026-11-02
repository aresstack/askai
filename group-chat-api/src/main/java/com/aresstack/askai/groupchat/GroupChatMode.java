package com.aresstack.askai.groupchat;

/**
 * Well-known interaction-mode identifiers used by the mode selector.
 *
 * <p>Yapping is direct chat with the bot; Questing is agent-driven work; Partying is the
 * decentralized LAN group-chat mode where both humans and bots can participate.</p>
 */
public final class GroupChatMode {

    /** Default casual chat with the local bot. */
    public static final String YAPPING = "builtin.yapping";

    /** Agent-driven work mode. */
    public static final String QUESTING = "builtin.questing";

    /** Decentralized LAN group chat with people and bots. */
    public static final String PARTYING = "builtin.partying";

    private GroupChatMode() {
    }
}
