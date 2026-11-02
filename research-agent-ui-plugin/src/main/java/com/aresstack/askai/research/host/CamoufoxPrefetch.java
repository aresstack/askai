package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.runtime.InProcessBrowser;

/**
 * Downloads the managed Camoufox browser in the background as soon as a productive research session with the
 * Camoufox channel starts, so the first browser command does not wait for the one-time download (about
 * 1.3 GB). Runs the same installer the browser uses at start (its install lock serializes concurrent
 * installs) on a daemon thread of this process, at most once per AskAI process. An already verified
 * installation finishes immediately.
 */
final class CamoufoxPrefetch {

    private static boolean started;

    private CamoufoxPrefetch() {
    }

    static synchronized void startOnce(ResearchRuntimeConfig config) {
        if (started || !ResearchRuntimeSettings.CAMOUFOX_CHANNEL.equals(config.getBrowserChannel())) {
            return;
        }
        started = true;
        Thread install = new Thread(new Runnable() {
            public void run() {
                System.err.println("[camoufox-prefetch] finished with exit code " + InProcessBrowser.installCamoufox());
            }
        }, "camoufox-prefetch");
        install.setDaemon(true);
        install.start();
    }
}
