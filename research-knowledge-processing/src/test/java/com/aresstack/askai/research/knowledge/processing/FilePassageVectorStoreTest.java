package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.knowledge.EmbeddingPort;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The binary passage-vector store: exact float roundtrip in the generation dir, deterministic idempotent bytes,
 * per-fingerprint namespacing (a different embedding world lands in a different generation dir), empty = no file.
 */
public class FilePassageVectorStoreTest {

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-vec").toFile();
    }

    private static Map<String, EmbeddingPort.EmbeddingVector> vectors(String fingerprint, float[] a, float[] b) {
        Map<String, EmbeddingPort.EmbeddingVector> v =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        v.put("cap#p1", new EmbeddingPort.EmbeddingVector("m", fingerprint, a));
        v.put("cap#p0", new EmbeddingPort.EmbeddingVector("m", fingerprint, b));
        return v;
    }

    @Test
    public void roundTripsVectorsExactly() throws IOException {
        File dir = tempDir();
        FilePassageVectorStore store = new FilePassageVectorStore(dir);
        store.store("cap", "seg-v1", "fpA", "en",
                vectors("fpA", new float[]{1f, -2.5f, 3f}, new float[]{0f, 0.25f, -1f}));

        Map<String, float[]> loaded = store.load("cap", "seg-v1", "fpA", "en");
        assertEquals(2, loaded.size());
        assertArrayEquals(new float[]{1f, -2.5f, 3f}, loaded.get("cap#p1"), 0f);
        assertArrayEquals(new float[]{0f, 0.25f, -1f}, loaded.get("cap#p0"), 0f);
        // deterministic order: ascending passage id
        assertEquals("[cap#p0, cap#p1]", store.passageIds("cap", "seg-v1", "fpA", "en").toString());
    }

    @Test
    public void reWritingIdenticalContentLeavesNoTempAndDoesNotChurn() throws IOException {
        File dir = tempDir();
        FilePassageVectorStore store = new FilePassageVectorStore(dir);
        Map<String, EmbeddingPort.EmbeddingVector> v =
                vectors("fpA", new float[]{1f, 2f}, new float[]{3f, 4f});
        store.store("cap", "seg-v1", "fpA", "en", v);
        File genDir = FileResearchProjectRepository.generationDir(dir, "cap", "seg-v1", "fpA", "en");
        byte[] first = Files.readAllBytes(new File(genDir, "vectors.bin").toPath());
        store.store("cap", "seg-v1", "fpA", "en", v);
        byte[] second = Files.readAllBytes(new File(genDir, "vectors.bin").toPath());
        assertArrayEquals("identical bytes on re-store", first, second);
        assertTrue("no leftover tmp", new File(genDir, "vectors.bin.tmp").length() == 0
                || !new File(genDir, "vectors.bin.tmp").exists());
    }

    @Test
    public void differentFingerprintsLandInDifferentGenerationDirsAndNeverMix() throws IOException {
        File dir = tempDir();
        FilePassageVectorStore store = new FilePassageVectorStore(dir);
        store.store("cap", "seg-v1", "fpA", "en", vectors("fpA", new float[]{1f}, new float[]{2f}));
        store.store("cap", "seg-v1", "fpB", "en", vectors("fpB", new float[]{9f, 9f}, new float[]{8f, 8f}));

        assertEquals(1, store.load("cap", "seg-v1", "fpA", "en").get("cap#p0").length); // dim 1 world
        assertEquals(2, store.load("cap", "seg-v1", "fpB", "en").get("cap#p0").length); // dim 2 world
        File a = FileResearchProjectRepository.generationDir(dir, "cap", "seg-v1", "fpA", "en");
        File b = FileResearchProjectRepository.generationDir(dir, "cap", "seg-v1", "fpB", "en");
        assertTrue("distinct namespaces per fingerprint", !a.getAbsolutePath().equals(b.getAbsolutePath()));
    }

    @Test
    public void anEmptyGenerationWritesNothing() throws IOException {
        File dir = tempDir();
        FilePassageVectorStore store = new FilePassageVectorStore(dir);
        store.store("cap", "seg-v1", "fpA", "en",
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>());
        assertTrue(store.load("cap", "seg-v1", "fpA", "en").isEmpty());
    }

    /** Point R: only a generation folder that holds nothing but vectors is a leftover; written generations stay. */
    @Test
    public void removeOrphansDeletesOnlyFoldersThatHoldNothingButVectors() throws IOException {
        File dir = tempDir();
        FilePassageVectorStore store = new FilePassageVectorStore(dir);
        store.store("cap", "seg-v1", "fpA", "en", vectors("fpA", new float[]{1f}, new float[]{2f}));
        store.store("cap", "seg-v1", "fpB", "en", vectors("fpB", new float[]{1f}, new float[]{2f}));
        store.store("other", "seg-v1", "fpB", "en", vectors("fpB", new float[]{1f}, new float[]{2f}));
        File written = FileResearchProjectRepository.generationDir(dir, "cap", "seg-v1", "fpA", "en");
        Files.write(new File(written, "manifest.properties").toPath(), "captureId=cap\n".getBytes("UTF-8"));
        File leftover = FileResearchProjectRepository.generationDir(dir, "cap", "seg-v1", "fpB", "en");
        Files.write(new File(leftover, "vectors.bin.tmp").toPath(), new byte[]{1});

        assertEquals(1, store.removeOrphans("cap"));
        assertTrue("a written generation keeps its vectors", new File(written, "vectors.bin").isFile());
        assertTrue("the leftover folder is gone", !leftover.exists());
        assertEquals("another capture is not touched", 2, store.load("other", "seg-v1", "fpB", "en").size());
        assertEquals("nothing left to remove", 0, store.removeOrphans("cap"));
        assertEquals("an unknown capture", 0, store.removeOrphans("nobody"));
    }
}
