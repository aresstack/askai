package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.agent.ResearchPhaseAccessoryContribution;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import javax.swing.JComponent;
import java.awt.Component;
import java.awt.Container;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * PR 12 over a REAL productive session: the blacklist filters in every phase, reports the accepted
 * sources a typed entry hits without excluding them, asks before a re-submit keeps accepted sources
 * out, and the embedding model is required for submit-scope (re-checked in the same chat).
 */
public class BlacklistAndEmbeddingGateTest {

    private static com.aresstack.askai.research.sources.ResearchSourceRecord accepted(String id, String title,
            String url) {
        return com.aresstack.askai.research.sources.ResearchSourceRecord.builder(id).title(title).url(url)
                .status(com.aresstack.askai.research.sources.SourceStatus.ACCEPTED).build();
    }

    @Test
    public void anEntryMatchingTwoAcceptedSourcesReportsAndListsBothAndExcludesNeither() throws Exception {
        Fx fx = new Fx(tempDir());
        fx.repository.put(accepted("source-1", "Swing layout managers", "https://a.example/layout"));
        fx.repository.put(accepted("source-2", "Threads", "https://b.example/swing-threads"));
        fx.repository.put(accepted("source-3", "FreeRTOS scheduling", "https://c.example/rtos"));
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);

        assertNull(fx.session.addScopeExclusion("Swing"));

