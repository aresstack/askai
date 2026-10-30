package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.Map;

/**
 * One tab (WebDriver BiDi {@code browsingContext.BrowsingContext}) of a worker user context. {@link #call}
 * runs a {@code web_*} tool with exactly the text contract of the chat browser ({@link BrowserToolSet}), on
 * the owner thread of the worker browser, bounded by the per-call timeout and by the per-site limit.
 */
public final class WorkerBrowsingContext implements AutoCloseable {

    private final WorkerUserContext userContext;
    private final String backendId;
    private final String id;
    /** The site of the page this tab shows (gate key for follow/back), or null before the first page. */
    private volatile String currentSite;
    private volatile boolean closed;

    WorkerBrowsingContext(WorkerUserContext userContext, String backendId, String id) {
        this.userContext = userContext;
        this.backendId = backendId;
        this.id = id;
    }

    /** {@code <user context id>/bc<n>}. */
    public String id() {
        return id;
    }

    public WorkerUserContext userContext() {
        return userContext;
    }

    public boolean isOpen() {
        return !closed && userContext.isOpen();
    }

    /**
     * Run one tool in this tab. Failures that mean the tab, its user context or its worker browser is gone
     * start with {@code USER_CONTEXT_LOST}; a timed-out call terminates the worker browser.
     */
    public String call(final String tool, final Map<String, Object> arguments)
            throws BrowserException, InterruptedException {
        if (closed || userContext.isClosed()) {
            throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — browsing context " + id + " is closed.");
        }
        PlaywrightUserContextPool pool = userContext.pool();
        long timeout = pool.limits().callTimeoutMillis;
        String site = siteKey(tool, arguments);
        pool.sites().enter(site, timeout);
        try {
            String result = userContext.lane().call(new WorkerBrowserLane.LaneTask<String>() {
                public String run(WorkerBrowserBackend backend) throws BrowserException {
                    return backend.call(backendId, tool, arguments);
                }
            }, timeout);
            rememberSite(result);
            return result;
        } catch (BrowserException failed) {
            String message = failed.getMessage() == null ? "" : failed.getMessage();
            if (message.contains("BROWSER_CLOSED")) {
                // In a worker the closed window is not the user's stop of the research: only this worker
                // lost its user context. Never let the chat-level marker leak into the run's fate.
                throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — the worker tab or its browser "
                        + "window was closed (" + id + ").");
            }
            throw failed;
        } finally {
            pool.sites().leave(site);
        }
    }

    /** Close this tab only. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!userContext.isClosed()) {
            userContext.closeTab(backendId);
        }
    }

    void markClosed() {
        closed = true;
    }

    private String siteKey(String tool, Map<String, Object> arguments) {
        if (tool == null) {
            return null;
        }
        switch (tool) {
            case "web_search":
            case "web_search_prepare":
                return SiteConcurrencyGate.SEARCH_KEY;
            case "web_open":
            case "web_probe":
                Object url = arguments == null ? null : arguments.get("url");
                return url == null ? null : SiteConcurrencyGate.siteOf(String.valueOf(url));
            case "web_follow":
            case "web_back":
                return currentSite;
            default:
                return null; // reads of the current page, HUD, challenge poll, cached-snapshot repair
        }
    }

    private void rememberSite(String result) {
        if (result != null && result.startsWith("URL: ")) {
            int end = result.indexOf('\n');
            String site = SiteConcurrencyGate.siteOf(end > 0 ? result.substring(5, end) : result.substring(5));
            if (site != null) {
                currentSite = site;
            }
        }
    }

    @Override
    public String toString() {
        return id;
    }
}
