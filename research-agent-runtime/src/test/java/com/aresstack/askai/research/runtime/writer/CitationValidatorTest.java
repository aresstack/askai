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

    /** Live run 4, section 1.2 of book 6: "… die Sicherheit der Arbeiter zu erhöhen [keine Passage]." */
    @Test
    public void aNoteInPlaceOfAMarkerIsStrippedAndTheSentenceStaysUncited() {
        CitationValidator.Result r = validator.validate("Wearables messen den Puls [1]. Das erhöht die Sicherheit "
                + "[keine Passage]. Helme warnen bei Stürzen. [Quelle fehlt] Westen leuchten [n].", 3);
        assertEquals(Arrays.asList("[keine Passage]", "[Quelle fehlt]", "[n]"), r.getStrippedMarkers());
        assertEquals("Wearables messen den Puls [1]. Das erhöht die Sicherheit. Helme warnen bei Stürzen. "
                + "Westen leuchten.", r.text());
        boolean flagged = false;
        for (ClaimFlag flag : r.getFlags()) {
            flagged |= flag.getKind() == ClaimFlag.Kind.UNCITED_SENTENCE
                    && flag.getSentence().equals("Das erhöht die Sicherheit.");
        }
        assertTrue(r.getFlags().toString(), flagged);
    }

    @Test
    public void aBracketInsideASentenceOrWithoutALetterIsNoNote() {
        String text = "Die Werte [Puls und Temperatur] gehen an die Leitstelle [1]. „Die Daten […] bleiben im "
                + "Betrieb“ [2].";
        CitationValidator.Result r = validator.validate(text, 2);
        assertTrue(r.getStrippedMarkers().toString(), r.getStrippedMarkers().isEmpty());
        assertEquals(text, r.text());
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
        assertEquals("a heading without a sentence is a label, not the start of the paragraph",
                "Text [1].\n\nPunkt [2].", r.text());
        assertEquals(Arrays.asList("Titel"), r.getLabelLines());
    }

    /** Live run 4, section 1.1 of books 2 and 3: the key points of the prompt came back as the last "paragraph". */
    @Test
    public void echoedKeyPointsAreNoProse() {
        String prose = "Die Datenschutzkonferenz empfiehlt Datensparsamkeit [1].";
        CitationValidator.Result listed = validator.validate(prose + "\n\n- Beispiel für IoT-Architekturen\n"
                + "- Risiken für das Persönlichkeitsrecht\n- Datensicherheit als primäre Anforderung", 3);
        assertEquals(prose, listed.text());
        assertEquals(Arrays.asList("Beispiel für IoT-Architekturen", "Risiken für das Persönlichkeitsrecht",
                "Datensicherheit als primäre Anforderung"), listed.getLabelLines());
        assertTrue(listed.getFlags().toString(), listed.getFlags().isEmpty());

        CitationValidator.Result runTogether = validator.validate(prose + "\n\nBeispiel für IoT-Architekturen "
                + "Risiken für das Persönlichkeitsrecht Datensicherheit als primäre Anforderung Stand der Technik im "
                + "Baubereich", 3);
        assertEquals(prose, runTogether.text());
        assertEquals(1, runTogether.getLabelLines().size());
        assertTrue("no uncited paragraph is left to flag", runTogether.getFlags().isEmpty());
    }

    @Test
    public void aCitedSentenceWithoutFullStopStaysProse() {
        CitationValidator.Result r = validator.validate("Die DGUV empfiehlt Schulungen [1]", 3);
        assertEquals("Die DGUV empfiehlt Schulungen [1]", r.text());
        assertTrue(r.getLabelLines().isEmpty());
    }

    /** Live run 4, section 2.2 of book 3: one passage, and the writer filled the word target by repeating itself. */
    @Test
    public void repeatedSentencesAreRemoved() {
        CitationValidator.Result r = validator.validate(
                "Sensoren verbessern die Sicherheit [1]. Warnsysteme sind wichtig.\n\n"
                        + "Warnsysteme sind wichtig. Projektleiter optimieren Abläufe [1].\n\n"
                        + "Sensoren verbessern die Sicherheit [1]. Projektleiter  optimieren Abläufe [1].", 3);
        assertEquals("Sensoren verbessern die Sicherheit [1]. Warnsysteme sind wichtig.\n\n"
                + "Projektleiter optimieren Abläufe [1].", r.text());
        assertEquals(3, r.getRepeatedSentences().size());
        assertEquals("flags point into the text as it stays", 1, r.getFlags().size());
        assertEquals(ClaimFlag.Kind.UNCITED_SENTENCE, r.getFlags().get(0).getKind());
        assertEquals(0, r.getFlags().get(0).getParagraph());
    }

    /** A sign, a unit or another symbol makes another claim, not a repeat; case, spacing and the end do not. */
    @Test
    public void sentencesThatDifferInASymbolAreNoRepeat() {
        CitationValidator.Result r = validator.validate("Die Temperatur beträgt -20 °C [1]. "
                + "Die Temperatur beträgt 20 °C [2]. Der Anteil liegt bei 5 % [1]. Der Anteil liegt bei 5 ‰ [2]. "
                + "Die temperatur  beträgt -20 °C [2]!", 3);
        assertEquals("Die Temperatur beträgt -20 °C [1]. Die Temperatur beträgt 20 °C [2]. "
                + "Der Anteil liegt bei 5 % [1]. Der Anteil liegt bei 5 ‰ [2].", r.text());
        assertEquals(Arrays.asList("Die temperatur beträgt -20 °C [2]!"), r.getRepeatedSentences());
    }

    @Test
    public void aSentenceAnEarlierPartWroteIsRemoved() {
        java.util.Set<String> written = CitationValidator.sentenceKeys("Teil eins [1].\n\nSensoren warnen früh [2].");
        CitationValidator.Result r = validator.validate("Sensoren warnen früh [3]. Teil zwei [2].", 3, written);
        assertEquals("Teil zwei [2].", r.text());
        assertEquals(Arrays.asList("Sensoren warnen früh [3]."), r.getRepeatedSentences());
    }

    /** Live run 4, section 2.2 of book 3: the writer closed with a paragraph of nine questions, some twice. */
    @Test
    public void uncitedQuestionsBecomeOpenPoints() {
        CitationValidator.Result r = validator.validate("Sensoren warnen früh [1].\n\n"
                + "Wie genau überwachen IoT-Sensoren die Arbeitsbedingungen? Wie prüft man Warnsysteme? "
                + "Wie genau überwachen IoT-Sensoren die Arbeitsbedingungen?\nGAP: Wie prüft man Warnsysteme?", 3);
        assertEquals("Sensoren warnen früh [1].", r.text());
        assertEquals(Arrays.asList("Wie prüft man Warnsysteme?",
                "Wie genau überwachen IoT-Sensoren die Arbeitsbedingungen?"), r.getGaps());
        assertEquals(2, r.getQuestions().size());
        assertEquals(1, r.getRepeatedSentences().size());
        assertTrue(r.getFlags().toString(), r.getFlags().isEmpty());
    }

    /** Live run 4, section 2.1 of book 5: seven of ten gap lines were sentences of the section's own text. */
    @Test
    public void aGapLineThatRepeatsTheTextOrAnotherGapLineIsNoOpenPoint() {
        CitationValidator.Result r = validator.validate("Ein weiterer wichtiger Aspekt ist die Gesundheit der "
                + "Arbeiter [2]. Sensoren messen den Puls [1].\n"
                + "GAP: Ein weiterer wichtiger Aspekt ist die Gesundheit der Arbeiter.\n"
                + "GAP: Grenzwerte für Hitzestress\n"
                + "GAP: grenzwerte für hitzestress\n"
                + "GAP: Sensoren messen den Puls [1]", 3);
        assertEquals(Arrays.asList("Grenzwerte für Hitzestress"), r.getGaps());
        assertEquals(Arrays.asList("Ein weiterer wichtiger Aspekt ist die Gesundheit der Arbeiter.",
                "grenzwerte für hitzestress", "Sensoren messen den Puls [1]"), r.getDroppedGaps());
        assertEquals("the text stays as written", 2, r.getParagraphs().get(0).getSentences().size());
    }

    @Test
    public void aGapLineThatRepeatsAnEarlierPartIsNoOpenPoint() {
        CitationValidator.Result r = validator.validate("Teil zwei [2].\nGAP: Teil eins sagt das.\nGAP: Normen", 3,
                CitationValidator.sentenceKeys("Teil eins sagt das [1]."));
        assertEquals(Arrays.asList("Normen"), r.getGaps());
        assertEquals(Arrays.asList("Teil eins sagt das."), r.getDroppedGaps());
    }

    @Test
    public void aQuestionWithItsCitedAnswerStaysProse() {
        CitationValidator.Result r = validator.validate("Was kostet ein Sensor? Die Studie nennt 500 Euro [1].", 3);
        assertEquals("Was kostet ein Sensor? Die Studie nennt 500 Euro [1].", r.text());
        assertTrue(r.getQuestions().isEmpty());
        assertTrue(r.getGaps().isEmpty());
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
