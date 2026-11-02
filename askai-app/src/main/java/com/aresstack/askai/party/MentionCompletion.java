package com.aresstack.askai.party;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Pure {@code @}-mention completion logic for the composer, kept Swing-free for testability.
 *
 * <p>Completion is active when the caret sits directly after an {@code @token} whose {@code @} is
 * at the text start or preceded by whitespace. Suggestions are the mention handles (humans plus
 * the reserved {@code AskAI} bot handle) whose prefix matches the typed query,
 * case-insensitively.</p>
 */
public final class MentionCompletion {

    /** One computed completion state: where the token starts and what to suggest. */
    public static final class Result {
        private final int tokenStart;
        private final String query;
        private final List<String> suggestions;

        Result(int tokenStart, String query, List<String> suggestions) {
            this.tokenStart = tokenStart;
            this.query = query;
            this.suggestions = Collections.unmodifiableList(suggestions);
        }

        /** Index of the {@code @} character the completion replaces from. */
        public int getTokenStart() {
            return tokenStart;
        }

        /** The text typed after {@code @} up to the caret. */
        public String getQuery() {
            return query;
        }

        /** Matching handles in case-insensitive alphabetical order; never {@code null}. */
        public List<String> getSuggestions() {
            return suggestions;
        }
    }

    private MentionCompletion() {
    }

    /**
     * Compute the completion state for {@code text} with the caret at {@code caret}.
     *
     * @return the completion result, or {@code null} when the caret is not inside a mention token
     *         or nothing matches
     */
    public static Result compute(String text, int caret, List<String> handles) {
        if (text == null || handles == null || handles.isEmpty()
                || caret < 1 || caret > text.length()) {
            return null;
        }
        int at = -1;
        for (int i = caret - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '@') {
                at = i;
                break;
            }
            if (Character.isWhitespace(c)) {
                return null;
            }
        }
        if (at < 0 || (at > 0 && !Character.isWhitespace(text.charAt(at - 1)))) {
            return null;
        }
        String query = text.substring(at + 1, caret);
        String lowered = query.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<String>();
        for (String handle : handles) {
            if (handle != null && handle.toLowerCase(Locale.ROOT).startsWith(lowered)) {
                matches.add(handle);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        Collections.sort(matches, new Comparator<String>() {
            public int compare(String a, String b) {
                return a.compareToIgnoreCase(b);
            }
        });
        return new Result(at, query, matches);
    }

    /**
     * Apply a chosen handle to {@code text}: replaces from the token's {@code @} to {@code caret}
     * with {@code @Handle } (with a trailing space).
     *
     * @return the new text; the new caret is {@link #caretAfterApply}
     */
    public static String apply(String text, int caret, Result result, String handle) {
        return text.substring(0, result.getTokenStart()) + "@" + handle + " " + text.substring(caret);
    }

    /** @return the caret position after {@link #apply} inserted {@code @Handle }. */
    public static int caretAfterApply(Result result, String handle) {
        return result.getTokenStart() + handle.length() + 2;
    }
}
