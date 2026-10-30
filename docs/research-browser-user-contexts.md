# Parallel web research with isolated browser user contexts

Status: implemented on `adr0001/browser-parallel` (ADR 0001 lane "browser parallelism"). Terms follow
[WebDriver BiDi](https://www.w3.org/TR/webdriver-bidi/): a **user context** (`browser.UserContext`,
created with `browser.createUserContext`) is an isolated browser session with its own cookies, local storage
and sign-ins; a **browsing context** (`browsingContext.BrowsingContext`, created with `browsingContext.create`
and its `userContext` parameter) is one tab inside it.

## 1. Requirements (what, not how)

- Several research workers of one chat browse at the same time: search, open, navigate, read, several tabs.
- Workers do not disturb each other. Each worker can get its OWN user context: two workers can visit the same
  site, one signed in, one anonymous or with another account. Cookies and local storage never leak between
  workers, and never between chats.
- The research results are NOT isolated: every page any worker reads enters the session's one source
  process (capture store, scope checks, source lifecycle, duplicate detection, user approvals, phases).
- The number of parallel browser operations is a setting of its own, independent of the model capacity.
- Resources are bounded and released on worker end, cancel, failure and timeout; twenty tabs never mean
  twenty browser processes.

## 2. State before this change (develop, 2026-10-30)

| Topic | Finding |
|---|---|
| Browser per chat | One `LazyRestartableBrowserRuntime` per research session (chat), started on the first browser command, stopped after the idle time (`ProductiveResearchBackendFactory.createSession`). |
| Profile | `CamoufoxProvisioning.leaseProfile()`: ONE persistent profile `browser-profiles/camoufox` for the first session that locks it; every concurrently running session gets a temporary profile, deleted on close. There is no per-chat persistent profile. Unchanged by this work. |
| Playwright / BiDi | Official Playwright Java 1.62 API on playwright4j (Core JS driver on GraalJS in a child JVM). Camoufox = Firefox over WebDriver BiDi (`moz-firefox` channel), started with `launchPersistentContext`. Chrome/Edge use CDP. |
| Objects | `Playwright4jDriver`: Playwright → Browser → ONE BrowserContext → ONE Page (+ one parked challenge tab). |
| Threading | `BrowserSessionActor`: one owner thread runs every command and pumps events while idle. `LazyRestartableBrowserRuntime`: one more single-thread owner. Every browser command of a chat is serialized. |
| Tool contract | `BrowserToolSet` serves the flat `web_*` tools as text; `BrowserBridgeEndpoint` adds the `capture_id` convention and records every page into the session's `CaptureStore`. |
| Parallel support | None: one page, one owner thread, one queue per chat. |

What Playwright actually supports (checked in the bundled Playwright Core 1.62 driver, `coreBundle.js`):

- On the BiDi path, `browser.newContext()` sends `browser.createUserContext` (`BidiBrowser.doCreateNewContext`)
  and `context.newPage()` sends `browsingContext.create` with `userContext` (`BidiBrowserContext.doCreateNewPage`).
  Cookies are read and written per user-context partition (`storage.getCookies` with
  `partition.userContext`). So a Playwright `BrowserContext` on Camoufox IS a BiDi user context, and a `Page`
  IS a browsing context. `context.close()` sends `browser.removeUserContext`.
- A persistent context (`launchPersistentContext`) is the browser's DEFAULT user context, bound to its
  profile directory. You cannot open further persistent contexts inside it; extra user contexts in that
  process are always non-persistent.
