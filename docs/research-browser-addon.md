# Research browser add-on

Status: stage 1 implemented (HUD in the add-on). Page reads (guard detection, SERP capture, anchors) run in the
add-on (see "Page reads in the add-on"). Consent clicks use real input only. Stage 4: the Chrome/Edge chat browser
gets the same add-on as a Manifest V3 extension (see "Stage 4: Chrome/Edge extension").

## Why

AskAI drives the research browser through Playwright. Until now, everything AskAI showed or checked in the
browser was injected into the visited page with `page.evaluate`, in the page's own JavaScript world. A page can see
all of it:

| Injected script | Purpose | What a page can observe |
| --- | --- | --- |
| `ResearchHudOverlay` | Research HUD: phase, status, pause/resume, skip, next, wait countdown, "solved", relevance star, delay slider | A host element on `document.documentElement` (open shadow root), the globals `__askaiResearchHudRoot`, `__askaiHudPaused`, `__askaiHudRelevant`, and the Playwright binding `window.__askaiHudCommand` |
| `SearchPageGuards` | CAPTCHA/challenge detection, consent banner detection and dismissal | Read-only DOM queries in the page world (prototype hooks can see them); the dismissal clicks |
| `RenderedPageDocumentCapture` | Structured capture of the SERP for the mechanical analysis | Read-only DOM walk in the page world |
| `Playwright4jDriver.state()` | Anchor list of the current page | Read-only DOM query in the page world |

The HUD is the worst of these: any page can detect it with one `document.getElementById` or `typeof` check. The
decision is to move our browser-side code into a browser add-on (WebExtension) that is loaded when the browser
starts. Firefox (Camoufox) comes first; a Chrome extension follows later.

## Stage 1: the HUD lives in the add-on

### What the user sees

The add-on **AskAI Research** shows the HUD in the browser UI, never in the page:

- a **sidebar** (opened automatically at every browser start) with phase, status, the wait box with countdown and
  "Solved", Pause/Resume, Next (only during the inter-page delay), Skip, the relevance star and the delay slider;
- a **toolbar button** with a badge: the countdown in red while AskAI waits for the user, `II` while paused. Its
  popup shows the same panel as the sidebar.

The controls and their commands are the same as the old overlay (`PAUSE`, `RESUME`, `SKIP`, `NEXT`,
`SET_DELAY:<s>`, `SET_RELEVANCE:on|off`), so the research runtime (`WebSearchApplicationService`, `web_hud_render`,
`web_hud_poll`) is unchanged.

Spec: GitHub issue #23. The panel also shows the host of the page AskAI is on (only the host, never path or query).

### What a page sees

Nothing of the add-on: no registered content script, no web-accessible resource, no element, no global, no binding.
The add-on only has a background page and its own panel page; page reads run in the isolated content-script world
(below). The live test checks this in a real Camoufox: the page reports `hud=false;binding=undefined;root=undefined`.

This removes OUR traces from the page; it does not make an automated browser undetectable. Every other automation
signal stays as it is.

### Pieces

| Piece | Where |
| --- | --- |
| Add-on sources (Manifest V2, Firefox) | `browser-playwright/src/main/resources/askai-addon/firefox/` |
| Writes the add-on next to the browser profile at every start, with this start's `bridge.json` | `ResearchAddon` |
| Adds the add-on directory to `CAMOU_CONFIG.addons` (Camoufox loads it like the bundled uBlock Origin) | `CamoufoxLaunch.withAddon` |
| Local bridge between add-on and Java | `AddonBridge` |
| Starts bridge and add-on, routes the HUD (Camoufox: add-on only, never the page) | `Playwright4jDriver.launch` / `renderHud` |

Manifest V2 is deliberate: Firefox keeps supporting it, its persistent background page keeps the long poll alive,
and its host permission (`http://127.0.0.1/*`) is granted at install, while Manifest V3 host permissions may need a
user grant.

### Communication between add-on and Java

The add-on's background page talks to a small HTTP server in the AskAI process:

- bound to `127.0.0.1` on a random port, one bridge per browser start;
- a random 256-bit bearer token per start; port and token are written to `<profile>/askai-research-addon/bridge.json`,
  which only the add-on reads (`browser.runtime.getURL`); pages cannot read add-on files;
- requests with a non-extension `Origin` are refused before the token is checked; a page cannot send the
  `Authorization` header without a CORS request that carries its own origin;
- `GET /v1/state?after=<version>`: long poll (held up to 20 s), returns the newest HUD state as JSON (Gson);
- `POST /v1/command`: one HUD command; anything outside the known command set is refused (400);
- `POST /v1/hello`: protocol and add-on version, logged once;
- `GET /v1/read`: long poll (held up to 20 s), returns the next page read `{id, url, script}`;
- `POST /v1/read-result`: `{id, ok:true, value}` or `{id, ok:false, error}` for that read (up to 64 MB).

