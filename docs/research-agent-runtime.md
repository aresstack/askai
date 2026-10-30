# The research agent (in-process)

`:research-agent-runtime` is a LIBRARY: the research agent that drives a session — the conversation
TeamAgent (greeting, scoping, outline dialog), the autonomous research loop, the search strategies, the
manual web search and the reranking. Since the return to Java 21 it runs INSIDE AskAI, in the research
plugin's classloader (the plugin bundles the jar in its `lib/`); the earlier external Java-8 agent process
and its ACP wire are gone. It is never part of the AskAI fat jar.

## Session contract

- `ResearchAgentSession(environment, research, browser, service, modelPort)` — one instance per research
  session, created by the plugin's `InProcessResearchSessionBackend`. `modelPort` is the host's `ModelPort`
  service (see "Model access" below); a host without it leaves the model roles honestly
  `MODEL_UNAVAILABLE`. The `ToolInvoker` lanes are the host's own
  tool contributions: `ContributionToolInvoker` dispatches directly to the `McpToolContribution`s of the
  research-control, service and browser-bridge endpoints — no MCP client, no HTTP. The same tools stay
  published on the host's loopback MCP server for external clients (bot control).
- `ResearchAgentEnvironment` carries what used to be the `ASKAI_*` process environment: session/project
  ids and the browser-search, reranker, search-strategy and inference configuration paths.
- `open()` performs REAL readiness: the research lane must offer `research_status`, and the call must
  succeed — a failure fails the session start atomically (no half-started agent).
- The first `prompt()` turn announces `RESEARCH_MCP_READY` (+ `BROWSER_NOT_AVAILABLE` when no browser
  lane exists — visible, non-fatal, never a silent fallback).
- The agent mirrors host state via `research_status`; it owns NO research state machine and has no way
  to switch phases. Everything it says travels as text lines through `AgentOutput` — the `#RSX1#`/`#RSC1#`
  wire the plugin's `ResearchAgentEventMapper` turns into backend events.
- `cancel()` stops the running turn at the next gate; `close()` releases the model watcher and the
  provider registry. Diagnostics go to STDERR only; logs never contain tokens.

## Model access: model port v2 and slots (ADR 0001, 3.2 and 4.5)

The runtime has no Ollama client and sees no model names. Every model call goes through the host's
`ModelPort` (`agent-model-runtime-api`, package `agent.model.port`): one call is one model turn with
messages, tool definitions, an output format (text, JSON, JSON schema), a thinking mode, a context size and
a timeout; the port streams thinking and content deltas, can be cancelled and never executes tools.
`PortMainModelChat` (package `team`) and `PortStructuredInferenceClient` (package `inference`) replace the
former HTTP clients and keep their call shapes; tests use `FakeModelPort` instead of a mock HTTP server.

Each call names a model slot, never a model. `ResearchModelSlots` defines `conversation`, `scope`, `judge`,
`layout-repair`, `orchestrator`, `planner`, `verifier` and `synthesis`. The platform resolves a slot to the
model the user assigned to it, otherwise to the main model, so an unconfigured install behaves as before.

The host implementation is `OllamaModelPort` in `askai-app` (package `modelport`) on ollama4j, published to
plugins by `AgentRuntimeServices`. Its `ModelResourceScheduler` owns the resource contract of ADR 4.5:
endpoints `ollama` and `local-runtime`, a capacity per model and endpoint (setting, default 1; never more
calls on the wire than the capacity), a GPU memory budget per endpoint, waiting work grouped by model so a
model switch is one deliberate step, residency read from `GET /api/ps` (advisory only) and `keep_alive` on
every call. All of it is a setting in `model-port.properties`, edited in the panel "Model resources"
under AI models (capacity, memory budget, model and context per slot, keep-alive, maximum wait).

## Roles (ADR 0001, 4.1)

A model role is a `ResearchRole` (package `role`): id, purpose, system prompt (or `CALLER` when the request
carries it), tool catalog (`RoleToolCatalog`, bound to what the host offers at call time, never more),
output contract (`RoleOutput`: TEXT, JSON, JSON_SCHEMA), model slot and context size (0 = the slot's
setting). Callers reach a model only through `RoleChatFactory.chatFor(role)`; productive is
`ResearchAgentSession.roleChats()`, tests and the benchmark inject their own.

