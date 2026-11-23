package com.aresstack.askai.research.knowledge.processing.index;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Brute-force cosine vector index: ranking, non-normalized vectors, dimension/world guards, upsert, supersede, rebuild. */
public class FileVectorPassageIndexTest {

    private static File tempDir() throws IOException {
        return Files.createTempDirectory("askai-vec-index").toFile();
    }

    private static PassageIndexDocument doc(String passageId, String captureId, String fp, float[] v) {
        return new PassageIndexDocument(passageId, captureId, "src-" + captureId, "text of " + passageId,
                "Root", "seg-v1", fp, v);
    }

    @Test
    public void aSemanticallyCloseQueryRanksTheExpectedPassageFirst() throws IOException {
        FileVectorPassageIndex index = new FileVectorPassageIndex(tempDir());
        index.upsert(Arrays.asList(
                doc("p-glasses", "cap-1", "fpA", new float[]{1f, 0f, 0f}),
                doc("p-battery", "cap-1", "fpA", new float[]{0f, 1f, 0f})));

        // A NON-normalized query pointing mostly along the first axis.
        List<PassageSearchHit> hits = index.search("fpA", new float[]{9f, 1f, 0f}, 10);
        assertEquals(2, hits.size());
        assertEquals("p-glasses", hits.get(0).getPassageId());
        assertTrue("the closer passage scores higher",
                hits.get(0).getScore() > hits.get(1).getScore());
        assertEquals("src-cap-1", hits.get(0).getSourceId());
    }

    @Test
    public void aWrongDimensionQueryIsRejected() throws IOException {
        FileVectorPassageIndex index = new FileVectorPassageIndex(tempDir());
        index.upsert(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        try {
            index.search("fpA", new float[]{1f, 0f}, 10);
            fail("a query of the wrong dimension must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void differentFingerprintsLandInDifferentNamespacesAndAreNeverCompared() throws IOException {
        FileVectorPassageIndex index = new FileVectorPassageIndex(tempDir());
        index.upsert(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        index.upsert(Arrays.asList(doc("p2", "cap-1", "fpB", new float[]{1f, 0f, 0f, 0f}))); // dim 4 world

        assertEquals("two distinct namespace files", 2, index.namespaceFileNames().size());
        List<PassageSearchHit> hitsA = index.search("fpA", new float[]{1f, 0f, 0f}, 10);
        assertEquals(1, hitsA.size());
        assertEquals("fpA search never sees the fpB passage", "p1", hitsA.get(0).getPassageId());
    }

    @Test
    public void reIndexingTheSamePassageNeverDuplicates() throws IOException {
        FileVectorPassageIndex index = new FileVectorPassageIndex(tempDir());
        index.upsert(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        index.upsert(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        assertEquals(1, index.search("fpA", new float[]{1f, 0f, 0f}, 10).size());
    }

    @Test
    public void replacingACapturesPassagesSupersedesTheOldGeneration() throws IOException {
        FileVectorPassageIndex index = new FileVectorPassageIndex(tempDir());
        index.upsert(Arrays.asList(doc("cap-1#p0@seg-v1-fpA", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        // A new generation of the SAME capture with a different passage set.
        index.replaceForCapture("fpA", "cap-1",
                Arrays.asList(doc("cap-1#p0@seg-v2-fpA", "cap-1", "fpA", new float[]{0f, 1f, 0f})));

        List<PassageSearchHit> hits = index.search("fpA", new float[]{0f, 1f, 0f}, 10);
        assertEquals(1, hits.size());
        assertEquals("only the new generation's passage is active", "cap-1#p0@seg-v2-fpA",
                hits.get(0).getPassageId());
    }

    /**
     * Point R: every session start retires the superseded captures from the index. A capture the namespace does not
     * hold, retired with no passages, leaves the namespace file as it is (not even rewritten) and creates none.
     */
    @Test
    public void retiringACaptureTheNamespaceDoesNotHoldWritesNothing() throws IOException {
        File dir = tempDir();
        FileVectorPassageIndex index = new FileVectorPassageIndex(dir);
        index.upsert(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        File namespace = new File(new File(new File(new File(dir, "indexes"), "knowledge"), "vectors"),
                index.namespaceFileNames().iterator().next());
        assertTrue(namespace.setLastModified(1_000_000L));

        index.replaceForCapture("fpA", "cap-retired", java.util.Collections.<PassageIndexDocument>emptyList());
        index.replaceForCapture("fpB", "cap-retired", null);

        assertEquals("not rewritten", 1_000_000L, namespace.lastModified());
        assertEquals("no namespace file for fpB", 1, index.namespaceFileNames().size());
        assertEquals(1, index.search("fpA", new float[]{1f, 0f, 0f}, 10).size());
    }

    @Test
    public void rebuildReproducesTheIndexFromScratch() throws IOException {
        File dir = tempDir();
        FileVectorPassageIndex index = new FileVectorPassageIndex(dir);
        index.upsert(Arrays.asList(doc("p-old", "cap-9", "fpA", new float[]{1f, 0f, 0f})));

        index.rebuild(Arrays.asList(
                doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f}),
                doc("p2", "cap-2", "fpA", new float[]{0f, 1f, 0f})));

        List<PassageSearchHit> all = index.search("fpA", new float[]{1f, 1f, 0f}, 10);
        assertEquals("stale p-old is gone, exactly the rebuilt set remains", 2, all.size());
    }

    @Test
    public void anEmptiedIndexCanBeRebuilt() throws IOException {
        FileVectorPassageIndex index = new FileVectorPassageIndex(tempDir());
        index.upsert(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        index.removeAll();
        assertTrue(index.search("fpA", new float[]{1f, 0f, 0f}, 10).isEmpty());
        index.rebuild(Arrays.asList(doc("p1", "cap-1", "fpA", new float[]{1f, 0f, 0f})));
        assertEquals(1, index.search("fpA", new float[]{1f, 0f, 0f}, 10).size());
    }
}
