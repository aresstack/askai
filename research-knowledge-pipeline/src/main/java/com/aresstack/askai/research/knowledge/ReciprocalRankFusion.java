package com.aresstack.askai.research.knowledge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fuses a keyword (BM25) ranking and an embedding-similarity ranking by rank, not by score (ported from the
 * enterprise AI client's {@code ReciprocalRankFusion}, plan section 2.5): {@code score = wK/(k+rankK) + wS/(k+rankS)}.
 * Pure and deterministic; ties are broken by id. Each id counts once per path with its best (first) rank, and ranks
 * are dense over the distinct ids. If one path failed ({@code null} list), the other's ranking stands and the result
 * carries a warning.
 */
public final class ReciprocalRankFusion {

    /** The usual rank constant of the RRF paper and the enterprise AI client's default. */
    public static final int DEFAULT_RANK_CONSTANT = 60;

    /** One fused entry. A rank of 0 means the path did not find it. */
    public static final class Entry {
        private final String id;
        private final double score;
        private final int keywordRank;
        private final int semanticRank;

        Entry(String id, double score, int keywordRank, int semanticRank) {
            this.id = id;
            this.score = score;
            this.keywordRank = keywordRank;
            this.semanticRank = semanticRank;
        }

        public String getId() {
            return id;
        }

        public double getScore() {
            return score;
        }

        public int getKeywordRank() {
            return keywordRank;
        }

        public int getSemanticRank() {
            return semanticRank;
        }
    }

    /** The fused ranking, best first, with a warning when one path failed. */
    public static final class Result {
        private final List<Entry> entries;
        private final String warning;

        Result(List<Entry> entries, String warning) {
            this.entries = Collections.unmodifiableList(entries);
            this.warning = warning;
        }

        public List<Entry> getEntries() {
            return entries;
        }

        public List<String> ids() {
            List<String> ids = new ArrayList<String>(entries.size());
            for (Entry entry : entries) {
                ids.add(entry.getId());
            }
            return ids;
        }

        /** Empty when both paths delivered. */
        public String getWarning() {
            return warning;
        }
    }

    private final double keywordWeight;
    private final double semanticWeight;
    private final int rankConstant;

    public ReciprocalRankFusion() {
        this(1.0, 1.0, DEFAULT_RANK_CONSTANT);
    }

    public ReciprocalRankFusion(double keywordWeight, double semanticWeight, int rankConstant) {
        if (!(keywordWeight >= 0) || !(semanticWeight >= 0) || Double.isInfinite(keywordWeight)
                || Double.isInfinite(semanticWeight) || keywordWeight + semanticWeight <= 0) {
            throw new IllegalArgumentException("weights must be finite, non-negative and not both zero");
        }
        if (rankConstant < 1) {
            throw new IllegalArgumentException("rank constant must be at least 1");
        }
        this.keywordWeight = keywordWeight;
        this.semanticWeight = semanticWeight;
        this.rankConstant = rankConstant;
    }

    /**
     * @param keywordIds  ids in keyword relevance order, or {@code null} when that path failed
     * @param semanticIds ids in embedding-similarity order, or {@code null} when that path failed
     */
    public Result fuse(List<String> keywordIds, List<String> semanticIds) {
        if (keywordIds == null && semanticIds == null) {
            return new Result(new ArrayList<Entry>(), "both ranking paths failed");
        }
        String warning = keywordIds == null ? "keyword ranking failed; embedding ranking stands"
                : semanticIds == null ? "embedding ranking failed; keyword ranking stands" : "";
        Map<String, int[]> ranks = new LinkedHashMap<String, int[]>();
        collect(ranks, keywordIds, 0);
        collect(ranks, semanticIds, 1);
        List<Entry> entries = new ArrayList<Entry>(ranks.size());
        for (Map.Entry<String, int[]> e : ranks.entrySet()) {
            int k = e.getValue()[0];
            int s = e.getValue()[1];
            entries.add(new Entry(e.getKey(), contribution(keywordWeight, k) + contribution(semanticWeight, s), k, s));
        }
        Collections.sort(entries, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int byScore = Double.compare(b.score, a.score);
                return byScore != 0 ? byScore : a.id.compareTo(b.id);
            }
        });
        return new Result(entries, warning);
    }

    private double contribution(double weight, int rank) {
        return rank > 0 ? weight / (rankConstant + rank) : 0.0;
    }

    private static void collect(Map<String, int[]> ranks, List<String> ids, int path) {
        if (ids == null) {
            return;
        }
        int rank = 0;
        for (String id : ids) {
            if (id == null) {
                continue;
            }
            int[] slot = ranks.get(id);
            if (slot == null) {
                slot = new int[2];
                ranks.put(id, slot);
            }
            if (slot[path] == 0) {
                slot[path] = ++rank;
            }
        }
    }
}
