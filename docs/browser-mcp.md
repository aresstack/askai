# Browser backend (in-process Playwright + static backend)

## Modules

- `:browser-api` — the shared port: `BrowserSession` (search/open/currentPage/links/follow/back/probe/
  dismissConsent/HUD/close), `BrowserPageSnapshot` (url/title/clean text/truncated — never raw HTML),
  `BrowserLink` (stable per-snapshot ids `link-1..n`), `WebSearchResult`, `UrlSafetyPolicy`,
  `BrowserLimits`, `BrowserBackendKind { PLAYWRIGHT_SIDECAR, STATIC_HTTP }`.
- `:browser-static-http` — `StaticHttpBrowserSession` (jsoup): static fetch + cleanup only;
  search is honestly unsupported. A separate, visibly limited backend — **never** an automatic
  fallback for the Playwright backend.
- `:browser-playwright` — the Playwright backend as a LIBRARY (until the return to Java 21 this was the
  separate Java-21 sidecar process `browser-mcp-server-java21`). The research plugin bundles it in its
  `lib/` and starts `PlaywrightBrowserRuntime` IN-PROCESS on the first browser command of a research
  phase; `BrowserToolSet` dispatches the flat `web_*` tool set by name with exactly the text contracts the
  sidecar used to serve over MCP. The host's `BrowserBridgeEndpoint` still publishes those tools (plus the
  `capture_id` convention) to the research runtime.
- The host wrapper `LazyRestartableBrowserRuntime` bounds every data call with the per-call browser timeout
  setting ("Tool timeout (s)" in the research settings). A call that exceeds it is treated like the former dead
  sidecar process: the generation is terminated off the owner thread (the Playwright connection is cut, which
  fails the stuck call and ends the driver child) and the command is retried once on a fresh generation; a
  wedged owner thread is abandoned behind a bounded wait, so neither a command nor the session close can hang.

## How the Playwright backend works (MCP-P005, resolved)

The backend uses the OFFICIAL Playwright Java API (`com.microsoft.playwright:playwright:1.62.0`,
with its `driver`/`driver-bundle` modules excluded) plus `com.aresstack:playwright4j:0.3.0`, which
replaces `com.microsoft.playwright.impl.driver.Driver`: instead of a Node.js process it spawns a
second **Java** process (`GraalDriverMain`) hosting the Playwright Core JS driver on GraalJS.

The default browser is **Camoufox** (channel `camoufox`), taken over from
[Argos](https://github.com/aresstack/argos) with minimal changes (package
`com.aresstack.askai.browser.playwright.camoufox`: `CamoufoxDistribution`, `CamoufoxInstaller`,
`CamoufoxLaunch`, plus `camoufox/distribution.properties` and the fingerprint profile
`camoufox/default-launch.json`):

- **One-time download** of Argos' Camoufox distribution `camoufox-156.0.1-beta.36-argos.2`: the
  daijro/camoufox release with the two WebDriver BiDi fixes of daijro/camoufox#952 already applied
  (not in any Camoufox release yet) plus uBlock Origin. Installed to `%APPDATA%/.askai-java8/browser`.
- **Verification**: archive SHA-256 pinned per platform (Windows x86_64, Linux x86_64), download into a
  `.partial-*` folder, unpack, check manifest and both fixed `omni.ja` entries, then one atomic rename.
  Every start re-checks the marker, the executable and both fixes. Concurrent installs serialize over
  `.install.lock`.
- **Start**: `firefox().launchPersistentContext` with channel `moz-firefox`, the verified executable,
  the fingerprint environment (`CAMOU_CONFIG_1..n`, `CAMOU_PREFS_1..n`) and prefs of the built-in
  profile, and no Playwright emulation (viewport, color scheme ...), exactly as Argos starts it. Profile:
  `%APPDATA%/.askai-java8/browser-profiles/camoufox`; a concurrent research session gets a temporary
  profile that is deleted on close.
