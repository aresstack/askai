package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolParameter;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.capture.CaptureStore;
import com.aresstack.askai.research.capture.VisitedCapture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The PRODUCTIVE home of the {@code capture_id} convention (it is deliberately NOT in the agent loop): a
 * host-registered browser endpoint that delegates every {@code web_*} call to the in-process Playwright
 * browser via {@link BrowserRuntimePort} and records every page-returning navigation into the session's Commit-37
 * {@link CaptureStore}. The model-facing result line is
 * {@code URL: <url> title="<title>" capture_id=<id>} followed by the cleaned text — the exact contract the
 * agent loop consumes and {@code source_accept} resolves. Endpoint identity is session+generation-scoped
 * like the research-control endpoint; {@link #close()} unregisters and is idempotent.
 */
public final class BrowserBridgeEndpoint {

    private final McpServerRegistry registry;
    private final BrowserRuntimePort browser;
    private final CaptureStore captures;
    private final String endpointId;
    private McpEndpointHandle handle;
    private boolean closed;
    /** The in-process browser lane of the agent (the same bridge tools, capture_id convention included). */
    private final com.aresstack.askai.research.runtime.loop.ContributionToolInvoker invoker =
            new com.aresstack.askai.research.runtime.loop.ContributionToolInvoker();

    public BrowserBridgeEndpoint(McpServerRegistry registry, BrowserRuntimePort browser, CaptureStore captures,
                                 String sessionKey, long generationId) {
        this.registry = registry;
        this.browser = browser;
        this.captures = captures;
        this.endpointId = "browser." + sessionKey + ".g" + generationId;
    }

    public String getEndpointId() {
        return endpointId;
    }

    public McpEndpointHandle getHandle() {
        return handle;
    }

    /** The agent's in-process browser lane over this bridge's tools (no transport). */
    public com.aresstack.askai.research.runtime.loop.ToolInvoker toolInvoker() {
        return invoker;
    }

    /** Register and publish the bridge tool set. Idempotent. */
    public void open() {
        if (closed || handle != null) {
            return;
        }
        handle = registry.registerEndpoint(new McpEndpointDefinition(endpointId, "Browser"));
        List<McpToolContribution> tools = new ArrayList<McpToolContribution>();
        tools.add(passThrough("web_search", "Search the web via the configured provider.",
                McpToolParameter.string("query", true, "The search query")));
        // A4: the typed two-step SERP layout preparation/repair — pure JSON pass-throughs (capture ids
        // are assigned when the loop later web_opens the resolved candidate urls).
        tools.add(passThrough("web_search_prepare",
                "Prepare a web search; returns organic candidates or bounded layout-repair tickets (JSON).",
                McpToolParameter.string("query", true, "The search query")));
        tools.add(passThrough("web_search_apply_layout",
                "Apply a runtime-validated layout decision to a cached snapshot (JSON in/out).",
                McpToolParameter.string("submission", true, "The validated repair submission (JSON)")));
        tools.add(passThrough("web_search_discard_repair",
                "Discard a pending layout-repair ticket held in the browser runtime.",
                McpToolParameter.string("repairTicketId", true, "The repair ticket id")));
        tools.add(capturing("web_open", "Open a URL and return the cleaned page text with a capture id.",
                McpToolParameter.string("url", true, "The http(s) URL to open")));
        tools.add(capturing("web_read", "Return the cleaned text of the current page with a capture id."));
        // Two-step visit: probe/re-probe report readability signals (no capture); dismiss clears a banner.
        tools.add(passThrough("web_probe",
                "Navigate to a URL and return a readability probe (no full text, no capture id).",
                McpToolParameter.string("url", true, "The http(s) URL to probe")));
        tools.add(passThrough("web_reprobe",
                "Re-probe the current page without navigating (after a dismiss or while waiting)."));
        tools.add(passThrough("web_dismiss_consent",
                "Try to dismiss a consent/cookie banner on the current page ('clicked:…' or 'none')."));
        tools.add(passThrough("web_links", "List the links of the current page with stable ids."));
        tools.add(capturing("web_follow", "Follow a link by its id from web_links.",
                McpToolParameter.string("link_id", true, "The link id to follow")));
        tools.add(capturing("web_back", "Go back to the previous page."));
        tools.add(passThrough("web_challenge_status",
                "Poll the pending manual challenge (CAPTCHA): CHALLENGE/RESOLVED/NONE lines."));
        // Research HUD browser runtime tools: the runtime may ONLY call tools the bridge publishes, so these MUST be
        // registered here too (the browser runtime implements them) — otherwise the call is rejected before Playwright
        // is ever reached ("Unknown tool"). Both are best-effort pass-throughs (no capture): render pushes a
        // serialized state onto the overlay, poll drains the user's overlay commands.
        tools.add(passThrough("web_hud_render",
                "Render the Research HUD overlay onto the current page (best-effort; ignored headless).",
                McpToolParameter.string("state", true, "The serialized ResearchHudState line")));
        // CONTROL PLANE: the poll goes through the runtime's out-of-band control lane, so a Skip is
        // deliverable even while a data command (probe/read/open) blocks the browser owner thread.
        tools.add(controlPassThrough("web_hud_poll",
                "Drain the HUD commands the user triggered in the overlay (PAUSE/RESUME/SKIP/…), or empty."));
        invoker.setTools(tools);
        registry.updateTools(handle, tools);
    }

    /** Unregister the bridge (invalidates its token). Idempotent. */
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

    // ------------------------------------------------------------------ tool construction

    private McpToolContribution passThrough(final String tool, String description,
                                            McpToolParameter... parameters) {
        return McpToolContribution.of(tool, description, new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                return delegate(tool, call.getArguments());
            }
        }, parameters);
    }

    /**
     * A control-plane pass-through: out of band (never behind a blocked data call), never starts a
     * browser, and never errors — control is best-effort, a dead browser is the DATA calls' verdict.
     */
    private McpToolContribution controlPassThrough(final String tool, String description,
                                                   McpToolParameter... parameters) {
        return McpToolContribution.of(tool, description, new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                try {
                    return McpToolResult.ok(browser.executeControl(tool, call.getArguments()));
                } catch (BrowserRuntimePort.BrowserRuntimeException ex) {
                    return McpToolResult.ok(""); // nothing to report — not an error
                }
            }
        }, parameters);
    }

    /** Page-returning tools additionally record a VISITED capture and prepend the contract line. */
    private McpToolContribution capturing(final String tool, String description,
                                          McpToolParameter... parameters) {
        return McpToolContribution.of(tool, description, new McpToolHandler() {
            public McpToolResult invoke(McpToolCall call) {
                McpToolResult raw = delegate(tool, call.getArguments());
                if (raw.isError()) {
                    return raw;
                }
                String[] page = parseBrowserPage(raw.getText());
                VisitedCapture capture = captures.record(page[0], page[1], page[2]);
                return McpToolResult.ok("URL: " + page[0] + " title=\"" + page[1]
                        + "\" capture_id=" + capture.getCaptureId() + "\n" + page[2]);
            }
        }, parameters);
    }

    private McpToolResult delegate(String tool, Map<String, Object> arguments) {
        // The FIRST browser command lazily starts the browser runtime on the runtime's dedicated owner thread
        // (never the EDT); a dead generation is restarted+retried once inside the port.
        try {
            return McpToolResult.ok(browser.execute(tool, arguments));
        } catch (BrowserRuntimePort.BrowserRuntimeException ex) {
            return McpToolResult.error(ex.isEndpointUnavailable()
                    ? "Browser runtime unavailable: " + ex.getMessage() : ex.getMessage());
        }
    }

    /** Parse the browser's render format ("URL: u\nTITLE: t\n[(truncated)]\n\ntext") into {url, title, text}. */
    static String[] parseBrowserPage(String rendered) {
        String url = "";
        String title = "";
        StringBuilder text = new StringBuilder();
        boolean inText = false;
        for (String line : (rendered == null ? "" : rendered).split("\n", -1)) {
            if (!inText && line.startsWith("URL: ")) {
                url = line.substring(5).trim();
            } else if (!inText && line.startsWith("TITLE: ")) {
                title = line.substring(7).trim();
            } else if (!inText && line.trim().isEmpty()) {
                inText = true;
            } else if (inText) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(line);
            }
        }
        return new String[]{url, title, text.toString()};
    }
}
