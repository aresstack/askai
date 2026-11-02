package com.aresstack.askai.browser.sidecar;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchItem;

import org.noear.solon.Solon;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import org.noear.solon.ai.chat.tool.ToolHandler;
import org.noear.solon.ai.mcp.server.McpServerEndpointProvider;

import java.util.Map;

/**
 * Entry point of the Java-21 Browser-MCP sidecar process. Boots a Solon streamable-HTTP MCP endpoint on
 * 127.0.0.1 with a caller-provided port and session token (both passed by the spawning host) and exposes the
 * exact flat tool set {@code web_search, web_open, web_read, web_links, web_follow, web_back} over a
 * {@link BrowserSession}. Args: {@code --port=<port> --token=<token>} plus optional
 * {@code --browser-channel=camoufox|chrome|msedge} (default camoufox), {@code --headless=true|false} (default true),
 * {@code --allow-private=true|false} (default false; true only for local test servers) and
 * {@code --search-url=<template with {query}>}. Logs go to STDERR only; the token never appears in them.
 *
 * <p>The Playwright-backed session ({@link PlaywrightSessionFactory}) runs a structured capability probe;
 * when the runtime is unavailable every tool reports the SPECIFIC status as a readable error. A missing
 * driver or browser never breaks packaging or the host build. The only download is the managed Camoufox
 * (once, verified; also standalone via {@code --install-camoufox}). See problems.md MCP-P005.</p>
 */
public final class BrowserMcpSidecarMain {

    private BrowserMcpSidecarMain() {
    }

