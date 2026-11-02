package com.aresstack.askai.browser.runtime;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchItem;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;

import java.util.Map;

/**
 * The browser tool set ({@code web_*}) as plain method calls on a {@link BrowserSession}: one place for the
 * text contract both the in-process browser ({@link InProcessBrowser}) and the MCP endpoint use. Every tool
 * returns the same text it always returned over MCP.
 */
final class BrowserTools {

    private final BrowserSession session;
    /** Null when the session is not Playwright-backed (no layout repair). */
    private final SearchLayoutRepairTools repairTools;

    BrowserTools(BrowserSession session, SearchLayoutRepairTools repairTools) {
        this.session = session;
        this.repairTools = repairTools;
    }

    boolean hasRepairTools() {
        return repairTools != null;
    }

    /**
     * Runs one tool.
     *
     * @throws BrowserException the tool failed (the message is the readable reason)
     * @throws IllegalArgumentException the tool is unknown
     */
    String call(String tool, Map<String, Object> args) throws BrowserException {
        switch (tool) {
            case "web_search":
                return search(str(args, "query"));
            case "web_open":
                return render(session.open(str(args, "url")));
            case "web_read":
                return render(session.currentPage());
            case "web_probe":
                return session.probe(str(args, "url")).render();
            case "web_reprobe":
                return session.probeCurrent().render();
            case "web_dismiss_consent":
                return session.dismissConsent();
            case "web_hud_render":
                return session.renderHud(str(args, "state"));
            case "web_hud_poll":
                return session.pollHudCommands();
            case "web_links":
                return links();
            case "web_follow":
                return render(session.follow(str(args, "link_id")));
            case "web_back":
                return render(session.back());
            case "web_challenge_status":
                return String.join("\n", session.challengeStatus());
            case "web_search_prepare":
                return repair().prepare(str(args, "query"));
            case "web_search_apply_layout":
                return repair().applyLayout(str(args, "submission"));
            case "web_search_discard_repair":
                return repair().discard(str(args, "repairTicketId"));
            default:
                throw new IllegalArgumentException("unknown browser tool: " + tool);
        }
    }

    void close() {
        if (repairTools != null) {
            repairTools.clear();
        }
        session.close();
    }

    private SearchLayoutRepairTools repair() throws BrowserException {
        if (repairTools == null) {
            throw new BrowserException("Layout repair needs the Playwright browser");
        }
        return repairTools;
    }

    private String search(String query) throws BrowserException {
        com.aresstack.askai.browser.WebSearchResult result = session.search(query);
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
        // A pending manual challenge travels typed WITH the results (same line format as
        // web_challenge_status), so the consumer can lock the domain family.
        for (String line : session.challengeStatus()) {
            if (line.startsWith("CHALLENGE: ")) {
                sb.append(line).append('\n');
            }
        }
        // One typed line per attempted engine — diagnostics, never candidates.
        for (com.aresstack.askai.browser.LegacySearchEngineAttemptResult attempt : result.getAttempts()) {
            sb.append("ATTEMPT: ").append(attempt.getSearchEngineHost())
              .append(' ').append(attempt.getOutcome()).append('\n');
        }
        return sb.length() == 0 ? "No results." : sb.toString();
    }

    private String links() throws BrowserException {
        StringBuilder sb = new StringBuilder();
        for (BrowserLink link : session.links()) {
            sb.append(link.getId()).append(": ").append(link.getText())
              .append(" — ").append(link.getUrl()).append('\n');
        }
        return sb.length() == 0 ? "No links." : sb.toString();
    }

    static String render(BrowserPageSnapshot snap) {
        return "URL: " + snap.getUrl() + "\nTITLE: " + snap.getTitle()
                + (snap.isTruncated() ? "\n(truncated)" : "") + "\n\n" + snap.getText();
    }

    static String str(Map<String, Object> args, String key) throws BrowserException {
        Object v = args == null ? null : args.get(key);
        if (v == null || String.valueOf(v).trim().isEmpty()) {
            throw new BrowserException("Missing required argument: " + key);
        }
        return String.valueOf(v);
    }
}
