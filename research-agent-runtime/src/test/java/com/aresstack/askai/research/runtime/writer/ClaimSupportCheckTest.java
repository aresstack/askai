package com.aresstack.askai.research.runtime.writer;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ClaimSupportCheckTest {

    private static List<ClaimFlag> check(String answer, EvidencePassage... passages) {
        CitationValidator.Result result = new CitationValidator().validate(answer, passages.length);
        return new ClaimSupportCheck().check(result, Arrays.asList(passages));
    }

    private static EvidencePassage p(int n, String text) {
        return new EvidencePassage(n, "s" + n, "c" + n, "p" + n, text);
    }

    @Test
    public void numberMissingFromCitedPassageIsFlagged() {
        List<ClaimFlag> flags = check("Der Anteil lag bei 15 % [1].", p(1, "Der Anteil lag bei 20 Prozent."));
        assertEquals(1, flags.size());
        assertEquals(ClaimFlag.Kind.UNSUPPORTED_NUMBER, flags.get(0).getKind());
        assertEquals("15 %", flags.get(0).getDetail());
    }

    @Test
    public void germanDecimalMatchesEnglishPassage() {
        assertTrue(check("Der Anteil lag bei 15,5 % [1].", p(1, "The share was 15.5 percent.")).isEmpty());
    }

    @Test
    public void thousandsSeparatorsAreNormalised() {
        assertTrue(check("Es waren 1.500 Personen [1].", p(1, "There were 1,500 people.")).isEmpty());
        assertTrue(check("Es waren 12 000 Personen [1].", p(1, "12,000 people")).isEmpty());
        assertTrue(check("Es waren 1.234,5 t [1].", p(1, "1,234.5 tonnes")).isEmpty());
    }

    @Test
    public void numberInParagraphCitationCounts() {
        assertTrue(check("Im Jahr 2020 stieg sie. Das zeigt die Studie [1].", p(1, "In 2020 it rose.")).isEmpty());
    }

    @Test
    public void numberOnlyInUncitedPassageIsFlagged() {
        List<ClaimFlag> flags = check("Im Jahr 2020 stieg sie [1].", p(1, "It rose."), p(2, "In 2020."));
        assertEquals(1, flags.size());
    }

    @Test
    public void quotationMissingFromPassageIsFlagged() {
        List<ClaimFlag> flags = check("Sie sagte „wir schaffen das schon“ [1].", p(1, "Sie sagte: Wir schaffen das."));
        assertEquals(1, flags.size());
        assertEquals(ClaimFlag.Kind.UNSUPPORTED_QUOTE, flags.get(0).getKind());
        assertTrue(check("Sie sagte „Wir schaffen das“ [1].", p(1, "Sie sagte: \"Wir schaffen das.\"")).isEmpty());
    }

    @Test
    public void capitalisedGermanNounsRaiseNoFlag() {
        assertTrue(check("Die Energiewende verändert Industrie, Verkehr und Gebäude [1].",
                p(1, "Ein ganz anderer Text.")).isEmpty());
    }

    @Test
    public void readingsAreCanonical() {
        assertEquals(Arrays.asList("15.5"), new java.util.ArrayList<String>(ClaimSupportCheck.readings("15,50")));
        assertEquals(Arrays.asList("1.5", "1500"), new java.util.ArrayList<String>(ClaimSupportCheck.readings("1.500")));
    }
}
