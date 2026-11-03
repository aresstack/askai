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

    /** What the managed Camoufox installation is doing right now (for a progress bar, e.g. in the setup wizard). */
    public static com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller.Status camoufoxStatus() {
        return CamoufoxProvisioning.installer().status();
    }

    /** Status of the Camoufox in a chosen folder (the folder itself or the root that holds the installation). */
    public static com.aresstack.askai.browser.playwright.camoufox.CamoufoxInstaller.Status camoufoxStatus(String folder) {
        return CamoufoxProvisioning.installerFor(java.nio.file.Paths.get(folder)).status();
    }

    /**
     * What a chosen folder holds: {@code VERIFIED} (AskAI's pinned Camoufox, used as it is), {@code FOREIGN} (a
     * Camoufox that does not verify: never downloaded over) or {@code NONE} (a download target).
     */
    public static String inspectCamoufox(String folder) {
        return CamoufoxProvisioning.inspect(java.nio.file.Paths.get(folder).toAbsolutePath().normalize()).name();
    }

    /** The verified Camoufox found without configuration (AskAI's own installation folder), or "". */
    public static String foundCamoufox() {
        java.nio.file.Path found = CamoufoxProvisioning.foundInstallation();
        return found == null ? "" : found.toString();
    }

    /** Where Camoufox goes when no folder is chosen. */
    public static String defaultCamoufoxFolder() {
        return CamoufoxProvisioning.defaultRoot().toString();
    }

    /**
     * Downloads and verifies Camoufox into a chosen folder (an existing verified installation there is reused, a
     * foreign Camoufox is never downloaded over) and uses it from then on; returns 0 on success.
     */
    public static int installCamoufox(String folder) {
        java.nio.file.Path chosen = java.nio.file.Paths.get(folder).toAbsolutePath().normalize();
        try {
            if (CamoufoxProvisioning.inspect(chosen) == CamoufoxProvisioning.Found.FOREIGN) {
                throw new IllegalStateException("a Camoufox that is not AskAI's verified build is in " + chosen
                        + "; nothing is downloaded over it");
            }
            CamoufoxProvisioning.awaitInstalled(CamoufoxProvisioning.installerFor(chosen));
            useCamoufox(chosen.toString());
            return 0;
        } catch (RuntimeException failed) {
            System.err.println("[browser] " + CamoufoxProvisioning.PROGRESS_MARKER + " FAILED " + failed.getMessage());
            return 1;
        }
    }

    /**
     * Uses the Camoufox in {@code folder} for every browser started afterwards in this process, when it holds the
     * verified installation; blank = the automatic choice again.
     */
    public static void useCamoufox(String folder) {
        if (folder == null || folder.trim().isEmpty()) {
            System.clearProperty(CamoufoxProvisioning.ROOT_PROPERTY);
            return;
        }
        java.nio.file.Path chosen = java.nio.file.Paths.get(folder.trim()).toAbsolutePath().normalize();
        if (CamoufoxProvisioning.inspect(chosen) == CamoufoxProvisioning.Found.VERIFIED) {
            System.setProperty(CamoufoxProvisioning.ROOT_PROPERTY, chosen.toString());
        }
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
