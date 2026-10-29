package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;

/**
 * Downloads the managed Camoufox browser in the background as soon as a productive research session with the
 * Camoufox channel starts, so the first browser command does not wait for the one-time download (about
 * 1.3 GB). Runs the same installer the in-process browser runtime uses at start (serialized through its
 * install lock), at most once per AskAI process, on a daemon thread. An already verified installation
 * finishes immediately.
 */
final class CamoufoxPrefetch {

    private static boolean started;

    private CamoufoxPrefetch() {
    }

    static synchronized void startOnce(String browserChannel) {
        if (started || !ResearchRuntimeSettings.CAMOUFOX_CHANNEL.equals(browserChannel)) {
            return;
        }
        started = true;
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    int code = PlaywrightBrowserRuntime.installCamoufox();
                    System.err.println("[camoufox-prefetch] finished with exit code " + code);
                } catch (RuntimeException failed) {
                    // Not fatal: the browser runtime installs on its first start anyway.
                    System.err.println("[camoufox-prefetch] failed: " + failed.getMessage());
                }
            }
        }, "camoufox-prefetch");
        worker.setDaemon(true);
        worker.start();
    }
}
