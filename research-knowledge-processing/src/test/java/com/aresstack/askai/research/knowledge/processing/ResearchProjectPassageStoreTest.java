package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The productive PassageStore records a capture's sentences + passages through the file-backed
 * ResearchProjectRepository so they survive a reload; re-storing the same derivation is idempotent (one
 * commit); a second capture is stored without disturbing the first.
 */
public class ResearchProjectPassageStoreTest {

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-passage-store").toFile();
    }

    private static SourceCapture capture(String captureId, String url) {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one. Beta two.");
        // one page = one source: a capture of another address belongs to another source
        return new SourceCapture(captureId, "src-" + url, url, 7L, "h-" + captureId, "T", "A",
                Collections.singletonList(b));
    }

    private static List<Sentence> sentences(String captureId) {
        return Arrays.asList(
                new Sentence(captureId + "#s0", captureId, "b1", 0, "Alpha one."),
                new Sentence(captureId + "#s1", captureId, "b1", 1, "Beta two."));
    }

    private static List<Passage> passages(String captureId) {
        return Arrays.asList(new Passage(captureId + "#p0@seg-v1-fpA", captureId,
                Arrays.asList(captureId + "#s0", captureId + "#s1"), "Root", "Alpha one. Beta two.",
                "fpA", "seg-v1"));
    }

    private static Map<String, EmbeddingPort.EmbeddingVector> vectors(String captureId) {
        Map<String, EmbeddingPort.EmbeddingVector> v =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        v.put(captureId + "#p0@seg-v1-fpA",
                new EmbeddingPort.EmbeddingVector("m", "fpA", new float[]{0.1f, 0.2f, 0.3f}));
        return v;
    }

    private static ResearchProjectPassageStore store(File dir) {
        return new ResearchProjectPassageStore(new FileResearchProjectRepository(dir), "p1",
                new FilePassageVectorStore(dir));
    }

    @Test
    public void storesACaptureGenerationWithVectorsThatSurvivesAReload() throws IOException {
        File dir = tempDir();
        store(dir).store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"),
                vectors("cap-1"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, reloaded.captures().size());
        assertEquals(2, reloaded.sentences().size());
        assertEquals(1, reloaded.passages().size());
        Passage p = reloaded.passages().get("cap-1#p0@seg-v1-fpA");
        assertNotNull(p);
        assertEquals(Arrays.asList("cap-1#s0", "cap-1#s1"), p.getSentenceIds());

        // The float vectors are canonically persistent (rebuildable index without re-embedding).
        Map<String, float[]> persisted =
                new FilePassageVectorStore(dir).load("cap-1", "seg-v1", "fpA", "en");
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f}, persisted.get("cap-1#p0@seg-v1-fpA"), 1e-6f);
    }

    @Test
    public void reStoringTheSameDerivationIsIdempotent() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, reloaded.captures().size());
        assertEquals(1, reloaded.passages().size());
    }

    @Test
    public void storingASecondCaptureLeavesTheFirstIntact() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));
        store.store(capture("cap-2", "https://x/2"), sentences("cap-2"), passages("cap-2"), vectors("cap-2"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(2, reloaded.captures().size());
        assertEquals(4, reloaded.sentences().size());
        assertNotNull("the first capture's passage is still active",
                reloaded.passages().get("cap-1#p0@seg-v1-fpA"));
        assertNotNull("the second capture's passage is active",
                reloaded.passages().get("cap-2#p0@seg-v1-fpA"));
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f},
                new FilePassageVectorStore(dir).load("cap-1", "seg-v1", "fpA", "en").get("cap-1#p0@seg-v1-fpA"),
                1e-6f);
    }

    // ------------------------------------------------------------------ point R: a capture derived anew

    /** The same capture's passages in another derivation (embedding world {@code fingerprint}, segmentation). */
    private static List<Passage> passages(String captureId, String segVersion, String fingerprint) {
        return Arrays.asList(new Passage(captureId + "#p0@" + segVersion + "-" + fingerprint, captureId,
                Arrays.asList(captureId + "#s0", captureId + "#s1"), "Root", "Alpha one. Beta two.",
                fingerprint, segVersion));
    }

    private static Map<String, EmbeddingPort.EmbeddingVector> vectors(String captureId, String segVersion,
                                                                       String fingerprint) {
        Map<String, EmbeddingPort.EmbeddingVector> v =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        v.put(captureId + "#p0@" + segVersion + "-" + fingerprint,
                new EmbeddingPort.EmbeddingVector("m", fingerprint, new float[]{0.4f, 0.5f}));
        return v;
    }

    /** Every file under {@code knowledge/} with its bytes, so a test can see what a store wrote. */
    private static Map<String, String> files(File dir) throws IOException {
        final Map<String, String> files = new java.util.TreeMap<String, String>();
        final java.nio.file.Path root = new File(dir, "knowledge").toPath();
        if (!root.toFile().exists()) {
            return files;
        }
        java.nio.file.Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                    java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                files.put(root.relativize(file).toString(), java.util.Base64.getEncoder()
                        .encodeToString(java.nio.file.Files.readAllBytes(file)) + "@"
                        + attributes.lastModifiedTime().toMillis());
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** Sets every file under {@code knowledge/} one hour back, so a rewrite shows in its time stamp. */
    private static void ageFiles(File dir) throws IOException {
        final java.nio.file.attribute.FileTime old =
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L);
        java.nio.file.Files.walkFileTree(new File(dir, "knowledge").toPath(),
                new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file,
                            java.nio.file.attribute.BasicFileAttributes attributes) throws IOException {
                        java.nio.file.Files.setLastModifiedTime(file, old);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
    }

    /** The generation folders of a capture that hold nothing but vectors (what the defect left behind). */
    private static int vectorsOnlyGenerations(File dir, String captureId) {
        File[] generations = FileResearchProjectRepository.derivedDir(dir, captureId).listFiles();
        int count = 0;
        for (File generation : generations == null ? new File[0] : generations) {
            String[] names = generation.list();
            if (names != null && names.length > 0 && !Arrays.asList(names).contains("manifest.properties")) {
                count++;
            }
        }
        return count;
    }

    /** Before point R this store threw "more than one derivation identity in a single save". */
    @Test
    public void aCaptureDerivedInANewEmbeddingWorldReplacesItsGenerationAndKeepsTheOldOneAsHistory()
            throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1", "seg-v1", "fpA"),
                vectors("cap-1", "seg-v1", "fpA"));

        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1", "seg-v1", "fpB"),
                vectors("cap-1", "seg-v1", "fpB"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, reloaded.passages().size());
        assertNotNull(reloaded.passages().get("cap-1#p0@seg-v1-fpB"));
        assertNull("the old world is no longer active", reloaded.passages().get("cap-1#p0@seg-v1-fpA"));
        assertEquals(2, reloaded.sentences().size());
        assertArrayEquals(new float[]{0.4f, 0.5f}, new FilePassageVectorStore(dir)
                .load("cap-1", "seg-v1", "fpB", "en").get("cap-1#p0@seg-v1-fpB"), 0f);
        File[] generations = FileResearchProjectRepository.derivedDir(dir, "cap-1").listFiles();
        assertEquals("the old generation stays on disk as history", 2, generations.length);
        assertEquals("no vectors without a generation", 0, vectorsOnlyGenerations(dir, "cap-1"));
    }

    @Test
    public void aNewSegmentationReplacesTheSentenceTextsToo() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1", "seg-v1", "fpA"),
                vectors("cap-1", "seg-v1", "fpA"));
        List<Sentence> resegmented = Arrays.asList(
                new Sentence("cap-1#s0", "cap-1", "b1", 0, "Alpha one (seg-v2)."),
                new Sentence("cap-1#s1", "cap-1", "b1", 1, "Beta two (seg-v2)."));

        store.store(capture("cap-1", "https://x/1"), resegmented, passages("cap-1", "seg-v2", "fpA"),
                vectors("cap-1", "seg-v2", "fpA"));

        ResearchProject reloaded = new FileResearchProjectRepository(dir).load("p1");
        assertEquals("Alpha one (seg-v2).", reloaded.sentences().get("cap-1#s0").getText());
        assertNotNull(reloaded.passages().get("cap-1#p0@seg-v2-fpA"));
        assertEquals(1, reloaded.passages().size());
    }

    @Test
    public void reStoringTheSameDerivationWritesNothing() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));
        ageFiles(dir);
        Map<String, String> before = files(dir);

        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));

        assertEquals("no file written, added or removed", before, files(dir));
    }

    @Test
    public void storingOneCaptureLeavesTheFilesOfTheOthersUntouched() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));
        store.store(capture("cap-2", "https://x/2"), sentences("cap-2"), passages("cap-2"), vectors("cap-2"));
        ageFiles(dir);
        Map<String, String> before = files(dir);

        store.store(capture("cap-2", "https://x/2"), sentences("cap-2"), passages("cap-2", "seg-v1", "fpB"),
                vectors("cap-2", "seg-v1", "fpB"));

        Map<String, String> after = files(dir);
        for (Map.Entry<String, String> file : before.entrySet()) {
            boolean ofCap2 = file.getKey().contains(FileResearchProjectRepository.derivedDir(dir, "cap-2")
                    .getName()) && !file.getKey().startsWith("captures");
            if (!ofCap2) {
                assertEquals(file.getKey(), file.getValue(), after.get(file.getKey()));
            }
        }
        assertNotNull(new FileResearchProjectRepository(dir).load("p1").passages().get("cap-1#p0@seg-v1-fpA"));
    }

    @Test
    public void aFailedSaveLeavesNoVectorsBehind() throws IOException {
        File dir = tempDir();
        final FileResearchProjectRepository files = new FileResearchProjectRepository(dir);
        ResearchProjectPassageStore store = new ResearchProjectPassageStore(
                new com.aresstack.askai.research.domain.ResearchProjectRepository() {
                    public ResearchProject load(String projectId) {
                        return files.load(projectId);
                    }

                    public void save(ResearchProject project) {
                        throw new IllegalStateException("disk full");
                    }
                }, "p1", new FilePassageVectorStore(dir));
        try {
            store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));
            fail("the failed save must reach the worker, which retries the job");
        } catch (IllegalStateException expected) {
            assertEquals("disk full", expected.getMessage());
        }
        assertEquals(0, vectorsOnlyGenerations(dir, "cap-1"));
        assertTrue(new FilePassageVectorStore(dir).load("cap-1", "seg-v1", "fpA", "en").isEmpty());
    }

    @Test
    public void vectorsLeftBehindByAnEarlierFailedAttemptAreRemoved() throws IOException {
        File dir = tempDir();
        FilePassageVectorStore vectorStore = new FilePassageVectorStore(dir);
        // what the defect left in an old workspace: vectors of another world without their generation
        vectorStore.store("cap-1", "seg-v1", "fpOld", "en", vectors("cap-1", "seg-v1", "fpOld"));
        vectorStore.store("cap-1", "seg-v1", "fpA", "en", vectors("cap-1", "seg-v1", "fpA"));
        assertEquals(2, vectorsOnlyGenerations(dir, "cap-1"));

        store(dir).store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));

        assertEquals(0, vectorsOnlyGenerations(dir, "cap-1"));
        assertEquals("only the stored generation remains", 1,
                FileResearchProjectRepository.derivedDir(dir, "cap-1").listFiles().length);
        assertNotNull(new FileResearchProjectRepository(dir).load("p1").passages().get("cap-1#p0@seg-v1-fpA"));
    }

    @Test
    public void aCaptureIdWithOtherContentIsRefusedBeforeAnythingIsWritten() throws IOException {
        File dir = tempDir();
        ResearchProjectPassageStore store = store(dir);
        store.store(capture("cap-1", "https://x/1"), sentences("cap-1"), passages("cap-1"), vectors("cap-1"));
        SourceCapture changed = new SourceCapture("cap-1", "src-https://x/1", "https://x/1", 7L, "other-hash",
                "T", "A", capture("cap-1", "https://x/1").getBlocks());
        try {
            store.store(changed, sentences("cap-1"), passages("cap-1", "seg-v1", "fpB"),
                    vectors("cap-1", "seg-v1", "fpB"));
            fail("captures are immutable");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("captures are immutable"));
        }
        assertFalse("no vectors of the refused derivation", FileResearchProjectRepository.generationDir(dir,
                "cap-1", "seg-v1", "fpB", "en").exists());
        assertNotNull(new FileResearchProjectRepository(dir).load("p1").passages().get("cap-1#p0@seg-v1-fpA"));
    }
}
