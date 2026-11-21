package com.aresstack.askai.research.memory;

import com.aresstack.askai.research.memory.EvidenceVerdict.Kind;
import com.aresstack.askai.research.memory.LeadDecision.Action;
import com.aresstack.askai.research.memory.LeadDecision.Target;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Component 7, single test (pure program test): fixed records in, lists and counts out. No model, no search, no
 * stock. No field may claim a question is answered.
 */
public class ResearchMemoryTest {

    static final String Q = "F01";

    static final String TEXT_A = "Der Betriebsrat hat nach § 87 Abs. 1 Nr. 6 BetrVG ein Mitbestimmungsrecht, "
            + "wenn technische Einrichtungen zur Überwachung der Beschäftigten geeignet sind.";
    static final String TEXT_B = "Eine Datenschutz-Folgenabschätzung ist nach Art. 35 DSGVO Pflicht, wenn die "
            + "Verarbeitung voraussichtlich ein hohes Risiko für die Rechte der Betroffenen birgt.";
    static final String TEXT_C = "Für KI-Assistenten im Kundendienst empfehlen die Aufsichtsbehörden eine "
            + "schriftliche Dokumentation der Zwecke, der Datenkategorien und der Löschfristen.";

    static ResearchMemory memoryWithQuestion() throws Exception {
        ResearchMemory memory = new ResearchMemory();
        memory.addTopic(ResearchTopic.ofCard("T-recht", "Recht, Datenschutz und Betriebsrat",
                "anchor-concept-recht-datenschutz-und-betriebsrat", 0, 1L));
        memory.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat bei KI-Systemen?", "T-recht",
                ResearchQuestion.Origin.KONZEPT, 1L));
        return memory;
    }

    static EvidenceVerdict direct(String passage, String source, String url, String text) {
        return direct(Q, passage, source, url, text, 1);
    }

    static EvidenceVerdict direct(String question, String passage, String source, String url, String text,
                                  int round) {
        return EvidenceVerdict.judged(question, passage, source, url, text, Kind.BELEGT, text.substring(0, 60),
                "nennt die Regel", "gemma4:12b", round, 1000L + round);
    }

    static EvidenceVerdict lead(String passage, String source, String text) {
        return EvidenceVerdict.judged(Q, passage, source, "", text, Kind.HINWEIS, "", "nur benachbart",
                "gemma4:12b", 1, 1000L);
    }

    static EvidenceVerdict none(String question, String passage, String text, int round) {
        return EvidenceVerdict.judged(question, passage, "s-" + passage, "", text, Kind.KEIN, "", "anderes Thema",
                "gemma4:12b", round, 1000L + round);
    }

    // ------------------------------------------------------------------------- evidence and sources

    @Test
    public void threeDirectPassagesFromOneSourceAreOneSource() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://example.org/a", TEXT_A));
        memory.record(direct("p2", "s1", "https://example.org/a", TEXT_B));
        memory.record(direct("p3", "s1", "https://example.org/a", TEXT_C));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(3, evidence.getDirectCount());
        assertEquals(1, evidence.getIndependentSources());
        assertEquals("Belegt: 3 (1 Quelle) · Weiterführender Hinweis: 0", evidence.summary());
    }

    @Test
    public void theSameTextOnTheSameDomainIsOneEvidenceFromOneSource() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://www.example.org/a", TEXT_A));
        memory.record(direct("p2", "s2", "https://print.example.org/a", "  " + TEXT_A.replace(" ", "\n") + " "));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getDirectCount());
        assertEquals(1, evidence.getIndependentSources());
    }

    @Test
    public void theSameTextOnTwoDomainsIsOneEvidenceFromTwoSources() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://example.org/a", TEXT_A));
        memory.record(direct("p2", "s2", "https://other.example.net/b", TEXT_A));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getDirectCount());
        assertEquals(2, evidence.getIndependentSources());
    }

    @Test
    public void differentTextsOnOneDomainAreTwoSources() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://example.org/a", TEXT_A));
        memory.record(direct("p2", "s2", "https://example.org/b", TEXT_B));

        assertEquals(2, memory.evidenceOf(Q).getIndependentSources());
    }

    @Test
    public void onlyLeadsAreLeadsNotEvidence() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(lead("p1", "s1", TEXT_B));
        memory.record(lead("p2", "s2", TEXT_C));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(0, evidence.getDirectCount());
        assertEquals(2, evidence.getLeadCount());
        assertEquals("Belegt: 0 · Weiterführender Hinweis: 2", evidence.summary());
        assertTrue(memory.report(), memory.report().contains("Weiterführender Hinweis (2)"));
        assertFalse(memory.report(), memory.report().contains("Kein Beleg gefunden"));
    }

    @Test
    public void aQuestionWithoutEvidenceShowsKeinBelegGefunden() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(none(Q, "p1", TEXT_C, 1));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals("Kein Beleg gefunden", evidence.summary());
        assertEquals(1, evidence.getJudgedPassages());
        assertTrue(memory.report(), memory.report().contains("Kein Beleg gefunden (1 Passagen geprüft)"));
    }

    @Test
    public void directEvidenceAndLeadsAreCountedApart() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://a.example/x", TEXT_A));
        memory.record(direct("p2", "s2", "https://b.example/y", TEXT_B));
        memory.record(lead("p3", "s3", TEXT_C));
        memory.record(lead("p4", "s4", TEXT_A));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(2, evidence.getDirectCount());
        assertEquals("a text that is direct evidence is no lead as well", 1, evidence.getLeadCount());
        assertEquals("Belegt: 2 (2 Quellen) · Weiterführender Hinweis: 1", evidence.summary());
        assertEquals(Integer.valueOf(2), evidence.counts().get("direkt"));
        assertEquals(Integer.valueOf(1), evidence.counts().get("hinweise"));
        assertEquals(Integer.valueOf(4), evidence.counts().get("geprueft"));
    }

    @Test
    public void theLatestVerdictOnAPassageHolds() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "", TEXT_A));
        memory.record(none(Q, "p1", TEXT_A, 2));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(0, evidence.getDirectCount());
        assertEquals(1, evidence.getJudgedPassages());
        assertEquals(Kind.KEIN, memory.verdict(Q, "p1").getKind());
    }

    // ------------------------------------------------------------------------------- quote check

    @Test
    public void aBelegtWhoseQuoteIsNotInThePassageBecomesALeadAndKeepsBothVerdicts() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        EvidenceVerdict invented = EvidenceVerdict.judged(Q, "p1", "s1", "", TEXT_A, Kind.BELEGT,
                "Der Betriebsrat muss jedem KI-System ausdrücklich zustimmen.", "sagt es", "m", 1, 1L);
        memory.record(invented);

        assertEquals(Kind.BELEGT, invented.getJudgedKind());
        assertEquals(Kind.HINWEIS, invented.getKind());
        assertTrue(invented.isDowngraded());
        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(0, evidence.getDirectCount());
        assertEquals(1, evidence.getLeadCount());
        assertEquals(1, evidence.getDowngraded());
    }

    @Test
    public void theQuoteCheckIgnoresLineBreaksHyphenationAndQuoteMarks() {
        String passage = "Der Betriebsrat hat nach § 87 Abs. 1 Nr. 6 BetrVG ein Mitbestim-\nmungsrecht, wenn "
                + "technische Ein­richtungen zur „Überwachung“ geeignet sind.";
        assertTrue(PassageText.holdsQuote(passage, "\"ein Mitbestimmungsrecht, wenn technische Einrichtungen\""));
        assertTrue(PassageText.holdsQuote(passage, "hat nach § 87 Abs. 1 ... zur \"Überwachung\" geeignet"));
        assertFalse("the order of the parts counts",
                PassageText.holdsQuote(passage, "zur \"Überwachung\" geeignet ... hat nach § 87 Abs. 1"));
        assertFalse("too short to prove anything", PassageText.holdsQuote(passage, "Betriebsrat"));
        assertFalse("a changed word is no quote",
                PassageText.holdsQuote(passage, "ein Zustimmungsrecht, wenn technische Einrichtungen"));
        assertFalse("no quote, no evidence", PassageText.holdsQuote(passage, ""));
    }

    // ------------------------------------------------------------------------------------ errors

    @Test
    public void aFailedJudgementIsAnErrorNotAKeinAndIsOfferedAgain() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(EvidenceVerdict.failed(Q, "p1", "s1", "", TEXT_A, "answer was no JSON", "gemma4:e2b", 1, 1L));

        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals(1, evidence.getErrors());
        assertEquals("a failed call is no judged passage", 0, evidence.getJudgedPassages());
        assertFalse(memory.isJudged(Q, "p1"));
        assertEquals(1, memory.toRetry().size());
        assertEquals("p1", memory.toRetry().get(0).getPassageId());
        assertTrue(memory.report(), memory.report().contains("Fehler: 1 Passagen, werden erneut geprüft"));

        memory.record(direct("p1", "s1", "", TEXT_A));
        assertTrue(memory.isJudged(Q, "p1"));
        assertTrue(memory.toRetry().isEmpty());
        assertEquals(0, memory.evidenceOf(Q).getErrors());
    }

    @Test
    public void aVerdictOnTheSameTextIsOfferedForReuse() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct("p1", "s1", "https://a.example/x", TEXT_A));
        memory.record(EvidenceVerdict.failed(Q, "p2", "s2", "", TEXT_B, "timeout", "m", 1, 1L));

        EvidenceVerdict reused = memory.verdictOnSameText(Q, TEXT_A.replace(" ", "  "));
        assertEquals("p1", reused.getPassageId());
        assertNull("an error is never reused", memory.verdictOnSameText(Q, TEXT_B));
        assertNull(memory.verdictOnSameText(Q, TEXT_C));
    }

    // ---------------------------------------------------------------------------------- questions

    @Test
    public void aFoundQuestionWithoutATopicIsKept() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wie gehen Gewerkschaften mit KI-Monitoring um?", "",
                ResearchQuestion.Origin.FINDER, 3L));

        assertEquals(2, memory.questions().size());
        ResearchQuestion held = memory.question("N01");
        assertEquals(ResearchQuestion.Origin.FINDER, held.getOrigin());
        assertEquals("", held.getTopicId());
        assertEquals("Kein Beleg gefunden", memory.evidenceOf("N01").summary());
    }

    @Test
    public void theSameWordingIsHeldOnce() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        ResearchQuestion again = ResearchQuestion.open("N02", "welche Rechte hat der Betriebsrat bei KI-Systemen",
                "", ResearchQuestion.Origin.LEITUNG, 2L);

        assertSame(memory.question(Q), memory.addQuestion(again));
        assertEquals(1, memory.questions().size());
    }

    @Test
    public void unknownIdsAreRefused() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        try {
            memory.record(none("F99", "p1", TEXT_A, 1));
            fail("verdict on an unknown question");
        } catch (IllegalArgumentException expected) {
            assertNull(memory.evidenceOf("F99"));
        }
        try {
            memory.addQuestion(ResearchQuestion.open("F02", "Was kostet ein Sensor?", "T-unbekannt",
                    ResearchQuestion.Origin.KONZEPT, 1L));
            fail("question on an unknown topic");
        } catch (IllegalArgumentException expected) {
            assertNull(memory.question("F02"));
        }
        try {
            memory.record(new SearchRecord("S1", "Betriebsrat KI", Arrays.asList("F99"), null, "", "PLANER", 1,
                    null, 1L));
            fail("search for an unknown question");
        } catch (IllegalArgumentException expected) {
            assertTrue(memory.searches().isEmpty());
        }
        try {
            new SearchRecord("S2", "Betriebsrat KI", null, null, "", "PLANER", 1, null, 1L);
            fail("a search names a question or a topic");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("names no question and no topic"));
        }
        try {
            memory.record(new LeadDecision(1, Target.FRAGE, "F99", Action.ZURUECKSTELLEN, "", null, "", 1L));
            fail("decision about an unknown question");
        } catch (IllegalArgumentException expected) {
            assertTrue(memory.decisions().isEmpty());
        }
    }

    @Test
    public void aSetAsideQuestionLeavesTheOpenListButKeepsItsEvidence() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(lead("p1", "s1", TEXT_B));
        memory.record(new LeadDecision(4, Target.FRAGE, Q, Action.ZURUECKSTELLEN, "zwei Strategien ohne Ertrag",
                memory.evidenceOf(Q).counts(), "gemma4:12b", 9L));
        memory.updateQuestion(memory.question(Q).withState(ResearchQuestion.State.ZURUECKGESTELLT, 9L));

        assertTrue(memory.openQuestions().isEmpty());
        assertEquals(1, memory.questions().size());
        assertEquals(1, memory.evidenceOf(Q).getLeadCount());
        assertEquals(Action.ZURUECKSTELLEN, memory.decisions().get(0).getAction());
        assertEquals(Integer.valueOf(1), memory.decisions().get(0).getCounts().get("hinweise"));
        assertTrue(memory.report(), memory.report().contains("(zurückgestellt)"));
    }

    @Test
    public void roundsAndSearchesWithoutNewEvidenceCountFromTheLastNewText() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.record(direct(Q, "p1", "s1", "", TEXT_A, 2));
        memory.record(none(Q, "p2", TEXT_B, 3));
        memory.record(direct(Q, "p3", "s2", "", TEXT_A, 4));
        memory.record(none(Q, "p4", TEXT_C, 5));
        QuestionEvidence evidence = memory.evidenceOf(Q);
        assertEquals("the same text in round 4 is nothing new", 2, evidence.getLastRoundWithNewEvidence());
        assertEquals("rounds 3, 4 and 5 judged passages without a new text", 3,
                evidence.getRoundsWithoutNewEvidence());

        memory.record(new SearchRecord("S1", "Betriebsrat KI Mitbestimmung", Arrays.asList(Q), null, "Wortwahl",
                "PLANER", 2, new SearchRecord.Yield(4, 30, 1, 0), 1L));
        memory.record(new SearchRecord("S2", "BetrVG 87 KI", Arrays.asList(Q), null, "Wortwahl", "PLANER", 3,
                new SearchRecord.Yield(3, 12, 0, 0), 2L));
        SearchRecord running = new SearchRecord("S3", "Mitbestimmung Algorithmus", Arrays.asList(Q), null,
                "andere Quellenart", "LEITUNG", 4, null, 3L);
        memory.record(running);
        assertEquals("a running search does not count", 1, memory.evidenceOf(Q).getSearchesWithoutNewEvidence());
        memory.record(running.withYield(new SearchRecord.Yield(2, 8, 0, 1), 4L));
        assertEquals(2, memory.evidenceOf(Q).getSearchesWithoutNewEvidence());
        assertEquals(3, memory.searches().size());
        assertEquals(1, memory.searches().get(2).getYield().getNewTopics());
    }

    @Test
    public void aMergedQuestionsEvidenceCountsForTheQuestionItWasMergedInto() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wann darf der Betriebsrat bei KI mitbestimmen?", "T-recht",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.record(direct(Q, "p1", "s1", "https://a.example/x", TEXT_A, 1));
        memory.record(direct("N01", "p2", "s2", "https://b.example/y", TEXT_B, 2));
        memory.record(new SearchRecord("S1", "BetrVG KI", Arrays.asList("N01"), null, "", "PLANER", 2,
                new SearchRecord.Yield(1, 5, 0, 0), 1L));

        ResearchQuestion merged = memory.question(Q).withMerged(Collections.singletonList(memory.question("N01")),
                3L);
        memory.updateQuestion(merged);

        assertEquals(1, memory.questions().size());
        assertEquals(Q, memory.currentQuestion("N01").getId());
        assertEquals(2, memory.evidenceOf(Q).getDirectCount());
        assertEquals(2, memory.evidenceOf(Q).getIndependentSources());
        assertEquals(1, memory.evidenceOf(Q).getSearchesWithoutNewEvidence());
        assertTrue("judged for the merged question is judged for the survivor", memory.isJudged(Q, "p2"));

        memory.updateQuestion(memory.question(Q).withoutMerged(Collections.singletonList("N01"), 4L));
        assertEquals("a merge can be taken back", 2, memory.questions().size());
        assertEquals(1, memory.evidenceOf(Q).getDirectCount());
        assertEquals(1, memory.evidenceOf("N01").getDirectCount());
    }

    @Test
    public void aQuestionCannotBeMergedIntoItself() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        memory.addQuestion(ResearchQuestion.open("N01", "Wann darf der Betriebsrat bei KI mitbestimmen?", "T-recht",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.updateQuestion(memory.question(Q).withMerged(Collections.singletonList(memory.question("N01")), 3L));
        try {
            memory.updateQuestion(memory.question("N01").withMerged(Collections.singletonList(memory.question(Q)),
                    4L));
            fail("a cycle of merges");
        } catch (IllegalArgumentException expected) {
            assertEquals(Q, memory.currentQuestion("N01").getId());
        }
    }

    // ------------------------------------------------------------------------------------- topics

    @Test
    public void topicsKeepTheirIdWhenMergedAndCountOverAllTheirQuestions() throws Exception {
        ResearchMemory memory = memoryWithQuestion();
        ResearchTopic union = ResearchTopic.found("T-gew", "Gewerkschaftliche Praxis", "Wie Gewerkschaften KI "
                        + "begleiten",
                Collections.singletonList(new ResearchTopic.Finding("s7", "p7", "Die IG Metall")), "gemma4:e2b", 2L);
        ResearchTopic works = ResearchTopic.found("T-bv", "Betriebsvereinbarungen zu KI", "",
                Arrays.asList(new ResearchTopic.Finding("s8", "p8", "Musterbetriebsvereinbarung"),
                        new ResearchTopic.Finding("s7", "p7", "Die IG Metall")), "gemma4:e2b", 2L);
        memory.addTopic(union);
        memory.addTopic(works);
        memory.addQuestion(ResearchQuestion.open("N01", "Was regeln Betriebsvereinbarungen zu KI?", "T-bv",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.addQuestion(ResearchQuestion.open("N02", "Wie begleiten Gewerkschaften KI-Einführungen?", "T-gew",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.record(direct("N01", "p1", "s1", "https://a.example/x", TEXT_A, 2));
        memory.record(direct("N02", "p2", "s2", "https://b.example/y", TEXT_B, 2));

        memory.updateTopic(union.withMerged(Collections.singletonList(works), "gemma4:12b", 3L)
                .withFence(ResearchTopic.Fence.INNEN, "betrifft Mitbestimmung bei KI", "gemma4:12b", 3L));

        assertEquals(Arrays.asList("T-recht", "T-gew"), ids(memory));
        assertEquals("T-gew", memory.currentTopic("T-bv").getId());
        TopicEvidence evidence = memory.evidenceOfTopic("T-gew");
        assertEquals(Arrays.asList("N01", "N02"), evidence.getQuestionIds());
        assertEquals(2, evidence.getDirectCount());
        assertEquals(2, evidence.getIndependentSources());
        assertEquals("the same place found twice counts once", 2, evidence.getFindings());
        assertEquals(ResearchTopic.Fence.INNEN, memory.topic("T-gew").getFence());
        assertEquals(ResearchTopic.Fence.INNEN, memory.topic("T-recht").getFence());
        assertEquals(ResearchTopic.Origin.KONZEPT, memory.topic("T-recht").getOrigin());
    }

    private static java.util.List<String> ids(ResearchMemory memory) {
        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (ResearchTopic topic : memory.topics()) {
            ids.add(topic.getId());
        }
        return ids;
    }

    // --------------------------------------------------------------------------- no completeness claim

    @Test
    public void noFieldClaimsAQuestionIsAnswered() {
        Pattern claim = Pattern.compile("(?i).*(answer|beantwort|complete|vollst|closed|done|finish|resolved|"
                + "covered|abgeschlossen|erledigt).*");
        for (Class<?> type : Arrays.asList(QuestionEvidence.class, ResearchQuestion.class, TopicEvidence.class,
                ResearchMemory.class)) {
            for (Method method : type.getMethods()) {
                assertFalse(type.getSimpleName() + "." + method.getName(), claim.matcher(method.getName()).matches());
            }
        }
        for (ResearchQuestion.State state : ResearchQuestion.State.values()) {
            assertFalse(state.name(), claim.matcher(state.name()).matches());
        }
        assertEquals(2, ResearchQuestion.State.values().length);
    }

    // ------------------------------------------------------------------------------------- domains

    @Test
    public void theDomainIsTheRegistrableName() {
        assertEquals("bmas.de", SourceDomain.of("https://www.bmas.de/DE/Arbeit/x.html"));
        assertEquals("bmas.de", SourceDomain.of("https://service.bmas.de/print"));
        assertEquals("ico.org.uk", SourceDomain.of("https://ico.org.uk/for-organisations/"));
        assertEquals("bund.de", SourceDomain.of("https://www.bmi.bund.de/x"));
        assertEquals("bka.gv.at", SourceDomain.of("https://www.ris.bka.gv.at/x"));
        assertEquals("example.org", SourceDomain.of("example.org/a"));
        assertEquals("", SourceDomain.of(""));
        assertEquals("", SourceDomain.of("not a url"));
    }

    static Map<String, Integer> counts(int direct) {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("direkt", direct);
        return counts;
    }
}