Commands land in the same `HudCommandInbox` the overlay binding fed, so a Skip still reaches the runtime while a
data command blocks the browser actor.

Contract: the existing browser-neutral types in `browser-api` (`ResearchHudState`, `ResearchHudCommand`) are the
contract; the add-on only renders the state and sends commands, it has no research logic. One bridge and one token
per browser start, and every chat has its own browser and profile, so a chat's add-on can only reach its own chat.

Alternatives considered: WebDriver BiDi script channels need raw BiDi access, which Playwright Java does not give us
(and `script.addPreloadScript` would again run in the page); Native Messaging needs a separately registered host
executable per OS and still an IPC channel back into the JVM. The loopback bridge needs neither and works the same
for a Chrome extension later.

Measured in the live test: Playwright's request interception (`PrivateTargetRequestFilter`, which aborts page
requests to loopback) does not touch the add-on's requests, so the bridge works with the filter on, while pages
still cannot reach loopback.

### Without the add-on

- **Camoufox:** there is no in-page fallback any more (issue #22: pages detect injected scripts and bindings, Bing's
  bot detection then keeps its results hidden). If the add-on could not be prepared, or has not connected within 15 s
  of the browser start, there is simply no HUD in the browser and the log says so once:
  `research add-on did not connect within 15 s: no HUD in the browser until it does (nothing is put into the page)`.
  The HUD state is still published, so an add-on that connects later shows it at once.
