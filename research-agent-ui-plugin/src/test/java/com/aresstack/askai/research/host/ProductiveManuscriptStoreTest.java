package com.aresstack.askai.research.host;

import com.aresstack.askai.research.book.ManuscriptPin;
import com.aresstack.askai.research.document.SectionEditor;
import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.OutlineFingerprint;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.store.FileManuscriptStore;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.Fx;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.assertHandled;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.firstMessage;
import static com.aresstack.askai.research.host.ProductiveBookJourneyTest.phase;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Plan PR 26 "Done when": the manuscript store, its guard, stale marks and pin over the productive path. */
public class ProductiveManuscriptStoreTest {

    private static OutlineChapter chapter(String title, String... sections) {
        List<OutlineSection> list = new ArrayList<OutlineSection>();
        for (String section : sections) {
            list.add(OutlineSection.draft(section, new OutlineBrief("On " + section,
                    Collections.<String>emptyList(), 300), OutlineProvenance.NONE));
        }
        return OutlineChapter.draft(title, new OutlineBrief("Why " + title, Arrays.asList("a"), 600),
                OutlineProvenance.NONE, list);
    }

    /** Concept -> Sources -> an outline with two chapters -> Document. */
    private static Outline toDocument(Fx fx) {
        firstMessage(fx, "A book about plugin isolation in Java");
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        OutlineWorkspace outline = fx.session.outlineWorkspace();
        outline.save(new Outline(Arrays.asList(chapter("Isolation", "Boundaries", "Costs"),
                chapter("Loaders", "Delegation"))), outline.workingRevision(), 1L);
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("draft/running", phase(fx));
        return fx.session.approvedOutlineOrNull();
    }

