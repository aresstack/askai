# Research MVP — architecture, acceptance and merge gate

## The chain

```text
AskAI host (Java 21, Swing) — ONE process
  ResearchRuntimeGenerationSwitch        prepare-then-publish generations
    ProductiveResearchBackendFactory     per-session composition (atomic, rollback)
      ProductiveResearchSessionResources one owner per resource
        OO research state machine        the ONLY transition authority
        ResearchControlEndpoint          research.<session>.g<gen> on SolonMcpServerRuntime (+ in-process lane)
        BrowserBridgeEndpoint            browser.<session>.g<gen> (capture_id convention, + in-process lane)
        LazyRestartableBrowserRuntime    PlaywrightBrowserRuntime in-process: playwright4j → GraalJS driver JVM → Camoufox/Chrome/Edge
        InProcessResearchSessionBackend  ResearchSessionBackend port (pure adapter)
          ResearchAgentSession           the research agent, in-process (research-agent-runtime library)
            roles (ResearchRole)         every model call through the host's ModelPort, one slot per role
            ResearchLoop                 deterministic autonomous loop (36A, backend-agnostic)
              ContributionToolInvoker ×2 in-process lanes → bridge + research endpoints
            OrchestratedResearchRun      optional (setting, default off): orchestrator role + TaskExecutor
              workers                    web researcher (same ResearchLoop), search planner, source verifier, writer
              ProjectTaskBoard           tasks in <project>/tasks/, run record in <project>/orchestrator/run.json
  AgentRuntimeServices (host)            ModelPort = OllamaModelPort (ollama4j + resource scheduler),
                                         McpToolClientFactory = SolonMcpToolClientFactory
```

Sources persist via `CaptureStore → SourceAcceptanceService → FileResearchSourceRepository`
(+ in-memory search index, MCP-P006). PHASE_READY is an event; the host dispatches
`REQUEST_EVIDENCE_REVIEW` to enter waiting_approval.

## Runtime prerequisites (nothing is downloaded automatically)

| Requirement                          | Used for                          |
|--------------------------------------|-----------------------------------|
| Java 21 (host JRE)                   | AskAI, the agent and the browser backend (one process) |
| the research plugin (`lib/`)         | agent library + Playwright/playwright4j/GraalVM 25 jars |
| Camoufox (downloaded once) or a locally installed Chrome/Edge | the Playwright channel browser |
| the configured search engines        | `web_search` / `web_search_prepare` |
| an Ollama server (or the local model runtime) | every model role, through the host's `ModelPort` |

Temporary artifacts: `%TEMP%/playwright4j-driver` (driver package assets, extracted by playwright4j).
Shutdown order per session: agent session → research endpoint → browser bridge → browser runtime (which
ends the GraalJS driver child and the browser). All closes are idempotent.

## What is proven automatically (all green, `./gradlew build --rerun-tasks`)

- 36A: autonomous loop over real MCP endpoints (deterministic browser), full budget/stop matrix.
- 36B: real Chrome via playwright4j renders JavaScript-only content (live test, environment-gated).
- 36C: the UNCHANGED loop + invoker against the live sidecar process and the productive
  Commit-37 acceptance path.
- 38: the productive HOST wiring end to end (`ProductiveResearchMvpEndToEndTest`): generation
  publish → real agent + sidecar + Chrome + two JS hosts → SUFFICIENT_EVIDENCE, ≥3 JS captures,
  ≥2 persisted sources on 2 hosts, no findings artifact anymore (issue #32: accepted sources are
  the evidence record), exactly one PHASE_READY with
  the state unchanged until the host reacts, ordered teardown, failed switch keeps the old
  generation usable, successful switch locks it.

## Enabling the productive mode in AskAI (Commits 40+42)

Start AskAI via `./gradlew :askai-app:runWithDevPlugins` — this assembles the research runtime
distribution (`build/research-runtime/`: agent jar, sidecar jar, `lib/`) and hands its location to
the app. Then open the Research workspace → artifact tab **Runtime**:

1. Productive is simply THE mode whenever its requirements are met (a persisted `Fake` value remains a
   developer-only override for the labelled clickdummy mode).
2. There are no path fields any more: the agent and the browser run inside AskAI; only the browser
   channel (Camoufox/Chrome/Edge), headless mode and the development-only loopback policy are configured.
3. Enter a search provider URL containing `{query}` (e.g. `https://www.bing.com/search?q={query}`) —
   it is REQUIRED: autonomous research always starts with `web_search`; validation says so.
4. **Check requirements** briefly starts the in-process browser off the EDT to obtain its real readiness
   status (READY / INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED /
   BROWSER_START_FAILED).
5. **Save** persists through the typed `ResearchRuntimeSettings` model; saving an unusable productive
   configuration is rejected with the concrete problem list. The save output states EXPLICITLY that
   the running session keeps its backend — close the Research session and open a new one.
