package com.aresstack.askai.ui.bubble;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.askai.ui.markdown.MarkdownMessageView;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * An assistant speech bubble whose body is a native {@link MarkdownMessageView} (headings, lists, code,
 * tables, links, Mermaid). Unlike {@link SpeechBubblePanel} it does not drive its own width from a text
 * area: it lays out inside the transcript's vertical box so the Markdown content wraps at the real
 * available width. Only the bubble chrome is painted here.
 *
 * <p>The tail direction follows the {@link BubbleSide} contract — it always points toward the transcript
 * center (a left-side bubble points right, a right-side bubble points left) — instead of being hardcoded.
 * The reserved padding and the body position mirror {@link SpeechBubblePanel} so both bubble styles line
 * up identically.</p>
 */
final class AssistantMarkdownBubble extends JPanel
        implements com.aresstack.askai.ui.markdown.WidthAwareHeight {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;
    private static final int BODY_GAP = 3;

    private final BubbleSide side;
    private final Color bubbleColor;
    private final MarkdownMessageView body;
    private final JLabel headerLabel;
    private final boolean headerShown;

    AssistantMarkdownBubble(BubbleSide side, BubblePalette palette, String header, MarkdownMessageView body) {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        this.side = side;
        this.bubbleColor = palette.getAssistantBackground();
        this.body = body;
        setOpaque(false);
        setLayout(new BorderLayout(0, BODY_GAP));
        // Reserve the tail's width on the side that carries the tail (the center-facing inner edge).
        int left = HORIZONTAL_PADDING + (side.pointsLeft() ? TAIL_WIDTH : 0);
        int right = HORIZONTAL_PADDING + (side.pointsRight() ? TAIL_WIDTH : 0);
        setBorder(new EmptyBorder(VERTICAL_PADDING, left, VERTICAL_PADDING, right));
        this.headerLabel = createHeaderLabel(header, palette.getAssistantForeground());
        this.headerShown = headerLabel.getText().length() > 0;
        this.headerForeground = palette.getAssistantForeground();
        if (headerShown) {
            headerRow.setOpaque(false);
            // WEST/EAST instead of a box with glue: the stamp is right-aligned however wide the
            // transcript really lays this bubble out.
            headerRow.setLayout(new BorderLayout(6, 0));
            headerRow.add(headerLabel, BorderLayout.WEST);
            add(headerRow, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
    }

    /** Header + (optional) the shared stacked time/date stamp — same format as every other bubble. */
    private final JPanel headerRow = new JPanel();
    private final Color headerForeground;
    private JLabel timestampLabel;

    /** Stamp this message with its creation time (shared format/tooltip; no-op without a header). */
    void setHeaderTimestamp(long epochMillis) {
        if (!headerShown) {
            return;
        }
        String full = BubbleTimestamps.tooltip(epochMillis);
        if (timestampLabel == null) {
            timestampLabel = new JLabel();
            Font base = headerLabel.getFont();
            timestampLabel.setFont(base.deriveFont(Font.PLAIN, Math.max(6f, base.getSize2D() * 0.5f)));
            headerRow.add(timestampLabel, BorderLayout.EAST);
        }
        timestampLabel.setForeground(withAlpha(headerForeground, 190));
        timestampLabel.setText(BubbleTimestamps.stackedHtml(epochMillis));
        timestampLabel.setToolTipText(full);
        headerLabel.setToolTipText(full);
        revalidate();
        repaint();
    }

    /** Correct height for a fixed bubble width: chrome + optional header + the Markdown body at that width. */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(1, width - insets.left - insets.right);
        int height = insets.top + insets.bottom + body.preferredHeightForWidth(innerWidth);
        if (headerShown) {
            height += Math.max(headerLabel.getPreferredSize().height,
                    headerRow.getPreferredSize().height) + BODY_GAP;
        }
        return height;
    }

    BubbleSide getSide() {
        return side;
    }

    private static JLabel createHeaderLabel(String header, Color foreground) {
        JLabel label = new JLabel(header == null ? "" : header);
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(baseFont.deriveFont(Font.BOLD, Math.max(10f, baseFont.getSize2D() - 1f)));
        label.setForeground(withAlpha(foreground, 220));
        label.setOpaque(false);
        return label;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            copy.setComposite(AlphaComposite.SrcOver);
            copy.setColor(bubbleColor);
            copy.fill(createBubbleShape());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private java.awt.Shape createBubbleShape() {
        int bodyX = side.pointsLeft() ? TAIL_WIDTH : 0;
        int bodyWidth = Math.max(1, getWidth() - TAIL_WIDTH);
        int bodyHeight = Math.max(1, getHeight());
        RoundRectangle2D body = new RoundRectangle2D.Float(bodyX, 0, bodyWidth, bodyHeight, ARC, ARC);

        java.awt.geom.Area shape = new java.awt.geom.Area(body);
        shape.add(new java.awt.geom.Area(buildTail(side, getWidth(), getHeight())));
        return shape;
    }

    /**
     * Build the tail triangle for the given side, pointing toward the transcript center: a left-side bubble
     * ({@link BubbleSide#pointsRight()}) has its tip on the right inner edge, a right-side bubble on the
     * left. Package-private and static so the geometry is unit-testable without painting.
     */
    static Path2D buildTail(BubbleSide side, int width, int height) {
        int centerY = Math.max(VERTICAL_PADDING + 13, height - 22);
        Path2D tail = new Path2D.Float();
        if (side.pointsRight()) {
            int baseX = width - TAIL_WIDTH - 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(width - 1, centerY);
            tail.lineTo(baseX, centerY + 8);
        } else {
            int baseX = TAIL_WIDTH + 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(1, centerY);
            tail.lineTo(baseX, centerY + 8);
        }
        tail.closePath();
        return tail;
    }

    /** {@code color} with {@code alpha}, derived live (follows theme switches even when stored). */
    private static Color withAlpha(Color color, int alpha) {
        return ThemeColor.withAlpha(color, alpha);
    }
}
