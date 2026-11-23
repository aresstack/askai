package com.aresstack.askai.research.knowledge.lucene;

import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue;
import com.aresstack.askai.research.knowledge.processing.QueueBackedKnowledgeProcessingScheduler;
import com.aresstack.askai.research.knowledge.processing.RepositoryIndexableGenerationSource;
import com.aresstack.askai.research.knowledge.processing.ResearchProjectPassageStore;
import com.aresstack.askai.research.knowledge.processing.SourceCaptureReader;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingJob;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingWorker;
import com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit;
import com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The whole C4 chain end to end (deterministic fake embeddings — the live-model run is the GUI gate):
 * enqueue → SourceProcessingWorker → SourceCaptureReader → OpenNLP(regex fallback) → embeddings →
 * PassageSegmentation → PassageStore (+ canonical vectors) → CompositeSemanticKnowledgeIndex → COMPLETED,
 * then a keyword AND a semantic search return the expected passages.
 */
public class KnowledgePipelineVerticalSliceTest {

    private static final String PROJECT_ID = "p1";
    private static final String FINGERPRINT = "fpINT";
    private static final int DIMENSION = 4;

    /** Keyword-driven deterministic embedder in one fixed world (fpINT/dim 4). */
    private static final class KeywordEmbedder implements EmbeddingPort {
        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (String t : texts) {
                String lower = t == null ? "" : t.toLowerCase();
                float[] v;
                if (lower.contains("glass")) {
                    v = new float[]{1f, 0f, 0f, 0f};
                } else if (lower.contains("batter")) {
                    v = new float[]{0f, 1f, 0f, 0f};
                } else {
                    v = new float[]{0f, 0f, 1f, 0f};
                }
                out.add(new EmbeddingVector("fake-model", FINGERPRINT, v));
            }
            return out;
        }
    }

    private static SourceCapture capture() {
        SourceCapture.StructuralBlock glasses = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Smart glasses",
                "Smart glasses project images onto the lens. They overlay text in the field of view.");
        SourceCapture.StructuralBlock battery = new SourceCapture.StructuralBlock("b2",
                SourceCapture.BlockKind.PARAGRAPH, "Battery",
                "Battery life is very limited today. It drains within a few hours.");
        return new SourceCapture("cap-1", "source-7", "https://x/1", 1L, "hash-1", "Wearables", "",
                Arrays.asList(glasses, battery));
    }

    @Test
    public void acceptedSourceFlowsThroughToCompletedAndIsSearchable() throws IOException {
        File dir = java.nio.file.Files.createTempDirectory("askai-slice").toFile();

        // --- canonical persistence + index (all productive, real files) ---
        FileResearchProjectRepository repository = new FileResearchProjectRepository(dir);
        FilePassageVectorStore vectorStore = new FilePassageVectorStore(dir);
        ResearchProjectPassageStore passageStore =
                new ResearchProjectPassageStore(repository, PROJECT_ID, vectorStore);
        CompositeSemanticKnowledgeIndex index = new CompositeSemanticKnowledgeIndex(dir, PROJECT_ID);
        RepositoryIndexableGenerationSource generations =
                new RepositoryIndexableGenerationSource(repository, vectorStore, PROJECT_ID);

        // --- pipeline (regex sentence fallback + fake embedder in the fpINT world) ---
        PassageSegmentation segmentation =
                new PassageSegmentation(new RegexSentenceSegmenter(), new KeywordEmbedder(), "seg-v1");
        SourceCaptureReader reader = new SourceCaptureReader() {
            public SourceCapture read(String captureId) {
                return "cap-1".equals(captureId) ? capture() : null;
            }
        };

        // --- persistent FIFO + worker, exactly as the productive composition wires them ---
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(new File(dir, "processing"));
        final int[] completedPassageCount = {-1};
        SourceProcessingWorker worker = new SourceProcessingWorker(queue, reader, segmentation, passageStore,
                index, generations, PROJECT_ID, 3, FINGERPRINT, new SourceProcessingWorker.Listener() {
            public void onStarted(SourceProcessingJob job) {
            }

            public void onCompleted(SourceProcessingJob job, int sentenceCount, int passageCount) {
                completedPassageCount[0] = passageCount;
            }

            public void onFailed(SourceProcessingJob job,
                                 com.aresstack.askai.research.knowledge.processing
                                         .SourceProcessingFailure failure) {
            }
        });

        // Acceptance → scheduler stamps the session's world fingerprint → FIFO.
        new QueueBackedKnowledgeProcessingScheduler(queue, "seg-v1", FINGERPRINT).enqueue("cap-1", "source-7");

        // Drain (one serial worker, as the runner would).
        assertEquals(1, worker.drain());

        // COMPLETED only after persist + index.
        assertTrue(queue.isAlreadyCompleted(
                new com.aresstack.askai.research.knowledge.processing.SourceProcessingRequest(
                        "cap-1", "source-7", "seg-v1", FINGERPRINT).idempotencyKey()));

        // Canonical corpus persisted.
        ResearchProject project = new FileResearchProjectRepository(dir).load(PROJECT_ID);
        int sentenceCount = project.sentences().size();
        int passageCount = project.passages().size();
        assertTrue("sentences persisted", sentenceCount >= 4);
        assertEquals("two paragraph passages", 2, passageCount);
        assertEquals(passageCount, completedPassageCount[0]);

        // Keyword search (Lucene) finds the glasses passage.
        List<PassageSearchHit> keyword =
                index.keywordSearch(PROJECT_ID, new PassageTextQuery(FINGERPRINT, "glasses", 10));
        assertEquals(1, keyword.size());
        assertEquals("source-7", keyword.get(0).getSourceId());
        assertTrue(keyword.get(0).getText().toLowerCase().contains("glasses"));

        // Semantic search (brute-force cosine) with a glasses-world query vector ranks the glasses passage first.
        float[] queryVector = new KeywordEmbedder().embedQueries(Arrays.asList("smart glasses")).get(0).getValues();
        List<PassageSearchHit> semantic = index.semanticSearch(PROJECT_ID,
                new PassageSemanticQuery(FINGERPRINT, queryVector, 10));
        assertEquals(2, semantic.size());
        assertTrue("closest passage is the glasses one",
                semantic.get(0).getText().toLowerCase().contains("glasses"));
        assertTrue(semantic.get(0).getScore() > semantic.get(1).getScore());

        // A different embedding world is a different namespace (no cross-world hits).
        assertTrue(index.keywordSearch(PROJECT_ID, new PassageTextQuery("fpOTHER", "glasses", 10)).isEmpty());

        // Durchstich evidence (printed for the report).
        System.out.println("[C4 Durchstich] captureId=cap-1 sentences=" + sentenceCount + " passages="
                + passageCount + " fingerprint=" + FINGERPRINT + " dimension=" + DIMENSION
                + " namespace=indexes/knowledge/{text,vectors}/<h(" + FINGERPRINT + ")>"
                + " keywordHit=" + keyword.get(0).getPassageId()
                + " semanticTop=" + semantic.get(0).getPassageId() + "@" + semantic.get(0).getScore());
        assertFalse(keyword.get(0).getPassageId().isEmpty());
    }
}
