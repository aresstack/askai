package com.aresstack.askai.agent.model.port;

/**
 * Whether and how strongly a thinking-capable model reasons before answering. The implementation maps the
 * value onto the provider's wire form verbatim (for Ollama: {@code think} as {@code false}, {@code true}
 * or the level string), and omits it for {@link #DEFAULT}. Models without a thinking mode ignore it;
 * models that only know on/off treat the levels as on.
 */
public enum ThinkingMode {
    /** Do not send a thinking setting; the model's own default applies. */
    DEFAULT,
    OFF,
    ON,
    LOW,
    MEDIUM,
    HIGH
}
