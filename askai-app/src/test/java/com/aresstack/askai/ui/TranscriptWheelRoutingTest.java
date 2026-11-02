package com.aresstack.askai.ui;

import org.junit.Test;

import javax.swing.DefaultBoundedRangeModel;
import javax.swing.JPanel;
import java.awt.event.MouseWheelEvent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hotfix 4.1 regression: rehoming the transcript scrollbar (POLICY NEVER + external bar) killed
 * Swing's scroll-pane wheel handling. The router must move THE shared {@code BoundedRangeModel}
 * directly — same model the external right-hand bar drags — for unit, precise-fraction and block
 * wheel events, clamped at the ends.
 */
public class TranscriptWheelRoutingTest {

    private static MouseWheelEvent wheel(int scrollType, int rotation, double precise) {
        return new MouseWheelEvent(new JPanel(), MouseWheelEvent.MOUSE_WHEEL,
                System.currentTimeMillis(), 0, 5, 5, 5, 5, 0, false,
                scrollType, 3, rotation, precise);
    }

    @Test
    public void aUnitWheelEventMovesTheSharedModel() {
        DefaultBoundedRangeModel model = new DefaultBoundedRangeModel(0, 200, 0, 2000);
        OllamaChatPanel.routeWheelToModel(
                wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, 1, 1.0), model, 18);
        assertEquals("one notch = scrollAmount × unit", 54, model.getValue());
        OllamaChatPanel.routeWheelToModel(
                wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, -1, -1.0), model, 18);
        assertEquals("scrolling back moves the SAME model, no second position", 0, model.getValue());
    }

    @Test
    public void preciseFractionalRotationStillScrolls() {
        DefaultBoundedRangeModel model = new DefaultBoundedRangeModel(100, 200, 0, 2000);
        OllamaChatPanel.routeWheelToModel(
                wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, 0, 0.01), model, 18);
        assertTrue("a tiny precise tick must still move the transcript", model.getValue() > 100);
    }

    @Test
    public void blockScrollMovesRoughlyOneViewport() {
        DefaultBoundedRangeModel model = new DefaultBoundedRangeModel(0, 300, 0, 2000);
        OllamaChatPanel.routeWheelToModel(
                wheel(MouseWheelEvent.WHEEL_BLOCK_SCROLL, 1, 1.0), model, 18);
        assertEquals("a page = extent minus one unit of overlap", 282, model.getValue());
    }

    @Test
    public void theModelClampsAtBothEnds() {
        DefaultBoundedRangeModel model = new DefaultBoundedRangeModel(1790, 200, 0, 2000);
        OllamaChatPanel.routeWheelToModel(
                wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, 5, 5.0), model, 18);
        assertEquals("never past the bottom", 1800, model.getValue());
        model.setValue(5);
        OllamaChatPanel.routeWheelToModel(
                wheel(MouseWheelEvent.WHEEL_UNIT_SCROLL, -5, -5.0), model, 18);
        assertEquals("never past the top", 0, model.getValue());
    }
}
