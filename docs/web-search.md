# Web search: engines asked like a person asks them

Issue #22. AskAI's `web_search` works like Argos' `web_search` (aresstack/argos, `SearchFormInteraction`,
`ParallelWebSearch`). No result URL (`?q=`) is ever built for a catalog engine: opened directly, the engines
answered with random, wrong result sets.

1. **Engines.** The driver catalog (`BrowserSearchEngineCatalog`) knows DuckDuckGo (HTML start page, Lite as its
   own fallback), Bing, Google, Startpage, Yahoo, Yahoo! JAPAN, Seznam, Yandex, Naver, Cốc Cốc and Baidu. Each endpoint is a `SearchForm`: its regular start page, the CSS selectors of its
   search field (alternatives for layout changes, e.g. Bing `#sb_form_q`, `textarea[name=q]`, `input[name=q]`) and
   the selectors of its own "next result page" control (Bing `a.sb_pagN`, Google `#pnnext`, DuckDuckGo's
   "Next" button).
2. **All engines at once.** Every engine of the search opens its start page in a background tab of its own; no
   tab is brought to the front. The query is then typed into all fields at the same time: the steps of every tab
   (pointer path into the field, click, a check that the field has the focus, select all and Backspace, every
   key, Enter) are planned with the human input policy and played as one timeline merged by time, so the keys of
   one tab fall into the pauses of the others. All of it is real input over WebDriver BiDi to each tab's
   browsing context; Shift is held for capitals, umlauts are real keys (playwright4j#59). Nothing is filled,
   submitted or dispatched by script.
3. **Evaluation.** Each tab's result page is read and extracted as before (consent, challenge check, capture,
   organic results), in the configured engine order. A field covered by a consent banner gets one dismissal
   and one more try. A start page whose field cannot be used ends the endpoint as `SEARCH_BOX_UNAVAILABLE`,
   never with a result URL instead. A challenged engine's tab stays open for the user.
4. **Deeper result pages** come from a real click on the engine's own "next" control, never from an offset URL.
5. **Afterwards** the search tabs are closed and the session is back on its own page.

## Which engines

`web_search` takes an optional `engines` parameter (comma-separated catalog ids, in this order, each once). A
named engine need not be enabled in the settings, but it must exist: a wrong id is refused, never corrected or
replaced. Without the parameter the table under *Settings → Search → Engines* decides.

### The catalog

`SearchEngineCatalog` lists 28 engines with country, data source, default languages and an implementation status
(see `docs/search-engine-catalog-plan.md`):

| Status | Engines | Meaning |
|---|---|---|
| `SUPPORTED` | Google, DuckDuckGo | Driver validated against the live engine. |
| `EXPERIMENTAL` | Bing, Startpage, Yahoo, Yahoo! JAPAN, Seznam, Yandex, Naver, Cốc Cốc, Baidu | Search form driver from the engine's public page structure, not validated live yet. |
| `NOT_IMPLEMENTED` | the others (Brave, Ecosia, Mojeek, Qwant, Sogou, Daum, …) | Listed so nothing disappears silently; they can be added to the table but do not search. |

### The engine table

One row per engine search: **Active**, **Engine**, **Priority**, **Delay (s)**, **Pages**, **Languages**,
**Further languages** (a switch plus free codes). The same engine may appear in several rows. "Add engine…" offers
the whole catalog; engines without a driver are shown but cannot be picked.

- **Priority groups.** Priority 0 runs first. The rows of one priority form a group and are searched in the same
  round: Google in the search browser and the group's other engines in the research browser, at the same time,
  each browser one request after another. The next group runs only when the groups so far have delivered fewer
  usable results than *Enough results* (`navigation.minimumUsableResults`, default 5). A failed, challenged or
  empty step counts nothing. Results are merged in group order; a URL already found is not added twice.
- **Delay** is kept between every request to that engine (each result page, each search, each redirect hop);
  Google's default is 15 s.
- **Languages.** A row's languages pick the language of its search: the search's own language when the row lists
  it, otherwise the row's first language; a row without languages keeps the search's language. Google takes it as
  the search browser's search language, so a second Google row with other languages searches again in that
  language. Rows that end in the same engine and language search once. Page engines are typed into their search
  form, which takes no language, so a page engine runs once per search.
- **One search at a time.** Every chat, the concept phase and every worker go through this one search tool;
  a search waits until the running one has finished.

A fresh installation searches with Google (priority 0, 15 s delay) and DuckDuckGo behind it; Bing stays in the
table but off, because DuckDuckGo's results come from Bing. Older engine lists migrate to the same order (the old
position becomes the priority, Google keeps 15 s).

### Result links

Result links that are engine redirect wrappers are resolved before any classification: Bing `/ck/a?u=a1…`, Google
`/url?q=…`, DuckDuckGo `/l/?uddg=…` and Yahoo `r.search.yahoo.com/…/RU=<target>/…` carry their target and are
replaced by it. Startpage links point straight at the target page.

Google's current result links, `/goto?url=<token>`, do not carry their target: the token is opaque and only Google
knows it. They are kept unresolved during the search and resolved one at a time, in the search browser, only when
the hit is actually opened; the research browser never opens an engine address. Asking Google for every target
up front was tried live and led to a block.

Baidu's links, `baidu.com/link?url=<token>`, are opaque too, but Baidu prints the real target on each result block
(the `mu` attribute). The page capture reads it as the link's target hint and the wrapper resolves offline from
it, with no request to Baidu; a link without it is dropped.

Only an engine's search hosts count as engine hosts: Yahoo Finance, Naver blogs, Seznam news or Baidu Baike stay
possible sources.

The dev/test `--search-url=<template with {query}>` override stays a URL template (local test worlds) and refuses
a call that names engines.

## Tests

- `SearchTabsFixtureLiveTest`: three local start pages in a real browser (Chrome; Camoufox with
  `ASKAI_TEST_BROWSER_CHANNEL=camoufox ASKAI_LIVE_CAMOUFOX=1`): each engine gets exactly its query, every key and
  Enter trusted, no blur or visibility change, the three result requests arrive within a fraction of the typing
  time (typed side by side, not one after another).
- `CamoufoxBingSearchLiveTest` (`ASKAI_LIVE_BING=1`): the real Bing through its start page.
- `CamoufoxEngineSearchLiveTest` (`ASKAI_LIVE_ENGINES=<ids>` or `ASKAI_LIVE_ENGINES=experimental`, optional
  `ASKAI_LIVE_QUERY`): the named engines, or every `EXPERIMENTAL` engine with a driver, through the product path
  in real Camoufox, one engine after another with its own attempt line and item count. Never part of the default
  CI run; it sends real requests from the machine's IP, so run it deliberately and not next to another search.
  With `ASKAI_SERP_SNAPSHOT_DIR` each result page is saved for an offline fixture.
