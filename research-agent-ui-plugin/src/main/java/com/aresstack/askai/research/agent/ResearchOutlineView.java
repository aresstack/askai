package com.aresstack.askai.research.agent;

import com.aresstack.comiccontrols.control.ComicButton;
import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * The "Inhaltsverzeichnis" (outline) view — a DERIVED, rebuildable projection of the knowledge corpus.
 * Issue #29: opening this tab NEVER rebuilds anything; it displays the last persisted outline (marked STALE
 * when its inputs changed) and the explicit "Inhaltsverzeichnis erzeugen" / "Neu erzeugen" button is the ONLY
 * rebuild trigger (topic discovery + outline building, visibly staged in the backend). All methods run on
 * the EDT.
 */
public final class ResearchOutlineView extends JPanel {

    private static final String NOT_GENERATED =
            "_Noch kein Inhaltsverzeichnis. Erzeuge es mit „Inhaltsverzeichnis erzeugen“._";
    private static final String UNAVAILABLE =
            "_Inhaltsverzeichnis nicht verfügbar: für diese Session ist kein Embedding-Modell konfiguriert._";
    private static final String GENERATING = "_Inhaltsverzeichnis wird erzeugt …_";
    private static final String STALE_NOTE = "Veraltet — Quellen oder Passagen haben sich geändert.";

    private final JLabel staleNote = new JLabel();
    private final JButton generateButton = new ComicButton("Inhaltsverzeichnis erzeugen");
    private final MarkdownView markdownView;
    // #43 slice 8: the graphical numbered chapter tree is the DEFAULT face of the outline;
    // the markdown stays reachable behind the toggle (and remains the face of meta states).
    private final OutlineTreeView treeView = new OutlineTreeView();
    private final JButton viewToggle = new ComicButton("Text");
    private final JPanel center = new JPanel(new java.awt.CardLayout());
    private boolean showingTree = true;

    public ResearchOutlineView(MarkdownViewFactory markdownViewFactory) {
        super(new BorderLayout());
        this.markdownView = markdownViewFactory.create(
                MarkdownViewOptions.builder().selectable(true).build());
        staleNote.setVisible(false);
        generateButton.setVisible(false); // shown only when a generate action is wired (plan PR 23: none in the drawer)
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        toolbar.add(generateButton);
        viewToggle.setToolTipText("Switch between the chapter tree and the markdown text");
        viewToggle.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showingTree = !showingTree;
                showCard();
            }
        });
        toolbar.add(viewToggle);
        toolbar.add(staleNote);
        add(toolbar, BorderLayout.NORTH);
        javax.swing.JScrollPane treeScroll = new javax.swing.JScrollPane(treeView);
        treeScroll.setBorder(BorderFactory.createEmptyBorder());
        treeScroll.getViewport().setOpaque(false);
        treeScroll.setOpaque(false);
        center.setOpaque(false);
        center.add(treeScroll, "tree");
        center.add(markdownView.getComponent(), "markdown");
        add(center, BorderLayout.CENTER);
        showCard();
    }

    /** Flip the visible card; the toggle names the OTHER view (what a click switches to). */
    private void showCard() {
        boolean tree = showingTree && treeView.hasRows();
        ((java.awt.CardLayout) center.getLayout()).show(center, tree ? "tree" : "markdown");
        viewToggle.setText(tree ? "Text" : "Tree");
        viewToggle.setEnabled(treeView.hasRows());
    }

    /** The explicit rebuild trigger (issue #29) — the ONLY thing that starts topic/outline processing. */
    public void setGenerateAction(final Runnable action) {
        generateButton.setVisible(action != null);
        generateButton.addActionListener(new java.awt.event.ActionListener() {
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (action != null) {
                    // A lightweight progress hint; the next projection-update refresh replaces it with the
                    // rebuilt outline (the rebuild itself is debounced and runs off the EDT).
                    markdownView.setMarkdown(GENERATING);
                    treeView.setRows(java.util.Collections.<OutlineTreeModel.Row>emptyList());
                    showCard();
                    action.run();
                }
            }
        });
    }

    /**
     * Show the persisted outline markdown ("" = none yet), the stale marker and the matching button label.
     * {@code stale} may be {@code null} when the knowledge capability is unavailable. NEVER rebuilds.
     */
    public void render(String outlineMarkdown, Boolean stale) {
        boolean hasOutline = outlineMarkdown != null && !outlineMarkdown.trim().isEmpty();
        boolean capabilityAvailable = stale != null;
        generateButton.setText(hasOutline ? "Neu erzeugen" : "Inhaltsverzeichnis erzeugen");
        generateButton.setEnabled(capabilityAvailable);
        boolean showStale = capabilityAvailable && hasOutline && stale.booleanValue();
        staleNote.setText(showStale ? STALE_NOTE : "");
        staleNote.setVisible(showStale);
        treeView.setRows(hasOutline ? OutlineTreeModel.parse(outlineMarkdown)
                : java.util.Collections.<OutlineTreeModel.Row>emptyList());
        if (!capabilityAvailable) {
            markdownView.setMarkdown(hasOutline ? outlineMarkdown : UNAVAILABLE);
            showCard();
            return;
        }
        markdownView.setMarkdown(hasOutline ? outlineMarkdown : NOT_GENERATED);
        showCard();
    }

    public void dispose() {
        markdownView.dispose();
    }
}
