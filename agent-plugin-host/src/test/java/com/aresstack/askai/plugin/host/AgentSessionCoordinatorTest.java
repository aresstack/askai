package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.AgentStateSnapshot;
import com.aresstack.askai.plugin.api.agent.ChatSubmissionTarget;
import com.aresstack.askai.plugin.api.agent.SubmissionAvailability;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult;
import com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/** The route matrix + session lifecycle, tested Swing-free with fake agent extensions/sessions. */
public class AgentSessionCoordinatorTest {

    private final Map<String, FakeExtension> registry = new HashMap<String, FakeExtension>();

    private AgentSessionCoordinator coordinator() {
        registry.put("agent.a", new FakeExtension("agent.a"));
        registry.put("agent.b", new FakeExtension("agent.b"));
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return registry.get(agentId);
                    }
                };
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return null; // the fake session ignores its host context
                    }
                };
        return new AgentSessionCoordinator(resolver, provider, new InlineUiExecutor());
    }

    private AgentSessionCoordinator coordinator(AgentSessionCoordinator.SessionScopeProvider scope) {
        registry.put("agent.a", new FakeExtension("agent.a"));
        registry.put("agent.b", new FakeExtension("agent.b"));
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return registry.get(agentId);
                    }
                };
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return null;
                    }
                };
        return new AgentSessionCoordinator(resolver, provider, new InlineUiExecutor(), scope);
    }

    @Test
    public void theActiveAgentsComposerAccessoriesAreExposedAndEmptyWithoutAnAgent() {
        AgentSessionCoordinator c = coordinator();
        assertTrue("no accessories without an active agent", c.getActiveComposerAccessories().isEmpty());
        c.setActiveAgent("agent.a");
        assertEquals(1, c.getActiveComposerAccessories().size());
        assertEquals("fake.accessory", c.getActiveComposerAccessories().get(0).getId());
    }

    @Test
    public void differentTabScopesGetDistinctSessionsAndReuseWithinAScope() {
        final String[] scope = {"tab-A"};
        AgentSessionCoordinator c = coordinator(new AgentSessionCoordinator.SessionScopeProvider() {
            public String currentScope() {
                return scope[0];
            }
        });
        c.setActiveAgent("agent.a");                     // tab A → session 1
        FakeSession tabA = registry.get("agent.a").lastSession;
        scope[0] = "tab-B";
        c.setActiveAgent("agent.a");                     // tab B, SAME agent → a DISTINCT session
        FakeSession tabB = registry.get("agent.a").lastSession;
        assertNotSame("two tabs of the same agent get distinct sessions", tabA, tabB);
        assertEquals(2, registry.get("agent.a").created);
        scope[0] = "tab-A";
        c.setActiveAgent("agent.a");                     // back to tab A → reuse its own session
        assertSame("returning to tab A reactivates ITS session", tabA, c.getActiveSession());
        assertEquals("no new session created when returning to a tab", 2, registry.get("agent.a").created);
    }

    @Test
    public void aPromptGoesToTheVisibleTabsSessionEvenIfAnotherTabsSessionIsStillActive() {
        final String[] scope = {"tab-A"};
        AgentSessionCoordinator c = coordinator(new AgentSessionCoordinator.SessionScopeProvider() {
            public String currentScope() {
                return scope[0];
            }
        });
        c.setActiveAgent("agent.a");
        FakeSession tabA = registry.get("agent.a").lastSession;
        scope[0] = "tab-B"; // the user now looks at tab B, but nobody re-activated the agent for it
        c.submitText("hi");
        FakeSession tabB = registry.get("agent.a").lastSession;
        assertNotSame(tabA, tabB);
        assertTrue("tab A's (invisible) session must NOT swallow the prompt", tabA.target.submitted.isEmpty());
        assertEquals(Collections.singletonList("hi"), tabB.target.submitted);
    }

    @Test
    public void anUndeliverablePromptStaysVisibleWithAReason() {
        final List<String> users = new ArrayList<String>();
        final List<String> bubbles = new ArrayList<String>();
        registry.put("agent.a", new FakeExtension("agent.a"));
        AgentSessionCoordinator c = new AgentSessionCoordinator(
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return registry.get(agentId);
                    }
                },
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return new SinkHost(new RecordingSink(bubbles));
                    }
                }, new InlineUiExecutor());
        RecordingSink.lastUsers.clear();
        c.setActiveAgent("agent.a");
        FakeSession session = registry.get("agent.a").lastSession;
        session.target.availability = SubmissionAvailability.UNAVAILABLE;
        c.submitText("hi");
        assertTrue(session.target.submitted.isEmpty());
        assertEquals(Collections.singletonList("hi"), RecordingSink.lastUsers);
        assertEquals(1, bubbles.size());
        assertTrue(bubbles.get(0).contains("could not be delivered"));
    }

    @Test
    public void closingATabScopeEndsOnlyThatTabsSessionsAndAFreshTabStartsFresh() throws Exception {
        final String[] scope = {"tab-A"};
        AgentSessionCoordinator c = coordinator(new AgentSessionCoordinator.SessionScopeProvider() {
            public String currentScope() {
                return scope[0];
            }
        });
        c.setActiveAgent("agent.a");
        FakeSession tabA = registry.get("agent.a").lastSession;
        scope[0] = "tab-B";
        c.setActiveAgent("agent.a");
        FakeSession tabB = registry.get("agent.a").lastSession;

        c.closeSessionsForScope("tab-A"); // tab A closes: detach on this thread, close off-EDT
        for (int i = 0; i < 100 && tabA.closeCount == 0; i++) {
            Thread.sleep(20);
        }
        assertEquals("tab A's session was really closed (off-EDT)", 1, tabA.closeCount);
        assertEquals("tab B's session is untouched", 0, tabB.closeCount);

        // A fresh tab A must start a NEW session, never resume the closed one.
        scope[0] = "tab-A";
        c.setActiveAgent("agent.a");
        assertNotSame("a fresh tab A gets a brand-new session", tabA, c.getActiveSession());
        assertEquals(3, registry.get("agent.a").created);

        // Tab B still has its live session (reused, not recreated).
        scope[0] = "tab-B";
        c.setActiveAgent("agent.a");
        assertSame(tabB, c.getActiveSession());
        assertEquals("tab B not recreated", 3, registry.get("agent.a").created);
    }

    private static final class InlineUiExecutor
            implements com.aresstack.askai.plugin.api.service.UiExecutor {
        public boolean isUiThread() {
            return true;
        }

        public void execute(Runnable runnable) {
            runnable.run();
        }

        public void assertUiThread() {
        }
    }

    @Test
    public void inactiveByDefault() {
        AgentSessionCoordinator c = coordinator();
        assertFalse(c.isActive());
        assertEquals(SubmissionAvailability.UNAVAILABLE, c.getAvailability());
    }

    @Test
    public void activatingCreatesAndActivatesExactlyOneSession() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        assertTrue(c.isActive());
        FakeSession session = registry.get("agent.a").lastSession;
        assertEquals(1, session.activateCount);
        assertEquals(1, registry.get("agent.a").created);
    }

    @Test
    public void returningToTheSameAgentReusesTheSession() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession first = registry.get("agent.a").lastSession;
        c.deactivateActive();               // back to Yapping
        c.setActiveAgent("agent.a");        // back to Questing, same agent
        assertSame(first, registry.get("agent.a").lastSession);
        assertEquals(1, registry.get("agent.a").created); // not recreated
        assertEquals(0, first.closeCount);                // kept, not closed
    }

    @Test
    public void switchingAgentDeactivatesOldAndActivatesNewAtomically() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        c.setActiveAgent("agent.b");
        FakeSession b = registry.get("agent.b").lastSession;
        assertEquals(1, a.deactivateCount);
        assertEquals(0, a.closeCount);      // old session kept
        assertTrue(c.isActive());
        assertEquals(1, b.activateCount);
        assertEquals("agent.b", c.getActiveAgentId());
    }

    @Test
    public void deactivateRoutesBackToYappingButKeepsSession() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        assertFalse(c.isActive());
        assertEquals(SubmissionAvailability.UNAVAILABLE, c.getAvailability());
        FakeSession a = registry.get("agent.a").lastSession;
        assertEquals(1, a.deactivateCount);
        assertEquals(0, a.closeCount);
    }

    @Test
    public void submitAndStopRouteToTheActiveTargetOnly() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.submitText("hello");
        c.stop();
        FakeSession a = registry.get("agent.a").lastSession;
        assertEquals(Collections.singletonList("hello"), a.target.submitted);
        assertEquals(1, a.target.stopCount);
    }

    @Test
    public void closeAgentClosesSessionAndFallsBack() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        c.closeAgent("agent.a");
        assertEquals(1, a.closeCount);
        assertFalse(c.isActive());
    }

    @Test
    public void retainOnlyClosesRemovedPluginsSessions() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        c.setActiveAgent("agent.b");
        FakeSession a = registry.get("agent.a").lastSession;
        FakeSession b = registry.get("agent.b").lastSession;
        c.retainOnly(Arrays.asList("agent.b"));   // agent.a's plugin disabled
        assertEquals(1, a.closeCount);
        assertEquals(0, b.closeCount);
        assertTrue(c.isActive());                 // agent.b still active
    }

    @Test
    public void shutdownClosesEverything() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        c.setActiveAgent("agent.b");
        c.shutdown();
        assertFalse(c.isActive());
        assertEquals(1, registry.get("agent.a").lastSession.closeCount);
        assertEquals(1, registry.get("agent.b").lastSession.closeCount);
    }

    @Test
    public void unresolvableAgentDeactivates() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.setActiveAgent("does.not.exist");
        assertFalse(c.isActive());
    }

    @Test
    public void aStartupFailureIsReportedAsAChatBubbleAndRetriesCleanly() {
        final List<String> bubbles = new ArrayList<String>();
        final RecordingSink sink = new RecordingSink(bubbles);
        final boolean[] failNext = {true};
        final FakeSession[] good = {null};
        // An extension whose factory THROWS on the first attempt (a mandatory model not selected), then works.
        final AgentPluginExtension flaky = new AgentPluginExtension() {
            public AgentPluginDescriptor getAgentDescriptor() {
                return AgentPluginDescriptor.builder().id("agent.x").displayName("agent.x").version("1").build();
            }

            public AgentSessionFactory getSessionFactory() {
                return new AgentSessionFactory() {
                    public AgentSession create(AgentSessionCreationRequest request, AgentHostContext host) {
                        if (failNext[0]) {
                            throw new IllegalStateException(
                                    "The productive research backend could not be started",
                                    new java.io.IOException("No reranker model is selected. Choose one in "
                                            + "AskAI → Configuration → AI models"));
                        }
                        good[0] = new FakeSession();
                        return good[0];
                    }
                };
            }

            public List<ChatCommandContribution> getChatCommands() {
                return Collections.emptyList();
            }

            public List<ArtifactViewContribution> getArtifactViews() {
                return Collections.emptyList();
            }
        };
        AgentSessionCoordinator.AgentExtensionResolver resolver =
                new AgentSessionCoordinator.AgentExtensionResolver() {
                    public AgentPluginExtension resolve(String agentId) {
                        return "agent.x".equals(agentId) ? flaky : null;
                    }
                };
        AgentSessionCoordinator.AgentHostContextProvider provider =
                new AgentSessionCoordinator.AgentHostContextProvider() {
                    public AgentHostContext create(String agentId, String sessionInstanceId) {
                        return new SinkHost(sink);
                    }
                };
        AgentSessionCoordinator c = new AgentSessionCoordinator(resolver, provider, new InlineUiExecutor());

        // First attempt fails: no EDT crash, no active session, and the user is told the actionable reason.
        c.setActiveAgent("agent.x");
        assertFalse("a failed startup leaves no active session", c.isActive());
        assertEquals(1, bubbles.size());
        assertTrue("the deepest, actionable reason reaches the user",
                bubbles.get(0).contains("No reranker model is selected"));

        // The user fixes the configuration; retrying now starts a fresh session cleanly.
        failNext[0] = false;
        c.setActiveAgent("agent.x");
        assertTrue(c.isActive());
        assertSame(good[0], c.getActiveSession());
        assertEquals("no extra bubble on the successful retry", 1, bubbles.size());
    }

    @Test
    public void availabilityComesFromTheActiveTarget() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        a.target.availability = SubmissionAvailability.BUSY;
        assertEquals(SubmissionAvailability.BUSY, c.getAvailability());
    }

    @Test
    public void changeListenerFiresOnActivationAndDeactivation() {
        AgentSessionCoordinator c = coordinator();
        final int[] count = {0};
        c.addChangeListener(new Runnable() {
            public void run() {
                count[0]++;
            }
        });
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        assertTrue(count[0] >= 2);
    }

    // ------------------------------------------------------------------ command registry

    @Test
    public void noAgentMeansNoCommandsAndSlashIsPlainText() {
        AgentSessionCoordinator c = coordinator();
        assertTrue(c.getCommands().isEmpty());
        assertFalse(c.isCommandLine("/status"));
        assertEquals(CommandExecutionResult.Status.UNKNOWN, c.execute("/status").getStatus());
    }

    @Test
    public void activeAgentExposesItsCommands() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        assertEquals(2, c.getCommands().size());
        assertTrue(c.isCommandLine("/status"));
    }

    @Test
    public void nameStageCompletionFiltersByPrefix() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        CommandCompletionResult all = c.complete("/", 1);
        assertEquals(2, all.getCompletions().size());
        CommandCompletionResult filtered = c.complete("/st", 3);
        assertEquals(1, filtered.getCompletions().size());
        assertEquals("/status", filtered.getCompletions().get(0).getDisplayText());
        assertEquals("/status", filtered.getCompletions().get(0).getInsertionText());
    }

    @Test
    public void executeStatusReturnsHandledWithoutTouchingTheChatTarget() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        CommandExecutionResult result = c.execute("/status");
        assertEquals(CommandExecutionResult.Status.HANDLED, result.getStatus());
        assertEquals("status:ok", result.getMessage());
        // A slash command must NOT be submitted as a normal prompt.
        assertTrue(registry.get("agent.a").lastSession.target.submitted.isEmpty());
    }

    @Test
    public void unknownCommandIsReported() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        assertEquals(CommandExecutionResult.Status.UNKNOWN, c.execute("/nope").getStatus());
    }

    @Test
    public void openCommandCompletesArtifactIdsAsFullLines() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        CommandCompletionResult result = c.complete("/open out", 9);
        assertEquals(1, result.getCompletions().size());
        // Reconstructed to a full replacement line.
        assertEquals("/open outline", result.getCompletions().get(0).getInsertionText());
        assertEquals("outline", result.getCompletions().get(0).getDisplayText());
    }

    @Test
    public void openCommandInvokesArtifactOpener() {
        AgentSessionCoordinator c = coordinator();
        final String[] opened = {null};
        c.setArtifactOpener(new AgentSessionCoordinator.ArtifactOpener() {
            public void open(String artifactId) {
                opened[0] = artifactId;
            }
        });
        c.setActiveAgent("agent.a");
        CommandExecutionResult result = c.execute("/open outline");
        assertEquals(CommandExecutionResult.Status.HANDLED, result.getStatus());
        assertEquals("outline", opened[0]);
    }

    @Test
    public void switchingAgentReplacesTheCommandContext() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        FakeSession a = registry.get("agent.a").lastSession;
        c.setActiveAgent("agent.b");
        // Execution now targets agent.b's session, not agent.a's.
        c.execute("/status");
        assertTrue(a.target.submitted.isEmpty());
        assertEquals("agent.b", c.getActiveAgentId());
    }

    @Test
    public void deactivateClearsCommands() {
        AgentSessionCoordinator c = coordinator();
        c.setActiveAgent("agent.a");
        c.deactivateActive();
        assertTrue(c.getCommands().isEmpty());
        assertFalse(c.isCommandLine("/status"));
    }

    // ------------------------------------------------------------------ fakes

    private static final class FakeExtension implements AgentPluginExtension {
        private final String id;
        int created;
        FakeSession lastSession;

        FakeExtension(String id) {
            this.id = id;
        }

        public AgentPluginDescriptor getAgentDescriptor() {
            return AgentPluginDescriptor.builder().id(id).displayName(id).version("1").build();
        }

        public AgentSessionFactory getSessionFactory() {
            return new AgentSessionFactory() {
                public AgentSession create(AgentSessionCreationRequest request, AgentHostContext hostContext) {
                    created++;
                    lastSession = new FakeSession();
                    return lastSession;
                }
            };
        }

        public List<ChatCommandContribution> getChatCommands() {
            return Arrays.<ChatCommandContribution>asList(new StatusCommand(), new OpenCommand());
        }

        public List<ArtifactViewContribution> getArtifactViews() {
            return Collections.emptyList();
        }

        public List<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
                getComposerAccessories() {
            return Collections.<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
                    singletonList(new FakeAccessory());
        }
    }

    /** A fake composer accessory contribution used to prove the coordinator exposes the active agent's list. */
    private static final class FakeAccessory
            implements com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution {
        public String getId() {
            return "fake.accessory";
        }

        public boolean supports(AgentSession session) {
            return true;
        }

        public com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory create(
                com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContext context) {
            return null;
        }
    }

    /** Fake no-arg command: /status. */
    private static final class StatusCommand implements ChatCommandContribution {
        public com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor getDescriptor() {
            return com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor.of(
                    "status", "Show status");
        }

        public com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult complete(
                com.aresstack.askai.plugin.api.agent.command.CommandCompletionRequest request,
                com.aresstack.askai.plugin.api.agent.AgentSessionContext context) {
            return com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult.empty();
        }

        public com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult execute(
                com.aresstack.askai.plugin.api.agent.command.CommandInvocation invocation,
                com.aresstack.askai.plugin.api.agent.AgentSessionContext context) {
            return com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult.handled("status:ok");
        }
    }

    /** Fake arg command: /open <artifact>, completes ids and calls context.openArtifact on execute. */
    private static final class OpenCommand implements ChatCommandContribution {
        public com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor getDescriptor() {
            return com.aresstack.askai.plugin.api.agent.command.ChatCommandDescriptor.of(
                    "open", "Open artifact", "/open <artifact>",
                    new com.aresstack.askai.plugin.api.agent.command.CommandArgumentDescriptor(
                            "artifact", "id", true));
        }

        public com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult complete(
                com.aresstack.askai.plugin.api.agent.command.CommandCompletionRequest request,
                com.aresstack.askai.plugin.api.agent.AgentSessionContext context) {
            List<com.aresstack.askai.plugin.api.agent.command.CommandCompletion> out =
                    new ArrayList<com.aresstack.askai.plugin.api.agent.command.CommandCompletion>();
            for (String id : new String[]{"outline", "concept"}) {
                if (id.startsWith(request.getPartialToken())) {
                    out.add(new com.aresstack.askai.plugin.api.agent.command.CommandCompletion(
                            id, id, "artifact",
                            com.aresstack.askai.plugin.api.agent.command.CompletionKind.ARGUMENT_VALUE));
                }
            }
            return new com.aresstack.askai.plugin.api.agent.command.CommandCompletionResult(out);
        }

        public com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult execute(
                com.aresstack.askai.plugin.api.agent.command.CommandInvocation invocation,
                com.aresstack.askai.plugin.api.agent.AgentSessionContext context) {
            String id = invocation.getArgument(0);
            if (id.isEmpty()) {
                return com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult.rejected("usage");
            }
            context.openArtifact(id);
            return com.aresstack.askai.plugin.api.agent.command.CommandExecutionResult.handled("opened " + id);
        }
    }

    private static final class FakeSession implements AgentSession {
        final FakeTarget target = new FakeTarget();
        int activateCount;
        int deactivateCount;
        volatile int closeCount; // closeSessionsForScope closes on a background thread — read across threads

        public ChatSubmissionTarget getChatTarget() {
            return target;
        }

        public List<AgentArtifact> getArtifacts() {
            return Collections.emptyList();
        }

        public com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore getArtifactStore() {
            return null;
        }

        public AgentStateSnapshot getState() {
            return AgentStateSnapshot.builder().build();
        }

        public void activate() {
            activateCount++;
        }

        public void deactivate() {
            deactivateCount++;
        }

        public void close() {
            closeCount++;
        }
    }

    /** Records assistant bubbles so the startup-failure mapping can be asserted. */
    private static final class RecordingSink
            implements com.aresstack.askai.plugin.api.agent.AgentConversationSink {
        private final List<String> bubbles;

        RecordingSink(List<String> bubbles) {
            this.bubbles = bubbles;
        }

        static List<String> lastUsers = new ArrayList<String>();

        public void appendUserMessage(String messageId, String markdown) {
            lastUsers.add(markdown);
        }

        public void appendAssistantMessage(String messageId, String markdown) {
            bubbles.add(markdown);
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
    }

    /** Minimal host exposing only the shared conversation sink (the rest is unused by these tests). */
    private static final class SinkHost implements AgentHostContext {
        private final com.aresstack.askai.plugin.api.agent.AgentConversationSink sink;

        SinkHost(com.aresstack.askai.plugin.api.agent.AgentConversationSink sink) {
            this.sink = sink;
        }

        public com.aresstack.askai.plugin.api.service.UiExecutor getUiExecutor() {
            return new InlineUiExecutor();
        }

        public com.aresstack.askai.plugin.api.service.ThemeService getThemeService() {
            return null;
        }

        public com.aresstack.askai.plugin.api.service.MarkdownViewFactory getMarkdownViewFactory() {
            return null;
        }

        public com.aresstack.askai.plugin.api.service.NotificationService getNotificationService() {
            return null;
        }

        public com.aresstack.askai.plugin.api.service.WorkspaceStateStore getStateStore() {
            return null;
        }

        public com.aresstack.askai.plugin.api.service.PluginPathService getPluginPathService() {
            return null;
        }

        public com.aresstack.askai.plugin.api.agent.AgentConversationSink getConversationSink() {
            return sink;
        }
    }

    private static final class FakeTarget implements ChatSubmissionTarget {
        final List<String> submitted = new ArrayList<String>();
        int stopCount;
        SubmissionAvailability availability = SubmissionAvailability.AVAILABLE;

        public SubmissionAvailability getAvailability() {
            return availability;
        }

        public void submitText(String text) {
            submitted.add(text);
        }

        public void stop() {
            stopCount++;
        }
    }
}
