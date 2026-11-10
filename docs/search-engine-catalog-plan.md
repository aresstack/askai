# Search engine catalog and priority table — inventory and PR plan

Status: plan, 2026-11-09. Requested by Angelo (spec forwarded from GPT, plus his additions: the request delay is a
column of the table like the languages; Startpage and Yahoo are the first engines to implement).

## 1. Goal

Search → Engines becomes a table of *engine configurations*. Each row points at an engine of a fixed catalog and has
its own active flag, priority, delay, result pages and search languages. The same engine may appear in several rows
(for example Google in German at priority 1 and Google in French and Spanish at priority 3). Priority 0 runs first;
rows with the same priority form one group that is searched in the same round; the next group runs only when the
previous one did not deliver enough usable results.

The catalog lists 28 engines and search services. Engines without an adapter stay visible in the catalog but are
never offered as working.

## 2. Inventory (develop c19cca99, plus open PR #161)

What exists and is extended, not replaced:

| Piece | Where | Role today |
| --- | --- | --- |
| `BrowserSearchEngineCatalog` | browser-api `search/engine` | The engines AskAI can *drive*: Google (search browser, Serp4J), DuckDuckGo (HTML + Lite start pages), Bing. Each is a `BrowserSearchEngine` with `SearchForm`s (start page, search box selectors, next-page selectors). |
| `BrowserSearchEngineSelection` + `Entry` | browser-api `search/engine` | The user's engine list: id, on/off, result pages, delay. Stored flat in `navigation.engines` as `google:on:1:15,duckduckgo:on:3,bing:off:3`; list order = execution order. |
| `EngineAcquisitionMode` | browser-api | `FIRST_USABLE` (stop at the first engine with results) or `ALL_ENABLED` (merge all). Setting `navigation.engineAcquisitionMode`. |
| `LegacyBrowserSearchDefaults.engineSelection()` | browser-api | Fresh-install default (#166): Google on, 1 page, 15 s delay; DuckDuckGo on as fallback; Bing off; `FIRST_USABLE`. |
| `SearchEngineOrderEditor` | research-agent-ui-plugin `host` | The engine list under Search → Browser (SERP) → Engines: check box per engine, ↑/↓ for order, pages and delay spinners in the selected row. |
| `BrowserEnginesSearchStrategy` | research-agent-runtime `search` | The web search tool's browser path. Builds at most two steps from the selection: Google in the search browser, and *one* "page-engines" step (DuckDuckGo/Bing together in the research browser). PR #161 makes it one-search-at-a-time for the whole process. |
| `SearchBrowser` (+ `SearchEngineThrottle` in #161) | browser-playwright | The one search browser. #161: spacing between every engine request (the Google delay), pause after a challenge, doubling up to a cap. |
| `SearchEngineHosts` | browser-api | Engine hosts the research browser never opens as pages (Google, Bing, DuckDuckGo). #161 refuses them in worker contexts. |
| `EngineFamily` | browser-api `search/layout` | Already knows Startpage, Brave, Ecosia, Mojeek, Yandex as SERP families; the result extraction itself is engine-agnostic (mechanical analysis). |
| `InitialSearchRequest.language` / `country` | research-agent-runtime | Language and region hints of one search, today filled from the global `navigation.language` / `navigation.country`. |

Consequences for the design:

- **No second search path.** Every engine row runs through the existing web search tool
  (`BrowserEnginesSearchStrategy` → search browser / research browser). There is no search service and no
  `search/service` package; the serialisation, spacing and challenge pause of #161 stay the single gate to the engines.
- **Catalog entry ≠ configuration row.** `SearchEngineCatalog` / `SearchEngineCatalogEntry` describe engines;
  `BrowserSearchEngineSelection.Entry` is the user's row and is extended (priority, languages) rather than replaced.
- **Adding an engine is data first.** A browser engine is a `BrowserSearchEngine` with its `SearchForm` in
  `BrowserSearchEngineCatalog` plus its hosts in `SearchEngineHosts`; the orchestrating strategy never gets an
  engine-specific branch. Where `SearchForm` is not enough for an international engine (consent flow, language
  parameter, IME-free typing of CJK text), `SearchForm` is extended as data.

## 3. Decisions taken (no question needed)

1. **Ids.** Existing ids stay (`google`, `bing`, `duckduckgo`); new ids are lower case, e.g. `startpage`, `yahoo`,
   `yahoo-japan`, `so360` (360 Search), `coccoc`.
2. **Status.** `SUPPORTED`, `EXPERIMENTAL`, `NOT_IMPLEMENTED`, `UNAVAILABLE`. A catalog entry is searchable exactly
   when its browser driver exists in `BrowserSearchEngineCatalog` (enforced by a test). Today: Google and DuckDuckGo
   `SUPPORTED`, Bing `EXPERIMENTAL` (not validated live recently), all others `NOT_IMPLEMENTED` with a note. Yaani's
   operation could not be confirmed (2026-11), so it is `NOT_IMPLEMENTED` with that note rather than removed.
   SearXNG and YaCy need an instance address, Kagi a paid account; Brave Search has an API provider already but no
   browser adapter.
3. **Languages** are BCP-47 codes (`de`, `zh-CN`, `zh-Hant`), normalised and de-duplicated; malformed input is
   reported, never stored. The default languages of the catalog are starting values for a new row, not a verified
   filter. No engine has a verified language filter yet, so the language of a search is carried by the wording of the
   query (the planner writes it in that language); `country` stays separate.
4. **Delay is a column** (Angelo, 2026-11-09 22:17). The existing per-engine delay moves into the table unchanged;
   migrated rows keep their delay (Google 15 s from #166).
5. **Migration of `navigation.engines`.** Old entries (`id:on:pages[:delay]`) get priority = their position in the
   list under `FIRST_USABLE`, and priority 0 for all under `ALL_ENABLED`, so the search behaves exactly as before.
   Languages of migrated rows come from the catalog defaults. No backward compatibility for older builds.
6. **Sorting.** The stored list is held sorted by priority; rows of one priority keep their stored order, because
   that order is observable (one browser types the engines of a group one after another and the attempt budget is
   spent in that order), so loading or migrating a configuration never reorders it. The table shows that order; a
   committed priority edit or an added row re-sorts it by priority, then engine name, then insertion order, and the
   table is stored as shown. Session snapshots move to schema 7 with an explicit v6 → v7 step.
7. **"Enough usable results"** (PR C): a group is enough when it delivered at least *N* distinct result URLs that
   survive the existing filters (engine hosts, non-source hosts, blacklist). *N* is a setting
   (`navigation.minimumUsableResults`) with a default, not a constant. An engine error, a challenge or a pause never
   counts as a successful search.
8. **Parallel within a group, never a burst.** Rows of one group run in the same round, but each browser runs its
   engines one after another (the search browser has one owner thread, the research browser one page), so at most one
   request per browser is in flight; the per-engine delay and the #161 spacing/pause still apply. Workers never search
   on their own.
9. **Fresh-install default once Startpage is supported** (PR D): Startpage priority 0, DuckDuckGo priority 1,
   Google priority 2 (15 s delay), Bing off. Startpage returns Google results with direct target URLs, so the default
   path no longer needs Google redirect hops. Existing user configurations are never overwritten.

## 4. PR plan

Each PR is independent enough to review on its own; later PRs start on their predecessor's branch and merge it in as
it changes, they do not wait for merges.

| PR | Branch | Content | Changes runtime behaviour? |
| --- | --- | --- | --- |
| **A — catalog** | `claude/project-thread-gth3vp` | `SearchEngineCatalog` (28 entries), `SearchEngineCatalogEntry`, `SearchEngineImplementationStatus`, `SearchLanguages`; this plan. Tests: 28 entries, unique stable ids, default languages, status ↔ driver consistency, language normalisation. | No |
| **B — table** | `…-engine-table` (on A) | `Entry` gains priority, languages, further languages (flag + codes); new flat encoding with migration; selection sorted by priority; duplicates allowed (first row of an engine supplies its pages/delay until PR C). `SearchEngineOrderEditor` becomes a table: Active · Engine · Priority · Delay (s) · Pages · Languages · Further languages · Remove, plus "Add engine…" from the catalog (non-searchable engines listed but disabled). Tests: migration, sorting, duplicate rows, free language codes, persistence round trip, UI fit 540–1600 px. | No (old configs migrate to the same order) |
| **C — priority groups** | `…-engine-groups` (on B and #161) | `BrowserEnginesSearchStrategy` runs groups by priority, one step per row (engine + languages), the enough-results criterion as a setting, challenge/error never counts as success; `navigation.engineAcquisitionMode` removed (priorities express it). Dedup key engine + language + region + normalised query; candidates deduplicated by canonical target URL across engines; provenance (engine, language) kept on each candidate. Tests with fake engines: fallback order, same-priority group, no duplicate queries, failure/challenge not counted, one search at a time across chats. | Yes, behind the migrated configuration (identical for migrated lists) |
| **D — Startpage** | `…-startpage` (on B) | Startpage driver (`SearchForm`: start page, search box `input#q`, pagination "next" button), Startpage hosts in `SearchEngineHosts` (research browser never opens them as sources; worker contexts refuse them with #161). Status `EXPERIMENTAL`. Live validation with the existing opt-in `CamoufoxEngineSearchLiveTest` (`ASKAI_LIVE_ENGINES=startpage`, `ASKAI_SERP_SNAPSHOT_DIR`), whose captures become the replay fixtures (like `serp/bing-java21-2026-10-31`) for a regression test (results, direct URLs, page 2, ads, challenge). After that: `SUPPORTED` and the new fresh-install default (decision 9) in a follow-up. | New engine, off by default |
| **E — Yahoo and regional engines** | one branch per engine or pair, on D (E1 Yahoo + Yahoo! JAPAN: `…-yahoo`; E2 Seznam, Yandex, Naver, Cốc Cốc: `…-regional`) | Yahoo Search and Yahoo! JAPAN first (E1: search form drivers, search hosts only — Yahoo Finance or Yahoo News stay possible sources —, Yahoo's `r.search.yahoo.com/…/RU=<target>/` wrapper decoded without a request, `EngineFamily.YAHOO`), then (E2) Seznam, Yandex, Naver, Cốc Cốc as search form drivers with search hosts only (Naver blogs, Seznam news stay possible sources). E3 Baidu (`…-baidu`): its result links are opaque `baidu.com/link?url=` wrappers, but Baidu prints the real target on each result block (`<div class="result c-container" mu="https://…">`). The page capture reads that attribute next to each link as a target hint, and the wrapper resolves offline from it, without any request to Baidu. A wrapper without the attribute stays `UNRESOLVED` and is dropped like any engine address. Only the search hosts (`www.baidu.com`, `baidu.com`, `m.baidu.com`) are engine hosts; Baidu Baike, Zhidao and Tieba stay possible sources. Same pattern as D: driver data, hosts, fixtures, status stays `EXPERIMENTAL` until validated. `SearchForm` extended where an engine needs it (language parameter, consent step, CJK input). | New engines, off by default |
| **F — languages in planning, live smoke, docs** | on C | The query planner writes each query in the row's languages (the search budget, not the number of languages, caps engine requests); `InitialSearchRequest.language` is set per row; opt-in live smoke tests (system property, never in the default CI run); user documentation. | Yes, only for rows with several languages |

## 5. Tests that must never touch a live engine in CI

All engine tests run against HTML fixtures or fake strategies. Live smoke tests are opt-in (`-Dsearch.live=true`),
run one engine at a time with the configured delay, and are never part of the default Gradle test task.
