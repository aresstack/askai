package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.domain.scope.TypedBlacklist;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Component 4 of the component plan (2026-11-21): the search hygiene rejects a query only for its form, a repeat of
 * an earlier search or a hit on the typed blacklist. The queries are taken from the 17 Nov run where they can be.
 */
public class SearchHygieneTest {

    private static final List<String> NONE = Collections.emptyList();

    private static SearchHygiene.Verdict check(String query, String... earlier) {
        return SearchHygiene.check(query, Arrays.asList(earlier), null);
    }

    private static SearchHygiene.Verdict check(String query, TypedBlacklist blacklist) {
        return SearchHygiene.check(query, NONE, blacklist);
    }

    private static String repeat(String of) {
        return "repeats the search \"" + of + "\"";
    }

    // ----- form -----

    @Test
    public void aQueryWithoutASearchWordIsAFormalError() {
        for (String query : Arrays.asList(null, "", "   ", "?! -- ...", "site:baua.de", "site:baua.de -site:dguv.de")) {
            SearchHygiene.Verdict verdict = check(query);
            assertEquals(String.valueOf(query), SearchHygiene.Reason.NO_WORDS, verdict.getReason());
            assertEquals("no search word", verdict.text());
        }
    }

    @Test
    public void theLengthLimitsAreTheQueryPlansOwn() {
        assertEquals(SearchHygiene.Reason.TOO_SHORT, check("ab").getReason());
        assertEquals("shorter than 3 characters", check("ab").text());
        assertTrue("three characters are enough", check("a b").isAdmitted());
        StringBuilder query = new StringBuilder("Wearable");
        while (query.length() < ConceptQueryPlan.MAX_QUERY_LENGTH) {
            query.append(" x");
        }
        assertEquals(ConceptQueryPlan.MAX_QUERY_LENGTH, query.length());
        assertTrue(check(query.toString()).isAdmitted());
        query.append('y');
        assertEquals(SearchHygiene.Reason.TOO_LONG, check(query.toString()).getReason());
        assertEquals("longer than 160 characters", check(query.toString()).text());
    }

    @Test
    public void runsOfWhitespaceDoNotCountTowardsTheLength() {
        assertTrue(check("ab" + "          " + "c").isAdmitted());
        assertEquals(SearchHygiene.Reason.TOO_SHORT, check("  a  ").getReason());
    }

    // ----- repeats -----

    @Test
    public void theSameWordsInAnotherOrderRepeatTheEarlierSearch() {
        // the one repeat among the 69 searches of the 17 Nov run
        SearchHygiene.Verdict verdict = check("DGUV Richtlinien Wearables Baustellen Sensoren",
                "DGUV Richtlinien Wearables Sensoren Baustellen");
        assertEquals(SearchHygiene.Reason.REPEAT, verdict.getReason());
        assertEquals("DGUV Richtlinien Wearables Sensoren Baustellen", verdict.getDetail());
        assertEquals(repeat("DGUV Richtlinien Wearables Sensoren Baustellen"), verdict.text());
    }

    @Test
    public void caseAndPunctuationAndRepeatedWordsMakeNoDifference() {
        String earlier = "Recht, Datenschutz und Betriebsrat Wearables auf Baustellen";
        for (String query : Arrays.asList("recht datenschutz und betriebsrat wearables auf baustellen",
                "RECHT; Datenschutz und Betriebsrat: Wearables auf Baustellen!",
                "Recht   Datenschutz und Betriebsrat Wearables auf Baustellen Baustellen",
                "Wearables auf Baustellen: Recht, Datenschutz und Betriebsrat")) {
            assertEquals(query, repeat(earlier), check(query, earlier).text());
        }
        assertEquals("upper case umlauts", SearchHygiene.Reason.REPEAT,
                check("ARBEITSSCHUTZ-GEFÄHRDUNGEN und Wirksamkeit Wearables auf Baustellen",
                        "Arbeitsschutz-Gefährdungen und Wirksamkeit Wearables auf Baustellen").getReason());
    }

