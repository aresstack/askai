package com.aresstack.askai.ui.bubble;

import javax.swing.JComponent;
import java.awt.Container;

/**
 * THE geometry of a transcript row: how wide the row is, how wide the bubble inside it may be, how tall it
 * is at that width, and where it sits.
 * <p>
 * It exists because this was computed twice — once for speech bubbles, once for the streamed assistant
 * Markdown bubble — with two sets of margins, two ratios and two fallbacks. Two copies of the same geometry
 * are two chances to drift apart, and they did: the same conversation could be laid out differently
 * depending on which row type held it.
 */
final class BubbleRowGeometry {

    /** The gap towards the row's own side. Moderate on purpose: the conversation should still read as one. */
    static final int HORIZONTAL_MARGIN = 14;
    static final int VERTICAL_GAP = 5;

    /**
     * How much of the row a bubble may fill. The remainder is the conversational margin towards the
     * opposite side — enough to tell left from right, not so much that long messages sit in a column.
     */
    private static final double MAX_WIDTH_RATIO = 0.92d;

    private static final int MIN_BUBBLE_WIDTH = 100;

    /** Only for a row that is asked to measure itself before it has ever had a width. */
    private static final int UNKNOWN_ROW_WIDTH = 720;

    private BubbleRowGeometry() {
    }

    /**
     * The width the row will ACTUALLY be laid out at — its own whenever it has one. Measuring against the
     * parent instead reports a height for a different width than the layout then uses, and the last lines
     * of a long message fall out of the bubble.
     */
    static int rowWidth(Container row) {
        if (row.getWidth() > 0) {
            return row.getWidth();
        }
        return row.getParent() != null && row.getParent().getWidth() > 0
                ? row.getParent().getWidth() : UNKNOWN_ROW_WIDTH;
    }

    /** The most a bubble may take in a row of this width. */
    static int maxBubbleWidth(int rowWidth) {
        return Math.max(120, (int) Math.floor((rowWidth - (HORIZONTAL_MARGIN * 2)) * MAX_WIDTH_RATIO));
    }

    /**
     * How wide this bubble wants to be within the row.
     * <ul>
     * <li>A {@link WidthBoundedBubble} is asked — a short message then keeps its natural width.</li>
     * <li>A width-aware (wrapping) bubble FILLS the allowance: its own preferred width collapses to
     *     something narrow, which is why it must not be asked.</li>
     * <li>Anything else (a thinking bubble, an action row) keeps its natural size, capped.</li>
     * </ul>
     */
    static int bubbleWidth(JComponent bubble, int rowWidth) {
        int allowed = maxBubbleWidth(rowWidth);
        if (bubble instanceof WidthBoundedBubble) {
            return Math.max(MIN_BUBBLE_WIDTH,
                    Math.min(allowed, ((WidthBoundedBubble) bubble).preferredWidthWithin(allowed)));
        }
        if (bubble instanceof com.aresstack.askai.ui.markdown.WidthAwareHeight) {
            return allowed;
        }
        return Math.max(MIN_BUBBLE_WIDTH, Math.min(allowed, bubble.getPreferredSize().width));
    }

    /** The height at EXACTLY this width — deterministic, never size-then-hope-for-a-second-pass. */
    static int bubbleHeight(JComponent bubble, int width) {
        if (bubble instanceof com.aresstack.askai.ui.markdown.WidthAwareHeight) {
            return ((com.aresstack.askai.ui.markdown.WidthAwareHeight) bubble)
                    .preferredHeightForWidth(width);
        }
        bubble.setSize(new java.awt.Dimension(width, Short.MAX_VALUE));
        return bubble.getPreferredSize().height;
    }

    /** Where the bubble starts: anchored at its participant's side. */
    static int bubbleX(BubbleSide side, int rowWidth, int bubbleWidth) {
        return side == BubbleSide.LEFT
                ? HORIZONTAL_MARGIN
                : Math.max(HORIZONTAL_MARGIN, rowWidth - HORIZONTAL_MARGIN - bubbleWidth);
    }
}
