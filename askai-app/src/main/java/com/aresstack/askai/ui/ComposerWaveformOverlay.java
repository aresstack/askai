package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * The recording sampler floating over the composer editor: no background at all — only the live
 * waveform (comic dark green, newest right) as MOUNTAINS rising from a FLAT BOTTOM baseline, so
 * the typed text and the caret stay fully visible and editable. Plus the voice-activation gate:
 * ONE red level line the user drags up/down DURING the recording — everything below it is
 * ignored as background noise (faint bars), everything above counts as speech; dragged to the
 * very bottom the gate is OPEN and everything is accepted, even the faintest noise. Hit-testing
 * claims ONLY the line's grab band; every other pixel is click-through to the editor.
 */
public final class ComposerWaveformOverlay extends JComponent {

    /** Comic dark green — clearly "recording", distinct from the blue/red accent families. */
    private static final Color WAVE = ThemeColor.of("chat.waveformSpeechBar");
    private static final Color WAVE_BELOW = ThemeColor.of("chat.waveformNoiseBar");
    /** The voice-activation line is RED: the classic "below this is ignored" gate colour. */
    private static final Color GATE = ResearchUiPalette.DANGER_RED;
    private static final int BAR_WIDTH = 3;
    private static final int BAR_GAP = 3;
    private static final int PAD_V = 6;
    private static final int GRAB_BAND = 5;

    /** Live threshold changes (percent 0-100) while the user drags the line. */
    public interface ThresholdListener {
        void thresholdChanged(int percent);
    }

    private final int[] levels = new int[120]; // ring buffer of recent level samples (0-100)
    private int levelCount;
    private int levelHead;
    private boolean active;
    private int thresholdPercent = 8;
    private ThresholdListener thresholdListener;
    private boolean dragging;

    public ComposerWaveformOverlay() {
        setOpaque(false);
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                dragging = true;
                applyDrag(event.getY());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                if (dragging) {
                    applyDrag(event.getY());
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                dragging = false;
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        setToolTipText("Signal threshold — drag: everything below counts as background noise"
                + " (silence auto-stop listens above this line).");
    }

    /** Recording lifecycle: activating clears the history; deactivating hides everything. */
    public void setActive(boolean value) {
        if (this.active != value) {
            this.active = value;
            levelCount = 0;
            levelHead = 0;
            repaint();
        }
    }

    /** Append one live level sample (0-100, the level-bar scale). */
    public void pushLevel(int level) {
        levels[levelHead] = Math.max(0, Math.min(100, level));
        levelHead = (levelHead + 1) % levels.length;
        levelCount = Math.min(levelCount + 1, levels.length);
        if (active) {
            repaint();
        }
    }

    public void setThresholdPercent(int percent) {
        this.thresholdPercent = clampPercent(percent);
        repaint();
    }

    public int getThresholdPercent() {
        return thresholdPercent;
    }

    public void setThresholdListener(ThresholdListener listener) {
        this.thresholdListener = listener;
    }

    /** ONLY the threshold line's grab band is interactive — the rest belongs to the editor. */
    @Override
    public boolean contains(int x, int y) {
        return active && Math.abs(y - thresholdY()) <= GRAB_BAND;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (!active || getHeight() <= 0) {
            return; // NO background ever — only the floating bars while recording
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int usable = Math.max(1, getHeight() - 2 * PAD_V);
            int baselineY = getHeight() - PAD_V; // FLAT bottom: the mountains rise from here
            int slots = Math.max(1, (getWidth() - 8) / (BAR_WIDTH + BAR_GAP));
            int shown = Math.min(levelCount, slots);
            for (int i = 0; i < shown; i++) {
                // newest sample at the RIGHT edge, history flowing left
                int sampleIndex = (levelHead - shown + i + levels.length * 2) % levels.length;
                int level = levels[sampleIndex];
                int barHeight = Math.max(2, level * usable / 100);
                int x = getWidth() - 8 - (shown - i) * (BAR_WIDTH + BAR_GAP);
                // Peaks reaching ABOVE the gate are speech (full green); below = ignored noise.
                boolean aboveGate = level >= thresholdPercent;
                g2.setColor(withAlpha(aboveGate ? WAVE : WAVE_BELOW, aboveGate ? 205 : 70));
                g2.fillRoundRect(x, baselineY - barHeight, BAR_WIDTH, barHeight,
                        BAR_WIDTH, BAR_WIDTH);
            }
            // ONE red voice-activation line above the flat bottom: below it, input is ignored.
            int gateY = thresholdY();
            g2.setColor(withAlpha(GATE, dragging ? 235 : 165));
            g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                    1f, new float[]{5f, 4f}, 0f));
            g2.drawLine(4, gateY, getWidth() - 4, gateY);
        } finally {
            g2.dispose();
        }
    }

    /** The gate line's y, measured from the flat bottom baseline. */
    private int thresholdY() {
        int usable = Math.max(1, getHeight() - 2 * PAD_V);
        return getHeight() - PAD_V - thresholdPercent * usable / 100;
    }

    private void applyDrag(int y) {
        int usable = Math.max(1, getHeight() - 2 * PAD_V);
        int baselineY = getHeight() - PAD_V;
        int percent = clampPercent((baselineY - y) * 100 / usable);
        if (percent != thresholdPercent) {
            thresholdPercent = percent;
            repaint();
            ThresholdListener listener = thresholdListener;
            if (listener != null) {
                listener.thresholdChanged(percent);
            }
        }
    }

    private static int clampPercent(int percent) {
        return Math.max(0, Math.min(95, percent)); // 0 = gate open, everything is input
    }

    private static Color withAlpha(Color color, int alpha) {
        return ThemeColor.withAlpha(color, alpha);
    }
}
