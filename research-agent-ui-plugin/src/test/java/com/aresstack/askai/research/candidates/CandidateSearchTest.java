package com.aresstack.askai.research.candidates;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 5: the candidate search on a hand-made index (rankings are given, the list rules are tested). */
public class CandidateSearchTest {

    private static final String WORLD = "world-1";
    private static final String PROJECT = "p";
    private static final CandidateSearchSettings THREE = new CandidateSearchSettings(
            CandidateSearchSettings.Mode.UNION, 3, 2, 50);

    @Test
    public void theUnionTakesTheBestOfBothListsInTurn() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("s1", "A", 0.9), hit("s2", "B", 0.8), hit("s3", "C", 0.7), hit("s4", "D", 0.6));
        index.keyword = hits(hit("k1", "E", 9), hit("s2", "B", 8), hit("k3", "F", 7), hit("k4", "G", 6));
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "  Frage? ");

        assertEquals(Arrays.asList("s1", "k1", "s2", "s3", "k3"), list.passageIds());
        assertEquals(CandidateList.PathState.DELIVERED, list.getSimilarity());
        assertEquals(CandidateList.PathState.DELIVERED, list.getKeywords());
        assertFalse(list.isFailed());
        assertTrue(list.getNotes().isEmpty());
        assertEquals("Frage?", list.getQuestion());
        Candidate s1 = list.getCandidates().get(0);
        assertEquals(Candidate.Via.SIMILARITY, s1.getVia());
        assertEquals(1, s1.getSimilarityRank());
        assertEquals(0.9, s1.getSimilarity(), 1e-9);
        assertEquals("the key-word ranking never reached it", 0, s1.getKeywordRank());
        assertTrue(Double.isNaN(s1.getKeywordScore()));
        assertEquals("A", s1.getSourceId());
        Candidate k1 = list.getCandidates().get(1);
        assertEquals(Candidate.Via.KEYWORDS, k1.getVia());
        assertEquals(0, k1.getSimilarityRank());
        assertEquals(1, k1.getKeywordRank());
        Candidate s2 = list.getCandidates().get(2);
        assertEquals(Candidate.Via.BOTH, s2.getVia());
        assertEquals(2, s2.getSimilarityRank());
        assertEquals(2, s2.getKeywordRank());
        assertEquals(8.0, s2.getKeywordScore(), 1e-9);
    }

    @Test
    public void aSourceGivesAtMostTwoPassagesToEachList() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("a1", "A", 0.9), hit("a2", "A", 0.8), hit("a3", "A", 0.7), hit("b1", "B", 0.6));
        index.keyword = hits(hit("a4", "A", 9), hit("a5", "A", 8), hit("a6", "A", 7), hit("c1", "C", 6));
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");

        assertEquals("two of A from each list, so four in the union", Arrays.asList("a1", "a4", "a2", "a5", "b1",
                "c1"), list.passageIds());
    }

    @Test
    public void aPassageWithoutSourceCountsAgainstItsCaptureOrItself() {
        assertEquals("S", CandidateSearch.sourceOf(new PassageSearchHit("p", "c", "S", "t", "", 1)));
        assertEquals("capture:c", CandidateSearch.sourceOf(new PassageSearchHit("p", "c", " ", "t", "", 1)));
        assertEquals("passage:p", CandidateSearch.sourceOf(new PassageSearchHit("p", "", "", "t", "", 1)));
    }

    @Test
    public void aTextCountsOnceInEachListAndInTheUnion() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("x1", "A", "Same text here.", 0.9), hit("x2", "B", "Same  text, here!", 0.8),
                hit("e1", "B", " ... ", 0.75), hit("y1", "C", 0.7), hit("y2", "D", 0.6));
        index.keyword = hits(hit("x3", "E", "same TEXT here", 9), hit("z1", "F", 8));
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");

        assertEquals("x2 and x3 carry the text of x1, e1 has no text", Arrays.asList("x1", "y1", "z1", "y2"),
                list.passageIds());
        Candidate x1 = list.getCandidates().get(0);
        assertEquals("the text came up in both lists", Candidate.Via.BOTH, x1.getVia());
        assertEquals(Arrays.asList("x2", "x3"), x1.getSameTextPassageIds());
        assertEquals("same text here", x1.getTextKey());
        assertEquals("x1's own key-word rank: none", 0, x1.getKeywordRank());
    }

    @Test
    public void theIndexOrderDoesNotMatter() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("p3", "C", 0.5), hit("p1", "A", 0.9), hit("p2", "B", 0.5), hit("p1", "A", 0.9));
        index.keyword = hits();
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");

        assertEquals("by score, ties by passage id, each passage once", Arrays.asList("p1", "p2", "p3"),
                list.passageIds());
        assertEquals(3, list.getCandidates().get(2).getSimilarityRank());
        assertEquals(3, list.getSimilarityRead());
        assertEquals(CandidateList.PathState.EMPTY, list.getKeywords());
        assertTrue("no key word in the stock is no failure", list.getNotes().isEmpty());
    }

    @Test
    public void theFusionKeepsTodaysMixUnderTheSameListRules() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("a", "S1", 0.9), hit("b", "S2", 0.8), hit("c", "S1", 0.7), hit("d", "S1", 0.6));
        index.keyword = hits(hit("c", "S1", 9), hit("d", "S1", 8), hit("e", "S3", 7));
        CandidateList list = new CandidateSearch(index, new FakePort(),
                THREE.withMode(CandidateSearchSettings.Mode.FUSION)).search(PROJECT, WORLD, "Frage");

        // Fused by rank: c (1/63 + 1/61), d (1/64 + 1/62), a (1/61), b (1/62), e (1/63); a is S1's third passage.
        assertEquals(Arrays.asList("c", "d", "b"), list.passageIds());
        assertEquals(CandidateSearchSettings.Mode.FUSION, list.getMode());
        Candidate c = list.getCandidates().get(0);
        assertEquals(Candidate.Via.FUSION, c.getVia());
        assertEquals(3, c.getSimilarityRank());
        assertEquals(1, c.getKeywordRank());
    }

    @Test
    public void withoutAQuestionVectorTheKeyWordListStandsAlone() {
        FakeIndex index = twoRankings();
        FakePort port = new FakePort();
        port.failure = new IllegalStateException("connection refused");
        CandidateList list = new CandidateSearch(index, port, THREE).search(PROJECT, WORLD, "Frage");

        assertEquals(Arrays.asList("k1", "k2", "k3"), list.passageIds());
        assertEquals(CandidateList.PathState.FAILED, list.getSimilarity());
        assertEquals(0, index.semanticCalls);
        assertFalse(list.isFailed());
        assertEquals(1, list.getNotes().size());
        assertTrue(list.getNotes().get(0), list.getNotes().get(0).contains("connection refused"));
        assertTrue(list.getNotes().get(0).contains("the key-word list stands alone"));
    }

    @Test
    public void aVectorOfAnotherWorldIsNoQuestionVector() {
        FakeIndex index = twoRankings();
        FakePort port = new FakePort();
        port.fingerprint = "world-2";
        CandidateList list = new CandidateSearch(index, port, THREE).search(PROJECT, WORLD, "Frage");

        assertEquals(CandidateList.PathState.FAILED, list.getSimilarity());
        assertEquals(0, index.semanticCalls);
        assertTrue(list.getNotes().get(0), list.getNotes().get(0).contains("world world-2"));
        assertEquals(Arrays.asList("k1", "k2", "k3"), list.passageIds());
    }

    @Test
    public void aFailedSimilaritySearchLeavesTheKeyWordList() {
        FakeIndex index = twoRankings();
        index.semanticFailure = new IllegalStateException("vector file damaged");
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");

        assertEquals(CandidateList.PathState.FAILED, list.getSimilarity());
        assertTrue(list.getNotes().get(0), list.getNotes().get(0).contains("vector file damaged"));
        assertEquals(Arrays.asList("k1", "k2", "k3"), list.passageIds());
        assertFalse(list.isFailed());
    }

    @Test
    public void passagesWithoutVectorsLeaveTheKeyWordList() {
        FakeIndex index = twoRankings();
        index.semantic = hits();
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");

        assertEquals(CandidateList.PathState.EMPTY, list.getSimilarity());
        assertEquals(Arrays.asList("k1", "k2", "k3"), list.passageIds());
        assertEquals(1, list.getNotes().size());
        assertTrue(list.getNotes().get(0), list.getNotes().get(0).contains("no passage vector in world world-1"));
    }

    @Test
    public void aFailedKeyWordSearchLeavesTheSimilarityList() {
        FakeIndex index = twoRankings();
        index.keywordFailure = new IllegalStateException("text index locked");
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");

        assertEquals(CandidateList.PathState.FAILED, list.getKeywords());
        assertEquals(Arrays.asList("s1", "s2", "s3"), list.passageIds());
        assertTrue(list.getNotes().get(0), list.getNotes().get(0).contains("text index locked"));
        assertTrue(list.getNotes().get(0).contains("the similarity list stands alone"));
    }

    @Test
    public void withNothingDeliveredAndAFailureTheListIsFailedNotEmpty() {
        FakeIndex both = twoRankings();
        both.keywordFailure = new IllegalStateException("text index locked");
        FakePort port = new FakePort();
        port.failure = new IllegalStateException("connection refused");
        CandidateList failed = new CandidateSearch(both, port, THREE).search(PROJECT, WORLD, "Frage");
        assertTrue(failed.isFailed());
        assertEquals(0, failed.size());
        assertEquals(2, failed.getNotes().size());

        FakeIndex noVectors = twoRankings();
        noVectors.semantic = hits();
        noVectors.keywordFailure = new IllegalStateException("text index locked");
        CandidateList noVectorsFailed = new CandidateSearch(noVectors, new FakePort(), THREE).search(PROJECT, WORLD,
                "Frage");
        assertTrue(noVectorsFailed.isFailed());
        assertEquals("both causes are named", 2, noVectorsFailed.getNotes().size());
        assertTrue(noVectorsFailed.getNotes().get(0), noVectorsFailed.getNotes().get(0).endsWith("world world-1."));

        FakeIndex empty = new FakeIndex();
        CandidateList none = new CandidateSearch(empty, new FakePort(), THREE).search(PROJECT, WORLD, "Frage");
        assertFalse("an empty stock is no error", none.isFailed());
        assertEquals(0, none.size());
        assertTrue(none.getNotes().get(0), none.getNotes().get(0).contains("No passage of world world-1"));
    }

    @Test
    public void bothRankingsAreReadToTheDepth() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("s1", "A", 0.9), hit("s2", "B", 0.8), hit("s3", "C", 0.7), hit("s4", "D", 0.6),
                hit("s5", "E", 0.5));
        index.keyword = hits(hit("s5", "E", 9));
        CandidateSearchSettings settings = new CandidateSearchSettings(CandidateSearchSettings.Mode.UNION, 2, 2, 3);
        CandidateList list = new CandidateSearch(index, new FakePort(), settings).search(PROJECT, WORLD, " Frage ");

        assertEquals(3, index.semanticMax);
        assertEquals(3, index.keywordMax);
        assertEquals("Frage", index.lastText);
        assertEquals(WORLD, index.lastFingerprint);
        assertEquals(3, list.getSimilarityRead());
        assertEquals(Arrays.asList("s1", "s5", "s2"), list.passageIds());
        assertEquals("s5 lies below the depth of the similarity ranking", 0,
                list.getCandidates().get(1).getSimilarityRank());
    }

    /** Architect review of #260: the text index gets the question's content words, as the search plan takes them. */
    @Test
    public void theKeyWordQueryIsTheQuestionsContentWords() {
        FakeIndex index = twoRankings();
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD,
                "Welche Sensoren messen die Herzfrequenz bei der Arbeit?");
        assertEquals("Sensoren messen Herzfrequenz", index.lastText);
        assertEquals("Sensoren messen Herzfrequenz", list.getKeywordQuery());
        assertEquals("an acronym stays, a stopword goes", "hilft KI Arbeitsschutz",
                CandidateSearch.keywordQuery("Wie hilft KI beim Arbeitsschutz?"));
    }

    @Test
    public void aQuestionWithoutKeyWordsLeavesTheSimilarityList() {
        FakeIndex index = twoRankings();
        CandidateList list = new CandidateSearch(index, new FakePort(), THREE).search(PROJECT, WORLD,
                "Was ist das?");
        assertEquals("", list.getKeywordQuery());
        assertEquals("the text index is not asked", 0, index.keywordCalls);
        assertEquals(CandidateList.PathState.EMPTY, list.getKeywords());
        assertEquals(Arrays.asList("s1", "s2", "s3"), list.passageIds());
        assertEquals(1, list.getNotes().size());
        assertTrue(list.getNotes().get(0), list.getNotes().get(0).contains("no key word")
                && list.getNotes().get(0).endsWith("the similarity list stands alone."));
        assertFalse(list.isFailed());
    }

    @Test
    public void searchAllEmbedsTheQuestionsInOneCall() {
        FakeIndex index = twoRankings();
        FakePort port = new FakePort();
        List<CandidateList> lists = new CandidateSearch(index, port, THREE).searchAll(PROJECT, WORLD,
                Arrays.asList("Erste Frage", "Zweite Frage"));

        assertEquals(1, port.calls);
        assertEquals(EmbeddingPort.Role.QUERY, port.lastRole);
        assertEquals(Arrays.asList("Erste Frage", "Zweite Frage"), port.lastTexts);
        assertEquals(2, lists.size());
        assertEquals("Zweite Frage", lists.get(1).getQuestion());
        assertEquals(2, index.semanticCalls);
        assertEquals(2, index.keywordCalls);
    }

    @Test
    public void aSearchNeedsAQuestionAndAWorld() {
        CandidateSearch search = new CandidateSearch(new FakeIndex(), new FakePort(), THREE);
        try {
            search.search(PROJECT, WORLD, " ");
            fail("a blank question");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        try {
            search.search(PROJECT, "", "Frage");
            fail("no world");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        assertTrue(search.searchAll(PROJECT, WORLD, new ArrayList<String>()).isEmpty());
    }

    @Test
    public void theDefaultsAreTheMeasuredBest() {
        CandidateSearchSettings defaults = new CandidateSearch(new FakeIndex(), new FakePort(), null).getSettings();
        assertEquals(CandidateSearchSettings.Mode.UNION, defaults.getMode());
        assertEquals(20, defaults.getPerList());
        assertEquals(2, defaults.getPerSource());
        assertEquals(1000, defaults.getDepth());
    }

    @Test
    public void theSettingsComeFromTheWorkspace() {
        MapStore store = new MapStore();
        store.put(CandidateSearchSettings.KEY_MODE, " fusion ");
        store.put(CandidateSearchSettings.KEY_PER_LIST, "30");
        store.put(CandidateSearchSettings.KEY_PER_SOURCE, "3");
        store.put(CandidateSearchSettings.KEY_DEPTH, "200");
        CandidateSearchSettings loaded = CandidateSearchSettings.load(store);
        assertEquals(CandidateSearchSettings.Mode.FUSION, loaded.getMode());
        assertEquals(30, loaded.getPerList());
        assertEquals(3, loaded.getPerSource());
        assertEquals(200, loaded.getDepth());

        store.put(CandidateSearchSettings.KEY_MODE, "rerank");
        store.put(CandidateSearchSettings.KEY_PER_LIST, "x");
        store.put(CandidateSearchSettings.KEY_PER_SOURCE, "-1");
        store.put(CandidateSearchSettings.KEY_DEPTH, "99999");
        CandidateSearchSettings fallback = CandidateSearchSettings.load(store);
        assertEquals(CandidateSearchSettings.Mode.UNION, fallback.getMode());
        assertEquals(20, fallback.getPerList());
        assertEquals(2, fallback.getPerSource());
        assertEquals("cut at the widest window", CandidateSearchSettings.MAX_DEPTH, fallback.getDepth());
        assertEquals("the depth never falls below the list", 50,
                new CandidateSearchSettings(CandidateSearchSettings.Mode.UNION, 50, 2, 10).getDepth());
        assertEquals(CandidateSearchSettings.defaults().toString(), CandidateSearchSettings.load(null).toString());
    }

    @Test
    public void theTextKeyIsTheRuleOfTheMemory() {
        assertEquals("schutzausrüstung für baustellen 2026", CandidateText.key(
                "Schutz­ausrüs-\n  tung – für \"Baustellen\" (2026)!"));
        assertEquals("", CandidateText.key(" ... "));
        assertEquals("", CandidateText.key(null));
        StringBuilder longText = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            longText.append("Wort ").append(i).append(". ");
        }
        assertEquals(CandidateText.KEY_CHARS, CandidateText.key(longText.toString()).length());
    }

    private static FakeIndex twoRankings() {
        FakeIndex index = new FakeIndex();
        index.semantic = hits(hit("s1", "A", 0.9), hit("s2", "B", 0.8), hit("s3", "C", 0.7));
        index.keyword = hits(hit("k1", "D", 9), hit("k2", "E", 8), hit("k3", "F", 7));
        return index;
    }

    private static PassageSearchHit hit(String id, String source, double score) {
        return hit(id, source, "Text of " + id + ".", score);
    }

    private static PassageSearchHit hit(String id, String source, String text, double score) {
        return new PassageSearchHit(id, "cap-" + id, source, text, "", score);
    }

    private static List<PassageSearchHit> hits(PassageSearchHit... hits) {
        return new ArrayList<PassageSearchHit>(Arrays.asList(hits));
    }

    /** Returns the given rankings as they are (unsorted when given unsorted) and records the queries. */
    private static final class FakeIndex implements SemanticKnowledgeIndex {
        List<PassageSearchHit> semantic = new ArrayList<PassageSearchHit>();
        List<PassageSearchHit> keyword = new ArrayList<PassageSearchHit>();
        RuntimeException semanticFailure;
        RuntimeException keywordFailure;
        int semanticCalls;
        int keywordCalls;
        int semanticMax;
        int keywordMax;
        String lastText;
        String lastFingerprint;

        @Override
        public List<PassageSearchHit> keywordSearch(String projectId, PassageTextQuery query) {
            keywordCalls++;
            keywordMax = query.getMaxResults();
            lastText = query.getText();
            lastFingerprint = query.getEmbeddingFingerprint();
            if (keywordFailure != null) {
                throw keywordFailure;
            }
            return new ArrayList<PassageSearchHit>(keyword);
        }

        @Override
        public List<PassageSearchHit> semanticSearch(String projectId, PassageSemanticQuery query) {
            semanticCalls++;
            semanticMax = query.getMaxResults();
            if (semanticFailure != null) {
                throw semanticFailure;
            }
            return new ArrayList<PassageSearchHit>(semantic);
        }

        @Override
        public void indexPassages(String projectId, Collection<PassageIndexDocument> passages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void replacePassagesForCapture(String projectId, String embeddingFingerprint, String captureId,
                                              Collection<PassageIndexDocument> passages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void rebuild(String projectId, Collection<PassageIndexDocument> passages) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeProject(String projectId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakePort implements EmbeddingPort {
        String fingerprint = WORLD;
        RuntimeException failure;
        int calls;
        Role lastRole;
        List<String> lastTexts;

        @Override
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            calls++;
            lastRole = role;
            lastTexts = new ArrayList<String>(texts);
            if (failure != null) {
                throw failure;
            }
            List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
            for (int i = 0; i < texts.size(); i++) {
                vectors.add(new EmbeddingVector("fake", fingerprint, new float[] {1f, 0f, 0f}));
            }
            return vectors;
        }
    }

    private static final class MapStore implements WorkspaceStateStore {
        private final Map<String, String> values = new HashMap<String, String>();

        @Override
        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        @Override
        public int getInt(String key, int defaultValue) {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
        }

        @Override
        public void put(String key, String value) {
            values.put(key, value);
        }

        @Override
        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        @Override
        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }
}