`ResearchRoles` lists the five roles that existed before the ADR (`CONVERSATION`,
`SCOPE_PROBE_GENERATOR`, `SCOPE_ADVICE_CHOOSER`, `PAGE_READINESS_JUDGE`, `SERP_LAYOUT_REPAIR`); their
prompts are byte-identical to before and pinned by SHA-256 in `ResearchRolesTest`. `ResearchRoles.orchestration()`
adds the orchestrator and the worker roles below. The conversation TeamAgent runs per-phase profiles
(`PhaseAssistantProfile`): RESEARCH and DRAFT (FINALIZATION shares the DRAFT prompt) have their own
prompts and read-only tool subsets; no profile can advance a phase.

## Task model and persistence (ADR 0001, 4.4)

A work package is a `ResearchTask` (`research-domain`, package `research.domain.task`): id, phase (the
plugin's phase name as text), role, dependencies, state, inputs, artifacts (`FINDING`, `SOURCE_REFERENCE`,
`VERDICT`, `TEXT`), model slot, context size, time and token budget, retry counter and limit, receipts,
timestamps and the assigned endpoint. `TaskState` is our own enumeration (`submitted`, `working`,
`input-required`, `completed`, `failed`, `canceled`, `rejected`, names chosen to match A2A 1.0, no
dependency) with one closed transition table; an invalid transition throws
`IllegalTaskTransitionException`.

The plugin persists every task as `<project>/tasks/<taskId>.json` (`FileResearchTaskStore`, Gson, written
atomically on every change, full transition history). A file that breaks the rules is rejected and
reported, never repaired. `ResearchTaskResumption` applies the resume rules: completed tasks are never re-run,
`working` tasks are re-queued as interrupted within their retry limit, `input-required` tasks keep waiting
for the user, budgets carry over. The State tab shows a read-only task list when a run has tasks.

## The autonomous loop (36A)

