package com.aresstack.askai.research.candidates;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.ReciprocalRankFusion;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Component 5 of the build plan of 21.11.2026: the passages of the stock that the evidence judge (component 6) should
 * read for one question. No chat model is involved. It reads the project's {@link SemanticKnowledgeIndex} twice: by
 * similarity to the question's vector (the cosine index of the active embedding world) and by the question's key words
 * (the text index).
 *
 * <p>{@link CandidateSearchSettings.Mode#UNION} (default): the {@code perList} passages most similar to the question
 * united with the {@code perList} best by key words. Each list is walked from the top: a text counts once, a source
 * gives at most {@code perSource} passages, empty texts are skipped. In the union a text counts once as well; the
 * two lists are interleaved, so a list cut short keeps the best of both. Measured post hoc on the stock of 17 Nov
 * (architect, 21.11.): the two lists side by side serve 15 of 16 answerable questions, the fused order 11.
 * {@link CandidateSearchSettings.Mode#FUSION} keeps today's mix ({@link ReciprocalRankFusion}, rank constant 60) under
 * the same list rules.</p>
 *
 * <p>Each candidate carries its rank in both rankings. The index's order is not trusted: hits are sorted by score, ties
 * by passage id, so the same stock gives the same list. A failed ranking never empties the list silently. Without a
 * question vector of the stock's world, after a failed similarity search, or when the similarity search finds no
 * passage vector at all, the key-word list stands alone and the list says so; the same holds the other way round.
 * Only when no ranking delivers and one of them failed is the list {@link CandidateList#isFailed() failed}.</p>
 */
public final class CandidateSearch {

    private final SemanticKnowledgeIndex index;
    private final EmbeddingPort embeddings;
    private final CandidateSearchSettings settings;

    public CandidateSearch(SemanticKnowledgeIndex index, EmbeddingPort embeddings, CandidateSearchSettings settings) {
        if (index == null || embeddings == null) {
            throw new IllegalArgumentException("the candidate search needs the index and the embedding port");
        }
        this.index = index;
        this.embeddings = embeddings;
        this.settings = settings == null ? CandidateSearchSettings.defaults() : settings;
    }

    public CandidateSearchSettings getSettings() {
        return settings;
    }

    /** The candidates for one question in the stock's world {@code embeddingFingerprint}. */
    public CandidateList search(String projectId, String embeddingFingerprint, String question) {
        return searchAll(projectId, embeddingFingerprint, Collections.singletonList(question)).get(0);
    }

    /** One list per question, in the given order; the questions are embedded in one call. */
    public List<CandidateList> searchAll(String projectId, String embeddingFingerprint, List<String> questions) {
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("the candidate search needs the stock's embedding world");
        }
        if (questions == null || questions.isEmpty()) {
            return new ArrayList<CandidateList>();
        }
        List<String> texts = new ArrayList<String>(questions.size());
        for (String question : questions) {
            if (question == null || question.trim().isEmpty()) {
                throw new IllegalArgumentException("a candidate search needs the question's text");
            }
            texts.add(question.trim());
        }
        List<float[]> vectors = new ArrayList<float[]>(Collections.<float[]>nCopies(texts.size(), null));
        String vectorProblem = embed(texts, embeddingFingerprint, vectors);
        List<CandidateList> lists = new ArrayList<CandidateList>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            lists.add(searchOne(projectId, embeddingFingerprint, texts.get(i), vectors.get(i), vectorProblem));
        }
        return lists;
    }

    /** Fills {@code vectors} with one query vector per text; returns why there are none, or {@code null}. */
    private String embed(List<String> texts, String fingerprint, List<float[]> vectors) {
        List<EmbeddingPort.EmbeddingVector> embedded;
        try {
            embedded = embeddings.embedQueries(texts);
        } catch (RuntimeException failed) {
            return "the question could not be embedded: " + message(failed);
        }
        if (embedded == null || embedded.size() != texts.size()) {
            return "the embedding port returned " + (embedded == null ? 0 : embedded.size()) + " vectors for "
                    + texts.size() + " questions";
        }
        for (int i = 0; i < embedded.size(); i++) {
            EmbeddingPort.EmbeddingVector vector = embedded.get(i);
            if (vector == null || vector.getDimension() == 0) {
                return "the embedding port returned an empty vector";
            }
            if (!fingerprint.equals(vector.getModelFingerprint())) {
                return "the question was embedded in world " + vector.getModelFingerprint()
                        + ", the passages live in " + fingerprint;
            }
            vectors.set(i, vector.getValues());
        }
        return null;
    }

    private CandidateList searchOne(String projectId, String fingerprint, String question, float[] vector,
                                    String vectorProblem) {
        List<String> notes = new ArrayList<String>();
        Ranking similar = similar(projectId, fingerprint, vector, vectorProblem);
        Ranking keywords = keywords(projectId, fingerprint, question);
        String keywordsAlone = keywords.state == CandidateList.PathState.DELIVERED
                ? "; the key-word list stands alone." : ".";
        if (similar.problem != null) {
            notes.add("Similarity ranking failed (" + similar.problem + ")" + keywordsAlone);
        } else if (similar.state == CandidateList.PathState.EMPTY && keywords.state != CandidateList.PathState.EMPTY) {
            notes.add("The similarity search found no passage vector in world " + fingerprint + keywordsAlone);
        }
        if (keywords.problem != null) {
            notes.add("Key-word ranking failed (" + keywords.problem + ")"
                    + (similar.state == CandidateList.PathState.DELIVERED
                    ? "; the similarity list stands alone." : "."));
        }
        List<Candidate> candidates = settings.getMode() == CandidateSearchSettings.Mode.FUSION
                ? fused(similar, keywords) : united(similar, keywords);
        if (candidates.isEmpty() && similar.problem == null && keywords.problem == null) {
            notes.add("No passage of world " + fingerprint + " matched the question.");
        }
        return new CandidateList(question, settings, candidates, similar.state, similar.hits.size(),
                keywords.state, keywords.hits.size(), notes);
    }

    private Ranking similar(String projectId, String fingerprint, float[] vector, String vectorProblem) {
        if (vector == null) {
            return Ranking.failed(vectorProblem == null ? "no question vector" : vectorProblem);
        }
        try {
            return Ranking.of(index.semanticSearch(projectId,
                    new PassageSemanticQuery(fingerprint, vector, settings.getDepth())), settings.getDepth());
        } catch (RuntimeException failed) {
            return Ranking.failed("the similarity search failed: " + message(failed));
        }
    }

    private Ranking keywords(String projectId, String fingerprint, String question) {
        try {
            return Ranking.of(index.keywordSearch(projectId,
                    new PassageTextQuery(fingerprint, question, settings.getDepth())), settings.getDepth());
        } catch (RuntimeException failed) {
            return Ranking.failed("the key-word search failed: " + message(failed));
        }
    }

    /** The default: both lists, each under the list rules, interleaved; a text once. */
    private List<Candidate> united(Ranking similar, Ranking keywords) {
        Taken bySimilarity = take(similar.hits, settings.getPerList(), settings.getPerSource());
        Taken byKeywords = take(keywords.hits, settings.getPerList(), settings.getPerSource());
        Map<String, Slot> byKey = new LinkedHashMap<String, Slot>();
        int longest = Math.max(bySimilarity.hits.size(), byKeywords.hits.size());
        for (int i = 0; i < longest; i++) {
            if (i < bySimilarity.hits.size()) {
                admit(byKey, bySimilarity, i, Candidate.Via.SIMILARITY);
            }
            if (i < byKeywords.hits.size()) {
                admit(byKey, byKeywords, i, Candidate.Via.KEYWORDS);
            }
        }
        return candidates(byKey, similar, keywords);
    }

    private static void admit(Map<String, Slot> byKey, Taken list, int i, Candidate.Via via) {
        PassageSearchHit hit = list.hits.get(i);
        String key = list.keys.get(i);
        Slot slot = byKey.get(key);
        if (slot == null) {
            slot = new Slot(hit, key, via);
            byKey.put(key, slot);
        } else {
            if (slot.via != via) {
                slot.via = Candidate.Via.BOTH;
            }
            slot.sameText(hit.getPassageId());
        }
        for (String other : list.sameText.get(i)) {
            slot.sameText(other);
        }
    }

    /** Today's mix: one order fused by rank, under the same list rules. */
    private List<Candidate> fused(Ranking similar, Ranking keywords) {
        ReciprocalRankFusion.Result fusion = new ReciprocalRankFusion().fuse(
                keywords.state == CandidateList.PathState.FAILED ? null : keywords.ids(),
                similar.state == CandidateList.PathState.FAILED ? null : similar.ids());
        Map<String, PassageSearchHit> byId = new HashMap<String, PassageSearchHit>();
        for (PassageSearchHit hit : keywords.hits) {
            byId.put(hit.getPassageId(), hit);
        }
        for (PassageSearchHit hit : similar.hits) {
            byId.put(hit.getPassageId(), hit);
        }
        List<PassageSearchHit> order = new ArrayList<PassageSearchHit>(fusion.getEntries().size());
        for (String id : fusion.ids()) {
            order.add(byId.get(id));
        }
        Taken taken = take(order, settings.getPerList(), settings.getPerSource());
        Map<String, Slot> byKey = new LinkedHashMap<String, Slot>();
        for (int i = 0; i < taken.hits.size(); i++) {
            admit(byKey, taken, i, Candidate.Via.FUSION);
        }
        return candidates(byKey, similar, keywords);
    }

    private static List<Candidate> candidates(Map<String, Slot> byKey, Ranking similar, Ranking keywords) {
        List<Candidate> out = new ArrayList<Candidate>(byKey.size());
        for (Slot slot : byKey.values()) {
            String id = slot.hit.getPassageId();
            out.add(new Candidate(id, slot.hit.getSourceId(), slot.hit.getCaptureId(), slot.hit.getHeadingPath(),
                    slot.hit.getText(), slot.key, similar.rank(id), similar.score(id), keywords.rank(id),
                    keywords.score(id), slot.via, slot.sameText));
        }
        return out;
    }

    /**
     * Walks an order from the top: a passage without letters or digits is skipped, a text counts once, a source
     * gives at most {@code perSource} passages, and the walk stops at {@code size}. A passage passed over for its
     * text is noted with the passage that carries the text.
     */
    static Taken take(List<PassageSearchHit> order, int size, int perSource) {
        Taken taken = new Taken();
        Set<String> ids = new HashSet<String>();
        Map<String, Integer> byKey = new HashMap<String, Integer>();
        Map<String, Integer> bySource = new HashMap<String, Integer>();
        for (PassageSearchHit hit : order) {
            if (taken.hits.size() >= size) {
                break;
            }
            if (hit == null || !ids.add(hit.getPassageId())) {
                continue;
            }
            String key = CandidateText.key(hit.getText());
            if (key.isEmpty()) {
                continue;
            }
            Integer same = byKey.get(key);
            if (same != null) {
                taken.sameText.get(same).add(hit.getPassageId());
                continue;
            }
            String source = sourceOf(hit);
            Integer used = bySource.get(source);
            if (used != null && used >= perSource) {
                continue;
            }
            bySource.put(source, used == null ? 1 : used + 1);
            byKey.put(key, taken.hits.size());
            taken.hits.add(hit);
            taken.keys.add(key);
            taken.sameText.add(new ArrayList<String>());
        }
        return taken;
    }

    /** The source a passage counts against; without a source id its capture, without that the passage itself. */
    static String sourceOf(PassageSearchHit hit) {
        if (!hit.getSourceId().trim().isEmpty()) {
            return hit.getSourceId();
        }
        return hit.getCaptureId().trim().isEmpty() ? "passage:" + hit.getPassageId() : "capture:" + hit.getCaptureId();
    }

    private static String message(RuntimeException failed) {
        String message = failed.getMessage();
        return message == null || message.trim().isEmpty() ? failed.getClass().getSimpleName() : message.trim();
    }

    /** One ranking of the stock, sorted by score, ties by passage id, each passage once. */
    private static final class Ranking {
        final CandidateList.PathState state;
        final String problem;
        final List<PassageSearchHit> hits;
        final Map<String, Integer> ranks = new HashMap<String, Integer>();
        final Map<String, Double> scores = new HashMap<String, Double>();

        private Ranking(CandidateList.PathState state, String problem, List<PassageSearchHit> hits) {
            this.state = state;
            this.problem = problem;
            this.hits = hits;
            for (int i = 0; i < hits.size(); i++) {
                ranks.put(hits.get(i).getPassageId(), i + 1);
                scores.put(hits.get(i).getPassageId(), hits.get(i).getScore());
            }
        }

        static Ranking failed(String problem) {
            return new Ranking(CandidateList.PathState.FAILED, problem, new ArrayList<PassageSearchHit>());
        }

        static Ranking of(List<PassageSearchHit> found, int depth) {
            List<PassageSearchHit> sorted = new ArrayList<PassageSearchHit>();
            Set<String> seen = new HashSet<String>();
            List<PassageSearchHit> all = new ArrayList<PassageSearchHit>(
                    found == null ? Collections.<PassageSearchHit>emptyList() : found);
            Collections.sort(all, new Comparator<PassageSearchHit>() {
                @Override
                public int compare(PassageSearchHit a, PassageSearchHit b) {
                    int byScore = Double.compare(b.getScore(), a.getScore());
                    return byScore != 0 ? byScore : a.getPassageId().compareTo(b.getPassageId());
                }
            });
            for (PassageSearchHit hit : all) {
                if (sorted.size() < depth && !hit.getPassageId().isEmpty() && seen.add(hit.getPassageId())) {
                    sorted.add(hit);
                }
            }
            return new Ranking(sorted.isEmpty() ? CandidateList.PathState.EMPTY : CandidateList.PathState.DELIVERED,
                    null, sorted);
        }

        List<String> ids() {
            List<String> ids = new ArrayList<String>(hits.size());
            for (PassageSearchHit hit : hits) {
                ids.add(hit.getPassageId());
            }
            return ids;
        }

        int rank(String passageId) {
            Integer rank = ranks.get(passageId);
            return rank == null ? 0 : rank;
        }

        double score(String passageId) {
            Double score = scores.get(passageId);
            return score == null ? Double.NaN : score;
        }
    }

    /** A list after the list rules: the hits, their text keys, and the passages passed over for the same text. */
    static final class Taken {
        final List<PassageSearchHit> hits = new ArrayList<PassageSearchHit>();
        final List<String> keys = new ArrayList<String>();
        final List<List<String>> sameText = new ArrayList<List<String>>();
    }

    /** One place in the final list. */
    private static final class Slot {
        final PassageSearchHit hit;
        final String key;
        Candidate.Via via;
        final List<String> sameText = new ArrayList<String>();

        Slot(PassageSearchHit hit, String key, Candidate.Via via) {
            this.hit = hit;
            this.key = key;
            this.via = via;
        }

        void sameText(String passageId) {
            if (!passageId.equals(hit.getPassageId()) && !sameText.contains(passageId)) {
                sameText.add(passageId);
            }
        }
    }
}
