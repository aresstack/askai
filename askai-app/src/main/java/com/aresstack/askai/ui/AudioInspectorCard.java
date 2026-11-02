package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.Timer;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * A rounded, block-width settings card that lives INSIDE the pipeline canvas, directly under the selected
 * block: it rolls down when a block is selected and rolls up when the selection clears, with a caret at
 * the top pointing to the block. It scrolls together with the canvas (no scroll bar between the pipeline
 * and the card). During the animation the content keeps its full height and the card clips it, so the
 * reveal grows top-down instead of squashing the controls. All state changes run on the EDT.
 */
final class AudioInspectorCard extends JPanel {

    private static final int CARET_HEIGHT = 9;
    private static final int CARET_HALF_WIDTH = 9;
    private static final int PAD_X = 10;
    private static final int PAD_TOP = 8;
    private static final int PAD_BOTTOM = 12;
    private static final int ARC = 14;
    private static final double STEP = 0.16d;

    private final int cardWidth;
    private final JComponent content;
    private final Timer timer;
    private double fraction;       // 0 = fully collapsed, 1 = fully expanded
    private boolean expanding;
    private int caretX = -1;       // caret tip x in card coordinates, or -1 to hide it

    AudioInspectorCard(JComponent content, int cardWidth) {
        this.cardWidth = cardWidth;
        this.content = content;
        setOpaque(false);
        setLayout(null); // the content is positioned manually so the card can clip it while animating
        content.setOpaque(false);
        content.setVisible(false);
        add(content);
        timer = new Timer(15, new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                tick();
            }
        });
    }

    int cardWidth() {
        return cardWidth;
    }

    /** @return whether the settings content is currently shown (revealing or open). */
    boolean isContentShown() {
        return content.isVisible();
    }

    /** Points the caret at {@code x} (card coordinates); pass a negative value to hide it. */
    void setCaretX(int x) {
        caretX = x;
        repaint();
    }

    /** Rolls the card down (expanded) or up (collapsed); animation runs to completion either way. */
    void setExpanded(boolean expanded) {
        if (expanded == expanding && (expanded ? fraction >= 1.0d : fraction <= 0.0d)) {
            return;
        }
        expanding = expanded;
        if (expanded) {
            content.setVisible(true);
        }
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    private void tick() {
        fraction += expanding ? STEP : -STEP;
        if (fraction >= 1.0d) {
            fraction = 1.0d;
            timer.stop();
        } else if (fraction <= 0.0d) {
            fraction = 0.0d;
            timer.stop();
            content.setVisible(false);
        }
        revalidate();
        if (getParent() != null) {
            getParent().revalidate();
        }
        repaint();
    }

    private int contentHeight() {
        int inner = Math.max(0, cardWidth - 2 * PAD_X);
        content.setSize(inner, 1);
        content.doLayout();
        return content.getPreferredSize().height;
    }

    @Override
    public Dimension getPreferredSize() {
        int full = CARET_HEIGHT + PAD_TOP + contentHeight() + PAD_BOTTOM;
        return new Dimension(cardWidth, (int) Math.round(full * fraction));
    }

    @Override
    public void doLayout() {
        int inner = Math.max(0, cardWidth - 2 * PAD_X);
        content.setBounds(PAD_X, CARET_HEIGHT + PAD_TOP, inner, contentHeight());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getHeight() <= CARET_HEIGHT) {
            return;
        }
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color fill = uiColor("Panel.background", ThemeColor.of("comic.buttonPlate"));
            Color border = uiColor("Component.borderColor", uiColor("controlShadow", ThemeColor.of("audio.cardBorder")));
            int top = CARET_HEIGHT;
            int width = getWidth();
            int height = getHeight() - CARET_HEIGHT;

            g.setColor(fill);
            g.fillRoundRect(0, top, width - 1, height - 1, ARC, ARC);
            g.setColor(border);
            g.drawRoundRect(0, top, width - 1, height - 1, ARC, ARC);

            if (caretX >= 0) {
                int tipX = Math.max(CARET_HALF_WIDTH + 1, Math.min(width - CARET_HALF_WIDTH - 1, caretX));
                int[] xs = {tipX - CARET_HALF_WIDTH, tipX, tipX + CARET_HALF_WIDTH};
                int[] ys = {top + 1, 0, top + 1};
                g.setColor(fill);
                g.fillPolygon(xs, ys, 3);
                g.setColor(border);
                g.drawLine(xs[0], ys[0], xs[1], ys[1]);
                g.drawLine(xs[1], ys[1], xs[2], ys[2]);
            }
        } finally {
            g.dispose();
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
}
