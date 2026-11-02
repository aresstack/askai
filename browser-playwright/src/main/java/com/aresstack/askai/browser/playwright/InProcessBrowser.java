package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;

import java.io.IOException;
import java.util.Map;
import java.util.function.Supplier;

/**
 * AskAI's research browser inside the AskAI process (askai #31, Java 21): the same Playwright session, actor
 * thread, Camoufox start and tool set the browser sidecar runs, called directly instead of over MCP. One
 * instance is one browser generation; {@link #close()} ends it (page, context, browser, Playwright).
 */
public final class InProcessBrowser implements AutoCloseable {

    private final BrowserSessionActor session;
    private final BrowserTools tools;
    private volatile boolean closed;

    private InProcessBrowser(BrowserSessionActor session, BrowserTools tools) {
        this.session = session;
        this.tools = tools;
    }

    /**
     * Starts the browser and blocks until it is ready (Camoufox is downloaded first if needed).
     *
     * @param channel           {@code camoufox} (default), {@code chrome} or {@code msedge}
     * @param browserConfigPath the versioned browser-search config document, or {@code null} for the defaults
     * @throws IOException              the config document cannot be read
     * @throws IllegalArgumentException the config document is invalid
     */
    public static InProcessBrowser start(final String channel, final boolean headless,
                                         final boolean allowPrivateNetworks, String browserConfigPath)
            throws IOException {
        final LegacyBrowserSearchSettings settings = BrowserMcpSidecarMain.readBrowserConfig(browserConfigPath);
        BrowserSessionActor session = BrowserSessionActor.start(new Supplier<BrowserSession>() {
            public BrowserSession get() {
                return PlaywrightSessionFactory.create(channel, headless, allowPrivateNetworks, null,
                        BrowserLimits.defaults(), settings);
            }
        });
        SearchLayoutRepairTools repairTools = session.isPlaywrightBacked()
                ? new SearchLayoutRepairTools(settings, new PlaywrightRenderedPageSource(session),
                        System::currentTimeMillis)
                : null;
        System.err.println("[browser] started in-process backend=" + session.getBackendKind());
        return new InProcessBrowser(session, new BrowserTools(session, repairTools));
    }

    /**
     * Runs one browser tool ({@code web_open}, {@code web_search_prepare}, …) and returns its text, exactly as
     * the MCP tool returned it.
     *
     * @throws BrowserException the tool failed; the message is the readable reason
     */
    public String call(String tool, Map<String, Object> arguments) throws BrowserException {
        if (closed) {
            throw new BrowserException("Browser session is closed.");
        }
        return tools.call(tool, arguments);
    }

    /** Whether this generation can still serve tools. */
    public boolean isAlive() {
        return !closed;
    }

    /** Downloads and verifies the managed Camoufox without starting a browser; returns 0 on success. */
    public static int installCamoufox() {
        return CamoufoxProvisioning.installOnly();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        tools.close();
    }
}
