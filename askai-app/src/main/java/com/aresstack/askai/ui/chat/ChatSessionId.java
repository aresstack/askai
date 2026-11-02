package com.aresstack.askai.ui.chat;

import java.util.UUID;

/**
 * Stable identity of a single chat session: a UUID that is the business key for the session's transcript,
 * model/mode selection, running task, tab mapping and any later persistence. Never derived from a tab
 * index (indices shift when tabs close).
 */
public final class ChatSessionId {

    private final UUID value;

    public ChatSessionId(UUID value) {
        if (value == null) {
            throw new IllegalArgumentException("Chat session ID must not be null.");
        }
        this.value = value;
    }

    public static ChatSessionId create() {
        return new ChatSessionId(UUID.randomUUID());
    }

    public UUID getValue() {
        return value;
    }

    /** @return a short, human-friendly prefix for compact tab titles (the full id stays in the tooltip). */
    public String shortLabel() {
        String text = value.toString();
        return text.length() > 8 ? text.substring(0, 8) : text;
    }

    @Override
    public String toString() {
        return value.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ChatSessionId)) {
            return false;
        }
        return value.equals(((ChatSessionId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
