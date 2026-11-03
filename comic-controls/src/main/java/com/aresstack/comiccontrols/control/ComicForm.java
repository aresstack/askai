package com.aresstack.comiccontrols.control;

import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.text.JTextComponent;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/**
 * THE settings form grid: one label column, one field column, compact uniform row spacing. Every
 * settings page (application pages, chat/party tabs, plugin pages) builds its rows through this class,
 * so labels line up, fields start at the same x and no page invents its own spacing.
 *
 * <ul>
 *   <li>{@link #row(String, JComponent)} — label left, field right. Text fields stretch to the column's
 *       width; combos, spinners and buttons keep their natural width (see {@link #stretches(JComponent)}).</li>
 *   <li>{@link #row(String, JComponent, JComponent...)} — the field plus trailing controls (buttons, a unit
 *       label) on the same line.</li>
 *   <li>{@link #check(JCheckBox)} — a switch spanning both columns (the box starts at the label column).</li>
 *   <li>{@link #inline(JComponent...)} — a sentence of controls ("Stop after [2] seconds").</li>
 *   <li>{@link #area(String, JComponent)} — a multi-line editor with its label ABOVE it, full width.</li>
 *   <li>{@link #note(String)} — a small, wrapping hint; it never widens the page.</li>
 *   <li>{@link #actions(JComponent...)} — a wrapping button row.</li>
 * </ul>
 *
 * <p>Pages are assembled with {@link #page(JComponent...)}: a vertical stack of sections that tracks the
 * viewport WIDTH (no horizontal scroll bar, ever) but never its height (so a short page is not stretched
 * and rows never drift apart), inside a borderless scroll pane.</p>
 */
