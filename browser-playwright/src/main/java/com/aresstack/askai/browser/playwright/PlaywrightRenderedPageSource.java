package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.LegacySearchEngineAttemptResult;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.analysis.RenderedPageSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The productive, browser-backed {@link RenderedPageSource} for {@code web_search_prepare}. It reuses
 * the SAME engine navigation as {@code web_search} (via
 * {@link PlaywrightBrowserSession#navigateAndCaptureSearchEngines}) — one loop, one truth — and simply
 * collects every captured engine page plus the navigation metadata (provider hosts, per-engine
 * attempts, challenge states). It performs no extraction and calls no model; the model-free
 * {@code SearchLayoutRepairTools} decides organic-vs-repair per page.
 */
final class PlaywrightRenderedPageSource implements RenderedPageSource {

    /** All browser access goes through the actor — never directly at the Playwright-backed session. */
    private final BrowserSessionActor actor;

    PlaywrightRenderedPageSource(BrowserSessionActor actor) {
        this.actor = actor;
    }

    public EngineCapture capture(final String query, final PageEvaluator evaluator) {
        final List<Captured> pages = new ArrayList<Captured>();
        try {
            PlaywrightBrowserSession.EngineNavigation nav = actor.onPlaywrightSession(
                    new BrowserSessionActor.PlaywrightSessionTask<PlaywrightBrowserSession.EngineNavigation>() {
                        public PlaywrightBrowserSession.EngineNavigation run(
                                PlaywrightBrowserSession session) throws BrowserException {
                            return session.navigateAndCaptureSearchEngines(query,
                                    new PlaywrightBrowserSession.CapturedPageConsumer() {
                                        public PlaywrightBrowserSession.CapturedPageVerdict accept(
                                                RenderedPageDocument document, String host,
                                                List<LegacySearchEngineAttemptResult> attempts) {
                                            pages.add(new Captured(document, host));
                                            // Judge the page HERE, while the navigation can still act on it.
                                            if (evaluator == null) {
                                                return PlaywrightBrowserSession.CapturedPageVerdict.UNUSABLE;
                                            }
                                            switch (evaluator.judge(document, host)) {
                                                case DELIVERED:
                                                    return PlaywrightBrowserSession.CapturedPageVerdict.DELIVERED;
                                                case EMPTY:
                                                    return PlaywrightBrowserSession.CapturedPageVerdict.EMPTY;
                                                default:
                                                    return PlaywrightBrowserSession.CapturedPageVerdict.UNUSABLE;
                                            }
                                        }
                                    });
                        }
                    });
            return new EngineCapture(pages, nav.providerHosts, nav.attempts, nav.challenges);
        } catch (BrowserException engineUnavailable) {
            if (engineUnavailable.getMessage() != null
                    && engineUnavailable.getMessage().contains("BROWSER_CLOSED")) {
                // The user's window close must reach the runtime as the typed marker — swallowed into
                // an empty capture it would surface as "Websuche technisch fehlgeschlagen".
                throw new IllegalStateException(engineUnavailable.getMessage());
            }
            // No engine reachable / no provider configured: an empty capture — prepare reports FAILED
            // and the runtime falls through its existing engine policy.
            return new EngineCapture(pages, Collections.<String>emptyList(),
                    Collections.<LegacySearchEngineAttemptResult>emptyList(),
                    Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>
                            emptyList());
        }
    }
}
