package com.aresstack.comiccontrols.control;

import org.junit.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.Component;
import java.awt.Dimension;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ComicFormTest {

    /** The bug behind the old pages: a short page was stretched over the viewport, rows drifted apart. */
    @Test
    public void shortPageKeepsItsRowsCompactInsteadOfSpreadingOverTheViewport() {
        ComicForm form = ComicForm.titled("Notifications");
        form.check(ComicControls.checkBox("One"));
        form.check(ComicControls.checkBox("Two"));
        form.row("Sound", ComicControls.<String>comboBox(new String[] {"click"}));
        JScrollPane page = ComicForm.page(form);
        layout(page, 900, 900);

        Component[] rows = form.getComponents();
        int previousBottom = -1;
        for (Component row : rows) {
            if (previousBottom >= 0) {
                int gap = row.getY() - previousBottom;
                assertTrue("rows must stay close together, gap was " + gap, gap <= ComicForm.ROW_GAP + 8);
            }
            previousBottom = row.getY() + row.getHeight();
        }
        assertTrue("the form must not be stretched to the page height",
                form.getHeight() < 300);
    }

    /** No horizontal scroll bar ever: a long text field shrinks to the page instead of widening it. */
    @Test
    public void longTextFieldFollowsThePageWidthInsteadOfForcingAHorizontalScrollBar() {
        ComicForm form = new ComicForm();
        JTextField url = ComicControls.textField("https://example.invalid/" + repeat('x', 400), 80);
        form.row("URL", url);
        JScrollPane page = ComicForm.page(form);
        layout(page, 420, 300);

        assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, page.getHorizontalScrollBarPolicy());
        assertTrue("the view must track the viewport width",
                page.getViewport().getView().getWidth() <= 420);
        assertTrue("the field must end inside the page", url.getX() + url.getWidth()
                <= form.getWidth());
    }

    @Test
    public void fieldsOfOneFormStartAtTheSameXAndAlignedFormsShareTheLabelColumn() {
        ComicForm a = new ComicForm();
        JTextField shortLabelled = ComicControls.textField("a", 10);
        a.row("Short", shortLabelled);
        ComicForm b = new ComicForm();
        JTextField longLabelled = ComicControls.textField("b", 10);
        b.row("A much, much longer label", longLabelled);
        ComicForm.alignLabels(a, b);
        JScrollPane page = ComicForm.page(a, b);
        layout(page, 600, 400);

        assertEquals(longLabelled.getX(), shortLabelled.getX());
    }

    @Test
    public void choiceControlsKeepTheirNaturalWidthWhileTextFieldsStretch() {
        assertFalse(ComicForm.stretches(ComicControls.<String>comboBox()));
        assertFalse(ComicForm.stretches(ComicControls.spinner()));
        assertFalse(ComicForm.stretches(new JButton("x")));
        assertTrue(ComicForm.stretches(ComicControls.textField(3)));
        assertTrue(ComicForm.stretches(new JScrollPane(ComicControls.textArea(2, 2))));
    }

    @Test
    public void durationFieldReadsAndWritesTheOllamaSyntax() {
        ComicDurationField field = new ComicDurationField(100000);
        field.setText("45m");
        assertEquals(2700L, field.getSeconds());
        assertEquals("45m", field.getText());
        field.setText("300");
        assertEquals(300L, field.getSeconds());
        assertEquals("300s", field.getText());
        field.setSeconds(7200);
        assertEquals("2h", field.getText());
        field.setText("1500ms");
        assertEquals(2L, field.getSeconds());
        field.setText("garbage");
        assertEquals(2L, field.getSeconds());
    }

    @Test
    public void durationFieldCanMeanIndefinitelyWhenAllowed() {
        ComicDurationField field = new ComicDurationField(1000, true);
        field.setText("-1m");
        assertTrue(field.isIndefinite());
        assertEquals("-1", field.getText());
        assertEquals(-1L, field.getSeconds());
        field.setText("45m");
        assertFalse(field.isIndefinite());
        assertEquals("45m", field.getText());
        ComicDurationField plain = new ComicDurationField(1000);
        plain.setText("30s");
        plain.setText("-1"); // not allowed here: the value stays as it was
        assertEquals("30s", plain.getText());
    }

    @Test
    public void inlineControlsWrapInsteadOfBeingClippedOnANarrowPage() {
        ComicForm form = new ComicForm();
        JTextField[] parts = new JTextField[6];
        for (int i = 0; i < parts.length; i++) {
            parts[i] = ComicControls.textField("part " + i, 12);
        }
        form.inline(parts);
        JScrollPane page = ComicForm.page(form);
        layout(page, 400, 400);
        JTextField last = parts[parts.length - 1];
        assertTrue("the page was laid out", form.getWidth() > 0);
        assertTrue("the last control must stay inside its line", last.getX() + last.getWidth() <= last.getParent().getWidth());
        assertTrue("the line must stay inside the form", last.getParent().getWidth() <= form.getWidth());
        assertTrue("it wrapped onto a further line", last.getY() > parts[0].getY());
    }

    private static void layout(JComponent component, int width, int height) {
        component.setSize(new Dimension(width, height));
        // No peer in a headless test, so validate() is a no-op: lay the tree out top-down by hand,
        // twice, so width-dependent preferred sizes (wrapping rows) see their final width.
        layoutTree(component);
        layoutTree(component);
    }

    private static void layoutTree(java.awt.Container container) {
        container.doLayout();
        for (Component child : container.getComponents()) {
            if (child instanceof java.awt.Container) {
                layoutTree((java.awt.Container) child);
            }
        }
    }

    private static String repeat(char c, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
