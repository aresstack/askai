package com.aresstack.askai.research.host;

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
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.capture.VisitedCapture;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceQuery;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * askai #47: the productive research run end to end in ONE JVM, through the host wiring AskAI uses — port of the
 * former process-based {@code ProductiveResearchMvpEndToEndTest}. {@link ResearchRuntimeGenerationSwitch} →
 * {@link ProductiveResearchBackendFactory} → {@link ProductiveResearchSessionResources} → the research backend
 * port, with the agent created by {@link InProcessResearchRuntime} (no agent process, no browser process, no
 * MCP or ACP wire).
 *
 * <p>Proven here: both host endpoints are registered in the in-process registry; the research browser is lazy
 * (STOPPED at session start, READY after the first web command); the host state machine is the only transition
 * authority; the initial search runs through Serp4J's Google provider on the shared search browser (pointed at a
 * local Google-shaped result page); the research browser opens JavaScript-only pages on two hosts; captures are
 * rendered; sources are accepted and persisted from two hosts; PHASE_READY is an event and the host dispatches
 * REQUEST_EVIDENCE_REVIEW; the teardown is ordered and idempotent (endpoint unregistered, browser stopped); a
 * successful generation switch retires and locks the previous generation.</p>
 *
 * <p>Environment-gated on an installed browser ({@code ASKAI_TEST_BROWSER_CHANNEL}, default {@code chrome}).
 * The mandatory reranker is a local stub endpoint that serves the reranker protocol with a fixed order: the test
 * checks the composition, not the ranking quality.</p>
 */
public class InProcessResearchEndToEndTest {

    private static final String RERANKER_MODEL = "e2e-stub-reranker";

    private static final String PAGE_TEMPLATE = "<!doctype html><html><head><title>%TITLE%</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='%TEXT% Rendered by JavaScript.';"
            + "%LINKS%"
            + "</script></body></html>";

