package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Live run 4, book 6: sentences of sections 1.1 and 1.2 that came back in 1.2 and 1.3. */
public class RepeatCheckTest {

    static final String SECTION_12 = "Wearables wie smarte Helme, Sicherheitswesten und Handschuhe sind mit Sensoren "
            + "ausgestattet, die die Vitalwerte und Bewegungen der Arbeiter überwachen [2]. Bei einem Sturz oder "
            + "ungewöhnlichen Gesundheitswerten sendet das Wearable sofort einen Alarm an die Bauleitung und "
            + "Rettungsteams [2].\n\nIn bestimmten Branchen, wie dem Bauwesen, müssen die Arbeitgeber sicherstellen, "
            + "dass die Arbeitsschutz- und Unfallverhütungsvorschriften eingehalten werden [5].";

    private final RepeatCheck check = new RepeatCheck(RepeatCheck.Criteria.defaults(), null);

    @Test
    public void aSentenceAnotherSectionAlreadyHoldsWithOneMoreClauseIsARepeat() {
        RepeatCheck.Memory book = check.memory(Collections.singletonList(SECTION_12));
        assertTrue(book.repeats("Bei einem Sturz oder ungewöhnlichen Gesundheitswerten sendet das Wearable sofort "
                + "einen Alarm an die Bauleitung und Rettungsteams, die dann schnell eingreifen können."));
        assertTrue("a shorter sentence the book already holds",
                book.repeats("Diese Geräte sind mit Sensoren ausgestattet, die die Vitalwerte und Bewegungen der "
                        + "Arbeiter überwachen."));
    }

    @Test
    public void aRelatedSentenceWithItsOwnClaimStays() {
        RepeatCheck.Memory book = check.memory(Collections.singletonList(SECTION_12));
        assertFalse("half of its words are new: its own claim",
                book.repeats("Die Arbeitgeber müssen auch sicherstellen, dass die Arbeitnehmer nicht in Situationen "
                        + "arbeiten, die ihre Gesundheit gefährden."));
    }

    @Test
    public void aShortSentenceIsNeverANearRepeat() {
        RepeatCheck.Memory book = check.memory(Collections.singletonList(
                "Die DSGVO schützt die Daten der Nutzer in der Europäischen Union und regelt Auskunftsrechte [1]."));
        assertFalse("five content words: only an exact repeat leaves",
                book.repeats("Die DSGVO schützt die Wearable-Daten der Nutzer."));
    }

    @Test
    public void bothBoundsAreSettings() {
        RepeatCheck.Memory strict = new RepeatCheck(new RepeatCheck.Criteria(6, 1.0), null)
                .memory(Collections.singletonList(SECTION_12));
        assertFalse(strict.repeats("Bei einem Sturz oder ungewöhnlichen Gesundheitswerten sendet das Wearable "
                + "sofort einen Alarm an die Bauleitung und Rettungsteams, die dann schnell eingreifen können."));
        RepeatCheck.Memory loose = new RepeatCheck(new RepeatCheck.Criteria(3, 0.5), null)
                .memory(Collections.singletonList(SECTION_12));
        assertTrue(loose.repeats("Die Arbeitgeber müssen auch sicherstellen, dass die Arbeitnehmer nicht in "
                + "Situationen arbeiten, die ihre Gesundheit gefährden."));
    }

    @Test
    public void aCopyLeavesTheMemoryUnchangedAndNoneFindsNothing() {
        RepeatCheck.Memory book = check.memory(Arrays.asList(SECTION_12, "", null));
        assertEquals(3, book.size());
        RepeatCheck.Memory copy = book.copy();
        copy.remember("Ein ganz neuer Satz über Exoskelette und ihre Wirkung auf die Wirbelsäule.");
        assertEquals(4, copy.size());
        assertEquals(3, book.size());
        RepeatCheck.Memory none = RepeatCheck.none();
        none.rememberText(SECTION_12);
        assertFalse(none.repeats("Bei einem Sturz oder ungewöhnlichen Gesundheitswerten sendet das Wearable sofort "
                + "einen Alarm an die Bauleitung und Rettungsteams."));
    }

    @Test
    public void theValidatorTakesNearRepeatsOutOfTheProseAndKeepsTheRest() {
        RepeatCheck.Memory book = check.memory(Collections.singletonList(SECTION_12));
        String answer = "Bei einem Sturz oder ungewöhnlichen Gesundheitswerten sendet das Wearable sofort einen Alarm "
                + "an die Bauleitung und Rettungsteams, die dann schnell eingreifen können [1]. Exoskelette entlasten "
                + "den Rücken beim Heben schwerer Lasten auf der Baustelle [2]. Exoskelette entlasten nachweislich den "
                + "Rücken beim Heben schwerer Lasten auf Baustellen [3].";
        CitationValidator.Result result = new CitationValidator().validate(answer, 3,
                CitationValidator.sentenceKeys(SECTION_12), book);
        assertEquals("Exoskelette entlasten den Rücken beim Heben schwerer Lasten auf der Baustelle [2].",
                result.text());
        assertEquals("one repeat of the book, one of the answer itself", 2, result.getRepeatedSentences().size());
        assertEquals("the caller's memory is not changed", 3, book.size());
    }
}
