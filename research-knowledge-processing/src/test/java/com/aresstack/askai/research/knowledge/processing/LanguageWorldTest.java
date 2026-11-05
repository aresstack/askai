package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;
import com.aresstack.askai.research.knowledge.processing.index.PassageIndexDocument;
import com.aresstack.askai.research.knowledge.processing.index.SemanticKnowledgeIndex;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The LANGUAGE is part of the processing world: same capture + same segmentation version + same embedding
 * fingerprint under "en" vs "de" are DIFFERENT derivations (different keys, different generation dirs), a
 * legacy queue entry without a language reads as the documented default "en", a queued "de" job survives a
 * restart as "de", and the worker resolves the segmenter per JOB from the job's immutable snapshot.
 */
public class LanguageWorldTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void enAndDeAreDifferentDerivationIdentities() throws Exception {
        File dir = folder.newFolder("p");
        File en = FileResearchProjectRepository.generationDir(dir, "cap-1", "seg-v1", "fpA", "en");
        File de = FileResearchProjectRepository.generationDir(dir, "cap-1", "seg-v1", "fpA", "de");
        assertNotEquals("en and de must never collide on one generation", en, de);
        // Empty/legacy language normalizes to the documented default "en" (same generation as explicit en).
        assertEquals(en, FileResearchProjectRepository.generationDir(dir, "cap-1", "seg-v1", "fpA", ""));
        assertEquals(en, FileResearchProjectRepository.generationDir(dir, "cap-1", "seg-v1", "fpA", null));

        assertNotEquals(
                new SourceProcessingRequest("cap-1", "s", "seg-v1", "fpA", "en").idempotencyKey(),
                new SourceProcessingRequest("cap-1", "s", "seg-v1", "fpA", "de").idempotencyKey());
    }

    @Test
    public void aLegacyQueueEntryWithoutLanguageReadsAsEn() throws Exception {
        File dir = folder.newFolder("q");
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(dir);
        queue.enqueue(new SourceProcessingRequest("cap-legacy", "src", "seg-v1", "fpA", "de"));
        // Simulate a LEGACY job file: strip the languageCode line the new format writes.
        File[] files = dir.listFiles();
        for (File f : files) {
            if (f.getName().endsWith(".properties")) {
                String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                content = content.replaceAll("(?m)^languageCode=.*\r?\n", "");
                java.nio.file.Files.write(f.toPath(), content.getBytes("UTF-8"));
            }
        }
        FileSourceProcessingQueue restarted = new FileSourceProcessingQueue(dir);
        SourceProcessingJob job = restarted.takeNext();
        assertEquals("legacy entries default to the documented \"en\"", "en",
                job.getRequest().getLanguageCode());
    }

    @Test
    public void aQueuedDeJobSurvivesARestartAsDe() throws Exception {
        File dir = folder.newFolder("q2");
        new FileSourceProcessingQueue(dir)
                .enqueue(new SourceProcessingRequest("cap-de", "src", "seg-v1", "fpA", "de"));
        FileSourceProcessingQueue restarted = new FileSourceProcessingQueue(dir);
        assertEquals("de", restarted.takeNext().getRequest().getLanguageCode());
    }

    @Test
    public void theWorkerResolvesTheSegmenterPerJobFromTheJobsSnapshot() throws Exception {
        File dir = folder.newFolder("w");
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(dir);
        queue.enqueue(new SourceProcessingRequest("cap-en", "s1", "seg-v1", "fpA", "en"));
        queue.enqueue(new SourceProcessingRequest("cap-de", "s2", "seg-v1", "fpA", "de"));

        final List<String> resolvedLanguages = new ArrayList<String>();
        SourceProcessingWorker.SegmentationFactory factory =
                new SourceProcessingWorker.SegmentationFactory() {
                    public PassageSegmentation forLanguage(String languageCode) {
                        resolvedLanguages.add(languageCode);
                        return new PassageSegmentation(new DotSegmenter(), new FixedEmbedder("fpA"),
                                "seg-v1", languageCode, 3, 0.35, 2, 8);
                    }
                };
        SourceProcessingWorker worker = new SourceProcessingWorker(queue, new FixedCaptures(), factory,
                new NoopStore(), new NoopIndex(), new EmptyGenerations(), "p1", 3, "fpA",
                SourceProcessingWorker.Listener.NONE);

        assertEquals(2, worker.drain());
        assertEquals("each job resolves its OWN immutable language snapshot",
                Arrays.asList("en", "de"), resolvedLanguages);
    }

    // ------------------------------------------------------------------ fakes

    private static final class DotSegmenter implements SentenceSegmentationPort {
        public List<String> segment(String text) {
            List<String> out = new ArrayList<String>();
            for (String part : text.split("\\. ")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
            return out;
        }
    }

    private static final class FixedEmbedder implements EmbeddingPort {
        private final String fingerprint;

        FixedEmbedder(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (int i = 0; i < texts.size(); i++) {
                out.add(new EmbeddingVector("fake", fingerprint, new float[]{1f, 0f}));
            }
            return out;
        }
    }

    private static final class FixedCaptures implements SourceCaptureReader {
        public SourceCapture read(String captureId) {
            return new SourceCapture(captureId, "src", "https://x/" + captureId, 1L, "sum", "T", "",
                    java.util.Collections.singletonList(new SourceCapture.StructuralBlock(
                            captureId + "#b0", SourceCapture.BlockKind.PARAGRAPH, "T",
                            "One sentence. Two sentence. Three sentence.")));
        }
    }

    private static final class NoopStore implements PassageStore {
        public void store(SourceCapture capture, List<com.aresstack.askai.research.domain.Sentence> sentences,
                          List<Passage> passages,
                          Map<String, EmbeddingPort.EmbeddingVector> passageVectors) {
        }
    }

    private static final class NoopIndex implements SemanticKnowledgeIndex {
        public void indexPassages(String projectId, java.util.Collection<PassageIndexDocument> passages) {
        }

        public void replacePassagesForCapture(String projectId, String embeddingFingerprint, String captureId,
                                              java.util.Collection<PassageIndexDocument> documents) {
        }

        public List<com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit> keywordSearch(
                String projectId,
                com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery query) {
            return new ArrayList<com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit>();
        }

        public List<com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit> semanticSearch(
                String projectId,
                com.aresstack.askai.research.knowledge.processing.index.PassageSemanticQuery query) {
            return new ArrayList<com.aresstack.askai.research.knowledge.processing.index.PassageSearchHit>();
        }

        public void rebuild(String projectId, java.util.Collection<PassageIndexDocument> passages) {
        }

        public void removeProject(String projectId) {
        }
    }

    private static final class EmptyGenerations implements IndexableGenerationSource {
        public List<PassageIndexDocument> loadPersisted(String captureId, String segVersion,
                                                        String fingerprint, String languageCode) {
            return new ArrayList<PassageIndexDocument>();
        }
    }

    // Silence "unused" for assertFalse/assertTrue imports if not used above.
    static {
        assertTrue(true);
        assertFalse(false);
    }
}
