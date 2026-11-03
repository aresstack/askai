package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionContext;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionRequest;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandInvocation;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.backend.FakeResearchSessionBackend;
import com.aresstack.askai.research.backend.ManualResearchScheduler;
import com.aresstack.askai.research.backend.ResearchClock;
import com.aresstack.askai.research.backend.ResearchIdGenerator;

import org.junit.Test;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

/**
 * Deterministic tests for the new agent-model research session: it drives the existing backend, pushes activity
 * to the shared conversation sink (never its own surface), and its slash commands translate to typed controls.
 */
public class ResearchAgentSessionTest {

    private static ResearchIdGenerator ids() {
        final AtomicInteger c = new AtomicInteger();
        return new ResearchIdGenerator() {
            public String newId() {
                return "id-" + c.incrementAndGet();
            }
        };
    }

    private static ResearchClock clock() {
        return new ResearchClock() {
            public long now() {
                return 1_000L;
            }
        };
    }

    private static final class Fixture {
        final ManualResearchScheduler scheduler = new ManualResearchScheduler();
        final FakeResearchSessionBackend backend =
                new FakeResearchSessionBackend(scheduler, clock(), ids(), 10L);
        final RecordingSink sink = new RecordingSink();
        final ResearchAgentSession session =
                new ResearchAgentSession(backend, null, new FakeHost(sink), "s1", "p1");
    }

    @Test
    public void factoryCreatesIsolatedSessions() {
        ResearchAgentSessionFactory factory = new ResearchAgentSessionFactory();
        FakeHost host = new FakeHost(new RecordingSink());
        AgentSession a = factory.create(
                new com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest("a", "p", null), host);
        AgentSession b = factory.create(
                new com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest("b", "p", null), host);
        assertNotSame(a, b);
        assertNotSame(a.getChatTarget(), b.getChatTarget());
        a.close();
        b.close();
    }

    @Test
    public void closeIsIdempotent() {
        Fixture f = new Fixture();
        f.session.activate();
        f.session.close();
        f.session.close(); // must not throw
    }

    @Test
    public void submitTextRoutesUserAndAssistantToTheSharedSink() {
        Fixture f = new Fixture();
        f.session.activate();
        f.sink.assistantMessages.clear();
        f.session.getChatTarget().submitText("investigate pf4j"); // the question starts the run
        assertTrue(f.sink.userMessages.contains("investigate pf4j"));
        f.session.getChatTarget().submitText("look into caching"); // follow-up gets a visible reply
        assertTrue(f.sink.userMessages.contains("look into caching"));
        assertFalse(f.sink.assistantMessages.isEmpty());
    }

    @Test
    public void runReachesApprovalGateAndStateReflectsIt() {
        Fixture f = new Fixture();
        f.session.activate();
        // Creation is passive: the user's first question starts the run.
        f.session.getChatTarget().submitText("investigate pf4j");
        f.scheduler.runUntilIdle(); // → Sources approval gate (#43: evidence review inside Sources)
        assertEquals("RESEARCH", f.session.getState().getPhaseLabel());
        assertTrue(f.session.getState().hasPendingApproval());
        assertTrue(f.sink.approvals > 0);
        assertTrue(f.session.getState().getAllowedCommandNames().contains("approve"));
    }

    @Test
    public void chatTargetAvailabilityFollowsRunState() {
        Fixture f = new Fixture();
        assertEquals(SubmissionAvailability.UNAVAILABLE, f.session.getChatTarget().getAvailability());
        f.session.activate(); // passive: SCOPING/NEW until the first question
        f.session.getChatTarget().submitText("investigate pf4j"); // → SCOPING/RUNNING
        assertEquals(SubmissionAvailability.BUSY, f.session.getChatTarget().getAvailability());
        f.scheduler.runUntilIdle(); // → WAITING for approval
        assertEquals(SubmissionAvailability.AVAILABLE, f.session.getChatTarget().getAvailability());
    }

