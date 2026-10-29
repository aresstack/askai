package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;

import java.util.Arrays;

/**
 * The per-session INTERNAL research-service MCP endpoint — a SEPARATE namespace from the agent-facing
 * {@link ResearchControlEndpoint}. It hosts runtime→host SERVICE operations a USER-triggered backend service
 * needs (currently {@code manual_source_accept}) which must NEVER be offered to the agent/model. Because they
 * live on their OWN endpoint, they are structurally absent from the agent tool catalog (and from any later
 * capability filter, S4) — no "hidden tool" flag in the shared MCP registry is required.
 *
 * <p>{@code manual_source_accept} delegates to the EXACT SAME host-side acceptance service the phase-gated
 * agent tool {@code source_accept} uses ({@link ResearchControlContext#acceptCapture}) — acceptance logic is
 * never duplicated. The ONLY difference is the trust boundary: this endpoint is not phase-gated, and the
 * trusted user origin is established by WHICH endpoint was invoked, never by a tool argument an agent could
 * forge. {@link #close()} unregisters the endpoint and is idempotent.</p>
 */
public final class ResearchServiceEndpoint {

    /** The internal tool names — deliberately distinct from the agent's phase-gated {@code source_accept}. */
    public static final String MANUAL_SOURCE_ACCEPT = "manual_source_accept";
    public static final String MANUAL_SOURCE_PARK = "manual_source_park";
    /** SC1 search-scope control — internal sensor lane, structurally never an agent tool. */
    public static final String SEARCH_SCOPE_BEGIN = "search_scope_begin";
    public static final String SEARCH_SCOPE_EVALUATE = "search_scope_evaluate";
    public static final String SEARCH_SCOPE_END = "search_scope_end";

     private final McpServerRegistry registry;
    private final ResearchControlContext context;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;
    /** The in-process service lane of the agent (manual_source_*, search_scope_*). */
    private final com.aresstack.askai.research.runtime.loop.ContributionToolInvoker invoker =
            new com.aresstack.askai.research.runtime.loop.ContributionToolInvoker();