6. **Ask a normal question.** Gate-free phase transitions advance automatically; the outline approval
   gate appears as a visible approval in the chat (approve / request changes); after approval the
   next question runs the autonomous research — no `/do` ceremony and no internal prompt prefix.
   `/do <command>` remains available for explicit control (completion = exactly the live-allowed
   commands; rejections show the structured status). Free text stays a prompt — commands are never
   smuggled as chat messages.

Known remaining UX gaps (deliberately documented, not hidden): saving does not yet restart the
session for you (explicit close/reopen), and the very first prompt can rarely require a resend
(RA-P003).

## Manual acceptance checklist

Environment: Windows, Java 21 installed, Camoufox downloaded (or Chrome installed), the plugin built.

1. `./gradlew build` is green.
2. `./gradlew :askai-app:runWithDevPlugins` starts AskAI with the research plugin from a fresh generation.
3. The first browser command of a session logs `[browser] ready backend=PLAYWRIGHT_SIDECAR READY: channel=…` on the app console.
4. The settings tab's **Check requirements** reports the browser readiness line; nothing else is required.
5. Rename the Chrome folder (channel chrome, or use a machine without Chrome): the check reports `BROWSER_NOT_INSTALLED`, the session reports it honestly, AskAI stays up. Restore Chrome.
6. `web_open` on a JavaScript-rendered page returns the rendered text (not the empty static HTML).
7. `web_open` on `file:///…` and on `http://169.254.169.254/` is rejected with a readable reason.
8. `web_links` → `web_follow` → `web_back` navigate correctly; link ids reset per page.
9. Kill the sidecar: no Chromium/GraalDriverMain processes remain (task manager).
10. Start AskAI; the Research workspace opens with the deterministic FAKE backend (clickdummy) — visibly, as documented in MCP-P007.
11. Marketplace: importing an MCP server entry creates a configuration but starts NO process.
12. Run `ProductiveResearchMvpEndToEndTest` (or the programmatic wiring): the agent process appears, the sidecar appears, Chrome launches headless.
13. During the run, `research_status` reflects the host state; the offered tool set changes with the phase (tools/list_changed).
14. `source_accept` outside RESEARCH/running is rejected with "Not allowed in the current state".
15. The run stops with `RESEARCH_RUN_STOPPED: SUFFICIENT_EVIDENCE …` and exactly one `PHASE_READY` line.
16. `<projectDir>/sources` contains the persisted source records; origins cover both test hosts.
17. No findings artifact is written (issue #32) — the persisted sources are the evidence record.
18. The host command REQUEST_EVIDENCE_REVIEW moves the session to waiting_approval; the agent never did.
19. Closing the session ends agent + sidecar processes and invalidates both endpoint URLs (client calls fail).
20. A second close is a no-op (no exception, no log spam).
21. A generation switch with a broken config fails with the validation list; the active generation still creates sessions.
22. A successful switch locks the old generation (`retired`) for new sessions.
23. Search the transcript/logs of the whole run for the endpoint tokens: zero hits.

## State after ADR 0001 (work items A, C0, C, D, E0, E, F, G, H, J)

ADR 0001 (`docs/adr/0001-research-agent-architecture-after-java-21.md`) is implemented in stacked PRs
against `develop` (#11, #6, #5, #8, #7, #9, #10, #13, #14; this document is item L). What they add on top of
the chain above, as built:

- **Model port v2.** All model roles reach a model through the platform's `ModelPort`; ollama4j is the only
  Ollama client and lives in `askai-app`. Model slots per role, capacity per model and endpoint (default 1),
  GPU memory budget, residency from `/api/ps`, `keep_alive`; settings panel "Model resources".
- **Roles.** The five existing model roles are `ResearchRole`s with unchanged prompts; RESEARCH and DRAFT
  have their own read-only conversation profiles.
- **Task model.** Work packages are `ResearchTask`s with checked state transitions, persisted per project
  and resumed after an interruption; the State tab lists them.
- **Orchestrated run.** Setting "Orchestrated research" (default off): a research orchestrator plans work
  packages as schema output, the user approves plan and budget, a deterministic task executor runs them
  (dependencies, capacities, role tool subsets, budgets, retries, resume), and a close is accepted only when
  the deterministic evidence measurement agrees. `research:` keeps running the single loop. Worker roles:
  web researcher, search planner, source verifier, writer (settings row "Worker roles"). No worker and no
  orchestrator switches a phase; the writer's drafts stay on its task.
- **Benchmark.** `research-benchmark` compares the single-agent baseline with the orchestrated run and each
  worker role: `gradlew :research-benchmark:benchmark -Daskai.benchmark.models=<model>`. It needs Ollama, a
  local rerank model and a browser; the live comparison has not been run yet, so whether the orchestrated
  mode becomes the default is still open.
- **MCP client port** is back (`McpToolClient`, `McpToolClientFactory`, published by the host) and pinned
  against the in-process lane by a parity test. One Gson version constant (`ext.gsonVersion`)
  serves our own JSON code; `browser-playwright` moved from Jackson to Gson, so the plugin `lib/` carries no
  Jackson.
- **Parallel browsing** for workers (WebDriver BiDi user contexts per worker, browsing contexts as tabs,
  worker browsers per chat, settings row "Worker browsers") is PR #12, independent of the lanes and not yet
  wired into the task executor (see research-agent-runtime.md).

Not part of these items: the ACP question (ADR 3.5 stays open, work item B waits for the owner), the
LangChain4j spike (item I, optional), per-chat persistent browser profiles.

## Maturity levels (review framing)

This section was written for the MVP merge review, before the return to Java 21 and before ADR 0001; the
"NOT yet proven" list below is partly addressed by the section above (model-driven roles, orchestrated
research with semantic evaluation, task persistence and resume). Semantic research quality is measured by
the benchmark, not claimed.

Two maturity levels must be read separately; conflating them overstates the second.

### Platform MVP — fully implemented and E2E-proven

PF4J workspace, ACP client/agent runtime, both MCP endpoints (research-control + browser bridge),
Playwright browser sidecar, productive host wiring, generation lifecycle (prepare-then-publish with
rollback), runtime configuration UI, chat-command bridge to the host state machine, source
acceptance, and the productive vertical user path:

```text
AskAI UI → runtime configuration → ResearchSessionCommandPort → host state machine
→ tool republication → ACP agent → real MCP endpoints → Chromium research
→ PHASE_READY → allowed user action → state update back to the UI
```

### Research methodology — Evidence-Collection MVP

Proven today: autonomous discovery, web page access, source acceptance, evidence
threshold, approval gate. The loop is DETERMINISTIC (query-term matching) by design — it proves the
platform, not semantic research quality.

NOT yet proven (deliberately out of scope of this branch; own epics, not lifecycle hardening,
and explicitly not hidden under RA-P001/RA-P002):

- a productive model binding in the external research agent (LLM planning / query generation),
- productive scoping and outline creation,
- semantic evidence evaluation instead of deterministic term hits,
- drafting, revision and finalization,
- citation integrity on persisted source snapshots,
- one shared productive artifact/source store for agent AND UI (ResearchProjectContext),
- project restore after a restart (RA-P002 is only the lifecycle part of this).

### Next epics (separate branches, after review)

1. **Productive project context** — one `ResearchProjectContext` (state machine, artifact store,
   source repository, snapshot store, event publisher, command port) shared by agent, UI and MCP;
   collision-free source ids; persisted page snapshots; wiring of the existing file stores.
2. **Model-driven research agent** — a `ResearchLanguageModel` port (planScope, proposeOutline,
   planSearch, evaluateEvidence, createDraft, reviseFinal) with a configurable model profile; the
   deterministic loop remains as test/fallback strategy.
3. **Full phase strategies** — Scoping/Outline/EvidenceCollection/Drafting/Finalization strategies
   in the agent; the host state machine keeps owning approvals.
4. **Evidence & citation integrity** — findings carry source id, snapshot id, concrete passage,
   claim, support/contradiction, relevance, reliability; cited drafts derive only from that.

### Closing statement

> The technical research process chain is proven end-to-end, including a no-manually-typed-paths
> acceptance run through the exact factory and command facade AskAI uses: assembled runtime
> distribution, automatic defaults, a plain user question, automatic gate-free phase advancement, a
> visible outline approval, autonomous Chromium research, and host-controlled state transitions.
> Remaining product-integration gaps are listed above (session restart after save, RA-P003) and the
> research METHODOLOGY (semantic planning, evidence analysis, drafting, citation integrity,
> persistence, recovery) is the next development stage — the platform does not claim it.

## Merge gate

**NOT MERGE READY** (as mandated: no merge to `main` within this work order).

Open problem ids and why they do not block the MVP scope:

| Id       | Status        | Restriction                                                                    |
|----------|---------------|--------------------------------------------------------------------------------|
| MCP-P006 | DEFERRED/LOW  | Search index is the in-memory adapter; sources themselves are file-persisted and the index is rebuildable — Lucene is an additive adapter behind the same port. |
| MCP-P007 | RESOLVED (40) | The productive mode is selectable, validated and startable from AskAI (Runtime settings view + typed settings + strict factory switch). |
| MCP-P008 | RESOLVED (41) | Structured chat/workspace actions drive the productive host state machine through the ResearchSessionCommandPort (`/do <command>` with live-allowed completion; approve/pause/... routed; structured rejection statuses). |
| RA-P001  | WORKAROUND    | PF4J refresh quirk documented in plugin-lifecycle docs; hardened stop/unload path in place. |
| RA-P002  | WORKAROUND    | ACP runtime integration works. Restart restoration remains incomplete until RA-P002 is resolved (memento store wiring). |

Everything else on the MVP list (MCP-P001…P005) is RESOLVED or an accepted documented workaround
(MCP-P001: the Java-21 sidecar itself). Merge decision stays with the maintainer after review of
this branch; the technical precondition list above is complete and honest.
