package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The engines the product knows how to drive through a browser. Ids are stable — they are persisted. */
public final class BrowserSearchEngineCatalog {

    public static final String DUCKDUCKGO = "duckduckgo";
    public static final String BING = "bing";
    /**
     * The engine a dev/test {@code --search-url=} override installs. It is not part of the catalog: it
     * exists only when someone hands the browser runtime an explicit template, and it then REPLACES the
     * configured engines rather than joining them.
     */
    public static final String CUSTOM = "custom";

    private static final List<BrowserSearchEngine> ENGINES = Collections.unmodifiableList(
            new ArrayList<BrowserSearchEngine>(Arrays.asList(
                    new BrowserSearchEngine(DUCKDUCKGO, "DuckDuckGo", Arrays.asList(
                            // Server-rendered endpoints of the SAME provider: the plain HTML one first,
                            // the lite one as its own fallback. One provider to the user.
                            "https://html.duckduckgo.com/html/?q={query}",
                            "https://lite.duckduckgo.com/lite/?q={query}"),
                            // Follow-up result pages address the provider's `s` offset (~30/page).
                            Arrays.asList(
                                    "https://html.duckduckgo.com/html/?q={query}&s={offset0}",
                                    "https://lite.duckduckgo.com/lite/?q={query}&s={offset0}"),
                            30),
                    new BrowserSearchEngine(BING, "Bing", Arrays.asList(
                            "https://www.bing.com/search?q={query}"),
                            // Bing pages via `first` (1-based offset of the first result, 10/page).
                            Arrays.asList("https://www.bing.com/search?q={query}&first={offset1}"),
                            10))));

    private BrowserSearchEngineCatalog() {
    }

    /** All known engines in catalog order (NOT the execution order — that is the user's). */
    public static List<BrowserSearchEngine> engines() {
        return ENGINES;
    }

    /** @return the engine with this id, or {@code null} when the id is unknown (e.g. an older config). */
    public static BrowserSearchEngine byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim();
        for (BrowserSearchEngine engine : ENGINES) {
            if (engine.getId().equals(wanted)) {
                return engine;
            }
        }
        return null;
    }

    /** The single-endpoint engine an explicit {@code --search-url} template stands for. */
    public static BrowserSearchEngine custom(String urlTemplate) {
        return new BrowserSearchEngine(CUSTOM, "Custom", Collections.singletonList(urlTemplate));
    }
}
