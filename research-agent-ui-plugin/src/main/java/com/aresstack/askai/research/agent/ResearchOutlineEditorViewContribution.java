package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContext;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.store.FileOutlineStore;

import javax.swing.JComponent;

/**
 * The Outline tab between Sources and Document (plan PR 23): the editor of the stored outline. Every write goes
 * through the session's {@link OutlineWorkspace} and its one write guard; the tab's mode is that guard's answer
 * (the D4 table). Everything about making the outline lives here: "Generate outline", the progress and the
 * stop of the topic clustering it starts, and the outline to read (before one exists: the topics found so far).
 */
public final class ResearchOutlineEditorViewContribution implements ArtifactViewContribution {

    @Override
    public String getArtifactTypeId() {
        return ResearchArtifacts.TYPE_OUTLINE_EDITOR;
    }

    @Override
    public String getDisplayName() {
        return "Outline";
    }

    @Override
    public JComponent createView(ArtifactViewContext context) {
        final OutlineEditorView view = new OutlineEditorView();
        AgentSession session = context.getSession();
        if (!(session instanceof ResearchAgentSession)) {
            return view;
        }
        final ResearchAgentSession research = (ResearchAgentSession) session;
        final UiExecutor uiExecutor = context.getUiExecutor();
        view.setActions(actions(research));
        if (context.getMarkdownViewFactory() != null) {
            final com.aresstack.askai.plugin.api.service.MarkdownView text = context.getMarkdownViewFactory().create(
                    com.aresstack.askai.plugin.api.service.MarkdownViewOptions.builder().selectable(true).build());
            view.setTextView(text.getComponent(), new java.util.function.Consumer<String>() {
                public void accept(String markdown) {
                    text.setMarkdown(markdown);
                }
            });
        }
        final Runnable refresh = new Runnable() {
            public void run() {
                final OutlineEditorView.Snapshot snapshot = snapshot(research);
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.render(snapshot);
                    }
                });
            }
        };
        view.addAncestorListener(new javax.swing.event.AncestorListener() {
            public void ancestorAdded(javax.swing.event.AncestorEvent event) {
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
        refresh.run();
        return view;
    }

    /** One read of the session for a repaint; a damaged store shows as a read-only message. */
    static OutlineEditorView.Snapshot snapshot(ResearchAgentSession research) {
        OutlineWorkspace workspace = research.outlineWorkspace();
        if (workspace == null) {
            return new OutlineEditorView.Snapshot(Outline.EMPTY, 0, "This session has no outline store.", "");
        }
        try {
            com.aresstack.askai.research.host.OutlineClusteringRun.Status run = research.outlineRunStatus();
            return new OutlineEditorView.Snapshot(workspace.current(), workspace.workingRevision(),
                    workspace.writeRefusal(), research.outlineChangeRequest())
                    .withText(research.outlineDrawerMarkdown())
                    .withRun(run == null ? "" : run.describe(),
                            run != null && run.state
                                    == com.aresstack.askai.research.host.OutlineClusteringRun.State.RUNNING,
                            run != null && run.state
                                    == com.aresstack.askai.research.host.OutlineClusteringRun.State.FAILED);
        } catch (FileOutlineStore.CorruptOutlineException corrupt) {
            return new OutlineEditorView.Snapshot(Outline.EMPTY, 0, corrupt.getMessage(), "");
        }
    }

    static OutlineEditorView.Actions actions(final ResearchAgentSession research) {
        return new OutlineEditorView.Actions() {
            public String save(Outline draft, long expectedRevision) {
                OutlineWorkspace workspace = research.outlineWorkspace();
                if (workspace == null) {
                    return "This session has no outline store.";
                }
                try {
                    workspace.saveEdits(draft, expectedRevision, System.currentTimeMillis());
                    return null;
                } catch (FileOutlineStore.RevisionConflictException moved) {
                    return "The outline changed meanwhile (now revision " + moved.getCurrentRevision()
                            + "). Your edit was not saved.";
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    return refused.getMessage();
                }
            }

            public String restore(long revision, long expectedRevision) {
                OutlineWorkspace workspace = research.outlineWorkspace();
                if (workspace == null) {
                    return "This session has no outline store.";
                }
                try {
                    workspace.restore(revision, expectedRevision, System.currentTimeMillis());
                    return null;
                } catch (FileOutlineStore.RevisionConflictException moved) {
                    return "The outline changed meanwhile (now revision " + moved.getCurrentRevision() + ").";
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    return refused.getMessage();
                }
            }

            public String history(long revision) {
                OutlineWorkspace workspace = research.outlineWorkspace();
                if (workspace == null) {
                    return null;
                }
                try {
                    Outline outline = workspace.store().workingHistory(revision);
                    return outline == null ? null : OutlineJson.write(outline);
                } catch (RuntimeException unreadable) {
                    return null;
                }
            }

            public void generate() {
                research.derivedActions().generateOutline();
            }

            public void cancelGenerate() {
                research.cancelOutlineClustering();
            }

            public OutlineEditorView.JobOutcome regenerateChapter(String chapterId, int sections, String comment) {
                return outcome(chapterService(research).regenerateChapter(chapterId, sections, comment));
            }

            public OutlineEditorView.JobOutcome adoptCandidate(String clusterId) {
                return outcome(chapterService(research).adoptCandidate(clusterId));
            }
        };
    }

    private static com.aresstack.askai.research.outline.brief.OutlineChapterService chapterService(
            ResearchAgentSession research) {
        com.aresstack.askai.research.outline.brief.OutlineChapterService service = research.outlineChapterService();
        if (service == null) {
            throw new IllegalStateException("Chapter regeneration needs the model port and the knowledge store.");
        }
        return service;
    }

    private static OutlineEditorView.JobOutcome outcome(
            com.aresstack.askai.research.outline.brief.OutlineChapterService.Outcome outcome) {
        return new OutlineEditorView.JobOutcome(outcome.getChapterId(), outcome.getMessage(),
                outcome.getBriefFailure() != null);
    }
}