    @Test
    public void productiveInProcessCompositionRunsTheFullResearchAndSwitchesGenerations() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated): " + readiness.render(), readiness.isReady());

        // ---- a Google-shaped engine + two content hosts (distinct loopback addresses = distinct hosts) ----
        HttpServer engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        HttpServer hostOne = HttpServer.create(new InetSocketAddress("127.0.0.2", 0), 0);
        HttpServer hostTwo = HttpServer.create(new InetSocketAddress("127.0.0.3", 0), 0);
        HttpServer reranker = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String baseEngine = "http://127.0.0.1:" + engine.getAddress().getPort();
        String baseOne = "http://127.0.0.2:" + hostOne.getAddress().getPort();
        String baseTwo = "http://127.0.0.3:" + hostTwo.getAddress().getPort();
        final List<String> engineQueries = new CopyOnWriteArrayList<String>();
        engine.createContext("/search", googleShapedSerp(engineQueries, new String[][]{
                {"PF4J primer", baseOne + "/a"},
                {"Independent pf4j review", baseTwo + "/c"},
                {"pf4j in production", baseTwo + "/e"}}));
        hostOne.createContext("/a", page("PF4J primer",
                "pf4j is a plugin framework. Primary source.", jsLink("pf4j details", baseTwo + "/c")));
        hostTwo.createContext("/c", page("Independent pf4j review",
                "pf4j works well with java 8.", jsLink("pf4j extra evidence", baseTwo + "/e")));
        hostTwo.createContext("/e", page("pf4j in production", "More pf4j evidence from the field.", ""));
        reranker.createContext("/api/tags", json("{\"models\":[{\"name\":\"" + RERANKER_MODEL + "\"}]}"));
        reranker.createContext("/api/rerank", new StubRerank());
        engine.start();
        hostOne.start();
        hostTwo.start();
        reranker.start();
        String oldSearchUrl = System.setProperty("askai.research.googleSearchUrl", baseEngine + "/search");

        InProcessMcpServerRegistry botRegistry = new InProcessMcpServerRegistry();
        InProcessResearchRuntime runtime = new InProcessResearchRuntime(channel);
        ResearchRuntimeGenerationSwitch switcher = new ResearchRuntimeGenerationSwitch(botRegistry, runtime,
                stubRerankerSnapshots("http://127.0.0.1:" + reranker.getAddress().getPort()));
        ResearchRuntimeConfig config = new ResearchRuntimeConfig(channel, true, true, RERANKER_MODEL);
        File projectDir = Files.createTempDirectory("askai-inprocess-e2e").toFile();
        ProductiveResearchSessionResources resources = null;
        try {
            // ---- generation 1: prepare-then-publish, then a productive session ----
            ResearchRuntimeGeneration gen1 = switcher.switchTo(config);
            resources = gen1.createSession("e2e1", projectDir);

            InProcessMcpServerRegistry agentRegistry = runtime.registry();
            assertNotNull("research endpoint registered in the in-process registry",
                    agentRegistry.endpointUrl(resources.getControlEndpoint().getHandle()));
            assertNotNull("browser bridge endpoint registered in the in-process registry",
                    agentRegistry.endpointUrl(resources.getBrowserBridge().getHandle()));
            assertFalse("the research browser is STOPPED at session start (lazy)",
                    resources.getBrowser().isReady());

            // ---- the host is the only state authority: drive the machine to RESEARCH/running ----
            for (ResearchCommandType command : new ResearchCommandType[]{
                    ResearchCommandType.START, ResearchCommandType.SUBMIT_SCOPE,
                    ResearchCommandType.START_RESEARCH}) {
                assertTrue("command must be accepted: " + command, resources.dispatch(command).isAccepted());
            }
            assertEquals(ResearchStateIds.RESEARCH, resources.currentState().getPhaseId());
            assertEquals(ResearchStateIds.RUNNING, resources.currentState().getStateId());

            // ---- the productive backend port: in-process agent + prompt-driven autonomous run ----
            Collecting observer = new Collecting();
            ResearchSessionHandle handle = resources.getBackend().createSession(
                    new ResearchProjectRequest("e2e1", "p1", "Research project"), observer);
            assertEquals("agent session must not report a start error",
                    0, observer.count("could not be started"));
            resources.getBackend().submitPrompt(handle, new ResearchPrompt("research: pf4j plugin framework", ""));
            assertTrue("autonomous run must finish", observer.runStopped.await(180, TimeUnit.SECONDS));

            assertEquals("one initial search through Serp4J's Google provider on the search browser: " + engineQueries,
                    1, engineQueries.size());
            assertTrue(engineQueries.get(0), engineQueries.get(0).contains("q=pf4j"));
            assertTrue("the research browser became READY on the first web command",
                    resources.getBrowser().isReady());
            assertEquals("exactly one PHASE_READY event", 1, observer.count("PHASE_READY:"));
            assertEquals("run must stop with sufficient evidence",
                    1, observer.count("run stopped: SUFFICIENT_EVIDENCE"));
            assertEquals("the structured outcome event arrived exactly once", 1, observer.outcomes());
            assertTrue("readiness reached the observer", observer.count("RESEARCH_MCP_READY") >= 1);

            List<VisitedCapture> captures = resources.getCaptures().list();
            assertTrue("at least three captures, was " + captures.size(), captures.size() >= 3);
            for (VisitedCapture capture : captures) {
                assertTrue("capture must be JS-rendered: " + capture.getCanonicalUrl(),
                        capture.getText().contains("Rendered by JavaScript"));
            }
            List<ResearchSourceRecord> sources = resources.getRepository().find(SourceQuery.all());
            assertTrue("at least two persisted sources, was " + sources.size(), sources.size() >= 2);
            Set<String> origins = new HashSet<String>();
            for (ResearchSourceRecord source : sources) {
                origins.add(source.getOrigin());
            }
            assertEquals("sources from two distinct hosts: " + origins, 2, origins.size());

            assertEquals("issue #32: no findings artifact is written - accepted sources are the record",
                    "", resources.getArtifactStore().read("findings").getMarkdown());

            // PHASE_READY was an event only: the state did not change until the HOST reacts.
            assertEquals(ResearchStateIds.RUNNING, resources.currentState().getStateId());
            assertTrue(resources.dispatch(ResearchCommandType.REQUEST_EVIDENCE_REVIEW).isAccepted());
            assertEquals(ResearchStateIds.WAITING_APPROVAL, resources.currentState().getStateId());

            // ---- ordered, idempotent teardown ----
            resources.getBackend().close(handle);
            McpEndpointHandle controlHandle = resources.getControlEndpoint().getHandle();
            McpEndpointHandle bridgeHandle = resources.getBrowserBridge().getHandle();
            resources.close();
            resources.close();
            assertFalse("the research browser stopped with the session", resources.getBrowser().isReady());
            assertNull("research endpoint unregistered", agentRegistry.endpointUrl(controlHandle));
            assertNull("browser bridge endpoint unregistered", agentRegistry.endpointUrl(bridgeHandle));

            // ---- the generation still creates sessions; a successful switch retires and locks it ----
            ProductiveResearchSessionResources second = gen1.createSession("e2e2", projectDir);
            assertFalse("a fresh session's browser is STOPPED until first use (lazy)",
                    second.getBrowser().isReady());
            second.close();
            ResearchRuntimeGeneration gen2 = switcher.switchTo(config);
            assertTrue("generation 1 retired after publish", gen1.isRetired());
            try {
                gen1.createSession("e2e3", projectDir);
                fail("a retired generation must be locked for new sessions");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("retired"));
            }
            assertTrue(switcher.getActive() == gen2);
        } finally {
            if (resources != null) {
                resources.close();
            }
            switcher.shutdown();
            runtime.close();
            botRegistry.shutdown();
            engine.stop(0);
            hostOne.stop(0);
            hostTwo.stop(0);
            reranker.stop(0);
            if (oldSearchUrl == null) {
                System.clearProperty("askai.research.googleSearchUrl");
            } else {
                System.setProperty("askai.research.googleSearchUrl", oldSearchUrl);
            }
        }
    }

    // ------------------------------------------------------------------ observer

    private static final class Collecting implements ResearchSessionListener {
        final List<ResearchBackendEvent> events = new CopyOnWriteArrayList<ResearchBackendEvent>();
        final CountDownLatch runStopped = new CountDownLatch(1);

        public void onEvent(ResearchBackendEvent event) {
            events.add(event);
            if (event.getType() == ResearchBackendEventType.RUN_OUTCOME) {
                runStopped.countDown();
            }
        }

        int count(String needle) {
            int n = 0;
            for (ResearchBackendEvent event : events) {
                String text = (event.getText() == null ? "" : event.getText()) + " "
                        + (event.getPublicMessage() == null ? "" : event.getPublicMessage());
                if (text.contains(needle)) {
                    n++;
                }
            }
            return n;
        }

        int outcomes() {
            int n = 0;
            for (ResearchBackendEvent event : events) {
                if (event.getType() == ResearchBackendEventType.RUN_OUTCOME) {
                    n++;
                }
            }
            return n;
        }
    }

    // ------------------------------------------------------------------ reranker stub

    private static RerankerConfigurationSnapshotProvider stubRerankerSnapshots(final String baseUrl) {
        return new RerankerConfigurationSnapshotProvider() {
            public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                                   String selectedModel)
                    throws RerankerConfigurationException {
                if (!RERANKER_MODEL.equals(selectedModel)) {
                    throw new RerankerConfigurationException("unexpected reranker selection " + selectedModel);
                }
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
            int documents = countDocuments(readAll(exchange.getRequestBody()));
            StringBuilder body = new StringBuilder("{\"model\":\"" + RERANKER_MODEL + "\",\"results\":[");
            for (int i = 0; i < documents; i++) {
                body.append(i == 0 ? "" : ",").append("{\"index\":").append(i)
                        .append(",\"score\":").append(10 - i).append(".0}");
            }
            body.append("]}");
            json(body.toString()).handle(exchange);
        }
    }

    /** Number of strings in the request's {@code documents} array. */
    static int countDocuments(String request) {
        int start = request.indexOf("\"documents\":[");
        if (start < 0) {
            return 0;
        }
        int count = 0;
        boolean inString = false;
        for (int i = start + "\"documents\":[".length(); i < request.length(); i++) {
            char c = request.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                count++;
            } else if (c == ']') {
                break;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ fixtures

    /** A result page in Google's markup ({@code #rso}, {@code div.MjjYud}, {@code a > h3}, {@code div.VwiC3b}). */
    private static HttpHandler googleShapedSerp(final List<String> queries, String[][] results) {
        StringBuilder html = new StringBuilder("<!doctype html><html><head><title>pf4j - Search</title></head>"
                + "<body><div id='search'><div id='rso'>");
        for (String[] result : results) {
            html.append("<div class='MjjYud'><div><a href='").append(result[1]).append("'><h3>")
                    .append(result[0]).append("</h3><cite>").append(result[1]).append("</cite></a></div>")
                    .append("<div class='VwiC3b'>Explanatory snippet describing ").append(result[0])
                    .append(" in detail.</div></div>");
        }
        html.append("</div></div></body></html>");
        final HttpHandler page = html(html.toString());
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                queries.add(exchange.getRequestURI().getRawQuery());
                page.handle(exchange);
            }
        };
    }

    private static HttpHandler page(String title, String text, String linkScript) {
        return html(PAGE_TEMPLATE.replace("%TITLE%", title).replace("%TEXT%", text).replace("%LINKS%", linkScript));
    }

    private static String jsLink(String text, String href) {
        return "var a=document.createElement('a');a.href='" + href + "';a.textContent='" + text
                + "';document.body.appendChild(a);";
    }

    private static HttpHandler html(String body) {
        return respond("text/html; charset=utf-8", body);
    }

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
}
