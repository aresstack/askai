package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.BoundingBox;

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
    /** Chrome/Edge HUD overlay: the command binding is registered ONCE on the context (survives navigation). */
    private boolean hudBindingRegistered;
    /**
     * Control plane: filled by the research add-on's bridge thread (Camoufox) or the overlay binding (Chrome/Edge),
     * drained on any thread (never via the actor).
     */
    private final HudCommandInbox hudCommands = new HudCommandInbox();
    /**
     * Camoufox only: the bridge to the AskAI Research add-on, which shows the HUD in the browser UI instead of the
     * page (docs/research-browser-addon.md); null when the add-on is off or could not be prepared.
     */
    private AddonBridge addonBridge;
    /**
     * Camoufox with the add-on: where page reads run (the chat browser's {@link #addonBridge}, or the worker
     * browser's bridge for a worker tab). Null = no add-on, reads fall back to {@code page.evaluate}.
     */
    private AddonBridge readBridge;
    /**
     * The add-on's stable key per page (main page, parked challenge page): it binds each key to exactly one tab, so
     * a read never lands in another tab that shows the same URL. Unique per JVM, because worker tabs of several
     * drivers share one worker bridge.
     */
    private final Map<Page, String> pageKeys = new java.util.IdentityHashMap<Page, String>();
    private static final java.util.concurrent.atomic.AtomicLong PAGE_KEYS = new java.util.concurrent.atomic.AtomicLong();
    /** When the browser was started: the add-on gets {@link #ADDON_GRACE_MILLIS} before its absence is logged. */
    private long launchedMillis;
    private boolean addonConnectedLogged;
    private boolean addonMissingLogged;

    /** System property: {@code false} starts Camoufox without the AskAI Research add-on. */
    static final String ADDON_PROPERTY = "askai.browser.researchAddon";
    /**
     * System property: {@code false} keeps the HUD overlay out of Chrome/Edge pages too (no HUD in the browser there).
     * Camoufox never gets the overlay, whatever this says.
     */
    static final String IN_PAGE_FALLBACK_PROPERTY = "askai.browser.hud.inPageFallback";
    /** How long a fresh browser's add-on may take to connect before its absence is logged. */
    static final long ADDON_GRACE_MILLIS = 15_000L;
    /**
     * How long one page read may take through the add-on. Covers a fresh browser whose add-on is still connecting
     * (it fetches the queued read once it polls) and a full structured capture of a large page.
     */
    static final long PAGE_READ_TIMEOUT_MILLIS = 30_000L;
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
    /**
     * False for a worker browsing context ({@link #inBrowsingContext}): the driver then owns only its page;
     * the user context, the browser and Playwright belong to the worker browser that created it.
     */
    private final boolean ownsGeneration;
    /**
     * Camoufox: nothing of AskAI is put into the page — no HUD overlay, no binding; the HUD lives only in the research
     * add-on. Chrome/Edge keep the overlay until their extension exists (issue #23, stage 4).
     */
    private final boolean camoufox;
    /** Every main-world script passes this gate; in script-free mode it refuses them all (see PageScriptGate). */
    private final PageScriptGate pageScripts;
    private Playwright4jDriver(Playwright playwright, Browser browser, BrowserContext context, Page page,
                               com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                               com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                               int navigationTimeoutMillis, boolean camoufox) {
        this(playwright, browser, context, page, consent, captcha, navigationTimeoutMillis, true, camoufox);
    }

    private Playwright4jDriver(Playwright playwright, Browser browser, BrowserContext context, Page page,
                               com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                               com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                               int navigationTimeoutMillis, boolean ownsGeneration, boolean camoufox) {
        this.ownsGeneration = ownsGeneration;
        this.camoufox = camoufox;
        this.pageScripts = PageScriptGate.forBrowser(camoufox);
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
     * authoritative post-navigation URL-policy check in the session). @param profileKey the chat whose persistent
     * Camoufox profile this browser uses (null = a temporary profile; see {@link CamoufoxProvisioning#leaseProfile}).
     */
    static Playwright4jDriver launch(String channel, boolean headless, int timeoutMillis, String profileKey,
                                     final Predicate<String> requestAllowed,
                                     com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                     com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha)
            throws BrowserException {
        Playwright playwright = null;
        Browser browser = null;
        BrowserContext context = null;
        CamoufoxProvisioning.ProfileLease profile = null;
        AddonBridge bridge = null;
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
                profile = CamoufoxProvisioning.leaseProfile(profileKey);
                if (!"false".equalsIgnoreCase(System.getProperty(ADDON_PROPERTY))) {
                    stage = "ADDON_PREPARE";
                    try {
                        bridge = AddonBridge.start();
                        Path addon = ResearchAddon.install(profile.directory(), bridge.clientConfig());
                        camoufoxLaunch = camoufoxLaunch.withAddon(addon);
                    } catch (java.io.IOException | RuntimeException addonFailed) {
                        // The browser still starts, without a HUD in it: nothing is put into the page instead.
                        System.err.println("[browser-mcp] research add-on not prepared: " + firstLine(addonFailed));
                        if (bridge != null) {
                            bridge.close();
                            bridge = null;
                        }
                    }
                    stage = "BROWSER_LAUNCH";
                }
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
                    consent, captcha, timeoutMillis, camoufox);
            driver.profileLease = profile;
            if (bridge != null) {
                bridge.deliverTo(driver.hudCommands);
                driver.addonBridge = bridge;
                driver.readBridge = bridge;
            }
            driver.launchedMillis = System.currentTimeMillis();
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
            if (bridge != null) {
                bridge.close();
            }
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

    /**
     * A driver for ONE browsing context (tab) of a worker user context, created by the worker browser on its
     * owner thread (which becomes this driver's owner). It owns only {@code page}: {@link #close()} closes the
     * page (and a parked challenge tab), never the user context, the browser or Playwright. The Research HUD
     * belongs to the chat's own browser and is not rendered in worker tabs. @param camoufox whether the worker
     * browser is Camoufox (script-free mode applies to it).
     */
    static Playwright4jDriver inBrowsingContext(Browser browser, BrowserContext userContext, Page page,
                                                int timeoutMillis,
                                                com.aresstack.askai.browser.search.ConsentHandlingSettings consent,
                                                com.aresstack.askai.browser.search.CaptchaHandlingSettings captcha,
                                                boolean camoufox) {
        final Playwright4jDriver driver = new Playwright4jDriver(null, browser, userContext, page, consent,
                captcha, timeoutMillis, false, camoufox);
        page.onPopup(driver.popupCloser());
        if (browser != null) {
            browser.onDisconnected(new Consumer<Browser>() {
                public void accept(Browser gone) {
                    driver.browserDisconnected = true;
                }
            });
        }
        page.onClose(driver.activeTabCloseWatcher());
        return driver;
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
            com.aresstack.askai.browser.render.RenderedPageDocument document =
                    renderedCapture.capture(new RenderedPageDocumentCapture.PageScriptRunner() {
                public Object evaluate(String script) {
                    return readPage(target, script);
                }

                public String url() {
                    return target.url();
                }

                public String title() {
                    return target.title();
                }
            }, domainKeys, snapshotGeneration);
            System.err.println(SerpCaptureDiagnostics.summary(document));
            java.nio.file.Path dumpDirectory = SerpCaptureDiagnostics.snapshotDirectory();
            if (dumpDirectory != null) {
                try {
                    String html = SerpCaptureDiagnostics.sanitizedHtml(target.content());
                    System.err.println("[serp-capture] snapshot written to " + SerpCaptureDiagnostics
                            .dump(dumpDirectory, document, html));
                } catch (java.io.IOException | PlaywrightException dumpFailed) {
                    // Diagnostics only: a failed dump never fails the search.
                    System.err.println("[serp-capture] snapshot dump failed: " + dumpFailed);
                }
            }
            return document;
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
        humanPointer().moveAndClick(lastPointerX, lastPointerY, targetX, targetY);
        lastPointerX = targetX;
        lastPointerY = targetY;
    }

    /** The human-input policy over this page's REAL mouse (WebDriver BiDi input actions, trusted events only). */
    private com.aresstack.askai.browser.input.HumanPointer humanPointer() {
        return new com.aresstack.askai.browser.input.HumanPointer(humanPolicy,
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
    }

    /** At most this many matches of one consent target are measured (the old in-page scan stopped at 120 buttons). */
    private static final int CONSENT_MATCHES_PER_TARGET = 20;
    /** A consent probe only measures what is already there: it never waits for a control to appear. */
    private static final double CONSENT_MEASURE_TIMEOUT_MILLIS = 1_000d;

    /**
     * A located consent control: the target it matched, the ELEMENT itself (a handle, so stacked banners with an equal
     * control at the same spot cannot be mistaken for the clicked one) and its box when it was measured.
     */
    private static final class ConsentHit {
        final SearchPageGuards.ConsentTarget target;
        final ElementHandle control;
        final BoundingBox box;
        /** All matches of the target and how many there were when measured: a click on a covering banner counts. */
        final Locator matches;
        final int matchCount;

        ConsentHit(SearchPageGuards.ConsentTarget target, ElementHandle control, BoundingBox box, Locator matches,
                   int matchCount) {
            this.target = target;
            this.control = control;
            this.box = box;
            this.matches = matches;
            this.matchCount = matchCount;
        }
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
            // Issue #22: the control is located with Playwright locators (its own selector engine, no page script),
            // measured with boundingBox and clicked with the human-paced REAL mouse. There is no script fallback:
            // a JS click is exactly what bot detection looks for, so a click that does not land ends the attempt.
            List<SearchPageGuards.ConsentTarget> targets = SearchPageGuards.consentTargets(consent);
            String lastClicked = "none";
            for (int attempt = 0; attempt < consent.maximumDismissAttempts; attempt++) {
                ConsentHit hit = locateConsentControl(targets);
                if (hit == null) {
                    break;
                }
                try {
                    if (!pointerClickInView(hit.control, hit.box)) {
                        System.err.println("[browser-mcp] consent control " + hit.target.action
                                + " could not be clicked with the pointer; no script fallback");
                        break;
                    }
                    page.waitForTimeout(consent.postClickSettleMillis);
                    if (!controlReacted(hit)) {
                        System.err.println("[browser-mcp] consent control " + hit.target.action
                                + " did not react to the pointer click; no script fallback");
                        break;
                    }
                } finally {
                    disposeQuietly(hit.control);
                }
                lastClicked = "clicked:" + hit.target.action + ":pointer";
            }
            return lastClicked;
        } catch (RuntimeException ex) {
            return "none"; // a broken CMP must never take the search down
        }
    }

    /** The first visible control in minimal-consent priority order, or null. Reads nothing with a page script. */
    private ConsentHit locateConsentControl(List<SearchPageGuards.ConsentTarget> targets) {
        for (SearchPageGuards.ConsentTarget target : targets) {
            try {
                Locator matches = page.locator(target.selector);
                if (target.text != null) {
                    matches = matches.filter(new Locator.FilterOptions().setHasText(target.text));
                }
                int total = matches.count();
                int count = Math.min(total, CONSENT_MATCHES_PER_TARGET);
                for (int i = 0; i < count; i++) {
                    ElementHandle control = matches.nth(i).elementHandle(
                            new Locator.ElementHandleOptions().setTimeout(CONSENT_MEASURE_TIMEOUT_MILLIS));
                    BoundingBox box = control.boundingBox();
                    if (box != null && box.width > 0 && box.height > 0) {
                        return new ConsentHit(target, control, box, matches, total);
                    }
                    disposeQuietly(control);
                }
            } catch (PlaywrightException unusable) {
                // An invalid configured selector or a control that went away while measured: try the next target.
            }
        }
        return null;
    }

    /**
     * When the window height is unknown (Camoufox runs without viewport emulation, so {@code page.viewportSize()} is
     * null, and the fingerprint only knows the outer window), a control whose center lies below this line is first
     * wheeled up to {@link #UNKNOWN_WINDOW_ANCHOR}: every real window is at least this tall, so the click target is
     * then inside it. Asking the page for {@code innerHeight} would be a page script.
     */
    private static final int UNKNOWN_WINDOW_SAFE_Y = 360;
    private static final int UNKNOWN_WINDOW_ANCHOR = 200;
    private static final int MAX_SCROLL_ROUNDS = 3;

    /**
     * Brings the control into the window with real wheel input when it lies (or may lie) outside, then clicks its
     * center with the human-paced mouse. False when it cannot be brought into view or the click is refused.
     */
    private boolean pointerClickInView(ElementHandle control, BoundingBox box) {
        try {
            BoundingBox target = box;
            com.microsoft.playwright.options.ViewportSize viewport = page.viewportSize();
            int height = viewport != null && viewport.height > 0 ? viewport.height : 0;
            int safeBottom = height > 0 ? height : UNKNOWN_WINDOW_SAFE_Y;
            int anchor = height > 0 ? height / 3 : UNKNOWN_WINDOW_ANCHOR;
            for (int round = 0; round < MAX_SCROLL_ROUNDS; round++) {
                double centerY = target.y + target.height / 2;
                if (centerY >= 0 && centerY < safeBottom) {
                    break;
                }
                // The wheel turns where the pointer rests, which must be inside the window: its last landed spot.
                page.mouse().move(lastPointerX, lastPointerY);
                humanPointer().scrollBy((int) Math.round(centerY - anchor));
                BoundingBox moved = control.boundingBox();
                if (moved == null || moved.width <= 0 || moved.height <= 0) {
                    return false;
                }
                if (Math.abs(moved.y - target.y) < 1) {
                    // Nothing scrolls (a fixed banner): it is where the page shows it, inside a known window or not.
                    if (height > 0 && (centerY < 0 || centerY >= height)) {
                        return false;
                    }
                    break;
                }
                target = moved;
            }
            humanPointerClick((int) Math.round(target.x + target.width / 2),
                    (int) Math.round(target.y + target.height / 2));
            return true;
        } catch (RuntimeException refused) {
            // e.g. "move target out of bounds": the pointer goes back to its last spot inside the window
            try {
                page.mouse().move(lastPointerX, lastPointerY);
            } catch (RuntimeException ignored) {
                // the page is gone
            }
            return false;
        }
    }

    /**
     * Whether the click took effect: the clicked element is gone, hidden or moved, or fewer controls of its kind are
     * left (a stacked banner on top took the click and closed). Read without a page script.
     */
    private boolean controlReacted(ConsentHit hit) {
        try {
            if (hit.matches.count() < hit.matchCount) {
                return true;
            }
        } catch (PlaywrightException unreadable) {
            // fall through to the element itself
        }
        BoundingBox before = hit.box;
        try {
            BoundingBox after = hit.control.boundingBox();
            return after == null || after.width <= 0 || after.height <= 0
                    || Math.abs(after.x - before.x) > 2 || Math.abs(after.y - before.y) > 2
                    || Math.abs(after.width - before.width) > 2 || Math.abs(after.height - before.height) > 2;
        } catch (PlaywrightException gone) {
            return true; // detached with its banner
        }
    }

    private static void disposeQuietly(ElementHandle handle) {
        try {
            handle.dispose();
        } catch (RuntimeException ignored) {
            // gone with its page
        }
    }

    // ------------------------------------------------------------------ search forms (issue #22, Argos' web_search)

    /**
     * The engines' tabs of the running search, in the order they were opened. Each engine is asked in a tab of its
     * own (Argos' ParallelWebSearch): no tab is brought to the front, real input goes to each tab's browsing context.
     */
    private final List<Page> searchTabs = new ArrayList<Page>();
    /** The page the driver worked on before the search tabs took over; restored by {@link #closeSearchTabs}. */
    private Page pageBeforeSearch;
    /** Per page: how many top-level navigations started (protocol events only), to wait for the one Enter starts. */
    private final Map<Page, int[]> navigationCounts = new java.util.IdentityHashMap<Page, int[]>();
    /** Per tab: where its pointer rests (BiDi keeps one input state per browsing context). */
    private final Map<Page, int[]> tabPointers = new java.util.IdentityHashMap<Page, int[]>();
    /** A search field is only measured, never waited for. */
    private static final double SEARCH_BOX_MEASURE_TIMEOUT_MILLIS = 1_500d;
    /** How long the navigation that Enter (or a "next" click) starts may take to begin. */
    private static final long SUBMIT_NAVIGATION_START_MILLIS = 5_000L;
    /** DOMContentLoaded is enough to read; late resources get a bounded chance (Argos' settleLoad). */
    private static final double SETTLE_LOAD_MILLIS = 5_000d;

    @Override
    public List<String> openSearchTabs(List<String> homeUrls) throws BrowserException {
        owner.check();
        requireOpen();
        closeSearchTabs();
        pageBeforeSearch = page;
        List<String> outcomes = new ArrayList<String>();
        // Fan-out: every start page begins loading before any is waited for, so no engine waits for another.
        for (String url : homeUrls) {
            try {
                Page tab = context.newPage(); // never brought to the front: which tab to watch stays the user's choice
                tab.onPopup(popupCloser());
                watchNavigations(tab);
                searchTabs.add(tab);
                tab.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.COMMIT)
                        .setTimeout(navigationTimeoutMillis));
                outcomes.add(null);
            } catch (PlaywrightException failed) {
                outcomes.add(firstLine(failed));
            }
        }
        for (int i = 0; i < outcomes.size() && i < searchTabs.size(); i++) {
            if (outcomes.get(i) != null) {
                continue;
            }
            try {
                searchTabs.get(i).waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(navigationTimeoutMillis));
                settleLoad(searchTabs.get(i));
            } catch (PlaywrightException failed) {
                outcomes.set(i, firstLine(failed));
            }
        }
        while (outcomes.size() < homeUrls.size()) {
            outcomes.add("tab could not be opened");
        }
        return outcomes;
    }

    @Override
    public PlaywrightPageState useSearchTab(int index) throws BrowserException {
        owner.check();
        requireOpen();
        if (index < 0 || index >= searchTabs.size() || searchTabs.get(index).isClosed()) {
            throw new BrowserException("Search tab " + index + " is not open.");
        }
        page = searchTabs.get(index); // reads, guards and clicks now work on this tab; it is NOT brought to the front
        try {
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Reading the search tab failed", ex);
        }
    }

    @Override
    public PlaywrightPageState openInSearchTab(int index, String url) throws BrowserException {
        owner.check();
        requireOpen();
        if (index < 0 || index >= searchTabs.size() || searchTabs.get(index).isClosed()) {
            throw new BrowserException("Search tab " + index + " is not open.");
        }
        page = searchTabs.get(index);
        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMillis));
            settleLoad(page);
            return state();
        } catch (PlaywrightException ex) {
            throw asBrowserException("Navigation failed", ex);
        }
    }

    /**
     * Types the query into each listed tab's search field AT THE SAME TIME and presses Enter in each (Argos #79): the
     * steps of all tabs (pointer path into the field, click, select-all and Backspace, every key, Enter) are planned
     * with human timing and played as ONE timeline merged by time, so the keys of one tab fall into the pauses of the
     * others. Every step is real input over WebDriver BiDi to that tab's browsing context; no tab is switched to or
     * brought to the front, nothing is filled or submitted by script. Then waits for each tab's result page.
     *
     * @param searchBoxSelectors per tab (same index as the tabs): the field's selectors in order, or null to leave
     *                           that tab alone
     * @return per tab: {@code "submitted"}, {@code "no-search-box"}, {@code "refused: <why>"} or {@code "skipped"}
     */
    @Override
    public List<String> submitSearchInTabs(List<List<String>> searchBoxSelectors, String query) throws BrowserException {
        owner.check();
        requireOpen();
        String typed = query == null ? "" : query.trim().replaceAll("\\s+", " ");
        List<String> outcomes = new ArrayList<String>();
        List<TabTyping> plans = new ArrayList<TabTyping>();
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < searchTabs.size(); i++) {
            List<String> selectors = i < searchBoxSelectors.size() ? searchBoxSelectors.get(i) : null;
            Page tab = searchTabs.get(i);
            if (selectors == null || tab.isClosed() || typed.isEmpty()) {
                outcomes.add("skipped");
                continue;
            }
            String refused = null;
            TabTyping plan = null;
            for (String selector : selectors) {
                try {
                    Locator first = tab.locator(selector).first();
                    if (first.count() == 0) {
                        continue;
                    }
                    ElementHandle field = first.elementHandle(
                            new Locator.ElementHandleOptions().setTimeout(SEARCH_BOX_MEASURE_TIMEOUT_MILLIS));
                    BoundingBox box = inView(tab, field);
                    if (box == null) {
                        disposeQuietly(field);
                        refused = "the search field " + selector + " is not visible in the window";
                        continue;
                    }
                    plan = new TabTyping(i, tab, selector, field);
                    planTyping(plan, box, typed, 40L + random.nextInt(360), random);
                    break;
                } catch (PlaywrightException unusable) {
                    refused = firstLine(unusable); // a hidden or covered match; an alternative may be the field
                }
            }
            if (plan != null) {
                plans.add(plan);
                outcomes.add("submitted");
            } else {
                outcomes.add(refused == null ? "no-search-box" : "refused: " + refused);
            }
        }
        if (plans.isEmpty()) {
            return outcomes;
        }
        playMerged(plans);
        for (TabTyping plan : plans) {
            disposeQuietly(plan.field);
            if (plan.failure != null) {
                outcomes.set(plan.index, "refused: " + plan.failure);
            }
        }
        awaitResultPages(plans);
        return outcomes;
    }

    @Override
    public boolean clickNextPage(List<String> selectors) throws BrowserException {
        owner.check();
        requireOpen();
        watchNavigations(page);
        for (String selector : selectors) {
            ElementHandle control = null;
            try {
                Locator first = page.locator(selector).first();
                if (first.count() == 0) {
                    continue;
                }
                control = first.elementHandle(
                        new Locator.ElementHandleOptions().setTimeout(SEARCH_BOX_MEASURE_TIMEOUT_MILLIS));
                BoundingBox box = control.boundingBox();
                if (box == null || box.width <= 0 || box.height <= 0) {
                    continue;
                }
                int before = navigationCounts.get(page)[0];
                // The real, human-paced pointer (wheel scrolling to the pager first): never Locator.click.
                if (!pointerClickInView(control, box)) {
                    continue;
                }
                awaitNavigation(page, before);
                return true;
            } catch (PlaywrightException unusable) {
                // an alternative selector may be the engine's pager
            } finally {
                if (control != null) {
                    disposeQuietly(control);
                }
            }
        }
        return false;
    }

    @Override
    public void closeSearchTabs() {
        owner.check();
        for (Page tab : searchTabs) {
            pageKeys.remove(tab);
            navigationCounts.remove(tab);
            tabPointers.remove(tab);
            if (tab == challengePage) {
                continue; // a parked challenge stays open for the user
            }
            try {
                if (!tab.isClosed()) {
                    tab.close();
                }
            } catch (RuntimeException ignored) {
                // already gone
            }
        }
        searchTabs.clear();
        if (pageBeforeSearch != null) {
            page = pageBeforeSearch;
            pageBeforeSearch = null;
        }
    }

    /** One tab's planned input: the steps, each at its time after the timeline started. */
    private static final class TabTyping {
        final int index;
        final Page tab;
        final String selector;
        final ElementHandle field;
        final List<long[]> times = new ArrayList<long[]>();
        final List<Runnable> steps = new ArrayList<Runnable>();
        String failure;
        int navigationsBeforeEnter = -1;

        TabTyping(int index, Page tab, String selector, ElementHandle field) {
            this.index = index;
            this.tab = tab;
            this.selector = selector;
            this.field = field;
        }

        void at(long millis, Runnable step) {
            times.add(new long[]{millis, steps.size()});
            steps.add(step);
        }
    }

    /**
     * The steps of one tab, with the policy's human timing: a pointer path into the field (not its exact centre), a
     * click, a check that the click put the focus into the field (otherwise select-all would mark the page), select
     * all and Backspace, the query key by key (Shift held for capitals), a look at the input, Enter.
     */
    private void planTyping(final TabTyping plan, BoundingBox box, String typed, long start, java.util.Random random) {
        final Page tab = plan.tab;
        int[] from = pointerOf(tab);
        final int targetX = (int) Math.round(box.x + box.width * (0.25 + random.nextDouble() * 0.4));
        final int targetY = (int) Math.round(box.y + box.height * (0.35 + random.nextDouble() * 0.3));
        long t = start;
        for (final com.aresstack.askai.browser.input.HumanInteractionPolicy.TimedPoint point
                : humanPolicy.mousePath(from[0], from[1], targetX, targetY)) {
            plan.at(t, new Runnable() {
                public void run() {
                    tab.mouse().move(point.x, point.y);
                    pointerOf(tab)[0] = point.x;
                    pointerOf(tab)[1] = point.y;
                }
            });
            t += point.pauseMillis;
        }
        t += humanPolicy.preClickPauseMillis();
        plan.at(t, new Runnable() {
            public void run() {
                tab.mouse().down();
            }
        });
        t += 40 + random.nextInt(80);
        plan.at(t, new Runnable() {
            public void run() {
                tab.mouse().up();
            }
        });
        t += humanPolicy.postClickPauseMillis();
        plan.at(t, new Runnable() {
            public void run() {
                // Read through Playwright's selector engine (utility world), no page script.
                if (tab.locator(plan.selector + ":focus").count() == 0) {
                    throw new IllegalStateException("the click did not put the focus into the search field"
                            + " (an overlay or banner may cover it); nothing was typed");
                }
            }
        });
        t += 80 + random.nextInt(120);
        plan.at(t, key(tab, "ControlOrMeta+a"));
        t += 90 + random.nextInt(110);
        plan.at(t, key(tab, "Backspace"));
        List<Long> delays = humanPolicy.typingDelaysMillis(typed);
        for (int i = 0; i < typed.length(); ) {
            int codePoint = typed.codePointAt(i);
            String character = new String(Character.toChars(codePoint));
            t += delays.get(i);
            plan.at(t, typeCharacter(tab, character));
            i += Character.charCount(codePoint);
        }
        t += humanPolicy.preSubmitPauseMillis();
        plan.at(t, new Runnable() {
            public void run() {
                plan.navigationsBeforeEnter = navigationCounts.get(tab)[0];
                tab.keyboard().press("Enter");
            }
        });
    }

    /** Plays the steps of all tabs in time order; a tab whose step fails stops, the others go on. */
    private void playMerged(List<TabTyping> plans) {
        List<Object[]> timeline = new ArrayList<Object[]>();
        for (TabTyping plan : plans) {
            for (long[] time : plan.times) {
                timeline.add(new Object[]{time[0], plan, plan.steps.get((int) time[1])});
            }
        }
        java.util.Collections.sort(timeline, new java.util.Comparator<Object[]>() {
            public int compare(Object[] a, Object[] b) {
                return Long.compare((Long) a[0], (Long) b[0]);
            }
        });
        long started = System.currentTimeMillis();
        Page clock = plans.get(0).tab;
        for (Object[] entry : timeline) {
            TabTyping plan = (TabTyping) entry[1];
            if (plan.failure != null) {
                continue;
            }
            long wait = (Long) entry[0] - (System.currentTimeMillis() - started);
            if (wait > 0) {
                clock.waitForTimeout(wait); // keeps the Playwright message loop running meanwhile
            }
            try {
                ((Runnable) entry[2]).run();
            } catch (RuntimeException refused) {
                plan.failure = firstLine(refused);
                try {
                    plan.tab.keyboard().up("Shift"); // never leave a tab with Shift held
                } catch (RuntimeException ignored) {
                    // the tab is gone
                }
            }
        }
    }

    /** One named key or chord as real key input. */
    private static Runnable key(final Page tab, final String chord) {
        return new Runnable() {
            public void run() {
                tab.keyboard().press(chord);
            }
        };
    }

    /** Shifted symbols of the US layout: typed with Shift held, as on a real keyboard (Playwright's type omits it). */
    private static final String SHIFTED_SYMBOLS = "~!@#$%^&*()_+{}|:\"<>?";

    /**
     * One character as a real key. Over WebDriver BiDi playwright4j sends any single grapheme ("ä") as a real key
     * (playwright4j#59); a browser that refuses the key name (Chromium) gets it as inserted text, which CDP delivers
     * as trusted native input.
     */
    private static Runnable typeCharacter(final Page tab, final String character) {
        return new Runnable() {
            public void run() {
                if (" ".equals(character)) {
                    tab.keyboard().press("Space");
                    return;
                }
                int codePoint = character.codePointAt(0);
                boolean shift = Character.isUpperCase(codePoint) || SHIFTED_SYMBOLS.indexOf(codePoint) >= 0;
                if (shift) {
                    tab.keyboard().down("Shift");
                }
                try {
                    tab.keyboard().press(character);
                } catch (PlaywrightException refused) {
                    if (!String.valueOf(refused.getMessage()).contains("Unknown key")) {
                        throw refused;
                    }
                    tab.keyboard().insertText(character);
                } finally {
                    if (shift) {
                        tab.keyboard().up("Shift");
                    }
                }
            }
        };
    }

    /**
     * The field's box once it is inside the window: a field below the safe line is first wheeled up with real input
     * (the pointer resting inside the window). Null when it has no box or cannot be brought into view.
     */
    private BoundingBox inView(Page tab, ElementHandle field) {
        BoundingBox box = field.boundingBox();
        com.microsoft.playwright.options.ViewportSize viewport = tab.viewportSize();
        int height = viewport != null && viewport.height > 0 ? viewport.height : 0;
        int safeBottom = height > 0 ? height : UNKNOWN_WINDOW_SAFE_Y;
        int anchor = height > 0 ? height / 3 : UNKNOWN_WINDOW_ANCHOR;
        for (int round = 0; round < MAX_SCROLL_ROUNDS && box != null; round++) {
            double centerY = box.y + box.height / 2;
            if (box.width <= 0 || box.height <= 0) {
                return null;
            }
            if (centerY >= 0 && centerY < safeBottom) {
                return box;
            }
            int[] pointer = pointerOf(tab);
            tab.mouse().move(pointer[0], pointer[1]);
            for (com.aresstack.askai.browser.input.HumanInteractionPolicy.ScrollStep step
                    : humanPolicy.scrollSteps((int) Math.round(centerY - anchor))) {
                tab.mouse().wheel(0, step.deltaY);
                tab.waitForTimeout(step.pauseMillis);
            }
            BoundingBox moved = field.boundingBox();
            if (moved == null || Math.abs(moved.y - box.y) < 1) {
                return null; // nothing scrolls: the field stays outside the window
            }
            box = moved;
        }
        return box != null && box.y + box.height / 2 >= 0 && box.y + box.height / 2 < safeBottom ? box : null;
    }

    private int[] pointerOf(Page tab) {
        int[] pointer = tabPointers.get(tab);
        if (pointer == null) {
            pointer = new int[]{8 + new java.util.Random().nextInt(40), 8 + new java.util.Random().nextInt(40)};
            tabPointers.put(tab, pointer);
        }
        return pointer;
    }

    /** Counts a page's top-level navigation requests (protocol events only). Idempotent. */
    private void watchNavigations(final Page target) {
        if (navigationCounts.containsKey(target)) {
            return;
        }
        final int[] count = new int[1];
        navigationCounts.put(target, count);
        target.onRequest(new Consumer<com.microsoft.playwright.Request>() {
            public void accept(com.microsoft.playwright.Request request) {
                try {
                    if (request.isNavigationRequest() && request.frame() == target.mainFrame()) {
                        count[0]++;
                    }
                } catch (RuntimeException ignored) {
                    // a request of a frame that is already gone
                }
            }
        });
    }

    /** After Enter in every typed tab: each tab's result page, waited for side by side. */
    private void awaitResultPages(List<TabTyping> plans) {
        long deadline = System.currentTimeMillis() + SUBMIT_NAVIGATION_START_MILLIS;
        for (TabTyping plan : plans) {
            if (plan.failure != null || plan.navigationsBeforeEnter < 0) {
                continue;
            }
            final int[] count = navigationCounts.get(plan.tab);
            final int before = plan.navigationsBeforeEnter;
            long left = deadline - System.currentTimeMillis();
            try {
                if (count[0] <= before) {
                    plan.tab.waitForCondition(() -> count[0] > before,
                            new Page.WaitForConditionOptions().setTimeout(Math.max(100L, left)));
                }
            } catch (PlaywrightException noNavigation) {
                plan.failure = "Enter started no navigation (the engine did not take the query)";
            }
        }
        for (TabTyping plan : plans) {
            if (plan.failure != null) {
                continue;
            }
            try {
                plan.tab.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(navigationTimeoutMillis));
                settleLoad(plan.tab);
            } catch (PlaywrightException slow) {
                plan.failure = "the result page did not load: " + firstLine(slow);
            }
        }
    }

    /** Waits for the navigation a click started, then for its document; protocol events only. */
    private void awaitNavigation(Page target, int before) {
        final int[] count = navigationCounts.get(target);
        try {
            if (count[0] <= before) {
                target.waitForCondition(() -> count[0] > before,
                        new Page.WaitForConditionOptions().setTimeout(SUBMIT_NAVIGATION_START_MILLIS));
            }
            target.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                    new Page.WaitForLoadStateOptions().setTimeout(navigationTimeoutMillis));
            settleLoad(target);
        } catch (PlaywrightException slow) {
            // reading proceeds with the document that is there
        }
    }

    /** Gives late subresources a bounded chance, never a long sleep. */
    private static void settleLoad(Page target) {
        try {
            target.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD,
                    new Page.WaitForLoadStateOptions().setTimeout(SETTLE_LOAD_MILLIS));
        } catch (PlaywrightException slowSubresources) {
            // reading proceeds with the DOM that is there
        }
    }

    @Override
    public String renderHud(String stateLine) {
        owner.check();
        if (closed) {
            return "closed";
        }
        if (!ownsGeneration) {
            return "unsupported"; // the HUD belongs to the chat's own browser, never to a worker tab
        }
        com.aresstack.askai.browser.hud.ResearchHudState state =
                com.aresstack.askai.browser.hud.ResearchHudState.parse(stateLine);
        if (camoufox) {
            return renderHudInAddon(state); // Camoufox never gets the overlay or the binding (issue #22)
        }
        if (!inPageFallbackAllowed()) {
            return "unsupported";
        }
        // Chrome/Edge: no extension yet (issue #23, stage 4), so the HUD is still the in-page overlay there.
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
            pageScripts.evaluate(page, ResearchHudOverlay.installScript(), "hud-overlay"); // idempotent
            pageScripts.evaluate(page, ResearchHudOverlay.renderScript(state), "hud-overlay");
            return "rendered";
        } catch (RuntimeException ex) {
            return "error"; // a HUD failure must never take the visit down
        }
    }

    /**
     * The HUD through the research add-on, the only HUD Camoufox has: the state is always published (a late add-on
     * shows the current one at once). Without a connected add-on there is no HUD in the browser and only the log
     * says so; nothing is put into the page instead.
     */
    private String renderHudInAddon(com.aresstack.askai.browser.hud.ResearchHudState state) {
        if (addonBridge == null) {
            if (!addonMissingLogged) {
                addonMissingLogged = true;
                System.err.println("[browser-mcp] research add-on is off or not prepared: no HUD in the browser "
                        + "(nothing is put into the page)");
            }
            return "addon-missing";
        }
        addonBridge.publish(state, pageHost());
        if (addonBridge.addonConnected()) {
            if (!addonConnectedLogged) {
                addonConnectedLogged = true;
                System.err.println("[browser-mcp] research add-on connected (version "
                        + addonBridge.addonVersion() + "): HUD shown in the browser UI, not in the page");
            }
            return "rendered-addon";
        }
        if (System.currentTimeMillis() - launchedMillis < ADDON_GRACE_MILLIS) {
            return "pending-addon";
        }
        if (!addonMissingLogged) {
            addonMissingLogged = true;
            System.err.println("[browser-mcp] WARNING research add-on did not connect within "
                    + (ADDON_GRACE_MILLIS / 1000L) + " s: no HUD in the browser until it does (nothing is put into "
                    + "the page)");
        }
        return "addon-missing";
    }

    /** The host of the current page for the add-on panel; never the full URL (no paths or queries leave here). */
    private String pageHost() {
        try {
            String host = java.net.URI.create(page.url()).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static boolean inPageFallbackAllowed() {
        return !"false".equalsIgnoreCase(System.getProperty(IN_PAGE_FALLBACK_PROPERTY));
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

    /**
     * Worker tabs only: the user closing the ACTIVE page ends the tab (its later calls fail with the typed
     * marker). A parked challenge page closing — resolved, or closed by {@link #closeParkedChallenge()} — is
     * not the tab going away.
     */
    private Consumer<Page> activeTabCloseWatcher() {
        return new Consumer<Page>() {
            public void accept(Page gone) {
                if (!closed && gone == page) {
                    browserDisconnected = true;
                }
            }
        };
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

    /**
     * A worker tab: read its pages through the worker browser's add-on bridge (owned and closed by the worker
     * browser), or null to keep {@code page.evaluate}.
     */
    void readThroughAddon(AddonBridge workerBridge) {
        this.readBridge = workerBridge;
    }

    /** The research add-on's bridge, or null (Chrome/Edge, add-on off or not prepared). */
    AddonBridge addonBridge() {
        return addonBridge;
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
            return String.valueOf(readPage(page, SearchPageGuards.challengeDetectScript(captcha)))
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
            return String.valueOf(readPage(page, SearchPageGuards.challengeDetectScript(captcha)));
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
            return String.valueOf(readPage(page, SearchPageGuards.consentReportScript(consent)));
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
        if (searchTabs.contains(page) && pageBeforeSearch != null) {
            // An engine's tab shows the challenge: it simply stays open for the user (not closed with the other
            // search tabs); the search goes on in the other engines' tabs.
            challengePage = page;
            page = pageBeforeSearch;
            if (captcha.focusTabOnFirstDetection) {
                try {
                    challengePage.bringToFront();
                } catch (RuntimeException ignored) {
                }
            }
            return true;
        }
        try {
            challengePage = page;
            page = context.newPage();
            page.onPopup(popupCloser());
            if (!ownsGeneration) {
                page.onClose(activeTabCloseWatcher());
            }
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
            return String.valueOf(readPage(challengePage, SearchPageGuards.challengeDetectScript(captcha)))
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
        if (!ownsGeneration) {
            return; // a worker tab: the user context and the browser stay with the worker browser
        }
        closeQuietly(context, browser, playwright);
        if (addonBridge != null) {
            addonBridge.close();
        }
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
        if (!ownsGeneration) {
            return; // the worker browser cuts its own connection; a tab has nothing of its own to cut
        }
        final Playwright toCut = playwright;
        final CamoufoxProvisioning.ProfileLease lease = profileLease;
        final AddonBridge bridgeToClose = addonBridge;
        Thread cutter = new Thread(new Runnable() {
            public void run() {
                closeQuietly(null, null, toCut);
                if (bridgeToClose != null) {
                    bridgeToClose.close();
                }
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
        Object raw = readPage(page,
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

    /**
     * Every read-only page script goes through here. With the research add-on it runs in the tab's isolated
     * content-script world (docs/research-browser-addon.md): the page's own scripts never see it, and no script of
     * AskAI is evaluated in the page's world. Only a driver without the add-on (Chrome/Edge, add-on off or not
     * prepared) still uses {@code page.evaluate}, through {@link PageScriptGate}. Failures surface as {@link PlaywrightException}, so every caller
     * keeps its existing error handling. While waiting, the Playwright message loop keeps running (route
     * interception, popups), exactly as during the idle pump.
     */
    private Object readPage(Page target, String script) {
        AddonBridge reads = readBridge;
        if (reads == null) {
            // Without the add-on: the main-world fallback passes the script gate (refused in script-free mode).
            return pageScripts.evaluate(target, script, "page-read");
        }
        String key = pageKeys.get(target);
        if (key == null) {
            key = "page-" + PAGE_KEYS.incrementAndGet();
            pageKeys.put(target, key);
        }
        AddonBridge.PageRead read = reads.submitRead(key, target.url(), script);
        try {
            if (!read.isDone()) {
                target.waitForCondition(read::isDone,
                        new Page.WaitForConditionOptions().setTimeout(PAGE_READ_TIMEOUT_MILLIS));
            }
            return read.value();
        } catch (com.microsoft.playwright.TimeoutError timedOut) {
            throw new PlaywrightException("The research add-on did not answer the page read within "
                    + (PAGE_READ_TIMEOUT_MILLIS / 1000L) + " s");
        } catch (AddonBridge.PageReadFailed failed) {
            throw new PlaywrightException(failed.getMessage());
        } finally {
            if (!read.isDone()) {
                read.cancel();
            }
        }
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
