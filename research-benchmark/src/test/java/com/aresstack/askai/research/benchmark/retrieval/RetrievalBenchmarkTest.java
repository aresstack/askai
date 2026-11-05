package com.aresstack.askai.research.benchmark.retrieval;

import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.runtime.rerank.Bm25CandidateReranker;
import com.google.gson.JsonParser;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Model-free checks of the retrieval benchmark's metrics, data loading and ranking strategies. */
public class RetrievalBenchmarkTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void metricsOfKnownItemRetrieval() {
        assertEquals(3, RetrievalMetrics.rankOf(Arrays.asList("a", "b", "t"), "t"));
        assertEquals(0, RetrievalMetrics.rankOf(Arrays.asList("a", "b"), "t"));
        RetrievalMetrics.Summary s = RetrievalMetrics.summarize(Arrays.asList(1, 6, 0, 2));
        assertEquals(0.5, s.recallAt5, 1e-12);
        assertEquals(0.75, s.recallAt10, 1e-12);
        assertEquals((1 + 1 / 6.0 + 0 + 0.5) / 4, s.meanReciprocalRank, 1e-12);
        assertEquals(0.5, RetrievalMetrics.hitRate(Arrays.asList(1, 6, 0, 2), 5), 1e-12);
    }

    @Test
    public void theCheckedInQueryFileLoads() throws Exception {
        RetrievalBenchmarkData.QuerySet set = RetrievalBenchmarkData.loadQueries();
        for (RetrievalBenchmarkData.Query q : set.queries) {
            assertFalse(q.text.isEmpty());
        }
    }

    @Test
    public void loadingValidatesTargetsAgainstTheCorpus() throws Exception {
        RetrievalBenchmarkData.QuerySet set = RetrievalBenchmarkData.parseQueries(JsonParser.parseString(
                "{\"topic\":\"t\",\"queries\":[{\"id\":\"q1\",\"group\":\"DE_EN\",\"text\":\"Kupfer\","
                        + "\"targetPassageId\":\"p2\"}],\"conceptNodes\":[{\"id\":\"n1\",\"labelPath\":\"A > B\"}],"
                        + "\"labelledPassages\":[{\"passageId\":\"p1\",\"nodeId\":\"n1\"}]}").getAsJsonObject());
        File dir = tmp.newFolder();
        Files.write(new File(dir, "corpus.jsonl").toPath(), Arrays.asList(
                "{\"id\":\"p1\",\"language\":\"en\",\"text\":\"one\"}"), StandardCharsets.UTF_8);
        try {
            RetrievalBenchmarkData.loadCorpus(dir, set);
            fail("missing target must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("p2"));
        }
        Files.write(new File(dir, "corpus.jsonl").toPath(), Arrays.asList(
                "{\"id\":\"p1\",\"language\":\"en\",\"text\":\"one\"}",
                "{\"id\":\"p2\",\"language\":\"en\",\"text\":\"two\"}"), StandardCharsets.UTF_8);
        assertEquals(2, RetrievalBenchmarkData.loadCorpus(dir, set).size());
        assertEquals(null, RetrievalBenchmarkData.loadSearchResults(dir, "q1"));
    }

    @Test
    public void unknownConceptNodesAreRejected() {
        try {
            RetrievalBenchmarkData.parseQueries(JsonParser.parseString(
                    "{\"labelledPassages\":[{\"passageId\":\"p1\",\"nodeId\":\"nope\"}]}").getAsJsonObject());
            fail();
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("nope"));
        }
    }

    private static List<RetrievalBenchmarkData.SearchResult> results() {
        return Arrays.asList(
                new RetrievalBenchmarkData.SearchResult("r1", "Weather today", "rain and wind", "https://a.example/1"),
                new RetrievalBenchmarkData.SearchResult("r2", "Copper price history", "copper price since 1900",
                        "https://b.example/2"),
                new RetrievalBenchmarkData.SearchResult("r3", "Copper mines", "where copper is mined",
                        "https://c.example/3"));
    }

    @Test
    public void bm25StrategyRanksThroughTheProductiveReranker() {
        RankingStrategies.Ranking r = RankingStrategies.byReranker(new Bm25CandidateReranker(), "copper price",
                results());
        assertEquals("r2", r.ids.get(0));
        assertFalse(r.ids.contains("r1"));
        assertFalse(r.selectedNothing);
    }

    @Test
    public void embeddingStrategyEmbedsTheQueryAsQueryAndResultsAsDocuments() {
        final List<String> log = new ArrayList<String>();
        EmbeddingPort port = new EmbeddingPort() {
            public List<EmbeddingVector> embed(Role role, List<String> texts) {
                List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
                for (String text : texts) {
                    log.add(role.name());
                    boolean copper = text.toLowerCase().contains("price");
                    out.add(new EmbeddingVector("m", "fp", copper ? new float[]{1f, 0f} : new float[]{0f, 1f}));
                }
                return out;
            }
        };
        RankingStrategies.Ranking r = RankingStrategies.byEmbedding(port, "copper price", results());
        assertEquals("r2", r.ids.get(0));
        assertEquals(Arrays.asList("QUERY", "DOCUMENT", "DOCUMENT", "DOCUMENT"), log);
        RankingStrategies.Ranking fused = RankingStrategies.byFusion(
                RankingStrategies.byReranker(new Bm25CandidateReranker(), "copper price", results()), r);
        assertEquals("r2", fused.ids.get(0));
    }

    @Test
    public void truncationCountsOnlyAContextRejection() {
        ProtocolProbes.Answer ok = new ProtocolProbes.Answer(200, "{}");
        assertEquals("yes", ModelProtocolLiveTest.truncationVerdict(ok,
                new ProtocolProbes.Answer(400, "{\"error\":\"input length exceeds the context length\"}")));
        assertEquals("no: it answered", ModelProtocolLiveTest.truncationVerdict(ok, ok));
        assertTrue(ModelProtocolLiveTest.truncationVerdict(new ProtocolProbes.Answer(404, "model not found"),
                new ProtocolProbes.Answer(404, "model not found")).startsWith("unavailable"));
        assertTrue(ModelProtocolLiveTest.truncationVerdict(ok,
                new ProtocolProbes.Answer(500, "runner crashed")).startsWith("unclear"));
    }

    @Test
    public void protocolContextFollowsThePresetsUnlessOverridden() {
        assertEquals(32768, ModelProtocolLiveTest.numCtx("gemma4:e2b"));
        assertEquals(16384, ModelProtocolLiveTest.numCtx("llama3.1:8b"));
        assertEquals(8192, ModelProtocolLiveTest.numCtx("other"));
        System.setProperty("askai.benchmark.numCtx.llama3.1:8b", "32768");
        try {
            assertEquals(32768, ModelProtocolLiveTest.numCtx("llama3.1:8b"));
        } finally {
            System.clearProperty("askai.benchmark.numCtx.llama3.1:8b");
        }
    }
}
