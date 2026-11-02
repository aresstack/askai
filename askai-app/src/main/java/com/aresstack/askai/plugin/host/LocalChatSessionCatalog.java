package com.aresstack.askai.plugin.host;

import com.aresstack.askai.history.ChatHistoryStore;
import com.aresstack.askai.history.ChatRecord;
import com.aresstack.askai.plugin.api.service.ChatSessionCatalog;
import com.aresstack.askai.plugin.api.service.ChatSessionMetadata;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The host implementation of the read-only {@link ChatSessionCatalog}: titles come from the SAME
 * {@link ChatHistoryStore} the chat tabs persist into (never a second store), the selected chat is published
 * by the UI.
 * <p>
 * Threading: the selected id is held in an {@link AtomicReference} that the EDT writes on every tab switch,
 * so an MCP worker thread can read it WITHOUT touching Swing. Reading a title goes through the store's own
 * synchronization; a chat that was never persisted yet is reported as "(new chat)" with timestamp 0 rather
 * than as unknown — it exists, it just has no content.
 */
public final class LocalChatSessionCatalog implements ChatSessionCatalog {

    private static final String UNTITLED = "(new chat)";

    private final ChatHistoryStore historyStore;
    private final AtomicReference<String> activeSessionId = new AtomicReference<String>("");

    public LocalChatSessionCatalog(ChatHistoryStore historyStore) {
        this.historyStore = historyStore;
    }

    /** Published by the UI on every tab switch (EDT); read from any thread. */
    public void setActiveSessionId(String sessionId) {
        activeSessionId.set(sessionId == null ? "" : sessionId);
    }

    @Override
    public String getActiveSessionId() {
        return activeSessionId.get();
    }

    @Override
    public ChatSessionMetadata getSession(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return null;
        }
        String id = sessionId.trim();
        ChatRecord record = historyStore.load(id);
        if (record == null) {
            return new ChatSessionMetadata(id, UNTITLED, 0L);
        }
        String title = record.getTitle() == null || record.getTitle().trim().isEmpty()
                ? UNTITLED : record.getTitle().trim();
        return new ChatSessionMetadata(id, title, record.getModifiedAt());
    }
}
