package com.aresstack.askai.research.runtime.rerank;

import com.aresstack.askai.browser.search.SearchResultCandidate;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The lexical DEFAULT scorer: BM25 over title + snippet, no model, no browser runtime. It orders by real
 * word overlap (umlauts intact), selects only candidates that matched at all, and serves the same
 * relevance-assessment seam the AI reranker does — so "KI-Reranker aus" is a working search, not a
 * degraded one.
 */
public class Bm25CandidateRerankerTest {

    private static SearchResultCandidate candidate(String id, String title, String snippet) {
        return new SearchResultCandidate(id, "snap", "https://example.test/" + id,
                "https://example.test/" + id, title, snippet, "example.test", 1, "c", "b", 1.0, 1.0,
                Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
    }

    @Test
    public void ordersByLexicalOverlapAndDropsZeroMatches() {
        Bm25CandidateReranker reranker = new Bm25CandidateReranker();
        List<SearchResultCandidate> candidates = Arrays.asList(
                candidate("c-1", "Kochrezepte für Nudeln", "Pasta einfach selbst machen"),
                candidate("c-2", "Hühner richtig halten", "Hühner brauchen Auslauf und Schutz — "
                        + "Hühner sind Herdentiere"),
                candidate("c-3", "Hühner im Garten", "kurze Notiz"));

        SearchResultRerankingResult result = reranker.rerank("hühner halten",
                candidates, null);

        assertEquals(SearchResultRerankingOutcome.SUCCESS, result.outcome);
        assertEquals("the strongest lexical match leads", "c-2",
                result.reranked.get(0).candidate.candidateId);
        for (RerankedSearchResultCandidate selected : result.selected) {
            assertTrue("zero-overlap candidates are honest non-matches, never selected",
                    !"c-1".equals(selected.candidate.candidateId));
        }
        assertEquals(Bm25CandidateReranker.MODEL_NAME, result.modelName);
    }

    @Test
    public void assessScoresArbitraryDocumentsWithoutAModel() {
        LinkedHashMap<String, String> documents = new LinkedHashMap<String, String>();
        documents.put("d-1", "Die Hühnerhaltung im eigenen Garten");
        documents.put("d-2", "Ein Artikel über Aktienfonds");

        com.aresstack.askai.research.domain.search.RelevanceAssessment assessment =
                new Bm25CandidateReranker().assess("hühnerhaltung garten", documents, null);

        assertTrue(assessment.isAvailable());
        assertEquals("d-1", assessment.getScores().get(0).getCandidateId());
        assertTrue(assessment.getScores().get(0).getRelevance()
                > assessment.getScores().get(1).getRelevance());
    }
}
