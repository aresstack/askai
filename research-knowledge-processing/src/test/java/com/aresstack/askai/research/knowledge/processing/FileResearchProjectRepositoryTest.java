package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The file-backed ResearchProjectRepository: per capture-generation layout, atomic active-pointer commit,
 * reprocessing/supersession, schema version, restart roundtrip.
 */
public class FileResearchProjectRepositoryTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-rproj").toFile();
    }

    private static SourceCapture capture() {
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "Root", "Alpha one.\nAlpha two.");
        return new SourceCapture("cap-1", "src-1", "https://x/cap-1", 7L, "h1", "T", "A",
                Collections.singletonList(b));
    }

    /** A generation of capture cap-1 with a distinguishable sentence text + derivation identity. */
    private static ResearchProject generation(String marker, String segVersion, String fingerprint) {
        ResearchProject p = new ResearchProject("p1", IdSequence.counting());
        p.recordSourceCapture(capture());
        p.recordSentences(Arrays.asList(new Sentence("cap-1#s0", "cap-1", "b1", 0, marker + " sentence.")));
        p.recordPassages(Arrays.asList(new Passage("cap-1#p0@" + segVersion + "-" + fingerprint, "cap-1",
                Arrays.asList("cap-1#s0"), "Root", marker + " sentence.", fingerprint, segVersion)));
        return p;
    }

    @Test
    public void emptyProjectLoadsCleanly() throws IOException {
        ResearchProject p = new FileResearchProjectRepository(tempDir()).load("p1");
        assertEquals("p1", p.getProjectId());
        assertTrue(p.captures().isEmpty());
        assertTrue(p.sentences().isEmpty());
        assertTrue(p.passages().isEmpty());
    }

    @Test
    public void oneGenerationSurvivesARestartWithIdsAndRefs() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(generation("V1", "seg-v1", "fpA"));

        ResearchProject r = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(1, r.captures().size());
        assertEquals("Alpha one.\nAlpha two.", r.captures().get("cap-1").getBlocks().get(0).getText());
        assertEquals(1, r.sentences().size());
        assertEquals("V1 sentence.", r.sentences().get("cap-1#s0").getText());
        assertEquals(1, r.passages().size());
        Passage p = r.passages().get("cap-1#p0@seg-v1-fpA");
        assertNotNull(p);
        assertEquals(Arrays.asList("cap-1#s0"), p.getSentenceIds());
        assertEquals("seg-v1", p.getSegmentationPipelineVersion());
        assertEquals("fpA", p.getEmbeddingFingerprint());
    }

    @Test
    public void reprocessingActivatesOnlyTheNewGenerationOnReload() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(generation("V1", "seg-v1", "fpA"));
        new FileResearchProjectRepository(dir).save(generation("V2", "seg-v2", "fpB"));

        ResearchProject r = new FileResearchProjectRepository(dir).load("p1");
        // ONLY the active v2 sentences AND passages — never a v1/v2 mix (they share one generation dir).
        assertEquals(1, r.sentences().size());
        assertEquals("V2 sentence.", r.sentences().get("cap-1#s0").getText());
        assertEquals(1, r.passages().size());
        assertNotNull(r.passages().get("cap-1#p0@seg-v2-fpB"));
        assertNull("the superseded v1 passage is not active", r.passages().get("cap-1#p0@seg-v1-fpA"));
    }

    @Test
    public void theHistoricalGenerationStaysOnDiskButIsNotActive() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(generation("V1", "seg-v1", "fpA"));
        new FileResearchProjectRepository(dir).save(generation("V2", "seg-v2", "fpB"));
        // Two generation directories exist under derived/<h(cap-1)>/.
        File captureDerived = new File(dir, "knowledge/derived").listFiles()[0];
        assertEquals("both generations retained on disk", 2, captureDerived.listFiles().length);
    }

    @Test
    public void aCrashBeforeThePointerSwapKeepsThePreviousGenerationActive() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        repo.save(generation("V1", "seg-v1", "fpA"));
        String v1Pointer = new String(Files.readAllBytes(activePointer(dir).toPath()), UTF8);

        // Full v2 generation gets written, then a crash BEFORE the atomic pointer swap: restore the v1 pointer.
        repo.save(generation("V2", "seg-v2", "fpB"));
        Files.write(activePointer(dir).toPath(), v1Pointer.getBytes(UTF8));

        ResearchProject r = new FileResearchProjectRepository(dir).load("p1");
        assertEquals("V1 sentence.", r.sentences().get("cap-1#s0").getText());
        assertNotNull("complete v1, not a half state", r.passages().get("cap-1#p0@seg-v1-fpA"));
        assertNull(r.passages().get("cap-1#p0@seg-v2-fpB"));
    }

    @Test
    public void savingTheSameGenerationAgainIsIdempotentAndLeavesNoTempFiles() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        repo.save(generation("V1", "seg-v1", "fpA"));
        repo.save(generation("V1", "seg-v1", "fpA")); // same processing key + content → nothing rewritten
        assertTrue("no leftover .tmp files", tempFiles(new File(dir, "knowledge")).isEmpty());
        ResearchProject r = repo.load("p1");
        assertEquals(1, r.passages().size());
    }

    @Test
    public void multipleCapturesStaySeparate() throws IOException {
        File dir = tempDir();
        ResearchProject p = new ResearchProject("p1", IdSequence.counting());
        p.recordSourceCapture(capture());
        p.recordSourceCapture(new SourceCapture("cap-2", "src-1", "https://x/cap-2", 8L, "h2", "T", "A",
                Collections.<SourceCapture.StructuralBlock>emptyList()));
        new FileResearchProjectRepository(dir).save(p);
        ResearchProject r = new FileResearchProjectRepository(dir).load("p1");
        assertEquals(2, r.captures().size());
        assertNotNull(r.captures().get("cap-1"));
        assertNotNull(r.captures().get("cap-2"));
    }

    @Test
    public void anUnknownSchemaVersionIsRejected() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(generation("V1", "seg-v1", "fpA"));
        File meta = new File(dir, "knowledge/project.properties");
        Files.write(meta.toPath(), "schemaVersion=999\nprojectId=p1\n".getBytes(UTF8));
        try {
            new FileResearchProjectRepository(dir).load("p1");
            fail("an incompatible schema version must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("schema version"));
        }
    }

    @Test
    public void loadingTheWrongProjectIdIsRejected() throws IOException {
        File dir = tempDir();
        new FileResearchProjectRepository(dir).save(generation("V1", "seg-v1", "fpA"));
        try {
            new FileResearchProjectRepository(dir).load("a-different-project");
            fail("loading a mismatched project id must be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not the requested"));
        }
    }

    // ------------------------------------------------------------------ one active capture per source

    /** One processed capture of {@code sourceId}, captured at {@code capturedAt}, with one passage. */
    private static ResearchProject processed(String captureId, String sourceId, long capturedAt, String text) {
        ResearchProject p = new ResearchProject("p1", IdSequence.counting());
        SourceCapture.StructuralBlock b = new SourceCapture.StructuralBlock("b1",
                SourceCapture.BlockKind.PARAGRAPH, "", text);
        p.recordSourceCapture(new SourceCapture(captureId, sourceId, "https://x/page", capturedAt,
                "h-" + captureId, "T", "", Collections.singletonList(b)));
        p.recordSentences(Arrays.asList(new Sentence(captureId + "#s0", captureId, "b1", 0, text)));
        p.recordPassages(Arrays.asList(new Passage(captureId + "#p0", captureId,
                Arrays.asList(captureId + "#s0"), "", text, "fpA", "seg-v1")));
        return p;
    }

    /** As the passage store does it: load, record the new capture, save. */
    private static void store(FileResearchProjectRepository repo, ResearchProject processed) {
        ResearchProject project = repo.load("p1");
        for (SourceCapture capture : processed.captures().values()) {
            project.recordSourceCapture(capture);
        }
        project.recordSentences(new ArrayList<Sentence>(processed.sentences().values()));
        project.recordPassages(new ArrayList<Passage>(processed.passages().values()));
        repo.save(project);
    }

    @Test
    public void aNewerCaptureOfTheSameSourceReplacesTheOlderOne() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        store(repo, processed("cap-34", "source-61", 1000L, "First read of the page."));
        store(repo, processed("cap-7", "source-2", 1500L, "Another source."));
        store(repo, processed("cap-8", "source-61", 2000L, "Second read of the page."));

        ResearchProject r = new FileResearchProjectRepository(dir).load("p1");
        assertNull("the older capture of source-61 is no longer active", r.captures().get("cap-34"));
        assertNotNull(r.captures().get("cap-8"));
        assertNotNull("other sources are untouched", r.captures().get("cap-7"));
        assertEquals("only the newest capture's passages count", 2, r.passages().size());
        assertNull(r.passages().get("cap-34#p0"));
        assertNotNull(r.passages().get("cap-8#p0"));
        assertEquals("one active pointer per source", 2, new File(dir, "knowledge/active").listFiles().length);
        assertEquals(Collections.singleton("cap-34"), repo.supersededCaptureIds());
        assertEquals(Collections.singleton("cap-34"), repo.supersededCaptureIds("source-61"));
        assertTrue(repo.supersededCaptureIds("source-2").isEmpty());
        // History stays on disk: the older capture file and its generation are kept, only inactive.
        assertEquals(3, new File(dir, "knowledge/captures").listFiles().length);
    }

    @Test
    public void anOlderCaptureStoredLateNeverReplacesTheNewerOne() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        store(repo, processed("cap-8", "source-61", 2000L, "Second read of the page."));
        store(repo, processed("cap-34", "source-61", 1000L, "First read of the page."));

        ResearchProject r = repo.load("p1");
        assertNotNull(r.captures().get("cap-8"));
        assertNull(r.captures().get("cap-34"));
        assertEquals(1, r.passages().size());
        assertNotNull(r.passages().get("cap-8#p0"));
        assertEquals(Collections.singleton("cap-34"), repo.supersededCaptureIds("source-61"));
    }

    @Test
    public void reStoringTheActiveCaptureKeepsItActive() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        store(repo, processed("cap-34", "source-61", 1000L, "First read of the page."));
        store(repo, processed("cap-8", "source-61", 2000L, "Second read of the page."));
        store(repo, processed("cap-8", "source-61", 2000L, "Second read of the page."));
        ResearchProject r = repo.load("p1");
        assertEquals(1, r.captures().size());
        assertNotNull(r.passages().get("cap-8#p0"));
    }

    @Test
    public void capturesWithoutASourceAreNeverGrouped() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        store(repo, processed("cap-1", "", 1000L, "Unlinked one."));
        store(repo, processed("cap-2", "", 2000L, "Unlinked two."));
        ResearchProject r = repo.load("p1");
        assertEquals(2, r.captures().size());
        assertEquals(2, r.passages().size());
        assertTrue(repo.supersededCaptureIds().isEmpty());
    }

    @Test
    public void loadingAProjectWrittenBeforeTheRuleKeepsOnlyTheNewestCapturePerSource() throws IOException {
        File dir = tempDir();
        FileResearchProjectRepository repo = new FileResearchProjectRepository(dir);
        store(repo, processed("cap-34", "source-61", 1000L, "First read of the page."));
        File olderPointer = new File(dir, "knowledge/active").listFiles()[0];
        String legacy = new String(Files.readAllBytes(olderPointer.toPath()), UTF8);
        store(repo, processed("cap-8", "source-61", 2000L, "Second read of the page."));
        // Recreate the on-disk state of live run 4: both captures active, pointers in the old format (capture id +
        // processing key only), no superseded notes.
        String oldFormat = legacy.substring(0, legacy.indexOf("sourceId="));
        Files.write(olderPointer.toPath(), oldFormat.getBytes(UTF8));
        for (File f : new File(dir, "knowledge/active").listFiles()) {
            String content = new String(Files.readAllBytes(f.toPath()), UTF8);
            if (content.contains("sourceId=")) {
                Files.write(f.toPath(), content.substring(0, content.indexOf("sourceId=")).getBytes(UTF8));
            }
        }
        for (File f : new File(dir, "knowledge/superseded").listFiles()) {
            assertTrue(f.delete());
        }
        assertEquals(2, new File(dir, "knowledge/active").listFiles().length);

        ResearchProject r = new FileResearchProjectRepository(dir).load("p1");
        assertNull("newest wins on load", r.captures().get("cap-34"));
        assertNotNull(r.captures().get("cap-8"));
        assertEquals(1, r.passages().size());
        assertEquals("the duplicate pointer is cleaned up on disk", 1,
                new File(dir, "knowledge/active").listFiles().length);
        assertEquals(Collections.singleton("cap-34"), repo.supersededCaptureIds());
        // And stays clean on the next load.
        assertEquals(1, new FileResearchProjectRepository(dir).load("p1").captures().size());
    }

    private static File activePointer(File dir) {
        File[] pointers = new File(dir, "knowledge/active").listFiles();
        assertEquals("exactly one capture pointer", 1, pointers.length);
        return pointers[0];
    }

    private static List<File> tempFiles(File dir) {
        List<File> out = new ArrayList<File>();
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                if (f.isDirectory()) {
                    out.addAll(tempFiles(f));
                } else if (f.getName().endsWith(".tmp")) {
                    out.add(f);
                }
            }
        }
        return out;
    }
}