Prompts starting with `research:` run the deterministic loop (`loop.ResearchLoop`):
`web_search` seed → content-driven frontier (query terms against page text/title and link text) →
`web_open` (parse `capture_id=`) → `source_accept` (Commit-37 contract, duplicates are not errors,
ALREADY_ACCEPTED never re-claims) → `finding_add` for new claims → `web_links` → follow only
content-matching links. One central budget gate (`ResearchRunBudget`: tool calls, pages, sources,
consecutive errors, wall time; defaults 30/20/8/3/10min, sufficiency = ≥3 sources on ≥2 hosts) runs
before EVERY tool call; the explicit `ResearchStopReason` (11 values) is streamed as
`RESEARCH_RUN_STOPPED: <reason> pages=… sources=… hosts=…`. `PHASE_READY: SUFFICIENT_EVIDENCE` is an
EVENT line only — the host decides what follows. Server-side write rejections ("Not allowed in the
current state") end the run as APPROVAL_REQUIRED/STATE_CHANGED, never as a crash. Cancel stops the loop
at the next gate.

`ContributionToolInvoker` is the production `ToolInvoker`; the identical class runs in unit fixtures,
against in-JVM endpoints (36A), against the live in-process Playwright browser (36C) and inside the
productive E2E (38) — there is no backend-specific code path in the loop.

## The orchestrated run (ADR 0001, 4.2 to 4.5)

With the setting "Orchestrated research" (`research.orchestrator.enabled`, default off; runtime key
`ASKAI_ORCHESTRATOR_MODE=orchestrated`) a plain research question in RESEARCH/running goes to the
orchestrator instead of the single loop. `research:` keeps running the single loop in both modes.
Package `runtime.orchestrator`:

- `ResearchOrchestrator` is a model role (`research-orchestrator`, slot `orchestrator`, the main model
  unless the user assigns one). Every decision is schema output with a per-request schema
  (`OrchestratorDecisionCodec`): the plan (work packages with role, query, brief, outline sections and
  dependencies) and the round decision (evaluation per package, gaps, contradictions, quality, then
  `continue`, `close` or `ask-user` with follow-ups). An answer that breaks the schema or the run's facts
  (unknown role, section or package, a cycle, too many packages) is sent back once with the reason
  (`ASKAI_ORCHESTRATOR_DECISION_REPAIR_ATTEMPTS`).
- `TaskExecutor` is deterministic code. It persists every package as a `ResearchTask` on the project's
  blackboard (`TaskBoard`; in the plugin `ProjectTaskBoard`: the task store plus
  `orchestrator/run.json`), dispatches ready tasks in dependency order and caps concurrency with the
  capacity the model port reports per model and endpoint, plus a separate browser capacity. Each worker
  sees only the tools of its role (`RoleScopedToolInvoker`), its tokens and time are booked on its task,
  retryable failures are retried within `ASKAI_ORCHESTRATOR_TASK_RETRY_LIMIT`, a package whose dependency
  did not complete is cancelled, and `resume` re-queues what an interrupted session left working.
- `WebResearchWorker` (role `web-researcher`) runs the same `ResearchLoop` over the same
  `WebSearchApplicationService` funnel as `research:`; there is no second acquisition path. Each accepted
  source becomes a `SOURCE_REFERENCE` artifact on its task, and the page passages that match the
  package's query become a bounded `FINDING` (`ASKAI_ORCHESTRATOR_EVIDENCE_CHARS`), the evaluation's evidence.
- `OrchestratedResearchRun` is the envelope. Nothing runs before the user approves the plan; follow-ups
  inside the approved sections and roles run without asking, anything beyond them, the round limit and the
  run budget (tokens, minutes, packages) go back to the user (`AWAITING_APPROVAL`). `ask-user` waits for
  the answer (`AWAITING_INPUT`; the next chat message is the answer). A `close` is accepted only when
  `EvidenceMeter` agrees: the run rule (minimum sources and hosts of the search-run settings), every
  outline section covered by `ASKAI_ORCHESTRATOR_MIN_SOURCES_PER_SECTION` sources and no open package.
  The orchestrator never switches a phase; approve, continue and stop are typed service commands
  (`orchestrator_approve`, `orchestrator_stop`, `orchestrator_answer`) from the State tab.

All limits are settings with defaults (`OrchestratorSettings`, plugin: Settings, Research runtime,
"Orchestrator" row). The research benchmark measures the run as subject `orchestrated-run` next to the
single-agent baseline.

### Worker roles of lane G

Besides the web researcher the orchestrator can delegate to three more worker roles. Each has its own
model slot, tool subset and per-request JSON schema; code checks every answer and sends an invalid one
back once (`ASKAI_WORKER_REPAIR_ATTEMPTS`). Workers read upstream results from the blackboard
(`WorkerContext.dependencies()` / `runTasks()`); the conventions are in `WorkerArtifacts`.

- `SearchPlannerWorker` (`search-planner`, slot `planner`, no tools): turns one aspect into at most
  `ASKAI_WORKER_PLANNER_MAX_QUERIES` distinct search queries (empty, overlong, duplicate and already
  searched queries are refused). A web research package that depends on a planner package searches
  exactly the planned queries, each as one run of the same funnel, bounded by the package's time budget.
- `SourceVerifierWorker` (`source-verifier`, slot `verifier`, tool `source_review_context`): one
  verdict per upstream source (`supports`, `partial`, `off-topic`, `unreliable`, `unreadable`), the claim
  each supporting source makes, and contradictions between two sources. `off-topic` and `unreliable`
  are rejections: the `EvidenceMeter` stops counting such a source. The verifier changes nothing in the
  project's source list.
- `WriterWorker` (`writer`, slot `synthesis`, tool `source_review_context`): drafts the package's
  sections from the unrejected upstream sources (verified ones first, at most
  `ASKAI_WORKER_WRITER_MAX_SOURCES`); every paragraph must cite known source ids, gaps are named per
  section. Drafts stay on the task (`draft:<section>`); the project document is written only in the DRAFT
  phase, by the user's transition.

Verifier and writer read the captured content of exactly their sources through `source_review_context`
with `source_ids`; nothing is fetched outside the research funnel. Each role can be switched off
(`ASKAI_WORKER_SEARCH_PLANNER`, `ASKAI_WORKER_SOURCE_VERIFIER`, `ASKAI_WORKER_WRITER`; plugin: "Worker
roles" row); the orchestrator only sees roles that have a worker. The benchmark measures each role as
its own subject (`orchestrated+search-planner`, `orchestrated+source-verifier`, `orchestrated+writer`)
next to `orchestrated-run` (all lane G roles off) and the baseline; select subjects with
`-Daskai.benchmark.subjects=...`.

## Parallel browsing for workers (BrowserUserContextPool)

Research workers of one chat browse in parallel (design: `docs/research-browser-user-contexts.md`). Terms
follow WebDriver BiDi: each search run of a worker gets its own **user context** (`browser.createUserContext`:
own cookies, local storage and sign-ins); inside it the worker opens **browsing contexts** (tabs,
`browsingContext.create` with `userContext`). The chat's own browser (the chat's own persistent Camoufox
profile, HUD, serial loop, manual search) stays the chat browser; each chat now has its own persistent
profile instead of one shared profile for the first session.

Playwright Java runs one call at a time per instance, so real parallelism comes from several **worker
browsers**: each is one Playwright instance on its own owner thread with one non-persistent browser process
hosting several user contexts, and belongs to exactly one chat. Every worker tab is served through the
session's `BrowserBridgeEndpoint`, so every page lands in the session's one `CaptureStore` and goes through
its one `SourceAcceptanceService`. Sign-in state is kept only on request, outside the project folder. The
runtime API is `runtime.browser.BrowserUserContextPool`; the plugin hands it out via
`ProductiveResearchSessionResources.getBrowserUserContexts()`. Plugin settings row "Worker browsers":
processes 3, user contexts per process 4, tabs per user context 4, per site 2, wait 120 s; separate from the
model capacity.

Wiring into the orchestrated run:

- `ProductiveResearchBackendFactory.createSession` hands the chat's pool through
  `InProcessResearchSessionBackend` to `ResearchAgentSession.setBrowserUserContexts`.
- `ResearchAgentSession.orchestratorSession()` sizes the executor's browser capacity from the pool
  (`UserContextWebAcquisition.browserSlots(pool)`); without a pool `ASKAI_ORCHESTRATOR_BROWSER_SLOTS`
  (default 1) applies and the packages share the chat browser one after another.
- `UserContextWebAcquisition` (the orchestrated run's `WebAcquisition`) leases one user context per search
  run and gives it back on success, failure, cancel and before a retry. The wait for a free user context is
  bounded by the package's remaining time and taken off it; no free user context is a retryable stop, a
  cancelled turn ends every wait at once.
- `ResearchAgentSession.buildResearchLoop(turn, browserLane, researchLane, budget)` takes the browser lane
  as a parameter: the same funnel (`WebSearchApplicationService`) runs on the worker's user context or on the
  chat browser.

The serial `research:` loop and the manual search keep the chat browser. A CAPTCHA in a worker tab cannot be
solved by the user; a user context lost during a challenge wait ends the run as retryable.

## Tests

- `ResearchLoopTest` — 7 content-fixture tests (budgets, cancel, error reset, late-write rejection,
  page-count semantics) with an injected clock, no sleeps.
- `ResearchLoopMcpIntegrationTest` — the loop over the in-process lanes against the real endpoint logic.
- `ResearchLoopPlaywrightIntegrationTest` — the unchanged loop against the in-process Playwright browser
  and a real local browser (environment-gated).
- `ResearchAgentSessionRoundTripTest` — the session boundary walk in-process (readiness, honest browser
  absence, status mirror, second turn, atomic open failure).
- `InProcessResearchSessionBackendTest` (plugin) — the adapter: lines → events, typed COMPLETED terminal,
  silence after close.
- `ProductiveResearchMvpEndToEndTest` — the full productive host wiring (see research-mvp.md).
- `WorkerUserContextExecutionTest` — the real `TaskExecutor` and `WebResearchWorker` on a pool: one user
  context per package, parallel browsing up to the pool's capacity, leases always given back, the chat
  browser as fallback without a pool.
- `ResearchRolesTest`, `ResearchRoleTest`, `RoleToolCatalogTest` — the role interface and the pinned prompts.
- `PortMainModelPassthroughTest`, `PortStructuredInferenceClientTest`, `MainModelCancelOnCloseTest` — the
  roles on the model port (with `FakeModelPort`).
- `TaskExecutorTest`, `OrchestratedResearchRunTest`, `OrchestratorDecisionCodecTest`, `OrchestratorModelTest`
  — executor, envelope and decision schema.
- `SearchPlannerWorkerTest`, `SourceVerifierWorkerTest`, `WriterWorkerTest`, `WorkerChainTest` — the lane G
  worker roles, the chain plan → search → verify → write through the real executor.
- `ToolTransportParityTest` — the same contributions in-process and over the loopback server (see
  mcp-runtime.md).
- Quality against the single-agent baseline is measured by `:research-benchmark` (see its README), not by
  these tests.
