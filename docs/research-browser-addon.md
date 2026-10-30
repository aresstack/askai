# Research browser add-on

Status: stage 1 implemented (HUD in the add-on). Stages 2 to 4 are planned.

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

Nothing of the add-on: no content script, no web-accessible resource, no element, no global, no binding. The add-on
only has a background page and its own panel page. The live test checks this in a real Camoufox: the page reports
`hud=false;binding=undefined;root=undefined`.

This removes OUR traces from the page; it does not make an automated browser undetectable. Playwright's own
evaluations (guards, capture, stages 2 and 3) and every other automation signal stay as they are.

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
- `POST /v1/hello`: protocol and add-on version, logged once.

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

Worker browsers (`PlaywrightWorkerBrowserBackend`) have no HUD and are started without the add-on in stage 1.

## Stage 2: CAPTCHA and consent guards in the add-on

Move `SearchPageGuards` (challenge detection, consent detection and dismissal) into a content script that runs in
the add-on's isolated world (Xray vision: page prototype hooks do not see it). The background page answers guard
requests over the bridge; requests are keyed by the tab the add-on reports for the Playwright page (URL plus a
per-navigation nonce published through the bridge, never through the page). The worker browsers get the add-on too.
The `page.evaluate` path stays as the fallback for Chrome until stage 4.

## Stage 3: SERP capture in the add-on

Move `RenderedPageDocumentCapture` and the anchor list into the content script. This touches the same selector and
capture code as the Bing extraction work, so it starts only after that is settled and is coordinated with it.

## Stage 4: Chrome extension

The same add-on as a Chrome Manifest V3 extension, loaded with `--load-extension` into a persistent context; the
bridge protocol stays the same; the bridge then also accepts `Origin: chrome-extension://…` next to `moz-extension://`.

## Tests

- Unit: `AddonBridgeTest` (token, origin, long poll, command whitelist), `ResearchAddonTest` (files, `bridge.json`,
  manifest exposes nothing to pages), `CamoufoxLaunchTest.withAddonAppendsTheAddonAndRechunksOnlyTheFingerprint`.
- Live, real Camoufox (downloads it on first use, like the app):

  ```
  ASKAI_LIVE_CAMOUFOX=1 gradlew :browser-playwright:test --rerun --tests '*CamoufoxResearchAddonLiveTest'
  ```

  On Windows: `set ASKAI_LIVE_CAMOUFOX=1` first. With `ASKAI_LIVE_CAMOUFOX_HEADED=1` and
  `ASKAI_LIVE_CAMOUFOX_HOLD_SECONDS=120` the browser stays open with a running countdown; clicked HUD buttons are
  printed as `[addon-live] commands from the add-on: ...`.
