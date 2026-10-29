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
