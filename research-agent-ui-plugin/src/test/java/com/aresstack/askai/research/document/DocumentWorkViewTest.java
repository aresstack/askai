package com.aresstack.askai.research.document;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Document tab's main area: the book and the editor of the section selected in the sidebar. Switching
 * never drops unsaved text; a section the writer moved on shows its new text.
 */
public class DocumentWorkViewTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private static final class Fixture {
        final FakeDocumentSession session;
        final SectionEditPanel editor;
        final DocumentWorkView work;

        Fixture(FakeDocumentSession session) {
            this.session = session;
            final DocumentWorkView[] ref = new DocumentWorkView[1];
            editor = new SectionEditPanel(new SectionEditor(session.manuscript), session, line -> ref[0].say(line));
            work = new DocumentWorkView(new JLabel("pages"), editor);
            ref[0] = work;
        }

        SectionEditor.Item item(String sectionId) {
            for (SectionEditor.Item item : SectionEditor.items(session.outline)) {
                if (item.sectionId.equals(sectionId)) {
                    return item;
                }
            }
            throw new AssertionError(sectionId);
        }
    }

    private FakeDocumentSession session() {
        FakeDocumentSession session = new FakeDocumentSession(folder.getRoot());
        session.write("s1", "c1", "Falls are detected by an accelerometer.");
        return session;
    }

    @Test
    public void theBookShowsFirstAndASelectedSectionOpensInTheEditor() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            Fixture fx = new Fixture(session);
            assertEquals(DocumentWorkView.CARD_BOOK, fx.work.shownCard());
            assertEquals("Book", fx.work.titleText());
            fx.work.setBookState("incomplete draft");
            assertEquals("incomplete draft", fx.work.stateText());

            fx.work.openSection(null);
            assertEquals("a chapter heading opens nothing", DocumentWorkView.CARD_BOOK, fx.work.shownCard());

            fx.work.openSection(fx.item("s1"));
            assertEquals(DocumentWorkView.CARD_SECTION, fx.work.shownCard());
            assertEquals("1.1 Fall detection", fx.work.titleText());
            assertEquals("Written · revision 1", fx.work.stateText());
            assertEquals("Falls are detected by an accelerometer.", fx.editor.shownText());

            fx.work.openSection(fx.item("s4"));
            assertEquals("2.1 Works council", fx.work.titleText());
            assertEquals("Pending", fx.work.stateText());
            assertEquals("", fx.editor.shownText());
        });
    }

    @Test
    public void switchingToTheBookAndBackKeepsTheUnsavedText() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            Fixture fx = new Fixture(session);
            fx.work.openSection(fx.item("s1"));
            fx.editor.type("My own words.");
            fx.work.showCard(DocumentWorkView.CARD_BOOK);
            assertEquals("Book", fx.work.titleText());
            fx.work.showCard(DocumentWorkView.CARD_SECTION);
            assertEquals("My own words.", fx.editor.shownText());

            fx.work.openSection(fx.item("s4"));
            fx.work.openSection(fx.item("s1"));
            assertEquals("the draft of a section the user left is kept", "My own words.", fx.editor.shownText());
            assertEquals("Unsaved edits of this section were kept. Save them or reload.", fx.work.stateText());

            fx.editor.button("Save").doClick();
            assertEquals("Saved (revision 2).", fx.work.stateText());
            assertEquals(2, session.manuscript.store().section("s1").getRevision());
        });
    }

    @Test
    public void aSectionTheWriterMovedOnShowsItsNewTextUnlessTheUserIsEditing() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            Fixture fx = new Fixture(session);
            fx.work.openSection(fx.item("s1"));
            session.write("s1", "c1", "The writer's second version.");
            fx.editor.refreshState();
            assertEquals("The writer's second version.", fx.editor.shownText());

            fx.editor.type("I am typing.");
            session.write("s1", "c1", "The writer's third version.");
            fx.editor.refreshState();
            assertEquals("unsaved text is never replaced", "I am typing.", fx.editor.shownText());
            fx.editor.button("Save").doClick();
            assertEquals("the conflict shows the saved text below the local one",
                    "The writer's third version.", fx.editor.belowText());
            assertEquals("I am typing.", fx.editor.shownText());
        });
    }

    @Test
    public void theCommentDrivesTheRewritesAndTheGapSearch() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            Fixture fx = new Fixture(session);
            fx.work.openSection(fx.item("s1"));
            assertFalse("an open point needs words",
                    fx.editor.button("Research the comment as an open point of this section").isEnabled());
            fx.editor.typeComment("battery life");
            fx.editor.button("Rewrite this section (uses the comment)").doClick();
            assertEquals("Rewriting the section...", fx.work.stateText());
            fx.editor.button("Rewrite the whole chapter (uses the comment)").doClick();
            fx.editor.button("Research the comment as an open point of this section").doClick();
            assertEquals("Gap search added to the search plan and started.", fx.work.stateText());
        });
        assertEquals(Arrays.asList("rewrite-section s1 battery life", "rewrite-chapter c1 battery life",
                "gap s1 battery life"), session.started);
    }

    @Test
    public void skipTurnsIntoUnskipAndAReadOnlyBookLocksTheEditor() throws Exception {
        final FakeDocumentSession session = session();
        SwingUtilities.invokeAndWait(() -> {
            Fixture fx = new Fixture(session);
            fx.work.openSection(fx.item("s4"));
            fx.editor.button("Skip this section").doClick();
            fx.work.updateHead();
            assertTrue(fx.work.stateText().startsWith("Skipped"));
            assertEquals("Write this section after all (unskip)",
                    fx.editor.button("Skip this section").getToolTipText());
            fx.editor.button("Skip this section").doClick();
            fx.work.updateHead();
            assertEquals("Pending", fx.work.stateText());

            session.refusal = "The book is approved. The manuscript is read-only.";
            fx.editor.refreshState();
            assertFalse(fx.editor.button("Save").isEnabled());
            assertFalse(fx.editor.button("Skip this section").isEnabled());
            assertFalse(fx.editor.button("Rewrite this section (uses the comment)").isEnabled());
        });
    }

    @Test
    public void withoutAManuscriptThereIsOnlyTheBook() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            DocumentWorkView work = new DocumentWorkView(new JLabel("pages"), null);
            work.showCard(DocumentWorkView.CARD_SECTION);
            assertEquals(DocumentWorkView.CARD_BOOK, work.shownCard());
            assertEquals("Book", work.titleText());
        });
    }
}