    @Test
    public void aRepeatNamesTheFirstEarlierSearchItRepeats() {
        SearchHygiene.Verdict verdict = check("smart ppe iot baustellen", "EU-OSHA Richtlinien Smart PPE",
                "Smart PPE IoT Baustellen", "Baustellen IoT Smart PPE");
        assertEquals("Smart PPE IoT Baustellen", verdict.getDetail());
    }

    @Test
    public void similarQueriesWithOtherWordsAreNewSearches() {
        assertTrue(check("Wearables Baustellen Sensorik", "Wearables Baustellen Sensoren").isAdmitted());
        // 17 Nov: #23 is a good search, #27 holds all its words and one more
        assertTrue(check("Wearables Arbeitssicherheit Sensorik Baustellen",
                "Wearables Arbeitsschutz Baustellen Sensorik Arbeitssicherheit Baustellen").isAdmitted());
        assertTrue(check("Wearables Arbeitsschutz Baustellen Sensorik Arbeitssicherheit Baustellen",
                "Wearables Arbeitssicherheit Sensorik Baustellen").isAdmitted());
        // a hyphen separates the words of a compound: "Smart-PPE" and "Smart PPE" are one search
        assertEquals(SearchHygiene.Reason.REPEAT, check("Smart-PPE IoT Baustellen", "Smart PPE IoT Baustellen")
                .getReason());
        // without the hyphen, a compound is another word
        assertTrue(check("Baustellenverordnung Arbeitsschutzgesetze", "Baustellen Verordnung Arbeitsschutzgesetze")
                .isAdmitted());
    }

    @Test
    public void withoutEarlierSearchesNothingRepeats() {
        assertTrue(SearchHygiene.check("Smart PPE IoT Baustellen", null, null).isAdmitted());
        assertTrue(SearchHygiene.check("Smart PPE IoT Baustellen", Arrays.asList(null, "", "site:baua.de"), null)
                .isAdmitted());
    }

    // ----- site: -----

    @Test
    public void theSameWordsWithAnotherSiteAreANewSearchAndAreCounted() {
        String base = "Technologien und Sensortypen Wearables auf Baustellen";
        SearchHygiene.Verdict baua = check(base + " site:baua.de", base);
        assertTrue(baua.isAdmitted());
        assertEquals(Collections.singletonList(base), baua.getSameWordsOtherSite());

        SearchHygiene.Verdict osha = check(base + " site:osha.europa.eu", base, base + " site:baua.de",
                "Implementierung und Praxis Wearables auf Baustellen site:osha.europa.eu");
        assertTrue(osha.isAdmitted());
        assertEquals(Arrays.asList(base, base + " site:baua.de"), osha.getSameWordsOtherSite());

        SearchHygiene.Verdict plain = check(base, base + " site:baua.de");
        assertTrue("the plain search after its site: variant", plain.isAdmitted());
        assertEquals(1, plain.getSameWordsOtherSite().size());
    }

    @Test
    public void theSameWordsWithTheSameSiteRepeatTheSearch() {
        String earlier = "Technologien und Sensortypen Wearables auf Baustellen site:baua.de";
        for (String query : Arrays.asList("technologien und sensortypen wearables auf baustellen SITE:BAUA.DE",
                "site:baua.de Technologien und Sensortypen Wearables auf Baustellen",
                "Technologien und Sensortypen Wearables auf Baustellen site:www.baua.de",
                "Technologien und Sensortypen Wearables auf Baustellen site:https://www.baua.de/DE/Home",
                "Technologien und Sensortypen Wearables auf Baustellen site:baua.de.")) {
            SearchHygiene.Verdict verdict = check(query, earlier);
            assertEquals(query, repeat(earlier), verdict.text());
            assertTrue(verdict.getSameWordsOtherSite().isEmpty());
        }
    }

