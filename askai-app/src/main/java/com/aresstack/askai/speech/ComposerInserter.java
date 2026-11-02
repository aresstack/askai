package com.aresstack.askai.speech;

/**
 * Pure text logic for inserting a transcript into the chat composer at the caret: existing text is
 * preserved, the transcript replaces the current selection (if any), separating spaces are added when
 * needed, empty transcripts are ignored, and the caret ends up directly behind the inserted words.
 * Kept Swing-free so it is unit-testable; the panel applies the result to its text component.
 */
public final class ComposerInserter {

    /** The new composer text and where the caret should be placed afterwards. */
    public static final class Insertion {
        private final String text;
        private final int caret;

        public Insertion(String text, int caret) {
            this.text = text;
            this.caret = caret;
        }

        public String getText() {
            return text;
        }

        public int getCaret() {
            return caret;
        }
    }

    private ComposerInserter() {
    }

    /**
     * @param existing  the current composer text
     * @param selStart  selection start (caret when no selection)
     * @param selEnd    selection end (== selStart when no selection)
     * @param transcript the recognized text to insert (trimmed; empty is ignored)
     * @return the resulting text and caret position
     */
    public static Insertion insert(String existing, int selStart, int selEnd, String transcript) {
        String current = existing == null ? "" : existing;
        int start = clamp(selStart, current.length());
        int end = clamp(selEnd, current.length());
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        String words = transcript == null ? "" : transcript.trim();
        if (words.isEmpty()) {
            return new Insertion(current, start); // never insert an empty transcription
        }

        String before = current.substring(0, start);
        String after = current.substring(end);
        boolean leadingSpace = before.length() > 0 && !endsWithWhitespace(before);
        boolean trailingSpace = after.length() > 0 && !startsWithWhitespace(after);

        StringBuilder inserted = new StringBuilder();
        if (leadingSpace) {
            inserted.append(' ');
        }
        int caretAfterWords = before.length() + inserted.length() + words.length();
        inserted.append(words);
        if (trailingSpace) {
            inserted.append(' ');
        }
        String text = before + inserted + after;
        return new Insertion(text, caretAfterWords);
    }

    private static boolean endsWithWhitespace(String value) {
        return Character.isWhitespace(value.charAt(value.length() - 1));
    }

    private static boolean startsWithWhitespace(String value) {
        return Character.isWhitespace(value.charAt(0));
    }

    private static int clamp(int index, int length) {
        if (index < 0) {
            return 0;
        }
        return Math.min(index, length);
    }
}
