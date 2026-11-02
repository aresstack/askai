package com.aresstack.askai.groupchat;

import java.util.List;

/**
 * Local append-only log of a room's messages.
 *
 * <p>Every client keeps its own log; on join/reconnect missing ranges are requested from reachable
 * peers, deduplicated by message ID and appended. There is no central server: if no reachable
 * participant holds an old message and the local log has no copy, that history cannot be
 * reconstructed — the UI states this limitation.</p>
 */
public interface RoomHistoryLog {

    /** Append a message; duplicates (same messageId) are ignored. */
    void append(GroupChatMessage message);

    /** @return all stored messages in deterministic {@link HistoryMerger} order. */
    List<GroupChatMessage> readAll();

    /** @return all stored messages with {@code createdAt >= sinceMillis} in deterministic order. */
    List<GroupChatMessage> readSince(long sinceMillis);

    /** @return {@code true} when a message with this ID is already stored. */
    boolean contains(String messageId);

    /** Flush and release resources. */
    void close();
}
