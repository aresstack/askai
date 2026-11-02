package com.aresstack.askai.ui;

/**
 * Produces the short summary that floats up when a thinking bubble bursts. Ollama gives no summary value,
 * so this is a swappable strategy (a later agent layer may provide a better one). Implementations must not
 * make an extra model call — the full reasoning is already shown in the thinking bubble beforehand.
 */
public interface ThinkingSummaryProvider {

    /** @return a short (≤ ~120 char) summary, or a neutral fallback when none can be derived. */
    String createSummary(String thinkingText);
}
