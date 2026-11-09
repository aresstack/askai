package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CitationValidatorTest {

    private final CitationValidator validator = new CitationValidator();

    @Test
    public void markerAboveKIsStripped() {
        CitationValidator.Result r = validator.validate("Satz eins [9]. Satz zwei [2, 9].", 6);
        assertEquals(Arrays.asList("[9]", "[9]"), r.getStrippedMarkers());
        assertEquals("Satz eins. Satz zwei [2].", r.text());
        assertEquals(1, r.getFlags().size());
        assertEquals(ClaimFlag.Kind.UNCITED_SENTENCE, r.getFlags().get(0).getKind());
    }

    @Test
    public void markerAfterFullStopBelongsToSentenceBefore() {
        CitationValidator.Result r = validator.validate("Erster Satz. [1] Zweiter Satz. [2]", 6);
        assertTrue(r.getFlags().toString(), r.getFlags().isEmpty());
        assertEquals(2, r.getParagraphs().get(0).getSentences().size());
    }

    @Test
    public void gapsHeadingsAndBulletsAreHandled() {
        CitationValidator.Result r = validator.validate("## Titel\nText [1].\n\n- Punkt [2].\nGAP: Daten fehlen\nGAP:", 3);
        assertEquals(Arrays.asList("Daten fehlen"), r.getGaps());
        assertEquals("Titel Text [1].\n\nPunkt [2].", r.text());
    }

    @Test
    public void uncitedParagraphIsFlagged() {
        CitationValidator.Result r = validator.validate("Belegt [1].\n\nOhne Beleg. Auch ohne.", 3);
        assertEquals(1, r.getFlags().stream().filter(f -> f.getKind() == ClaimFlag.Kind.UNCITED_PARAGRAPH).count());
        assertEquals(2, r.getFlags().stream().filter(f -> f.getKind() == ClaimFlag.Kind.UNCITED_SENTENCE).count());
        assertEquals(Arrays.asList(1), new java.util.ArrayList<Integer>(r.citedNumbers()));
    }

    @Test
    public void abbreviationDoesNotSplit() {
        CitationValidator.Result r = validator.validate("Viele Stoffe, z. B. die Säuren, wirken [1].", 3);
        assertEquals(1, r.getParagraphs().get(0).getSentences().size());
    }
}
