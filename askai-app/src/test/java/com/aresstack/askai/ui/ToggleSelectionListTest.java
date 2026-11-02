package com.aresstack.askai.ui;

import org.junit.Test;

import javax.swing.DefaultListModel;
import javax.swing.ListSelectionModel;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The toggle list turns a plain click (which the list UI delivers as {@code setValueIsAdjusting} +
 * {@code setSelectionInterval}) into a per-row toggle that leaves other selected rows untouched, while
 * programmatic {@code setSelectedIndices} still replaces the selection wholesale.
 */
public class ToggleSelectionListTest {

    private final ToggleSelectionList<String> list = newList("a", "b", "c", "d");

    @Test
    public void plainClickSelectsThenDeselectsTheSameRow() {
        click(1);
        assertTrue(list.isSelectedIndex(1));
        click(1);
        assertFalse("second click on the same row deselects it", list.isSelectedIndex(1));
        assertArrayEquals(new int[]{}, list.getSelectedIndices());
    }

    @Test
    public void clickingAnotherRowKeepsExistingSelection() {
        click(0);
        click(2);
        click(3);
        assertArrayEquals(new int[]{0, 2, 3}, list.getSelectedIndices());
        // Deselecting the middle one leaves the others.
        click(2);
        assertArrayEquals(new int[]{0, 3}, list.getSelectedIndices());
    }

    @Test
    public void aClickDragOnlyTogglesTheRowOnceWithinTheGesture() {
        ListSelectionModel model = list.getSelectionModel();
        model.setValueIsAdjusting(true);
        model.setSelectionInterval(1, 1); // press on row 1 -> selected
        model.setSelectionInterval(2, 2); // drag extends -> ignored within the gesture
        model.setValueIsAdjusting(false);
        assertArrayEquals(new int[]{1}, list.getSelectedIndices());
    }

    @Test
    public void programmaticSetSelectedIndicesStillReplacesSelection() {
        click(0);
        list.setSelectedIndices(new int[]{2, 3});
        assertArrayEquals(new int[]{2, 3}, list.getSelectedIndices());
    }

    @Test
    public void usesMultipleIntervalSelectionMode() {
        assertEquals(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION, list.getSelectionMode());
    }

    /** Simulate what BasicListUI does for a plain click on a row: an adjusting setSelectionInterval gesture. */
    private void click(int index) {
        ListSelectionModel model = list.getSelectionModel();
        model.setValueIsAdjusting(true);
        model.setSelectionInterval(index, index);
        model.setValueIsAdjusting(false);
    }

    private static ToggleSelectionList<String> newList(String... items) {
        DefaultListModel<String> model = new DefaultListModel<String>();
        for (String item : items) model.addElement(item);
        return new ToggleSelectionList<String>(model);
    }
}
