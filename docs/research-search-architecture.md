# Research search: what exists today, and where SearchRun fits

Status: analysis for S3 (2026-08-23). No code changed by this document. It answers one question before any
refactoring starts: **which semantics of the current search stack must survive, and which responsibilities
are currently entangled?**

## 1. What is already there — and is good

### `SearchStrategy` is already a narrow discovery seam

`research-agent-runtime/.../search/SearchStrategy` turns *a query* into *initial URL candidates* and
explicitly nothing else ("not responsible for navigation, page analysis, link discovery, capture, source
acceptance, findings or session state"). Two implementations:

| Implementation | Path |
|---|---|
| `LegacyBrowserSearchStrategy` | browser SERP via the in-process Playwright browser (Bing/DuckDuckGo, layout repair) |
| `SingleProviderSearchStrategy` | API providers (Brave, BrightData, DataForSEO, …) |

Its result type `InitialSearchResult` is already richer than a URL list:

- `candidates` — typed `SearchResultCandidate` (title, snippet, resolved target URL, …)
- `providerHosts` — engine transit hosts that must never become sources
- `challenges` — typed CAPTCHA/consent states
- `status` — `RESULTS` / `NO_RESULTS` / `TECHNICAL_PROBLEM`

**This is the layer S3's `SearchDiscovery` should grow out of, not next to.** In particular `status` already
encodes the distinction "empty search" vs "technical failure", which must not be lost.

### The acquisition engine is proven and must be preserved

`WebSearchApplicationService` (≈1100 lines, extracted verbatim from the old `ResearchLoop`) owns everything
after discovery, and every step of it is worth keeping:

```
search → MANDATORY rerank → frontier → redirect/canonical visited handling →
provider-transit filtering → web_probe/web_read (two-step readiness) → deterministic page relevance →
source acceptance → web_links → link relevance + frontier expansion →
challenge/deferred-domain handling → budgets → cancellation → typed stop reason
```

Load-bearing details a refactor must not break:

- **Nothing is ever opened in raw engine order.** A reranker failure ends the run with a typed reason
  (`RERANKER_UNAVAILABLE`, `RERANKER_TIMEOUT`, …) and opens no page.
- **Candidates are already parked before they are visited** (`parkCandidate` → `sourceAcceptancePort.park`
  with title, snippet and rerank score). A hit therefore exists in the store *without* a page visit; the
  full text is filled only on a successful read.
- Two-step visit (`web_probe` → `web_read`) with a readiness judge (heuristic or model-backed).
- HUD pause/resume, cancellation, deferred domains, error/navigation/time budgets, `sufficientOr(...)`.

## 2. Where the responsibilities are entangled

### 2.1 Discovery is a side effect, not a result

`seedReranking(query, candidates, frontier)` writes the reranked survivors into

```java
List<String> frontier   // bare URL strings
```

At that assignment **rank, title, snippet, SERP page and candidate identity die**. Everything downstream
knows only URLs (plus a side map `expectedContentByUrl`). Consequences:

- A run "42 candidates, 0 pages visited" cannot even be expressed — there is no object to return.
- Nothing can be addressed later, so "open candidate #18 without re-running the search" (S3b) is impossible
  today; the only way back to a hit is to search again.
- The parked source is the closest thing to a candidate record, but it is a *source*, not a search result:
  it has no run id, no rank, no SERP page, and it is created only for reranker survivors.

### 2.2 Selection is implicit

Selection = "whatever the reranker returned, in its order". There is no policy seam, so `TOP_RANKED` is not
a choice, it is the only behaviour. Diversity, user selection and agent selection have no place to live.

### 2.3 SERP traversal does not exist

`INITIAL_SEARCH_RESULT_COUNT` is a single constant; one SERP fetch per query. There is no page 2, no
cursor/continuation, and therefore no `COLLECT_THEN_SELECT` over several result pages — the first result
page alone decides the map of the topic. For orientation that is precisely the wrong property.

### 2.4 Obstacle handling is hardcoded, not a policy

A challenged domain is deferred; when only challenge-bound work remains, `waitForManualChallenge(...)`
blocks and the run reports `WAITING_FOR_USER`. There is no `SKIP` and no per-purpose policy. This is exactly
the Truthahnragout finding: a consent dialog on a *scoping orientation* click asked the user to operate a
foreign website.

### 2.5 Budgets count the wrong thing for visits

`ResearchRunBudget` counts navigations, errors and time for the whole run. There is no distinction between
*attempted* and *successfully read* pages, so "read 3 pages" degrades to "try 3 URLs" as soon as anything is
skipped.

### 2.6 The entry point is all-or-nothing

`execute(Set<String> terms)` runs the entire chain and returns a `ResearchStopReason`. There is no way to
ask for discovery only, and no way to enter the pipeline *with candidates that already exist*.

## 3. Target layering

The existing engine is not replaced; it is cut at the seam where identity currently dies.

```
SearchStrategyProfile          the decisions, as one snapshot-able object
        ↓
SearchDiscovery                today's SearchStrategy + SERP traversal
        ↓
SearchRun                      PERSISTENT, addressable
        └── SearchCandidate*   candidateId, serpPage, rank, title, url, snippet, domain, status
        ↓
CandidateSelection             rerank + policy (TOP_RANKED | DIVERSE_RELEVANT | USER_SELECTED |
                               AGENT_SELECTED | HYBRID)
        ↓
PageInspection                 today's WebSearchApplicationService visit loop, driven by candidates
                               instead of bare URLs
```

`SearchStrategyProfile` must express at least:

| Decision | Values |
|---|---|
| Result acquisition | `SERP_ONLY` \| `VISIT_RESULTS` |
| SERP traversal | `maxSerpPages`, continuation policy |
| Acquisition order | `COLLECT_THEN_SELECT` \| `PROGRESSIVE` |
| Candidate selection | `TOP_RANKED` \| `DIVERSE_RELEVANT` \| `USER_SELECTED` \| `AGENT_SELECTED` \| `HYBRID` |
| Page budget | `maxVisitAttempts` **and** `targetSuccessfulVisits` (deliberately two numbers) |
| Obstacle policy | `SKIP` \| `DEFER` \| `WAIT_FOR_USER` |

With `SERP_ONLY`, `WAITING_FOR_USER` becomes structurally impossible: no result page is opened at all.

### `COLLECT_THEN_SELECT` vs `PROGRESSIVE`

```
COLLECT_THEN_SELECT              PROGRESSIVE
  SERP 1                           SERP 1
  SERP 2                             ↓ evaluate / maybe visit
  SERP 3                             ↓ enough?
    ↓ one candidate pool             ├─ yes → stop
    ↓ dedupe / diversify / rank      └─ no  → SERP 2 → …
    ↓ select N
    ↓ optionally visit
```

Orientation needs the first one, so that page 1 alone does not decide the map. Targeted fact-checking is
cheaper with the second.

## 4. The decisive first refactor step

**Make the frontier carry candidates instead of URL strings.** That single change is what unlocks everything
else: a `SearchRun` that can be returned and persisted, per-candidate status, addressability for S3b, and a
place for selection policy to plug in. `expectedContentByUrl` and `parkCandidate` then become properties of
the candidate rather than side maps.

Ownership split (proposal): the **runtime executes** searches and reports the `SearchRun`; the **host
persists** it next to the project, because the host already owns project storage and the UI needs the
candidates for a later "Vertiefen". S3b's inspect request then travels host → runtime carrying the
candidates it holds.

## 5. Out of scope for S3

Yellow scoping tags stay wired to the old path (S3c), no scoping follow-up turn, no new UI. S3 is done when
these three runs are executable programmatically and the existing productive research still works:

- **A** query → 3 SERP pages → 0 pages visited → persistent run + candidates
- **B** query → collect 3 SERP pages → select diverse 5 → visit under policy
- **C** query → progressive → stop when the page/evidence target is reached
