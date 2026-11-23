package com.aresstack.askai.research.knowledge.lucene;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.PassageSegmentation;
import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue;
import com.aresstack.askai.research.knowledge.processing.ProcessingReconciliation;
import com.aresstack.askai.research.knowledge.processing.QueueBackedKnowledgeProcessingScheduler;
import com.aresstack.askai.research.knowledge.processing.RepositoryIndexableGenerationSource;
import com.aresstack.askai.research.knowledge.processing.ResearchProjectPassageStore;
import com.aresstack.askai.research.knowledge.processing.SourceCaptureReader;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingFailure;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingJob;
import com.aresstack.askai.research.knowledge.processing.SourceProcessingWorker;
import com.aresstack.askai.research.knowledge.processing.index.PassageTextQuery;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Point R of the component plan (2026-11-21): a workspace of an older embedding world is opened by a build that
 * embeds in another world. The session start enqueues every capture without a passage of the active world
 * ({@link ProcessingReconciliation#reembedStale}, as {@code ProductiveResearchBackendFactory} does), and the worker
 * derives it anew. Before the fix every such capture failed at PASSAGE_PERSISTENCE ("more than one derivation
 * identity in a single save"), left its vectors behind and was tried again at every opening.
 * <p>Passed when the re-embedding runs through, no vectors without a generation remain, the old world stays on disk
 * as history, and a second opening enqueues nothing and writes no file. Deterministic fake embeddings; the real
 * workspaces of 12 and 17 Nov are the architect's test with the app.
 */
public class ReembeddingVerticalSliceTest {

    private static final String PROJECT_ID = "p1";
    private static final String SEG = "seg-v1";
    private static final String OLD_WORLD = "fpOLD";
    private static final String NEW_WORLD = "fpNEW";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Keyword vectors in one embedding world. */
    private static final class WorldEmbedder implements EmbeddingPort {
        private final String fingerprint;

        WorldEmbedder(String fingerprint) {
            this.fingerprint = fingerprint;
        }

        public List<EmbeddingVector> embed(Role role, List<String> texts) {
            List<EmbeddingVector> out = new ArrayList<EmbeddingVector>();
            for (String text : texts) {
                String lower = text == null ? "" : text.toLowerCase();
                float[] v = lower.contains("glass") ? new float[]{1f, 0f, 0f}
                        : lower.contains("batter") ? new float[]{0f, 1f, 0f} : new float[]{0f, 0f, 1f};
                out.add(new EmbeddingVector("fake-" + fingerprint, fingerprint, v));
            }
            return out;
        }
    }

    private static final Map<String, SourceCapture> CAPTURES = new LinkedHashMap<String, SourceCapture>();

    static {
        CAPTURES.put("cap-1", new SourceCapture("cap-1", "source-1", "https://x/1", 1L, "hash-1", "Glasses", "",
                Arrays.asList(new SourceCapture.StructuralBlock("b1", SourceCapture.BlockKind.PARAGRAPH,
                        "Smart glasses", "Smart glasses project images onto the lens. They overlay text."))));
        CAPTURES.put("cap-2", new SourceCapture("cap-2", "source-2", "https://x/2", 2L, "hash-2", "Battery", "",
                Arrays.asList(new SourceCapture.StructuralBlock("b1", SourceCapture.BlockKind.PARAGRAPH,
                        "Battery", "Battery life is very limited today. It drains within a few hours."))));
    }

    /** What one opening of the workspace did. */
    private static final class Opening {
        int enqueued;
        int handled;
        final List<String> failures = new ArrayList<String>();
        CompositeSemanticKnowledgeIndex index;
    }

    /**
     * One opening in {@code world}: the session start's re-embedding of stale captures, then the worker drains the
     * queue (also jobs an earlier opening left queued). {@code accept} are captures accepted in this session.
     */
    private static Opening open(File dir, String world, String... accept) {
        FileResearchProjectRepository repository = new FileResearchProjectRepository(dir);
        FilePassageVectorStore vectors = new FilePassageVectorStore(dir);
        final Opening opening = new Opening();
        opening.index = new CompositeSemanticKnowledgeIndex(dir, PROJECT_ID);
        FileSourceProcessingQueue queue = new FileSourceProcessingQueue(new File(dir, "processing"));
        final QueueBackedKnowledgeProcessingScheduler scheduler =
                new QueueBackedKnowledgeProcessingScheduler(queue, SEG, world);
        for (String captureId : accept) {
            scheduler.enqueue(captureId, CAPTURES.get(captureId).getSourceId());
        }
        ResearchProject project = repository.load(PROJECT_ID);
        Map<String, String> captureSourceIds = new LinkedHashMap<String, String>();
        for (SourceCapture capture : project.captures().values()) {
            captureSourceIds.put(capture.getCaptureId(), capture.getSourceId());
        }
        Set<String> currentWorld = new HashSet<String>();
        for (Passage passage : project.passages().values()) {
            if (world.equals(passage.getEmbeddingFingerprint())
                    && SEG.equals(passage.getSegmentationPipelineVersion())) {
                currentWorld.add(passage.getCaptureId());
            }
        }
        opening.enqueued = ProcessingReconciliation.reembedStale(captureSourceIds, currentWorld,
                queue.captureIdsWithKnownProcessing(world, SEG), new HashSet<String>(captureSourceIds.values()),
                new ProcessingReconciliation.MissingJobScheduler() {
                    public void enqueue(String captureId, String sourceId) {
                        scheduler.enqueue(captureId, sourceId);
                    }
                });
        SourceProcessingWorker worker = new SourceProcessingWorker(queue, new SourceCaptureReader() {
            public SourceCapture read(String captureId) {
                return CAPTURES.get(captureId);
            }
        }, new PassageSegmentation(new RegexSentenceSegmenter(), new WorldEmbedder(world), SEG),
                new ResearchProjectPassageStore(repository, PROJECT_ID, vectors), opening.index,
                new RepositoryIndexableGenerationSource(repository, vectors, PROJECT_ID), PROJECT_ID, 3, world,
                new SourceProcessingWorker.Listener() {
                    public void onStarted(SourceProcessingJob job) {
                    }

                    public void onCompleted(SourceProcessingJob job, int sentenceCount, int passageCount) {
                    }

                    public void onFailed(SourceProcessingJob job, SourceProcessingFailure failure) {
                        opening.failures.add(job.getRequest().getCaptureId() + " " + failure);
                    }
                });
        opening.handled = worker.drain();
        return opening;
    }

    /** Generation folders that hold vectors but no generation (what the defect left in every old workspace). */
    private static List<String> vectorsWithoutGeneration(File dir) {
        List<String> orphans = new ArrayList<String>();
        File[] captures = new File(dir, "knowledge/derived").listFiles();
        for (File capture : captures == null ? new File[0] : captures) {
            File[] generations = capture.listFiles();
            for (File generation : generations == null ? new File[0] : generations) {
                if (new File(generation, "vectors.bin").isFile()
                        && !new File(generation, "manifest.properties").isFile()) {
                    orphans.add(capture.getName() + "/" + generation.getName());
                }
            }
        }
        return orphans;
    }

    /** Every file of the workspace with its size and time stamp. */
    private static Map<String, String> files(File dir) throws IOException {
        final Map<String, String> files = new TreeMap<String, String>();
        final Path root = dir.toPath();
        Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                files.put(root.relativize(file).toString(), attributes.size() + " bytes @"
                        + attributes.lastModifiedTime().toMillis());
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static void ageFiles(File dir) throws IOException {
        final FileTime old = FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L);
        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.setLastModifiedTime(file, old);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @Test
    public void aWorkspaceOfAnOlderEmbeddingWorldIsReEmbeddedOnceAndASecondOpeningWritesNothing()
            throws IOException {
        File dir = temp.newFolder("workspace");
        Opening old = open(dir, OLD_WORLD, "cap-1", "cap-2");
        assertEquals(2, old.handled);
        assertTrue(old.failures.toString(), old.failures.isEmpty());

        Opening first = open(dir, NEW_WORLD);

        assertEquals("both captures are of the older world", 2, first.enqueued);
        assertEquals(2, first.handled);
        assertTrue("the re-embedding runs through: " + first.failures, first.failures.isEmpty());
        ResearchProject project = new FileResearchProjectRepository(dir).load(PROJECT_ID);
        assertFalse(project.passages().isEmpty());
        for (Passage passage : project.passages().values()) {
            assertEquals(passage.getPassageId(), NEW_WORLD, passage.getEmbeddingFingerprint());
        }
        assertEquals(1, first.index.keywordSearch(PROJECT_ID, new PassageTextQuery(NEW_WORLD, "glasses", 10))
                .size());
        assertTrue("no vectors without a generation", vectorsWithoutGeneration(dir).isEmpty());
        for (File capture : new File(dir, "knowledge/derived").listFiles()) {
            assertEquals("the older world stays on disk as history", 2, capture.listFiles().length);
        }

        ageFiles(dir);
        Map<String, String> before = files(dir);
        Opening second = open(dir, NEW_WORLD);

        assertEquals("nothing to re-embed", 0, second.enqueued);
        assertEquals("nothing to do", 0, second.handled);
        assertEquals("a second opening writes no file", before, files(dir));
    }

    /** The old defect left vectors and queued jobs behind; the next opening completes them and leaves nothing over. */
    @Test
    public void theLeftoversOfAFailedOpeningAreCompletedByTheNextOne() throws IOException {
        File dir = temp.newFolder("workspace");
        open(dir, OLD_WORLD, "cap-1", "cap-2");
        // the defect: the vectors of the new world were written, the save failed, the job stayed queued
        FilePassageVectorStore vectors = new FilePassageVectorStore(dir);
        Map<String, EmbeddingPort.EmbeddingVector> leftover =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        leftover.put("cap-1#p0@" + SEG + "-" + NEW_WORLD, new EmbeddingPort.EmbeddingVector("fake", NEW_WORLD,
                new float[]{1f, 0f, 0f}));
        vectors.store("cap-1", SEG, NEW_WORLD, "", leftover);
        vectors.store("cap-2", SEG, "fpBETWEEN", "", java.util.Collections.singletonMap("cap-2#p0@" + SEG
                + "-fpBETWEEN", new EmbeddingPort.EmbeddingVector("fake", "fpBETWEEN", new float[]{0f, 1f, 0f})));
        new QueueBackedKnowledgeProcessingScheduler(new FileSourceProcessingQueue(new File(dir, "processing")), SEG,
                NEW_WORLD).enqueue("cap-1", "source-1");
        assertEquals(2, vectorsWithoutGeneration(dir).size());

        Opening next = open(dir, NEW_WORLD);

        assertEquals("the queued job is not doubled, only cap-2 is enqueued", 1, next.enqueued);
        assertEquals(2, next.handled);
        assertTrue(next.failures.toString(), next.failures.isEmpty());
        assertTrue("no vectors without a generation: " + vectorsWithoutGeneration(dir),
                vectorsWithoutGeneration(dir).isEmpty());
        for (Passage passage : new FileResearchProjectRepository(dir).load(PROJECT_ID).passages().values()) {
            assertEquals(NEW_WORLD, passage.getEmbeddingFingerprint());
        }
    }
}
