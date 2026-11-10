package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.SectionStatus;
import com.aresstack.askai.research.tabs.RowSignal;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The Document tab's sidebar: the book in order with the writing state of every section, one colour per
 * state, and the actions on the whole book.
 */
public class DocumentSectionsViewTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private FakeDocumentSession session() {
        FakeDocumentSession session = new FakeDocumentSession(folder.getRoot());
        session.write("s1", "c1", "Falls are detected by an accelerometer.");
        session.write("s2", "c1", "Heat is measured at the wrist.");
        session.manuscript.store().mark("s2", "c1", SectionStatus.STALE, "its sources changed", 2L);
        session.manuscript.markFailed("s3", "c1", "no evidence found", 3L);
        return session;
    }

    @Test
    public void theListShowsTheBookInOrderWithTheStateOfEverySection() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            DocumentSectionsView view = new DocumentSectionsView();
            view.setSession(session);
            assertEquals(6, view.rowCount());
            assertEquals(Arrays.asList("1", "Wearables on site", "2 of 3"), row(view, 0));
            assertEquals("a chapter heading carries no colour", RowSignal.NONE, view.signalAt(0));
            assertEquals(Arrays.asList("1.1", "Fall detection", "Written"), row(view, 1));
            assertEquals(RowSignal.DONE, view.signalAt(1));
            assertEquals(Arrays.asList("1.2", "Heat stress", "Stale"), row(view, 2));
            assertEquals(RowSignal.CAUTION, view.signalAt(2));
            assertEquals(Arrays.asList("1.3", "Exoskeletons", "Failed"), row(view, 3));
            assertEquals(RowSignal.FAILED, view.signalAt(3));
            assertEquals(Arrays.asList("2", "Data protection", "0 of 1"), row(view, 4));
            assertEquals(Arrays.asList("2.1", "Works council", "Pending"), row(view, 5));
            assertEquals(RowSignal.PENDING, view.signalAt(5));
            assertEquals("2 of 4 sections written · 1 stale · 1 failed", view.summaryText());

            session.bookStatus = "incomplete draft";
            view.refresh();
            assertEquals("2 of 4 sections written · 1 stale · 1 failed · book: incomplete draft",
                    view.summaryText());
        });
    }

    @Test
    public void selectingASectionOpensItAndAChapterHeadingOpensNothing() throws Exception {
        final FakeDocumentSession session = session();
        final List<String> opened = new ArrayList<String>();
        SwingUtilities.invokeAndWait(() -> {
            DocumentSectionsView view = new DocumentSectionsView();
            view.setSession(session);
            view.setSelectionListener(item -> opened.add(item == null ? null : item.sectionId));
            view.selectRow(2);
            view.selectRow(0);
            view.selectRow(5);
            view.refresh(); // a repaint keeps the selection and does not open the section again
        });
        assertEquals(Arrays.asList("s2", null, "s4"), opened);
    }

    @Test
    public void theFilterKeepsTheChapterOfAHitAndTheSummaryStillCountsTheWholeBook() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            DocumentSectionsView view = new DocumentSectionsView();
            view.setSession(session);
            view.typeFilter("council");
            assertEquals(2, view.rowCount());
            assertEquals("2", view.cellAt(0, 0));
            assertEquals("2.1", view.cellAt(1, 0));
            assertEquals("2 of 4 sections written · 1 stale · 1 failed", view.summaryText());
        });
    }

    @Test
    public void theButtonsActOnTheWholeBook() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            DocumentSectionsView view = new DocumentSectionsView();
            view.setSession(session);
            assertTrue(view.button("Write the remaining sections").isEnabled());
            assertFalse("nothing runs", view.button("Stop writing").isVisible());
            assertFalse("no stale section known to the writer", view.button("Rewrite the stale sections").isEnabled());
            assertFalse("no export without an artifact store", view.button("Export the book").isEnabled());
            view.button("Write the remaining sections").doClick();
            assertEquals("Writing...", view.notesText());

            session.running = true;
            session.stale = true;
            session.writingStatus = "Writing 2.1 Works council (3 of 4)";
            session.changeRequest = "Shorter sentences";
            session.refusal = "Waiting for your review. Approve or Request changes.";
            view.say("");
            assertTrue(view.button("Stop writing").isVisible());
            assertFalse("no second job while one runs", view.button("Write the remaining sections").isEnabled());
            assertFalse(view.button("Rewrite the stale sections").isEnabled());
            assertEquals("Waiting for your review. Approve or Request changes.\n"
                    + "Requested changes: Shorter sentences\nWriting 2.1 Works council (3 of 4)", view.notesText());
            view.button("Stop writing").doClick();

            session.running = false;
            session.startRefusal = "The writer model is not configured.";
            view.refresh();
            view.button("Rewrite the stale sections").doClick();
            assertTrue(view.notesText().endsWith("The writer model is not configured."));
        });
        assertEquals(Arrays.asList("write-remaining", "stop", "rewrite-stale"), session.started);
    }

    @Test
    public void withoutASessionTheTabIsEmptyAndQuiet() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DocumentSectionsView view = new DocumentSectionsView();
            assertEquals(0, view.rowCount());
            assertEquals("No approved outline yet", view.summaryText());
            assertEquals("", view.notesText());
            assertFalse(view.button("Write the remaining sections").isEnabled());
            assertNull(view.button("No such button"));
        });
    }

    private static List<Object> row(DocumentSectionsView view, int row) {
        return Arrays.asList(view.cellAt(row, 0), view.cellAt(row, 1), view.cellAt(row, 2));
    }
}
