package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.AskAiTheme;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * A comic text field that shows a muted placeholder while it is empty, e.g. the value an empty field stands for
 * ("autodetect" with the detected path).
 */
public class PlaceholderTextField extends ComicTextField {

    private static final long serialVersionUID = 1L;

    private String placeholder;

    public PlaceholderTextField(int columns) {
        super(columns);
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        if (getText().length() != 0 || placeholder == null || placeholder.isEmpty()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(AskAiTheme.active().placeholderText());
            g2.setFont(getFont().deriveFont(Font.PLAIN));
            FontMetrics metrics = g2.getFontMetrics();
            Insets insets = getInsets();
            int y = (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent();
            g2.drawString(placeholder, insets.left, y);
        } finally {
            g2.dispose();
        }
    }
}
