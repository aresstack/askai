package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;

/**
 * Contributes the "Inhaltsverzeichnis" view for the {@code research.outline} artifact — a DERIVED projection
 * of the knowledge corpus, not a source-of-truth artifact. Issue #29: it shows the PERSISTED outline (with a
 * stale marker when its inputs changed) and offers the explicit rebuild button; neither opening the tab nor
 * any upstream change triggers processing. Read-only, no approval, no phase transition. The listener + host
 * MarkdownView are released when the view leaves the hierarchy.
 *
 * <p>No tab of its own anymore: the Document tab uses this view as its DRAWER pane (the document itself
 * shows in the main area).</p>
 */
public final class ResearchOutlineViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_OUTLINE;
    }

    @Override
    public String getDisplayName() {
        return "Outline";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ResearchOutlineView view = new ResearchOutlineView(context.getMarkdownViewFactory());
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        // Issue #29: the button is the ONLY rebuild trigger — opening/refreshing this view never is.
        // Issue #33: the button is an ADAPTER over the session's derived-action command (the service MCP
        // invokes the same command) — no action logic lives in the view.
        view.setGenerateAction(new Runnable() {
            public void run() {
                research.derivedActions().generateOutline();
            }
        });
        view.setCancelAction(new Runnable() {
            public void run() {
                research.cancelOutlineClustering();
            }
        });
        final Runnable refresh = new Runnable() {
            public void run() {
                final String markdown = research.outlineDrawerMarkdown();
                final Boolean stale = research.outlineStale();
                final com.aresstack.askai.research.host.OutlineClusteringRun.Status run =
                        research.outlineRunStatus();
                final boolean allowed = research.outlineClusteringAllowed();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.render(markdown, stale);
                        view.renderRun(run == null ? "" : run.describe(),
                                run != null && run.state
                                        == com.aresstack.askai.research.host.OutlineClusteringRun.State.RUNNING,
                                run != null && run.state
                                        == com.aresstack.askai.research.host.OutlineClusteringRun.State.FAILED,
                                allowed);
                    }
                });
            }
        };
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // (Re)shown: re-attach the observer AND re-read the persisted state so an update made while
                // hidden shows immediately (addIfAbsent-safe). A pure read — never a rebuild.
                research.addStateListener(refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                // Stop live updates while hidden, but keep the reusable MarkdownView intact for a re-show.
                research.removeStateListener(refresh);
            }
        });
        research.addStateListener(refresh);
        refresh.run(); // initial paint: persisted outline or the explicit not-generated state
        return view;
    }
}
