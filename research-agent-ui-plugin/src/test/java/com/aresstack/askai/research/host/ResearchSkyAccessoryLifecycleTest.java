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
 * Hotfix 4.1 regression: the out-of-scope sky over a REAL productive session, along the exact
 * lifecycle the GUI takes — fresh session, exclusions added, a phase change, and (the critical
 * case) a NEW session over the same project directory with PERSISTED exclusions, i.e. a restored
 * research chat. The sky must appear whenever the session is in SCOPING with ≥1 exclusion, and it
 * must fully retreat (inset 0, no claim) otherwise. No UI heuristics: everything is read from the
 * session's own snapshot and scope draft.
 */
public class ResearchSkyAccessoryLifecycleTest {

    @Test
    public void aScopingSessionWithAnExclusionShowsTheSkyWithAPositiveInset() throws Exception {
        Fx fx = new Fx(tempDir());
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        assertNull("the exclusion is recorded through the one scope path",
                fx.session.addScopeExclusion("Thema A"));

        JComponent sky = createSky(fx);
        assertTrue("SCOPING + exclusions → the sky is visible", sky.isVisible());
        layout(sky);
        assertTrue("collapsed by default, the bar still reserves top room", insetOf(sky) > 0);
        openSky(sky);
        assertTrue("opened, the cloud for the exclusion is rendered",
                tooltipsOf(sky).toString().contains("Thema A"));
    }

    @Test
    public void aRestoredSessionWithPersistedExclusionsShowsTheSkyAgain() throws Exception {
        File project = tempDir();
        Fx first = new Fx(project);
        first.session.dispatch(ResearchCommandType.START, null);
        completeTurn(first, 1L);
        assertNull(first.session.addScopeExclusion("Thema A"));

        // The restored chat: a FRESH session over the SAME project directory — the draft comes
        // from disk, not from anything the first session left in memory.
        Fx restored = new Fx(project);
        assertEquals("the restored session starts in scoping", ResearchStateIds.SCOPING,
                restored.session.currentResearchSnapshot().getCurrentPhaseId());
        assertEquals("the persisted exclusion is loaded",
                java.util.Collections.singletonList("Thema A"),
                restored.session.currentScopeDraft().getExclusions());

        JComponent sky = createSky(restored);
        assertTrue("a restored SCOPING chat with saved exclusions shows its sky", sky.isVisible());
        layout(sky);
        assertTrue(insetOf(sky) > 0);
        openSky(sky);
        assertTrue(tooltipsOf(sky).toString().contains("Thema A"));
    }

    @Test
    public void withoutExclusionsTheScopingSkyStillOffersTheAddCloud() throws Exception {
        // A fresh scoping chat has zero exclusions — the sky must NOT be blank, or the first
        // exclusion could never be added manually. It shows exactly the + Hinzufügen cloud.
        Fx fx = new Fx(tempDir());
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);

        JComponent sky = createSky(fx);
        assertTrue("the scoping sky is visible even with zero exclusions", sky.isVisible());
        layout(sky);
        assertTrue("the collapsed bar reserves its slim top room", insetOf(sky) > 0);
        assertTrue("the bar zone is interactive", sky.contains(20, 15));
        openSky(sky);
        assertTrue("opened, the + Add cloud is offered",
                tooltipsOf(sky).toString().contains("Add exclusion"));
    }

    @Test
    public void leavingScopingKeepsTheBlacklistSky() throws Exception {
        Fx fx = new Fx(tempDir());
        fx.session.dispatch(ResearchCommandType.START, null);
        completeTurn(fx, 1L);
        assertNull(fx.session.addScopeExclusion("Thema A"));

        JComponent sky = createSky(fx);
        layout(sky);
        assertTrue(sky.isVisible());
        assertTrue(insetOf(sky) > 0);

        ConceptTestSupport.makeSubmittable(fx.session);
        assertTrue("the phase change goes through the machine",
                fx.session.dispatch(ResearchCommandType.SUBMIT_SCOPE, null).isAccepted());
        assertEquals(ResearchStateIds.RESEARCH,
                fx.session.currentResearchSnapshot().getCurrentPhaseId());
        // PR 12: the blacklist filters in every phase, so its sky stays in every phase
        assertTrue("outside Phase 1 the sky stays", sky.isVisible());
    }

    // ------------------------------------------------------------------ helpers

    /** The accessory exactly as the host builds it (contribution + inline-EDT context). */
    private static JComponent createSky(final Fx fx) throws Exception {
        final java.util.concurrent.atomic.AtomicReference<JComponent> ref =
                new java.util.concurrent.atomic.AtomicReference<JComponent>();
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ComposerAccessory accessory = new ResearchPhaseAccessoryContribution()
                        .create(new FakeComposerContext(fx.session));
                assertEquals(ComposerAccessory.Placement.TRANSCRIPT_OVERLAY,
                        accessory.getPlacement());
                ref.set(accessory.getComponent());
            }
        });
        return ref.get();
    }

    /** Open the collapsed status bar like a user: a real mouse press on the WHOLE bar. */
    private static void openSky(final JComponent sky) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                Component bar = findByName(sky, "sky.statusBar");
                assertTrue("the collapsed status bar exists", bar != null);
                bar.dispatchEvent(new java.awt.event.MouseEvent(bar,
                        java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                        0, 5, 5, 1, false));
            }
        });
        layout(sky);
    }

    private static Component findByName(Component root, String name) {
        if (name.equals(root.getName())) {
            return root;
        }
        if (root instanceof Container) {
            for (Component child : ((Container) root).getComponents()) {
                Component found = findByName(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void layout(final JComponent sky) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                sky.setSize(900, 600);
                sky.doLayout();
            }
        });
    }

    private static int insetOf(JComponent sky) {
        Object value = sky.getClientProperty(ComposerAccessory.TRANSCRIPT_TOP_INSET_PROPERTY);
        return value instanceof Integer ? (Integer) value : 0;
    }

    /** All tooltips of VISIBLE components in the sky's tree — the cloud chips carry theirs. */
    private static List<String> tooltipsOf(Component component) {
        List<String> tooltips = new ArrayList<String>();
        collectTooltips(component, tooltips);
        return tooltips;
    }

    private static void collectTooltips(Component component, List<String> into) {
        if (!component.isVisible()) {
            return;
        }
        if (component instanceof JComponent) {
            String tooltip = ((JComponent) component).getToolTipText();
            if (tooltip != null) {
                into.add(tooltip);
            }
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                collectTooltips(child, into);
            }
        }
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
                            return new com.aresstack.askai.research.sources
                                    .InMemoryResearchSourceRepository();
                        }

                        public String acceptCapture(String captureId) {
                            return null;
                        }
                    });
            control.open();
            resources = new ProductiveResearchSessionResources("s1",
                    new OoResearchStateMachine("s1"), null, null, null,
                    com.aresstack.askai.research.store.ResearchProjectContext.open(
                            "s1", projectDir),
                    control, null, null, null);
            holder[0] = resources;
            session = new ResearchAgentSession(new NoopBackend(), null, new PlainHost(),
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
