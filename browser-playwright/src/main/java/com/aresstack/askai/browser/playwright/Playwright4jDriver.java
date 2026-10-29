package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import com.aresstack.askai.browser.playwright.camoufox.CamoufoxLaunch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * {@link PlaywrightDriver} over the OFFICIAL Playwright Java API, executed by playwright4j: the Driver
 * replacement spawns a second Java process ({@code GraalDriverMain}) hosting the Playwright Core JS driver on
 * GraalJS, which in turn launches the browser: by default the managed Camoufox (Firefox over WebDriver BiDi,
 * downloaded once and verified by {@link CamoufoxProvisioning}, started with its fingerprint profile in a
 * persistent context, as Argos does), or the locally installed Chrome/Edge via the channel mechanism.
 * Playwright itself never downloads a browser ({@code PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1} is always set).
 *
 * <p>Lifecycle owned here (one research session per runtime instance): Playwright → Browser → one
 * BrowserContext → one Page + history. Popups are closed immediately, downloads are refused, and when a
 * request filter is given, browser-side requests to disallowed targets are aborted via route interception.
 * {@link #close()} tears down page → context → browser → Playwright (ending the driver child) and is
 * idempotent.</p>
 *
 * <p>THREADING CONTRACT: every instance is created and used on exactly ONE thread (the
 * {@link BrowserSessionActor} owner). Playwright Java is not thread-safe, and its events (route
 * interception, exposeBinding, popups) are only dispatched while that thread runs a Playwright call —
 * which is why the actor keeps {@link #pumpEvents} running whenever no command is queued. Every
 * Playwright-touching method asserts the owner via {@link ThreadOwnershipGuard}.</p>
 */
final class Playwright4jDriver implements PlaywrightDriver {

    /** Created in the constructor — the launching thread (the actor owner) is the only legal caller. */
    private final ThreadOwnershipGuard owner = new ThreadOwnershipGuard();
    private final Playwright playwright;
    /** Null for a persistent context whose browser Playwright does not expose (Camoufox). */
    private final Browser browser;
    private final BrowserContext context;
    /** Camoufox only: the profile this browser runs on (released/deleted on close), else null. */
    private CamoufoxProvisioning.ProfileLease profileLease;
    /** Set by BrowserContext.onClose: a persistent context ends with its browser window. */
    private volatile boolean contextClosed;
    /** SERP guard behaviour comes exclusively from the settings — no local constants. */
    private final com.aresstack.askai.browser.search.ConsentHandlingSettings consent;
    private final com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha;
    /** The structured-page capture is a separate collaborator; this driver only delegates to it. */
    private RenderedPageDocumentCapture renderedCapture;
    private Page page;
    /** The parked manual-challenge page (kept open for the user), or null. At most one at a time. */
    private Page challengePage;
    /** Research HUD: the command binding is registered ONCE on the context (survives navigation). */
    private boolean hudBindingRegistered;
    /** Control plane: filled by the binding on the owner thread, drained on any thread (never via the actor). */
    private final HudCommandInbox hudCommands = new HudCommandInbox();
    /** How many popups the close-immediately policy handled — observable proof the event dispatched. */
    private final java.util.concurrent.atomic.AtomicInteger popupsClosed =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean closed;
    /** Set by Browser.onDisconnected (dispatched during the idle pump): the USER closed the window. */
    private volatile boolean browserDisconnected;

    /**
     * The typed marker for "the user closed the browser window". It leads every resulting tool error, so
     * the research runtime can recognize the USER's stop and end the run as their decision — instead of
     * the historic behaviour, where 'Target closed' matched no classifier and the run kept polling a dead
     * browser until some budget gave out.
     */
    static final String BROWSER_CLOSED_MESSAGE =
            "BROWSER_CLOSED — the browser window was closed by the user.";

    /** The per-navigation deadline ({@code navigationCommitTimeoutMillis}) — explicit on every navigate. */
    private final int navigationTimeoutMillis;

    private Playwright4jDriver(Playwright playwright, Browser browser, BrowserContext context, Page page,
                               com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                               com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                               int navigationTimeoutMillis) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.page = page;
        this.consent = consent;
        this.captcha = captcha;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    /**
     * Launch the full chain. @param requestAllowed null = no interception; otherwise every browser-side
     * request URL is checked and aborted when not allowed (cheap best-effort SSRF net below the
     * authoritative post-navigation URL-policy check in the session).
     */
    static Playwright4jDriver launch(String channel, boolean headless, int timeoutMillis,
                                     final Predicate<String> requestAllowed,
                                     com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                     com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha)
            throws BrowserException {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        CamoufoxProvisioning.ProfileLease profile = null;
        boolean camoufox = CamoufoxProvisioning.CHANNEL.equals(channel);
        // Which step of the chain was running when it failed — a visible window with a failed start
        // (browser up, CDP/context not) reads very differently from a driver that never came up.
        String stage = camoufox ? "CAMOUFOX_INSTALL" : "PLAYWRIGHT_CREATE";
        long startedNanos = System.nanoTime();
        try {
            CamoufoxLaunch camoufoxLaunch = null;
            Path camoufoxExecutable = null;
            if (camoufox) {
                // Downloads once (progress lines keep the host waiting), afterwards only verifies.
                camoufoxExecutable = CamoufoxProvisioning.ensureInstalled();
                // The system language, as Chrome used it: search engines answer in it (see builtIn).
                camoufoxLaunch = CamoufoxLaunch.builtIn(camoufoxExecutable, CamoufoxProvisioning.browserRoot(),
                        java.util.Locale.getDefault());
                stage = "PLAYWRIGHT_CREATE";
            }
            Map<String, String> env = new LinkedHashMap<String, String>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            env.put("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            stage = "BROWSER_LAUNCH";
            if (camoufox) {
                profile = CamoufoxProvisioning.leaseProfile();
                context = playwright.firefox().launchPersistentContext(profile.directory(),
                        camoufoxOptions(camoufoxExecutable, camoufoxLaunch, headless));
                browser = context.browser();
            } else {
                browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                        .setChannel(channel)
                        .setHeadless(headless));
                stage = "CONTEXT_CREATE";
                context = browser.newContext(new Browser.NewContextOptions().setAcceptDownloads(false));
            }
            context.setDefaultNavigationTimeout(timeoutMillis);
            context.setDefaultTimeout(timeoutMillis);
            if (requestAllowed != null) {
                context.route("**/*", route -> {
                    if (requestAllowed.test(route.request().url())) {
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
            }
            stage = "PAGE_CREATE";
            // A persistent context opens with its first tab already; use it instead of a second one.
            Page page = camoufox && !context.pages().isEmpty() ? context.pages().get(0) : context.newPage();
            final Playwright4jDriver driver = new Playwright4jDriver(playwright, browser, context, page,
                    consent, captcha, timeoutMillis);
            driver.profileLease = profile;
            page.onPopup(driver.popupCloser());
            // Dispatched during the idle pump: the user closing the window becomes a TYPED condition
            // instead of an anonymous 'Target closed' that no classifier recognized.
            if (browser != null) {
                browser.onDisconnected(new Consumer<Browser>() {
                    public void accept(Browser gone) {
                        driver.browserDisconnected = true;
                    }
                });
            }
            context.onClose(new Consumer<BrowserContext>() {
                public void accept(BrowserContext gone) {
                    driver.contextClosed = true;
                    driver.browserDisconnected = true;
                }
            });
            return driver;
        } catch (RuntimeException | java.io.IOException ex) {
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
            closeQuietly(context, browser, playwright);
            if (profile != null) {
                profile.close();
            }
            // The one-line message travels to the host and the chat; the FULL exception (Playwright's
            // multi-line message carries the browser log and call log) goes to a persistent file —
            // start failures are rare, the next one must be diagnosable without a repro.
            String report = BrowserStartFailureLog.append(channel, headless, stage, elapsedMillis, ex);
            throw new BrowserException("Browser start failed (channel=" + channel + ", stage=" + stage
                    + ", after " + elapsedMillis + " ms): " + firstLine(ex)
                    + (report == null ? "" : " (full report: " + report + ")"));
        }
    }

    @Override
    public PlaywrightPageState open(String url) throws BrowserException {
        owner.check();
        requireOpen();
        try {
            // DOMCONTENTLOADED, not the default 'load': the navigation's job is to deliver the
            // document — whether the page is actually usable is decided by the session's OWN readiness
            // machinery (probe/consent/challenge/read) afterwards. Waiting for 'load' let one hanging
            // ad/tracking subresource stall the whole visit for the full navigation timeout. Never
            // NETWORKIDLE. With this, navigationCommitTimeoutMillis finally bounds roughly what its
            // name promises.
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMillis));
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Navigation failed", ex);
        }
    }

    @Override
    public PlaywrightPageState current() throws BrowserException {
        owner.check();
        requireOpen();
        try {
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Reading the current page failed", ex);
        }
    }

    @Override
    public PlaywrightPageState back() throws BrowserException {
        owner.check();
        requireOpen();
        try {
            // Same navigation semantics as open(): the document suffices, readiness judges the rest.
            if (page.goBack(new Page.GoBackOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMillis)) == null) {
                throw new BrowserException("No previous page in history.");
            }
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Going back failed", ex);
        }
    }

    /** Hand in the capture collaborator (built from the analysis settings by the factory). */
    void setRenderedCapture(RenderedPageDocumentCapture capture) {
        this.renderedCapture = capture;
    }

    @Override
    public com.aresstack.askai.browser.render.RenderedPageDocument captureRenderedPage(
            com.aresstack.askai.browser.domain.DomainKeyResolver domainKeys,
            long snapshotGeneration) throws BrowserException {
        owner.check();
        requireOpen();
        if (renderedCapture == null) {
            return null;
        }
        try {
            final Page target = page;
            return renderedCapture.capture(new RenderedPageDocumentCapture.PageScriptRunner() {
                public Object evaluate(String script) {
                    return target.evaluate(script);
                }

                public String url() {
                    return target.url();
                }

                public String title() {
                    return target.title();
                }
            }, domainKeys, snapshotGeneration);
        } catch (PlaywrightException ex) {
            throw asBrowserException("Structured page capture failed", ex);
        }
    }

    // ------------------------------------------------------------------ SERP guards

    // #44: the ONE human-input policy of this driver — every pointer path/pause comes from it.
    private final com.aresstack.askai.browser.input.HumanInteractionPolicy humanPolicy =
            new com.aresstack.askai.browser.input.HumanInteractionPolicy(
                    com.aresstack.askai.browser.input.HumanInteractionPolicy.Config
                            .fromEnvironment(System.getenv()),
                    new java.util.Random());
    private int lastPointerX = 8;
    private int lastPointerY = 8;

    /** Human-paced pointer click via Playwright's mouse (many timed moves, exact target). */
    private void humanPointerClick(final int targetX, final int targetY) {
        com.aresstack.askai.browser.input.HumanPointer pointer =
                new com.aresstack.askai.browser.input.HumanPointer(humanPolicy,
                        new com.aresstack.askai.browser.input.HumanPointer.PointerDevice() {
                            public void moveTo(int x, int y) {
                                page.mouse().move(x, y);
                            }

                            public void click(int x, int y) {
                                page.mouse().click(x, y);
                            }

                            public void wheel(int deltaY) {
                                page.mouse().wheel(0, deltaY);
                            }

                            public void sleep(long millis) {
                                page.waitForTimeout(millis);
                            }
                        });
        pointer.moveAndClick(lastPointerX, lastPointerY, targetX, targetY);
        lastPointerX = targetX;
        lastPointerY = targetY;
    }

    @Override
    public String tryDismissConsent() {
        owner.check(); // BEFORE the guard-try: an ownership violation must never be swallowed as "none"
        if (closed || !consent.enabled) {
            return "none";
        }
        try {
            // Up to maximumDismissAttempts stacked banners; each successful click settles before the
            // next probe, and the caller re-reads the page when anything was clicked at all.
            // #44: POINTER-FIRST — the control is located (same priority chains) and clicked
            // with the human-paced mouse; the injected JS click stays the fallback when the
            // pointer click did not land (animated overlays, moved controls).
            String lastClicked = "none";
            for (int attempt = 0; attempt < consent.maximumDismissAttempts; attempt++) {
                String located = String.valueOf(
                        page.evaluate(SearchPageGuards.consentLocateScript(consent)));
                if (located.startsWith("locate:")) {
                    int comma = located.lastIndexOf(',');
                    int colon = located.lastIndexOf(':');
                    try {
                        int x = Integer.parseInt(located.substring(colon + 1, comma).trim());
                        int y = Integer.parseInt(located.substring(comma + 1).trim());
                        humanPointerClick(x, y);
                        page.waitForTimeout(consent.postClickSettleMillis);
                        String again = String.valueOf(
                                page.evaluate(SearchPageGuards.consentLocateScript(consent)));
                        if (!again.equals(located)) {
                            lastClicked = "clicked:"
                                    + located.substring("locate:".length(), colon)
                                    + ":pointer";
                            continue; // the banner moved on — probe for a stacked one
                        }
                        // fall through: the pointer click did not land — JS fallback below
                    } catch (NumberFormatException malformedCoordinates) {
                        // fall through to the JS fallback
                    }
                }
                String result = String.valueOf(
                        page.evaluate(SearchPageGuards.consentDismissScript(consent)));
                if (!result.startsWith("clicked")) {
                    break;
                }
                lastClicked = result;
                page.waitForTimeout(consent.postClickSettleMillis);
            }
            return lastClicked;
        } catch (RuntimeException ex) {
            return "none"; // a broken CMP script must never take the search down
        }
    }

    @Override
    public String renderHud(String stateLine) {
        owner.check();
        if (closed) {
            return "closed";
        }
        try {
            if (!hudBindingRegistered) {
                // Buffer overlay button commands; registered on the CONTEXT so it survives navigations.
                context.exposeBinding("__askaiHudCommand", (source, args) -> {
                    if (args != null && args.length > 0 && args[0] != null) {
                        hudCommands.add(String.valueOf(args[0]));
                    }
                    return null;
                });
                hudBindingRegistered = true;
            }
            page.evaluate(ResearchHudOverlay.installScript()); // idempotent
            page.evaluate(ResearchHudOverlay.renderScript(
                    com.aresstack.askai.browser.hud.ResearchHudState.parse(stateLine)));
            return "rendered";
        } catch (RuntimeException ex) {
            return "error"; // a HUD failure must never take the visit down
        }
    }

    @Override
    public String pollHudCommands() {
        // Deliberately NO owner check: this drains a thread-safe Java inbox and never touches Playwright.
        return hudCommands.drain();
    }

    @Override
    public HudCommandInbox hudInbox() {
        return hudCommands;
    }

    /** The one popup policy: close immediately, count it, and never let a dead popup break the session. */
    private Consumer<Page> popupCloser() {
        return new Consumer<Page>() {
            public void accept(Page popup) {
                popupsClosed.incrementAndGet();
                try {
                    popup.close();
                } catch (RuntimeException ignored) {
                    // A popup that is already gone must not take the session down.
                }
            }
        };
    }

    /** How many popups were closed so far. Thread-safe counter read — touches no Playwright state. */
    int closedPopupCount() {
        return popupsClosed.get();
    }

    @Override
    public boolean pumpEvents(java.util.function.BooleanSupplier wake, long timeoutMillis) {
        owner.check();
        if (closed) {
            return false; // torn down — the actor falls back to a plain queue wait
        }
        Page target = page;
        if (target == null) {
            return false;
        }
        try {
            // Runs the Playwright message loop until the wake condition holds or the slice ends: route
            // interception gets its resume/abort, exposeBinding and popup events are delivered — even
            // when no MCP command is in flight. This is what keeps the visible browser interactive.
            target.waitForCondition(wake::getAsBoolean,
                    new Page.WaitForConditionOptions().setTimeout(timeoutMillis));
        } catch (com.microsoft.playwright.TimeoutError normalIdle) {
            // The slice ended with nothing queued — the actor simply pumps again.
        } catch (RuntimeException broken) {
            // A page closing/navigating mid-pump must not kill the loop; yield briefly so a permanently
            // broken page cannot hot-spin the owner thread. But FIRST: when the pump broke because the
            // USER closed the window, record it — with the page gone no message loop runs anymore, so
            // Browser.onDisconnected would never dispatch and the close would stay invisible until some
            // API call happened to fail.
            if (!connected() || page.isClosed()) {
                browserDisconnected = true;
            }
            try {
                Thread.sleep(Math.min(50L, Math.max(1L, timeoutMillis)));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    @Override
    public boolean browserClosedByUser() {
        owner.check();
        return !closed && (browserDisconnected || !connected());
    }

    @Override
    public boolean challengePresent() {
        owner.check();
        if (closed || !captcha.enabled) {
            return false;
        }
        try {
            // Only a VISIBLE/blocking challenge counts here (this drives challenge parking) — a hidden
            // artifact ('hidden:…') must never park a readable page.
            return String.valueOf(page.evaluate(SearchPageGuards.challengeDetectScript(captcha)))
                    .startsWith("visible");
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public String challengeMarker() {
        owner.check();
        if (closed || !captcha.enabled) {
            return "none";
        }
        try {
            return String.valueOf(page.evaluate(SearchPageGuards.challengeDetectScript(captcha)));
        } catch (RuntimeException ex) {
            return "none";
        }
    }

    @Override
    public String consentCandidate() {
        owner.check();
        if (closed || !consent.enabled) {
            return "none";
        }
        try {
            return String.valueOf(page.evaluate(SearchPageGuards.consentReportScript(consent)));
        } catch (RuntimeException ex) {
            return "none"; // a broken CMP script must never take a probe down
        }
    }

    @Override
    public boolean parkChallenge() {
        owner.check();
        if (closed || challengePage != null) {
            return false;
        }
        try {
            challengePage = page;
            page = context.newPage();
            page.onPopup(popupCloser());
            // Bring the challenge to the user's attention exactly ONCE — polls never steal focus again.
            if (captcha.focusTabOnFirstDetection) {
                try {
                    challengePage.bringToFront();
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        } catch (RuntimeException ex) {
            page = challengePage; // fresh page failed: keep working on the original page
            challengePage = null;
            return false;
        }
    }

    @Override
    public boolean parkedChallengeStillPresent() {
        owner.check();
        if (closed || challengePage == null) {
            return false;
        }
        try {
            // Resolved once the challenge is no longer VISIBLY blocking (a lingering hidden artifact does not count).
            return String.valueOf(challengePage.evaluate(SearchPageGuards.challengeDetectScript(captcha)))
                    .startsWith("visible");
        } catch (RuntimeException ex) {
            return false; // an unreadable/closed challenge tab counts as resolved, never blocks forever
        }
    }

    @Override
    public void closeParkedChallenge() {
        owner.check();
        if (challengePage != null) {
            try {
                challengePage.close();
            } catch (RuntimeException ignored) {
            }
            challengePage = null;
        }
    }

    @Override
    public void close() {
        owner.check();
        if (closed) {
            return;
        }
        closed = true;
        closeParkedChallenge();
        try {
            page.close();
        } catch (RuntimeException ignored) {
        }
        closeQuietly(context, browser, playwright);
        if (profileLease != null) {
            profileLease.close();
        }
    }

    @Override
    public void terminate() {
        // Deliberately NO owner check: the owner thread is the stuck one. Cutting the Playwright connection
        // fails its pending call ("connection closed") and ends the driver child; `closed` stops the pump
        // and every later call. The cut runs on its own daemon thread because Playwright's close waits
        // (bounded) for the driver process to exit — the caller must never block here. Context and browser
        // are NOT closed over the stuck connection (that could block forever); the browser window of a cut
        // connection may therefore survive as an orphan. close() stays the orderly path.
        if (closed) {
            return;
        }
        closed = true;
        browserDisconnected = true;
        final Playwright toCut = playwright;
        final CamoufoxProvisioning.ProfileLease lease = profileLease;
        Thread cutter = new Thread(new Runnable() {
            public void run() {
                closeQuietly(null, null, toCut);
                if (lease != null) {
                    try {
                        lease.close();
                    } catch (RuntimeException ignored) {
                        // best effort: a profile still held by an orphaned browser stays on disk
                    }
                }
            }
        }, "playwright-terminate");
        cutter.setDaemon(true);
        cutter.start();
    }

    /** Whether the browser is still there; a persistent context without a Browser object ends with its context. */
    private boolean connected() {
        return browser != null ? browser.isConnected() : !contextClosed;
    }

    /**
     * Camoufox as Argos starts it: Firefox over WebDriver BiDi ({@code moz-firefox}) with the verified
     * executable, the fingerprint environment and prefs of the built-in profile, and NO Playwright
     * emulation (viewport, color scheme, motion ...) — the fingerprint owns the window and the screen.
     */
    private static BrowserType.LaunchPersistentContextOptions camoufoxOptions(Path executable,
                                                                              CamoufoxLaunch launch,
                                                                              boolean headless) {
        Map<String, String> environment = new java.util.HashMap<String, String>(System.getenv());
        environment.putAll(launch.environment());
        Map<String, Object> prefs = new LinkedHashMap<String, Object>(launch.prefs());
        // Playwright's BiDi layer misses history traversals restored from the back-forward cache
        // (page.url() stays stale, goBack never settles) — the same pref Argos sets.
        prefs.putIfAbsent("browser.sessionhistory.max_total_viewers", 0);
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setChannel("moz-firefox")
                .setExecutablePath(executable)
                .setHeadless(headless)
                .setEnv(environment)
                .setAcceptDownloads(false)
                .setViewportSize(null)
                .setColorScheme(null)
                .setReducedMotion(null)
                .setForcedColors(null)
                .setContrast(null)
                .setFirefoxUserPrefs(prefs);
        if (!launch.args().isEmpty()) {
            options.setArgs(launch.args());
        }
        return options;
    }

    /** RENDERED state via the DOM: url after redirects, title, body innerText, anchors with absolute hrefs. */
    private PlaywrightPageState state() {
        String url = page.url();
        String title = page.title();
        String text = page.innerText("body");
        List<PlaywrightPageState.Anchor> anchors = new ArrayList<PlaywrightPageState.Anchor>();
        Object raw = page.evaluate(
                "() => Array.from(document.querySelectorAll('a[href]'))"
                        + ".map(a => [String(a.innerText || '').trim(), String(a.href || '')])");
        if (raw instanceof List) {
            for (Object entry : (List<?>) raw) {
                if (entry instanceof List && ((List<?>) entry).size() == 2) {
                    List<?> pair = (List<?>) entry;
                    anchors.add(new PlaywrightPageState.Anchor(
                            String.valueOf(pair.get(0)), String.valueOf(pair.get(1))));
                }
            }
        }
        return new PlaywrightPageState(url, title, text, anchors);
    }

    private void requireOpen() throws BrowserException {
        if (closed) {
            throw new BrowserException("Browser session is closed.");
        }
        if (browserDisconnected) {
            throw new BrowserException(BROWSER_CLOSED_MESSAGE);
        }
    }

    /** Map a Playwright failure to a readable tool error; a dead/closed browser keeps its typed marker. */
    private BrowserException asBrowserException(String action, PlaywrightException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        if (browserDisconnected || message.contains("has been closed") || message.contains("Target closed")) {
            return new BrowserException(BROWSER_CLOSED_MESSAGE);
        }
        return new BrowserException(action + ": " + firstLine(ex));
    }

    private static void closeQuietly(BrowserContext context, Browser browser, Playwright playwright) {
        try {
            if (context != null) {
                context.close();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (browser != null) {
                browser.close();
            }
        } catch (RuntimeException ignored) {
        }
        try {
            if (playwright != null) {
                playwright.close();
            }
        } catch (RuntimeException ignored) {
        }
    }

    /** Tool errors carry the reason, never a multi-line driver stack trace. */
    private static String firstLine(Throwable ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).trim() : message.trim();
    }
}
