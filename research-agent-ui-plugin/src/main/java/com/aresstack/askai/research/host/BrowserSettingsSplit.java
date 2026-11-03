package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCatalog;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Where each browser setting is shown. The catalog mixes two concerns, split here by what a field configures:
 * <ul>
 *   <li><b>Browsing</b> (tab "Browser", needed with EVERY search provider, because the hits are always opened
 *       in the browser): the navigation deadline of every page load, cookie consent, CAPTCHA cooperation and
 *       page readiness — the driver and the page reader apply them to search pages and to visited hits alike.</li>
 *   <li><b>Browser search</b> (tab "Search", provider "Browser (SERP)" only): the engines, the analysis of the
 *       result page (mechanical + visual), result extraction, the AI layout resolver, the reranker of the
 *       extracted hits, their retry policies, the analysis diagnostics and layout repair — all of them only
 *       run on a search engine's result page, which an API provider never opens.</li>
 * </ul>
 * The store keys stay the same; only the place in the dialog changes.
 */
final class BrowserSettingsSplit {

    /** The first browsing page: channel, headless, private targets and the navigation deadline. */
    static final String PAGE_BROWSER = "Browser";
    static final String PAGE_ENGINES = "Engines";

    static final List<String> BROWSING_PAGES = Collections.unmodifiableList(Arrays.asList(PAGE_BROWSER,
            LegacyBrowserSearchSettingsCatalog.SECTION_CONSENT, LegacyBrowserSearchSettingsCatalog.SECTION_CAPTCHA,
            LegacyBrowserSearchSettingsCatalog.SECTION_READINESS));

    static final List<String> SEARCH_PAGES = Collections.unmodifiableList(Arrays.asList(PAGE_ENGINES,
            LegacyBrowserSearchSettingsCatalog.SECTION_ANALYSIS, LegacyBrowserSearchSettingsCatalog.SECTION_VISUAL,
            LegacyBrowserSearchSettingsCatalog.SECTION_EXTRACTION, LegacyBrowserSearchSettingsCatalog.SECTION_AI_RESOLVER,
            LegacyBrowserSearchSettingsCatalog.SECTION_RERANKER, LegacyBrowserSearchSettingsCatalog.SECTION_RETRY,
            LegacyBrowserSearchSettingsCatalog.SECTION_DIAGNOSTICS,
            LegacyBrowserSearchSettingsCatalog.SECTION_LAYOUT_REPAIR));

    /** The driver's deadline for EVERY navigation (search pages and visited hits) — sits in the engines section. */
    static final String KEY_NAVIGATION_COMMIT_TIMEOUT = "navigation.navigationCommitTimeoutMillis";

    private BrowserSettingsSplit() {
    }

    /** The "Browser" tab page of {@code field}, or {@code null} when it belongs to the browser search. */
    static String browsingPage(LegacyBrowserSearchSettingsCatalog.Field field) {
        if (KEY_NAVIGATION_COMMIT_TIMEOUT.equals(field.key)) {
            return PAGE_BROWSER;
        }
        String section = field.section;
        if (LegacyBrowserSearchSettingsCatalog.SECTION_CONSENT.equals(section)
                || LegacyBrowserSearchSettingsCatalog.SECTION_CAPTCHA.equals(section)
                || LegacyBrowserSearchSettingsCatalog.SECTION_READINESS.equals(section)) {
            return section;
        }
        return null;
    }

    /** The "Browser (SERP)" page of {@code field}, or {@code null} when it belongs to browsing. */
    static String searchPage(LegacyBrowserSearchSettingsCatalog.Field field) {
        if (browsingPage(field) != null) {
            return null;
        }
        return LegacyBrowserSearchSettingsCatalog.SECTION_ENGINES.equals(field.section) ? PAGE_ENGINES : field.section;
    }
}