public class ComicForm extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Horizontal gap between the label and the field column. */
    public static final int LABEL_GAP = 12;
    /** Vertical gap between two rows. */
    public static final int ROW_GAP = 6;
    /** Gap between controls placed on one line. */
    public static final int INLINE_GAP = 6;
    /** Gap between two sections of a page. */
    public static final int SECTION_GAP = 10;
    /** The outer page margin. */
    public static final int PAGE_MARGIN = 8;
    /** The smallest width a stretching field may shrink to before the page would need to scroll. */
    private static final int MIN_FIELD_WIDTH = 80;

    /** Below this width of the field column the labels move above the fields. */
    private static final int MIN_FIELD_COLUMN = 160;

    private final List<JLabel> labels = new ArrayList<JLabel>();
    /** {label, field cell} of every two-column row, for the narrow-window layout. */
    private final List<JComponent[]> pairs = new ArrayList<JComponent[]>();
    private boolean stacked;
    private int nextRow;

    /** An untitled form (no border); use {@link #titled(String)} for a comic group frame. */
    public ComicForm() {
        super(new GridBagLayout());
        setOpaque(false);
    }

    /** A form framed by the comic titled border. */
    public static ComicForm titled(String title) {
        ComicForm form = new ComicForm();
        form.setBorder(BorderFactory.createCompoundBorder(ComicControls.titledBorder(title),
                BorderFactory.createEmptyBorder(2, 6, 4, 6)));
        return form;
    }

    // ------------------------------------------------------------------ rows

    /** Label left, one field right. */
    public ComicForm row(String label, JComponent field) {
        return row(label, field, new JComponent[0]);
    }

    /** Label left, the field plus trailing controls (buttons, unit label, …) on the same line. */
    public ComicForm row(String label, JComponent field, JComponent... trailing) {
        boolean stretch = stretches(field);
        JComponent cell = field;
        if (trailing != null && trailing.length > 0) {
            JPanel line = new JPanel(new GridBagLayout());
            line.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.gridy = 0;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(0, 0, 0, INLINE_GAP);
            c.weightx = stretch ? 1 : 0;
            c.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
            line.add(limitShrink(field), c);
            c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            for (int i = 0; i < trailing.length; i++) {
                c.insets = new Insets(0, 0, 0, i == trailing.length - 1 ? 0 : INLINE_GAP);
                line.add(trailing[i], c);
            }
            if (!stretch) {
                // Keep the line left-aligned: the glue takes the rest of the column.
                c.weightx = 1;
                c.fill = GridBagConstraints.HORIZONTAL;
                c.insets = new Insets(0, 0, 0, 0);
                line.add(javax.swing.Box.createHorizontalGlue(), c);
            }
            cell = line;
            stretch = true; // the line always spans the column; its own layout decides what stretches
        }
        JLabel caption = addLabel(label, cell);
        GridBagConstraints c = fieldConstraints(stretch);
        add(stretch ? limitShrink(cell) : cell, c);
        pairs.add(new JComponent[] {caption, cell});
        nextRow++;
        return this;
    }

    /** A switch spanning both columns; its box sits at the label column's left edge. */
    public ComicForm check(JCheckBox box) {
        return spanning(box, false);
    }

    /** Controls as one sentence ("Delete history older than [30] days"), spanning both columns; wraps when narrow. */
    public ComicForm inline(JComponent... parts) {
        JPanel line = new JPanel(new WrapRowLayout(java.awt.FlowLayout.LEFT, INLINE_GAP, 2));
        line.setOpaque(false);
        for (JComponent part : parts) {
            line.add(part);
        }
        return spanning(line, true);
    }

    /** A multi-line editor (usually a scroll pane around a text area) with its label ABOVE, full width. */
    public ComicForm area(String label, JComponent editor) {
        if (label != null && label.length() > 0) {
            JLabel caption = new JLabel(label);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = 2 * (nextRow++);
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(nextRow == 1 ? 0 : ROW_GAP, 0, 2, 0);
            add(caption, c);
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 2 * (nextRow++);
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, ROW_GAP, 0);
        add(limitShrink(editor), c);
        return this;
    }

    /** A small muted hint that wraps at the page width — it never forces a horizontal scroll bar. */
    public ComicForm note(String text) {
        return spanning(hint(text), true);
    }

    /** A row of buttons that wraps onto further lines when the page is narrow. */
    public ComicForm actions(JComponent... buttons) {
        JPanel bar = new JPanel(new WrapRowLayout(java.awt.FlowLayout.LEFT, INLINE_GAP, 0));
        bar.setOpaque(false);
        for (JComponent button : buttons) {
            bar.add(button);
        }
        return spanning(bar, true);
    }

    /** Any component across both columns, stretched to the form's width. */
    public ComicForm component(JComponent component) {
        return spanning(component, true);
    }

    private ComicForm spanning(JComponent component, boolean stretch) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 2 * (nextRow++);
        c.gridwidth = 2;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.insets = new Insets(c.gridy == 0 ? 0 : ROW_GAP, 0, 0, 0);
        add(stretch ? limitShrink(component) : component, c);
        return this;
    }

    private JLabel addLabel(String text, JComponent field) {
        JLabel label = new JLabel(text == null ? "" : text);
        if (field != null) {
            label.setLabelFor(field);
        }
        labels.add(label);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 2 * nextRow;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(nextRow == 0 ? 0 : ROW_GAP, 0, 0, LABEL_GAP);
        add(label, c);
        return label;
    }

    // ------------------------------------------------------------------ narrow windows

    @Override
    public void doLayout() {
        boolean narrow = tooNarrowForTwoColumns();
        if (narrow != stacked) {
            stacked = narrow;
            applyColumns();
            revalidate(); // our preferred height changed: the page must give us the new height
        }
        super.doLayout();
    }

    /** Whether label column + gap + the widest field line's minimum no longer fit the current width. */
    private boolean tooNarrowForTwoColumns() {
        if (pairs.isEmpty() || getWidth() <= 0) {
            return false;
        }
        Insets insets = getInsets();
        int available = getWidth() - insets.left - insets.right;
        int labelColumn = 0;
        int fieldColumn = MIN_FIELD_COLUMN;
        for (JComponent[] pair : pairs) {
            labelColumn = Math.max(labelColumn, pair[0].getPreferredSize().width);
            fieldColumn = Math.max(fieldColumn, pair[1].getMinimumSize().width);
        }
        return available < labelColumn + LABEL_GAP + fieldColumn;
    }

    /**
     * Two columns (label left, field right) while they fit; in a window too narrow for both, each label
     * moves above its field instead of the fields being squeezed out of the window.
     */
    private void applyColumns() {
        GridBagLayout grid = (GridBagLayout) getLayout();
        for (JComponent[] pair : pairs) {
            GridBagConstraints label = grid.getConstraints(pair[0]);
            GridBagConstraints field = grid.getConstraints(pair[1]);
            int row = label.gridy;
            label.gridwidth = stacked ? 2 : 1;
            field.gridx = stacked ? 0 : 1;
            field.gridy = stacked ? row + 1 : row;
            field.gridwidth = stacked ? 2 : 1;
            field.insets = new Insets(stacked ? 2 : label.insets.top, 0, 0, 0);
            grid.setConstraints(pair[0], label);
            grid.setConstraints(pair[1], field);
        }
    }

    /** Whether the labels currently sit above their fields (narrow window). */
    boolean isStacked() {
        return stacked;
    }

    private GridBagConstraints fieldConstraints(boolean stretch) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = 2 * nextRow;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.insets = new Insets(nextRow == 0 ? 0 : ROW_GAP, 0, 0, 0);
        return c;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Whether a field takes the whole column: single-line text editors (URLs, paths, prompts) and
     * anything already laid out as a line; choice controls keep their natural width.
     */
    public static boolean stretches(JComponent field) {
        if (field instanceof JComboBox || field instanceof JSpinner || field instanceof JCheckBox
                || field instanceof ComicDurationField
                || field instanceof javax.swing.AbstractButton || field instanceof JLabel
                || field instanceof JSlider) {
            return false;
        }
        return field instanceof JTextComponent || field instanceof JScrollPane || field instanceof JPanel;
    }

    /**
     * Lets a stretching field shrink below its preferred width (a text field's minimum is its text
     * width, which would otherwise overflow the page) — the page never scrolls sideways.
     */
    private static JComponent limitShrink(JComponent field) {
        if (field instanceof JTextComponent || field instanceof JComboBox) {
            Dimension pref = field.getPreferredSize();
            field.setMinimumSize(new Dimension(Math.min(MIN_FIELD_WIDTH, pref.width), pref.height));
        }
        return field;
    }

    /** A small, muted, wrapping text for hints below a group of fields. */
    public static JComponent hint(String text) {
        JTextArea area = new WrappingText(text == null ? "" : text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        JLabel reference = new JLabel();
        area.setFont(reference.getFont().deriveFont(reference.getFont().getSize2D() - 1f));
        area.setForeground(ThemeColor.of("comic.hintText"));
        return area;
    }

    /**
     * A text that wraps at whatever width the form gives it: it never asks for more width than a short
     * field (so it can't widen the page), and when a resize changes its line count it re-lays the form
     * out, so no line is cut off — neither when the window grows nor when it shrinks.
     */
    static final class WrappingText extends JTextArea {
        private static final long serialVersionUID = 1L;

        WrappingText(String text) {
            super(text);
            addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (getPreferredSize().height != getHeight()) {
                        revalidate();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension pref = super.getPreferredSize();
            return new Dimension(Math.min(pref.width, MIN_FIELD_WIDTH), pref.height);
        }

        /** Narrow, but never lower than its wrapped lines (GridBag falls back to minimums when tight). */
        @Override
        public Dimension getMinimumSize() {
            return getPreferredSize();
        }
    }

    /** A plain, wrapping status text in the field column (a JLabel would run out of a narrow window). */
    public static JTextArea text(String text) {
        JTextArea area = (JTextArea) hint(text);
        area.setFont(new JLabel().getFont());
        area.setForeground(new JLabel().getForeground());
        return area;
    }

    /** The labels of this form, for {@link #alignLabels(ComicForm...)}. */
    List<JLabel> labels() {
        return labels;
    }

    /** Gives all forms of a page the same label column, so the fields line up across sections. */
    public static void alignLabels(ComicForm... forms) {
        int widest = 0;
        for (ComicForm form : forms) {
            for (JLabel label : form.labels()) {
                widest = Math.max(widest, label.getPreferredSize().width);
            }
        }
        for (ComicForm form : forms) {
            for (JLabel label : form.labels()) {
                Dimension pref = label.getPreferredSize();
                label.setPreferredSize(new Dimension(widest, pref.height));
                label.setMinimumSize(new Dimension(widest, pref.height));
            }
        }
    }

    // ------------------------------------------------------------------ pages

    /**
     * A settings page: the sections stacked top-down with uniform gaps, in a borderless scroll pane that
     * scrolls vertically only. Sections keep their preferred height — the page is never stretched.
     */
    public static JScrollPane page(JComponent... sections) {
        return scroll(stack(sections));
    }

    /** The page's content without the scroll pane (for callers that embed it elsewhere). */
    public static JPanel stack(JComponent... sections) {
        Stack stack = new Stack();
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.NORTHWEST;
        for (int i = 0; i < sections.length; i++) {
            c.gridy = i;
            c.insets = new Insets(i == 0 ? 0 : SECTION_GAP, 0, 0, 0);
            stack.add(sections[i], c);
        }
        c.gridy = sections.length;
        c.weighty = 1;
        c.fill = GridBagConstraints.BOTH;
        c.insets = new Insets(0, 0, 0, 0);
        stack.add(javax.swing.Box.createGlue(), c);
        return stack;
    }

    /** A borderless, vertical-only scroll pane around a page stack (or any width-tracking content). */
    public static JScrollPane scroll(JComponent content) {
        // A view that is not Scrollable is sized by JViewport to max(preferred, viewport): wider than the
        // window as soon as one hint or field prefers more, and it never shrinks again with the window.
        // That was THE recurring "content runs out of the frame" bug — so every view gets bound here.
        JComponent view = content instanceof Scrollable ? content : new Fit(content);
        JScrollPane scroll = new JScrollPane(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    /**
     * THE single entry point for putting any settings content into a slot of a window (a settings card,
     * a tab, a plugin dialog): the content always takes exactly the slot's WIDTH, follows every resize at
     * once, and scrolls vertically only. A {@link JScrollPane} is already bound and stays as it is; the
     * tabs of a {@link JTabbedPane} are fitted one by one; anything else goes into {@link #scroll}.
     */
    public static JComponent fit(JComponent content) {
        if (content == null || content instanceof JScrollPane) {
            return content;
        }
        if (content instanceof javax.swing.JTabbedPane) {
            javax.swing.JTabbedPane tabs = (javax.swing.JTabbedPane) content;
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component tab = tabs.getComponentAt(i);
                if (tab instanceof JComponent && !(tab instanceof JScrollPane)) {
                    // Release the tab's component first: re-parenting it into the wrapper would drop the tab.
                    tabs.setComponentAt(i, new JPanel());
                    tabs.setComponentAt(i, fit((JComponent) tab));
                }
            }
            return tabs;
        }
        return scroll(content);
    }

    /**
     * Binds a non-{@link Scrollable} view to its viewport: always the viewport's width; the viewport's
     * height too when the content can live with it (its minimum fits — e.g. it scrolls inside itself, or
     * it is short), so nested scroll panes scroll instead of the whole page; otherwise its own height.
     * A short content stays at the top at its preferred height and is never spread over the viewport.
     */
    static final class Fit extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;
        private final JComponent content;

        Fit(JComponent content) {
            super(null);
            this.content = content;
            setOpaque(false);
            // A former tab component comes back hidden (JTabbedPane hides unselected tabs).
            content.setVisible(true);
            add(content);
        }

        JComponent content() {
            return content;
        }

        @Override
        public Dimension getPreferredSize() {
            return content.getPreferredSize();
        }

        @Override
        public Dimension getMinimumSize() {
            return content.getMinimumSize();
        }

        @Override
        public void doLayout() {
            int height = Math.min(getHeight(), content.getPreferredSize().height);
            height = Math.max(height, Math.min(getHeight(), content.getMinimumSize().height));
            content.setBounds(0, 0, getWidth(), height);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 14;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(14, (orientation == javax.swing.SwingConstants.VERTICAL
                    ? visibleRect.height : visibleRect.width) - 14);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            Component parent = getParent();
            return parent instanceof JViewport
                    && parent.getHeight() >= content.getMinimumSize().height;
        }
    }

    /**
     * The page column: tracks the viewport width (so wrapping hints and stretching fields follow the
     * window) and NEVER the height — a short page stays compact at the top instead of being spread over
     * the whole viewport.
     */
    public static class Stack extends JPanel implements Scrollable {
        private static final long serialVersionUID = 1L;

        public Stack() {
            super(new GridBagLayout());
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN, PAGE_MARGIN));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 14;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return orientation == javax.swing.SwingConstants.VERTICAL
                    ? Math.max(14, visibleRect.height - 14) : Math.max(14, visibleRect.width - 14);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            // Only when the content is SHORTER than the viewport: then it may fill it (the glue at the
            // bottom takes the space, rows stay compact); otherwise the page scrolls.
            Component parent = getParent();
            return parent instanceof JViewport && parent.getHeight() > getPreferredSize().height;
        }
    }
}
