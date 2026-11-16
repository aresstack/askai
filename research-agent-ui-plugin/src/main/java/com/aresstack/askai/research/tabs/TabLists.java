package com.aresstack.askai.research.tabs;

import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSectionPanel;
import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;

/**
 * The shared look of the sidebar tabs' lists: the search bar and the list on one quiet plate, rows tinted by
 * their {@link RowSignal}, the selected row in the navigation blue, one muted summary line at the bottom.
 * Sources, Outline and Document build on this, so the three tabs read as one family.
 */
public final class TabLists {

    /** How strongly a signal colour is mixed into the field background (0 = none, 1 = full). */
    private static final float TINT_STRENGTH = 0.35f;
    /** How far the grey of a pending row sits between the field background (0) and the ink (1). */
    private static final float PENDING_GREY = 0.5f;
    /** How far the muted texts step back from the ink (0 = ink, 1 = background). */
    private static final float MUTED_MIX = 0.35f;
    private static final float SUMMARY_FONT_SIZE = 11.5f;
    private static final int PLATE_PADDING_V = 8;
    private static final int PLATE_PADDING_H = 10;
    /** The gap between the search row and the list on the plate. */
    public static final int PLATE_GAP = 6;
    /** The outer margin of a tab around its plate and footer. */
    public static final int TAB_MARGIN = 6;

    private final ComicPalette palette;

    public TabLists(ComicPalette palette) {
        this.palette = palette;
    }

    /** The plate that carries the search row (top) and the list (center). */
    public ComicSectionPanel plate(JComponent searchRow, JComponent list) {
        ComicSectionPanel plate = new ComicSectionPanel(palette);
        plate.setLayout(new BorderLayout(0, PLATE_GAP));
        plate.setBorder(BorderFactory.createEmptyBorder(PLATE_PADDING_V, PLATE_PADDING_H, PLATE_PADDING_V,
                PLATE_PADDING_H));
        plate.add(searchRow, BorderLayout.NORTH);
        plate.add(list, BorderLayout.CENTER);
        return plate;
    }

    /** A list never scrolls sideways: its columns give way instead. */
    public JScrollPane scroll(Component list) {
        JScrollPane scroll = new ComicScrollPane(list, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(ThemeColor.of("comic.fieldBg"));
        return scroll;
    }

    /** The one quiet line at the bottom of a tab. */
    public JLabel summaryLabel() {
        JLabel summary = new JLabel(" ");
        summary.setFont(ResearchUiTypography.regular(SUMMARY_FONT_SIZE));
        summary.setForeground(mutedInk());
        return summary;
    }

    /**
     * Colour one cell of a row: the signal colour as background; the selected row is filled with the
     * navigation blue and light text (the app's "selected" look), so it never reads as one of the signal
     * colours. Renderers are reused, so every colour is set for every cell.
     *
     * @param muted  the row is outside the work: its text steps back
     * @param accent the cell carries the user's own mark (a star): it speaks in the action accent
     */
    public Component dress(JTable table, Component cell, boolean selected, RowSignal signal, boolean muted,
                           boolean accent) {
        if (selected) {
            cell.setBackground(palette.getNavigationBlue());
            cell.setForeground(ThemeColor.of("comic.fieldBg"));
            return cell;
        }
        Color tint = tint(signal);
        cell.setBackground(tint != null ? tint : table.getBackground());
        cell.setForeground(accent ? palette.getAccentOrange() : muted ? mutedInk() : table.getForeground());
        return cell;
    }

    /** The row colour of a signal, or {@code null} for an uncoloured row. */
    public Color tint(RowSignal signal) {
        Color field = ThemeColor.of("comic.fieldBg");
        Color mark;
        switch (signal) {
            case PENDING:
                mark = ThemeColor.mix(field, palette.getInk(), PENDING_GREY);
                break;
            case RUNNING:
                mark = palette.getNavigationBlue();
                break;
            case DONE:
                mark = palette.getAgentPetrol();
                break;
            case CAUTION:
                mark = palette.getAccentYellow();
                break;
            case FAILED:
                mark = palette.getAccentRed();
                break;
            default:
                return null;
        }
        return ThemeColor.mix(field, mark, TINT_STRENGTH);
    }

    public Color mutedInk() {
        return ThemeColor.mix(palette.getInk(), ThemeColor.of("comic.fieldBg"), MUTED_MIX);
    }

    /** Text for an HTML tooltip. */
    public static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
