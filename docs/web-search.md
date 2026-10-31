# Web search: engines asked like a person asks them

Issue #22. AskAI's `web_search` works like Argos' `web_search` (aresstack/argos, `SearchFormInteraction`,
`ParallelWebSearch`). No result URL (`?q=`) is ever built for a catalog engine: opened directly, the engines
answered with random, wrong result sets.

1. **Engines.** The catalog (`BrowserSearchEngineCatalog`) knows DuckDuckGo (HTML start page, Lite as its own
   fallback), Bing and Google. Each endpoint is a `SearchForm`: its regular start page, the CSS selectors of its
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
replaced. Without the parameter the engines enabled in the settings search (*Search engines* list; default
DuckDuckGo, Bing and Google, strategy `ALL_ENABLED`, all three merged). `FIRST_USABLE` types into the first
engine only and asks the next one, in a tab of its own, only when the first does not deliver.

*Max. engine attempts* (`maximumEngineAttempts`, default 4) is spent before any tab opens: only the engines it
reaches get a tab, each first start page counts as one opened endpoint, and a fallback endpoint (DuckDuckGo Lite)
only gets what is left.

The dev/test `--search-url=<template with {query}>` override stays a URL template (local test worlds) and refuses
a call that names engines.

## Tests

- `SearchTabsFixtureLiveTest`: three local start pages in a real browser (Chrome; Camoufox with
  `ASKAI_TEST_BROWSER_CHANNEL=camoufox ASKAI_LIVE_CAMOUFOX=1`): each engine gets exactly its query, every key and
  Enter trusted, no blur or visibility change, the three result requests arrive within a fraction of the typing
  time (typed side by side, not one after another).
- `CamoufoxBingSearchLiveTest` (`ASKAI_LIVE_BING=1`): the real Bing through its start page.
