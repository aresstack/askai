package com.aresstack.askai.plugin.host;

import com.aresstack.askai.history.ChatHistoryStore;
import com.aresstack.askai.history.ChatMessageRecord;
import com.aresstack.askai.history.ChatRecord;
import com.aresstack.askai.plugin.api.service.ChatMessageSnapshot;
import com.aresstack.askai.plugin.api.service.ChatSessionHistoryReader;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The host implementation of {@link ChatSessionHistoryReader}: it reads the SAME {@link ChatHistoryStore} the
 * chat tabs persist into — there is no second message store anywhere, and therefore no second truth that
 * could drift from what the user sees.
 * <p>
 * Reads are live: every persisted message is saved immediately by the chat panel, so a message of the
 * running conversation is visible here right after it appeared in the UI. Store access is synchronized
 * internally, so an MCP worker may call this while the EDT is writing.
 */
public final class LocalChatSessionHistoryReader implements ChatSessionHistoryReader {

    private final ChatHistoryStore historyStore;

    public LocalChatSessionHistoryReader(ChatHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    @Override
    public List<ChatMessageSnapshot> readMessages(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        ChatRecord record = historyStore.load(sessionId.trim());
        if (record == null) {
            return Collections.emptyList();
        }
        List<ChatMessageSnapshot> messages = new ArrayList<ChatMessageSnapshot>();
        for (ChatMessageRecord message : record.getMessages()) {
            messages.add(new ChatMessageSnapshot(message.getMessageId(), message.getRole(),
                    message.getText(), message.getCreatedAt(), message.getModel()));
        }
        return messages;
    }
}
