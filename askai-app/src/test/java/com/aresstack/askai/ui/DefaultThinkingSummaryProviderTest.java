package com.aresstack.askai.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The floating thinking summary is short, whitespace-collapsed, and falls back to a neutral text. */
public class DefaultThinkingSummaryProviderTest {

    private final ThinkingSummaryProvider provider = new DefaultThinkingSummaryProvider();

    @Test
    public void takesTheFirstSentence() {
        assertEquals("I should check the spec first.",
                provider.createSummary("I should check the spec first. Then compare the values."));
    }

    @Test
    public void collapsesWhitespace() {
        assertEquals("Plan the steps.", provider.createSummary("Plan   the\n steps."));
    }

    @Test
    public void truncatesLongSingleSentence() {
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            longText.append("word ");
        }
        String summary = provider.createSummary(longText.toString());
        assertTrue(summary.length() <= 111);
        assertTrue(summary.endsWith("…"));
    }

    @Test
    public void blankOrTinyReasoningUsesNeutralFallback() {
        assertEquals("Antwort vorbereitet", provider.createSummary(""));
        assertEquals("Antwort vorbereitet", provider.createSummary("   \n  "));
        assertEquals("Antwort vorbereitet", provider.createSummary(null));
        assertEquals("Antwort vorbereitet", provider.createSummary("ok"));
    }
}