- **Prefetch**: when a productive research session with the Camoufox channel starts, the plugin runs the
  installer once in the background (`CamoufoxPrefetch` → `PlaywrightBrowserRuntime.installCamoufox()`), so
  the first browser command usually finds the browser ready; the installer prints `camoufox install: ...`
  progress lines every 5 s.

The channels `chrome` / `msedge` use a **locally installed** Chrome or Edge via the channel mechanism.

Process tree: `AskAI JVM -> GraalDriverMain JVM -> browser processes`. playwright4j embeds the Playwright Core
JS package (`driver/package/**`, no Node.js binaries) in its own jar; `driver-bundle` is not needed.

**Playwright never downloads a browser**: `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` is fixed; Chrome/Edge
discovery only probes standard install locations. The only download is the pinned Camoufox above.

## Classloading and packaging

The browser stack (Playwright, playwright4j, the GraalVM 25 polyglot/Truffle/JS jars, Jackson) ships as
intact jars in the research plugin's `lib/` and is loaded plugin-first by the PF4J plugin classloader. It
therefore never shares a classpath with the host's GraalJS 21.2 (mermaid-java, PAC evaluation), and nothing
is repackaged: Truffle needs the jars' `Multi-Release: true` manifests and their `META-INF/services` files
untouched (a fat jar breaks it with `InternalError: Truffle could not be initialized`). The AskAI fat jar
never contains the plugin. The driver child JVM takes its classpath from the plugin classloader's jar URLs
(`DriverClasspath` in playwright4j); `-Dplaywright4j.driver.classpath=<path-list>` overrides the detection.

## Threading

Playwright Java is not thread-safe and dispatches its events only while the owning thread is inside a
Playwright call. `BrowserSessionActor` creates Playwright and everything below it on ONE dedicated owner
thread and runs every command there; callers block on a future. While idle the owner pumps the Playwright
event loop (route interception, HUD bindings, popup close). The HUD command poll (`web_hud_poll`) drains its
inbox directly on the caller's thread, so a Skip is deliverable while a data call blocks the owner. The
plugin's `LazyRestartableBrowserRuntime` adds its own owner thread per research session: created STOPPED,
started on the first command, stopped when the browsing phase ends, replaced on failure.

## Capability probing

`PlaywrightCapabilityProbe` reports a SPECIFIC status before the backend is offered:
`READY / INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED /
BROWSER_START_FAILED`. There is no `NODE_RUNTIME_NOT_FOUND` — playwright4j needs no Node; the
equivalent failure is a missing embedded driver package. When not READY, `PlaywrightBrowserRuntime.start`
fails with that status line (the settings tab's "Check requirements" shows it); there is no STATIC_HTTP
fallback.

## Security

- `UrlSafetyPolicy` runs BEFORE navigation and again on the FINAL URL after redirects; a blocked
  redirect target abandons the page.
- Best-effort in-browser request interception aborts requests to loopback/RFC1918/link-local/
  cloud-metadata literals (strict mode).
- Downloads refused, popups closed immediately, one BrowserContext per session, navigation timeouts
  and text/link limits from `BrowserLimits`.
- `web_search` is navigation to the configured search engines (extraction behind `WebSearchProvider`).

## Dev/test hand-off

Local test worlds pass extra browser arguments per run through the documented system property
`askai.research.browser.args`, e.g. `--domain-key-mode=host-port` (host:port domain families) and
`--search-url=http://127.0.0.1:1234/find?q={query}` (one explicit engine). Never a persisted value.

## Verification

Unit tests (session mapping, link ids, pre/post-redirect policy, probe classification, request
filter) plus `PlaywrightLiveBrowserTest` — a real browser run against JavaScript-only local pages,
environment-gated on the probe (skips readably without an installed browser). The research runtime's
`ResearchLoopPlaywrightIntegrationTest` drives the UNCHANGED research loop against the in-process
browser through an in-JVM bridge endpoint.
