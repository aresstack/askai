package com.aresstack.askai.ui.chat;

import com.aresstack.comiccontrols.theme.ComicPalette;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPainter;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;
import com.aresstack.comiccontrols.theme.ThemeColor;

import javax.swing.JComponent;
import javax.swing.JPopupMenu;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * One chat entry of the drawer's history — a two-line, rounded, hover-aware row instead of the old
 * HTML-label button line. Line 1: activity dot + title (13 Semi Bold) + time (right, 11 Regular);
 * line 2: quiet metadata (11 Regular). No permanent frame: transparent at rest, a very light
 * blue/grey wash on hover, the light {@link ResearchUiPalette#ACCENT_BLUE} wash plus a 3px left
 * accent when selected. The green dot means ONE thing: processing is running in this chat RIGHT
 * NOW — selection and activity are independent states (selected∧idle → wash without dot; busy
 * without selection → dot without wash). Idle rows carry no marker at all. A {@code …} action
 * trigger exists ONLY while hovered; it (and right-click) opens the menu the workspace supplies —
 * the row itself owns no chat actions.
 */
final class ChatHistoryRow extends JComponent {

    /** Builds the row's action menu on demand (close/delete/project — existing functions only). */
    interface MenuSupplier {
        JPopupMenu buildMenu();
    }

    private static final Color HOVER_WASH =
            ThemeColor.mix(ResearchUiPalette.ACCENT_BLUE, ThemeColor.of("comic.fieldBg"), 0.94f);
    private static final Color SELECTED_WASH =
            ThemeColor.mix(ResearchUiPalette.ACCENT_BLUE, ThemeColor.of("comic.fieldBg"), 0.88f);

    private final String title;
    private final String meta;
    private final String time;
    private final boolean busy;
    private final boolean selected;
    private final boolean deleted;
    private final Runnable openAction;
    private final MenuSupplier menuSupplier;
    private final Runnable undoAction;
    private boolean hovered;
    private boolean menuHovered;

    ChatHistoryRow(String title, String meta, String time, boolean busy, boolean selected,
                   Runnable openAction, MenuSupplier menuSupplier) {
        this(title, meta, time, busy, selected, false, openAction, menuSupplier, null);
    }

    ChatHistoryRow(String title, String meta, String time, boolean busy, boolean selected,
                   boolean deleted, Runnable openAction, MenuSupplier menuSupplier,
                   Runnable undoAction) {
        this.title = title;
        this.meta = meta == null ? "" : meta;
        this.time = time == null ? "" : time;
        this.busy = busy;
        this.selected = selected;
        this.deleted = deleted;
        this.openAction = openAction;
        this.menuSupplier = menuSupplier;
        this.undoAction = undoAction;
        setOpaque(false);
        setToolTipText(title);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = false;
                menuHovered = false;
                repaint();
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                boolean inMenu = menuHit().contains(event.getPoint());
                if (inMenu != menuHovered) {
                    menuHovered = inMenu;
                    repaint();
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    showMenu(event.getX(), event.getY());
                    return;
                }
                if (deleted && undoAction != null && menuHit().contains(event.getPoint())) {
                    undoAction.run(); // the permanent return arrow brings the chat back
                    return;
                }
                if (hovered && menuSupplier != null && menuHit().contains(event.getPoint())) {
                    Rectangle hit = menuHit();
                    showMenu(hit.x, hit.y + hit.height);
                } else if (javax.swing.SwingUtilities.isLeftMouseButton(event)
                        && openAction != null) {
                    openAction.run();
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    showMenu(event.getX(), event.getY());
                }
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    String titleForTest() {
        return title;
    }

    boolean deletedForTest() {
        return deleted;
    }

    @Override
    public String getToolTipText(MouseEvent event) {
        if (deleted && undoAction != null && menuHit().contains(event.getPoint())) {
            return "Restore (until the app restarts)";
        }
        return title;
    }

    private void showMenu(int x, int y) {
        JPopupMenu menu = menuSupplier == null ? null : menuSupplier.buildMenu();
        if (menu != null && menu.getComponentCount() > 0) {
            menu.show(this, x, y);
        }
    }

    /** The {@code …} hit area: right side of line 1, left of the time text. */
    private Rectangle menuHit() {
        FontMetrics timeMetrics = getFontMetrics(ResearchUiTypography.regular(11f));
        int timeWidth = time.isEmpty() ? 0 : timeMetrics.stringWidth(time) + 8;
        int size = 20;
        int x = getWidth() - ResearchUiMetrics.CHAT_ROW_PADDING_H - timeWidth - size;
        return new Rectangle(x, 6, size, size);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            int radius = ResearchUiMetrics.CHAT_ROW_RADIUS;
            if (selected) {
                ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius,
                        SELECTED_WASH);
                // The 3px vertical accent in THE navigation blue — no new color.
                g2.setColor(ResearchUiPalette.ACCENT_BLUE);
                g2.fillRoundRect(0, 6, ResearchUiMetrics.CHAT_ROW_ACCENT_WIDTH,
                        getHeight() - 12, 2, 2);
            } else if (hovered) {
                ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), radius, HOVER_WASH);
            }

            int paddingH = ResearchUiMetrics.CHAT_ROW_PADDING_H;
            ComicPalette palette = ComicPalette.defaultPalette();

            // Activity dot: ● small green ONLY while processing actually runs; idle rows carry
            // no marker (the reserved column keeps titles aligned either way).
            int dotCenterY = 15;
            int dotX = paddingH;
            if (busy) {
                g2.setColor(palette.getAgentPetrol());
                g2.fillOval(dotX, dotCenterY - 4, 8, 8);
            }
            int textX = dotX + 8 + 8;

            // Line 1 right side: time, and the hover-only … trigger left of it.
            g2.setFont(ResearchUiTypography.regular(11f));
            FontMetrics timeMetrics = g2.getFontMetrics();
            int timeWidth = time.isEmpty() ? 0 : timeMetrics.stringWidth(time);
            int rightEdge = getWidth() - paddingH;
            if (!time.isEmpty()) {
                g2.setColor(ResearchUiPalette.LIGHT_TEXT_MUTED);
                g2.drawString(time, rightEdge - timeWidth,
                        dotCenterY + timeMetrics.getAscent() / 2 - 1);
            }
            int titleLimit = rightEdge - timeWidth - 8;
            if (deleted && undoAction != null) {
                // Deleted rows carry a PERMANENT return arrow (no hover needed): one click undoes
                // the delete for as long as the app runs.
                Rectangle hit = menuHit();
                if (menuHovered) {
                    g2.setColor(ResearchUiPainter.mix(
                            ResearchUiPalette.ACCENT_BLUE, ThemeColor.of("comic.fieldBg"), 0.82f));
                    g2.fillOval(hit.x, hit.y, hit.width, hit.height);
                }
                g2.setColor(ResearchUiPalette.LIGHT_CONTROL_TEXT);
                g2.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                int cx = hit.x + hit.width / 2;
                int cy = hit.y + hit.height / 2;
                g2.drawLine(cx - 5, cy, cx + 6, cy);       // shaft
                g2.drawLine(cx - 5, cy, cx - 1, cy - 4);   // arrow head (pointing back/left)
                g2.drawLine(cx - 5, cy, cx - 1, cy + 4);
                g2.drawLine(cx + 6, cy, cx + 6, cy - 5);   // the return hook
                titleLimit = hit.x - 6;
            } else if (hovered && menuSupplier != null) {
                Rectangle hit = menuHit();
                if (menuHovered) {
                    g2.setColor(ResearchUiPainter.mix(
                            ResearchUiPalette.ACCENT_BLUE, ThemeColor.of("comic.fieldBg"), 0.82f));
                    g2.fillOval(hit.x, hit.y, hit.width, hit.height);
                }
                g2.setColor(ResearchUiPalette.LIGHT_CONTROL_TEXT);
                int cx = hit.x + hit.width / 2;
                int cy = hit.y + hit.height / 2;
                for (int i = -1; i <= 1; i++) {
                    g2.fillOval(cx + i * 4 - 1, cy - 1, 2, 2);
                }
                titleLimit = hit.x - 6;
            }

            // Line 1: the title, ellipsized against time/… so nothing ever clips mid-glyph.
            // A deleted chat dims its title — the row must READ as deleted, not merely say so.
            g2.setFont(ResearchUiTypography.semiBold(13f));
            FontMetrics titleMetrics = g2.getFontMetrics();
            g2.setColor(deleted ? ResearchUiPalette.LIGHT_TEXT_MUTED : palette.getInk());
            g2.drawString(ellipsize(title, titleMetrics, titleLimit - textX), textX,
                    dotCenterY + titleMetrics.getAscent() / 2 - 1);

            // Line 2: quiet metadata ("Deleted" speaks in a calmed danger red).
            if (!meta.isEmpty()) {
                g2.setFont(ResearchUiTypography.regular(11f));
                FontMetrics metaMetrics = g2.getFontMetrics();
                g2.setColor(deleted
                        ? ResearchUiPainter.mix(ResearchUiPalette.DANGER_RED, ThemeColor.of("comic.fieldBg"), 0.25f)
                        : ResearchUiPalette.LIGHT_TEXT_MUTED);
                g2.drawString(ellipsize(meta, metaMetrics, rightEdge - textX), textX,
                        getHeight() - 9);
            }
        } finally {
            g2.dispose();
        }
    }

    private static String ellipsize(String text, FontMetrics metrics, int maxWidth) {
        if (metrics.stringWidth(text) <= maxWidth || text.isEmpty()) {
            return text;
        }
        String ellipsis = "…";
        int budget = maxWidth - metrics.stringWidth(ellipsis);
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end)) > budget) {
            end--;
        }
        return text.substring(0, end) + ellipsis;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, ResearchUiMetrics.CHAT_ROW_HEIGHT);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, ResearchUiMetrics.CHAT_ROW_HEIGHT);
    }
}
