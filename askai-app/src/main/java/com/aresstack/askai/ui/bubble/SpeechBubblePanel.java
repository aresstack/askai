package com.aresstack.askai.ui.bubble;

import com.aresstack.comiccontrols.theme.ThemeColor;
import com.aresstack.comiccontrols.control.ComicControls;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/**
 * Render one selectable, streamable chat message inside a painted speech bubble.
 *
 * <p>Keep the text as a real Swing text component so copy and selection continue to work. Paint
 * only the bubble chrome with {@link Graphics2D}.</p>
 *
 * <p>Implements {@link com.aresstack.askai.ui.markdown.WidthAwareHeight} so the transcript
 * rows can ask for the exact wrapped height at the final bubble width — before that, long
 * unbroken texts were measured unwrapped once and rendered as a single clipped line.</p>
 */
public final class SpeechBubblePanel extends JPanel
        implements com.aresstack.askai.ui.markdown.WidthAwareHeight, WidthBoundedBubble {

    private static final int ARC = 22;
    private static final int TAIL_WIDTH = 16;
    private static final int HORIZONTAL_PADDING = 15;
    private static final int VERTICAL_PADDING = 11;
    /**
     * Only the fallback for a bubble used OUTSIDE a transcript row (showcases, standalone rendering). Inside
     * a row the row supplies the limit through {@link WidthBoundedBubble}, because only it knows how much
     * width the chat has. A fixed pixel cap must never be what decides the width: at 580 every message
     * stayed in a narrow column, and any other constant would just move the same problem to a wider window.
     */
    private static final int DEFAULT_MAXIMUM_WIDTH = 1200;
    private static final int MINIMUM_WIDTH = 104;

    private final BubbleSide side;
    private final Color bubbleColor;
    private final Color textColor;
    private final JLabel headerLabel;
    private final JPanel headerRow;
    private JLabel timestampLabel; // small stacked date/time next to the name, or null
    private final JTextArea textArea;
    private int maximumBubbleWidth;

    public SpeechBubblePanel(BubbleSide side,
                             Color bubbleColor,
                             Color textColor,
                             String header,
                             String text) {
        if (side == null) {
            throw new IllegalArgumentException("side must not be null");
        }
        if (bubbleColor == null) {
            throw new IllegalArgumentException("bubbleColor must not be null");
        }
        if (textColor == null) {
            throw new IllegalArgumentException("textColor must not be null");
        }
        this.side = side;
        this.bubbleColor = bubbleColor;
        this.textColor = textColor;
        this.maximumBubbleWidth = DEFAULT_MAXIMUM_WIDTH;
        this.headerLabel = createHeaderLabel(header);
        this.headerRow = new JPanel();
        this.textArea = createTextArea(text);
        buildUi();
    }

    public BubbleSide getSide() {
        return side;
    }

    public String getText() {
        return textArea.getText();
    }

    public void setText(String text) {
        textArea.setText(normalize(text));
        refreshLayout();
    }

    public void appendText(String delta) {
        if (delta == null || delta.length() == 0) {
            return;
        }
        textArea.append(delta);
        refreshLayout();
    }

    public void setHeader(String header) {
        headerLabel.setText(normalize(header));
        headerLabel.setVisible(headerLabel.getText().length() > 0);
        headerRow.setVisible(headerLabel.isVisible());
        refreshLayout();
    }

    /**
     * Colors the header label (the sender name), used for per-participant colors in Partying
     * mode.  {@code null} restores the default muted text color.
     */
    public void setHeaderColor(Color color) {
        headerLabel.setForeground(color != null ? color : withAlpha(textColor, 220));
        if (timestampLabel != null) {
            timestampLabel.setForeground(timestampColor());
        }
        repaint();
    }

    /** The timestamp always follows the name color (first line), just dimmed. */
    private Color timestampColor() {
        Color header = headerLabel.getForeground();
        return withAlpha(header != null ? header : textColor, 165);
    }

    /**
     * Shows the message creation time next to the sender name: date over time, squeezed into two
     * half-size lines no taller than the name itself.
     */
    public void setHeaderTimestamp(long epochMillis) {
        // ONE timestamp format for every mode (see BubbleTimestamps) — Partying introduced it,
        // Questing reuses it verbatim.
        String full = BubbleTimestamps.tooltip(epochMillis);
        if (timestampLabel == null) {
            timestampLabel = new JLabel();
            Font base = headerLabel.getFont();
            timestampLabel.setFont(base.deriveFont(Font.PLAIN, Math.max(6f, base.getSize2D() * 0.5f)));
            timestampLabel.setAlignmentY(BOTTOM_ALIGNMENT);
            // A minimum gap, then glue so the time/date block is pushed to the bubble's right edge.
            headerRow.add(javax.swing.Box.createHorizontalStrut(6));
            headerRow.add(javax.swing.Box.createHorizontalGlue());
            headerRow.add(timestampLabel);
        }
        timestampLabel.setForeground(timestampColor());
        timestampLabel.setText(BubbleTimestamps.stackedHtml(epochMillis));
        // A shared, readable tooltip on the whole header so hovering the name or the stamp shows it.
        headerLabel.setToolTipText(full);
        timestampLabel.setToolTipText(full);
        setToolTipText(full);
        // Cap the two stacked lines to the username's height; pad the width so the year is never
        // clipped (HTML labels tend to under-measure their preferred width by a pixel or two).
        Dimension pref = timestampLabel.getPreferredSize();
        Dimension cap = new Dimension(pref.width + 4, headerLabel.getPreferredSize().height);
        timestampLabel.setPreferredSize(cap);
        timestampLabel.setMaximumSize(cap);
        refreshLayout();
    }

    /** Header block size: the name plus the optional timestamp, no taller than the name. */
    private Dimension headerBlockSize() {
        Dimension label = headerLabel.getPreferredSize();
        if (timestampLabel != null) {
            return new Dimension(label.width + 6 + timestampLabel.getPreferredSize().width + 4, label.height);
        }
        return label;
    }

    public void setMaximumBubbleWidth(int maximumBubbleWidth) {
        if (maximumBubbleWidth < MINIMUM_WIDTH) {
            throw new IllegalArgumentException("maximumBubbleWidth must be at least " + MINIMUM_WIDTH);
        }
        this.maximumBubbleWidth = maximumBubbleWidth;
        refreshLayout();
    }

    /**
     * The width this bubble wants within a limit the CALLER supplies — the transcript row's share of the
     * chat width. A short message still asks for its natural width; only a long one grows into the limit.
     */
    @Override
    public int preferredWidthWithin(int limit) {
        int allowed = Math.max(MINIMUM_WIDTH, limit);
        int contentMaximumWidth = allowed - TAIL_WIDTH - (HORIZONTAL_PADDING * 2);
        int naturalWidth = calculateNaturalTextWidth();
        int contentWidth = Math.max(72, Math.min(contentMaximumWidth, naturalWidth));
        int headerWidth = headerLabel.isVisible() ? headerBlockSize().width : 0;
        int width = Math.max(contentWidth, headerWidth) + (HORIZONTAL_PADDING * 2) + TAIL_WIDTH;
        return Math.max(MINIMUM_WIDTH, Math.min(allowed, width));
    }

    @Override
    public Dimension getPreferredSize() {
        int contentMaximumWidth = maximumBubbleWidth
                - TAIL_WIDTH
                - (HORIZONTAL_PADDING * 2);
        int naturalWidth = calculateNaturalTextWidth();
        int contentWidth = Math.max(72, Math.min(contentMaximumWidth, naturalWidth));

        textArea.setSize(new Dimension(contentWidth, Short.MAX_VALUE));
        Dimension textSize = textArea.getPreferredSize();
        Dimension headerSize = headerLabel.isVisible()
                ? headerBlockSize()
                : new Dimension(0, 0);

        int width = Math.max(textSize.width, headerSize.width)
                + (HORIZONTAL_PADDING * 2)
                + TAIL_WIDTH;
        width = Math.max(MINIMUM_WIDTH, Math.min(maximumBubbleWidth, width));

        int height = VERTICAL_PADDING * 2 + textSize.height;
        if (headerLabel.isVisible()) {
            height += headerSize.height + 3;
        }
        return new Dimension(width, Math.max(48, height));
    }

    /**
     * Deterministic height for a fixed bubble width: paddings + optional header + the text wrapped
     * at exactly the inner width.  Uses the larger of the text view's measurement and a
     * font-metrics greedy-wrap estimate, so a stale unwrapped view measurement can never produce
     * a one-line bubble for a long text.
     */
    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int innerWidth = Math.max(24, width - insets.left - insets.right);
        textArea.setSize(new Dimension(innerWidth, Short.MAX_VALUE));
        int viewHeight = textArea.getPreferredSize().height;
        int metricsHeight = estimateWrappedTextHeight(innerWidth);
        int height = insets.top + insets.bottom + Math.max(viewHeight, metricsHeight);
        if (headerLabel.isVisible()) {
            height += headerBlockSize().height + 3;
        }
        return Math.max(48, height);
    }

    /** Greedy word-wrap line count from font metrics — independent of the Swing view state. */
    private int estimateWrappedTextHeight(int innerWidth) {
        FontMetrics metrics = textArea.getFontMetrics(textArea.getFont());
        int lineHeight = metrics.getHeight();
        int lines = 0;
        for (String paragraph : textArea.getText().split("\n", -1)) {
            lines += countWrappedLines(paragraph, metrics, innerWidth);
        }
        return Math.max(1, lines) * lineHeight;
    }

    private static int countWrappedLines(String paragraph, FontMetrics metrics, int width) {
        if (paragraph.isEmpty()) {
            return 1;
        }
        int lines = 1;
        int currentWidth = 0;
        int spaceWidth = metrics.charWidth(' ');
        for (String word : paragraph.split(" ")) {
            int wordWidth = metrics.stringWidth(word);
            if (currentWidth > 0 && currentWidth + spaceWidth + wordWidth > width) {
                lines++;
                currentWidth = 0;
            }
            if (wordWidth > width) {
                // Overlong words wrap mid-word across additional lines.
                lines += wordWidth / Math.max(1, width);
                currentWidth = wordWidth % Math.max(1, width);
            } else {
                currentWidth += (currentWidth > 0 ? spaceWidth : 0) + wordWidth;
            }
        }
        return lines;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            applyQualityHints(copy);
            copy.setComposite(AlphaComposite.SrcOver);
            copy.setColor(bubbleColor);
            copy.fill(createBubbleShape());
        } finally {
            copy.dispose();
        }
        super.paintComponent(graphics);
    }

    private void buildUi() {
        setOpaque(false);
        setLayout(new BorderLayout(0, 3));
        setBorder(createContentBorder());
        headerRow.setLayout(new javax.swing.BoxLayout(headerRow, javax.swing.BoxLayout.X_AXIS));
        headerRow.setOpaque(false);
        headerLabel.setAlignmentY(BOTTOM_ALIGNMENT);
        headerRow.add(headerLabel);
        headerRow.setVisible(headerLabel.isVisible());
        add(headerRow, BorderLayout.NORTH);
        add(textArea, BorderLayout.CENTER);
    }

    private JLabel createHeaderLabel(String header) {
        JLabel label = new JLabel(normalize(header));
        Font baseFont = UIManager.getFont("Label.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        label.setFont(baseFont.deriveFont(Font.BOLD, Math.max(10f, baseFont.getSize2D() - 1f)));
        label.setForeground(withAlpha(textColor, 220));
        label.setOpaque(false);
        label.setVisible(label.getText().length() > 0);
        return label;
    }

    private JTextArea createTextArea(String text) {
        JTextArea area = ComicControls.textArea(normalize(text));
        Font baseFont = UIManager.getFont("TextArea.font");
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        }
        area.setFont(baseFont.deriveFont(Font.PLAIN, Math.max(13f, baseFont.getSize2D())));
        area.setForeground(textColor);
        area.setCaretColor(textColor);
        area.setSelectionColor(withAlpha(textColor, 90));
        area.setSelectedTextColor(textColor);
        area.setOpaque(false);
        area.setEditable(false);
        area.setFocusable(true);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setBorder(BorderFactory.createEmptyBorder());
        return area;
    }

    private EmptyBorder createContentBorder() {
        int left = HORIZONTAL_PADDING + (side.pointsLeft() ? TAIL_WIDTH : 0);
        int right = HORIZONTAL_PADDING + (side.pointsRight() ? TAIL_WIDTH : 0);
        return new EmptyBorder(VERTICAL_PADDING, left, VERTICAL_PADDING, right);
    }

    private java.awt.Shape createBubbleShape() {
        Insets insets = getInsets();
        int bodyX = side.pointsLeft() ? TAIL_WIDTH : 0;
        int bodyWidth = Math.max(1, getWidth() - TAIL_WIDTH);
        int bodyHeight = Math.max(1, getHeight());
        RoundRectangle2D body = new RoundRectangle2D.Float(
                bodyX,
                0,
                bodyWidth,
                bodyHeight,
                ARC,
                ARC);

        Path2D tail = new Path2D.Float();
        int centerY = Math.max(insets.top + 13, getHeight() - 22);
        if (side.pointsRight()) {
            int baseX = getWidth() - TAIL_WIDTH - 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(getWidth() - 1, centerY);
            tail.lineTo(baseX, centerY + 8);
        } else {
            int baseX = TAIL_WIDTH + 2;
            tail.moveTo(baseX, centerY - 8);
            tail.lineTo(1, centerY);
            tail.lineTo(baseX, centerY + 8);
        }
        tail.closePath();

        java.awt.geom.Area shape = new java.awt.geom.Area(body);
        shape.add(new java.awt.geom.Area(tail));
        return shape;
    }

    private int calculateNaturalTextWidth() {
        FontMetrics textMetrics = textArea.getFontMetrics(textArea.getFont());
        int maximum = 72;
        String[] lines = textArea.getText().split("\\n", -1);
        for (int index = 0; index < lines.length; index++) {
            maximum = Math.max(maximum, textMetrics.stringWidth(lines[index]) + 8);
        }
        if (headerLabel.isVisible()) {
            FontMetrics headerMetrics = headerLabel.getFontMetrics(headerLabel.getFont());
            int headerWidth = headerMetrics.stringWidth(headerLabel.getText())
                    + (timestampLabel != null ? 6 + timestampLabel.getPreferredSize().width : 0);
            maximum = Math.max(maximum, headerWidth + 8);
        }
        return maximum;
    }

    private void refreshLayout() {
        revalidate();
        repaint();
    }

    private static void applyQualityHints(Graphics2D graphics) {
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    /** {@code color} with {@code alpha}, derived live (follows theme switches even when stored). */
    private static Color withAlpha(Color color, int alpha) {
        return ThemeColor.withAlpha(color, alpha);
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }
}
