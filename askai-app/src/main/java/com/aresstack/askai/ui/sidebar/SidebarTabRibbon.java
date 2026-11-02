package com.aresstack.askai.ui.sidebar;

import com.aresstack.askai.ui.ChatComposerPanel;

import javax.swing.JButton;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * The unfolding tab ribbon next to the hamburger: one composer-style (Java2D) entry per sidebar
 * pane. It does not slide in — it UNFOLDS: an animated reveal grows its visible width to the right,
 * uncovering the entries in place. When the entries do not fit the available width, a ‹ and/or ›
 * appears exactly on the side where more entries follow; hovering an arrow scrolls the ribbon (a
 * click steps too). The ribbon itself is dumb about open/close policy — the workspace opens it on
 * hamburger hover, keeps it while hovered, collapses it otherwise and can lock it (long press).
 */
public final class SidebarTabRibbon extends JPanel {

    /** Fired when the user picks a tab entry. */
    public interface Listener {
        void tabSelected(String title);
    }

    private static final int ARROW_WIDTH = 18;
    private static final int RIBBON_HEIGHT = 28;
    private static final float ANIM_STEP = 0.18f;
    private static final int ANIM_INTERVAL_MS = 15;
    private static final int SCROLL_STEP_PX = 10;
    private static final int SCROLL_INTERVAL_MS = 30;

    private final JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
    private final JPanel viewport = new JPanel(null);
    private final JButton scrollLeft = ChatComposerPanel.createRibbonArrowButton(true);
    private final JButton scrollRight = ChatComposerPanel.createRibbonArrowButton(false);
    private final Timer animator;
    private final Timer leftScroller;
    private final Timer rightScroller;

    private float progress; // 0 = folded away, 1 = fully unfolded
    private boolean expanding;
    private int scrollOffset;
    private Listener listener;

    public SidebarTabRibbon() {
        super(null); // fully manual layout (reveal + scroll)
        setOpaque(false);
        content.setOpaque(false);
        viewport.setOpaque(false);
        viewport.add(content);
        add(scrollLeft);
        add(viewport);
        add(scrollRight);
        scrollLeft.setVisible(false);
        scrollRight.setVisible(false);

        animator = new Timer(ANIM_INTERVAL_MS, event -> stepAnimation());
        leftScroller = new Timer(SCROLL_INTERVAL_MS, event -> scrollBy(-SCROLL_STEP_PX));
        rightScroller = new Timer(SCROLL_INTERVAL_MS, event -> scrollBy(SCROLL_STEP_PX));
        wireArrow(scrollLeft, leftScroller, -SCROLL_STEP_PX);
        wireArrow(scrollRight, rightScroller, SCROLL_STEP_PX);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    private List<String> shownTitles = java.util.Collections.emptyList();

    /** The entries currently shown, in order. */
    public List<String> titles() {
        return java.util.Collections.unmodifiableList(shownTitles);
    }

    /** Rebuild the entries (the active one is emphasized); keeps the current unfold state. */
    public void setTabs(List<String> titles, String activeTitle) {
        shownTitles = new java.util.ArrayList<String>(titles);
        content.removeAll();
        for (final String title : titles) {
            JButton entry = ChatComposerPanel.createRibbonEntryButton(title, title.equals(activeTitle));
            entry.addActionListener(event -> {
                if (listener != null) {
                    listener.tabSelected(title);
                }
            });
            content.add(entry);
        }
        revalidate();
        repaint();
    }

    /** Unfold to the right (animated reveal). */
    public void open() {
        expanding = true;
        animator.start();
    }

    /** Fold back (animated). */
    public void close() {
        expanding = false;
        animator.start();
    }

    public boolean isOpen() {
        return progress > 0f;
    }

    private void stepAnimation() {
        progress += expanding ? ANIM_STEP : -ANIM_STEP;
        if (progress <= 0f) {
            progress = 0f;
            animator.stop();
        } else if (progress >= 1f) {
            progress = 1f;
            animator.stop();
        }
        revalidate();
        repaint();
    }

    /** Jump to the animation's end state — for tests, which must not wait on timers. */
    void finishAnimationForTest() {
        animator.stop();
        progress = expanding ? 1f : 0f;
        revalidate();
        repaint();
    }

    private void wireArrow(JButton arrow, final Timer scroller, final int clickStep) {
        arrow.addActionListener(event -> scrollBy(clickStep));
        arrow.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                scroller.start(); // hovering the arrow is enough to scroll
            }

            @Override
            public void mouseExited(MouseEvent event) {
                scroller.stop();
            }
        });
    }

    private void scrollBy(int delta) {
        scrollOffset += delta;
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------ manual layout

    @Override
    public Dimension getPreferredSize() {
        // The ribbon asks for exactly its unfolded share (progress-scaled): folded away it takes
        // NOTHING, so a centered top-bar control can use the space — and gets pushed right/squeezed
        // while the ribbon unfolds. (It used to claim the whole top bar, which made a centered
        // sibling impossible.)
        int fullWidth = content.getPreferredSize().width + 2 * ARROW_WIDTH;
        return new Dimension(Math.round(progress * fullWidth), RIBBON_HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize(); // never stretch beyond the unfolded share
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(0, RIBBON_HEIGHT);
    }

    @Override
    public void doLayout() {
        int width = getWidth();
        int height = getHeight();
        int contentWidth = content.getPreferredSize().width;
        int visibleWidth = Math.round(progress * Math.min(contentWidth + 2 * ARROW_WIDTH, width));

        // An arrow appears only on a side where more entries follow (per spec).
        boolean overflow = contentWidth > visibleWidth;
        boolean showLeft = overflow && scrollOffset > 0;
        int viewportWidth = Math.max(0, visibleWidth - (showLeft ? ARROW_WIDTH : 0));
        // Clamp against the arrow-less viewport first: the far end is reached when the content is
        // flush with the right edge, and only before that do more entries follow. Deciding the right
        // arrow before clamping made it flicker at the far end: the arrow narrows the viewport by
        // ARROW_WIDTH, which moved the clamp and re-enabled the arrow on every other scroll step.
        int maxOffset = Math.max(0, contentWidth - viewportWidth);
        if (scrollOffset > maxOffset) {
            scrollOffset = maxOffset;
        }
        if (scrollOffset < 0) {
            scrollOffset = 0;
        }
        boolean showRight = overflow && scrollOffset < maxOffset;
        if (showRight) {
            viewportWidth = Math.max(0, viewportWidth - ARROW_WIDTH);
        }

        int x = 0;
        scrollLeft.setVisible(showLeft && visibleWidth > 0);
        if (scrollLeft.isVisible()) {
            scrollLeft.setBounds(x, 0, ARROW_WIDTH, height);
            x += ARROW_WIDTH;
        }
        viewport.setBounds(x, 0, viewportWidth, height);
        content.setBounds(-scrollOffset, 0, contentWidth, height);
        x += viewportWidth;
        scrollRight.setVisible(showRight && visibleWidth > 0);
        if (scrollRight.isVisible()) {
            scrollRight.setBounds(x, 0, ARROW_WIDTH, height);
        }
        if (!scrollLeft.isVisible()) {
            leftScroller.stop();
        }
        if (!scrollRight.isVisible()) {
            rightScroller.stop();
        }
    }

    // ------------------------------------------------------------------ test accessors

    JPanel contentForTest() {
        return content;
    }

    JButton scrollLeftForTest() {
        return scrollLeft;
    }

    JButton scrollRightForTest() {
        return scrollRight;
    }

    int scrollOffsetForTest() {
        return scrollOffset;
    }
}
