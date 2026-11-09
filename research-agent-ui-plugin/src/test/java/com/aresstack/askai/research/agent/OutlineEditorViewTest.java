package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 23: unsaved JSON survives a background refresh; the tab is read-only once the guard refuses. */
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
}
