package com.aresstack.askai.ui.markdown;

import javax.swing.BoxLayout;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Deterministically computes the height a rendered Markdown subtree needs at a given width, without
 * mutating component bounds or depending on a prior layout pass. It mirrors the width rules of the only
 * layouts the Markdown renderer uses — a vertical {@link BoxLayout} stack and {@link BorderLayout} rows
 * (list markers, quote gutters) — and delegates wrapping text to {@link WrappingTextPane#heightForWidth}.
 * Anything else (code blocks, tables, Mermaid images, separators) contributes its own width-independent
 * preferred height.
 *
 * <p>This is what lets a bubble/row report the correct height on the very first measurement, so no
 * "self-healing" second layout (triggered by a later message, scroll or resize) is required.
 */
final class MarkdownHeights {

    private MarkdownHeights() {
    }

    static int forWidth(Component component, int width) {
        if (component == null || !component.isVisible()) {
            return 0;
        }
        if (width <= 0) {
            return component.getPreferredSize().height;
        }
        if (component instanceof WidthAwareHeight) {
            return ((WidthAwareHeight) component).preferredHeightForWidth(width);
        }
        if (component instanceof WrappingTextPane) {
            return ((WrappingTextPane) component).heightForWidth(width);
        }
        if (component instanceof Container) {
            Container container = (Container) component;
            LayoutManager layout = container.getLayout();
            Insets insets = container.getInsets();
            int inner = Math.max(0, width - insets.left - insets.right);
            if (layout instanceof BoxLayout) {
                // The Markdown renderer only ever stacks vertically (verticalPanel()).
                int sum = 0;
                for (Component child : container.getComponents()) {
                    sum += forWidth(child, inner);
                }
                return sum + insets.top + insets.bottom;
            }
            if (layout instanceof BorderLayout) {
                return borderLayoutHeight(container, (BorderLayout) layout, inner, insets);
            }
        }
        return component.getPreferredSize().height;
    }

    private static int borderLayoutHeight(Container container, BorderLayout layout, int inner, Insets insets) {
        int hgap = layout.getHgap();
        int vgap = layout.getVgap();
        Component north = layout.getLayoutComponent(BorderLayout.NORTH);
        Component south = layout.getLayoutComponent(BorderLayout.SOUTH);
        Component west = layout.getLayoutComponent(BorderLayout.WEST);
        Component east = layout.getLayoutComponent(BorderLayout.EAST);
        Component center = layout.getLayoutComponent(BorderLayout.CENTER);

        int westWidth = west != null ? west.getPreferredSize().width : 0;
        int eastWidth = east != null ? east.getPreferredSize().width : 0;
        int centerWidth = Math.max(0, inner - westWidth - eastWidth
                - (west != null ? hgap : 0) - (east != null ? hgap : 0));

        int middle = 0;
        if (center != null) {
            middle = Math.max(middle, forWidth(center, centerWidth));
        }
        if (west != null) {
            middle = Math.max(middle, west.getPreferredSize().height);
        }
        if (east != null) {
            middle = Math.max(middle, east.getPreferredSize().height);
        }

        int height = middle;
        int gaps = 0;
        if (north != null) {
            height += forWidth(north, inner);
            gaps++;
        }
        if (south != null) {
            height += forWidth(south, inner);
            gaps++;
        }
        return height + gaps * vgap + insets.top + insets.bottom;
    }
}
