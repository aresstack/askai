package com.aresstack.askai.ui;

/**
 * Derives a compact summary from the reasoning text without any extra model call: it collapses
 * whitespace and takes the first sentence (or a truncated prefix), capped in length. When nothing usable
 * remains it returns a neutral fallback.
 */
public final class DefaultThinkingSummaryProvider implements ThinkingSummaryProvider {

    private static final int MAX_LENGTH = 110;
    private static final String FALLBACK = "Antwort vorbereitet";

    public String createSummary(String thinkingText) {
        if (thinkingText == null) {
            return FALLBACK;
        }
        String collapsed = thinkingText.replaceAll("\\s+", " ").trim();
        if (collapsed.isEmpty()) {
            return FALLBACK;
        }
        String candidate = firstSentence(collapsed);
        if (candidate.length() > MAX_LENGTH) {
            candidate = candidate.substring(0, MAX_LENGTH).trim() + "…";
        }
        // Too short to be meaningful (e.g. a stray token) → neutral text instead.
        return candidate.length() < 3 ? FALLBACK : candidate;
    }

    private static String firstSentence(String text) {
        for (int i = 0; i < text.length() && i <= MAX_LENGTH; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && i + 1 <= text.length()) {
                return text.substring(0, i + 1).trim();
            }
        }
        return text;
    }
}
