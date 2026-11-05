package com.aresstack.askai.research.benchmark.retrieval;

import java.util.List;

/** Known-item retrieval metrics (plan 5.4 item 7): Recall@k and reciprocal rank of one target per query. */
public final class RetrievalMetrics {

    private RetrievalMetrics() {
    }

    /** 1-based rank of the target in the ranking, 0 when it is missing. */
    public static int rankOf(List<String> ranking, String targetId) {
        if (ranking == null || targetId == null) {
            return 0;
        }
        int index = ranking.indexOf(targetId);
        return index < 0 ? 0 : index + 1;
    }

    /** 1 when the target is within the first k, otherwise 0 (one known item per query). */
    public static double recallAt(int rank, int k) {
        return rank > 0 && rank <= k ? 1.0 : 0.0;
    }

    public static double reciprocalRank(int rank) {
        return rank > 0 ? 1.0 / rank : 0.0;
    }

    /** Aggregate over the ranks of one group. */
    public static final class Summary {
        public final int queries;
        public final double recallAt5;
        public final double recallAt10;
        public final double meanReciprocalRank;

        Summary(int queries, double recallAt5, double recallAt10, double meanReciprocalRank) {
            this.queries = queries;
            this.recallAt5 = recallAt5;
            this.recallAt10 = recallAt10;
            this.meanReciprocalRank = meanReciprocalRank;
        }
    }

    public static Summary summarize(List<Integer> ranks) {
        if (ranks == null || ranks.isEmpty()) {
            return new Summary(0, 0, 0, 0);
        }
        double r5 = 0;
        double r10 = 0;
        double rr = 0;
        for (int rank : ranks) {
            r5 += recallAt(rank, 5);
            r10 += recallAt(rank, 10);
            rr += reciprocalRank(rank);
        }
        int n = ranks.size();
        return new Summary(n, r5 / n, r10 / n, rr / n);
    }

    /** Share of queries whose target is among the visited top N (the ranking of planned queries). */
    public static double hitRate(List<Integer> ranks, int topN) {
        if (ranks == null || ranks.isEmpty()) {
            return 0;
        }
        double hits = 0;
        for (int rank : ranks) {
            hits += recallAt(rank, topN);
        }
        return hits / ranks.size();
    }
}