- **Chrome/Edge channel:** the chat browser gets the extension (stage 4) and behaves like Camoufox: HUD only in the
  extension, reads in its isolated world. Only when the extension is switched off or could not be loaded does the
  HUD fall back to the in-page overlay and the reads to `page.evaluate` (the fallback issue #23 asks for).
- Before the add-on connects (first seconds after the start) the HUD is only published to the bridge; the add-on
  shows the newest state as soon as it connects.

Switches (JVM system properties):

| Property | Effect |
| --- | --- |
| `-Daskai.browser.researchAddon=false` | Start Camoufox without the add-on (no HUD in the browser); start Chrome/Edge without the extension, as before stage 4 (in-page overlay, `page.evaluate`) |
| `-Daskai.browser.hud.inPageFallback=false` | Chrome/Edge without the extension: keep the overlay out of the page too |
| `-Daskai.browser.scriptFree=true` | Camoufox: every main-world script (`page.evaluate`) fails before it reaches the page, i.e. the read fallback of a Camoufox without add-on; with the add-on the reads run in its world and are unaffected. Default off |

### Interactions: real input only

AskAI never clicks, focuses or types with a page script. A consent control is located with Playwright locators (CSS
plus a `hasText` filter, evaluated by Playwright's own selector engine), measured with `boundingBox`, brought into
view with real wheel input if needed and clicked with the human-paced real mouse (`HumanPointer` over
`page.mouse()`, WebDriver BiDi input actions, trusted events). If that click does not take effect, the attempt ends;
there is no script fallback. Every remaining main-world script passes `PageScriptGate`, which a source-scan test
enforces.

Worker browsers (`PlaywrightWorkerBrowserBackend`) have no HUD. They load the add-on for page reads only (see
below), with a bridge of their own.

## Page reads in the add-on

Issue #22 showed why this matters: Bing's bot detection keeps its result list `visibility:hidden` for AskAI's
Camoufox, while Argos, which never runs scripts in the page's world, gets the revealed page. Every read-only script
AskAI ran with `page.evaluate` now runs in the add-on's isolated content-script world instead:

| Read | Script (unchanged) | Used by |
| --- | --- | --- |
| Structured SERP capture | `RenderedPageDocumentCapture.captureScript()` | mechanical SERP analysis |
| DOM fingerprint before/after the capture | `RenderedPageDocumentCapture.FINGERPRINT_SCRIPT` | capture consistency check |
| Challenge detection | `SearchPageGuards.challengeDetectScript` | `challengePresent`, `challengeMarker`, parked challenge tab |
| Consent detection | `SearchPageGuards.consentReportScript` | `consentCandidate` |
| Anchor list | `Playwright4jDriver.state()` | page state after every navigation |

How it runs: `Playwright4jDriver.readPage(page, script)` queues the script on the bridge with the page's URL and a
stable key per Playwright page. The background page fetches it (`GET /v1/read`) and finds the page's tab: the first
read of a key binds it to the one unbound tab showing that URL, every later read goes to exactly that tab, even when
other tabs (other worker user contexts) show the same URL. Several unbound candidates fail with `AMBIGUOUS_TAB`, no
tab within 2 s with `NO_TAB`, a bound tab showing another URL with `URL_MISMATCH`; a read is never redirected to a
different tab. Then it runs `(<script>)()` with `tabs.executeScript` in that
tab's top frame at once (`runAt: document_start`). The script runs in the content-script sandbox with Xray vision:
it sees the DOM, layout and computed styles, but the page's own scripts see neither the script nor its globals, and a
page that hooks `getBoundingClientRect` or `getComputedStyle` sees no call. The structured-clone result goes back with
`POST /v1/read-result`; Java gets the same value shapes as from `page.evaluate` (maps, lists, numbers, booleans,
strings), so the conversion in `RenderedPageDocumentCapture` and every validation after it are unchanged. While Java
waits (up to 30 s, which also covers an add-on that is still connecting), the Playwright message loop keeps running.
A failed or unanswered read fails like a failed `page.evaluate` did (`PlaywrightException`): the capture reports
"Structured page capture failed", the guards answer `none`. There is no fallback into the page's world while the
add-on is present.

The scripts stay in Java so the capture and guard logic has one source and the existing tests keep applying to it.
Only AskAI can queue a read (token and extension origin, as for the HUD), and the scripts only read: they write
nothing into the page, click nothing and navigate nowhere.

`page.evaluate` is only left for drivers without the add-on: Chrome/Edge worker tabs, Chrome/Edge without the
extension, and Camoufox started with `-Daskai.browser.researchAddon=false` or with an add-on that could not be
prepared. That fallback passes
`PageScriptGate`, so `-Daskai.browser.scriptFree=true` refuses it for Camoufox.

Not part of the reads: the consent click (`tryDismissConsent`) uses Playwright Locators and real mouse input, and
Camoufox has no in-page HUD overlay (see "Interactions: real input only" above).

## Stage 4: Chrome/Edge extension

The chat browser on the `chrome` and `msedge` channels gets the add-on as a Manifest V3 extension. The bridge, its
protocol, the HUD state, the commands and the read scripts are the same as for Firefox; the panel files
(`panel.html`, `panel.css`, `panel.js`) are shared.

### What the user sees

- a **toolbar button** (pinned, so it is visible without the extensions menu) with the same badge: the countdown in
  red while AskAI waits for the user, `II` while paused;
- a click on it opens the **side panel** with the same HUD as the Firefox sidebar; it stays open across navigations.
  Chrome only opens a side panel on a user gesture, so it is not opened automatically at the start.

### How it is loaded

| Step | Where |
| --- | --- |
| Extension sources (Manifest V3: own `manifest.json` and service worker `background.js`, panel shared with Firefox) | `browser-playwright/src/main/resources/askai-addon/chrome/` |
| Writes the extension with this start's `bridge.json` into a temporary root next to a fresh profile (both deleted on close) | `ResearchAddon.installChrome`, `Playwright4jDriver.launch` |
| Starts Chrome/Edge with that profile as a persistent context, with `--enable-unsafe-extension-debugging` and without Playwright's `--disable-extensions` | `ChromeResearchExtension.options` |
| Loads the extension over the DevTools protocol: `Extensions.loadUnpacked` | `ChromeResearchExtension.load` |
| Pins the toolbar button (`extensions.pinned_extensions` in the fresh profile's `Default/Preferences`) | `ChromeResearchExtension.pinToolbarButton` |

Why this way (measured with Chrome for Testing 153 in the live test and a Node prototype):

- **No `--load-extension`:** branded Chrome ignores that switch since version 137. `Extensions.loadUnpacked` over
  Playwright's pipe connection is the supported way for automation; it needs `--enable-unsafe-extension-debugging`.
- **A persistent profile:** Playwright's `browser.newContext()` contexts are off-the-record profiles. Even with the
  extension allowed there, it can list their tabs but not script them ("No tab with id"), and in split mode its
  worker does not start at all. So the chat browser runs in its profile's own context, like Camoufox. Worker browsers
  (`PlaywrightWorkerBrowserBackend`) need one off-the-record context per worker and therefore stay without the
  extension on Chrome/Edge: their reads keep `page.evaluate`.
- **The id is fixed** by the public `key` in the manifest (`elobpccbfihfcmjgkadloggolgnnfhnb`), so the bridge
  accepts exactly `Origin: chrome-extension://elobpccbfihfcmjgkadloggolgnnfhnb` next to `moz-extension://`, and the
  toolbar pin can name it before the start.

### Differences to Firefox, forced by Manifest V3

- **Reads as files, not as code strings.** An MV3 extension cannot execute script text (no `code`, no `eval` or
  `new Function` in the isolated world). The bridge therefore writes each read script as
  `reads/<sha256>.js` (content `(<script>)();`) into the extension directory before queuing the read, and the read
  carries `file` instead of `script`. The service worker runs exactly that file with
  `chrome.scripting.executeScript({target: {tabId, frameIds: [0]}, files: [file], world: 'ISOLATED',
  injectImmediately: true})`, which returns the file's value. It refuses any file outside `reads/`. Tab binding,
  errors (`NO_TAB`, `AMBIGUOUS_TAB`, `URL_MISMATCH`) and value shapes are the same as with Firefox.
- **Service worker instead of a persistent page.** The long polls, an extension API call every 20 s and a 30 s alarm
  keep it running; if Chrome stops it anyway, the next alarm starts it again and the loops resume.
- **Request interception.** Unlike Firefox, Playwright's `context.route` also sees the extension's requests. The
  driver lets through exactly the requests to this start's bridge (`http://127.0.0.1:<port>/v1/`) that carry its
  bearer token (`Playwright4jDriver.fromExtensionWorker`); every other loopback request of a page is still aborted,
  and the bridge checks token and origin again.

### Fallback

If the extension cannot be prepared or the browser refuses `Extensions.loadUnpacked` (log line
`research extension not loaded (HUD and page reads stay in the page)`), the browser keeps running without it: the HUD
is the in-page overlay and the reads use `page.evaluate`, as before stage 4. With
`-Daskai.browser.researchAddon=false` the chat browser starts exactly as before stage 4 (no persistent profile).

### Not verified yet

Verified on Linux with Chrome for Testing 153 (headless and headed). Branded Chrome and Edge on Windows still need
the manual acceptance; Edge's toolbar pin preference in particular is unverified (if Edge ignores it, the button sits
in the extensions menu, the panel works the same).

## Tests

- Unit: `AddonBridgeTest` (token, origin, long poll, command whitelist, page reads: round trip, held poll, add-on
  error, cancel, refused for pages, close; Chrome: only its own extension id, reads as script files),
  `ResearchAddonTest` (files, `bridge.json`, manifest exposes nothing to pages, one injection point for reads; the same
  for the Chrome extension, and its manifest key yields `CHROME_ID`), `ChromeResearchExtensionTest` (launch switches,
  toolbar pin, the request filter lets only token-bearing bridge requests through),
  `CamoufoxLaunchTest.withAddonAppendsTheAddonAndRechunksOnlyTheFingerprint`.
- Live, real Chrome/Edge (environment-gated like the other Chrome live tests: runs when the browser of
  `ASKAI_TEST_BROWSER_CHANNEL`, default `chrome`, is installed):

  ```
  gradlew :browser-playwright:test --rerun --tests '*ChromeResearchExtensionLiveTest'
  ```

  With `ASKAI_TEST_BROWSER_CHANNEL=msedge` for Edge, `ASKAI_LIVE_CHROME_HEADED=1` and
  `ASKAI_LIVE_CHROME_HOLD_SECONDS=120` to look at the toolbar button and the side panel (clicked commands are
  printed). It checks: the extension connects through the request filter and takes the HUD, the page sees no HUD and
  no binding, a real click on Skip in the panel reaches the HUD inbox, the reads (capture, challenge, consent, anchors)
  never call the page's hooked functions (`calls=0`), without the extension the overlay and `page.evaluate` are back,
  and the temporary profile is gone after close. `PlaywrightActorLiveBrowserTest` covers the overlay binding and
  therefore runs with the extension switched off.
- Live, real Camoufox (downloads it on first use, like the app):

  ```
  ASKAI_LIVE_CAMOUFOX=1 gradlew :browser-playwright:test --rerun --tests '*CamoufoxResearchAddonLiveTest'
  ```

  On Windows: `set ASKAI_LIVE_CAMOUFOX=1` first. With `ASKAI_LIVE_CAMOUFOX_HEADED=1` and
  `ASKAI_LIVE_CAMOUFOX_HOLD_SECONDS=120` the browser stays open with a running countdown; clicked HUD buttons are
  printed as `[addon-live] commands from the add-on: ...`.

  `pageReadsRunInTheAddonAndThePageSeesNone` captures a small result page through the add-on, reads challenge,
  consent and anchors, and checks that the page's own hooked `getBoundingClientRect`/`getComputedStyle` were never
  called (`calls=0`); `withoutTheAddonReadsRunInThePageWorld` is the counter-proof with `page.evaluate`.
  `workerTabsReadThroughTheWorkerBrowsersAddon` does the same in a worker tab, and
  `tabsWithTheSameUrlAreReadAsTheirOwnTab` opens the same URL with different content in two worker user contexts and
  checks that each read comes from its own tab.
