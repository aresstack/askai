package com.aresstack.askai.research.agent;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;

/**
 * The ONE mindmap entry point shared by the toolbar button and {@code /map}: build the mechanical
 * sources mindmap and hand its MERMAID SOURCE to the host's diagram overlay — the host embeds its
 * full viewer (zoom/pan, high-res re-render, copy/save), so the plugin never renders Mermaid for
 * display. When nothing qualifies yet, an honest hint overlay replaces the diagram.
 */
final class ResearchMindmapAction {

    static final String OVERLAY_TITLE = "Sources Mindmap";

    /** The two overlay routes a context offers (diagram source vs plain hint component). */
    interface OverlayHost {
        void showDiagram(String mermaidSource, String title);

        void showHint(JComponent content, String title);
    }

    private ResearchMindmapAction() {
    }

    static void open(ResearchAgentSession session, OverlayHost host) {
        String mermaid = session.buildSourceMindmapMermaid();
        if (mermaid == null) {
            JLabel hint = new JLabel(
                    "No rated sources yet — run a web search first, then visualize.");
            hint.setBorder(BorderFactory.createEmptyBorder(24, 24, 24, 24));
            host.showHint(hint, OVERLAY_TITLE);
            return;
        }
        host.showDiagram(mermaid, OVERLAY_TITLE);
    }
}
