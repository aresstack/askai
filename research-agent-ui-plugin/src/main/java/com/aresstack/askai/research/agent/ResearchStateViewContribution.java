package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;

import javax.swing.JComponent;

/**
 * Contributes the read-only {@link ResearchStateView} for the {@code research.state} artifact. The view
 * subscribes to the session's state changes and re-reads the domain snapshot on the EDT via the host
 * {@link UiExecutor}; it removes its listener when the component is removed from the hierarchy so no listener
 * leaks after a plugin disable / agent switch (which also closes the session, halting events).
 */
public final class ResearchStateViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_STATE;
    }

    @Override
    public String getDisplayName() {
        return "Revisions";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final ResearchStateView view = new ResearchStateView();
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        // Phase clicks and run controls route through the session's SEMANTIC command processor —
        // the exact same path as the red tags, the slash commands and the MCP run_command. That
        // matters beyond symmetry: e.g. submit-scope runs the full brief-approval commit there,
        // which a raw state-machine dispatch would silently skip. Never the machine directly.
        view.setCommandListener(new ResearchStateView.CommandListener() {
            public com.aresstack.askai.research.backend.ResearchCommandDispatchResult commandClicked(
                    com.aresstack.askai.research.state.ResearchCommandType command) {
                String semantic = ResearchSemanticCommands.semanticNameFor(command);
                if (semantic == null) {
                    return research.dispatch(command, null); // not user vocabulary — plain dispatch
                }
                String outcome = research.executeCommand(semantic, "");
                if (outcome != null && outcome.startsWith("handled")) {
                    return com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            .accepted();
                }
                String detail = outcome == null ? "The command produced no result."
                        : outcome.replaceFirst("^rejected:\\s*", "");
                return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                        com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                .Status.DISPATCH_FAILED, detail);
            }
        });
        final Runnable refresh = new Runnable() {
            public void run() {
                // Snapshot is read on the caller thread but applied to Swing via the UiExecutor.
                final ResearchStateSnapshot snapshot = research.currentResearchSnapshot();
                final java.util.List<com.aresstack.askai.research.domain.task.ResearchTask> tasks =
                        research.currentResearchTasks();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setSnapshot(snapshot);
                        view.setTasks(tasks);
                    }
                });
            }
        };
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
                // (Re)shown: re-attach the observer AND re-read the snapshot so a change made while hidden
                // shows immediately instead of staying stale until a restart (addStateListener is addIfAbsent).
                research.addStateListener(refresh);
                refresh.run();
            }

            public void ancestorMoved(javax.swing.event.AncestorEvent event) {
            }

            public void ancestorRemoved(javax.swing.event.AncestorEvent event) {
                research.removeStateListener(refresh);
            }
        });
        research.addStateListener(refresh);
        refresh.run(); // initial paint
        return withPhaseFooter(view, context);
    }

    /**
     * The State pane with its FOOTER: the phase selector at the left — the same spot it had in the Chats
     * pane footer (same height and padding), now beside the state it switches.
     */
    private static JComponent withPhaseFooter(JComponent view, final ArtifactViewContext context) {
        JComponent phase = new ResearchPhaseToolbarContribution().createComponent(
                new com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContext() {
                    public AgentSession getSession() {
                        return context.getSession();
                    }

                    public UiExecutor getUiExecutor() {
                        return context.getUiExecutor();
                    }

                    public com.aresstack.askai.plugin.api.service.ThemeService getThemeService() {
                        return context.getThemeService();
                    }
                });
        javax.swing.JPanel footer = new javax.swing.JPanel(new java.awt.BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.setName("research.state.footer");
        footer.setBorder(javax.swing.BorderFactory.createEmptyBorder(
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.FOOTER_PADDING_V,
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.FOOTER_PADDING_H,
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.FOOTER_PADDING_V,
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.FOOTER_PADDING_H));
        footer.setPreferredSize(new java.awt.Dimension(10,
                com.aresstack.comiccontrols.theme.ResearchUiMetrics.FOOTER_HEIGHT));
        footer.add(phase, java.awt.BorderLayout.WEST);
        javax.swing.JPanel pane = new javax.swing.JPanel(new java.awt.BorderLayout());
        pane.setOpaque(false);
        pane.add(view, java.awt.BorderLayout.CENTER);
        pane.add(footer, java.awt.BorderLayout.SOUTH);
        return pane;
    }
}