    public ResearchServiceEndpoint(McpServerRegistry registry, String sessionKey, long pluginGenerationId,
                                   ResearchControlContext context) {
        this.registry = registry;
        this.context = context;
        this.endpointId = "research-service." + sessionKey + ".g" + pluginGenerationId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    /** @return the registry handle (endpoint id + opaque token), or null before open()/after close(). */
    public McpEndpointHandle getHandle() {
        return handle;
    }

    /** The agent's in-process service lane over this endpoint's tools (no transport). */
    public com.aresstack.askai.research.runtime.loop.ToolInvoker toolInvoker() {
        return invoker;
    }

    /** Register the internal endpoint and publish its fixed tool set (no phase dependency). Idempotent. */
    public void open() {
        if (closed || handle != null) {
            return;
        }
        handle = registry.registerEndpoint(
                new McpEndpointDefinition(endpointId, "Research Service (internal)"));
        java.util.List<McpToolContribution> tools = Arrays.asList(
                manualSourceAcceptTool(context), manualSourceParkTool(context),
                searchScopeBeginTool(context), searchScopeEvaluateTool(context),
                searchScopeEndTool(context));
        invoker.setTools(tools);
        registry.updateTools(handle, tools);
    }

    /** Unregister the endpoint (invalidates the token). Idempotent. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        invoker.close();
        if (handle != null) {
            registry.unregisterEndpoint(handle);
            handle = null;
        }
    }

    private static McpToolContribution manualSourceAcceptTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(MANUAL_SOURCE_ACCEPT,
                "Internal: accept a visited capture as a research source for a USER-triggered search "
                        + "(not phase-gated, never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String captureId = call.getString("capture_id");
                        if (captureId == null || captureId.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: capture_id");
                        }
                        // Delegate to the SAME host-side SourceAcceptanceService the agent path uses; no phase
                        // gate — the trusted user origin is this internal endpoint itself. The user query that
                        // found the source is persisted with it (so "already searched" survives a restart).
                        String searchQuery = call.getString("search_query");
                        boolean userRelevant = call.getBoolean("user_relevant", false);
                        String language = call.getString("language");
                        String searchRequestId = call.getString("search_request_id");
                        String result = ctx.acceptCapture(captureId.trim(),
                                searchQuery == null ? "" : searchQuery, userRelevant,
                                language == null ? "" : language,
                                searchRequestId == null ? "" : searchRequestId);
                        return result == null
                                ? McpToolResult.error("Unknown capture: " + captureId)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("capture_id", true, "The capture id from a visited page"),
                McpToolParameter.string("search_query", false,
                        "The user web-search query that found this capture"),
                McpToolParameter.string("user_relevant", false,
                        "true when the user marked this page relevant in the HUD (⭐)"),
                McpToolParameter.string("language", false,
                        "the language snapshot of the search that found this capture (en/de)"),
                McpToolParameter.string("search_request_id", false,
                        "the manual-search request id that found this capture"));
    }

    private static McpToolContribution manualSourceParkTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(MANUAL_SOURCE_PARK,
                "Internal: park a reranked search candidate as a scored source before it is visited "
                        + "(empty full text; not phase-gated, never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String url = call.getString("url");
                        if (url == null || url.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: url");
                        }
                        String searchQuery = call.getString("search_query");
                        String result = ctx.parkCandidate(url.trim(),
                                nullToEmpty(call.getString("title")),
                                nullToEmpty(call.getString("excerpt")),
                                parseScore(call.getString("score")),
                                searchQuery == null ? "" : searchQuery,
                                nullToEmpty(call.getString("search_request_id")));
                        return result == null ? McpToolResult.error("Could not park: " + url)
                                : McpToolResult.ok(result);
                    }
                },
                McpToolParameter.string("url", true, "The candidate's resolved target URL"),
                McpToolParameter.string("title", false, "The candidate title"),
                McpToolParameter.string("excerpt", false, "The search-result snippet/excerpt"),
                McpToolParameter.string("score", false, "The reranker relevance score (a double)"),
                McpToolParameter.string("search_query", false,
                        "The user web-search query that found this candidate"),
                McpToolParameter.string("search_request_id", false,
                        "the manual-search request id that produced this candidate"));
    }

    private static McpToolContribution searchScopeBeginTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(SEARCH_SCOPE_BEGIN,
                "Internal: pin one immutable search-scope snapshot for a search run "
                        + "(SC1 acquisition filter; never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String reply = ctx.searchScopeBegin();
                        return reply == null
                                ? McpToolResult.ok("INACTIVE outAnchors=0")
                                : McpToolResult.ok(reply);
                    }
                });
    }

    private static McpToolContribution searchScopeEvaluateTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(SEARCH_SCOPE_EVALUATE,
                "Internal: judge one candidate batch against a pinned search-scope snapshot "
                        + "(never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String handle = call.getString("handle");
                        if (handle == null || handle.trim().isEmpty()) {
                            return McpToolResult.error("Missing argument: handle");
                        }
                        String reply = ctx.searchScopeEvaluate(handle.trim(),
                                nullToEmpty(call.getString("lane")),
                                nullToEmpty(call.getString("items_json")));
                        return reply == null ? McpToolResult.ok("UNKNOWN_HANDLE")
                                : McpToolResult.ok(reply);
                    }
                },
                McpToolParameter.string("handle", true, "The snapshot handle from begin"),
                McpToolParameter.string("lane", false, "serp | links | page (log label)"),
                McpToolParameter.string("items_json", true,
                        "JSON array of {id,text} candidates"));
    }

    private static McpToolContribution searchScopeEndTool(final ResearchControlContext ctx) {
        return McpToolContribution.of(SEARCH_SCOPE_END,
                "Internal: release a pinned search-scope snapshot (never an agent tool).",
                new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        String handle = call.getString("handle");
                        if (handle != null && !handle.trim().isEmpty()) {
                            ctx.searchScopeEnd(handle.trim());
                        }
                        return McpToolResult.ok("ENDED");
                    }
                },
                McpToolParameter.string("handle", true, "The snapshot handle from begin"));
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }

    /** Missing/empty/non-numeric score → NaN ("unknown"); a valid double otherwise. */
    private static double parseScore(String v) {
        if (v == null || v.trim().isEmpty()) {
            return Double.NaN;
        }
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return Double.NaN;
        }
    }
}
