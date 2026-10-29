package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

/**
 * The narrow seam between the {@link PlaywrightBrowserSession} and the Playwright runtime. Everything below
 * this interface (official Playwright Java API, playwright4j's GraalJS driver child process, Chromium
 * lifecycle) may be complex; above it only {@link PlaywrightPageState} values flow.
 *
 * <p>Deliberate deviations from a fuller driver surface: link following is resolved to the anchor's absolute
 * href by the session (which owns the link-id mapping) and goes through {@link #open}, so pre/post URL-policy
 * checks apply identically; search is a session concern behind {@link WebSearchProvider}, because
 * playwright4j brings no search engine integration.</p>
 */
interface PlaywrightDriver extends AutoCloseable {

    /** Navigate the single page to the URL and return the resulting state (final URL after redirects). */
    PlaywrightPageState open(String url) throws BrowserException;

    /** Re-read the current page without navigating. */
    PlaywrightPageState current() throws BrowserException;

    /** Browser history back. Fails readably when there is no previous page. */
    PlaywrightPageState back() throws BrowserException;

    /**
     * Capture the CURRENT page as a neutral {@link com.aresstack.askai.browser.render.RenderedPageDocument}
     * (container hierarchy, text/link statistics, geometry, colors) for the mechanical SERP analysis.
     * @param domainKeys the session's domain-key resolver (link domain classification)
     * @param snapshotGeneration monotonic per session; stale-reference guard for container/link ids
     * @return the document, or null when this driver cannot capture (tests provide their own)
     */
    default com.aresstack.askai.browser.render.RenderedPageDocument captureRenderedPage(
            com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
            long snapshotGeneration) throws BrowserException {
        return null;
    }

    // ------------------------------------------------------------------ search-page guards (SERP only)
    // These stay driver-level so SERP details (consent, challenge DOM) never leak into the research loop.

    /**
     * Try to dismiss a consent banner on the CURRENT page by clicking one UNAMBIGUOUSLY positive button
     * ("Accept all", "Alle akzeptieren", …) — never a first-button-in-container guess.
     * @return {@code "clicked:…"} when a button was clicked, {@code "none"} otherwise.
     */
    default String tryDismissConsent() {
        return "none";
    }

    /** @return true when the CURRENT page shows a VISIBLE, blocking manual challenge (CAPTCHA / "one last step"). */
    default boolean challengePresent() {
        return false;
    }

    /**
     * @return the challenge marker that matched on the CURRENT page: {@code "visible:<selector>"} /
     * {@code "visible:text:<text>"} for a blocking challenge, {@code "hidden:<selector>"} for a present-but-
     * invisible artifact (not blocking), or {@code "none"}. A richer form of {@link #challengePresent}.
     */
    default String challengeMarker() {
        return challengePresent() ? "visible:?" : "none";
    }

    /**
     * @return a hint at a consent/cookie dismiss control on the CURRENT page WITHOUT clicking it
     * ({@code "candidate:<selector>"} / {@code "candidate-text:<label>"}), or {@code "none"}.
     */
    default String consentCandidate() {
        return "none";
    }

    /**
     * Park the current (challenge) page: keep it open and bring it to the user's attention ONCE, then
     * continue on a fresh page. At most one page is parked at a time.
     * @return false when parking is not supported or a page is already parked.
     */
    default boolean parkChallenge() {
        return false;
    }

    /** @return true while a parked challenge page still shows the challenge (false = resolved or none). */
    default boolean parkedChallengeStillPresent() {
        return false;
    }

    /** Close the parked challenge page, if any. Idempotent. */
    default void closeParkedChallenge() {
    }

    /**
     * Install (idempotent) + render the Research HUD overlay on the CURRENT page from a serialized
     * {@code ResearchHudState}. Buttons feed {@link #pollHudCommands()}. No-op default returns 'unsupported'.
     */
    default String renderHud(String stateLine) {
        return "unsupported";
    }

    /** Drain the buffered HUD commands from the overlay (newline-separated), or "" when none. */
    default String pollHudCommands() {
        return "";
    }

    /**
     * The control-plane inbox the HUD binding feeds, or null when this driver has none. Exposed so the
     * actor can drain it WITHOUT queueing behind a blocked data command — a Skip must arrive even while
     * probe/read is stuck.
     */
    default HudCommandInbox hudInbox() {
        return null;
    }

    /**
     * Run the driver's event loop for at most {@code timeoutMillis} or until {@code wake} holds, so that
     * browser-initiated events (route interception, exposeBinding, popups) are dispatched while the browser runtime
     * is otherwise idle. Playwright Java only delivers these while a Playwright call is active — without a
     * pump, the request interception freezes every user-driven navigation the moment no tool call runs.
     * @return true when this driver pumped (or handled the wait itself), false when it has no event loop —
     * the caller then waits on its own queue instead.
     */
    default boolean pumpEvents(java.util.function.BooleanSupplier wake, long timeoutMillis) {
        return false;
    }

    /**
     * True when the USER closed the browser window (their stop signal) — as opposed to a session that
     * was never opened or was torn down by the browser runtime itself. Drivers without a real browser have
     * nothing the user could close.
     */
    default boolean browserClosedByUser() {
        return false;
    }

    /** Idempotent: page → context → browser → Playwright (and with it the GraalJS driver child). */
    void close();

    /**
     * EMERGENCY teardown from a FOREIGN thread while the owner thread is stuck inside a driver call that
     * does not return. Must never block on the owner and must make the stuck call fail. The default is
     * the regular {@link #close()} (drivers without an owner-bound connection).
     */
    default void terminate() {
        close();
    }
}