    @Test
    public void executeCommandHonoursTheStateMachineGate() {
        // Issue #33: the structured command surface replaces the removed hardcoded /approve twin. A state
        // command that is not allowed yet is REJECTED with the reason; once the gate is reached it runs.
        Fixture f = new Fixture();
        f.session.activate();
        f.session.getChatTarget().submitText("investigate pf4j"); // the question starts the run

        String early = f.session.executeCommand("approve", "");
        assertTrue(early, early.startsWith("rejected:"));

        f.scheduler.runUntilIdle();
        String ok = f.session.executeCommand("approve", "");
        assertTrue(ok, ok.startsWith("handled:"));
        f.scheduler.runUntilIdle();
        assertEquals("OUTLINE", f.session.getState().getPhaseLabel());
    }

    @Test
    public void executeCommandRejectsUnknownCommandsAndSendsPlainTextAsChat() {
        Fixture f = new Fixture();
        f.session.activate();
        String unknown = f.session.executeCommand("frobnicate", "");
        assertTrue(unknown, unknown.startsWith("rejected: unknown command"));
        assertTrue("the rejection lists the valid commands", unknown.contains("search <query>"));
        String chat = f.session.executeCommand(null, "investigate pf4j");
        assertTrue(chat, chat.startsWith("handled: message sent"));
    }

    @Test
    public void describeSessionStateReportsPhaseCommandsButtonsAndSuggestions() {
        Fixture f = new Fixture();
        f.session.activate();
        String state = f.session.describeSessionState();
        assertTrue(state, state.contains("phase=scoping"));
        assertTrue("the valid commands are listed", state.contains("commands: search <query>"));
        assertTrue("buttons and suggestions lines exist", state.contains("buttons:")
                && state.contains("suggestions:"));
    }

    @Test
    public void openCommandCompletesArtifactIdsAndOpensTab() {
        Fixture f = new Fixture();
        f.session.activate();
        FixedContext ctx = new FixedContext(f.session);
        ChatCommandContribution open = command("open");

        // "outline" has no tab of its own since Outline and Document merged (8aff6592) — "document" does.
        CommandCompletionResult completions =
                open.complete(new CommandCompletionRequest("open", Collections.<String>emptyList(), "doc"), ctx);
        boolean suggestsDocument = false;
        for (com.aresstack.askai.plugin.api.agent.command.CommandCompletion c : completions.getCompletions()) {
            if ("document".equals(c.getInsertionText())) {
                suggestsDocument = true;
            }
        }
        assertTrue("expected 'document' completion", suggestsDocument);

        CommandExecutionResult opened = open.execute(
                new CommandInvocation("open", java.util.Arrays.asList("document"), "/open document"), ctx);
        assertEquals(CommandExecutionResult.Status.HANDLED, opened.getStatus());
        assertEquals("document", ctx.openedArtifactId);

        CommandExecutionResult bad = open.execute(
                new CommandInvocation("open", java.util.Arrays.asList("nope"), "/open nope"), ctx);
        assertEquals(CommandExecutionResult.Status.REJECTED, bad.getStatus());
    }

    @Test
    public void researchSnapshotReflectsDomainAndStateListenerFires() {
        Fixture f = new Fixture();
        final int[] stateChanges = {0};
        f.session.addStateListener(new Runnable() {
            public void run() {
                stateChanges[0]++;
            }
        });
        f.session.activate();
        f.session.getChatTarget().submitText("investigate pf4j"); // the question starts the run
        f.scheduler.runUntilIdle(); // → Sources waiting_approval (#43)
        com.aresstack.askai.research.agent.ResearchStateSnapshot snapshot =
                f.session.currentResearchSnapshot();
        assertEquals(com.aresstack.askai.research.state.oo.ResearchStateIds.RESEARCH,
                snapshot.getCurrentPhaseId());
        assertEquals(com.aresstack.askai.research.state.oo.ResearchStateIds.WAITING_APPROVAL,
                snapshot.getCurrentStateId());
        assertTrue(snapshot.getAllowedCommands().contains(
                com.aresstack.askai.research.state.ResearchCommandType.APPROVE_EVIDENCE));
        assertTrue("state listener should have fired", stateChanges[0] > 0);
    }

