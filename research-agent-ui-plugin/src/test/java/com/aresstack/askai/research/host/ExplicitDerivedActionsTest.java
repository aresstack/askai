package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchArtifactStore;

import com.aresstack.askai.research.mcp.ResearchControlContext;
import com.aresstack.askai.research.mcp.ResearchControlEndpoint;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.visualize.VisualizationStatus;

import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #29 gate: persisting an upstream artifact starts NO derived processing. A research-brief update is
 * pure core persistence (the visualizer is never touched — not even its honest "no inference port" failure
 * path runs), and a session without the knowledge capability exposes the outline as explicitly unavailable
 * rather than doing anything implicit. The EXPLICIT action paths are the only ones that reach the derived
 * machinery.
 */
public class ExplicitDerivedActionsTest {

    @Test
    public void persistingTheBriefNeverTouchesTheVisualizer() throws Exception {
        Fx fx = new Fx();

        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.RESEARCH_BRIEF)
                .envelope("evt-brief", "s1", "p1", 2L, 0L, 2L, null)
                .activity("brief", com.aresstack.askai.research.backend.ResearchActivityKind.TOOL_UPDATE,
                        ResearchStateIds.SCOPING, "")
                .text("# Fragestellung\n\nWearables im Gesundheitswesen.").build());

        // The brief write runs off the EDT — wait until it landed in the store.
        waitUntil(new Check() {
            public boolean ok() {
                return !fx.session.researchBriefStore().effectiveContent().isEmpty();
            }
        });

        // Old behavior scheduled the visualizer (status would flip to FAILED here: no inference port).
        // New behavior: the brief write is side-effect free — the visualization state is untouched.
        assertEquals(VisualizationStatus.NOT_STARTED, fx.session.visualizationStatus());
        assertNull(fx.session.latestVisualization());
        assertFalse("no visualization → nothing can be stale", fx.session.visualizationStale());
    }

    @Test
    public void theExplicitVisualizationActionReportsAnHonestFailureWithoutAnInferencePort() throws Exception {
        final Fx fx = new Fx();
        fx.session.onEvent(ResearchBackendEvent.builder(ResearchBackendEventType.RESEARCH_BRIEF)
                .envelope("evt-brief", "s1", "p1", 2L, 0L, 2L, null)
                .activity("brief", com.aresstack.askai.research.backend.ResearchActivityKind.TOOL_UPDATE,
                        ResearchStateIds.SCOPING, "")
                .text("# Fragestellung\n\nWearables.").build());
        waitUntil(new Check() {
            public boolean ok() {
                return !fx.session.researchBriefStore().effectiveContent().isEmpty();
            }
        });

        fx.session.requestVisualization(); // the ONLY trigger — this host has no inference port

        waitUntil(new Check() {
            public boolean ok() {
                return fx.session.visualizationStatus() == VisualizationStatus.FAILED;
            }
        });
        assertEquals(VisualizationStatus.FAILED, fx.session.visualizationStatus());
    }

    @Test
    public void withoutTheKnowledgeCapabilityTheOutlineIsExplicitlyUnavailable() {
        Fx fx = new Fx();
        assertNull("no embedding world → staleness is honestly unavailable", fx.session.outlineStale());
        assertFalse("the explicit rebuild reports unavailability instead of failing silently",
                fx.session.requestOutlineRebuild());
        assertEquals("no persisted outline artifact yet", "", fx.session.liveOutlineMarkdown().trim());
    }

    @Test
    public void theDerivedActionCommandsReportTypedOutcomes() {
        // Issue #33: UI buttons and the service MCP share these commands; outcomes are honest, not silent.
        Fx fx = new Fx();
        assertFalse("no embedding world -> outline rejected with a reason",
                fx.session.derivedActions().generateOutline().isAccepted());
        assertTrue(fx.session.derivedActions().generateOutline().getDetail().contains("unavailable"));
        assertTrue("a productive project accepts the visualization request",
                fx.session.derivedActions().generateVisualization().isAccepted());
        assertTrue("an active session accepts the review request",
                fx.session.derivedActions().reviewSources().isAccepted());
        assertTrue("the resources expose the SAME implementation for the service endpoint",
                fx.resources.getDerivedActions() == fx.session.derivedActions());
    }

    // ------------------------------------------------------------------ fixture (mirrors ManualSearchWiringTest)

    private interface Check {
        boolean ok();
    }

    private static void waitUntil(Check check) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (check.ok()) {
                    return;
                }
            } catch (RuntimeException notYet) {
                // keep polling
            }
            Thread.sleep(20L);
        }
        throw new AssertionError("condition not reached within 5s");
    }

    private static final class Fx {
        final InProcessMcpServerRegistry registry = new InProcessMcpServerRegistry();
        final RecordingBackend backend = new RecordingBackend();
        final ProductiveResearchSessionResources resources;
        final ResearchAgentSession session;

        Fx() {
            final ProductiveResearchSessionResources[] holder = new ProductiveResearchSessionResources[1];
            ResearchControlEndpoint control = new ResearchControlEndpoint(registry, "s1", 7L,
                    new ResearchControlContext() {
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

                        public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore artifactStore() {
                            return new ResearchArtifactStore();
                        }

                        public ResearchSourceRepository sourceRepository() {
                            return new com.aresstack.askai.research.sources.InMemoryResearchSourceRepository();
                        }

                        public String acceptCapture(String captureId) {
                            return null;
                        }
                    });
            control.open();
            resources = new ProductiveResearchSessionResources("s1", new OoResearchStateMachine("s1"),
                    null, null, null, tempProjectContext(), control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(backend, null, new PlainHost(), "s1", "p1", resources);
            session.activate();
        }
    }

    private static com.aresstack.askai.research.store.ResearchProjectContext tempProjectContext() {
        try {
            File dir = java.nio.file.Files.createTempDirectory("askai-derived-actions-test").toFile();
            return com.aresstack.askai.research.store.ResearchProjectContext.open("s1", dir);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static final class RecordingBackend implements ResearchSessionBackend {
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

        public boolean canExecute(ResearchSessionHandle handle,
                                  com.aresstack.askai.research.state.ResearchCommandType command) {
            return false;
        }

        public void executeCommand(ResearchSessionHandle handle,
                                   com.aresstack.askai.research.state.ResearchCommandType command) {
        }

        public void submitPrompt(ResearchSessionHandle handle, ResearchPrompt prompt) {
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

    private static final class PlainHost implements AgentHostContext {
        public UiExecutor getUiExecutor() {
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
                }
            };
        }
    }
}