- Playwright Java is not thread-safe: all calls on one `Playwright` instance and its objects must come from
  one thread, one at a time ([Playwright Java: multithreading](https://playwright.dev/java/docs/multithreading)).
  Several `Playwright` instances, each on its own thread, are allowed. Consequence: more user contexts in
  ONE Playwright instance give isolation, but not parallel execution.

## 3. Architecture decisions

1. **The chat browser stays untouched.** The persistent profile, the HUD, the serial research loop and the
   manual search keep running on `LazyRestartableBrowserRuntime` exactly as before.
2. **Worker browsers (lanes) for workers.** Each worker browser is one Playwright instance on its own owner
   thread with one NON-persistent browser process (Camoufox with the same fingerprint, prefs and executable
   as the chat browser, or Chrome/Edge). Playwright gives it a throw-away profile, deleted on close.
3. **One user context per worker** (`browser.newContext()` → `browser.createUserContext`), with one
   browsing context to start with and more on demand (`context.newPage()` → `browsingContext.create`).
   Each tab runs the unchanged session stack: page-scoped `Playwright4jDriver`, `PlaywrightBrowserSession`
   (URL policy before and after redirects, limits, consent/CAPTCHA guards), `BrowserToolSet` incl. the
   layout-repair tools.
4. **Parallelism = number of worker browsers.** Operations of different worker browsers run truly in
   parallel; user contexts in the same worker browser share its one call lane (Playwright's rule). Placement
   is parallelism first: a new worker gets an idle worker browser of its chat, else a NEW one while the
   process cap allows, else the least-loaded one, else (at the cap) an idle worker browser of another chat
   is stopped to make room, else the worker waits up to the acquire timeout.
5. **A worker browser belongs to one chat.** Chat A and chat B never share a browser process; the process cap
   holds over all chats (one shared pool per plugin).
6. **Results rejoin the session.** Every tab is served through the session's `BrowserBridgeEndpoint` tool
   contract (in-process only, no extra MCP endpoint), so each page a worker reads is recorded in the
   session's ONE `CaptureStore` with a `capture_id`; acceptance goes through the session's ONE
   `SourceAcceptanceService` (duplicate detection by canonical URL). No second source management.
7. **No second browser implementation.** Worker tabs reuse `Playwright4jDriver` (new page-scoped mode),
   `PlaywrightBrowserSession`, `BrowserToolSet`, `PlaywrightRenderedPageSource` and the bridge.

```text
AskAI
├── Chat A ── chat browser (persistent profile, unchanged, serial research path)
│   ├── worker browser w1 (process, own owner thread)
│   │   ├── user context uc1  (worker 1: own cookies/storage)  ── tab bc1, tab bc2
│   │   └── user context uc2  (worker 4)                       ── tab bc1
│   └── worker browser w2
│       └── user context uc3  (worker 2)                       ── tab bc1
└── Chat B ── chat browser
    └── worker browser w3
        └── user context uc4  (worker 1 of chat B)             ── tab bc1
all pages of chat A's workers ──► chat A's CaptureStore ──► source_accept / scope / approvals
```

## 4. Components and interfaces

Runtime (no browser types), `research-agent-runtime`, package `com.aresstack.askai.research.runtime.browser`:

| Type | Role |
|---|---|
| `BrowserUserContextPool` | `acquire(BrowserUserContextRequest)` → lease; `capacity()` = leases at once. `unavailable(reason)` for sessions without a browser. |
| `BrowserUserContextRequest` | `of(workerId, taskId)`, optional `withStateKey(key)` (keep sign-in across leases), `withAcquireTimeoutMillis`. |
| `BrowserUserContextLease` | `userContextId()`, `browser()` = first tab as `ToolInvoker` (same `web_*` + `capture_id` contract), `openBrowsingContext()`, `isOpen()`, `close()` (idempotent, any thread). |
| `BrowsingContextLease` | one more tab: `tools()`, `close()`. |
| `BrowserCapacityException` | no capacity within the timeout, or no browser. |

Browser backend, `browser-playwright`:

| Type | Role |
|---|---|
| `PlaywrightUserContextPool` (+ `.Chat`) | placement, limits, idle stop, chat scopes; thread-safe. |
| `WorkerUserContext`, `WorkerBrowsingContext` | one user context / one tab; `call(tool, args)`, `exportState()`. |
| `WorkerBrowserLimits` | the limits (settings). |
| `WorkerBrowserLane` | one worker browser: owner thread, FIFO queue, bounded calls, event pump, terminate. |
| `WorkerBrowserBackend` / `PlaywrightWorkerBrowserBackend` | what runs on the owner thread: `newContext`, `newPage`, tool calls, `storageState`. |
| `SiteConcurrencyGate` | per-site limit over all workers and chats. |

Plugin, `research-agent-ui-plugin` (`...research.host`):

| Type | Role |
|---|---|
| `ChatBrowserUserContexts` | the session's `BrowserUserContextPool`: chat scope + capture store + state store. |
| `WorkerTabRuntimePort` | a worker tab as `BrowserRuntimePort`, so `BrowserBridgeEndpoint.openInProcess()` serves it. |
| `WorkerUserContextStateStore` | saved sign-in states. |
| `SharedWorkerBrowserPool` | the one pool of the plugin. |
| `ProductiveResearchSessionResources.getBrowserUserContexts()` | where the task executor gets the pool (never null). |

Task executor integration (Lane F, `adr0001/f`), two places once both branches are in `develop`:

```java
// ResearchAgentSession.orchestratorSession(): browser capacity from the pool, the old setting only as fallback
ExecutorCapacity.fromPort(modelPort, pool.capacity());

// WebAcquisition.acquire(): one lease per task attempt, closed on success, failure, cancel and retry
try (BrowserUserContextLease lease = pool.acquire(BrowserUserContextRequest.of(workerId, taskId))) {
    ResearchLoop loop = buildResearchLoop(turn, lease.browser(), budget);
    ...
}
```

The model capacity (Model resources, ModelPort scheduler) and the browser capacity are separate resources:
a worker holding a lease holds no model slot and vice versa, so e.g. ten research tasks can browse while
only two of them talk to GPT-OSS 20B.

## 5. Lifecycles

- **Worker browser:** started on the first acquire that needs it (blocking only that worker; the first
  Camoufox start may download the browser), stopped after `idleStopMillis` without user contexts (the chat
  browser's "Idle stop (s)" setting), at chat end, at pool close, or terminated when a call hangs.
- **User context:** created on `acquire`, removed on `close()` (`browser.removeUserContext`: cookies and
  storage are gone). Saved first when the request has a state key.
- **Browsing context:** the first one opens with the user context; more via `openBrowsingContext()` up to the
  tab limit; each closes on its own, all close with the user context. Popups are closed, downloads refused.
- **Chat:** `ProductiveResearchSessionResources.close()` closes every lease and worker browser of the chat.
- **Persisted sign-in state:** only with a state key. Playwright `storageState` (cookies + local storage)
  in `<APPDATA>/.askai-java8/browser-profiles/worker-state/<sha256(chat)>/<key>.json`: next to the chat
  browser's own profile, never in the research project folder; owner-only (`rw-------`) on POSIX, the per-user
  `%APPDATA%` ACL on Windows (the same protection as the browser's own cookie database); atomic writes.

## 6. Synchronization and limits

| Setting (Research settings → "Worker browsers") | Key | Default |
|---|---|---|
| Processes (worker browsers, = parallel browser operations, all chats) | `research.browser.workers.maxProcesses` | 3 |
| User contexts/process | `research.browser.workers.maxUserContextsPerProcess` | 4 |
| Tabs/user context | `research.browser.workers.maxTabsPerUserContext` | 4 |
| Per site (operations on one host at once; search engines count as one) | `research.browser.workers.maxConcurrentPerSite` | 2 |
| Wait (s) for a free user context | `research.browser.workers.acquireTimeoutSeconds` | 120 |
| Per-call timeout | existing "Tool timeout (s)" | 300 |
| Idle stop of a worker browser | existing "Idle stop (s)" | 300 |

Capacity = processes x user contexts per process (default 12 leases, 3 operations in parallel).

- Every Playwright object of a worker browser is created and used only on its owner thread (ownership guard
  in `Playwright4jDriver`, asserted in the tests). Callers block on a future.
- A call that exceeds the timeout terminates its worker browser off the owner thread (the stuck call fails,
  the driver child ends); all user contexts there fail with `USER_CONTEXT_LOST` (to the runtime:
  `ToolInvoker.EndpointUnavailable`); other worker browsers are untouched; the next acquire starts a fresh one.
- A closed worker window is `USER_CONTEXT_LOST` for that worker only, never the chat-level
  `BROWSER_CLOSED` user stop.
- Cancel: an interrupted worker leaves the wait (acquire, site gate or call) with `InterruptedException`;
  its `finally` closes the lease; capacity is free at once.

## 7. Impact on the research agent architecture

- Unchanged: chat browser, serial research loop, manual search, HUD, `WebSearchApplicationService`,
  source lifecycle, scope checks, approvals and phase transitions. The pool only hands out browsers; what a
  worker may do stays the orchestrator's and the user's decision.
- `BrowserBridgeEndpoint` got `openInProcess()` (same tools, no MCP endpoint); `Playwright4jDriver` got a
  page-scoped mode; `PlaywrightRenderedPageSource` can run on a worker tab.
- ACP components are not touched.

## 8. Verification

| Test | Shows |
|---|---|
| `PlaywrightUserContextPoolTest` (13, deterministic worker browser) | worker vs worker cookie isolation; 4 workers in 4 processes overlap (max 4 in flight, < 1.5 s for 4 x 600 ms); 4 user contexts in 1 process serialize (honest bottleneck, ≥ 1 s for 4 x 250 ms); tabs share the session and close alone, tab limit; chat A/B never share process or cookies, closing B leaves A; capacity wait, timeout, reuse; cancelled waiter; hung call terminates only its process and is replaced; per-site limit; state export/import; idle stop and reclaim at the cap; failed start; pool close; owner-thread check for every call. |
| `PlaywrightUserContextPoolLiveTest` (real Chromium) | cookie AND local-storage isolation, second tab, chat separation, state restore, three navigations at the server at the same time, cleanup. Gated on a Chromium executable. |
| `ChatBrowserUserContextsLiveTest` (real Chromium, plugin) | leases through the runtime API; parallel workers' pages land in the session's one capture store; the one acceptance makes 3 sources of 4 visits (the page two workers found is one source); lease close; sign-in saved and restored; session close ends everything. |
| `WorkerUserContextStateStoreTest` | per-chat files, owner-only permissions, atomic replace, no path escape. |

Remaining limitations: the live tests run Chromium (CDP); Camoufox (BiDi) uses the same Playwright calls and
the BiDi mapping above, but needs the managed Camoufox and was not run in CI. Each worker browser costs a
browser process plus a playwright4j driver JVM. The per-site limit for `web_follow`/`web_back` uses the site
of the page the tab shows. Wiring the pool into the task executor is the two-line change in section 4.