    @Test
    public void searchCommandRunsAPhaseIndependentManualWebSearch() {
        Fixture f = new Fixture();
        f.session.activate();
        final List<String> searched = new ArrayList<String>();
        f.session.setManualWebSearchPort(new com.aresstack.askai.research.search.ManualWebSearchPort() {
            public com.aresstack.askai.research.search.ManualWebSearchHandle search(
                    com.aresstack.askai.research.search.ManualWebSearchRequest request) {
                searched.add(request.getQuery());
                return new com.aresstack.askai.research.search.ManualWebSearchHandle() {
                    public String getRequestId() {
                        return "req-1";
                    }

                    public void cancel() {
                    }
                };
            }
        });

        // "/search <free text>" runs the manual search over everything after the command name.
        CommandExecutionResult ok = command("search").execute(
                new CommandInvocation("search",
                        java.util.Arrays.asList("neuroscience", "wearable", "technology", "applications"),
                        "/search neuroscience wearable technology applications"),
                new FixedContext(f.session));
        assertEquals(CommandExecutionResult.Status.HANDLED, ok.getStatus());
        assertEquals(1, searched.size());
        assertEquals("neuroscience wearable technology applications", searched.get(0));

        // An empty query is rejected and starts no search.
        CommandExecutionResult empty = command("search").execute(
                new CommandInvocation("search", Collections.<String>emptyList(), "/search"),
                new FixedContext(f.session));
        assertEquals(CommandExecutionResult.Status.REJECTED, empty.getStatus());
        assertEquals(1, searched.size());
    }

    private static ChatCommandContribution command(String name) {
        for (ChatCommandContribution c : ResearchChatCommands.all()) {
            if (c.getDescriptor().getName().equals(name)) {
                return c;
            }
        }
        throw new AssertionError("no such command: " + name);
    }

    // ------------------------------------------------------------------ fakes

    private static final class FixedContext implements AgentSessionContext {
        private final AgentSession session;
        String openedArtifactId;

        FixedContext(AgentSession session) {
            this.session = session;
        }

        public AgentSession getSession() {
            return session;
        }

        public void openArtifact(String artifactId) {
            this.openedArtifactId = artifactId;
        }

        public UiExecutor getUiExecutor() {
            return new InlineUi();
        }
    }

    private static final class InlineUi implements UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            runnable.run();
        }

        public void assertUiThread() {
        }
    }

    private static final class FakeHost implements AgentHostContext {
        private final AgentConversationSink sink;

        FakeHost(AgentConversationSink sink) {
            this.sink = sink;
        }

        public UiExecutor getUiExecutor() {
            return new InlineUi();
        }

        public ThemeService getThemeService() {
            return new ThemeService() {
                public Color color(String key, Color fallback) {
                    return fallback;
                }

                public boolean isDark() {
                    return false;
                }

                public void addThemeChangeListener(Runnable listener) {
                }

                public void removeThemeChangeListener(Runnable listener) {
                }
            };
        }

        public MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public NotificationService getNotificationService() {
            return new NotificationService() {
                public void notify(Severity severity, String message) {
                }
            };
        }

        public WorkspaceStateStore getStateStore() {
            return null;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return new File(System.getProperty("java.io.tmpdir"));
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(System.getProperty("java.io.tmpdir"));
                }
            };
        }

        public AgentConversationSink getConversationSink() {
            return sink;
        }
    }

    private static final class RecordingSink implements AgentConversationSink {
        final List<String> userMessages = new ArrayList<String>();
        final List<String> assistantMessages = new ArrayList<String>();
        int approvals;
        int problems;

        public void appendUserMessage(String messageId, String markdown) {
            userMessages.add(markdown);
        }

        public void appendAssistantMessage(String messageId, String markdown) {
            assistantMessages.add(markdown);
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
            approvals++;
        }

        public void showProblem(String problemId, String publicMessage) {
            problems++;
        }
    }
}
