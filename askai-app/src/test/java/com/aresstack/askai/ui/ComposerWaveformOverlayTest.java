package com.aresstack.askai.ui;

import org.junit.Test;

import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The recording sampler's contract: everything except the red gate line's grab band is
 * click-through to the editor below, and dragging the line reports the percent the auto-stop
 * logic uses — measured from the FLAT BOTTOM baseline (mountains, not mirrored bars); the very
 * bottom (0) means "accept everything".
 */
public class ComposerWaveformOverlayTest {

    private ComposerWaveformOverlay overlay(int thresholdPercent) {
        ComposerWaveformOverlay overlay = new ComposerWaveformOverlay();
        overlay.setSize(400, 112); // usable height 100 → pixel and percent scales coincide
        overlay.setThresholdPercent(thresholdPercent);
        overlay.setActive(true);
        return overlay;
    }

    private static int gateY(ComposerWaveformOverlay overlay, int percent) {
        int usable = overlay.getHeight() - 12;
        return overlay.getHeight() - 6 - percent * usable / 100;
    }

    @Test
    public void onlyTheGateBandIsInteractive() {
        ComposerWaveformOverlay overlay = overlay(40);
        int lineY = gateY(overlay, 40);
        assertTrue("the line's grab band claims the click", overlay.contains(200, lineY));
        assertTrue(overlay.contains(200, lineY + 4));
        assertFalse("the editor keeps every other pixel", overlay.contains(200, lineY + 20));
        assertFalse("top edge stays click-through", overlay.contains(200, 1));
        overlay.setActive(false);
        assertFalse("inactive: fully click-through", overlay.contains(200, lineY));
    }

    @Test
    public void draggingTheLineReportsThePercentFromTheBottomBaseline() {
        ComposerWaveformOverlay overlay = overlay(40);
        final List<Integer> reported = new ArrayList<Integer>();
        overlay.setThresholdListener(new ComposerWaveformOverlay.ThresholdListener() {
            public void thresholdChanged(int percent) {
                reported.add(percent);
            }
        });
        overlay.dispatchEvent(new MouseEvent(overlay, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, 200, gateY(overlay, 20),
                1, false, MouseEvent.BUTTON1));
        assertEquals(1, reported.size());
        assertEquals("usable height 100 → the pixel maps exactly to 20 percent",
                20, (int) reported.get(0));
        assertEquals(20, overlay.getThresholdPercent());
    }

    @Test
    public void theBottomOpensTheGateCompletely() {
        ComposerWaveformOverlay overlay = overlay(40);
        overlay.dispatchEvent(new MouseEvent(overlay, MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, 200,
                overlay.getHeight() - 6, 1, false, MouseEvent.BUTTON1));
        assertEquals("line at the flat bottom = accept EVERYTHING, even the faintest noise",
                0, overlay.getThresholdPercent());
        overlay.dispatchEvent(new MouseEvent(overlay, MouseEvent.MOUSE_DRAGGED,
                System.currentTimeMillis(), MouseEvent.BUTTON1_DOWN_MASK, 200, -500,
                1, false, MouseEvent.BUTTON1));
        assertTrue("dragging far above clamps below 100", overlay.getThresholdPercent() <= 95);
    }
}
