package com.aresstack.askai.research.runtime;

import com.aresstack.askai.browser.sidecar.SearchBrowser;
import com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry;
import com.aresstack.askai.research.runtime.search.provider.async.ModuleAsyncSearchGenerationFactory;

/**
 * What all research sessions of one AskAI JVM share (askai #48): the headless search browser (one per AskAI,
 * reused across searches and sessions, restarted after a crash by {@link SearchBrowser} itself) and the
 * search-provider registry with its HTTP clients. AskAI owns it and closes it when it ends.
 */
public final class ResearchRuntimeShared implements AutoCloseable {

    private static final long SEARCH_PROVIDER_TIMEOUT_MILLIS = 90_000L;

    private final String searchBrowserChannel;
    private SearchBrowser searchBrowser;
    private AsyncSearchProviderRegistry searchProviderRegistry;
    private boolean closed;

    /** @param searchBrowserChannel {@code camoufox} (default) or a Chromium channel */
    public ResearchRuntimeShared(String searchBrowserChannel) {
        this.searchBrowserChannel = searchBrowserChannel == null || searchBrowserChannel.trim().isEmpty()
                ? "camoufox" : searchBrowserChannel.trim();
    }

    /** The search browser; created on first use, started on the first search. */
    public synchronized SearchBrowser searchBrowser() {
        if (closed) {
            throw new IllegalStateException("research runtime resources are closed");
        }
        if (searchBrowser == null) {
            searchBrowser = new SearchBrowser(searchBrowserChannel);
        }
        return searchBrowser;
    }

    /** The search-provider registry; opened on first use. */
    public synchronized AsyncSearchProviderRegistry searchProviderRegistry() {
        if (closed) {
            throw new IllegalStateException("research runtime resources are closed");
        }
        if (searchProviderRegistry == null) {
            searchProviderRegistry = new AsyncSearchProviderRegistry(
                    ModuleAsyncSearchGenerationFactory.userHome(SEARCH_PROVIDER_TIMEOUT_MILLIS));
        }
        return searchProviderRegistry;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (searchBrowser != null) {
            searchBrowser.close();
        }
        if (searchProviderRegistry != null) {
            searchProviderRegistry.close();
        }
    }
}
