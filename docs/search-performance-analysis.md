# Search performance: where the time goes with Camoufox

Status: analysis (2026-10-29), measured on `develop@523699ea` (PR #50). No code changed by this document.

Update 2026-10-30: options 1 and 2 are implemented on `develop` — the browser generation stays warm after a
search for the idle keep-alive setting ("Idle stop (s)", default 300 s) instead of stopping on every
terminal event, and the capture retries are a setting (`analysis.maximumCaptureRetries`, default 0 = capture
once and flag the change). Since the Java 21 return the "sidecar start" below is the in-process
`PlaywrightBrowserRuntime.start` (driver child JVM + browser launch); its cost is unchanged, it is just paid
once per idle period now.
The measurements were taken with temporary timing lines in the sidecar (not committed) and a scratch
test that drives `BrowserSessionActor` → `SearchLayoutRepairTools.prepare` exactly as the sidecar wires it,
plus one standalone start of the sidecar jar. Configuration: the user's `browser-config.json` (Bing,
3 result pages, engine delay 0), `headless=false`, persistent Camoufox profile, Chrome as the comparison
channel with a fresh context.

## The user-visible numbers (live run in AskAI, search "gurkensalat")

| Phase | Measured |
|---|---|
| `web_search_prepare` as seen by the runtime (`[initial-search] … elapsedMs`) | 42.8 s |
| … of which lazy sidecar + browser start | ~10–13 s |
| … of which the SERP prepare over 3 Bing pages | ~30 s |
| Acquisition: 8 accepted sources, 1 unreadable, 3 followed links | ~52 s (`capturedSince` → `capturedThrough`) |
| Total until the agent summary starts | ~95 s |

The first hit is opened only after ~42 s; afterwards a page costs ~5–6 s end to end.

## Measured breakdown (sidecar side)

| Step | Camoufox | Chrome |
|---|---|---|
| Sidecar JVM start → `ready on` (standalone jar) | 10.1 s | 8.8 s |
| … JVM + Solon + capability probe | 0.4 s | 0.4 s |
| … `CamoufoxProvisioning.ensureInstalled` (omni.ja check) | 0.1 s | – |
| … `Playwright.create` (playwright4j: GraalJS driver child, Playwright Core JS interpreted) | 7.7 s | 7.2 s |
| … browser launch (`launchPersistentContext` / `chromium.launch`) | 1.9 s | 0.6 s |
| SERP prepare, 3 result pages (`SearchLayoutRepairTools.prepare`) | 28.8 s | n/a (see side observation) |
| … `captureRenderedPage` total, 3 pages | 23.0 s | |
| … of which the 2 re-captures per page | 14.9 s | |
| … capture evaluate, page 1 (600 containers = `maximumCapturedContainers`) | 4.8 s | 2.5 s |
| … `page.navigate` per Bing page | 0.3–1.2 s | 0.4–0.8 s |
| … `state()` page 1 (`innerText` + anchors evaluate) | 2.1 s (innerText 1.7 s) | 1.6 s (innerText 0.5 s) |
| … consent locate + challenge detect per page | 0.1–0.2 s | 0.05 s (1.7 s with a consent click) |
| … judge (extraction, pure Java) per page | 0.005–0.06 s | 0.07 s |
| Page visit (`open` = navigate + state + policy, then `links`) | 1.1–1.8 s | |

Per Bing page on Camoufox:

| Page | `open` (navigate + state) | capture evaluate | re-captures | capture total | containers |
|---|---|---|---|---|---|
| 1 | 3.3 s | 4.8 s | 3.7 s + 3.6 s | 13.0 s | 600 (cap) |
| 2 | 1.1 s | 1.7 s | 1.6 s + 1.6 s | 5.7 s | 275 |
| 3 | 0.9 s | 1.2 s | 1.2 s + 1.2 s | 4.4 s | 195 |

On all three pages the DOM fingerprint (`element count : body text length : top-level tags`) differed before
and after the capture, after both re-captures too (`DOM_CHANGED_DURING_CAPTURE` on every page). Bing keeps
mutating its page (lazy images, ads, related searches), so the re-captures never reach a consistent state.

## Findings

1. **The browser is not the cost.** Camoufox launch, install verification and navigation together stay
   under 3 s. The sidecar start costs ~10 s because `Playwright.create` loads the Playwright driver as
   interpreted JavaScript on GraalJS (the sidecar logs "JVMCI is not enabled for this JVM"). This is paid on
   EVERY search: the host stops the browser phase after each search (`stopBrowserPhase` → `browser.stop()`)
   and the next search spawns a fresh sidecar.
2. **Half of the SERP prepare is wasted re-capturing.** `RenderedPageDocumentCapture` retries the capture up
   to `MAX_RECAPTURES = 2` times (a code constant, not a setting) with a 300 ms settle in between whenever the
   fingerprint changed. On Bing it always changes, so every page runs three full captures and still ends with
   the same warning: 14.9 s of 28.8 s.
