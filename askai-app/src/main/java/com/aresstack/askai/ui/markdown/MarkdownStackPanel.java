package com.aresstack.askai.ui.markdown;

import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;

/**
 * A vertical stack for rendered Markdown blocks that measures each child at the width it is actually given.
 *
 * <p>It replaces a {@code BoxLayout.Y_AXIS} panel specifically to avoid BoxLayout's cached child size
 * requirements: BoxLayout measures a wrapping paragraph once (often at width 0, i.e. a single line) and
 * reuses that stale height, so the paragraph stays clipped until a later invalidation. This panel instead
 * computes every child's height from the real container width in {@link #doLayout()} and
 * {@link #preferredHeightForWidth(int)} via {@link MarkdownHeights}, so the very first layout is correct
 * and no self-healing second pass (a new message, a scroll, a resize) is needed.
 */
final class MarkdownStackPanel extends JPanel implements WidthAwareHeight {

    MarkdownStackPanel() {
        setOpaque(false);
        setLayout(null); // children are stacked manually at the real width in doLayout()
    }

    @Override
    public int preferredHeightForWidth(int width) {
        Insets insets = getInsets();
        int inner = Math.max(0, width - insets.left - insets.right);
        int height = insets.top + insets.bottom;
        for (Component child : getComponents()) {
            if (child.isVisible()) {
                height += MarkdownHeights.forWidth(child, inner);
            }
        }
        return height;
    }

    @Override
    public void doLayout() {
        Insets insets = getInsets();
        int inner = Math.max(0, getWidth() - insets.left - insets.right);
        int y = insets.top;
        for (Component child : getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            int height = MarkdownHeights.forWidth(child, inner);
            child.setBounds(insets.left, y, inner, height);
            y += height;
        }
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth();
        if (width <= 0 && getParent() != null) {
            width = getParent().getWidth();
        }
        if (width <= 0) {
            int widest = 1;
            for (Component child : getComponents()) {
                widest = Math.max(widest, child.getPreferredSize().width);
            }
            width = widest;
        }
        return new Dimension(width, preferredHeightForWidth(width));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, getPreferredSize().height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }
}
