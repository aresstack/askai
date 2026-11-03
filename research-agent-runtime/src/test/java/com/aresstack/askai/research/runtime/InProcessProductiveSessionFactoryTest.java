package com.aresstack.askai.research.runtime;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationException;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshot;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptorCodec;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.browser.runtime.PlaywrightCapabilityProbe;
import com.aresstack.askai.browser.runtime.PlaywrightReadiness;
import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.plugin.api.agent.AgentConversationSink;
import com.aresstack.askai.plugin.api.agent.AgentHostContext;
import com.aresstack.askai.plugin.api.agent.AgentSession;
import com.aresstack.askai.plugin.api.agent.AgentSessionCreationRequest;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.NotificationService;
import com.aresstack.askai.plugin.api.service.PluginPathService;
import com.aresstack.askai.plugin.api.service.ThemeService;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.acp.ResearchBackendMode;
import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.agent.ResearchAgentSessionFactory;
import com.aresstack.askai.research.host.ResearchRuntimeSettings;
import com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader;
import com.aresstack.askai.research.runtime.team.MockMainModelServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * askai #47: the GUI's start path in ONE JVM — port of the former process-based {@code GuiChainReproductionTest} and
 * the start of {@code ProductiveModeFactorySmokeTest}. Persisted settings → the same
 * {@link ResearchAgentSessionFactory} the plugin registers → {@code activate()} creates the agent in this JVM through
 * {@code InProcessResearchRuntime.shared()} (no agent jar, no process, no ACP/MCP wire) → the agent connects to the
 * research endpoint and the model-backed TeamAgent answers the user's first message through the host-published main model (mock
 * {@code /api/chat}) → close is idempotent. The research run itself (search, research browser, captures, sources,
 * state transitions) is proven through the same factory by {@code InProcessResearchEndToEndTest}.
 *
 * <p>Environment-gated on an installed browser ({@code ASKAI_TEST_BROWSER_CHANNEL}, default {@code chrome}), because
 * a productive session requires one. The mandatory reranker is a local stub endpoint.</p>
 */
public class InProcessProductiveSessionFactoryTest {

    private static final String RERANKER_MODEL = "e2e-stub-reranker";