    public static void main(String[] args) {
        if (java.util.Arrays.asList(args).contains("--install-camoufox")) {
            // One-time download/verification of the managed Camoufox (the host's background prefetch);
            // dies with the host like the server mode.
            ParentDeathWatchdog.install("camoufox-install");
            System.exit(CamoufoxProvisioning.installOnly());
        }
        int port = intArg(args, "--port=", 0);
        String token = stringArg(args, "--token=");
        if (port <= 0 || token == null || token.isEmpty()) {
            System.err.println("usage: --port=<port> --token=<token>");
            System.exit(2);
        }

        // Die with the parent (stdin EOF): a hard-killed agent/host must never leave this JVM
        // (and its Playwright browser) behind — part of the Zulu orphan-leak fix.
        ParentDeathWatchdog.install("browser-sidecar");
        Solon.start(BrowserMcpSidecarMain.class, new String[]{
                "--server.host=127.0.0.1",
                "--server.port=" + port
        });

        final String channel = stringArg(args, "--browser-channel=");
        // PRECEDENCE (fixed contract): defaults < --browser-config document < explicit legacy CLI
        // overrides (dev/test escape hatches) — never any other mix.
        final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings =
                loadBrowserConfig(stringArg(args, "--browser-config="));
        final boolean headless = !"false".equalsIgnoreCase(stringArg(args, "--headless="));
        final boolean allowPrivate = "true".equalsIgnoreCase(stringArg(args, "--allow-private="));
        final String searchUrl = stringArg(args, "--search-url=");
        // The SESSION-ACTOR contract: Playwright and everything below it is created ON the dedicated
        // owner thread and only ever touched there; the MCP handlers below talk to the actor façade,
        // which also pumps the Playwright event loop while idle (route/binding/popup dispatch).
        BrowserSessionActor session = BrowserSessionActor.start(
                new java.util.function.Supplier<BrowserSession>() {
                    public BrowserSession get() {
                        return PlaywrightSessionFactory.create(
                                channel, headless, allowPrivate,
                                searchUrl, com.aresstack.askai.browser.BrowserLimits.defaults(),
                                searchSettings);
                    }
                });
        // DEV/TEST ONLY: host:port domain families so local multi-server worlds act as distinct domains
        // (production keeps the public-suffix resolver; never the default).
        if ("host-port".equalsIgnoreCase(stringArg(args, "--domain-key-mode="))
                && session.isPlaywrightBacked()) {
            try {
                session.onPlaywrightSession(
                        new BrowserSessionActor.PlaywrightSessionTask<Void>() {
                            public Void run(PlaywrightBrowserSession playwrightSession) {
                                playwrightSession.setDomainKeyResolver(
                                        new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver());
                                return null;
                            }
                        });
                System.err.println("[browser-mcp] domain-key-mode=host-port (dev/test)");
            } catch (BrowserException ex) {
                System.err.println("[browser-mcp] domain-key-mode failed: " + ex.getMessage());
            }
        }
        McpServerEndpointProvider endpoint = McpServerEndpointProvider.builder()
                .name("browser")
                .version("0.1")
                .channel("streamable")
                .mcpEndpoint("/mcp/browser/" + token)
                .build();
        registerTools(endpoint, session);
        // A4: the three layout-repair tools, backed by the SAME engine navigation as web_search. The
        // sidecar stays MODEL-FREE — it only captures/extracts and applies a runtime-validated
        // decision to a cached snapshot. web_search remains the unchanged compatibility surface.
        final com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools repairTools =
                session.isPlaywrightBacked()
                        ? new com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools(
                                searchSettings,
                                new PlaywrightRenderedPageSource(session),
                                new java.util.function.LongSupplier() {
                                    public long getAsLong() {
                                        return System.currentTimeMillis();
                                    }
                                })
                        : null;
        if (repairTools != null) {
            registerRepairTools(endpoint, repairTools);
        }
        endpoint.postStart();
        // Ordered teardown even on SIGTERM: clear the repair-ticket cache, then page/context/browser/
        // driver-child close before the JVM exits, so no Chromium process is left behind.
        final BrowserSession toClose = session;
        final com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools toClear =
                repairTools;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                if (toClear != null) {
                    toClear.clear();
                }
                toClose.close();
            }
        }, "browser-session-shutdown"));
        System.err.println("[browser-mcp] ready on 127.0.0.1:" + port
                + " backend=" + session.getBackendKind());
    }

    /**
     * Load the typed settings from the {@code --browser-config} document. Without the argument the
     * settings are exactly {@code LegacyBrowserSearchDefaults.create()} (the single default origin).
     * An unreadable, malformed or invalid document FAILS the start with the concrete reason on STDERR
     * — a broken configuration never silently degrades to defaults.
     */
    static com.aresstack.askai.browser.search.LegacyBrowserSearchSettings loadBrowserConfig(
            String path) {
        try {
            return readBrowserConfig(path);
        } catch (java.io.IOException ex) {
            System.err.println("[browser-mcp] FATAL: cannot read --browser-config=" + path
                    + ": " + ex.getMessage());
            System.exit(2);
            throw new IllegalStateException("unreachable");
        } catch (IllegalArgumentException ex) {
            System.err.println("[browser-mcp] FATAL: " + ex.getMessage());
            System.exit(2);
            throw new IllegalStateException("unreachable");
        }
    }

    /**
     * The typed settings of a browser-config document, or the central defaults for a {@code null}/blank path.
     *
     * @throws java.io.IOException      the document cannot be read
     * @throws IllegalArgumentException the document is malformed or invalid (the message says why)
     */
    static com.aresstack.askai.browser.search.LegacyBrowserSearchSettings readBrowserConfig(String path)
            throws java.io.IOException {
        if (path == null || path.trim().isEmpty()) {
            return com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        }
        byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path.trim()));
        com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument document =
                com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument
                        .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.Decoded decoded =
                com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec
                        .fromValues(document.values);
        if (!decoded.violations.isEmpty()) {
            throw new IllegalArgumentException("browser config has invalid values:\n"
                    + new com.aresstack.askai.browser.search.SettingsValidationResult(
                            decoded.violations).describe());
        }
        com.aresstack.askai.browser.search.SettingsValidationResult validation =
                new com.aresstack.askai.browser.search.DefaultLegacyBrowserSearchSettingsValidator()
                        .validate(decoded.settings);
        if (!validation.isValid()) {
            throw new IllegalArgumentException("browser config failed validation:\n"
                    + validation.describe());
        }
        System.err.println("[browser] browser config: revision=" + document.settingsRevision
                + " digest=" + document.settingsDigest);
        return decoded.settings;
    }

    /**
     * The A4 layout-repair tool surface, additive to the legacy flat tool set. The runtime uses these
     * for the typed two-step repair; the sidecar remains model-free.
     */
    static void registerRepairTools(McpServerEndpointProvider endpoint,
            final com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools tools) {
        endpoint.addTool(new FunctionToolDesc("web_search_prepare")
                .description("Prepare a web search; returns typed organic candidates, or bounded "
                        + "layout-repair tickets (JSON) for low-confidence result pages.")
                .stringParamAdd("query", "The search query")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return tools.prepare(str(args, "query"));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_search_apply_layout")
                .description("Apply a runtime-validated layout decision to a cached result-page "
                        + "snapshot; returns typed candidates or a typed rejection (JSON).")
                .stringParamAdd("submission", "The validated repair submission (JSON)")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return tools.applyLayout(str(args, "submission"));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_search_discard_repair")
                .description("Discard a pending layout-repair ticket held in the sidecar.")
                .stringParamAdd("repairTicketId", "The repair ticket id")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return tools.discard(str(args, "repairTicketId"));
                    }
                }));
    }

    static void registerTools(McpServerEndpointProvider endpoint, final BrowserSession session) {
        endpoint.addTool(new FunctionToolDesc("web_search")
                .description("Search the web; returns numbered results with title, url and snippet.")
                .stringParamAdd("query", "The search query")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        com.aresstack.askai.browser.WebSearchResult result =
                                session.search(str(args, "query"));
                        StringBuilder sb = new StringBuilder();
                        // Consumers treat every engine host as transit — never an evidence source.
                        for (String providerHost : result.getProviderHosts()) {
                            sb.append("PROVIDER: ").append(providerHost).append('\n');
                        }
                        for (WebSearchItem item : result.getItems()) {
                            sb.append(item.getId()).append(": ").append(item.getTitle())
                              .append(" — ").append(item.getUrl()).append('\n')
                              .append("   ").append(item.getSnippet()).append('\n');
                        }
                        // A pending manual challenge travels typed WITH the results (same line format
                        // as web_challenge_status), so the consumer can lock the domain family.
                        for (String line : session.challengeStatus()) {
                            if (line.startsWith("CHALLENGE: ")) {
                                sb.append(line).append('\n');
                            }
                        }
                        // One typed line per attempted engine — diagnostics, never candidates.
                        for (com.aresstack.askai.browser.LegacySearchEngineAttemptResult attempt
                                : result.getAttempts()) {
                            sb.append("ATTEMPT: ").append(attempt.getSearchEngineHost())
                              .append(' ').append(attempt.getOutcome()).append('\n');
                        }
                        return sb.length() == 0 ? "No results." : sb.toString();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_open")
                .description("Open a URL and return the cleaned page text.")
                .stringParamAdd("url", "The http(s) URL to open")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.open(str(args, "url")));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_read")
                .description("Return the cleaned text of the current page.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.currentPage());
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_probe")
                .description("Step 1 of a two-step visit: navigate to a URL and return a readability PROBE "
                        + "(final url, title, text_length, a short excerpt, and consent/challenge signals) "
                        + "WITHOUT the full text. Follow with web_dismiss_consent / web_challenge_status and "
                        + "then web_read once readable.")
                .stringParamAdd("url", "The http(s) URL to probe")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return session.probe(str(args, "url")).render();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_reprobe")
                .description("Re-probe the CURRENT page without navigating (after dismissing a banner or "
                        + "while waiting for a CAPTCHA). Same fields as web_probe.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return session.probeCurrent().render();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_dismiss_consent")
                .description("Try to dismiss a consent/cookie banner on the CURRENT page by clicking one "
                        + "unambiguously positive control. Returns 'clicked:…' or 'none'.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return session.dismissConsent();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_hud_render")
                .description("Render the Research HUD overlay (Pause/Resume, Skip, one-line status, user-wait "
                        + "countdown) onto the CURRENT visited page. Arg 'state' is the serialized "
                        + "ResearchHudState. The overlay is capture-isolated (never part of web_read). "
                        + "Returns 'rendered' or 'unsupported'.")
                .stringParamAdd("state", "The serialized ResearchHudState")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return session.renderHud(str(args, "state"));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_hud_poll")
                .description("Drain the HUD commands the user triggered in the overlay (newline-separated "
                        + "PAUSE/RESUME/SKIP/NEXT/…), or empty. Poll each tick while waiting or between pages.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return session.pollHudCommands();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_links")
                .description("List the links of the current page with stable ids.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        StringBuilder sb = new StringBuilder();
                        for (BrowserLink link : session.links()) {
                            sb.append(link.getId()).append(": ").append(link.getText())
                              .append(" — ").append(link.getUrl()).append('\n');
                        }
                        return sb.length() == 0 ? "No links." : sb.toString();
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_follow")
                .description("Follow a link by its id from web_links.")
                .stringParamAdd("link_id", "The link id to follow")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.follow(str(args, "link_id")));
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_back")
                .description("Go back to the previous page.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        return render(session.back());
                    }
                }));
        endpoint.addTool(new FunctionToolDesc("web_challenge_status")
                .description("Poll the pending manual challenge (CAPTCHA): CHALLENGE/RESOLVED/NONE lines.")
                .doHandle(new ToolHandler() {
                    public Object handle(Map<String, Object> args) throws Throwable {
                        StringBuilder sb = new StringBuilder();
                        for (String line : session.challengeStatus()) {
                            if (sb.length() > 0) {
                                sb.append('\n');
                            }
                            sb.append(line);
                        }
                        return sb.toString();
                    }
                }));
    }

    private static String render(BrowserPageSnapshot snap) {
        return "URL: " + snap.getUrl() + "\nTITLE: " + snap.getTitle()
                + (snap.isTruncated() ? "\n(truncated)" : "") + "\n\n" + snap.getText();
    }

    private static String str(Map<String, Object> args, String key) throws BrowserException {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new BrowserException("Missing required argument: " + key);
        }
        return String.valueOf(v);
    }

    private static int intArg(String[] args, String prefix, int fallback) {
        String v = stringArg(args, prefix);
        try {
            return v == null ? fallback : Integer.parseInt(v);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String stringArg(String[] args, String prefix) {
        for (String a : args) {
            if (a != null && a.startsWith(prefix)) {
                return a.substring(prefix.length());
            }
        }
        return null;
    }
}
