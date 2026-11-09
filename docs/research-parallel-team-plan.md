# Parallel research team for the Sources phase: orchestrator, page workers, one search service

Status: architecture and PR plan, 2026-11-09, on `develop` 1d0b734 (after PR #153, #155 and #156). No code changed by
this document. It extends `docs/research-book-mvp-plan.md` (D5: "Phase 2 is a deterministic plan from the frozen
concept, run through the one funnel") and ADR 0001 (orchestrator, task executor, resource scheduling). The MVP
acceptance run has priority; nothing here changes the productive path until the flag of section 6 is switched on.

## 0. Summary

Today the Sources phase searches with ONE tab and ONE model turn at a time: `SearchPlanQueue` hands one planned
query at a time to the manual search port, and `WebSearchApplicationService` opens the hits one after another in the
chat browser. The GPU is idle most of that time (the 2026-10-30 benchmark saturates `gemma4:e2b` only at 16 to 24
concurrent requests), and the browser waits on one page at a time.

The target is a team: a **deterministic orchestrator** (Java) that owns the plan, the coverage and the task queue
and asks a model only for judgement; a **central search service** that is the only thing that ever talks to a search
engine; and **N page workers** (small model + one isolated browser user context each) that each inspect one page.
Workers talk in natural language through exactly two operations, `send_message` and `finish_task`; routing,
persistence, identity and permissions are Java's job. Sites are protected by a **site lease gate**: a bounded wait
per site, a deadline on every holder, and a waiter that always gets its turn (no deadlock, no unbounded wait, no
burst on ResearchGate).

Almost everything needed already exists and is extended, not replaced (section 1). The new code is: a site lease
gate with deadlines (replacing the counter in `SiteConcurrencyGate`), a search-service facade with query and URL
dedup and engine backoff, an agent mailbox with the two tools, a page worker on the existing `TaskExecutor`, a
feedback channel from workers into `SearchPlanCycles`, and a capacity governor over the existing model and browser
limits. Seven PRs (section 5); three of them (T1 site leases, T2 search service, T3 messaging) are independent
foundations that can start now without touching the MVP path.

Guideline, as given: intelligent agents, the simplest possible model communication, reliable deterministic Java.

## 1. What exists today, and what becomes of it

Checked in the code on `develop` c283dc2.

| Component | Where | Finding | Verdict |
|---|---|---|---|
| `SearchPlanQueue`, `SearchPlanCycles`, `SearchPlanCoverage`, `SearchPlanStore`, `SearchPlanEntry` | plugin `research.plan` | The host-side plan of D5: entries per concept node, priority entries, coverage per node (`SourceEligibility.isEvidence`), research cycles with a `Replanner` (PR #153), honest `Outcome`, `search-plan/outcome.md`. **Runs one entry at a time** through `SearchPlanQueue.SearchPort` → `ManualWebSearchPort`; "two acquisitions never overlap" (`Busy`, `deferUntilEntryEnds`). Entries are persisted as tasks in `FileResearchTaskStore`. | **Extend.** The queue keeps plan, coverage, cycles, stop/run and the outcome. Its `SearchPort` gets a second implementation that hands the query to the search service and the hits to the page task queue instead of running the whole funnel (section 2.3). A second input feeds the replanner: worker feedback (section 2.6). |
| `QueryPlanner`, `BackendQueryPlanner`, `ConceptQueryPlan` | runtime `orchestrator`, plugin `plan`, domain `plan` | Prompt and strict reader for query variants per node; the cycle request already carries `queriesRun`, `blacklist`, `foundDomains` per node and the `cycle`. | **Extend** the request with worker findings (gaps, contradictions, unproductive angles, suggested queries) and one prompt block. No second planner. |
| `WebSearchApplicationService` | runtime `acquire` | The one funnel (≈1,500 lines): search → mandatory rerank → frontier of URL strings → park candidates → `web_probe`/`web_read` → page relevance → `source_accept` → `web_links` → budgets → typed stop reason. Serial per run; the caller's browser lane decides where it runs. | **Keep** for the serial loop, the manual search and `research:`. The page visit (probe, read, readability, consent, acceptance, link listing) is **extracted** into a reusable `PageInspection` that both the funnel and the page worker call (the cut `docs/research-search-architecture.md` section 4 asked for: "make the frontier carry candidates"). The funnel's behaviour does not change. |
| `SearchStrategy`, `InitialSearchResult`, `BrowserEnginesSearchStrategy`, `GoogleSearchBrowserStrategy`, `SingleProviderSearchStrategy`, `ResearchSearchStrategyFactory` | runtime `search` | Query → candidates with `providerHosts`, `challenges`, `status`. Google runs in the **search browser**; DuckDuckGo/Bing still run as a step in the research browser (`LegacyBrowserSearchStrategy`); API providers (Brave, Bright Data, DataForSEO, SerpApi) over Serp4J. | **Reuse unchanged** as the engine behind the search service. |
| `SearchBrowser`, `ResearchRuntimeShared` | browser-playwright, runtime | ONE headless search browser per AskAI JVM, owner thread, searches serialised, restarts itself. Separate from the research browser. | **This is the central search executor already.** The search service (T2) adds what is missing around it: query dedup, result dedup, spacing and backoff, one gate for all chats, and the rule that no worker context ever reaches an engine host. |
| `BrowserUserContextPool`, `PlaywrightUserContextPool`, `WorkerBrowserLane`, `WorkerUserContext`, `WorkerBrowsingContext`, `WorkerBrowserLimits`, `SharedWorkerBrowserPool`, `ChatBrowserUserContexts` | runtime `browser`, browser-playwright, plugin `host` | Implemented and tested (`docs/research-browser-user-contexts.md`): worker browsers (processes) × user contexts × tabs, one pool over all chats, per-chat scopes, hung-call termination, idle stop, saved sign-in state per key, every page of a worker lands in the session's one `CaptureStore`. Settings "Worker browsers": processes 3, user contexts/process 4, tabs/user context 4, per site 2, wait 120 s. | **Reuse unchanged.** 20 workers = 5 processes × 4 user contexts; that is a settings change, not code. |
| `SiteConcurrencyGate` | browser-playwright | A counter per site key (host without `www.`, all engines share `search-engines`), limit over all workers and chats. `enter` waits up to the **call timeout (300 s)** and then throws; `wait()`/`notifyAll()` without FIFO order; no deadline on a holder; a holder that hangs keeps its place until its lane is terminated. | **Replace the inside, keep the seam** (T1): leases with deadlines, FIFO waiters, bounded wait, per-site overrides, site health (cool-down after CAPTCHA/consent/HTTP 429). `WorkerBrowsingContext.call` keeps calling `enter`/`leave`. |
| `TaskExecutor`, `ExecutorCapacity`, `TaskBoard`, `ProjectTaskBoard`, `FileResearchTaskStore`, `ResearchTask`, `TaskState`, `ResearchTaskResumption`, `TaskWorker`, `WorkerContext`, `WorkerResult` | runtime `orchestrator`, plugin `store`, domain `task` | A deterministic executor: persisted tasks with a closed transition table, dependencies, per-resource semaphores (`model:<endpoint>/<model>` from the port's capacity, `browser`), retries, cancel, `resume` of interrupted work, receipts and artifacts per task. Proven with parallel user contexts (`WorkerUserContextExecutionTest`). | **Reuse as the worker pool engine.** The page worker is one more `TaskWorker`; page tasks are `ResearchTask`s with role `page-worker` in the Sources phase, next to the plan entries that already live in the same store. |
| `ResearchOrchestrator`, `OrchestratedResearchRun`, `OrchestratorDecisionCodec`, `RoundDecision`, `WebResearchWorker`, `SearchPlannerWorker`, `SourceVerifierWorker`, `WriterWorker` | runtime `orchestrator` | The opt-in orchestrated mode (off by default): a model writes the plan and every round decision as **schema JSON**; `WebResearchWorker` runs one whole search run per package. The MVP plan hides this mode and removes it in PR 37 (Q5); its planner and writer logic were extracted (PR 13, PR 28). | **Not reused.** A per-round JSON decision schema is the complexity `gemma4:e2b` must not carry; the book pipeline's plan is deterministic (D5). The team orchestrator is Java with one natural-language review role (section 2.5). `WebResearchWorker` stays for the benchmark baseline. |
| `SourceAcceptanceService`, `CaptureStore`, `SourceEligibility`, `NonSourceHosts`, `ErrorPageSignals`, scope fence / blacklist, `ResearchSourceRecord` (`searchRequestId`, `planEntryId`, `phaseTag`) | plugin `capture`, `sources`, `scope`; domain `scope` | One acceptance per session, atomic and idempotent, dedup by canonical URL + content hash; the live test with parallel workers makes 3 sources of 4 visits. Sources carry the request and plan entry that found them. | **Reuse unchanged.** Page tasks carry the plan entry id, so coverage per node keeps working. |
| `ModelPort`, `ModelResourceScheduler`, `ModelPortSettings`, `ModelCapacity`, `ResourceSnapshot` | askai-app `modelport`, agent-model-runtime-api | Admission control per model and endpoint: **capacity default 1** per model (setting "Model resources"), grouping by model, GPU budget, `keep_alive`. `ExecutorCapacity.fromPort` turns the slot's capacity into the executor's model semaphore. | **Reuse.** The governor (T6) reads the page-worker slot's capacity from the snapshot; the user raises the capacity of `gemma4:e2b` in the existing panel. No second scheduler. Ollama's own `OLLAMA_NUM_PARALLEL` (8 on the target machine) is a server start setting AskAI cannot change (open question 1). |
| `docs/ollama-parallel-inference-benchmark-2026-10-30.md` | docs | `gemma4:e2b`: 680 tok/s at 8, 906 at 16, ≈1,000 to 1,030 at 20 to 32 concurrent requests (plateau 16 to 24; latency 10 s at 20). `llama3.1:8b`: best at 12 (758 tok/s), regression at 16. `gpt-oss:20b`: only 2 slots help. | **Used as the sizing basis** of the governor's defaults and of the benchmark design (section 7). |
| Sources assistant (`ResearchTeamAgent`, `PhaseAssistantProfile` RESEARCH, tools `request_research`, `search_plan_overview`, `source_overview`, `run_command`) | runtime `team`, plugin `mcp` | The user's conversation partner in `research/running` (PR 15, #133). It adds plan entries; it never switches a phase (Q13). | **Unchanged.** The orchestrator reports through the plan checklist and the same chat lines the queue uses today; `request_research` keeps adding entries. |
| `McpToolContribution`, `ContributionToolInvoker`, `RoleScopedToolInvoker`, `ResearchRole`, `RoleToolCatalog` | mcp-runtime-api, runtime `loop`, `role` | Tools are flat contributions dispatched in-process; a role sees only its catalog; the same contributions stay on the loopback MCP server. | **Reuse.** `send_message` and `finish_task` are two contributions on the research-control lane, in the page-worker role's catalog (section 2.4). |
| `TeamAgentTurnParser` | runtime `team` | Tolerant parser for small models: extracts the first balanced JSON object out of prose or fences, one bounded repair, then an honest error. | **Pattern reused** for the worker's answer (section 2.4). |
| Solon AI `TeamAgent` (`solon-ai-agent`, latest 4.1.1 on Maven Central) | not a dependency; only `solon-ai-mcp` 3.10.1 is used | Re-checked against ADR 0001 §3.4 (2026-10-30): `TeamAgent` needs Solon's concrete `ChatModel` (its own Ollama HTTP client, outside our `ModelPort` and `ModelResourceScheduler`); members are chat turns in a `solon-flow` graph; no task states, budgets, persistence or resource control; protocols (`HierarchicalProtocol`, `BlackboardProtocol`, …) are concepts we already use by name. | **Not used.** The messaging we need is two tools, a persisted mailbox and a router, about the size of one plugin class each; a framework would add a second model client and a second execution loop. Decision of ADR 0001 §3.4 stands. |

The repository `aresstack/ollama-manager` is a separate program; AskAI only links to its download. Its benchmark
results are the document above.

## 2. Target architecture

### 2.1 Picture

```text
Sources phase of one chat (research/running)
│
├── SearchPlanQueue (exists) ── plan, coverage, cycles, outcome, stop/run
│       │  entry.query                                  ▲ worker feedback (gaps, suggested queries, …)
│       ▼                                               │
├── SearchService (T2, one per AskAI) ──────────────────┼── the ONLY path to a search engine
│       │  SearchStrategy (exists) → SearchBrowser / Serp4J providers (exist)
│       │  query dedup · result dedup (UrlRegistry) · goto-redirect batches · engine spacing + backoff · one gate for all chats
│       ▼  candidates (reranked, scope-fenced, NonSourceHosts-filtered)
├── PageTaskQueue (T4) ── ResearchTask role=page-worker, one per URL, claims URL in UrlRegistry
│       ▼
├── TaskExecutor (exists) ── runs ready page tasks up to TeamCapacity (T6)
│       │                     resources: model:<endpoint>/<model> (ModelPort capacity) + browser (pool capacity)
│       ▼
├── PageWorker × N (T4) ── each: BrowserUserContextLease (exists) → SiteLeaseGate (T1) → PageInspection (extracted
│       │                   from WebSearchApplicationService) → source_accept/park (exists) → one model turn on the
│       │                   page-worker slot → finish_task / send_message (T3)
│       ▼
├── AgentMailbox (T3) ── persisted, async, bounded; routes worker ↔ orchestrator ↔ worker
│       ▼
└── TeamCoordinator (T5, Java) ── reads finished tasks and messages, updates coverage, feeds SearchPlanCycles,
        asks the gap-review role (natural language) at review points, enqueues follow-up pages and queries,
        ends the plan with SearchPlanQueue.Outcome (never "covered" for a stop)

Every page any worker reads → the session's ONE CaptureStore → ONE SourceAcceptanceService (exist)
```

### 2.2 Roles and responsibilities

**Team coordinator (orchestrator, Java).** Owns the research goal (the frozen concept and mission of Phase 1), the
plan and the coverage (all through `SearchPlanQueue` and `SearchPlanCoverage`), the page task queue and the
mailbox. It decides deterministically what the code can decide: which hits become page tasks (rank, dedup, scope,
host rules), how many follow-up links a worker may queue, when a node is covered, when the plan is out of budget,
and when to stop. It asks a model only for what needs judgement: "given these worker reports, what is still
missing for node X and which queries would find it?" (the gap-review role, section 2.5). It never ends the
research because a number of pages was visited; it ends with a typed `Outcome` reason (COVERED, BUDGET, PHASE_CAP,
PLANNER_UNAVAILABLE, plus STOPPED).

**Page worker (model + isolated browser).** Gets one URL, the node and the query it came from, a two-line brief
("find statements that support or contradict …; the book is about …") and its inbox. Java does the browsing: lease a
user context, take a site lease, probe and read the page through `PageInspection`, run the page relevance gate,
**park** the capture through the session's acceptance port (`SourceAcceptancePort.park`, exists: a parked record
with title, excerpt and score, not yet a source), list the links. Then the model gets the brief, the page text
(bounded by the slot's context) and the link list, and answers in plain text: what the page says that matters,
whether it is useful (and why not), which of the listed links (at most k) are worth a look, and any message to the
orchestrator or another worker. Only now does Java decide about the source: `USEFUL: yes` or `partly` → `accept`
(the one idempotent path, knowledge processing starts here); `USEFUL: no` → the capture stays parked, the URL is
recorded as rejected in the registry with the worker's reason, and nothing counts for coverage. The serial funnel
keeps accepting at its own point; only the team path moves acceptance behind the worker's answer. Java then turns
the answer into artifacts (`FINDING`, `SOURCE_REFERENCE`), follow-up page tasks and mailbox messages. The worker has **no search tool and cannot reach an engine host** (section 2.3).

**Search service (Java, one per AskAI).** Takes a query with its plan entry and language, runs it through the
configured `SearchStrategy` (engines in the search browser, or an API provider), and returns deduplicated,
reranked candidates. It owns the global engine limits: one engine request at a time (the `search-engines` key
today), a minimum spacing between requests, exponential backoff and a cool-down after a challenge, over all chats.
It refuses a query it has already run for the same project (normalised: case, whitespace, stop words), and strips
URLs the project has already visited or rejected (`UrlRegistry`).

### 2.3 Why no worker can bypass the search service

Three independent guards, each sufficient on its own:

1. **Tool catalog.** The `page-worker` role's `RoleToolCatalog` contains no `web_search*`, `web_open` with a free
   URL, or `search_*` tool. Its only browser input is the task's URL and the links Java listed from the page;
   `RoleScopedToolInvoker` refuses everything else (exists).
2. **URL policy of the worker context.** `PlaywrightBrowserSession` already applies its `UrlSafetyPolicy` twice per
   navigation (before and after redirects). T2 adds the engine deny list (`SearchEngineHosts` in `browser-api`, PR #156, plus the `providerHosts` the
   strategy reports) for worker user contexts: a navigation to an engine host fails typed (`ENGINE_HOST_REFUSED`),
   is recorded on the task, and never leaves the process. The chat browser and the search browser are not affected.
3. **Lease identity.** The search browser and the API providers are reachable only through `SearchService`, which
   is not on any worker's lane.

PR #156 (merged as `develop` 1d0b734) is the basis T2 builds on: `SearchEngineHosts`; the research browser never opens
google/bing/duckduckgo; Google `goto` wrappers are resolved lazily in the search browser (`SearchBrowser.resolveRedirect`
on its owner thread, port `OpaqueRedirectResolver`, adapter `SearchBrowserRedirectResolver`, set through
`ResearchLoop.setRedirectResolver`); `WebSearchApplicationService` records `engineRedirectBlocked` per search; a node is
covered only once no planned query of it is pending, and the per-query cap is `sourcesPerNode`.

**The search browser is a serial resource, and every `goto` hop is one more call on it.** With twenty workers, the
redirect resolution of their candidates must not queue behind the next engine query or starve it. T2 therefore makes
redirect resolution part of the search service: hits are resolved in one batch right after the SERP, with
`SearchBrowser.resolveRedirect` calls that are cheap (a request without following the redirect) and counted in the
service's spacing; a page task is created with the **resolved** URL, so no worker ever asks the search browser for
anything. Queries and redirect batches share the one owner thread in FIFO order; the service reports the search
browser's queue length so the benchmark (T7) shows whether it becomes the bottleneck.

### 2.4 Communication: two operations, natural language, Java does the rest

Operations available to a worker (and, as a tool-less call, to the coordinator):

| Operation | Meaning | Java fills in |
|---|---|---|
| `send_message(recipient, message)` | A free-text message to `orchestrator` or to `worker <n>` (the number the worker sees in its inbox). Never blocks, never waits for an answer. | sender, task id, run id, timestamp, delivery, persistence, permission (a worker may address the orchestrator and live workers of the same run; unknown recipients are answered with a one-line refusal in the next inbox), per-task cap and TTL. |
| `finish_task(summary)` | Ends the own task with a free-text summary. | artifacts recorded so far, state transition on the task, release of browser lease and site lease, wake-up of the coordinator. |

Everything else a worker "says" is its structured answer to the one page turn, in **labelled plain text**, not
JSON:

```text
SUMMARY: <2–5 sentences: what the page states that matters for the node>
USEFUL: yes | partly | no — <reason>
FOLLOW: <link number>, <link number>      (optional, at most k from the numbered list)
TO ORCHESTRATOR: <message>                 (optional)
TO WORKER 4: <message>                     (optional)
```

Reasons: the small model has to fill in labelled lines, not build nested JSON; the parser is the tolerant pattern of
`TeamAgentTurnParser` (first matching labels, one bounded repair when `SUMMARY` is missing, then an honest
`failed` result); the labels **are** `send_message`/`finish_task` for a model that is bad at tool calls, while a
model that calls tools well can call them directly. Both paths end in the same Java code. Prompts are English;
the model may write the summary in the book's language (the Phase-1 record).

Delivery is asynchronous. A message to a worker is appended to that worker's inbox and shown at its **next** model
turn as a block `MESSAGES FOR YOU` (bounded: the last m messages, each cut to a length); a worker that has no further
turn (its task is ending) never sees it, and the message is redirected to the coordinator with a note. A message to
the coordinator is queued for the next review point. Nobody waits for an answer inside a turn; a worker that needs
one ends its task with the open question in its summary, and the coordinator may enqueue a follow-up task with the
answer in the brief. That rule alone makes blocking communication cycles impossible.

Persistence: `tasks/<taskId>.json` (exists) for tasks and artifacts; `team/<runId>/messages.jsonl` (new, append-only,
UTF-8 helper) for every message with sender, recipient, task ids, time and delivery state. A restart replays the
undelivered messages into the inboxes of the re-queued tasks.

What a worker never has to know: which worker is free, how its user context is named, which Ollama slot it holds,
what a task state is. Addressing is by the display number of the run; Java maps it to the task.

### 2.5 The orchestrator's model use: one review role, natural language

The coordinator calls a model at **review points** only: when a cycle's entries are all searched and their pages are
all finished or failed, when the mailbox holds messages for the orchestrator and no review ran in the last interval,
and when the user asks ("What is still missing?"). The role `research-gap-reviewer` runs on the planner slot
(`phase.research.planner`, default `gemma4:e2b`, thinking off) and gets: the uncovered nodes with their coverage
lines (exists in `Outcome.report`), the worker summaries of the cycle (bounded), the messages addressed to the
orchestrator, the queries already run and the unproductive angles. It answers in labelled text:

```text
NODE <label>: MISSING <what is still missing> | QUERIES <query>; <query>
NODE <label>: COVERED
NOTE: <anything the user should read>
```

Java validates every query the way `QueryPlanner.read` does today (length, duplicates, blacklist terms, already
run), writes the accepted ones as `CYCLE` entries with the worker-feedback origin (section 2.6), and posts `NOTE`
lines to the chat as the queue posts the outcome today. No JSON schema, no state transitions in the prompt, no
answer longer than a few hundred tokens.

### 2.6 Dynamic planning while workers run

`SearchPlanQueue` today waits until no pending entry is left, then asks the `Replanner` once per cycle. The team
adds a second, continuous feed and a new origin `WORKER` on `SearchPlanEntry`:

- **Worker feedback → entries without waiting for the cycle end.** A `TO ORCHESTRATOR` message that the review role
  turns into queries, a `FOLLOW` link that the coordinator decides to search rather than open (a link to a search
  page or a listing), and a "this node needs scientific sources, not vendor pages" note all become pending entries
  of the running cycle with origin `WORKER` and the node they belong to. They run as soon as the search service is
  free; the queue keeps its priority order (PRIORITY, then WORKER, then plan order).
- **The cycle request learns.** `ConceptQueryPlan.Request` gets three lists: `workerGaps` (per node), `contradictions`
  (source pairs with the claim), `unproductiveQueries` (queries whose pages were all `USEFUL: no` or rejected). The
  prompt gets one block `WHAT THE WORKERS FOUND`. `SearchPlanCycles.nextCycleRequest` fills it from the task store.
- **Repeated strategies are recognised.** The merger refuses a query that normalises to one already run (exists for
  plan merges) and a query whose first three content words equal those of an unproductive query of the same node;
  the reviewer gets those as `UNPRODUCTIVE`. A node with two unproductive cycles is reported as "needs another
  kind of source" in the outcome instead of being searched a third time the same way.
- **Coverage stays the deterministic rule.** `SearchPlanCoverage` decides covered/uncovered from evidence sources and
  domains; a worker's `USEFUL: yes` is not coverage, an accepted source is, and a page the worker rejected is never
  accepted (section 2.2), so it cannot count. The reviewer's `COVERED` line is a hint
  that is logged, never applied.

### 2.7 Site coordination: leases with deadlines, never a deadlock

Requirement, as stated by the owner: several workers must not hit one site at once (ResearchGate blocks faster than
Google); a waiting worker must not wait forever; after a timeout the holder that appears blocked loses its access
and the waiter gets its turn.

`SiteLeaseGate` (T1, inside the seam of `SiteConcurrencyGate`):

- **Key**: the registrable site (`PublicSuffixDomainKeyResolver`, so `www.researchgate.net` and `researchgate.net`
  are one site; subdomains of one registrable domain count as one by default). Engines keep the one key
  `search-engines`, which only the search service uses after T2.
- **Permits per site**: default from the existing setting (2), with **per-site overrides** in a small list setting
  (`research.browser.sites.overrides`, e.g. `researchgate.net=1@10s`, `*.sciencedirect.com=1@5s`: one at a time with a
  minimum spacing of 10 s between navigations). The list ships with a short default for known strict sites and is
  editable in the "Worker browsers" row.
- **Lease**: `enter` returns a lease with a **hold deadline** (default 90 s). A holder past its deadline is
  **expired**: its next browser call on that site fails typed (`SITE_LEASE_EXPIRED`); the page worker records the
  obstacle and ends its attempt `retryable` with a site backoff, so another worker (or the same one later) visits the
  URL. Expiry never kills the browser process; it only takes the place away.
- **An operation in flight is never overlapped.** `WorkerBrowsingContext.call` holds the gate around the whole lane
  call, so a permit is handed on only once the holder's **current operation has returned** (or its lane was
  terminated by the call timeout), never while it runs. To keep that bounded, worker tabs get their own
  **navigation timeout** (`context.setDefaultNavigationTimeout`, already set per worker context in
  `PlaywrightWorkerBrowserBackend`; new setting, default 45 s) that is at most the hold deadline, so no single
  operation on a site can outlive the hold deadline by more than that. The call timeout (300 s) stays the bound for a
  hung lane, as today.
- **Waiting**: FIFO per site, bounded by the smaller of the task's remaining time and the acquire setting. A waiter
  whose wait exceeds the **wait deadline** (default 60 s) marks the oldest holder expired and gets its place as soon
  as that holder's running operation has ended (worst case: wait deadline + navigation timeout). A waiter whose
  remaining task time is shorter than that worst case fails typed (`SITE_BUSY`) and its task is re-queued with a
  site backoff instead of taking an occupied permit. So the second worker always comes to its turn or ends typed,
  the first one is told, in a typed way, that it lost the site, and two operations on `researchgate.net=1` never
  overlap.
- **Site health**: a CAPTCHA, a consent wall that the guard cannot dismiss, HTTP 429/503 or a `temporarily
  unavailable` page (the `ErrorPageSignals` of PR #153) puts the site into **cool-down** for all workers and chats
  (exponential, 30 s to 10 min); pending page tasks of that site stay queued and are dispatched after the cool-down,
  never lost. The funnel's deferred-domain handling is unchanged for the chat browser.
- **Observability**: the gate exposes holders, waiters and cool-downs per site for the technical log and the Sources
  tab (T7).

### 2.8 Capacity: separate limits, one governor, all chats

Resources and where their limit lives:

| Resource | Limit | Owner |
|---|---|---|
| Concurrent model calls per model and endpoint | `ModelPortSettings` capacity (default 1; raise for `gemma4:e2b`) | `ModelResourceScheduler` (exists) |
| Worker browser processes, user contexts, tabs | "Worker browsers" settings | `PlaywrightUserContextPool` (exists) |
| Operations on one site | per-site permits and overrides | `SiteLeaseGate` (T1) |
| Search engine requests | one at a time, spacing, backoff | `SearchService` (T2) |
| Page workers running at once | `research.team.maxWorkers` (default 8, see question 3) | `TeamCapacity` (T6) |

Today `TaskExecutor.runReady` acquires every resource of a task before the attempt starts and releases it only when
the attempt ends, and `ExecutorCapacity.fromPort` gives every task a model permit; a page task that browses for a
minute would hold a model place the whole time, so 8 model places would mean 8 page workers. T6 therefore lets a
worker declare its admission resources (`TaskWorker.resources()`, default = today's model + browser): the page
worker declares **`team-worker` and `browser` only, no model permit**. Its one model call is admitted by
`ModelResourceScheduler` (the real admission control: capacity per model and endpoint, grouping, GPU budget), where
it queues while the browser work of the other workers goes on. Page-task admission and model-call admission are
thus separate; nothing is ever sent to Ollama beyond the model's capacity.

`TeamCapacity` computes the `team-worker` count at every dispatch:
`min(maxWorkers setting, pool capacity (processes × user contexts), model capacity of the page-worker slot ×
workersPerModelSlot)`, where `workersPerModelSlot` (default 2) reflects that a page worker spends most of its time
in the browser, not in the model. The count is **global over all chats** (one plugin-wide semaphore like
`SharedWorkerBrowserPool`), so two research chats share 8 workers instead of starting 16. Tasks beyond the capacity
wait in the queue; nothing is dropped. Settings changes apply at the next dispatch.

Target configuration for the RTX 5080 (16 GB) as measured: `gemma4:e2b` with 16 to 20 concurrent requests needs
`OLLAMA_NUM_PARALLEL` ≥ 16 **on the server**, which is the owner's setting (today 8; open question 1). Until it is
raised, the governor's honest maximum is 8 model places × 2 = 16 workers (16 browsing, at most 8 model calls in
flight), which the browser pool (3 × 4 = 12 user contexts by default) caps at 12; 20 workers need the pool at 5 × 4
and the model at ≥ 10 places.

### 2.9 Robustness and resumption

| Requirement | How |
|---|---|
| Persisted tasks and messages | `FileResearchTaskStore` (exists, atomic writes, full transition history) and `team/<runId>/messages.jsonl` (T3). |
| Resume after a restart | `ResearchTaskResumption` re-queues `working` tasks within their retry limit (exists); the plan's resume pass (PR #153) makes interrupted entries pending; T7 adds the replay of undelivered messages and of URL claims. |
| No double processing of a source | `UrlRegistry` (T2): canonical URL → {claimed by task, visited, accepted source id, rejected reason}; a page task claims before it visits, a failed task releases its claim; `SourceAcceptanceService` stays idempotent as the last guard. |
| Idempotent result merge | Acceptance by canonical URL + hash (exists); coverage computed from the store, never from in-memory counters. |
| Bounded retries | `ResearchTask.retryLimit` (exists); site backoff counts as a retry with a delay; a URL that fails twice is recorded `UNREADABLE` and never queued again. |
| Stuck workers | Task time budget (exists); a browser call past the call timeout terminates only that worker browser (exists); the site lease expiry (T1) frees the site; the coordinator marks a task whose `WorkerContext.progress` has been silent for the task's time budget as interrupted and re-queues it. |
| Pause and stop | `TaskExecutor.cancelOpen` moves tasks to `CANCELED`, which is terminal, so it is **not** the pause. T7 adds `TaskExecutor.suspendOpen`: running attempts are interrupted (leases close within milliseconds, see `WorkerUserContextExecutionTest`), each open page task goes `WORKING → SUBMITTED` through a new `ResearchTask.suspend` (the table already allows the transition; unlike `requeue` it counts no retry), and the task keeps its URL claim. "Stop search plan" (exists) cancels the running search, suspends the page tasks and stops the queue; "Run search plan" dispatches the submitted tasks again. A phase change or a run abort uses `cancelOpen` as today and releases the claims of the cancelled tasks. |
| No blocking cycles | Messages never block; no worker waits for a reply (2.4); the coordinator's review is a bounded model call with a timeout and `PLANNER_UNAVAILABLE` as the typed failure. |
| Deterministic storage | One `CaptureStore` and one acceptance per session (exists); tasks keyed by id; messages keyed by sequence number; the outcome report lists every node, every unproductive query and every refused site. |

## 3. What the small model sees (one page turn)

```text
You inspect ONE web page for a book. Book: <title>. Chapter node: <label> (<path>). Query that found the page: <query>.
Decide what the page states that matters for this node. Do not invent content that is not on the page.

PAGE <url> (<title>, <n> characters):
<text, bounded to the slot's context minus the frame>

LINKS (numbered):
1. <text> — <url>
2. …

MESSAGES FOR YOU:
- orchestrator: For this node we need studies, not vendor pages.

Answer with these lines only:
SUMMARY: …
USEFUL: yes | partly | no — reason
FOLLOW: numbers of links worth opening (at most 2), or none
TO ORCHESTRATOR: a message, or omit the line
TO WORKER <n>: a message, or omit the line
```

Budget: one model call per page, answer ≤ 400 tokens, thinking off, context per the slot's setting (`gemma4:e2b`
32,768 by PR 03); a page longer than the window is cut at passage boundaries with the parts that match the query
first (the `SectionContextBuilder` ordering of PR 27). The page relevance gate (exists, deterministic) runs before
the model call, so a page that does not match the node never costs a model turn.

## 4. Layering and module placement

| Layer | Module | New or changed |
|---|---|---|
| Domain (Java 8, no AskAI dependency) | `research-domain` | `ConceptQueryPlan.Request` fields (gaps, contradictions, unproductive queries); `SearchPlanEntry.Origin.WORKER` lives in the plugin's plan package as today. |
| Runtime library | `research-agent-runtime` | `acquire.PageInspection` (extracted), `search.SearchService` (facade + `UrlRegistry` port), `team.mailbox.*` (mailbox, router, message codec, two tools), `orchestrator.PageWorker`, `orchestrator.TeamCapacity`, role `page-worker`, role `research-gap-reviewer`, labelled-text parsers. |
| Browser backend | `browser-playwright` | `SiteLeaseGate` replaces the inside of `SiteConcurrencyGate`; engine deny list in the worker session's URL policy; site overrides in `WorkerBrowserLimits`. |
| Plugin (host) | `research-agent-ui-plugin` | `plan.TeamSearchPort` (the queue's second `SearchPort`), `plan.PageTaskQueue`, `plan.TeamCoordinator`, file `UrlRegistry` and mailbox store under the project folder, settings rows, checklist and Sources-tab view, wiring in `ResearchAgentSession.wireSearchPlan` behind the flag. |

Dependency direction stays: the domain knows no provider; the runtime knows ports; the plugin implements them;
Playwright stays in `browser-playwright`. SRP: the coordinator decides, the executor runs, the gate protects sites,
the service protects engines, the mailbox routes; none of them does the others' job. The ACP interface is not
touched. UI strings English.

## 5. PR plan

| PR | Title | Depends on | Size | Starts |
|---|---|---|---|---|
| T1 | Site leases with deadlines, per-site overrides and site health | – | M | now |
| T2 | Search service: one engine path, query and URL dedup, redirect batches, spacing and backoff, engine deny list for worker contexts | PR #156 (merged) | M | now |
| T3 | Agent mailbox: `send_message`, `finish_task`, labelled-text answers, persistence | – | M | now |
| T4 | Page worker and page task queue on the task executor; `PageInspection` extracted from the funnel | T1, T2, T3 | L | after MVP acceptance is stable |
| T5 | Team coordinator: plan queue feeds the search service, worker feedback feeds the cycles, gap-review role, outcome | T4 | L | after T4 |
| T6 | Capacity governor and settings: team worker count over all chats, model-slot-aware limits, "Research team" settings row | T1 (interfaces), T4 | M | can start after T1 |
| T7 | Recovery, stop/pause, stuck detection, acceptance tests 2/4/8/20, benchmark subject, Sources-tab team view | T5, T6 | L | last |

Chain: T1/T3 → T4 → T5 → T7, with T2 and T6 joining. T1, T2, T3 and T6 change nothing the MVP run uses (the gate
keeps its seam and its default behaviour for limit 2 without overrides; the service wraps the strategies; the
mailbox and the governor are new classes); they are merged behind no flag. T4 and T5 are wired into
`ResearchAgentSession` behind `research.team.enabled` (default off) and switched on for the live benchmark of T7
only. Each PR comes with its tests and is merged on green CI with a regular merge commit.

**T1 Site leases with deadlines, per-site overrides and site health** (M)
- Change: `SiteLeaseGate` behind the `enter`/`leave` seam of `SiteConcurrencyGate` (lease object with hold deadline,
  FIFO waiters, wait deadline, oldest-holder expiry, typed `SITE_LEASE_EXPIRED`, per-site permits and spacing from a
  parsed override list, cool-down registry fed by `WorkerBrowsingContext` on challenge/consent/429/`ErrorPageSignals`).
  `WorkerBrowserLimits` gets `siteOverrides`, `holdDeadlineMillis`, `waitDeadlineMillis`; `ResearchRuntimeSettings`
  reads them; the "Worker browsers" row gets the override field (fit test 540 to 1600 px).
- Done when: two leases on `researchgate.net=1` never overlap and keep 10 s spacing, also when the holder's operation
  is still running at the waiter's deadline (the permit moves only after that operation returned); a holder past its
  deadline fails typed on its next call and the waiter proceeds within wait deadline + navigation timeout, or ends
  typed when its task has less time than that; a cool-down parks the site for everyone and
  releases; limit 2 without overrides behaves exactly as today (existing pool tests green); no unbounded wait exists
  (test with a hung holder and an injected clock).

**T2 Search service** (M)
- Change: `SearchService` in the runtime (`search(SearchRequest) → SearchOutcome` over the configured
  `SearchStrategy`, followed by one redirect batch through `OpaqueRedirectResolver` so every candidate carries its
  resolved URL and `engineRedirectBlocked` ends the batch typed; normalised query dedup per project; `UrlRegistry` port with a file implementation in the plugin
  (`search-plan/urls.jsonl`); engine spacing and backoff over all chats using the `search-engines` key; `providerHosts`
  and `SearchEngineHosts` (browser-api) as the engine deny list applied to worker user contexts by
  `PlaywrightBrowserSession`'s `UrlSafetyPolicy`). The manual search and the funnel keep their path; only the registry is
  written by both, so a page the serial loop visited is not queued again by the team.
- Done when: the same query twice yields one engine request; a URL visited by the chat browser is not a candidate for
  the team; a worker context's navigation to any engine host fails typed and is logged; two chats searching at once
  never overlap on the engine; a challenge on the engine backs off exponentially and the queue reports it as the
  reason of the pause, not as "no results".

**T3 Agent mailbox** (M)
- Change: `AgentMailbox` (append-only store, per-recipient inboxes, delivery state, caps and TTL), `MessageRouter`
  (display numbers ↔ task ids, permissions, redirect of undeliverable messages to the orchestrator), the two tool
  contributions on the research-control lane, `WorkerAnswerParser` for the labelled lines (tolerant, one repair,
  honest failure), the `MESSAGES FOR YOU` block builder (bounded), plugin store under `team/<runId>/messages.jsonl`.
- Done when: a worker's message reaches the orchestrator's queue and another worker's next turn; a message to a
  finished worker is redirected with a note; caps and TTL hold; a restart replays undelivered messages; the parser
  turns the sample answers of section 3 (and three malformed variants) into the expected messages or a typed failure;
  nothing in the mailbox ever blocks (tests with a never-reading recipient).

**T4 Page worker and page task queue** (L)
- Change: extract `PageInspection` (probe, readiness, read, relevance gate, accept/park, links) from
  `WebSearchApplicationService` and call it from the funnel unchanged (byte-identical behaviour: the existing funnel
  tests stay green); `PageWorker implements TaskWorker` (needsBrowser, lease per task, site lease per navigation,
  one page turn on the `page-worker` role, artifacts, follow-up tasks ≤ k with depth 1 by default, messages);
  `PageTaskQueue` (plan entry → claimed page tasks with node and entry id, phase-tagged, dedup through the registry);
  `WorkerContext` gets the inbox and the display number.
- Done when: `TaskExecutor` runs 2, 4 and 8 page tasks in parallel on a fake pool and `FakeModelPort`, every lease
  and site lease is given back, the chat browser is never used, every accepted source carries the plan entry id, a
  `USEFUL: no` page stays parked, is recorded as rejected and never counts for coverage, `FOLLOW` yields at most k
  follow-up tasks on the same registry, a crash in one
  worker leaves the others running, and the funnel's own tests are unchanged.

**T5 Team coordinator** (L)
- Change: `TeamSearchPort` as the queue's second `SearchPort` (search through the service, hits to the page task
  queue, the entry is "done" when its page tasks are finished, sources counted through `acceptedBy` as today);
  `TeamCoordinator` (review points, gap-review role, worker-feedback entries with origin `WORKER`,
  `ConceptQueryPlan.Request` extension and the prompt block in `QueryPlanner`, unproductive-angle detection in
  `SearchPlanMerger`, outcome lines for refused sites and unproductive queries); `ResearchAgentSession.wireSearchPlan`
  chooses the port by `research.team.enabled`.
- Done when: a worker's `TO ORCHESTRATOR` note becomes a `WORKER` entry that runs before the cycle ends; the cycle
  request carries the workers' gaps; a node with only vendor pages is reported uncovered with "needs another kind of
  source"; the outcome never says COVERED for a stop; with the flag off the queue behaves exactly as on `develop`
  (`SearchPlanQueueTest` unchanged).

**T6 Capacity governor and settings** (M)
- Change: `TaskWorker.resources()` with `ExecutorCapacity` honouring it (the page worker holds `team-worker` and
  `browser`, no model permit; model calls are admitted by `ModelResourceScheduler`), `TeamCapacity` (effective worker
  count from setting, pool capacity, model capacity of the page-worker slot and `workersPerModelSlot`; one plugin-wide
  semaphore over all chats; live settings), settings row "Research team"
  (max workers, workers per model slot, follow-link limit, hold and wait deadlines; the per-site overrides stay in
  "Worker browsers"), a note in the Model resources panel that the capacity of the worker's model bounds the team,
  `docs/ollama-model-budget-measurements.md` gains the sizing table of section 2.8.
- Done when: two chats share the configured worker count; a raised model capacity raises the effective count at the
  next dispatch; a lowered pool capacity lowers it; with model capacity 8 and 16 workers, 16 tasks browse while the
  `FakeModelPort` never sees more than 8 calls in flight; tasks beyond the capacity wait and none is lost (test with
  20 tasks, capacity 4, then 8).

**T7 Recovery, control and benchmark** (L)
- Change: restart replay (tasks, claims, messages), resumable pause through `SearchPlanQueue.stop` and the new
  `TaskExecutor.suspendOpen` / `ResearchTask.suspend` (no retry counted, claims kept), abort through `cancelOpen`
  (claims released), stuck detection on silent progress, Sources-tab team view (workers, their page, site
  waits and cool-downs, the mailbox of the run), `research-benchmark` subject `parallel-team` with the metric
  **useful pages per minute** (pages whose source was accepted and whose summary passed the relevance gate) next to
  the baseline, run at 1, 2, 4, 8 and 20 workers; `docs/research-parallel-team-plan.md` gets the measured table.
- Done when: the acceptance list of section 7 is green in tests, and the live benchmark on the owner's machine is
  recorded with throughput, GPU memory (`ollama ps`), browser processes and refused sites per configuration.

## 6. Integration without disturbing the MVP

- T1, T2, T3 and T6 touch no line the serial Sources run executes differently; T1 keeps the default behaviour of
  `SiteConcurrencyGate` for limit 2 without overrides. They can be merged while the M2 to M4 live tests run.
- T4 and T5 are wired behind `research.team.enabled` (default off). The flag is switched on for the live benchmark
  only, after the MVP acceptance run has passed, and becomes the default only with a recorded benchmark that shows
  more useful pages per minute than the serial run at the same quality (the `BookIntegrityCheck` of PR 32 on the
  resulting book).
- The repair thread's search-browser fix is merged (PR #156, `develop` 1d0b734); T2 starts from it. Conflicts with that
  thread's further work are settled directly; `ResearchAgentSession.wireSearchPlan` and `WebSearchApplicationService`
  are the two shared hotspots.
- Rebase rule from the MVP plan: dependent PRs start on their predecessor's branch and merge it continuously; after the
  predecessor's merge they are transferred cleanly onto `develop`.

## 7. Acceptance criteria and how each is shown

| Criterion | Where it is shown |
|---|---|
| One orchestrator coordinates 2, 4, 8 and then up to 20 workers | T4 executor tests (2/4/8 on fakes); T7 live benchmark 1/2/4/8/20 on the owner's machine (pool 5 × 4, model capacity per question 1) |
| All workers analyse different pages in parallel | T4: distinct URLs per task from the registry; overlap measured in the test (max in flight = capacity) |
| No worker bypasses the search service | T2: tool catalog, engine deny list and lease identity tests; T7 live: the search browser's request log vs. worker contexts' navigation log |
| A worker asks the orchestrator and receives an answer | T3/T5: `TO ORCHESTRATOR` → review → follow-up task with the answer in the brief; test asserts the brief |
| Two workers exchange information | T3: `TO WORKER 4` appears in worker 4's next turn; redirected if 4 has finished |
| A worker proposes a research question and the orchestrator starts a search from it | T5: `WORKER` entry runs before the cycle end; test asserts the engine request |
| Duplicate sources and queries are recognised | T2 registry and query dedup; acceptance dedup (exists) |
| The orchestrator does not end for a page count | T5: `Outcome` reasons only; `SearchPlanQueueTest` extended |
| Restarts lose no task | T7: kill between claim and visit, between visit and accept, between accept and finish; every case resumes with one visit per URL and one source |
| Single worker vs. team: throughput and load | T7 benchmark: useful pages per minute, tokens, GPU memory, browser processes, refused sites; the table lands in this document |

## 8. Risks

- **Small-model answers.** `gemma4:e2b` may ignore the labels or write prose. Mitigation: deterministic relevance gate
  before the call, tolerant parser with one repair, a `USEFUL`-less answer counts as `partly` with the summary kept,
  and the benchmark measures the parse-failure rate per model.
- **Message noise.** Twenty workers can flood the orchestrator. Mitigation: per-task caps, TTL, review points instead
  of reactions per message, and a worker-to-worker tool that is on only with the flag (question 5).
- **Site bans despite the gate.** A site may fingerprint parallel contexts of one IP. Mitigation: overrides with
  spacing, cool-downs, and the registry so a banned site is not retried blindly; the live benchmark records refused
  sites per site.
- **GPU memory.** 20 Gemma slots at 32k context do not fit next to a second loaded model. Mitigation: the governor
  reads the port's capacity; the sizing table names the combinations; the owner's Ollama settings are not changed by
  code.
- **Two hotspots.** `ResearchAgentSession` and `WebSearchApplicationService` are touched by the repair thread and by
  T4/T5. Mitigation: T4's extraction is behaviour-preserving and starts from PR #156; T5's wiring is one branch
  in `wireSearchPlan`.
- **The search browser as the serial bottleneck.** Queries and `goto` resolutions share one owner thread. Mitigation:
  redirect batches in the service (section 2.3), spacing that counts both, the queue length in the benchmark; if it
  binds, the next step is a second search browser identity, not a worker that talks to an engine.

## 9. Decisions for the owner

1. **Ollama server parallelism.** The measured plateau for `gemma4:e2b` is 16 to 24 concurrent requests;
   `OLLAMA_NUM_PARALLEL` is 8 today. Recommendation: raise to 16 for the T7 benchmark (with `MAX_LOADED_MODELS` 2 the
   KV memory grows; the benchmark records `ollama ps`), and set the `gemma4:e2b` capacity in Model resources to the
   same value. Nothing is changed without the owner's word.
2. **Model slot of the page worker.** Recommendation: a new slot `phase.research.worker` that falls back to the
   model of `phase.research` (so an unconfigured install uses `gemma4:e2b`), rather than sharing `phase.research`,
   so the Sources assistant can later run on another model than twenty workers.
3. **Default worker count.** Recommendation: 8 as the shipped default (fits NUM_PARALLEL 8 × 2 and the browser pool
   3 × 4), 20 as the benchmark target after question 1.
4. **Initial per-site overrides.** Recommendation: `researchgate.net=1@10s`, `academia.edu=1@10s`,
   `sciencedirect.com=1@5s`, `springer.com=2@3s`, `wiley.com=2@3s`; everything else the general limit 2.
5. **Worker-to-worker messages in the first version.** Recommendation: build the mailbox for it (T3), expose the
   `TO WORKER <n>` line to workers only when `research.team.workerMessaging` is on; measure the noise in T7 first.
6. **Follow-up links.** Recommendation: depth 1, at most 2 links per page, same registrable site or a direct document
   link (PDF), never a search or listing page; everything else goes to the orchestrator as a suggested query.

Until decided, the PRs follow these recommendations.

## 10. Relation to existing documents

- `docs/research-book-mvp-plan.md` D5 stays the rule for Phase 2; this plan parallelises its run and keeps its
  plan, coverage, cycles and outcome. PR 37's removal of the orchestrated mode is unaffected: nothing here uses
  `ResearchOrchestrator` or `OrchestratedResearchRun`.
- `docs/research-browser-user-contexts.md` describes the pool this plan runs on; section 6 of that document (limits)
  gains the site overrides and deadlines of T1.
- `docs/research-search-architecture.md` section 4 named the cut (frontier of candidates, `PageInspection` driven by
  candidates); T4 makes it.
- `docs/adr/0001-research-agent-architecture-after-java-21.md` §3.4 (no agent framework) and §4.5 (resource
  scheduling) are confirmed; §4.3 gains the page worker as a HIERARCHICAL member with BLACKBOARD communication through
  the task store and the mailbox.
- `docs/ollama-parallel-inference-benchmark-2026-10-30.md` is the sizing basis; T7 adds the team benchmark next to it.