    @Test
    public void theGuiFactoryStartsTheInProcessAgentAndUserCommandsDriveThePhases() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated): " + readiness.render(), readiness.isReady());

        HttpServer reranker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        reranker.createContext("/api/tags", json("{\"models\":[{\"name\":\"" + RERANKER_MODEL + "\"}]}"));
        reranker.createContext("/api/rerank", new StubRerank());
        reranker.start();
        // Per-run hand-off for local test worlds: the shared search browser uses the installed browser channel.
        String oldChannel = System.setProperty("askai.research.searchBrowserChannel", channel);

        final MockMainModelServer mockModel = new MockMainModelServer();
        mockModel.enqueueMessage("Hi! I'm your research assistant. What would you like to find out?");

        InProcessMcpServerRegistry botRegistry = new InProcessMcpServerRegistry();
        File dataDir = Files.createTempDirectory("askai-inprocess-factory").toFile();
        List<String> messages = new CopyOnWriteArrayList<String>();
        FakeHost host = new FakeHost(dataDir, messages);
        host.services.put(McpServerRegistry.class, botRegistry);
        host.services.put(RerankerConfigurationSnapshotProvider.class,
                stubRerankerSnapshots("http://127.0.0.1:" + reranker.getAddress().getPort()));
        host.services.put(InferenceConfigurationSnapshotProvider.class, new InferenceConfigurationSnapshotProvider() {
            public InferenceConfigurationSnapshot prepareForSession(String sessionId, File dir)
                    throws InferenceConfigurationException {
                try {
                    File config = mockModel.writeInferenceConfig(new File(dir, "inference-config.json"), "gemma4:e2b");
                    return new InferenceConfigurationSnapshot(config,
                            InferenceConfigurationLoader.load(config.getAbsolutePath()));
                } catch (IOException ex) {
                    throw new InferenceConfigurationException(ex.getMessage());
                }
            }
        });
        new ResearchRuntimeSettings(ResearchBackendMode.ACP, channel, true, true, RERANKER_MODEL).save(host.store);
        // An explicit channel choice: the one-time switch of an old stored "chrome" to Camoufox is already done.
        host.store.put("research.runtime.browserChannel.camoufoxSwitch", "done");

        AgentSession session = null;
        try {
            session = new ResearchAgentSessionFactory().create(
                    new AgentSessionCreationRequest("factory1", "p1", new HashMap<String, String>()), host);
            session.activate(); // the GUI's start point: creates the agent in this JVM; a new chat stays empty
            assertTrue("the in-process agent connected to the research endpoint: " + messages,
                    host.ready.await(60, TimeUnit.SECONDS));
            assertFalse("a new chat stays empty: no greeting, no model call: " + messages,
                    host.greeted.await(2, TimeUnit.SECONDS));
            ((ResearchAgentSession) session).submitPrompt("Hasenhaltung");
            assertTrue("the model answer to the user's first message reached the chat: " + messages,
                    host.greeted.await(60, TimeUnit.SECONDS));
            assertTrue("the mock /api/chat received model=gemma4:e2b", mockModel.sawModel("gemma4:e2b"));
            for (String message : messages) {
                assertFalse("the session start reported a problem: " + messages, message.startsWith("PROBLEM:"));
            }
            ResearchAgentSession research = (ResearchAgentSession) session;
            assertTrue("the host state machine is the session's state authority",
                    research.getState().getRunStateLabel() != null);
        } finally {
            if (session != null) {
                session.close();
                session.close(); // idempotent: closes agent, endpoints and research browser
            }
            botRegistry.shutdown();
            mockModel.close();
            reranker.stop(0);
            restore("askai.research.searchBrowserChannel", oldChannel);
        }
    }

    private static void restore(String key, String oldValue) {
        if (oldValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, oldValue);
        }
    }

    // ------------------------------------------------------------------ reranker stub

    private static RerankerConfigurationSnapshotProvider stubRerankerSnapshots(final String baseUrl) {
        return new RerankerConfigurationSnapshotProvider() {
            public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                                   String selectedModel)
                    throws RerankerConfigurationException {
                RerankerEndpointDescriptor descriptor = new RerankerEndpointDescriptor(RerankerProvider.ASKAI_LOCAL,
                        baseUrl, RERANKER_MODEL, Arrays.asList(RerankerCapability.RERANK),
                        RerankerScoreSemantics.RAW_LOGIT, 30_000L, RerankerSelectionConfiguration.topN(10));
                RerankerConfigurationDocument document = RerankerConfigurationDocument.current(0L, descriptor);
                File target = new File(sessionDirectory, "reranker-config.json");
                try {
                    Files.write(target.toPath(),
                            RerankerEndpointDescriptorCodec.toJson(document).getBytes(StandardCharsets.UTF_8));
                } catch (Exception ex) {
                    throw new RerankerConfigurationException("cannot write snapshot: " + ex.getMessage(), ex);
                }
                return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
            }
        };
    }

    /** Scores the documents in the order they were sent (first = best); the protocol, not a model. */
    private static final class StubRerank implements HttpHandler {
        public void handle(HttpExchange exchange) throws IOException {
            String request = readAll(exchange.getRequestBody());
            int documents = 0;
            int start = request.indexOf("\"documents\":[");
            boolean inString = false;
            for (int i = start < 0 ? request.length() : start + "\"documents\":[".length(); i < request.length(); i++) {
                char c = request.charAt(i);
                if (inString) {
                    if (c == '\\') {
                        i++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                    documents++;
                } else if (c == ']') {
                    break;
                }
            }
            StringBuilder body = new StringBuilder("{\"model\":\"" + RERANKER_MODEL + "\",\"results\":[");
            for (int i = 0; i < documents; i++) {
                body.append(i == 0 ? "" : ",").append("{\"index\":").append(i)
                        .append(",\"score\":").append(10 - i).append(".0}");
            }
            body.append("]}");
            json(body.toString()).handle(exchange);
        }
    }

    // ------------------------------------------------------------------ fixtures

    private static HttpHandler json(String body) {
        return respond("application/json; charset=utf-8", body);
    }

    private static HttpHandler respond(final String contentType, final String body) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", contentType);
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                exchange.close();
            }
        };
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, n);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ host

    /** Minimal host: direct-run UI executor, in-memory store, host services, recording conversation sink. */
    private static final class FakeHost implements AgentHostContext {
        final MemoryStore store = new MemoryStore();
        final Map<Class<?>, Object> services = new HashMap<Class<?>, Object>();
        final CountDownLatch ready = new CountDownLatch(1);
        final CountDownLatch greeted = new CountDownLatch(1);
        private final File dataDir;
        private final List<String> messages;

        FakeHost(File dataDir, List<String> messages) {
            this.dataDir = dataDir;
            this.messages = messages;
        }

        private void record(String text) {
            String body = text == null ? "" : text;
            messages.add(body);
            if (body.contains("RESEARCH_MCP_READY")) {
                ready.countDown();
            }
            if (body.contains("What would you like to find out?")) {
                greeted.countDown();
            }
        }

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
            return store;
        }

        public PluginPathService getPluginPathService() {
            return new PluginPathService() {
                public File getPluginDataDirectory() {
                    return dataDir;
                }

                public File getWorkspaceDirectory(String workspaceInstanceId) {
                    return new File(dataDir, workspaceInstanceId);
                }
            };
        }

        public AgentConversationSink getConversationSink() {
            return new AgentConversationSink() {
                public void appendUserMessage(String messageId, String markdown) {
                }

                public void appendAssistantMessage(String messageId, String markdown) {
                    record(markdown);
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
                    record(explanation); // run diagnostics render inside the progress card
                }

                public void completeToolActivity(String activityId, String summary) {
                }

                public void failToolActivity(String activityId, String summary) {
                }

                public void requestApproval(String approvalId, String prompt) {
                    messages.add("APPROVAL: " + prompt);
                }

                @Override
                public void showActionCard(String cardId, String markdown, List<ActionOption> actions,
                                           ActionHandler handler) {
                    messages.add("CARD: " + markdown);
                }

                @Override
                public void appendInfoMessage(String messageId, String markdown) {
                    record(markdown);
                }

                @Override
                public void appendTechnicalLog(String line) {
                    record(line);
                }

                public void showProblem(String problemId, String publicMessage) {
                    messages.add("PROBLEM: " + publicMessage);
                }
            };
        }

        @SuppressWarnings("unchecked")
        public <T> T getService(Class<T> type) {
            return (T) services.get(type);
        }
    }

    private static final class MemoryStore implements WorkspaceStateStore {
        final Map<String, String> values = new HashMap<String, String>();

        public String get(String key, String defaultValue) {
            return values.containsKey(key) ? values.get(key) : defaultValue;
        }

        public boolean getBoolean(String key, boolean defaultValue) {
            return values.containsKey(key) ? Boolean.parseBoolean(values.get(key)) : defaultValue;
        }

        public int getInt(String key, int defaultValue) {
            return values.containsKey(key) ? Integer.parseInt(values.get(key)) : defaultValue;
        }

        public void put(String key, String value) {
            values.put(key, value);
        }

        public void putBoolean(String key, boolean value) {
            values.put(key, String.valueOf(value));
        }

        public void putInt(String key, int value) {
            values.put(key, String.valueOf(value));
        }
    }
}
