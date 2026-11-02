package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.theme.ThemeColor;

import java.awt.BasicStroke;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Line2D;

import javax.swing.Icon;

/**
 * A refresh glyph: two circular arrows chasing each other, painted with Java2D (no asset). Shared by the
 * Chat and Batch panels so both refresh controls look identical.
 */
public final class RefreshIcon implements Icon {

    private final int size;

    public RefreshIcon(int size) {
        this.size = size;
    }

    public int getIconWidth() {
        return size;
    }

    public int getIconHeight() {
        return size;
    }

    public void paintIcon(Component component, Graphics graphics, int x, int y) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(component.isEnabled() ? ThemeColor.of("app.iconGlyph")
                    : ThemeColor.of("app.iconGlyphDisabled"));
            float stroke = Math.max(1.6f, size / 9f);
            g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double pad = stroke + 1;
            double diameter = size - 2 * pad;
            double cx = x + size / 2.0;
            double cy = y + size / 2.0;
            double radius = diameter / 2.0;
            g.draw(new Arc2D.Double(x + pad, y + pad, diameter, diameter, 30, 140, Arc2D.OPEN));
            g.draw(new Arc2D.Double(x + pad, y + pad, diameter, diameter, 210, 140, Arc2D.OPEN));
            drawArrowHead(g, cx, cy, radius, 170);
            drawArrowHead(g, cx, cy, radius, 350);
        } finally {
            g.dispose();
        }
    }

    private void drawArrowHead(Graphics2D g, double cx, double cy, double radius, double angleDeg) {
        double a = Math.toRadians(angleDeg);
        double tipX = cx + radius * Math.cos(a);
        double tipY = cy - radius * Math.sin(a);
        double travel = a + Math.PI / 2.0;
        double length = Math.max(3.0, radius * 0.75);
        for (int side = -1; side <= 1; side += 2) {
            double barb = travel + side * Math.toRadians(150);
            double bx = tipX + length * Math.cos(barb);
            double by = tipY - length * Math.sin(barb);
            g.draw(new Line2D.Double(tipX, tipY, bx, by));
        }
    }
}
