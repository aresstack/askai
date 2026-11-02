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

    private final List<JLabel> labels = new ArrayList<JLabel>();
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
        addLabel(label, cell);
        GridBagConstraints c = fieldConstraints(stretch);
        add(stretch ? limitShrink(cell) : cell, c);
        nextRow++;
        return this;
    }

    /** A switch spanning both columns; its box sits at the label column's left edge. */
    public ComicForm check(JCheckBox box) {
        return spanning(box, false);
    }

    /** Controls as one sentence ("Delete history older than [30] days"), spanning both columns. */
    public ComicForm inline(JComponent... parts) {
        JPanel line = new JPanel(new GridBagLayout());
        line.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.WEST;
        for (int i = 0; i < parts.length; i++) {
            c.insets = new Insets(0, 0, 0, i == parts.length - 1 ? 0 : INLINE_GAP);
            line.add(parts[i], c);
        }
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        line.add(javax.swing.Box.createHorizontalGlue(), c);
        return spanning(line, true);
    }

    /** A multi-line editor (usually a scroll pane around a text area) with its label ABOVE, full width. */
    public ComicForm area(String label, JComponent editor) {
        if (label != null && label.length() > 0) {
            JLabel caption = new JLabel(label);
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0;
            c.gridy = nextRow++;
            c.gridwidth = 2;
            c.anchor = GridBagConstraints.WEST;
            c.insets = new Insets(nextRow == 1 ? 0 : ROW_GAP, 0, 2, 0);
            add(caption, c);
        }
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = nextRow++;
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
        c.gridy = nextRow++;
        c.gridwidth = 2;
        c.weightx = 1;
        c.anchor = GridBagConstraints.WEST;
        c.fill = stretch ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        c.insets = new Insets(c.gridy == 0 ? 0 : ROW_GAP, 0, 0, 0);
        add(stretch ? limitShrink(component) : component, c);
        return this;
    }

    private void addLabel(String text, JComponent field) {
        JLabel label = new JLabel(text == null ? "" : text);
        if (field != null) {
            label.setLabelFor(field);
        }
        labels.add(label);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = nextRow;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(nextRow == 0 ? 0 : ROW_GAP, 0, 0, LABEL_GAP);
        add(label, c);
    }

    private GridBagConstraints fieldConstraints(boolean stretch) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = nextRow;
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
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(false);
        area.setBorder(BorderFactory.createEmptyBorder());
        JLabel reference = new JLabel();
        area.setFont(reference.getFont().deriveFont(reference.getFont().getSize2D() - 1f));
        area.setForeground(ThemeColor.of("comic.hintText"));
        // A wrapping text area reports its single-line width as preferred; cap it so the page decides.
        area.setMinimumSize(new Dimension(MIN_FIELD_WIDTH, 0));
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
        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(14);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
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
