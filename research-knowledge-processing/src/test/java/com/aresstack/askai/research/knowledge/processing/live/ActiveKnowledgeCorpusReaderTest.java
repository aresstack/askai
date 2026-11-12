package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.ResearchProjectPassageStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * C5b corpus reader: the active corpus is the persisted passages + vectors of the session's embedding world,
 * filtered by the source filter — canonical data is never touched by filtering, other vector worlds are never
 * mixed, and ⭐ sources surface as a priority signal.
 */
public class ActiveKnowledgeCorpusReaderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static final String PROJECT = "p1";

    private static SourceCapture capture(String captureId, String sourceId) {
        return capture(captureId, sourceId, 7L);
    }

    private static SourceCapture capture(String captureId, String sourceId, long capturedAtMillis) {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one. Beta two.");
        return new SourceCapture(captureId, sourceId, "https://x/" + captureId, capturedAtMillis,
                "h-" + captureId, "T", "A", Collections.singletonList(b));
    }

    private static List<Sentence> sentences(String captureId) {
        return Arrays.asList(
                new Sentence(captureId + "#s0", captureId, "b1", 0, "Alpha one."),
                new Sentence(captureId + "#s1", captureId, "b1", 1, "Beta two."));
    }

    private static List<Passage> passages(String captureId, String fingerprint) {
        return Collections.singletonList(new Passage(captureId + "#p0@seg-v1-" + fingerprint, captureId,
                Arrays.asList(captureId + "#s0", captureId + "#s1"), "Root", "Alpha one. Beta two.",
                fingerprint, "seg-v1", "en"));
    }

    private static Map<String, EmbeddingPort.EmbeddingVector> vectors(String captureId, String fingerprint) {
        Map<String, EmbeddingPort.EmbeddingVector> v =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        v.put(captureId + "#p0@seg-v1-" + fingerprint,
                new EmbeddingPort.EmbeddingVector("m", fingerprint, new float[]{0.1f, 0.2f, 0.3f}));
        return v;
    }

    private File storeTwoCaptures() throws Exception {
        File dir = folder.newFolder("corpus");
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        FilePassageVectorStore vectorStore = new FilePassageVectorStore(dir);
        ResearchProjectPassageStore store = new ResearchProjectPassageStore(repo, PROJECT, vectorStore);
        store.store(capture("cap-1", "source-1"), sentences("cap-1"), passages("cap-1", "fpA"),
                vectors("cap-1", "fpA"));
        store.store(capture("cap-2", "source-2"), sentences("cap-2"), passages("cap-2", "fpA"),
                vectors("cap-2", "fpA"));
        return dir;
    }

    @Test
    public void readsAllActivePassagesWithTheirPersistedVectors() throws Exception {
        File dir = storeTwoCaptures();
        ActiveKnowledgeCorpusReader reader = new ActiveKnowledgeCorpusReader(
                new FileResearchProjectRepository(dir), new FilePassageVectorStore(dir), PROJECT, "fpA");
        ActiveKnowledgeCorpusReader.Corpus corpus = reader.read(null);
        assertEquals(2, corpus.getPassages().size());
        assertEquals("deterministic order (sorted by passage id)", "cap-1#p0@seg-v1-fpA",
                corpus.getPassages().get(0).getPassageId());
        assertEquals(2, corpus.getVectors().size());
        assertEquals(3, corpus.getVectors().get("cap-1#p0@seg-v1-fpA").getDimension());
    }

    @Test
    public void anExcludedSourceLeavesTheProjectionButNeverTheCanonicalData() throws Exception {
        File dir = storeTwoCaptures();
        ActiveKnowledgeCorpusReader reader = new ActiveKnowledgeCorpusReader(
                new FileResearchProjectRepository(dir), new FilePassageVectorStore(dir), PROJECT, "fpA");
        ActiveKnowledgeCorpusReader.Corpus corpus = reader.read(
                new ActiveKnowledgeCorpusReader.SourceFilter() {
                    public boolean includeSource(String sourceId) {
                        return !"source-2".equals(sourceId); // e.g. EXCLUDED in the sources tab
                    }

                    public boolean isUserRelevant(String sourceId) {
                        return "source-1".equals(sourceId); // ⭐
                    }
                });
        assertEquals(1, corpus.getPassages().size());
        assertEquals("cap-1#p0@seg-v1-fpA", corpus.getPassages().get(0).getPassageId());
        assertEquals(Collections.singletonList("cap-1#p0@seg-v1-fpA"),
                corpus.getUserRelevantPassageIds());
        // Canonical data untouched: the full project still holds BOTH captures' passages.
        assertEquals(2, new FileResearchProjectRepository(dir).load(PROJECT).passages().size());
    }

    @Test
    public void aDifferentEmbeddingWorldIsNeverMixedIn() throws Exception {
        File dir = storeTwoCaptures();
        ActiveKnowledgeCorpusReader other = new ActiveKnowledgeCorpusReader(
                new FileResearchProjectRepository(dir), new FilePassageVectorStore(dir), PROJECT, "fpZZ");
        assertTrue("no passage of another vector world enters the corpus",
                other.read(null).getPassages().isEmpty());
    }

    /** #28/#39.7: per-source passage inspection — only THIS source's passages, stable order. */
    @org.junit.Test
    public void passagesForSourceReturnsOnlyThatSourcesPassagesInStableOrder() throws Exception {
        File dir = storeTwoCaptures();
        ActiveKnowledgeCorpusReader reader = new ActiveKnowledgeCorpusReader(
                new FileResearchProjectRepository(dir), new FilePassageVectorStore(dir),
                PROJECT, "fpA");
        List<Passage> one = reader.passagesForSource("source-1");
        assertEquals(1, one.size());
        assertEquals("cap-1#p0@seg-v1-fpA", one.get(0).getPassageId());
        assertTrue("an unknown source reads as empty, never as an error",
                reader.passagesForSource("source-unknown").isEmpty());
        assertTrue(reader.passagesForSource("").isEmpty());
    }

    /** #39 review: "active" is REAL — stale vector worlds of the same source never show. */
    @org.junit.Test
    public void passagesForSourceNeverMixesAStaleEmbeddingWorldIntoTheActiveView() throws Exception {
        File dir = storeTwoCaptures();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        ResearchProjectPassageStore store =
                new ResearchProjectPassageStore(repo, PROJECT, new FilePassageVectorStore(dir));
        // An OLD capture of the same source, never reprocessed: its active generation still
        // lives in the fpOld vector world and sits beside cap-1's current fpA generation.
        // (captured BEFORE cap-1: one active capture per source keeps the newest, so it is not active at all)
        store.store(capture("cap-1old", "source-1", 6L), sentences("cap-1old"),
                Collections.singletonList(new Passage("cap-1old#p0@seg-v1-fpOld", "cap-1old",
                        Arrays.asList("cap-1old#s0"), "Root", "Old vector world text.",
                        "fpOld", "seg-v1", "en")),
                Collections.<String, EmbeddingPort.EmbeddingVector>emptyMap());
        ActiveKnowledgeCorpusReader reader = new ActiveKnowledgeCorpusReader(
                repo, new FilePassageVectorStore(dir), PROJECT, "fpA");
        List<Passage> active = reader.passagesForSource("source-1");
        assertEquals("only the session's embedding world — same rule as read()",
                1, active.size());
        assertEquals("cap-1#p0@seg-v1-fpA", active.get(0).getPassageId());
    }
}
