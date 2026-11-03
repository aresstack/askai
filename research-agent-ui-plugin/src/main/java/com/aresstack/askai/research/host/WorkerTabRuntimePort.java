package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.playwright.PlaywrightUserContextPool;
import com.aresstack.askai.browser.playwright.WorkerBrowsingContext;

import java.util.Map;

/**
 * A worker tab seen as a {@link BrowserRuntimePort}, so the unchanged {@link BrowserBridgeEndpoint} tool set
 * (and its capture convention) runs on top of it. The tab has no lifecycle of its own to manage here: its
 * worker browser is started by the pool and the tab ends with its lease; a lost tab is reported as
 * endpoint-unavailable and never restarted behind the worker's back (its sign-in would be gone).
 */
final class WorkerTabRuntimePort implements BrowserRuntimePort {

    private final WorkerBrowsingContext tab;

    WorkerTabRuntimePort(WorkerBrowsingContext tab) {
        this.tab = tab;
    }

    @Override
    public void setListener(Listener listener) {
    }

    @Override
    public String execute(String tool, Map<String, Object> arguments) throws BrowserRuntimeException {
        try {
            return tab.call(tool, arguments);
        } catch (BrowserException failed) {
            String message = failed.getMessage() == null ? "browser call failed" : failed.getMessage();
            throw new BrowserRuntimeException(message, message.startsWith(PlaywrightUserContextPool.USER_CONTEXT_LOST));
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new BrowserRuntimeException(PlaywrightUserContextPool.USER_CONTEXT_LOST
                    + " — the worker was cancelled during a browser call.", true);
        }
    }

    @Override
    public String executeControl(String tool, Map<String, Object> arguments) {
        return ""; // the HUD control plane belongs to the chat browser; a worker tab has none
    }

    @Override
    public void ensureStarted() {
    }

    @Override
    public void restart() throws BrowserRuntimeException {
        throw new BrowserRuntimeException("a worker tab is not restartable", true);
    }

    @Override
    public void stop() {
    }

    @Override
    public boolean isReady() {
        return tab.isOpen();
    }

    @Override
    public void close() {
        tab.close();
    }
}
