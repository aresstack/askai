package com.aresstack.askai.research.sources;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifacts;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;

/** Contributes the structured {@link ResearchSourcesView} for the {@code research.sources} artifact. */
public final class ResearchSourcesViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_SOURCES;
    }

    @Override
    public String getDisplayName() {
        return "Sources";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        AgentSession session = context.getSession();
        if (session instanceof ResearchAgentSession) {
            ResearchAgentSession research = (ResearchAgentSession) session;
            ResearchSourceRepository repository = research.getSourceRepository();
            final ResearchSourcesView view =
                    new ResearchSourcesView(repository, ResearchSourcesView.demoKnownSections());
            view.setVisualizeButton(
                    com.aresstack.askai.research.agent.ResearchVisualizeButtons.sources(research, context));
            // Selecting a source shows its FULL TEXT in the main area (where the chat is) while the
            // Sources pane is active; clearing the selection brings the chat back.
            final SourceFullTextView fullText = new SourceFullTextView(repository, view.knownSectionIds());
            // A save in the reader (relevant, exclude, details, comment) shows in the list at once.
            fullText.setChangeListener(new Runnable() {
                public void run() {
                    view.refresh();
                }
            });
            view.setSelectionPublisher(new java.util.function.Consumer<ResearchSourceRecord>() {
                public void accept(ResearchSourceRecord record) {
                    if (record == null) {
                        context.clearMainContent();
                    } else {
                        fullText.show(record);
                        context.showMainContent(fullText);
                    }
                }
            });
            // Websites that demanded a security check: red (unsolved) / yellow (solved) rows.
            view.setAccessLogSupplier(new java.util.function.Supplier<SourceAccessLog>() {
                public SourceAccessLog get() {
                    return research.getSourceAccessLog();
                }
            });
            // Plan PR 14: the planned searches, reached through the Results/Searches switch.
            view.setSearchPlan(new SessionPlan(research));
            // Keep the table live: every session state change (run finished, approval, continuation)
            // re-reads the repository, so freshly accepted sources appear without manual filtering.
            research.addStateListener(new Runnable() {
                public void run() {
                    view.refresh();
                }
            });
            // #43 slice 9: a Document [n] badge reveals this tab and focuses the source.
            research.setSourcesFocusHandler(new ResearchAgentSession.SourcesFocusHandler() {
                public void focusSource(String sourceId) {
                    view.focusSource(sourceId);
                }
            });
            // #28/#39.7: the reader's Passages view reads the derived generation for the shown source.
            fullText.setPassagesProvider(new SourceFullTextView.PassagesProvider() {
                public java.util.List<com.aresstack.askai.research.domain.Passage>
                        passagesForSource(String sourceId) {
                    return research.passagesForSource(sourceId);
                }
            });
            // #39: the Add-source dialog imports through the session's canonical boundary.
            view.setImportHandler(new ResearchSourcesView.ImportHandler() {
                public String importSource(com.aresstack.askai.research.capture
                        .SourceImportService.SourceInput input) {
                    return research.importUserSource(input);
                }
            });
            return view;
        }
        JPanel placeholder = new JPanel(new BorderLayout());
        placeholder.add(new JLabel("No source repository available."), BorderLayout.NORTH);
        return placeholder;
    }

    /** The Sources tab's view of the session's search plan queue; every failure is logged and leaves the plan as it is. */
    static final class SessionPlan implements SourceSearchPlan {
        private final ResearchAgentSession research;

        SessionPlan(ResearchAgentSession research) {
            this.research = research;
        }

        private com.aresstack.askai.research.plan.SearchPlanQueue queue() {
            return research.searchPlanQueue();
        }

        public java.util.List<com.aresstack.askai.research.plan.SearchPlanEntry> entries() {
            com.aresstack.askai.research.plan.SearchPlanQueue queue = queue();
            if (queue == null) {
                return java.util.Collections.emptyList();
            }
            try {
                return queue.entriesInOrder();
            } catch (Exception unreadable) {
                return java.util.Collections.emptyList();
            }
        }

        public boolean running() {
            return research.searchPlanRunning();
        }

        public boolean runnable() {
            return research.searchPlanRunnable();
        }

        public java.util.List<String> uncovered() {
            com.aresstack.askai.research.plan.SearchPlanQueue queue = queue();
            if (queue == null) {
                return java.util.Collections.emptyList();
            }
            try {
                return queue.outcome().getUncovered();
            } catch (Exception unreadable) {
                return java.util.Collections.emptyList();
            }
        }

        @Override
        public java.util.List<String> weak() {
            com.aresstack.askai.research.plan.SearchPlanQueue queue = queue();
            if (queue == null) {
                return java.util.Collections.emptyList();
            }
            try {
                return queue.outcome().getWeak();
            } catch (Exception unreadable) {
                return java.util.Collections.emptyList();
            }
        }

        public void run() {
            research.runSearchPlan();
        }

        public void stop() {
            research.stopSearchPlan();
        }

        public void drop(String entryId) {
            try {
                queue().drop(entryId);
            } catch (Exception failed) {
                System.err.println("[search-plan] drop failed: " + failed.getMessage());
            }
        }

        public void edit(String entryId, String query) {
            try {
                queue().edit(entryId, query);
            } catch (Exception failed) {
                System.err.println("[search-plan] edit failed: " + failed.getMessage());
            }
        }

        public void rerun(String entryId) {
            try {
                queue().rerun(entryId);
                research.runSearchPlan();
            } catch (Exception failed) {
                System.err.println("[search-plan] re-run failed: " + failed.getMessage());
            }
        }

        public void searchAgain() {
            try {
                queue().searchAgain();
                research.runSearchPlan();
            } catch (Exception failed) {
                System.err.println("[search-plan] search again failed: " + failed.getMessage());
            }
        }
    }
}
