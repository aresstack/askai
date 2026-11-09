package com.aresstack.askai.research.writer;

/**
 * A conservative character-based token estimate (no tokenizer in the pipeline, see PR 20): four characters count
 * as one token. Texts are cut at sentence boundaries so that they fit a token budget.
 */
final class TokenBudget {

    static final int CHARS_PER_TOKEN = 4;

    private TokenBudget() {
    }

    static int estimate(String text) {
        return text == null ? 0 : (text.length() + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN;
    }

    /**
     * The longest prefix of {@code text} that fits {@code tokens} and ends at a sentence boundary. When even the
     * first sentence is too long, it is cut at the last word boundary and marked with an ellipsis.
     */
    static String cutAtSentence(String text, int tokens) {
        String value = text == null ? "" : text.trim().replaceAll("\\s+", " ");
        int maxChars = Math.max(0, tokens) * CHARS_PER_TOKEN;
        if (value.length() <= maxChars) {
            return value;
        }
        int end = -1;
        for (int i = 0; i < maxChars && i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '…')
                    && (i + 1 == value.length() || Character.isWhitespace(value.charAt(i + 1)))) {
                end = i + 1;
            }
        }
        if (end > 0) {
            return value.substring(0, end);
        }
        int limit = Math.max(0, maxChars - 1);
        int space = value.lastIndexOf(' ', limit);
        String head = (space > 0 ? value.substring(0, space) : value.substring(0, limit)).trim();
        return head + "…";
    }
}
