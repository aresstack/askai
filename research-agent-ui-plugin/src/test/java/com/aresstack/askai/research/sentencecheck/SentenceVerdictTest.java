package com.aresstack.askai.research.sentencecheck;

import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Attribution;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Jurisdiction;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Kind;
import com.aresstack.askai.research.sentencecheck.SentenceVerdict.Verdict;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A verdict of the sentence check: the quote check keeps a GEDECKT only with a passage quote holding its numbers. */
public class SentenceVerdictTest {

    static final String PASSAGE = "In den Tempo-30-Zonen der Stadt sank die Zahl der Unfälle mit Verletzten im ersten "
            + "Jahr um 25 Prozent. Die Verwaltung führt das vor allem auf die kürzeren Bremswege zurück. Auf den "
            + "Hauptstraßen blieb Tempo 50 erlaubt.";
    static final String SENTENCE = "In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 "
            + "Prozent [12].";
    static final String QUOTE = "In den Tempo-30-Zonen der Stadt sank die Zahl der Unfälle mit Verletzten im ersten "
            + "Jahr um 25 Prozent";

    static SentenceVerdict verdict(Verdict said, String quote) {
        return verdict("S1", SENTENCE, said, quote, Attribution.NICHT_GEFRAGT, Jurisdiction.NICHT_GEFRAGT);
    }

    static SentenceVerdict verdict(String id, String sentence, Verdict said, String quote, Attribution attribution,
                                   Jurisdiction jurisdiction) {
        return SentenceVerdict.checked(id, sentence, "p1", "s1", PASSAGE, said, Kind.TEXT, quote,
                "Die Passage nennt den Rückgang.", attribution, jurisdiction, "gemma4:e2b", SentenceCheck.VERSION,
                10L, 5L);
    }

    @Test
    public void aGedecktHoldsOnlyWithAQuoteOfThePassageThatHoldsTheNumbersOfTheSentence() {
        SentenceVerdict held = verdict(Verdict.GEDECKT, QUOTE);
        assertEquals(Verdict.GEDECKT, held.getVerdict());
        assertFalse(held.isDowngraded());
        assertFalse(held.needsRewrite());

        SentenceVerdict noNumbers = verdict(Verdict.GEDECKT, "sank die Zahl der Unfälle mit Verletzten im ersten Jahr");
        assertEquals("the quote stands in the passage, but 30 and 25 are not in it", Verdict.TEILWEISE,
                noNumbers.getVerdict());
        assertEquals(Verdict.GEDECKT, noNumbers.getSaid());
        assertTrue(noNumbers.isDowngraded());

        SentenceVerdict invented = verdict(Verdict.GEDECKT, "Die Zahl der Unfälle halbierte sich in allen 30 Zonen um "
                + "25 Prozent");
        assertEquals(Verdict.TEILWEISE, invented.getVerdict());
        assertEquals(Verdict.TEILWEISE, verdict(Verdict.GEDECKT, "").getVerdict());

        assertEquals("only a GEDECKT is checked", Verdict.TEILWEISE, verdict(Verdict.TEILWEISE, "").getVerdict());

        SentenceVerdict laterFirst = verdict(Verdict.GEDECKT, "Die Verwaltung führt das vor allem auf die kürzeren "
                + "Bremswege zurück. [...] In den Tempo-30-Zonen der Stadt sank die Zahl der Unfälle mit Verletzten im "
                + "ersten Jahr um 25 Prozent");
        assertEquals("parts of a shortened quote in another order (stock test 22.11.2026)", Verdict.GEDECKT,
                laterFirst.getVerdict());
        SentenceVerdict commas = verdict(Verdict.GEDECKT, "In den Tempo-30-Zonen der Stadt, sank die Zahl der Unfälle "
                + "(mit Verletzten) im ersten Jahr um 25 Prozent");
        assertEquals("commas and brackets do not count", Verdict.GEDECKT, commas.getVerdict());
        SentenceVerdict not = verdict(Verdict.NICHT_GEDECKT, "");
        assertEquals(Verdict.NICHT_GEDECKT, not.getVerdict());
        assertTrue("the writer gets it back", not.needsRewrite());
    }

