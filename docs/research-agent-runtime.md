# The research agent (in-process)

`:research-agent-runtime` is a LIBRARY: the research agent that drives a session — the conversation
TeamAgent (greeting, scoping, outline dialog), the autonomous research loop, the search strategies, the
manual web search and the reranking. Since the return to Java 21 it runs INSIDE AskAI, in the research
plugin's classloader (the plugin bundles the jar in its `lib/`); the earlier external Java-8 agent process
and its ACP wire are gone. It is never part of the AskAI fat jar.

## Session contract

- `ResearchAgentSession(environment, research, browser, service)` — one instance per research session,
  created by the plugin's `InProcessResearchSessionBackend`. The `ToolInvoker` lanes are the host's own
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
