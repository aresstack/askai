package com.aresstack.askai.speech;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Caret-position insertion: preserves text, adds spaces, ignores empty, no double-insert of a caller. */
public class ComposerInserterTest {

    @Test
    public void insertsAtCaretPreservingSurroundingText() {
        ComposerInserter.Insertion result = ComposerInserter.insert("Hello world", 5, 5, "there");
        // A space is added on both sides of the caret between "Hello" and "world".
        assertEquals("Hello there world", result.getText());
        assertEquals("caret behind inserted words", "Hello there".length(), result.getCaret());
    }

    @Test
    public void insertsIntoEmptyComposerWithoutExtraSpaces() {
        ComposerInserter.Insertion result = ComposerInserter.insert("", 0, 0, "  hallo welt  ");
        assertEquals("hallo welt", result.getText());
        assertEquals("hallo welt".length(), result.getCaret());
    }

    @Test
    public void appendsAtEndWithLeadingSpace() {
        ComposerInserter.Insertion result = ComposerInserter.insert("done:", 5, 5, "next");
        assertEquals("done: next", result.getText());
        assertEquals("done: next".length(), result.getCaret());
    }

    @Test
    public void replacesSelection() {
        ComposerInserter.Insertion result = ComposerInserter.insert("keep XXX end", 5, 8, "new");
        assertEquals("keep new end", result.getText());
        assertEquals("keep new".length(), result.getCaret());
    }

    @Test
    public void doesNotAddSpaceWhenNeighbourAlreadyHasOne() {
        // "a␣|␣b": caret between two existing spaces — no extra spaces are added.
        ComposerInserter.Insertion result = ComposerInserter.insert("a  b", 2, 2, "x");
        assertEquals("a x b", result.getText());
        assertEquals(3, result.getCaret());
    }

    @Test
    public void emptyTranscriptIsNotInserted() {
        ComposerInserter.Insertion result = ComposerInserter.insert("keep", 2, 2, "   ");
        assertEquals("keep", result.getText());
        assertEquals(2, result.getCaret());
    }
}
