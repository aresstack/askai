package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.comiccontrols.control.ComicButton;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * The two "Visualize" comic icon buttons, built for the footers of the views they belong to (formerly a
 * strip in the top bar): "Visualize concept" in the Concept pane, "Visualize sources" in the Sources pane.
 * Each opens its Mermaid mindmap through the view context's diagram overlay — the same action as
 * {@code /map} ({@link ResearchMindmapAction}) and the concept map ({@link ConceptMindmapAction}).
 */
public final class ResearchVisualizeButtons {

    private ResearchVisualizeButtons() {
    }

    /** "Visualize concept": the concept snapshot as the host's diagram overlay. */
    public static JComponent concept(final ResearchAgentSession session, final ArtifactViewContext context) {
        ComicButton button = iconButton(new MindmapIcon(),
                "Visualize concept");
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ConceptMindmapAction.open(session, new ConceptMindmapAction.OverlayHost() {
                    public void showDiagram(String mermaidSource, String title) {
                        context.showDiagramOverlay(mermaidSource, title);
                    }

                    public void showHint(JComponent content, String title) {
                        context.showTranscriptOverlay(content, title);
                    }
                });
            }
        });
        return button;
    }

    /** "Visualize sources": the sources mindmap (red core, tag leaves) as the host's diagram overlay. */
    public static JComponent sources(final ResearchAgentSession session, final ArtifactViewContext context) {
        ComicButton button = iconButton(new MindmapIcon(true),
                "Visualize sources");
        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                ResearchMindmapAction.open(session, sourcesOverlay(context));
            }
        });
        return button;
    }

    /**
     * The Sources pane's overlay route. The button sits in the source DETAIL row, i.e. a source is
     * selected and its full text covers the chat in the main area — an overlay on the (hidden)
     * transcript would stay invisible and only surface after switching to another pane (the
     * "mindmap shows up in the Concept tab" bug). So the main area is handed back to the chat
     * FIRST, then the overlay opens on top of it, visibly, while the Sources pane stays active.
     */
    static ResearchMindmapAction.OverlayHost sourcesOverlay(final ArtifactViewContext context) {
        return new ResearchMindmapAction.OverlayHost() {
            public void showDiagram(String mermaidSource, String title) {
                context.clearMainContent();
                context.showDiagramOverlay(mermaidSource, title);
            }

            public void showHint(JComponent content, String title) {
                context.clearMainContent();
                context.showTranscriptOverlay(content, title);
            }
        };
    }

    private static ComicButton iconButton(javax.swing.Icon icon, String tooltip) {
        ComicButton button = new ComicButton("", icon);
        button.setToolTipText(tooltip);
        button.setFocusable(false);
        button.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        button.setPreferredSize(new Dimension(26, 26));
        button.setMaximumSize(new Dimension(26, 26));
        return button;
    }
}