3. **Camoufox doubles the cost of big evaluates.** Firefox over WebDriver BiDi serializes the large capture
   result about twice as slowly as Chrome over CDP (4.8 s vs 2.5 s for 600 containers); `innerText` on the
   first page took 1.7 s vs 0.5 s. The payload per container includes geometry (`getBoundingClientRect`) and
   computed colors (`getComputedStyle`), so the cost scales with the container cap and the fields per
   container.
4. **The acquisition phase is dominated by the host/runtime, not the sidecar.** A visit costs 1.1–1.8 s in the
   sidecar but ~5–6 s in the app. Per page the runtime runs `web_probe` (navigate + full `state()`),
   `web_dismiss_consent`, `web_read` (a SECOND full `state()` via `currentPage()`), `web_links`, several
   `web_hud_render` calls (two evaluates each: install + render script), and every call travels
   runtime → host bridge → sidecar (two MCP hops). In parallel the knowledge worker embeds every accepted
   page on the CPU (`nomic-embed-text`, up to 378 passages for one page) while the GraalJS driver is CPU-bound
   too. The CPU-contention share is NOT measured yet; it is the one open point of this analysis.

Not the problem: JVM/Solon boot (0.4 s), the Camoufox install check (0.1 s), the Camoufox launch (1.9 s),
Bing navigation (0.3–1.2 s per page), extraction/judging (milliseconds), the reranker (BM25, lexical).

## Improvement options, ranked by gain

| # | Option | Estimated gain | Effort / risk |
|---|---|---|---|
| 1 | Keep the sidecar (and its Playwright driver) alive between searches, e.g. an idle timeout instead of stop-on-terminal | ~10 s per search | small; the window stays open, memory stays allocated |
| 2 | Re-capture policy as a setting: no retry when the fingerprint keeps changing, or a fingerprint that ignores volatile parts | ~15 s per search (page 1 alone ~7 s) | small; the `DOM_CHANGED_DURING_CAPTURE` warning stays honest |
| 3 | Smaller capture payload for SERPs: lower container cap, fewer per-container fields (colors, geometry only where the analysis uses them) | ~2–3 s per search | medium; the mechanical analysis and the AI layout repair read these fields |
| 4 | No redundant `state()`: the SERP prepare does not need `innerText`; `web_read` re-reads what `web_probe` just read; cache per navigation | ~2–3 s per search + ~0.5 s per visited page | small |
| 5 | Result pages 3 → 2 (existing setting) or lazy pagination: fetch page 2 only when the reranked page-1 candidates are not enough | ~5–12 s per search | small; fewer candidates for the reranker |
| 6 | JIT for the driver: Graal compiler via JVMCI (`-XX:+EnableJVMCI`, compiler jar on the upgrade module path) | faster start and every protocol message; unmeasured | medium; large dependency, JVM flags on the sidecar command line; only worth it if option 1 is not enough |
| 7 | Throttle the embedding worker while a browser phase runs | unknown until measured | small, once the contention is confirmed |
| 8 | HUD: install the overlay script once per page, render less often | ~0.5 s per visited page | small |

Options 1, 2 and 4 together bring the first opened hit from ~42 s to roughly 12 s without touching the search
method itself.

## How to reproduce the measurement

- Timing points used (temporary `System.err.println("[timing] …")`): `Playwright4jDriver.launch`
  (ensureInstalled, `Playwright.create`, launch, page), `Playwright4jDriver.open` (navigate, `state()` with
  innerText and anchors), `RenderedPageDocumentCapture.capture` (fingerprint, capture evaluate, re-captures,
  converted containers), `PlaywrightBrowserSession.navigateAndCaptureSearchEngines` (open, consent, challenge,
  capture, judge).
- Scratch test: start `BrowserSessionActor` with `PlaywrightSessionFactory.create(channel, false, false, null,
  defaults, loadBrowserConfig(path))`, run `SearchLayoutRepairTools.prepare("gurkensalat")`, then `open` +
  `links` for three candidate URLs. Channel via `ASKAI_TEST_BROWSER_CHANNEL`.
- Sidecar boot: run `build/research-runtime/browser-mcp-sidecar.jar` with `--browser-channel`, `--headless=false`
  and `--browser-config`, stdin held open, and timestamp its stderr until `ready on`.

## Side observation

The Chrome comparison run used a fresh context: Bing showed its cookie wall (clicked `ACCEPT_ALL` by the
pointer path, 1.7 s), the first page was judged UNUSABLE and the second page came back with 129 characters of
text, so the prepare ended as REPAIR_REQUIRED with 0 candidates. One run, not an assessment of Chrome; the
persistent Camoufox profile keeps its consent state and is not affected.
