package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Small, programmatically painted icons for a model's {@link ModelCapability capabilities}, shared by
 * both the HuggingFace and Ollama searches so they speak one visual language. Painted with Java2D
 * (no image assets). {@link #forCapabilities(Set)} returns a strip with one slot per present
 * capability, in the enum's canonical order, so a row of icons reads consistently.
 */
final class CapabilityIcons {

    private static final int SLOT_SIZE = 16;
    private static final int GAP = 2;

    private static final Color TEXT_COLOR = ThemeColor.of("models.capabilityText");
    private static final Color VISION_COLOR = ThemeColor.of("models.capabilityVision");
    private static final Color AUDIO_COLOR = ThemeColor.of("models.capabilityAudio");
    private static final Color TOOLS_COLOR = ThemeColor.of("models.capabilityTools");
    private static final Color THINKING_COLOR = ThemeColor.of("models.capabilityThinking");
    private static final Color EMBEDDING_COLOR = ThemeColor.of("models.capabilityEmbedding");
    private static final Color IMAGE_COLOR = ThemeColor.of("models.capabilityImage");
    private static final Color INSERT_COLOR = ThemeColor.of("models.capabilityInsert");
    private static final Color CLOUD_COLOR = ThemeColor.of("models.capabilityCloud");

    private CapabilityIcons() {
    }

    /** @return an icon strip for the given capabilities (one slot each, in enum order). */
    static Icon forCapabilities(Set<ModelCapability> capabilities) {
        return new CompositeIcon(ordered(capabilities));
    }

    private static List<ModelCapability> ordered(Set<ModelCapability> capabilities) {
        List<ModelCapability> ordered = new ArrayList<ModelCapability>();
        for (ModelCapability capability : ModelCapability.values()) {
            if (capabilities != null && capabilities.contains(capability)) {
                ordered.add(capability);
            }
        }
        return ordered;
    }

    /**
     * @param offsetX the x offset within the icon strip returned by {@link #forCapabilities(Set)}
     * @return the capability whose slot contains {@code offsetX}, or {@code null} in a gap / out of range
     */
    static ModelCapability capabilityAt(Set<ModelCapability> capabilities, int offsetX) {
        if (offsetX < 0) {
            return null;
        }
        List<ModelCapability> ordered = ordered(capabilities);
        int slot = offsetX / (SLOT_SIZE + GAP);
        if (slot < 0 || slot >= ordered.size()) {
            return null;
        }
        int within = offsetX - slot * (SLOT_SIZE + GAP);
        return within < SLOT_SIZE ? ordered.get(slot) : null;
    }

    private static final class CompositeIcon implements Icon {

        private final List<ModelCapability> capabilities;

        CompositeIcon(List<ModelCapability> capabilities) {
            this.capabilities = capabilities;
        }

        public int getIconWidth() {
            if (capabilities.isEmpty()) {
                return 0;
            }
            return capabilities.size() * SLOT_SIZE + (capabilities.size() - 1) * GAP;
        }

        public int getIconHeight() {
            return SLOT_SIZE;
        }

        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int slotX = x;
                for (int i = 0; i < capabilities.size(); i++) {
                    paint(g, capabilities.get(i), slotX, y);
                    slotX += SLOT_SIZE + GAP;
                }
            } finally {
                g.dispose();
            }
        }

        private void paint(Graphics2D g, ModelCapability capability, int x, int y) {
            switch (capability) {
                case TEXT: paintText(g, x, y); break;
                case VISION: paintVision(g, x, y); break;
                case AUDIO: paintAudio(g, x, y); break;
                case IMAGE: paintImage(g, x, y); break;
                case TOOLS: paintTools(g, x, y); break;
                case THINKING: paintThinking(g, x, y); break;
                case INSERT: paintInsert(g, x, y); break;
                case EMBEDDING: paintEmbedding(g, x, y); break;
                case CLOUD: paintCloud(g, x, y); break;
                default: break;
            }
        }

        /** Text input: three text lines, the last one shorter. */
        private void paintText(Graphics2D g, int x, int y) {
            g.setColor(TEXT_COLOR);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 2, y + 4, x + 13, y + 4);
            g.drawLine(x + 2, y + 8, x + 13, y + 8);
            g.drawLine(x + 2, y + 12, x + 9, y + 12);
        }

        /** Audio: a speaker with two sound waves. */
        private void paintAudio(Graphics2D g, int x, int y) {
            g.setColor(AUDIO_COLOR);
            g.fillRect(x + 1, y + 6, 3, 5);
            int[] xs = {x + 4, x + 8, x + 8, x + 4};
            int[] ys = {y + 6, y + 2, y + 15, y + 11};
            g.fillPolygon(xs, ys, 4);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(x + 8, y + 4, 5, 9, -55, 110);
            g.drawArc(x + 9, y + 1, 8, 15, -55, 110);
        }

        /** Vision: an eye with a pupil. */
        private void paintVision(Graphics2D g, int x, int y) {
            g.setColor(VISION_COLOR);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(x + 1, y + 2, 14, 12, 25, 130);
            g.drawArc(x + 1, y + 2, 14, 12, 205, 130);
            g.fillOval(x + 6, y + 6, 4, 4);
        }

        /** Tools: a wrench. */
        private void paintTools(Graphics2D g, int x, int y) {
            g.setColor(TOOLS_COLOR);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Handle from lower-left to upper-right.
            g.drawLine(x + 4, y + 12, x + 11, y + 5);
            // Two open jaws at the head (upper-right).
            g.drawArc(x + 8, y + 1, 7, 7, -30, 210);
        }

        /** Thinking: a lightbulb with rays. */
        private void paintThinking(Graphics2D g, int x, int y) {
            g.setColor(THINKING_COLOR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawOval(x + 5, y + 2, 6, 6);
            // Base.
            g.drawLine(x + 6, y + 9, x + 10, y + 9);
            g.drawLine(x + 7, y + 11, x + 9, y + 11);
            // Rays.
            g.drawLine(x + 8, y, x + 8, y + 1);
            g.drawLine(x + 2, y + 5, x + 3, y + 5);
            g.drawLine(x + 13, y + 5, x + 14, y + 5);
        }

        /** Embedding: three vector dots with a small trailing line (a vector arrow feel). */
        private void paintEmbedding(Graphics2D g, int x, int y) {
            g.setColor(EMBEDDING_COLOR);
            g.fillOval(x + 2, y + 10, 3, 3);
            g.fillOval(x + 7, y + 6, 3, 3);
            g.fillOval(x + 12, y + 2, 3, 3);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 3, y + 11, x + 13, y + 3);
        }

        /** Image generation: a picture frame with a small sun and a mountain (image output). */
        private void paintImage(Graphics2D g, int x, int y) {
            g.setColor(IMAGE_COLOR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawRoundRect(x + 2, y + 3, 11, 10, 3, 3);
            g.fillOval(x + 4, y + 5, 2, 2);
            int[] xs = {x + 4, x + 7, x + 11};
            int[] ys = {y + 11, y + 8, y + 11};
            g.drawPolyline(xs, ys, 3);
        }

        /** Insert / fill-in-the-middle: two brackets with a caret between them. */
        private void paintInsert(Graphics2D g, int x, int y) {
            g.setColor(INSERT_COLOR);
            g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(x + 4, y + 3, x + 2, y + 3);
            g.drawLine(x + 2, y + 3, x + 2, y + 13);
            g.drawLine(x + 2, y + 13, x + 4, y + 13);
            g.drawLine(x + 11, y + 3, x + 13, y + 3);
            g.drawLine(x + 13, y + 3, x + 13, y + 13);
            g.drawLine(x + 13, y + 13, x + 11, y + 13);
            g.fillRect(x + 7, y + 5, 2, 6);
        }

        /** Cloud: a rounded cloud outline. */
        private void paintCloud(Graphics2D g, int x, int y) {
            g.setColor(CLOUD_COLOR);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(x + 2, y + 6, 6, 6, 40, 200);
            g.drawArc(x + 5, y + 3, 7, 7, 20, 200);
            g.drawArc(x + 9, y + 6, 5, 6, -40, 180);
            g.drawLine(x + 4, y + 12, x + 12, y + 12);
        }
    }
}
