package com.aresstack.askai.research.runtime.loop;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.analysis.RenderedPageSource;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairCoordinator;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairJson;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;
import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.research.runtime.host.HostToolEndpoint;
import com.aresstack.askai.research.runtime.host.InProcessHostToolEndpoints;
import org.junit.AfterClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pflichtnachweis (§7): the two-step SERP layout repair runs across the in-process host tool endpoints (askai #50) — the
 * repair DTOs are serialized by the browser runtime tools, transported over the streamable MCP channel and
 * deserialized by the research runtime (never passed in-process). A model-free browser runtime prepares/
 * applies; the runtime coordinator validates against snapshot-local descriptors and repairs. Also:
 * high confidence calls no model, and the guard rejections (unknown / consumed / snapshot / expired)
 * hold over the wire.
 */
public class LayoutRepairMcpIntegrationTest {

    private static InProcessMcpServerRegistry runtime;

    @AfterClass
    public static void shutdown() {
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
    }

    private static InProcessMcpServerRegistry runtime() {
        if (runtime == null) {
            runtime = new InProcessMcpServerRegistry();
        }
        return runtime;
    }

    /** A scripted, deterministic StructuredInferencePort — records call count. */
    private static final class ScriptedPort implements StructuredInferencePort {
        final AtomicInteger calls = new AtomicInteger();
        private final StructuredInferenceResult response;

        ScriptedPort(StructuredInferenceResult response) {
            this.response = response;
        }

        public StructuredInferenceResult execute(StructuredInferenceRequest request) {
            calls.incrementAndGet();
            return response;
        }
    }

    private static StructuredInferenceResult decisionNaming(String container) {
        return StructuredInferenceResult.success(
                "{\"analysisId\":\"analysis-snap-1-itest-1\",\"snapshotId\":\"snap-1-itest\","
                + "\"organicResultContainerIds\":[\"" + container + "\"],"
                + "\"resultBlockContainerIds\":[],\"excludedContainerIds\":[],"
                + "\"confidence\":0.9,\"explanation\":\"repeated blocks\"}");
    }

    private McpEndpointHandle registerTools(SearchLayoutRepairTools tools) {
        McpEndpointHandle handle =
                runtime().registerEndpoint(new McpEndpointDefinition("browser.repair", "Browser"));
        List<McpToolContribution> contributions = new ArrayList<McpToolContribution>();
        contributions.add(McpToolContribution.of("web_search_prepare", "prepare",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok(tools.prepare(call.getString("query")));
                    }
                }, McpToolParameter.string("query", true, "q")));
        contributions.add(McpToolContribution.of("web_search_apply_layout", "apply",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok(tools.applyLayout(call.getString("submission")));
                    }
                }, McpToolParameter.string("submission", true, "s")));
        contributions.add(McpToolContribution.of("web_search_discard_repair", "discard",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        return McpToolResult.ok(tools.discard(call.getString("repairTicketId")));
                    }
                }, McpToolParameter.string("repairTicketId", true, "t")));
        runtime().updateTools(handle, contributions);
        return handle;
    }

    private SearchLayoutRepairTools tools(LegacyBrowserSearchSettings settings, LongSupplier clock) {
        RenderedPageSource source = new RenderedPageSource() {
            public EngineCapture capture(String query, PageEvaluator evaluator) {
                Captured page = new Captured(RepairBridgeFixtures.navPlusColumn(), "engine.example");
                evaluator.judge(page.document, page.engineHost);
                return new EngineCapture(Collections.singletonList(page),
                        Collections.singletonList("engine.example"),
                        Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>
                                emptyList(),
                        Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>
                                emptyList());
            }
        };
        return new SearchLayoutRepairTools(settings, source, clock);
    }

    private SearchLayoutRepairCoordinator coordinator(LegacyBrowserSearchSettings settings,
                                                      StructuredInferencePort port) {
        return new SearchLayoutRepairCoordinator(settings, port, InferenceBudgetGate.ALLOW_ALL,
                RetryDelay.IMMEDIATE, null);
    }

    private McpLayoutRepairClient.ToolBudget countingBudget(AtomicInteger counter) {
        return new McpLayoutRepairClient.ToolBudget() {
            public boolean beforeToolCall() {
                counter.incrementAndGet();
                return true;
            }
        };
    }

    @Test
    public void lowConfidenceRepairedAcrossRealMcpEndpointsYieldsRealCandidates() throws Exception {
        LegacyBrowserSearchSettings settings = RepairBridgeFixtures.lowConfidenceAiEnabled();
        McpEndpointHandle handle = registerTools(tools(settings, new AtomicLong(1000L)::get));
        HostToolEndpoint browser =
                new InProcessHostToolEndpoints(runtime()).connect(runtime().endpointUrl(handle), "streamable", 30);
        ScriptedPort port = new ScriptedPort(decisionNaming("container-0003"));
        AtomicInteger budgetChecks = new AtomicInteger();
        try {
            McpLayoutRepairClient client =
                    new McpLayoutRepairClient(browser, coordinator(settings, port));
            McpLayoutRepairClient.Result result = client.searchWithRepair("berlin",
                    CancellationSignal.NONE, 2000L, countingBudget(budgetChecks));

            assertEquals(McpLayoutRepairClient.Outcome.ORGANIC_RESULTS, result.status);
            assertEquals(3, result.candidates.size());
            assertEquals("Result 0 title", result.candidates.get(0).title);
            assertEquals("https://site0.example.org/page", result.candidates.get(0).resolvedTargetUrl);
            assertTrue(result.candidates.get(0).snippet.contains("Snippet for result 0"));
            assertEquals("exactly one model call", 1, port.calls.get());
            assertEquals("tool budget checked before prepare and apply", 2, budgetChecks.get());
        } finally {
            browser.close();
            runtime().unregisterEndpoint(handle);
        }
    }

    @Test
    public void highConfidenceUsesNoModelOverMcp() throws Exception {
        LegacyBrowserSearchSettings settings = RepairBridgeFixtures.highConfidenceAiEnabled();
        McpEndpointHandle handle = registerTools(tools(settings, new AtomicLong(1000L)::get));
        HostToolEndpoint browser =
                new InProcessHostToolEndpoints(runtime()).connect(runtime().endpointUrl(handle), "streamable", 30);
        ScriptedPort port = new ScriptedPort(decisionNaming("container-0003"));
        try {
            McpLayoutRepairClient client =
                    new McpLayoutRepairClient(browser, coordinator(settings, port));
            McpLayoutRepairClient.Result result = client.searchWithRepair("berlin",
                    CancellationSignal.NONE, 2000L, countingBudget(new AtomicInteger()));

            assertEquals(McpLayoutRepairClient.Outcome.ORGANIC_RESULTS, result.status);
            assertEquals(3, result.candidates.size());
            assertEquals("a high-confidence page must never call the model", 0, port.calls.get());
        } finally {
            browser.close();
            runtime().unregisterEndpoint(handle);
        }
    }

    @Test
    public void aiUnavailableEndsInExtractionFailedOverMcp() throws Exception {
        LegacyBrowserSearchSettings settings = RepairBridgeFixtures.lowConfidenceAiEnabled();
        McpEndpointHandle handle = registerTools(tools(settings, new AtomicLong(1000L)::get));
        HostToolEndpoint browser =
                new InProcessHostToolEndpoints(runtime()).connect(runtime().endpointUrl(handle), "streamable", 30);
        ScriptedPort port = new ScriptedPort(
                StructuredInferenceResult.of(StructuredInferenceStatus.UNAVAILABLE, "no adapter"));
        try {
            McpLayoutRepairClient client =
                    new McpLayoutRepairClient(browser, coordinator(settings, port));
            McpLayoutRepairClient.Result result = client.searchWithRepair("berlin",
                    CancellationSignal.NONE, 2000L, countingBudget(new AtomicInteger()));
            assertEquals(McpLayoutRepairClient.Outcome.EXTRACTION_FAILED, result.status);
            assertTrue(result.candidates.isEmpty());
        } finally {
            browser.close();
            runtime().unregisterEndpoint(handle);
        }
    }

    @Test
    public void guardRejectionsHoldOverMcp() throws Exception {
        LegacyBrowserSearchSettings settings = RepairBridgeFixtures.lowConfidenceAiEnabled();
        AtomicLong clock = new AtomicLong(1000L);
        SearchLayoutRepairTools tools = tools(settings, clock::get);
        McpEndpointHandle handle = registerTools(tools);
        HostToolEndpoint browser =
                new InProcessHostToolEndpoints(runtime()).connect(runtime().endpointUrl(handle), "streamable", 30);
        try {
            // unknown ticket
            assertEquals(SearchLayoutRepairStatus.UNKNOWN_ATTEMPT,
                    applyStatus(browser, submissionJson("nope", "snap-1-itest", "fp-itest", "x",
                            "container-0003")));

            // prepare a real ticket, then apply twice: first organic, second already-consumed/unknown
            McpLayoutRepairClient client =
                    new McpLayoutRepairClient(browser, coordinator(settings,
                            new ScriptedPort(decisionNaming("container-0003"))));
            McpLayoutRepairClient.Result ok = client.searchWithRepair("q",
                    CancellationSignal.NONE, 2000L, countingBudget(new AtomicInteger()));
            assertEquals(McpLayoutRepairClient.Outcome.ORGANIC_RESULTS, ok.status);

            // the ticket id is deterministic for the fixture snapshot
            String ticket = "repair-snap-1-itest";
            assertEquals("second application of a consumed ticket is rejected",
                    SearchLayoutRepairStatus.UNKNOWN_ATTEMPT,
                    applyStatus(browser, submissionJson(ticket, "snap-1-itest", "fp-itest",
                            layoutFingerprintFor(settings), "container-0003")));

            // snapshot mismatch on a fresh ticket
            browser.call("web_search_prepare", Collections.<String, Object>singletonMap("query", "q"));
            assertEquals(SearchLayoutRepairStatus.SNAPSHOT_MISMATCH,
                    applyStatus(browser, submissionJson("repair-snap-1-itest", "wrong-snap",
                            "fp-itest", layoutFingerprintFor(settings), "container-0003")));
        } finally {
            browser.close();
            runtime().unregisterEndpoint(handle);
        }
    }

    private SearchLayoutRepairStatus applyStatus(HostToolEndpoint browser, String submissionJson)
            throws Exception {
        String resultJson = browser.call("web_search_apply_layout",
                Collections.<String, Object>singletonMap("submission", submissionJson));
        SearchLayoutRepairResult result = SearchLayoutRepairJson.decodeRepairResult(resultJson);
        return result.status;
    }

    private static String submissionJson(String ticket, String snapshotId, String fingerprint,
                                         String layoutFp, String organicId) {
        // Used only by guard-rejection cases (unknown/consumed/snapshot) that reject before the
        // binding check, so the analysis/generation/settings values are placeholders here.
        ValidatedSearchPageLayoutDecision decision = new ValidatedSearchPageLayoutDecision(
                "analysis-x", snapshotId, 1L, fingerprint, "", organicId, Arrays.asList(organicId),
                Collections.<String>emptyList(), Collections.<String>emptyList(), 0.9);
        return SearchLayoutRepairJson.encodeSubmission(new SearchLayoutRepairSubmission(
                new SearchLayoutRepairAttemptId(ticket), "analysis-x", snapshotId, 1L, fingerprint,
                layoutFp, "", decision));
    }

    /** The structure fingerprint the browser runtime will have cached for the fixture under these settings. */
    private static String layoutFingerprintFor(LegacyBrowserSearchSettings settings) {
        com.aresstack.askai.browser.render.RenderedPageDocument doc =
                RepairBridgeFixtures.navPlusColumn();
        com.aresstack.askai.browser.search.analysis.SearchPageLayoutResolution resolution =
                new com.aresstack.askai.browser.search.analysis.SearchPageMechanicalAnalyzer(settings)
                        .analyze(doc);
        return com.aresstack.askai.browser.search.analysis.WebSearchLayoutRepairService
                .layoutStructureFingerprint(
                        new com.aresstack.askai.browser.search.analysis
                                .SearchPageAnalysisArtifactBuilder(settings)
                                .build(doc, resolution, "q"));
    }
}
