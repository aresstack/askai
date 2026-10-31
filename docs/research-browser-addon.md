# Research browser add-on

Status: stage 1 implemented (HUD in the add-on). Page reads (guard detection, SERP capture, anchors) run in the
add-on (see "Page reads in the add-on"). Consent clicks without JavaScript and the Chrome extension follow.

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
| Starts bridge and add-on, routes the HUD, falls back to the overlay | `Playwright4jDriver.launch` / `renderHud` |

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

### Fallback

- **Chrome/Edge channel:** there is no Chrome extension yet; the HUD stays the in-page overlay, as before.
- **Camoufox, add-on missing:** if the add-on could not be prepared, or has not connected within 15 s of the browser
  start, the HUD falls back to the in-page overlay and the log says so once:
  `research add-on did not connect within 15 s: falling back to the in-page HUD overlay, which pages can detect`.
  If the add-on connects later, the overlay is removed from the current page and the add-on takes over.
- Before the add-on connects (first seconds after the start) the HUD is only published to the bridge; the add-on
  shows the newest state as soon as it connects.

Switches (JVM system properties):

| Property | Effect |
| --- | --- |
| `-Daskai.browser.researchAddon=false` | Start Camoufox without the add-on (HUD = overlay) |
| `-Daskai.browser.hud.inPageFallback=false` | Never put the overlay into a page; without add-on there is no HUD in the browser |

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

How it runs: `Playwright4jDriver.readPage(page, script)` queues the script on the bridge for the tab's URL. The
background page fetches it (`GET /v1/read`), finds the tab showing exactly that URL (several: the active one, then
the most recently used; none within 2 s: `NO_TAB` error), and runs `(<script>)()` with `tabs.executeScript` in that
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

`page.evaluate` is only left for drivers without the add-on: Chrome/Edge (until stage 4) and Camoufox started with
`-Daskai.browser.researchAddon=false` or with an add-on that could not be prepared.

Not part of the reads: the consent click (`tryDismissConsent`) moves to Playwright Locators and real mouse input in a
separate change, together with removing the in-page HUD overlay for Camoufox.

## Stage 4: Chrome extension

The same add-on as a Chrome Manifest V3 extension, loaded with `--load-extension` into a persistent context; the
bridge protocol stays the same; the bridge then also accepts `Origin: chrome-extension://…` next to `moz-extension://`.

## Tests

- Unit: `AddonBridgeTest` (token, origin, long poll, command whitelist, page reads: round trip, held poll, add-on
  error, cancel, refused for pages, close), `ResearchAddonTest` (files, `bridge.json`, manifest exposes nothing to
  pages, one injection point for reads), `CamoufoxLaunchTest.withAddonAppendsTheAddonAndRechunksOnlyTheFingerprint`.
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
