package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.ComicPalette;

import javax.swing.JSplitPane;
import java.awt.Component;
import java.awt.Dimension;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;

/**
 * A horizontal split pane that hides the standard look-and-feel splitter behind the comic design
 * language: a quiet divider with a dark ink line and a small yellow grip pill. It OWNS the left
 * side's width policy — the left component may be collapsed to zero (divider disappears entirely)
 * or opened at a remembered width that is always clamped into {@code [minLeftWidth, maxLeftWidth]},
 * including while the user drags the divider.
 *
 * <p>The pane deliberately zeroes the left component's minimum size: without that, a collapse to
 * width 0 would fight the component's own layout minimum.</p>
 */
public class ComicSplitPane extends JSplitPane {

    /** Notified with the clamped width whenever the USER moves the divider (not on open/collapse). */
    public interface LeftWidthListener {
        void leftWidthChanged(int width);
    }


    private final int minLeftWidth;
    private final int maxLeftWidth;
    private final ComicPalette palette;
    private boolean leftCollapsed;
    private boolean adjusting;
    private int preferredLeftWidth;
    private LeftWidthListener leftWidthListener;

    public ComicSplitPane(Component left, Component right, int minLeftWidth, int maxLeftWidth) {
        this(left, right, minLeftWidth, maxLeftWidth, ComicPalette.defaultPalette());
    }

    public ComicSplitPane(Component left, Component right, int minLeftWidth, int maxLeftWidth,
                          ComicPalette palette) {
        super(HORIZONTAL_SPLIT, true, left, right);
        if (minLeftWidth <= 0 || maxLeftWidth < minLeftWidth) {
            throw new IllegalArgumentException(
                    "invalid width bounds: " + minLeftWidth + ".." + maxLeftWidth);
        }
        if (palette == null) {
            throw new IllegalArgumentException("palette must not be null");
        }
        this.minLeftWidth = minLeftWidth;
        this.maxLeftWidth = maxLeftWidth;
        this.palette = palette;
        this.preferredLeftWidth = minLeftWidth;
        left.setMinimumSize(new Dimension(0, 0));
        // The right side's own minimum must not cap how far the left side can be dragged open.
        right.setMinimumSize(new Dimension(0, 0));
        setBorder(null);
        setResizeWeight(0); // window resizes go to the right (content) side
        setOneTouchExpandable(false);
        installComicDivider();
        setDividerSize(ComicSplitPaneUI.DIVIDER_THICKNESS);
        setDividerLocation(preferredLeftWidth);
        addPropertyChangeListener(DIVIDER_LOCATION_PROPERTY, new PropertyChangeListener() {
            public void propertyChange(PropertyChangeEvent event) {
                onDividerMoved(((Integer) event.getNewValue()).intValue());
            }
        });
    }

    /** Collapse the left side completely: width 0 and NO divider — looks like a plain single pane. */
    public void collapseLeft() {
        leftCollapsed = true;
        adjusting = true;
        try {
            setDividerSize(0);
            setDividerLocation(0);
        } finally {
            adjusting = false;
        }
    }

    /** Re-open the left side at the remembered (clamped) width and bring the divider back. */
    public void openLeft() {
        leftCollapsed = false;
        adjusting = true;
        try {
            setDividerSize(ComicSplitPaneUI.DIVIDER_THICKNESS);
            setDividerLocation(clampLeftWidth(preferredLeftWidth));
        } finally {
            adjusting = false;
        }
    }

    public boolean isLeftCollapsed() {
        return leftCollapsed;
    }

    /** The width the left side opens at — updated by every user drag, clamped into the bounds. */
    public int getPreferredLeftWidth() {
        return preferredLeftWidth;
    }

    /** Set the remembered width (e.g. a persisted value); applies immediately when open. */
    public void setPreferredLeftWidth(int width) {
        preferredLeftWidth = clampLeftWidth(width);
        if (!leftCollapsed) {
            adjusting = true;
            try {
                setDividerLocation(preferredLeftWidth);
            } finally {
                adjusting = false;
            }
        }
    }

    public void setLeftWidthListener(LeftWidthListener listener) {
        this.leftWidthListener = listener;
    }

    public int getMinLeftWidth() {
        return minLeftWidth;
    }

    public int getMaxLeftWidth() {
        return maxLeftWidth;
    }

    /** Every user-driven divider move funnels through here: clamp, remember, notify. */
    private void onDividerMoved(int location) {
        if (adjusting || leftCollapsed) {
            return;
        }
        int clamped = clampLeftWidth(location);
        if (clamped != location) {
            adjusting = true;
            try {
                setDividerLocation(clamped);
            } finally {
                adjusting = false;
            }
        }
        preferredLeftWidth = clamped;
        if (leftWidthListener != null) {
            leftWidthListener.leftWidthChanged(clamped);
        }
    }

    private int clampLeftWidth(int width) {
        int upper = maxLeftWidth;
        int available = getWidth() - getDividerSize();
        if (available > minLeftWidth) {
            upper = Math.min(upper, available); // never push the divider out of the pane
        }
        return Math.max(minLeftWidth, Math.min(upper, width));
    }

    private void installComicDivider() {
        setUI(new ComicSplitPaneUI(palette)); // the shared comic divider (both orientations)
    }

    /** A theme switch (updateComponentTreeUI) keeps the comic divider with this pane's palette. */
    @Override
    public void updateUI() {
        setUI(new ComicSplitPaneUI(palette)); // palette is null during construction: the theme palette
        revalidate();
    }
}
