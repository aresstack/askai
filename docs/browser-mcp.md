# Browser MCP (Playwright sidecar + static backend)

## Modules

- `:browser-api` (Java 8) — the shared port: `BrowserSession` (search/open/currentPage/links/follow/
  back/close), `BrowserPageSnapshot` (url/title/clean text/truncated — never raw HTML),
  `BrowserLink` (stable per-snapshot ids `link-1..n`), `WebSearchResult`, `UrlSafetyPolicy`,
  `BrowserLimits`, `BrowserBackendKind { PLAYWRIGHT_SIDECAR, STATIC_HTTP }`.
- `:browser-static-http` (Java 8) — `StaticHttpBrowserSession` (jsoup): static fetch + cleanup only;
  search is honestly unsupported. A separate, visibly limited backend — **never** an automatic
  fallback for the sidecar.
- `:browser-mcp-server-java21` — the ONE deliberate Java-21 process. The Java-8 host never compiles
  against it and never loads its classes; it only talks MCP to it.

## How the Playwright backend works (MCP-P005, resolved)

The sidecar uses the OFFICIAL Playwright Java API (`com.microsoft.playwright:playwright:1.62.0`,
with its `driver`/`driver-bundle` modules excluded) plus `com.aresstack:playwright4j:0.3.0`, which
replaces `com.microsoft.playwright.impl.driver.Driver`: instead of a Node.js process it spawns a
second **Java** process (`GraalDriverMain`) hosting the Playwright Core JS driver on GraalJS.

The default browser is **Camoufox** (`--browser-channel=camoufox`), taken over from
[Argos](https://github.com/aresstack/argos) with minimal changes (package
`com.aresstack.askai.browser.sidecar.camoufox`: `CamoufoxDistribution`, `CamoufoxInstaller`,
`CamoufoxLaunch`, plus `camoufox/distribution.properties` and the fingerprint profile
`camoufox/default-launch.json`):

- **One-time download** of Argos' Camoufox distribution `camoufox-156.0.1-beta.36-argos.2`: the
  daijro/camoufox release with the two WebDriver BiDi fixes of daijro/camoufox#952 already applied
  (not in any Camoufox release yet) plus uBlock Origin. Installed to `%APPDATA%/.askai/browser`.
- **Verification**: archive SHA-256 pinned per platform (Windows x86_64, Linux x86_64), download into a
  `.partial-*` folder, unpack, check manifest and both fixed `omni.ja` entries, then one atomic rename.
  Every start re-checks the marker, the executable and both fixes. Concurrent installs serialize over
  `.install.lock`.
- **Start**: `firefox().launchPersistentContext` with channel `moz-firefox`, the verified executable,
  the fingerprint environment (`CAMOU_CONFIG_1..n`, `CAMOU_PREFS_1..n`) and prefs of the built-in
  profile, and no Playwright emulation (viewport, color scheme ...), exactly as Argos starts it. Profile:
  `%APPDATA%/.askai/browser-profiles/camoufox`; a concurrent research session gets a temporary
  profile that is deleted on close.
- **Progress**: while installing, the sidecar prints `camoufox install: ...` lines every 5 s; the host
  restarts its readiness timeout on each one. When a research session starts, the host runs
  `--install-camoufox` in the background so the first browser command usually finds the browser ready.

The channels `chrome` / `msedge` use a **locally installed** Chrome or Edge via the channel mechanism.

Process tree: `sidecar JVM -> GraalDriverMain JVM -> browser processes`. playwright4j embeds the Playwright Core
JS package (`driver/package/**`, no Node.js binaries) in its own jar; `driver-bundle` is no longer needed.

**Playwright never downloads a browser**: `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` is fixed; Chrome/Edge
discovery only probes standard install locations. The only download is the pinned Camoufox above.

## Capability probing

`PlaywrightCapabilityProbe` reports a SPECIFIC status before the backend is offered:
`READY / INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED /
BROWSER_START_FAILED`. There is no `NODE_RUNTIME_NOT_FOUND` — playwright4j needs no Node; the
equivalent failure is a missing embedded driver package. When not READY, every tool returns the status as a
readable error; the endpoint stays up and there is no STATIC_HTTP fallback.

## Security

- `UrlSafetyPolicy` runs BEFORE navigation and again on the FINAL URL after redirects; a blocked
  redirect target abandons the page.
- Best-effort in-browser request interception aborts requests to loopback/RFC1918/link-local/
  cloud-metadata literals (strict mode).
- Downloads refused, popups closed immediately, one BrowserContext per session, navigation timeouts
  and text/link limits from `BrowserLimits`.
- `web_search` is navigation to a configured `--search-url={query}` provider (extraction behind
  `WebSearchProvider`); without configuration it fails honestly. No hardcoded search engine.

## Packaging (thin jar + lib/)

A fat jar is NOT viable: repackaging GraalJS/Truffle loses `Multi-Release: true` and collapses
duplicate `META-INF/services` files → `InternalError: Truffle could not be initialized` (found by
the 36C live test). The `sidecarJar` task builds `build/sidecar/browser-mcp-sidecar-<version>.jar`
(thin, `Class-Path` manifest) + `build/sidecar/lib/` (62 untouched dependency jars). `java -jar`
works; the `-cp`-spawned driver child inherits the classpath via the same jar mechanism.

## Running

```
java -jar browser-mcp-sidecar-<version>.jar --port=<port> --token=<token> \
     [--browser-channel=camoufox|chrome|msedge] [--headless=true|false] \
     [--allow-private=true|false] [--search-url=<template with {query}>]

java -jar browser-mcp-sidecar-<version>.jar --install-camoufox   # download/verify only, exit 0 = ready
```

MCP endpoint: `http://127.0.0.1:<port>/mcp/browser/<token>` (streamable). Logs go to STDERR only;
the token never appears in them. Shutdown closes page → context → browser → Playwright (ending the
driver child and Chromium).

## Verification

Unit tests (session mapping, link ids, pre/post-redirect policy, probe classification, request
filter) plus `PlaywrightLiveBrowserTest` — a real Chrome run against JavaScript-only local pages,
environment-gated on the probe (skips readably without an installed browser).
