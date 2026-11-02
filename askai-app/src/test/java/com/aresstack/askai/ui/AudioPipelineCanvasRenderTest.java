package com.aresstack.askai.ui;

import com.aresstack.audio.pipeline.AudioProcessingProfiles;

import org.junit.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * The Java2D pipeline canvas must render into an offscreen image without throwing, so a headless CI run
 * exercises the drawing code (block boxes, arrows, selection marker) that the real editor uses.
 */
public class AudioPipelineCanvasRenderTest {

    @Test
    public void rendersTheDefaultProfileHeadlessWithoutException() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        canvas.setBlocks(AudioProcessingProfiles.defaultSpeech().getBlocks());
        canvas.setSelectedIndex(2);
        paintOffscreen(canvas, 1200, 200);
    }

    @Test
    public void rendersAnEmptyPipelineHeadlessWithoutException() {
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        canvas.setBlocks(Collections.<com.aresstack.audio.profile.AudioBlockDefinition>emptyList());
        paintOffscreen(canvas, 400, 200);
    }

    /**
     * Regression for the maximized-window ghosting: an opaque canvas must clear its ENTIRE area on every
     * repaint. Pre-fill the image with a conspicuous color, paint before and after a selection change, and
     * assert no pre-fill pixel survives — i.e. super.paintComponent cleared the full background both times.
     */
    @Test
    public void opaqueCanvasFullyOverpaintsItsBackgroundAcrossSelectionChange() {
        int width = 1200;
        int height = 200;
        AudioPipelineCanvas canvas = new AudioPipelineCanvas();
        canvas.setBlocks(AudioProcessingProfiles.defaultSpeech().getBlocks());
        canvas.setSize(width, height);

        canvas.setSelectedIndex(0);
        assertEquals("no pre-fill pixel may survive the first paint", 0,
                countColor(paintOverFill(canvas, width, height, Color.MAGENTA), Color.MAGENTA));

        // A selection change triggers the repaint that previously left double-buffer ghosting behind.
        canvas.setSelectedIndex(4);
        assertEquals("no pre-fill pixel may survive after a selection change", 0,
                countColor(paintOverFill(canvas, width, height, Color.MAGENTA), Color.MAGENTA));
    }

    /** @return a fresh image pre-filled with {@code fill}, then fully painted by the canvas over it. */
    private static BufferedImage paintOverFill(AudioPipelineCanvas canvas, int width, int height, Color fill) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(fill);
            graphics.fillRect(0, 0, width, height);
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static int countColor(BufferedImage image, Color color) {
        int target = color.getRGB() & 0xFFFFFF;
        int count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) == target) {
                    count++;
                }
            }
        }
        return count;
    }

    private static void paintOffscreen(AudioPipelineCanvas canvas, int width, int height) {
        canvas.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            canvas.paint(graphics);
        } finally {
            graphics.dispose();
        }
    }
}
