package com.aresstack.askai.research.manuscript;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.store.FileManuscriptStore;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 26: the manuscript store with section revisions and its stale marks. */
public class ManuscriptStoreTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FileManuscriptStore store() throws Exception {
        return new FileManuscriptStore(new File(folder.getRoot(), "manuscript"));
    }

    private static ManuscriptCitation citation(String marker, String source) {
        return new ManuscriptCitation(marker, source, "sha-" + source, "Cited words from " + source + ".",
                "p-" + source, null);
    }

    private static SectionRevision.Builder text(String text, ManuscriptCitation... citations) {
        return SectionRevision.builder().text(text).citations(Arrays.asList(citations));
    }

    private static OutlineChapter chapter(String id, String title, OutlineSection... sections) {
        return new OutlineChapter(id, 1, title, new OutlineBrief("why " + title, Arrays.asList("k"), 400),
                new OutlineProvenance("n-" + id, Collections.<String>emptyList(), Collections.<String>emptyList()),
                false, "", Arrays.asList(sections));
    }

    private static OutlineSection section(String id, String parent, int order, String title) {
        return new OutlineSection(id, parent, order, title, new OutlineBrief("on " + title,
                Collections.<String>emptyList(), 200), OutlineProvenance.NONE, false, "");
    }

    private static SectionRevision.Builder writtenFrom(Outline outline, String sectionId, String text) {
        for (OutlineChapter c : outline.getChapters()) {
            for (OutlineSection s : c.getSections()) {
                if (s.getId().equals(sectionId)) {
                    return text(text, citation("1", "src-a")).author(SectionRevision.AUTHOR_WRITER)
                            .modelLabel("llama3.1:8b").sectionFingerprint(OutlineFingerprint.of(s))
                            .chapterFingerprint(OutlineFingerprint.of(c));
                }
            }
        }
        throw new AssertionError(sectionId);
    }

    @Test
    public void savesRevisionsAndKeepsEveryField() throws Exception {
        FileManuscriptStore store = store();
        FileManuscriptStore.Write first = store.save("s1", "c1", 0, text("Über Äpfel [1].", citation("1", "src-a"))
                .modelLabel("llama3.1:8b").thinkingLevel("off").promptHash("ph").evidenceIds(Arrays.asList("e1"))
                .gaps(Arrays.asList("needs a number")), 100L);
        assertEquals(1, first.revision.getNumber());
        store.save("s1", null, 1, text("Second."), 200L);

        FileManuscriptStore reopened = store();
        ManuscriptSection state = reopened.section("s1");
        assertEquals(SectionStatus.WRITTEN, state.getStatus());
        assertEquals(2, state.getRevision());
        assertEquals("c1", state.getChapterId());
        SectionRevision one = reopened.revision("s1", 1);
        assertEquals("Über Äpfel [1].", one.getText());
        assertEquals("llama3.1:8b", one.getModelLabel());
        assertEquals("ph", one.getPromptHash());
        assertEquals(Arrays.asList("e1"), one.getEvidenceIds());
        assertEquals(Arrays.asList("needs a number"), one.getGaps());
        assertEquals("sha-src-a", one.getCitations().get(0).getSourceChecksum());
        assertEquals(64, one.getCitations().get(0).getCitedTextHash().length());
        assertEquals(2, reopened.revisions("s1").size());
    }

    @Test
    public void refusesCitationsWithoutChecksumOrCitedText() {
        try {
            new ManuscriptCitation("1", "src", "", "text", "", null);
            fail("no checksum");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
        try {
            new ManuscriptCitation("1", "src", "sha", " ", "", null);
            fail("no cited text");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("cited text"));
        }
    }

    @Test
    public void concurrentJobAndUserSavesLoseNeitherText() throws Exception {
        final FileManuscriptStore store = store();
        store.save("s1", "c1", 0, text("Base."), 1L);
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicReference<Object> job = new AtomicReference<Object>();
        final AtomicReference<Object> user = new AtomicReference<Object>();
        Thread a = race(store, start, job, "Writer text.");
        Thread b = race(store, start, user, "User text.");
        start.countDown();
        a.join();
        b.join();
        Object loser = job.get() instanceof FileManuscriptStore.SectionConflictException ? job.get() : user.get();
        assertTrue("exactly one save conflicts", loser instanceof FileManuscriptStore.SectionConflictException);
        FileManuscriptStore.SectionConflictException conflict = (FileManuscriptStore.SectionConflictException) loser;
        String saved = store.current("s1").getText();
        assertEquals("the conflict shows the saved text", saved, conflict.getCurrentText());
        // the losing editor keeps its own text and saves it on top: both stay in the history
        store.save("s1", null, conflict.getCurrentRevision(), text("Merged."), 3L);
        assertEquals(3, store.revisions("s1").size());
        assertTrue(new HashSet<String>(Arrays.asList(store.revision("s1", 2).getText(), "Writer text.",
                "User text.")).size() == 2);
    }

    private static Thread race(final FileManuscriptStore store, final CountDownLatch start,
                               final AtomicReference<Object> outcome, final String text) {
        Thread thread = new Thread(new Runnable() {
            public void run() {
                try {
                    start.await();
                    outcome.set(store.save("s1", null, 1, text(text), 2L));
                } catch (Exception failed) {
                    outcome.set(failed);
                }
            }
        });
        thread.start();
        return thread;
    }

    @Test
    public void guardRefusesContentWritesButNotMarks() throws Exception {
        ManuscriptWorkspace workspace = new ManuscriptWorkspace(store());
        workspace.setWriteGuard(new ManuscriptWorkspace.WriteGuard() {
            public String refusal() {
                return "Waiting for your review.";
            }
        });
        try {
            workspace.save("s1", "c1", 0, text("x"), 1L);
            fail("refused outside draft/running");
        } catch (ManuscriptWorkspace.WriteRefusedException expected) {
            assertEquals("Waiting for your review.", expected.getMessage());
        }
        try {
            workspace.skip("s1", "c1", 1L);
            fail("skip is a content write");
        } catch (ManuscriptWorkspace.WriteRefusedException expected) {
            // ok
        }
        Outline outline = new Outline(Arrays.asList(chapter("c1", "One", section("s1", "c1", 1, "A"))));
        workspace.reconcileOutline(outline, 1, 2L);
        assertEquals(SectionStatus.PENDING, workspace.store().section("s1").getStatus());
    }

    @Test
    public void skipUnskipAndRestore() throws Exception {
        ManuscriptWorkspace workspace = new ManuscriptWorkspace(store());
        workspace.save("s1", "c1", 0, text("one"), 1L);
        workspace.save("s1", "c1", 1, text("two"), 2L);
        FileManuscriptStore.Write restored = workspace.restore("s1", 1, 2, 3L);
        assertEquals(3, restored.revision.getNumber());
        assertEquals("one", restored.revision.getText());
        assertEquals(1, restored.revision.getRestoredFrom());
        assertEquals(SectionStatus.SKIPPED, workspace.skip("s1", null, 4L).getStatus());
        assertEquals(SectionStatus.PENDING, workspace.unskip("s1", 5L).getStatus());
    }

    @Test
    public void outlineReconcileMarksOnlyChangedEntries() throws Exception {
        ManuscriptWorkspace workspace = new ManuscriptWorkspace(store());
        Outline v1 = new Outline(Arrays.asList(
                chapter("c1", "One", section("s1", "c1", 1, "A"), section("s2", "c1", 2, "B")),
                chapter("c2", "Two", section("s3", "c2", 1, "C")),
                chapter("c3", "Three", section("s4", "c3", 1, "D"))));
        workspace.reconcileOutline(v1, 1, 1L);
        for (String id : new String[] {"s1", "s2", "s3", "s4"}) {
            workspace.save(id, null, 0, writtenFrom(v1, id, "Text of " + id), 2L);
        }
        // reopen without change: nothing becomes stale, the text stays
        assertTrue(workspace.reconcileOutline(v1, 2, 3L).isEmpty());
        // rename chapter c2, delete s4's chapter, add s5
        Outline v2 = new Outline(Arrays.asList(
                chapter("c1", "One", section("s1", "c1", 1, "A"), section("s2", "c1", 2, "B")),
                chapter("c2", "Two renamed", section("s3", "c2", 1, "C"), section("s5", "c2", 2, "E"))));
        Map<String, String> marked = workspace.reconcileOutline(v2, 3, 4L);
        assertEquals(new HashSet<String>(Arrays.asList("s3", "s4")), marked.keySet());
        FileManuscriptStore store = workspace.store();
        assertEquals(SectionStatus.WRITTEN, store.section("s1").getStatus());
        assertEquals(SectionStatus.WRITTEN, store.section("s2").getStatus());
        assertEquals(SectionStatus.STALE, store.section("s3").getStatus());
        assertTrue(store.section("s3").getReason().contains("Two"));
        assertEquals("nothing rewrites a stale section", "Text of s3", store.current("s3").getText());
        assertEquals(SectionStatus.ORPHAN, store.section("s4").getStatus());
        assertEquals("Text of s4", store.current("s4").getText());
        assertEquals(SectionStatus.PENDING, store.section("s5").getStatus());
        // idempotent
        assertTrue(workspace.reconcileOutline(v2, 3, 5L).isEmpty());
        try {
            store.save("s4", null, 1, text("x"), 6L);
            fail("an orphan takes no new text");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void evidenceApprovalMarksSectionsCitingRemovedSources() throws Exception {
        ManuscriptWorkspace workspace = new ManuscriptWorkspace(store());
        workspace.save("s1", "c1", 0, text("a [1]", citation("1", "src-a")), 1L);
        workspace.save("s2", "c1", 0, text("b [1]", citation("1", "src-b")), 1L);
        Map<String, String> marked = workspace.markCitingOutside(
                new HashSet<String>(Arrays.asList("src-a")), 2L);
        assertEquals(Collections.singleton("s2"), marked.keySet());
        assertEquals(SectionStatus.STALE, workspace.store().section("s2").getStatus());
        assertEquals(SectionStatus.WRITTEN, workspace.store().section("s1").getStatus());
    }

    @Test
    public void damagedStateFailsClosedAndIsKept() throws Exception {
        FileManuscriptStore store = store();
        store.save("s1", "c1", 0, text("one"), 1L);
        File revision = new File(store.directory(), "sections/s1/0001.json");
        Files.write(revision.toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        try {
            store.section("s1");
            fail("fails closed");
        } catch (FileManuscriptStore.CorruptManuscriptException expected) {
            assertTrue(expected.getMessage().contains("kept"));
        }
        assertEquals("{broken", new String(Files.readAllBytes(revision.toPath()), StandardCharsets.UTF_8));
        assertNull(store.section("other"));
        assertNotNull(store.directory());
        List<String> unused = Collections.emptyList();
        assertTrue(unused.isEmpty());
    }
}
