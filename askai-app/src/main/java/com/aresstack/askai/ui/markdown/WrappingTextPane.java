package com.aresstack.askai.ui.markdown;

import javax.swing.JTextPane;
import javax.swing.text.View;
import java.awt.Dimension;
import java.awt.Insets;

/** Keep a text pane at its natural wrapped height when placed in a vertical Swing layout. */
final class WrappingTextPane extends JTextPane {

    WrappingTextPane() {
        setEditable(false);
        setOpaque(false);
        setBorder(null);
        setFocusable(true);
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public Dimension getPreferredSize() {
        // Measure at the width the layout has assigned us (getWidth); before the first real layout fall
        // back to the parent's width. The height is derived from the text View at that width without
        // resizing this component, so a single normal layout pass already yields the correct height.
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        if (width <= 0) {
            return super.getPreferredSize();
        }
        return new Dimension(Math.max(1, width), heightForWidth(width));
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /**
     * Compute the wrapped height for an exact component width by laying out the text root View at that
     * width. This mutates the View's cached span, not this component's bounds, so it is safe to call from
     * {@code getPreferredSize()} and from a host that measures ahead of layout (see {@link MarkdownHeights}).
     */
    int heightForWidth(int width) {
        View root = getUI().getRootView(this);
        if (root == null) {
            return super.getPreferredSize().height;
        }
        Insets insets = getInsets();
        float textWidth = Math.max(1f, width - insets.left - insets.right);
        root.setSize(textWidth, Integer.MAX_VALUE);
        int textHeight = (int) Math.ceil(root.getPreferredSpan(View.Y_AXIS));
        return textHeight + insets.top + insets.bottom;
    }
}