    private static void writeAll(ManuscriptWorkspace manuscript, Outline outline) {
        for (OutlineChapter chapter : outline.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                manuscript.save(section.getId(), chapter.getId(), manuscript.store().section(section.getId())
                                .getRevision(),
                        SectionRevision.builder().text(section.getTitle() + " text [1].")
                                .citations(Collections.singletonList(new ManuscriptCitation("1", "src-1",
                                        "sha-1", "cited words", "p1", null)))
                                .author(SectionRevision.AUTHOR_WRITER).modelLabel("llama3.1:8b")
                                .sectionFingerprint(OutlineFingerprint.of(section))
                                .chapterFingerprint(OutlineFingerprint.of(chapter)), 2L);
            }
        }
    }

    @Test
    public void approvingTheOutlineCreatesPendingSectionsAndRendersTheDocument() {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        ManuscriptWorkspace manuscript = fx.session.manuscript();
        assertEquals(3, manuscript.store().sections().size());
        for (String id : outline.allIds()) {
            if (outline.chapter(id) == null) {
                assertEquals(SectionStatus.PENDING, manuscript.store().section(id).getStatus());
            }
        }
        writeAll(manuscript, outline);
        String document = com.aresstack.askai.research.manuscript.ManuscriptDocument.markdown(
                fx.session.approvedOutlineOrNull(), manuscript.store());
        assertTrue(document, document.contains("Boundaries text [1]."));
        assertTrue(document, document.contains("# 2 Loaders"));
    }

    @Test
    public void writesOnlyInDraftRunningAndReadOnlyAfterFinishDocument() {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        ManuscriptWorkspace manuscript = fx.session.manuscript();
        assertNotNull(manuscript);
        assertRefused(manuscript, "scoping/running");
        Outline outline = toDocumentFromScoping(fx);
        writeAll(manuscript, outline);
        assertHandled(fx.session.executeCommand("finish-document", ""));
        assertEquals("draft/waiting_approval", phase(fx));
        assertRefused(manuscript, "draft/waiting_approval");
        assertHandled(fx.session.executeCommand("request-changes", "tighten"));
        assertEquals("draft/running", phase(fx));
        String id = outline.getChapters().get(0).getSections().get(0).getId();
        manuscript.skip(id, null, 3L);
        assertEquals(SectionStatus.SKIPPED, manuscript.store().section(id).getStatus());
        manuscript.unskip(id, 4L);
        assertEquals(SectionStatus.PENDING, manuscript.store().section(id).getStatus());
    }

    /**
     * The Document tab's two job buttons are commands on every surface (live run 3, 2026-11-10: a session
     * driven by typed lines had no way to rewrite the sections the reopened outline had made stale).
     */
    @Test
    public void theDocumentJobButtonsAreCommandsWithTheSameRefusals() {
        Fx fx = new Fx();
        firstMessage(fx, "A book");
        String early = fx.session.executeCommand("write-sections", "");
        assertTrue(early, early.startsWith("rejected") && !early.contains("unknown command"));
        String unknown = fx.session.executeCommand("nonsense", "");
        assertTrue(unknown, unknown.contains("write-sections") && unknown.contains("rewrite-stale"));
        toDocumentFromScoping(fx);
        String rewrite = fx.session.executeCommand("rewrite-stale", "");
        assertTrue(rewrite, !rewrite.contains("unknown command"));
    }

    private static Outline toDocumentFromScoping(Fx fx) {
        ConceptTestSupport.makeSubmittable(fx.session);
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        OutlineWorkspace outline = fx.session.outlineWorkspace();
        outline.save(new Outline(Arrays.asList(chapter("Isolation", "Boundaries", "Costs"),
                chapter("Loaders", "Delegation"))), outline.workingRevision(), 1L);
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        return fx.session.approvedOutlineOrNull();
    }

    private static void assertRefused(ManuscriptWorkspace manuscript, String where) {
        try {
            manuscript.save("s-late", "c", 0, SectionRevision.builder().text("late"), 1L);
            fail("a write in " + where + " must be refused");
        } catch (ManuscriptWorkspace.WriteRefusedException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }

    @Test
    public void jumpBackToConceptAndRenamedChapterMarksExactlyItsSectionsStale() {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        ManuscriptWorkspace manuscript = fx.session.manuscript();
        writeAll(manuscript, outline);

        assertHandled(fx.session.executeCommand("reopen-concept", ""));
        assertEquals("scoping/running", phase(fx));
        assertHandled(fx.session.executeCommand("submit-scope", ""));
        assertHandled(fx.session.executeCommand("finish-sources", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("outline/running", phase(fx));
        OutlineWorkspace workspace = fx.session.outlineWorkspace();
        Outline current = workspace.current();
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(current.getChapters());
        chapters.set(1, chapters.get(1).withTitle("Class loaders"));
        workspace.save(current.withChapters(chapters), workspace.workingRevision(), 5L);
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("draft/running", phase(fx));

        FileManuscriptStore store = manuscript.store();
        for (OutlineChapter chapter : outline.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                SectionStatus expected = chapter.getTitle().equals("Loaders")
                        ? SectionStatus.STALE : SectionStatus.WRITTEN;
                assertEquals(section.getTitle(), expected, store.section(section.getId()).getStatus());
                assertEquals("nothing rewrites a section by itself", section.getTitle() + " text [1].",
                        store.current(section.getId()).getText());
            }
        }
    }

    @Test
    public void reopenOutlineWithoutChangesKeepsEveryText() {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        ManuscriptWorkspace manuscript = fx.session.manuscript();
        writeAll(manuscript, outline);
        assertHandled(fx.session.executeCommand("reopen-outline", ""));
        assertHandled(fx.session.executeCommand("finish-outline", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        for (String id : outline.allIds()) {
            if (outline.chapter(id) == null) {
                assertEquals(SectionStatus.WRITTEN, manuscript.store().section(id).getStatus());
            }
        }
    }

    @Test
    public void approveFinalPinsTheSectionRevisionsAndModelLabels() throws Exception {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        writeAll(fx.session.manuscript(), outline);
        assertHandled(fx.session.executeCommand("finish-document", ""));
        assertHandled(fx.session.executeCommand("approve", ""));
        assertEquals("draft/completed", phase(fx));
        ManuscriptPin pin = fx.session.bookManifest().validManuscriptPin(id -> true);
        assertNotNull(pin);
        assertEquals(3, pin.getSections().size());
        assertEquals(Collections.singletonList("llama3.1:8b"), pin.modelLabels());
        assertEquals(1, pin.getSections().get(0).getRevision());
        assertEquals("WRITTEN", pin.getSections().get(0).getStatus());
    }

    @Test
    public void editorKeepsLocalTextOnConflictAndShowsTheSavedText() {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        ManuscriptWorkspace manuscript = fx.session.manuscript();
        SectionEditor editor = new SectionEditor(manuscript);
        SectionEditor.Item item = SectionEditor.items(outline).get(0);
        editor.open(item);
        editor.edit("My edit.");
        // the writer job saves meanwhile
        manuscript.save(item.sectionId, item.chapterId, 0, SectionRevision.builder().text("Writer text.")
                .author(SectionRevision.AUTHOR_WRITER), 3L);
        SectionEditor.SaveResult conflict = editor.save(4L);
        assertFalse(conflict.saved);
        assertEquals("Writer text.", conflict.storedText);
        assertEquals("the local text stays", "My edit.", editor.localText());
        assertTrue(editor.save(5L).saved);
        assertEquals("My edit.", manuscript.store().current(item.sectionId).getText());
        assertEquals("Writer text.", manuscript.store().revision(item.sectionId, 1).getText());
    }

    @Test
    public void retryAfterConflictKeepsTheCitationsOfTheLocalText() {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        ManuscriptWorkspace manuscript = fx.session.manuscript();
        SectionEditor.Item item = SectionEditor.items(outline).get(0);
        manuscript.save(item.sectionId, item.chapterId, 0, SectionRevision.builder().text("Claim [1].")
                .citations(Collections.singletonList(new ManuscriptCitation("1", "src-A", "sha-A", "a", "", null))),
                2L);
        SectionEditor editor = new SectionEditor(manuscript);
        editor.open(item);
        editor.edit("My claim [1].");
        manuscript.save(item.sectionId, item.chapterId, 1, SectionRevision.builder().text("Other [1].")
                .citations(Collections.singletonList(new ManuscriptCitation("1", "src-B", "sha-B", "b", "", null))),
                3L);
        assertFalse(editor.save(4L).saved);
        assertTrue(editor.save(5L).saved);
        assertEquals("src-A", manuscript.store().current(item.sectionId).getCitations().get(0).getSourceId());
    }

    @Test
    public void switchingSectionsKeepsUnsavedTextAndRebindTakesNewMetadata() {
        Fx fx = new Fx();
        Outline outline = toDocument(fx);
        SectionEditor editor = new SectionEditor(fx.session.manuscript());
        List<SectionEditor.Item> items = SectionEditor.items(outline);
        editor.open(items.get(0));
        editor.edit("Unsaved.");
        editor.open(items.get(1));
        assertTrue(editor.hasDraft(items.get(0).sectionId));
        editor.open(items.get(0));
        assertEquals("Unsaved.", editor.localText());
        assertTrue(editor.isDirty());
        SectionEditor.Item renamed = SectionEditor.items(new Outline(Collections.singletonList(
                outline.getChapters().get(0).withTitle("Renamed")))).get(0);
        editor.rebind(renamed);
        assertEquals("Renamed", editor.item().chapter.getTitle());
        assertEquals("Unsaved.", editor.localText());
    }
}