    @Test
    public void citationMarkersAreNoPartOfTheSentence() {
        assertEquals("In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im ersten Jahr um 25 Prozent.",
                SentenceVerdict.plain(SENTENCE));
        assertEquals("Auf Hauptstraßen blieb Tempo 50 erlaubt, sagt die Stadt.",
                SentenceVerdict.plain("Auf Hauptstraßen blieb Tempo 50 erlaubt [3, 5], sagt die Stadt [7]."));
        assertEquals("the marker's 12 is no number of the sentence", Verdict.GEDECKT,
                verdict(Verdict.GEDECKT, QUOTE).getVerdict());
        assertEquals(SentenceVerdict.sentenceKey("a", "In Tempo-30-Zonen sank die Zahl der Unfälle mit Verletzten im "
                + "ersten Jahr um 25 Prozent."), SentenceVerdict.sentenceKey("b", SENTENCE));
        assertEquals("satz:S9", SentenceVerdict.sentenceKey("S9", " [4] "));
        assertEquals("passage:p9", SentenceVerdict.textKey("p9", ""));
        assertTrue(SentenceVerdict.numbersHeld("Ein Bußgeld von 1.000 Euro", "bis zu 1000 Euro Bußgeld"));
        assertFalse(SentenceVerdict.numbersHeld("Ein Bußgeld von 2,5 Prozent", "ein Bußgeld von 25 Prozent"));
    }

    @Test
    public void attributionAndLegalSpaceSendTheSentenceBack() {
        assertTrue(verdict("S2", SENTENCE, Verdict.GEDECKT, QUOTE, Attribution.ALS_TATSACHE,
                Jurisdiction.NICHT_GEFRAGT).needsRewrite());
        assertTrue(verdict("S3", SENTENCE, Verdict.GEDECKT, QUOTE, Attribution.NICHT_GEFRAGT,
                Jurisdiction.NICHT_GEKENNZEICHNET).needsRewrite());
        assertFalse(verdict("S4", SENTENCE, Verdict.GEDECKT, QUOTE, Attribution.ZUGESCHRIEBEN,
                Jurisdiction.KEINE_RECHTSAUSSAGE).needsRewrite());
    }

    @Test
    public void aFailedCheckIsFehlerAndNeverNichtGedeckt() {
        SentenceVerdict failed = SentenceVerdict.failed("S1", SENTENCE, "p1", "s1", PASSAGE, "model call failed",
                "gemma4:e2b", SentenceCheck.VERSION, 10L, 5L);
        assertEquals(SentenceVerdict.Status.FEHLER, failed.getStatus());
        assertNull(failed.getVerdict());
        assertNull(failed.getSaid());
        assertNull(failed.getKind());
        assertFalse(failed.needsRewrite());
        assertFalse(failed.isDowngraded());
        assertEquals("model call failed", failed.getProblem());
    }

    @Test
    public void aSentenceThatCitesSeveralPassagesTakesTheBestOfThem() {
        SentenceVerdict covered = verdict(Verdict.GEDECKT, QUOTE);
        SentenceVerdict partial = verdict(Verdict.TEILWEISE, "");
        SentenceVerdict not = verdict(Verdict.NICHT_GEDECKT, "");
        SentenceVerdict failed = SentenceVerdict.failed("S1", SENTENCE, "p2", "s2", "Anderer Text.", "x", "m", 1, 1L,
                0);
        assertEquals(Verdict.GEDECKT, SentenceVerdict.ofSentence(Arrays.asList(not, covered)));
        assertEquals(Verdict.GEDECKT, SentenceVerdict.ofSentence(Arrays.asList(failed, covered)));
        assertEquals(Verdict.TEILWEISE, SentenceVerdict.ofSentence(Arrays.asList(not, partial)));
        assertEquals(Verdict.NICHT_GEDECKT, SentenceVerdict.ofSentence(Arrays.asList(not, not)));
        assertNull("a failed check leaves it open", SentenceVerdict.ofSentence(Arrays.asList(not, failed)));
        assertNull(SentenceVerdict.ofSentence(Collections.<SentenceVerdict>emptyList()));
    }

    @Test
    public void aVerdictNeedsAPassageAndTheModelsVerdict() {
        for (Runnable bad : new Runnable[]{
                () -> SentenceVerdict.checked("S1", SENTENCE, "", "s1", PASSAGE, Verdict.GEDECKT, Kind.TEXT, QUOTE,
                        "x", null, null, "m", 1, 1L, 0),
                () -> SentenceVerdict.checked("S1", SENTENCE, "p1", "s1", PASSAGE, null, Kind.TEXT, QUOTE, "x", null,
                        null, "m", 1, 1L, 0)}) {
            try {
                bad.run();
                fail("accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().startsWith("a verdict"));
            }
        }
    }
}