        String report = fx.infos.toString();
        assertTrue(report, report.contains("\"Swing\" matches 2 accepted sources"));
        assertTrue(report.contains("Swing layout managers (https://a.example/layout)"));
        assertTrue(report.contains("Threads (https://b.example/swing-threads)"));
        assertFalse(report.contains("FreeRTOS"));
        for (com.aresstack.askai.research.sources.ResearchSourceRecord record
                : fx.repository.find(com.aresstack.askai.research.sources.SourceQuery.all())) {
            assertEquals("no source is excluded by the entry",
                    com.aresstack.askai.research.sources.SourceStatus.ACCEPTED, record.getStatus());
        }
        assertTrue("the typed entry is kept", fx.session.currentScopeDraft().getExclusions().contains("Swing"));
    }

    @Test
    public void withoutAnEmbeddingModelSubmitIsRefusedAndAfterInstallingItSucceedsInTheSameChat()
            throws Exception {
        Fx fx = new Fx(tempDir());
        ConceptTestSupport.makeSubmittable(fx.session);
        fx.resources.setEmbeddingDescriptor(null);
        final com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor[] installed = {null};
        fx.resources.setEmbeddingResolver(new ProductiveResearchSessionResources.EmbeddingResolver() {
            public com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor resolve() {
                if (installed[0] != null) {
                    fx.resources.setEmbeddingDescriptor(installed[0]);
                }
                return installed[0];
            }
        });

        com.aresstack.askai.research.agent.ScopingApprovalOutcome refused =
                fx.session.approveScopingBriefAndContinue();
        assertEquals(com.aresstack.askai.research.agent.ScopingApprovalOutcome.TRANSITION_REJECTED, refused);
        assertTrue(fx.infos.toString(), fx.infos.toString().contains(
                "Sources need an embedding model. Open Models -> Setup."));
        assertEquals(ResearchStateIds.SCOPING, fx.session.currentResearchSnapshot().getCurrentPhaseId());

        installed[0] = ConceptTestSupport.fakeEmbedding(); // the user installs it in Models -> Setup
        assertEquals(com.aresstack.askai.research.agent.ScopingApprovalOutcome.SUCCESS,
                fx.session.approveScopingBriefAndContinue());
        assertEquals(ResearchStateIds.RESEARCH, fx.session.currentResearchSnapshot().getCurrentPhaseId());
    }

    @Test
    public void aResubmitThatNewlyHitsAnAcceptedSourceAsksNamesItAndDispatchesNothingOnCancel()
            throws Exception {
        Fx fx = new Fx(tempDir());
        fx.repository.put(accepted("source-1", "Swing layout managers", "https://a.example/layout"));
        ConceptTestSupport.makeSubmittable(fx.session);
        assertNull(fx.session.addScopeExclusion("Swing"));
        final List<String> asked = new ArrayList<String>();
        fx.session.setResubmitConfirmation(new ResearchAgentSession.ResubmitConfirmation() {
            public boolean confirm(String message) {
                asked.add(message);
                return false;
            }
        });
        long revision = fx.resources.currentState().getRevision();

        assertEquals(com.aresstack.askai.research.agent.ScopingApprovalOutcome.USER_CANCELLED,
                fx.session.approveScopingBriefAndContinue());

        assertEquals(1, asked.size());
        assertTrue(asked.get(0), asked.get(0).contains("Swing layout managers (https://a.example/layout)"));
        assertEquals("nothing dispatched", revision, fx.resources.currentState().getRevision());
        assertEquals(ResearchStateIds.SCOPING, fx.session.currentResearchSnapshot().getCurrentPhaseId());
    }

    @Test
    public void afterSubmitARecordedEntryCannotBeRemovedAndAConceptCardCannotBeAdded() throws Exception {
        Fx fx = new Fx(tempDir());
        ConceptTestSupport.makeSubmittable(fx.session);
        assertNull(fx.session.addScopeExclusion("Swing"));
        assertEquals(com.aresstack.askai.research.agent.ScopingApprovalOutcome.SUCCESS,
                fx.session.approveScopingBriefAndContinue());

        String removal = fx.session.removeScopeExclusion("Swing");
        assertTrue(String.valueOf(removal), removal != null && removal.contains("Reopen Concept"));
        String card = fx.session.addScopeExclusion("Isolation");
        assertTrue(String.valueOf(card), card != null && card.contains("card of the approved concept"));
        assertNull("a later entry is added in every phase", fx.session.addScopeExclusion("example.org"));
        assertTrue(fx.session.currentScopeDraft().getExclusions().contains("example.org"));
        assertEquals("the Phase-1 record is unchanged", java.util.Arrays.asList("Swing"),
                typedOnly(fx.session.frozenConcept().getPin().getBlacklist(), "Swing"));
        assertNull("a later entry can be removed again", fx.session.removeScopeExclusion("example.org"));
    }

    private static List<String> typedOnly(List<String> recorded, String typed) {
        List<String> kept = new ArrayList<String>();
        for (String term : recorded) {
            if (term.equals(typed)) {
                kept.add(term);
            }
            assertFalse("the later entry never enters the record", term.equals("example.org"));
        }
        return kept;
    }

    private static void completeTurn(Fx fx, long seq) {
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.COMPLETED)
                .envelope("evt-complete-" + seq, "s1", "p1", seq, 0L, seq, null).build());
    }

    private static File tempDir() throws IOException {
        return java.nio.file.Files.createTempDirectory("askai-sky-test").toFile();
    }

    // ------------------------------------------------------------------ fixture (productive session)

    private static final class Fx {
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;
        final List<String> infos = new ArrayList<String>();
        final com.aresstack.askai.research.sources.InMemoryResearchSourceRepository repository =
                com.aresstack.askai.research.sources.InMemoryResearchSourceRepository.empty();

        Fx(File projectDir) {
            final ProductiveResearchSessionResources[] holder =
                    new ProductiveResearchSessionResources[1];
            ResearchControlEndpoint control = new ResearchControlEndpoint(
                    new InProcessMcpServerRegistry(), "s1", 7L, new ResearchControlContext() {
                        public String currentPhaseId() {
                            return holder[0] == null ? ResearchStateIds.SCOPING
                                    : holder[0].currentState().getPhaseId();
                        }

                        public String currentStateId() {
                            return holder[0] == null ? ResearchStateIds.NEW
                                    : holder[0].currentState().getStateId();
                        }

                        public String statusLine() {
                            return currentPhaseId() + "/" + currentStateId();
                        }

                        public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore
                                artifactStore() {
                            return new ResearchArtifactStore();
                        }

                        public ResearchSourceRepository sourceRepository() {
                            return repository;
                        }

                        public String acceptCapture(String captureId) {
                            return null;
                        }
                    });
            control.open();
            resources = new ProductiveResearchSessionResources("s1",
                    new OoResearchStateMachine("s1"), null, repository, null,
                    com.aresstack.askai.research.store.ResearchProjectContext.open(
                            "s1", projectDir),
                    control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(new NoopBackend(), null, new PlainHost(infos),
                    "s1", "p1", resources);
            session.activate();
        }
    }

    private static final class NoopBackend implements ResearchSessionBackend {
        public ResearchSessionHandle createSession(ResearchProjectRequest request,
                                                   ResearchSessionListener listener) {
            final String sessionId = request.getSessionId();
            final String projectId = request.getProjectId();
            return new ResearchSessionHandle() {
                public String getSessionId() {
                    return sessionId;
                }

                public String getProjectId() {
                    return projectId;
                }
            };
        }

        public boolean canExecute(ResearchSessionHandle handle, ResearchCommandType command) {
            return false;
        }

        public void executeCommand(ResearchSessionHandle handle, ResearchCommandType command) {
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
        }

        public void submitServiceCommand(ResearchSessionHandle handle, String controlEnvelope) {
        }

        public void approve(ResearchSessionHandle handle, String approvalId) {
        }

        public void reject(ResearchSessionHandle handle, String approvalId, String reason) {
        }

        public void pause(ResearchSessionHandle handle) {
        }

        public void resume(ResearchSessionHandle handle) {
        }

        public void cancel(ResearchSessionHandle handle) {
        }

        public void close(ResearchSessionHandle handle) {
        }
    }

    private static final class FakeComposerContext implements ComposerAccessoryContext {
        private final AgentSession session;

        FakeComposerContext(AgentSession session) {
            this.session = session;
        }

        public AgentSession getSession() {
            return session;
        }

        public UiExecutor getUiExecutor() {
            return inlineUi();
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }
    }

    private static final class PlainHost implements AgentHostContext {
        private final List<String> infos;

        PlainHost(List<String> infos) {
            this.infos = infos;
        }

        public UiExecutor getUiExecutor() {
            return inlineUi();
        }

        public ThemeService getThemeService() {
            return null;
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return null;
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return null;
        }

        public AgentConversationSink getConversationSink() {
            return new AgentConversationSink() {
                public void appendUserMessage(String messageId, String markdown) {
                }

                public void appendAssistantMessage(String messageId, String markdown) {
                }

                public void startThinking(String activityId, String title) {
                }

                public void updateThinking(String activityId, String text) {
                }

                public void finishThinking(String activityId, String summary) {
                }

                public void startToolActivity(String activityId, String title, String explanation) {
                }

                public void updateToolActivity(String activityId, String title, String explanation) {
                }

                public void completeToolActivity(String activityId, String summary) {
                }

                public void failToolActivity(String activityId, String summary) {
                }

                public void requestApproval(String approvalId, String prompt) {
                }

                public void showProblem(String problemId, String publicMessage) {
                    infos.add("PROBLEM " + publicMessage);
                }

                @Override
                public void appendInfoMessage(String messageId, String markdown) {
                    infos.add(markdown);
                }
            };
        }
    }

    private static UiExecutor inlineUi() {
        return new UiExecutor() {
            public void execute(Runnable runnable) {
                runnable.run();
            }

            public void assertUiThread() {
            }

            public boolean isUiThread() {
                return true;
            }
        };
    }
}
