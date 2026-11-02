package com.aresstack.askai.ui;

import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.ui.markdown.MarkdownMessageView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Insets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A tabbed area with one fixed, non-closable tab (e.g. the batch log) plus IDE-style Markdown tabs:
 * a single reused <b>preview</b> tab that follows the current selection, and any number of <b>pinned</b>
 * tabs (each with its own close button) that stay open until closed by hand.
 *
 * <p>Rendering is injected as a {@code Function<String, JComponent>} so the component is testable without
 * the full Markdown stack; {@link #markdownRenderer()} supplies the productive renderer.</p>
 */
public final class MarkdownPreviewTabs extends JPanel {

    private final JTabbedPane tabs = ComicControls.tabbedPane();
    private final Function<String, JComponent> renderer;
    private final Map<String, Component> pinnedByKey = new LinkedHashMap<String, Component>();

    private JScrollPane previewScroll; // the single reused preview tab, or null when none is open

    public MarkdownPreviewTabs(String fixedTitle, Component fixedContent, Function<String, JComponent> renderer) {
        super(new BorderLayout());
        if (renderer == null) {
            throw new IllegalArgumentException("renderer must not be null");
        }
        this.renderer = renderer;
        tabs.addTab(fixedTitle, fixedContent); // index 0 — no close button, never removed
        add(tabs, BorderLayout.CENTER);
    }

    /** The productive renderer: a {@link MarkdownMessageView} formatting the given Markdown. */
    public static Function<String, JComponent> markdownRenderer() {
        return new Function<String, JComponent>() {
            public JComponent apply(String markdown) {
                MarkdownMessageView view = new MarkdownMessageView();
                view.setMarkdown(markdown == null ? "" : markdown);
                return view;
            }
        };
    }

    /** Show {@code markdown} in the single reused preview tab, creating it on first use, and select it. */
    public void preview(String title, String markdown) {
        String display = title + " — preview";
        JComponent rendered = renderer.apply(markdown);
        if (previewScroll == null) {
            previewScroll = new JScrollPane(rendered);
            tabs.addTab(display, previewScroll);
        } else {
            previewScroll.setViewportView(rendered);
            tabs.setTitleAt(tabs.indexOfComponent(previewScroll), display);
        }
        tabs.setTabComponentAt(tabs.indexOfComponent(previewScroll), closableTab(display, previewScroll));
        tabs.setSelectedComponent(previewScroll);
    }

    /** Open a permanent, closable tab for {@code key}; if one already exists, just select it. */
    public void pin(String key, String title, String markdown) {
        Component existing = pinnedByKey.get(key);
        if (existing != null) {
            tabs.setSelectedComponent(existing);
            return;
        }
        JScrollPane scroll = new JScrollPane(renderer.apply(markdown));
        tabs.addTab(title, scroll);
        tabs.setTabComponentAt(tabs.indexOfComponent(scroll), closableTab(title, scroll));
        pinnedByKey.put(key, scroll);
        tabs.setSelectedComponent(scroll);
    }

    /** Remove the reused preview tab (if any); pinned tabs and the fixed tab are kept. */
    public void clearPreview() {
        if (previewScroll != null) {
            int index = tabs.indexOfComponent(previewScroll);
            if (index >= 0) {
                tabs.removeTabAt(index);
            }
            previewScroll = null;
        }
    }

    /** @return the number of tabs, including the fixed first tab (for tests). */
    public int tabCount() {
        return tabs.getTabCount();
    }

    /** Package-private tab pane accessor so tests can exercise the real close buttons. */
    JTabbedPane tabsForTest() {
        return tabs;
    }

    /** @return the title of the currently selected tab, or "" (for tests). */
    public String selectedTitle() {
        int i = tabs.getSelectedIndex();
        return i < 0 ? "" : tabs.getTitleAt(i);
    }

    private Component closableTab(String title, final Component content) {
        JPanel tab = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        tab.setOpaque(false);
        tab.add(new JLabel(title));
        JButton close = new ComicButton("×");
        close.setToolTipText("Close");
        close.setFocusable(false);
        close.setContentAreaFilled(false);
        close.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        close.setMargin(new Insets(0, 0, 0, 0));
        close.addActionListener(event -> closeTab(content));
        tab.add(close);
        return tab;
    }

    private void closeTab(Component content) {
        int index = tabs.indexOfComponent(content);
        if (index >= 0) {
            tabs.removeTabAt(index);
        }
        if (content == previewScroll) {
            previewScroll = null;
        } else {
            pinnedByKey.values().remove(content);
        }
    }
}