    @Test
    public void anExcludedSiteIsAnotherSearchThanARestrictedOne() {
        assertTrue(check("Smart PPE -site:amazon.de", "Smart PPE site:amazon.de").isAdmitted());
        assertEquals(SearchHygiene.Reason.REPEAT, check("Smart PPE -site:Amazon.de", "smart ppe -site:amazon.de")
                .getReason());
    }

    @Test
    public void theKeyIsTheSortedWordsAndTheSites() {
        assertEquals("baustellen iot ppe smart", SearchHygiene.key("Smart PPE, IoT Baustellen smart"));
        assertEquals("baustellen wearables site:baua.de,osha.europa.eu",
                SearchHygiene.key("Wearables site:osha.europa.eu Baustellen site:https://www.baua.de/x"));
        assertEquals("", SearchHygiene.key(null));
    }

    // ----- blacklist -----

    @Test
    public void aTermOfTheBlacklistRejectsTheQueryAsAWholeWord() {
        TypedBlacklist blacklist = TypedBlacklist.of(Arrays.asList("Betriebsrat", "GHS"));
        SearchHygiene.Verdict verdict = check("Recht, Datenschutz und Betriebsrat Wearables auf Baustellen", blacklist);
        assertEquals(SearchHygiene.Reason.BLACKLIST, verdict.getReason());
        assertEquals("Betriebsrat", verdict.getDetail());
        assertEquals("blacklist \"Betriebsrat\"", verdict.text());
        assertEquals("GHS", check("umweltbundesamt ghs", blacklist).getDetail());
        assertTrue("a part of a word is no hit", check("Betriebsratsarbeit Datenschutz", blacklist).isAdmitted());
        assertTrue(check("GHSV Baustellen", blacklist).isAdmitted());
    }

    @Test
    public void aHostOfTheBlacklistRejectsASiteRestrictionToItAndOnlyThat() {
        TypedBlacklist blacklist = TypedBlacklist.of(Arrays.asList("amazon.de", "!aws.amazon.de"));
        assertEquals("amazon.de", check("Smart PPE kaufen site:amazon.de", blacklist).getDetail());
        assertEquals("a subdomain", "amazon.de", check("Smart PPE site:www.smile.amazon.de", blacklist).getDetail());
        assertTrue("an exempt host", check("IoT Sensoren site:aws.amazon.de", blacklist).isAdmitted());
        assertTrue("an excluded host keeps the query clean", check("Smart PPE -site:amazon.de", blacklist)
                .isAdmitted());
        assertTrue("a host entry only looks at a site: restriction", check("amazon.de Smart PPE", blacklist)
                .isAdmitted());
    }

    @Test
    public void theBlacklistIsNamedBeforeARepeat() {
        SearchHygiene.Verdict verdict = SearchHygiene.check("DGUV Vorschrift 1", Collections.singletonList(
                "DGUV Vorschrift 1"), TypedBlacklist.of(Collections.singletonList("DGUV")));
        assertEquals(SearchHygiene.Reason.BLACKLIST, verdict.getReason());
    }

    @Test
    public void anEmptyBlacklistRejectsNothing() {
        assertTrue(check("DGUV Vorschrift 1", TypedBlacklist.empty()).isAdmitted());
        assertTrue(check("DGUV Vorschrift 1", (TypedBlacklist) null).isAdmitted());
    }

    @Test
    public void anAdmittedQueryHasNoReasonText() {
        SearchHygiene.Verdict verdict = check("Wearables Sicherheit SiWamed", "DGUV Vorschrift 1");
        assertNull(verdict.getReason());
        assertEquals("", verdict.text());
        assertEquals("", verdict.getDetail());
        assertEquals("admitted", verdict.toString());
        assertFalse(verdict.getSameWordsOtherSite().iterator().hasNext());
    }
}
