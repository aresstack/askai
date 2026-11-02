package com.aresstack.askai.ui.setup;

import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import com.aresstack.comiccontrols.theme.ThemeColor;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.function.IntConsumer;

/**
 * The setup wizard's progress trail in Java2D: per step a comic circle with ink contour and number (done: green with
 * a tick, current: blue), connecting lines in between, the step's name below. Also navigation: a click on a step
 * jumps there without a check (the host decides via {@link #setOnSelect(IntConsumer)}); the tick still only shows
 * real completion. Ported unchanged in behaviour from the Enterprise AI Client.
 */
final class SetupStepTrail extends JComponent {

    private static final long serialVersionUID = 1L;
    private static final int DIAMETER = 26;
    /** Completed steps: AskAI's success green. */
    static final Color DONE = ThemeColor.of("models.statusSuccessText");
    /** Fill of open steps and glyphs on filled circles. */
    private static final Color LIGHT = ThemeColor.of("comic.fieldBg");

    private final String[] names;
    private final ComicPalette palette;
    private int current;
    private boolean[] done;
    private IntConsumer onSelect;
    private boolean navigable = true;

    SetupStepTrail(String[] names, ComicPalette palette) {
        this.names = names.clone();
        this.palette = palette;
        this.done = new boolean[names.length];
        setOpaque(false);
        getAccessibleContext().setAccessibleName("Setup progress");
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = stepAt(e.getX());
                if (navigable && onSelect != null && SwingUtilities.isLeftMouseButton(e) && index >= 0) {
                    onSelect.accept(index);
                }
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                int index = stepAt(e.getX());
                boolean active = navigable && onSelect != null && index >= 0 && index != current;
                setCursor(active ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
                setToolTipText(active ? "Go to “" + names[index] + "”" : null);
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** Wer auf Klicks reagiert (Index des Schritts); {@code null} = nur Anzeige. */
    void setOnSelect(IntConsumer callback) {
        this.onSelect = callback;
    }

    /** Ob Klicks gerade springen dürfen (nicht während einer laufenden Abfrage). */
    void setNavigable(boolean value) {
        this.navigable = value;
        if (!value) {
            setCursor(Cursor.getDefaultCursor());
        }
    }

    /** Der Schritt unter einer x-Koordinate; -1 außerhalb. */
    int stepAt(int x) {
        if (getWidth() <= 0 || x < 0 || x >= getWidth()) {
            return -1;
        }
        return Math.min(names.length - 1, x / Math.max(1, getWidth() / names.length));
    }

    /** Mitte des Kreises eines Schritts (für Tests und Klickziele). */
    int centerOf(int index) {
        int slot = getWidth() / names.length;
        return slot * index + slot / 2;
    }

    void show(int currentStep, boolean[] completed) {
        this.current = currentStep;
        this.done = completed.clone();
        getAccessibleContext().setAccessibleDescription("Step " + (currentStep + 1) + " of " + names.length
                + ": " + names[currentStep]);
        repaint();
    }

    /**
     * Ein eigener Kontext: {@link JComponent} selbst liefert keinen ({@code null}), dann scheiterte schon der Aufbau
     * des Assistenten (Beschriftung im Konstruktor) und er erschien weder beim Erststart noch aus den Einstellungen.
     */
    @Override
    public AccessibleContext getAccessibleContext() {
        if (accessibleContext == null) {
            accessibleContext = new AccessibleJComponent() {
                private static final long serialVersionUID = 1L;

                @Override
                public AccessibleRole getAccessibleRole() {
                    return AccessibleRole.PROGRESS_BAR;
                }
            };
        }
        return accessibleContext;
    }

    int current() {
        return current;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(names.length * 120, DIAMETER + 30);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int count = names.length;
            int slot = getWidth() / count;
            int top = 4;
            Font label = ResearchUiTypography.regular(11.5f);
            Font bold = ResearchUiTypography.semiBold(11.5f);
            for (int i = 0; i < count; i++) {
                int cx = slot * i + slot / 2;
                if (i < count - 1) {
                    g2.setColor(done[i] ? DONE : ResearchUiPalette.LIGHT_CONTROL_BORDER);
                    g2.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.draw(new Line2D.Float(cx + DIAMETER / 2f + 4, top + DIAMETER / 2f,
                            cx + slot - DIAMETER / 2f - 4, top + DIAMETER / 2f));
                }
                Color fill = done[i] ? DONE
                        : i == current ? ResearchUiPalette.ACCENT_BLUE : LIGHT;
                Ellipse2D circle = new Ellipse2D.Float(cx - DIAMETER / 2f, top, DIAMETER, DIAMETER);
                g2.setColor(fill);
                g2.fill(circle);
                g2.setColor(palette.getInk());
                g2.setStroke(new BasicStroke(i == current ? 2.6f : 1.8f));
                g2.draw(circle);
                if (done[i]) {
                    g2.setColor(LIGHT);
                    g2.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g2.drawPolyline(new int[] {cx - 6, cx - 1, cx + 7}, new int[] {top + 13, top + 18, top + 8}, 3);
                } else {
                    g2.setFont(bold.deriveFont(13f));
                    g2.setColor(i == current ? LIGHT : palette.getInk());
                    String number = Integer.toString(i + 1);
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(number, cx - fm.stringWidth(number) / 2,
                            top + (DIAMETER - fm.getHeight()) / 2 + fm.getAscent());
                }
                g2.setFont(i == current ? bold : label);
                g2.setColor(i == current ? palette.getInk() : ResearchUiPalette.LIGHT_CONTROL_TEXT);
                FontMetrics fm = g2.getFontMetrics();
                String name = names[i];
                g2.drawString(name, cx - fm.stringWidth(name) / 2, top + DIAMETER + 6 + fm.getAscent());
            }
        } finally {
            g2.dispose();
        }
    }
}
