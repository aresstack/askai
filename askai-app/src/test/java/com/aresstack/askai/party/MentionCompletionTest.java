package com.aresstack.askai.party;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Pure-logic tests for the composer's {@code @}-mention completion. */
public class MentionCompletionTest {

    private static final List<String> HANDLES = Arrays.asList("Maria", "AliceSmith", "AskAI", "max");

    @Test
    public void completesAtTokenStart() {
        MentionCompletion.Result result = MentionCompletion.compute("@", 1, HANDLES);
        assertEquals(0, result.getTokenStart());
        assertEquals("", result.getQuery());
        assertEquals(Arrays.asList("AliceSmith", "AskAI", "Maria", "max"), result.getSuggestions());
    }

    @Test
    public void filtersByPrefixCaseInsensitively() {
        MentionCompletion.Result result = MentionCompletion.compute("Hello @ma", 9, HANDLES);
        assertEquals(Arrays.asList("Maria", "max"), result.getSuggestions());
        assertEquals(6, result.getTokenStart());
        assertEquals("ma", result.getQuery());
    }

    @Test
    public void requiresWhitespaceBeforeAt() {
        assertNull(MentionCompletion.compute("mail@ma", 7, HANDLES));
    }

    @Test
    public void inactiveOutsideMentionToken() {
        assertNull(MentionCompletion.compute("Hello there", 5, HANDLES));
        assertNull(MentionCompletion.compute("@Maria done ", 12, HANDLES));
    }

    @Test
    public void noMatchesMeansNoPopup() {
        assertNull(MentionCompletion.compute("@zzz", 4, HANDLES));
    }

    @Test
    public void applyReplacesTokenAndPlacesCaret() {
        String text = "Hi @ma, ok?";
        MentionCompletion.Result result = MentionCompletion.compute(text, 6, HANDLES);
        String applied = MentionCompletion.apply(text, 6, result, "Maria");
        assertEquals("Hi @Maria , ok?", applied);
        assertEquals(10, MentionCompletion.caretAfterApply(result, "Maria"));
    }

    @Test
    public void botHandleIsOffered() {
        MentionCompletion.Result result = MentionCompletion.compute("@ask", 4, HANDLES);
        assertEquals(Arrays.asList("AskAI"), result.getSuggestions());
    }
}
