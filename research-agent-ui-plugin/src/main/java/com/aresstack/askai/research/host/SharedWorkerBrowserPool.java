package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.playwright.PlaywrightUserContextPool;
import com.aresstack.askai.browser.playwright.WorkerBrowserLimits;

/**
 * The ONE worker browser pool of the plugin: every research session (chat) opens its scope here, so the
 * worker-browser process cap and the per-site limit hold over all chats at once. Settings changes apply to
 * the next placement; running worker browsers and leases are not disturbed.
 */
public final class SharedWorkerBrowserPool {

    private static PlaywrightUserContextPool pool;

    private SharedWorkerBrowserPool() {
    }

    static synchronized PlaywrightUserContextPool get(WorkerBrowserLimits limits) {
        if (pool == null) {
            pool = new PlaywrightUserContextPool(limits);
        } else {
            pool.updateLimits(limits);
        }
        return pool;
    }

    /** Plugin stop: end every worker browser. */
    public static synchronized void shutdown() {
        if (pool != null) {
            pool.close();
            pool = null;
        }
    }
}
