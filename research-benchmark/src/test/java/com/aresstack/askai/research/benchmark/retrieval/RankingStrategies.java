package com.aresstack.askai.research.benchmark.retrieval;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.ReciprocalRankFusion;
import com.aresstack.askai.research.knowledge.VectorMath;
import com.aresstack.askai.research.runtime.rerank.CandidateReranker;
import com.aresstack.askai.research.runtime.rerank.RerankedSearchResultCandidate;
import com.aresstack.askai.research.runtime.rerank.SearchResultRerankingOutcome;
import com.aresstack.askai.research.runtime.rerank.SearchResultRerankingResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The four rankings of planned queries the benchmark compares (plan 5.4 item 7): BM25 and the installed
 * cross-encoder through the productive {@link CandidateReranker}s, embedding similarity through the productive
 * {@link EmbeddingPort} (query role against title and snippet as documents), and BM25 fused with embedding
 * similarity by {@link ReciprocalRankFusion}. Each returns the result ids in ranked order and whether it selected
 * nothing at all ({@code NO_SEMANTIC_MATCHES}).
 */
public final class RankingStrategies {

    /** One strategy's ranking of one result list. */
    public static final class Ranking {
        public final List<String> ids;
        public final boolean selectedNothing;
        public final String problem;

        Ranking(List<String> ids, boolean selectedNothing, String problem) {
            this.ids = Collections.unmodifiableList(ids);
            this.selectedNothing = selectedNothing;
            this.problem = problem == null ? "" : problem;
        }
    }

    private RankingStrategies() {
    }

    /** A reranker's SELECTED candidates (what would be visited), in rank order. */
    public static Ranking byReranker(CandidateReranker reranker, String query,
                                     List<RetrievalBenchmarkData.SearchResult> results) {
        SearchResultRerankingResult result = reranker.rerank(query, candidates(results), CancellationSignal.NONE);
        List<String> ids = new ArrayList<String>();
        for (RerankedSearchResultCandidate entry : result.selected) {
            ids.add(entry.candidate.candidateId);
        }
        boolean nothing = result.outcome == SearchResultRerankingOutcome.NO_SEMANTIC_MATCHES;
        String problem = result.outcome == SearchResultRerankingOutcome.SUCCESS || nothing
                ? "" : result.outcome + ": " + result.diagnostics;
        return new Ranking(ids, nothing, problem);
    }

    /** Cosine of the query (query role) against title + snippet (document role). */
    public static Ranking byEmbedding(EmbeddingPort port, String query,
                                      List<RetrievalBenchmarkData.SearchResult> results) {
        List<String> texts = new ArrayList<String>();
        for (RetrievalBenchmarkData.SearchResult r : results) {
            texts.add(documentText(r));
        }
        final EmbeddingPort.EmbeddingVector q = port.embedQueries(Collections.singletonList(query)).get(0);
        final List<EmbeddingPort.EmbeddingVector> docs = port.embedDocuments(texts);
        List<Integer> order = new ArrayList<Integer>();
        final double[] scores = new double[results.size()];
        for (int i = 0; i < results.size(); i++) {
            order.add(i);
            scores[i] = VectorMath.cosine(q, docs.get(i));
        }
        Collections.sort(order, new Comparator<Integer>() {
            public int compare(Integer a, Integer b) {
                int c = Double.compare(scores[b], scores[a]);
                return c != 0 ? c : Integer.compare(a, b);
            }
        });
        List<String> ids = new ArrayList<String>();
        for (int index : order) {
            ids.add(results.get(index).id);
        }
        return new Ranking(ids, false, "");
    }

    /** BM25's full ranking fused with the embedding ranking by rank. */
    public static Ranking byFusion(Ranking bm25, Ranking embedding) {
        ReciprocalRankFusion.Result fused = new ReciprocalRankFusion().fuse(
                bm25.problem.isEmpty() ? bm25.ids : null, embedding.problem.isEmpty() ? embedding.ids : null);
        return new Ranking(fused.ids(), fused.ids().isEmpty(), fused.getWarning());
    }

    static String documentText(RetrievalBenchmarkData.SearchResult r) {
        return (r.title + " " + r.snippet).trim();
    }

    static List<SearchResultCandidate> candidates(List<RetrievalBenchmarkData.SearchResult> results) {
        List<SearchResultCandidate> out = new ArrayList<SearchResultCandidate>();
        for (int i = 0; i < results.size(); i++) {
            RetrievalBenchmarkData.SearchResult r = results.get(i);
            out.add(new SearchResultCandidate(r.id, "retrieval-benchmark", r.url, r.url, r.title, r.snippet,
                    host(r.url), i + 1, "", "", 1.0, 1.0, Collections.<SearchResultSiteLink>emptyList()));
        }
        return out;
    }

    private static String host(String url) {
        try {
            String h = new java.net.URI(url).getHost();
            return h == null ? "" : h;
        } catch (Exception malformed) {
            return "";
        }
    }
}
