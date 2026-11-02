package com.aresstack.askai.ui;

import javax.swing.DefaultListSelectionModel;
import javax.swing.JList;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;

/**
 * A multi-select {@link JList} that toggles individual rows on a plain click — no {@code Ctrl}/{@code Shift}
 * required. A single click on an unselected row selects it, a single click on a selected row deselects it,
 * and every other selected row is left untouched. Clicks outside any row never clear the selection.
 *
 * <p>The behaviour comes from two pieces: a toggling {@link ListSelectionModel} (the list UI calls
 * {@code setSelectionInterval} on a plain click, which we turn into add/remove) and a guard in
 * {@link #processMouseEvent(MouseEvent)} that swallows presses landing on empty space so the UI never
 * collapses the selection.</p>
 */
public final class ToggleSelectionList<E> extends JList<E> {

    public ToggleSelectionList(ListModel<E> model) {
        super(model);
        setSelectionModel(new ToggleSelectionModel());
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
    }

    @Override
    protected void processMouseEvent(MouseEvent event) {
        // Ignore a press that is not on an actual row so empty-area clicks keep the current selection.
        if (event.getID() == MouseEvent.MOUSE_PRESSED && !isPointOnRow(event.getPoint())) {
            return;
        }
        super.processMouseEvent(event);
    }

    private boolean isPointOnRow(Point point) {
        int index = locationToIndex(point);
        if (index < 0) {
            return false;
        }
        Rectangle bounds = getCellBounds(index, index);
        return bounds != null && bounds.contains(point);
    }

    /**
     * Turns the list UI's plain-click {@code setSelectionInterval} into a per-row toggle. {@code gestureToggled}
     * makes sure a single click toggles exactly one row even though a click-drag emits several interval calls;
     * it is reset when the adjusting gesture ends. Programmatic selection uses {@code addSelectionInterval}
     * (via {@code JList.setSelectedIndices}) and is therefore unaffected.
     */
    private static final class ToggleSelectionModel extends DefaultListSelectionModel {

        private boolean gestureToggled;

        ToggleSelectionModel() {
            setSelectionMode(MULTIPLE_INTERVAL_SELECTION);
        }

        @Override
        public void setSelectionInterval(int index0, int index1) {
            if (gestureToggled) {
                return;
            }
            if (isSelectedIndex(index0)) {
                super.removeSelectionInterval(index0, index1);
            } else {
                super.addSelectionInterval(index0, index1);
            }
            gestureToggled = true;
        }

        @Override
        public void setValueIsAdjusting(boolean isAdjusting) {
            super.setValueIsAdjusting(isAdjusting);
            if (!isAdjusting) {
                gestureToggled = false;
            }
        }
    }
}
