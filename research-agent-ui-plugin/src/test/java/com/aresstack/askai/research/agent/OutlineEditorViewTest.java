package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.tabs.RowSignal;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Plan PR 23: unsaved JSON survives a background refresh; the tab is read-only once the guard refuses. The
 * list shows the book in order with one colour per state, the three cards show the same outline, and earlier
 * revisions are previewed with the arrow buttons.
 */
public class OutlineEditorViewTest {

    private static Outline outline(String title) {
        return new Outline(Collections.singletonList(new OutlineChapter("ch-1", 1, title, OutlineBrief.EMPTY,
                OutlineProvenance.NONE, false, "", Collections.<OutlineSection>emptyList())));
    }

    @Test
    public void backgroundRefreshKeepsUnsavedTextAndFreezeMakesItReadOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.render(new OutlineEditorView.Snapshot(outline("A"), 1, null, "Shorter chapters"));
            assertTrue(view.isEditable());
            view.typeJson("{ unsaved");
            assertTrue(view.isDirty());
            view.render(new OutlineEditorView.Snapshot(outline("B"), 2, null, ""));
            assertEquals("{ unsaved", view.jsonText());
            view.button("Discard").doClick();
            assertTrue(view.jsonText().contains("\"B\""));

            view.render(new OutlineEditorView.Snapshot(outline("B"), 2,
                    "Frozen at revision 1. Reopen Outline to change it.", ""));
            assertFalse(view.isEditable());
            assertFalse(view.button("Add chapter").isEnabled());
            assertFalse(view.button("Generate outline").isEnabled());
            assertFalse(view.button("Save").isEnabled());
        });
    }

    @Test
    public void aJsonSaveNamesTheRevisionTheTextWasBasedOn() throws Exception {
        final long[] expected = {-1};
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.setActions(new OutlineEditorView.Actions() {
                public String save(Outline draft, long expectedRevision) {
                    expected[0] = expectedRevision;
                    return "The outline changed meanwhile.";
                }

                public String restore(long revision, long expectedRevision) {
                    return null;
                }

                public String history(long revision) {
                    return null;
                }

                public void generate() {
                }
            });
            view.render(new OutlineEditorView.Snapshot(outline("A"), 1, null, ""));
            view.typeJson(view.jsonText().replace("\"A\"", "\"C\""));
            view.render(new OutlineEditorView.Snapshot(outline("B"), 2, null, ""));
            view.button("Save").doClick();
            assertTrue(view.isDirty());
        });
        assertEquals(1L, expected[0]);
    }

    /** Two chapters: a ready one with a ready and a thin section, and a stale one without a brief. */
    private static Outline book() {
        OutlineBrief brief = new OutlineBrief("Why sensors on site matter.", Arrays.asList("Falls", "Heat"), 800);
        OutlineSection ready = new OutlineSection("s-1", "ch-1", 1, "Fall detection", brief,
                OutlineProvenance.NONE, true, "");
        OutlineSection thin = new OutlineSection("s-2", "ch-1", 2, "Heat stress", brief,
                OutlineProvenance.NONE, false, "", false, true);
        OutlineChapter first = new OutlineChapter("ch-1", 1, "Wearables on site", brief, OutlineProvenance.NONE,
                false, "", Arrays.asList(ready, thin));
        OutlineChapter second = new OutlineChapter("ch-2", 2, "Data protection", OutlineBrief.EMPTY,
                OutlineProvenance.NONE, false, "sources changed", Collections.<OutlineSection>emptyList());
        OutlineChapter third = new OutlineChapter("ch-3", 3, "Outlook", OutlineBrief.EMPTY,
                OutlineProvenance.NONE, false, "", Collections.<OutlineSection>emptyList());
        return new Outline(Arrays.asList(first, second, third));
    }

    private static final class RecordingActions implements OutlineEditorView.Actions {
        long restored = -1;
        boolean cancelled;

        public String save(Outline draft, long expectedRevision) {
            return null;
        }

        public String restore(long revision, long expectedRevision) {
            restored = revision;
            return null;
        }

        public String history(long revision) {
            return revision == 1 ? OutlineJson.write(outline("First draft")) : null;
        }

        public void generate() {
        }

        public void cancelGenerate() {
            cancelled = true;
        }
    }

    @Test
    public void theListShowsTheBookInOrderWithOneColourPerState() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.render(new OutlineEditorView.Snapshot(book(), 3, null, ""));
            assertEquals(5, view.rowCount());
            assertEquals("1", view.cellAt(0, 0));
            assertEquals("Wearables on site", view.cellAt(0, 1));
            assertEquals("Ready", view.cellAt(0, 2));
            assertEquals(RowSignal.DONE, view.signalAt(0));
            assertEquals("1.1", view.cellAt(1, 0));
            assertEquals("Edited", view.cellAt(1, 2));
            assertEquals("Thin", view.cellAt(2, 2));
            assertEquals(RowSignal.CAUTION, view.signalAt(2));
            assertEquals("Stale", view.cellAt(3, 2));
            assertEquals(RowSignal.CAUTION, view.signalAt(3));
            assertEquals("No brief", view.cellAt(4, 2));
            assertEquals(RowSignal.PENDING, view.signalAt(4));
            assertEquals("3 chapters · 2 sections · revision 3", view.summaryText());

            view.markBriefFailed("ch-3");
            assertEquals("Brief failed", view.cellAt(4, 2));
            assertEquals(RowSignal.FAILED, view.signalAt(4));
        });
    }

    @Test
    public void theFilterKeepsTheChapterOfAMatchingSection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.render(new OutlineEditorView.Snapshot(book(), 3, null, ""));
            view.typeFilter("heat");
            assertEquals(2, view.rowCount());
            assertEquals("1", view.cellAt(0, 0));
            assertEquals("1.2", view.cellAt(1, 0));
            view.typeFilter("");
            assertEquals(5, view.rowCount());
        });
    }

    @Test
    public void theTextCardShowsTheBriefsAndTheNotesSayWhyTheOutlineIsFrozen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.render(new OutlineEditorView.Snapshot(book(), 3, null, ""));
            assertTrue(view.shownText().contains("Why sensors on site matter."));
            assertTrue(view.shownText().contains("- Falls"));
            assertEquals("", view.notesText());

            view.render(new OutlineEditorView.Snapshot(book(), 3,
                    "Frozen at revision 2. Reopen Outline to change it.", "Shorter chapters")
                    .withText("# Topics found so far").withRun("Clustering topics … 40 %", true, false));
            assertEquals("# Topics found so far", view.shownText());
            assertEquals("Frozen at revision 2. Reopen Outline to change it.\n"
                    + "Change request: Shorter chapters\nClustering topics … 40 %", view.notesText());
        });
    }

    @Test
    public void theArrowsPreviewARevisionAndSaveMakesItCurrent() throws Exception {
        final RecordingActions actions = new RecordingActions();
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.setActions(actions);
            view.render(new OutlineEditorView.Snapshot(outline("Second draft"), 2, null, ""));
            assertFalse("nothing to save on the live head", view.button("Save").isVisible());
            assertFalse(view.button("Preview the next revision").isEnabled());

            view.button("Preview the previous revision").doClick();
            assertEquals(1L, view.previewedRevision());
            assertEquals("First draft", view.cellAt(0, 1));
            assertTrue(view.jsonText().contains("First draft"));
            assertEquals("1 chapter · 0 sections · preview of revision 1 of 2", view.summaryText());
            assertFalse("a preview is read-only", view.button("Add chapter").isEnabled());
            assertTrue(view.button("Save").isVisible());

            view.button("Preview the next revision").doClick();
            assertEquals("the newest revision is the live head", -1L, view.previewedRevision());
            assertEquals("Second draft", view.cellAt(0, 1));

            view.button("Preview the previous revision").doClick();
            view.button("Save").doClick();
            assertEquals(-1L, view.previewedRevision());
            assertEquals("Restored revision 1.", view.statusText());
        });
        assertEquals(1L, actions.restored);
    }

    @Test
    public void theStopButtonShowsOnlyWhileTheGenerationRuns() throws Exception {
        final RecordingActions actions = new RecordingActions();
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.setActions(actions);
            view.render(new OutlineEditorView.Snapshot(book(), 3, null, ""));
            assertFalse(view.button("Stop generating").isVisible());
            assertTrue(view.button("Generate outline").isEnabled());

            view.render(new OutlineEditorView.Snapshot(book(), 3, null, "")
                    .withRun("Clustering topics … 10 %", true, false));
            assertTrue(view.button("Stop generating").isVisible());
            assertFalse("no second start while it runs", view.button("Generate outline").isEnabled());
            view.button("Stop generating").doClick();
        });
        assertTrue(actions.cancelled);
    }

    @Test
    public void theEntryButtonsBelongToTheListCard() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            OutlineEditorView view = new OutlineEditorView();
            view.setActions(new RecordingActions());
            view.render(new OutlineEditorView.Snapshot(book(), 3, null, ""));
            view.select("ch-2");
            assertTrue(view.button("Rename").isEnabled());
            assertTrue("the second chapter can become a section of the first", view.button("Indent").isEnabled());
            assertFalse(view.button("Outdent").isEnabled());
            view.select("s-1");
            assertTrue(view.button("Outdent").isEnabled());
            assertFalse(view.button("Regenerate chapter").isEnabled());

            view.selectCard(OutlineEditorView.CARD_JSON);
            assertFalse(view.button("Rename").getParent().isVisible());
            assertTrue(view.button("Generate outline").getParent().isVisible());
        });
    }
}
