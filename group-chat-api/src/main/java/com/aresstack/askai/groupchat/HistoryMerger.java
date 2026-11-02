package com.aresstack.askai.groupchat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic merge of message histories from different peers.
 *
 * <p>Histories are deduplicated by message ID and sorted with a total order that every peer
 * computes identically, so all transcripts converge after a partition merge.</p>
 */
public final class HistoryMerger {

    /**
     * Deterministic total order over messages: by {@code createdAt}, then sender participant ID,
     * then sender sequence, then message ID.
     */
    public static final Comparator<GroupChatMessage> ORDER = new Comparator<GroupChatMessage>() {
        @Override
        public int compare(GroupChatMessage a, GroupChatMessage b) {
            int result = Long.compare(a.getCreatedAt(), b.getCreatedAt());
            if (result != 0) {
                return result;
            }
            result = a.getSenderParticipantId().compareTo(b.getSenderParticipantId());
            if (result != 0) {
                return result;
            }
            result = Long.compare(a.getSenderSequence(), b.getSenderSequence());
            if (result != 0) {
                return result;
            }
            return a.getMessageId().compareTo(b.getMessageId());
        }
    };

    private HistoryMerger() {
    }

    /**
     * Merge two histories: deduplicate by message ID (first occurrence wins, {@code a} before
     * {@code b}) and sort by {@link #ORDER}.
     */
    public static List<GroupChatMessage> merge(List<GroupChatMessage> a, List<GroupChatMessage> b) {
        Map<String, GroupChatMessage> byId = new LinkedHashMap<String, GroupChatMessage>();
        addAll(byId, a);
        addAll(byId, b);
        return sort(new ArrayList<GroupChatMessage>(byId.values()));
    }

    /** @return a new list containing {@code messages} sorted by {@link #ORDER}. */
    public static List<GroupChatMessage> sort(List<GroupChatMessage> messages) {
        List<GroupChatMessage> sorted = new ArrayList<GroupChatMessage>(
                messages != null ? messages : new ArrayList<GroupChatMessage>());
        sorted.sort(ORDER);
        return sorted;
    }

    private static void addAll(Map<String, GroupChatMessage> byId, List<GroupChatMessage> messages) {
        if (messages == null) {
            return;
        }
        for (GroupChatMessage message : messages) {
            if (message != null && !byId.containsKey(message.getMessageId())) {
                byId.put(message.getMessageId(), message);
            }
        }
    }
}
