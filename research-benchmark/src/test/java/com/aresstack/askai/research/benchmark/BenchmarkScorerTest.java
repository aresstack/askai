package com.aresstack.askai.research.benchmark;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BenchmarkScorerTest {

    private final BenchmarkQuestionSet set = BenchmarkQuestionSet.load();
    private final BenchmarkScorer scorer = new BenchmarkScorer(BenchmarkSettings.of(
            Collections.<String, String>emptyMap()));

    @Test
    public void aGoodRunScoresFullQualityCompletenessAndDetectsTheContradiction() {
        BenchmarkQuestion question = question("q2-contradiction");
        BenchmarkRunRecord record = finished(question, "SUFFICIENT_EVIDENCE", "q2-maker", "q2-lab", "q2-forum");
        record.statements.add("The manufacturer states a battery capacity of 4,200 mAh for the Kestrel-7 field "
                + "tablet. The lab test measured a battery capacity of 3,800 mAh, which contradicts the maker.");
        record.toolCalls = 10;
        record.toolFailures = 1;

        BenchmarkScorer.RunScores scores = scorer.score(question, record);

        assertEquals(1.0, scores.sourcePrecision, 1e-9);
        assertEquals(1.0, scores.pageRecall, 1e-9);
        assertEquals(0, scores.distractorsAccepted);
        assertEquals(3, scores.factsCovered);
        assertEquals(1.0, scores.factCoverage(), 1e-9);
        assertTrue(scores.sufficient);
        assertTrue(scores.expectedStop);
        assertTrue(scores.successful());
        assertEquals(1, scores.contradictionsDetected);
        assertEquals("text heuristic", scores.contradictionMethod);
        assertEquals(2, scores.claims);
        assertEquals("both statements are backed by an accepted page", 2, scores.supportedClaims);
        assertEquals(0.9, scores.toolCallSuccess, 1e-9);
        assertEquals(0, scores.gapsPlanted);
    }

    @Test
    public void aWeakRunTakesTheDistractorMissesFactsAndInventsClaims() {
        BenchmarkQuestion question = question("q4-distractors");
        BenchmarkRunRecord record = finished(question, "MAX_PAGES", "q4-datasheet", "q4-band");
        record.statements.add("Lindqvist alloy was invented by Swedish engineers during the second world war era.");

        BenchmarkScorer.RunScores scores = scorer.score(question, record);

        assertEquals(0.5, scores.sourcePrecision, 1e-9);
        assertEquals(1, scores.distractorsAccepted);
        assertEquals(1.0 / 3.0, scores.pageRecall, 1e-9);
        assertEquals("q4-f1 and q4-f2 are on the datasheet, pitting is not", 2, scores.factsCovered);
        assertFalse(scores.sufficient);
        assertFalse(scores.expectedStop);
        assertFalse(scores.successful());
        assertEquals(1, scores.claims);
        assertEquals("the invented claim is not on any accepted page", 0, scores.supportedClaims);
        assertNull("no tool calls observed", scores.toolCallSuccess);
    }

    @Test
    public void theGapIsDetectedOnlyWhenNamedWithAGapMarker() {
        BenchmarkQuestion question = question("q3-gap");
        BenchmarkRunRecord silent = finished(question, "SUFFICIENT_EVIDENCE", "q3-council", "q3-paper",
                "q3-contractor");
        silent.statements.add("The renovation costs 14.6 million euros and takes 22 months.");
        assertEquals(0, scorer.score(question, silent).gapsDetected);

        BenchmarkRunRecord reporting = finished(question, "SUFFICIENT_EVIDENCE", "q3-council", "q3-paper",
                "q3-contractor");
        reporting.statements.add("No source covers the environmental impact of the works.");
        BenchmarkScorer.RunScores scores = scorer.score(question, reporting);
        assertEquals(1, scores.gapsDetected);
        assertEquals(1, scores.gapsPlanted);
        assertNull("reporting missing evidence is not a claim that needs a source", scores.supportedShare());
    }

    @Test
    public void structuredReportsReplaceTheTextHeuristic() {
        BenchmarkQuestion question = question("q2-contradiction");
        BenchmarkRunRecord record = finished(question, "SUFFICIENT_EVIDENCE", "q2-maker", "q2-lab", "q2-forum");
        record.statements.add("Both values 4,200 mAh and 3,800 mAh appear.");
        record.reportedContradictions = Collections.emptyList();
        BenchmarkScorer.RunScores scores = scorer.score(question, record);
        assertEquals("an empty structured report means nothing was detected", 0, scores.contradictionsDetected);
        assertEquals("structured", scores.contradictionMethod);

        record.reportedContradictions = Arrays.asList("battery capacity of the Kestrel-7: 4,200 vs 3,800 mAh");
        assertEquals(1, scorer.score(question, record).contradictionsDetected);
    }

    @Test
    public void anUnfinishedRunNeverMeetsTheExpectedStop() {
        BenchmarkQuestion question = question("q1-sufficiency");
        BenchmarkRunRecord record = finished(question, "SUFFICIENT_EVIDENCE", "q1-primer");
        record.finished = false;
        // A source outside the corpus (the engine page, say): accepted, but neither relevant nor a distractor.
        record.acceptedSources.add(new BenchmarkRunRecord.AcceptedSource("http://127.0.0.1:1/find?q=x", "Find",
                "results"));
        record.resolvePages(url -> null);
        BenchmarkScorer.RunScores scores = scorer.score(question, record);
        assertFalse(scores.expectedStop);
        assertEquals(2, scores.acceptedSources);
        assertEquals(0.0, scores.sourcePrecision, 1e-9);
        assertEquals(0, scores.distractorsAccepted);
    }

    @Test
    public void contentWordsKeepNumbersWithSeparators() {
        assertTrue(BenchmarkScorer.contentWords("It costs 14.6 million euros, about 4,200 units.")
                .containsAll(Arrays.asList("14.6", "4,200", "million", "euros", "units", "costs")));
        assertFalse(BenchmarkScorer.contentWords("the and with").iterator().hasNext());
    }

    private BenchmarkQuestion question(String id) {
        return set.select(Collections.singletonList(id)).get(0);
    }

    /** A finished record that accepted the given pages (each page on a port per host, as in a live world). */
    private static BenchmarkRunRecord finished(BenchmarkQuestion question, String stopReason, String... pageIds) {
        BenchmarkRunRecord record = new BenchmarkRunRecord("test", "model", question.getId(), 1);
        record.finished = true;
        record.stopReason = stopReason;
        Map<String, Integer> ports = new HashMap<String, Integer>();
        Map<String, BenchmarkQuestion.Page> byUrl = new HashMap<String, BenchmarkQuestion.Page>();
        for (BenchmarkQuestion.Page page : question.getPages()) {
            int port = ports.computeIfAbsent(page.getHost(), host -> 40000 + ports.size());
            byUrl.put("http://127.0.0.1:" + port + page.getPath(), page);
        }
        for (String pageId : pageIds) {
            BenchmarkQuestion.Page page = question.page(pageId);
            String url = "http://127.0.0.1:" + ports.get(page.getHost()) + page.getPath();
            record.acceptedSources.add(new BenchmarkRunRecord.AcceptedSource(url, page.getTitle(),
                    String.join("\n", page.getParagraphs())));
        }
        record.resolvePages(byUrl::get);
        return record;
    }
}
