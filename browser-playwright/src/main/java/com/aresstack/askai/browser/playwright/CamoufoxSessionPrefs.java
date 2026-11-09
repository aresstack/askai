package com.aresstack.askai.browser.playwright;

import java.util.Map;

/**
 * Firefox prefs every Camoufox launch of AskAI adds on top of the fingerprint prefs.
 *
 * <ul>
 *   <li>{@code browser.sessionhistory.max_total_viewers = 0}: Playwright's BiDi layer misses history
 *       traversals restored from the back-forward cache (page.url() stays stale, goBack never settles).</li>
 *   <li>{@code dom.confirm_repost.testing.always_accept = true}: without the bfcache, every history
 *       traversal or reload onto a page reached via POST (typically the result of a solved challenge
 *       form) opens Firefox's modal "resend data" prompt. That prompt is browser chrome, not a page
 *       dialog, so Playwright cannot dismiss it; it blocks the tab and every probe of it, and
 *       "Cancel" simply lets it reappear. Accepting it re-submits the same request the user already
 *       made, which is what the user ends up clicking anyway.</li>
 * </ul>
 */
final class CamoufoxSessionPrefs {

    static final String NO_BFCACHE = "browser.sessionhistory.max_total_viewers";
    static final String ALWAYS_ACCEPT_REPOST = "dom.confirm_repost.testing.always_accept";

    private CamoufoxSessionPrefs() {
    }

    /** Adds the session prefs without overriding a value the launch profile already sets. */
    static Map<String, Object> apply(Map<String, Object> prefs) {
        prefs.putIfAbsent(NO_BFCACHE, 0);
        prefs.putIfAbsent(ALWAYS_ACCEPT_REPOST, Boolean.TRUE);
        return prefs;
    }
}
