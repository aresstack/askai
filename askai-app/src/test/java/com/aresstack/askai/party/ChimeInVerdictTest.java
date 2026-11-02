package com.aresstack.askai.party;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tolerant parsing of the chime-in gate verdict — small models rarely answer with a bare "YES".
 */
public class ChimeInVerdictTest {

    @Test
    public void plainVerdicts() {
        assertTrue(OllamaBotResponder.isAffirmative("YES"));
        assertTrue(OllamaBotResponder.isAffirmative("yes"));
        assertFalse(OllamaBotResponder.isAffirmative("NO"));
        assertFalse(OllamaBotResponder.isAffirmative("no"));
    }

    @Test
    public void decoratedVerdicts() {
        assertTrue(OllamaBotResponder.isAffirmative("**YES**"));
        assertTrue(OllamaBotResponder.isAffirmative("Yes."));
        assertTrue(OllamaBotResponder.isAffirmative("Ja"));
        assertFalse(OllamaBotResponder.isAffirmative("**No.**"));
        assertFalse(OllamaBotResponder.isAffirmative("Nein"));
    }

    @Test
    public void verdictAfterReasoningUsesLastToken() {
        assertTrue(OllamaBotResponder.isAffirmative("The statement is false, so YES"));
        assertFalse(OllamaBotResponder.isAffirmative("This is a true fact, so NO"));
    }

    @Test
    public void unparseableStaysSilent() {
        assertFalse(OllamaBotResponder.isAffirmative(""));
        assertFalse(OllamaBotResponder.isAffirmative(null));
        assertFalse(OllamaBotResponder.isAffirmative("maybe"));
    }
}
