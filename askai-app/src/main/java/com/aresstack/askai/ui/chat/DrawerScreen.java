package com.aresstack.askai.ui.chat;

import com.aresstack.askai.ui.ChatComposerPanel;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.comiccontrols.control.ComicScrollPane;
import com.aresstack.comiccontrols.control.ComicSplitPane;
import com.aresstack.comiccontrols.theme.ResearchUiMetrics;
import com.aresstack.comiccontrols.theme.ResearchUiPalette;
import com.aresstack.comiccontrols.theme.ResearchUiTypography;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A screen with the chat workspace's DRAWER look: on the left the page list in the drawer's own design (a group
 * heading, then one chat-list row per page, the shown page highlighted like the active chat), optionally a
 * footer with a way back; on the right the selected page. Used for Audio (batch transcription, audio
 * processing) and Models (setup, installed, running).
 */
public final class DrawerScreen extends JPanel {

    private static final long serialVersionUID = 1L;
    private static final int DRAWER_MIN = 200;
    private static final int DRAWER_MAX = 460;
    private static final int DRAWER_DEFAULT = 260;

    /** One page: its content (pages may share one component) and what to do when it is shown. */
    private static final class Page {
        final JComponent content;
        final Runnable onShow;

        Page(JComponent content, Runnable onShow) {
            this.content = content;
            this.onShow = onShow;
        }
    }

    private final String heading;
    private final Map<String, Page> pages = new LinkedHashMap<String, Page>();
    private final JPanel rows = new JPanel();
    private final JPanel drawer = new JPanel(new BorderLayout());
    private final JPanel main = new JPanel(new BorderLayout());
    private String selected;

    public DrawerScreen(String heading) {
        super(new BorderLayout());
        this.heading = heading;
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        JScrollPane scroll = new ComicScrollPane(rows,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        drawer.add(scroll, BorderLayout.CENTER);
        main.setOpaque(false);
        ComicSplitPane split = new ComicSplitPane(drawer, main, DRAWER_MIN, DRAWER_MAX);
        split.setPreferredLeftWidth(DRAWER_DEFAULT);
        add(split, BorderLayout.CENTER);
        renderRows();
    }

    /** Adds a page; {@code onShow} (may be null) runs every time the page is shown. */
    public void addPage(String name, JComponent content, Runnable onShow) {
        pages.put(name, new Page(content, onShow));
        if (selected == null) {
            selected = name;
        }
        renderRows();
        if (name.equals(selected)) {
            showSelected(false);
        }
    }

    /** Shows this page (no-op for unknown names). */
    public void select(String name) {
        if (name == null || !pages.containsKey(name)) {
            return;
        }
        selected = name;
        renderRows();
        showSelected(true);
    }

    public String selectedPage() {
        return selected;
    }

    public List<String> pageNames() {
        return new ArrayList<String>(pages.keySet());
    }

    /** The drawer's usual footer with a way back (e.g. "Back to chat"). */
    public void setBackAction(String label, final Runnable back) {
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H,
                ResearchUiMetrics.FOOTER_PADDING_V, ResearchUiMetrics.FOOTER_PADDING_H));
        footer.setPreferredSize(new Dimension(10, ResearchUiMetrics.FOOTER_HEIGHT));
        ComicButton button = new ComicButton(label, ChatComposerPanel.createBackGlyphIcon());
        button.setIconTextGap(8);
        button.setFocusable(false);
        button.addActionListener(event -> back.run());
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.add(button);
        footer.add(wrap, BorderLayout.WEST);
        drawer.add(footer, BorderLayout.SOUTH);
        drawer.revalidate();
    }

    private void showSelected(boolean runOnShow) {
        Page page = pages.get(selected);
        if (page == null) {
            return;
        }
        if (main.getComponentCount() != 1 || main.getComponent(0) != page.content) {
            main.removeAll();
            main.add(page.content, BorderLayout.CENTER);
            main.revalidate();
        }
        main.repaint();
        if (runOnShow && page.onShow != null) {
            page.onShow.run();
        }
    }

    private void renderRows() {
        rows.removeAll();
        JLabel title = new JLabel(heading);
        title.setFont(ResearchUiTypography.semiBold(11f));
        title.setForeground(ResearchUiPalette.LIGHT_TEXT_MUTED);
        title.setBorder(BorderFactory.createEmptyBorder(14, 10, 4, 8));
        title.setAlignmentX(LEFT_ALIGNMENT);
        rows.add(title);
        for (final String name : pages.keySet()) {
            ChatHistoryRow row = new ChatHistoryRow(name, "", "", false, name.equals(selected),
                    () -> select(name), null);
            row.setAlignmentX(LEFT_ALIGNMENT);
            rows.add(row);
        }
        rows.revalidate();
        rows.repaint();
    }
}
