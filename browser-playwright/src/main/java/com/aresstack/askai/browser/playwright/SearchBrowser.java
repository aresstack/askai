package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.playwright.camoufox.CamoufoxLaunch;
import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.playwright.PlaywrightSearchProvider;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

/**
 * AskAI's search browser (askai #31, Serp4J ADR-020): a headless browser, separate from the research browser,
 * that AskAI starts once, keeps running, starts again after a crash and passes to a Serp4J
 * {@link PlaywrightSearchProvider} with every search. Serp4J never starts or stops it.
 *
 * <ul>
 *   <li>Camoufox (default) runs with the same executable, fingerprint profile and preferences as the research
 *       browser, but is started with {@code launch()} instead of a persistent context: Playwright only returns
 *       a {@link Browser} object for a launched browser. One browser context is created after the start and
 *       lives as long as the browser, so cookies and consent answers survive between searches.</li>
 *   <li>All Playwright calls run on one owner thread (Playwright objects are not thread-safe); searches are
 *       serialised. The browser starts on the first search.</li>
 *   <li>A disconnected browser is started again before the next search; a browser that dies during a search
 *       is started again and the search is repeated once.</li>
 * </ul>
 */
public final class SearchBrowser implements AutoCloseable {

    private final String channel;
    private final ExecutorService owner;
    private volatile boolean closed;
    // Touched only on the owner thread.
    private Playwright playwright;
    private Browser browser;
    private int launches;

    /** @param channel {@code camoufox} (default) or a Chromium channel such as {@code chrome} / {@code msedge} */
    public SearchBrowser(String channel) {
        this.channel = channel == null || channel.trim().isEmpty() ? CamoufoxProvisioning.CHANNEL : channel.trim();
        this.owner = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "search-browser-owner");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Runs one search with the current search browser and waits for it.
     *
     * @throws SearchProviderException the provider's failure, or {@code UNAVAILABLE} when the browser does not
     *                                 start
     */
    public SearchResponse search(PlaywrightSearchProvider provider, SearchRequest request, SearchContext context) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(context, "context");
        return onOwner(provider, () -> {
            Browser current = ensureBrowser(provider);
            try {
                return provider.search(request, context, current);
            } catch (SearchProviderException e) {
                if (e.getCategory() != SearchErrorCategory.UNAVAILABLE || current.isConnected()
                        || context.getCancellation().isCancelled()) {
                    throw e;
                }
                System.err.println("[search-browser] browser died during the search; starting it again");
                return provider.search(request, context, ensureBrowser(provider));
            }
        });
    }

    /** How often the browser was started (1 after the first search, +1 per restart). */
    public int getLaunchCount() {
        try {
            return owner.submit(() -> launches).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return launches;
        } catch (ExecutionException | RejectedExecutionException e) {
            return launches;
        }
    }

    public String getChannel() {
        return channel;
    }

    private Browser ensureBrowser(PlaywrightSearchProvider provider) {
        if (browser != null && browser.isConnected()) {
            return browser;
        }
        closeBrowserQuietly();
        try {
            if (playwright == null) {
                Map<String, String> env = new LinkedHashMap<String, String>();
                env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
                env.put("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1");
                playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            }
            boolean camoufox = CamoufoxProvisioning.CHANNEL.equals(channel);
            browser = camoufox ? launchCamoufox(playwright)
                    : playwright.chromium().launch(new BrowserType.LaunchOptions().setChannel(channel)
                            .setHeadless(true));
            // The one context of this browser: the provider opens its pages here, cookies stay between searches.
            browser.newContext(camoufox
                    ? new Browser.NewContextOptions().setAcceptDownloads(false).setViewportSize(null)
                            .setColorScheme(null).setReducedMotion(null).setForcedColors(null).setContrast(null)
                    : new Browser.NewContextOptions().setAcceptDownloads(false));
            launches++;
            System.err.println("[search-browser] started " + channel + " headless (start " + launches + ")");
            return browser;
        } catch (RuntimeException e) {
            closeBrowserQuietly();
            closePlaywrightQuietly();
            throw SearchProviderException.builder(provider.getDescriptor().getId(), SearchErrorCategory.UNAVAILABLE,
                            "search browser could not be started: " + e.getClass().getSimpleName())
                    .retryable(true).cause(e).build();
        }
    }

    private static Browser launchCamoufox(Playwright playwright) {
        Path executable = CamoufoxProvisioning.ensureInstalled();
        CamoufoxLaunch launch;
        try {
            launch = CamoufoxLaunch.builtIn(executable, CamoufoxProvisioning.browserRoot(), Locale.getDefault());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Camoufox launch profile unreadable", e);
        }
        Map<String, String> environment = new HashMap<String, String>(System.getenv());
        environment.putAll(launch.environment());
        Map<String, Object> prefs = new LinkedHashMap<String, Object>(launch.prefs());
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setChannel("moz-firefox")
                .setExecutablePath(executable)
                .setHeadless(true)
                .setEnv(environment)
                .setFirefoxUserPrefs(prefs);
        if (!launch.args().isEmpty()) {
            options.setArgs(launch.args());
        }
        return playwright.firefox().launch(options);
    }

    private SearchResponse onOwner(PlaywrightSearchProvider provider, Callable<SearchResponse> work) {
        if (closed) {
            throw new SearchProviderException(provider.getDescriptor().getId(), SearchErrorCategory.CANCELLED,
                    "search browser is closed");
        }
        Future<SearchResponse> future;
        try {
            future = owner.submit(work);
        } catch (RejectedExecutionException e) {
            throw new SearchProviderException(provider.getDescriptor().getId(), SearchErrorCategory.CANCELLED,
                    "search browser is closed");
        }
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new SearchProviderException(provider.getDescriptor().getId(), SearchErrorCategory.CANCELLED,
                    "interrupted while waiting for the search browser");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SearchProviderException) {
                throw (SearchProviderException) cause;
            }
            throw SearchProviderException.builder(provider.getDescriptor().getId(), SearchErrorCategory.INTERNAL,
                    "search failed: " + cause.getClass().getSimpleName()).cause(cause).build();
        }
    }

    private void closeBrowserQuietly() {
        if (browser != null) {
            try {
                browser.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            browser = null;
        }
    }

    private void closePlaywrightQuietly() {
        if (playwright != null) {
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            playwright = null;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            owner.submit(() -> {
                closeBrowserQuietly();
                closePlaywrightQuietly();
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | RejectedExecutionException ignored) {
            // best effort
        } finally {
            owner.shutdown();
        }
    }
}
