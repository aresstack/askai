# ADR 0001: Research agent architecture after the return to Java 21

- Status: **Proposed** (decision pending; the ACP question in section 3.5 is explicitly open)
- Date: 2026-10-30
- Authors: Miguel Angelo Fernández Iglesias (owner), Claude (analysis and draft)
- Related: `docs/research-mvp.md`, `docs/research-agent-runtime.md`, `docs/mcp-runtime.md`,
  `docs/search-performance-analysis.md`, `migration.md` ("Java 21 return")

## 1. Context

### 1.1 Platform and plugin

AskAI is a Swing platform: chat, model management, the plugin host (PF4J), a loopback MCP server, the local
model runtime and the workspace/state store. The research agent is NOT part of the platform: it is a PF4J
plugin installed as a jar with its own `lib/` (`research-agent-runtime`, `browser-playwright`, Playwright
and the GraalVM 25 driver, Gson). Plugin classes load plugin-first, which is what separates the plugin's
GraalVM 25 from the host's GraalJS 21.2. Platform decisions therefore concern model access, protocol
infrastructure and shared APIs; everything about how the agent researches lives in the plugin.

### 1.2 Status quo on `develop` (5318f9b9)

| Commit | Content | Standing |
|---|---|---|
| 01401f1f | Java 21 for the whole build, Gradle 8.14.6, CI on 21 | accepted |
| fc5cada9 | official `io.github.ollama4j:ollama4j:1.1.7` instead of the in-house Java-8 fork | accepted |
| dc7e78cd | local model runtime in-process (library `local-model-runtime`) | accepted |
| feacb5e0 | Playwright browser in-process inside the plugin (library `browser-playwright`) | accepted |
| f1cf0df1 | research agent in-process (`ResearchAgentSession`, `ContributionToolInvoker`, `InProcessResearchSessionBackend`); the ACP modules `acp-client-api`, `acp-solon-client`, `acp-demo-agent`, the adapter `AcpResearchSessionBackend` and the MCP client port (`McpToolClient`, `McpToolClientFactory`, `SolonMcpToolClientFactory`) were DELETED | **disputed**: the deletions were not covered by the mandate |
| 5318f9b9 | per-call browser timeout works in-process (hung call → generation terminated → retry once), research settings tab in English | accepted |
| working tree | browser idle keep-alive and SERP capture-retry settings (performance options 1 and 2), uncommitted, untested | pending |

The mandate for the Java 21 work was "Java 21 for everything, official ollama4j, no sidecar processes".
It covered the process cut, not the removal of interfaces the owner had built. All deleted code is intact
in git at feacb5e0.

### 1.3 What the research agent is today

"One agent" is true only at session level. Behind one session run five model roles, orchestrated by code
and by the host state machine, which is the sole transition authority (phases, approvals):

| Role | Classes | Call shape |
|---|---|---|
| conversation, scoping, outline dialogue | `ResearchTeamAgent`, `PhaseAssistantProfile` | chat with tools, text wire `#RSX1#`/`#RSC1#` |
| scope sweep | `MainModelScopeProbeGenerator`, `MainModelScopeAdviceChooser` | two structured calls |
| page readiness judge | `PageReadinessModel` | structured |
| SERP layout repair | `HttpStructuredInferenceClient` | structured |
| narration | `LlmNarrator` over the host's `AgentInferencePort` | streaming |

Everything else is deterministic code and the bulk of the plugin: the browser actor, SERP analysis and
extraction, readiness and consent handling, reranking (BM25 by default), the scope fence and the SC1 search
gates in `WebSearchApplicationService`, source acceptance, the knowledge pipeline. The reliability problems
of the past months came from the model calls on local models (repeating finished actions, inverted
narration, offers that were never made, calibration-dependent sweeps) and were fixed with policy guards
inside the tool loop: sensor lock, receipts with three truths, exact-repetition guard, schema-enforced
output. These guards are the reason a 20B local model drives the agent at all.

Model access is fragmented: ollama4j in the platform, two hand-written `HttpURLConnection` clients in the
plugin runtime (`HttpMainModelChatClient`, `HttpStructuredInferenceClient`, talking to Ollama with the
host-published inference descriptor), plus the narrow host port `AgentInferencePort`. Three Ollama client
implementations in one JVM — a leftover of the former process boundary.

Performance (measured, `docs/search-performance-analysis.md`): about 42 s until the first opened hit,
about 95 s until the summary starts; the model summary dominates; a local Ollama shares its throughput
between concurrent requests, and the embedding worker already competes with the browser for the CPU.

## 2. Decision drivers

1. The user owns every phase transition and approval (RA-P6); no agent, orchestrator or framework may
   take that over.
2. Every source acquisition goes through the one funnel with the scope gates; a second path leaks the
   fence.
3. Small local models need a deterministic skeleton, narrow roles and schema-enforced output.
4. The owner's investments stay: the ACP interface, the Gson-based code, the policy guards.
5. Limits are settings, never constants.
6. The platform's dependency surface stays small; plugin-only dependencies stay in the plugin `lib/`.
7. Decisions are made on evidence (live gates, benchmarks), not on framework marketing.

## 3. Third-party libraries

### 3.1 JSON: Gson for our own code, Jackson confined to adapters

Inventory (main sources):

| Module | Gson files | Jackson files | own `SimpleJson` files | Declared |
|---|---|---|---|---|
| `askai-app` | 4 | 1 (`RawChatRequest`) | 18 | Gson 2.10.1, Jackson 2.21.0 |
| `research-agent-ui-plugin` | 25 | 0 | 0 | Gson via `lib/` (2.14.0, Playwright's) |
| `web-search-providers` | 12 | 0 | 0 | Gson 2.13.1 |
| `mcp-marketplace` | yes | 0 | 0 | Gson 2.10.1 |
| `browser-playwright` | 0 | 2 (`CamoufoxInstaller`, `CamoufoxLaunch`) | 0 | Jackson 2.18.2 |
| `research-agent-runtime` | 0 | 0 | 0 | hand-rolled codecs (`SearchJson`, `RerankJson`) |
| Solon (MCP server, ACP SDK) | — | via `mcp-json-jackson2` | — | snack4 + Jackson, transitive |
| ollama4j | — | yes | — | Jackson 2.21, transitive |
| Playwright Java | yes | — | — | Gson, transitive |

Decision:

- **Gson is the house library** for code we write: the plugin, the search providers, the marketplace, the
  app's non-Ollama parts. Reasons: it is what the owner uses, 41 of our files already use it, and the
  plugin cannot avoid it anyway because Playwright Java depends on Gson.
- **Jackson appears only where a dependency serializes with it**, and never in a neutral port or API.
  ollama4j builds its request bodies with its own `ObjectMapper`; patching the body (`think` verbatim,
  `tool_name` on tool turns) must happen on that mapper's tree, otherwise the body is serialized twice.
  The MCP and ACP SDKs bring `mcp-json-jackson2`; we do not touch those trees.
- The two Jackson usages in `browser-playwright` (Camoufox manifest and launch file) move to Gson. This
  removes the three Jackson jars from the plugin `lib/`; Gson stays (Playwright needs it).
- `SimpleJson` stays for the app's own small config files; replacing it with Gson is an optional cleanup,
  not part of this decision.
- Gson versions are aligned through one version constant in the root build (today 2.10.1, 2.13.1 and
  2.14.0 coexist).

Rejected: Gson everywhere (would mean re-serializing ollama4j's and the SDKs' Jackson trees); Jackson
everywhere (our code base is Gson, Jackson's module/annotation surface buys nothing for config files and
wire envelopes, and Gson is mandatory through Playwright anyway).

### 3.2 Ollama: ollama4j, exactly one client, behind the host's model port

Decision: ollama4j stays the only Ollama client in the JVM. The plugin never sees Ollama, Jackson or model
names; it sees the host's model port.

Why ollama4j: the official, maintained client (1.1.7, Java 17+) covers chat, tools, thinking, streaming,
model management, pull and embeddings. We need exactly one patch, the `RawChatRequest` body patch, and a
wire test pins it. Rejected alternatives: the plugin's two hand-written clients (duplicated wire knowledge;
the hidden 30 s timeout regression came from exactly this kind of duplication), LangChain4j's
`OllamaChatModel` and Solon's `OllamaChatDialect` (both are further Ollama clients bound to their
framework's HTTP stack).

Consequence: `AgentInferencePort` grows from "one prompt in, stream out" to a full model port: chat with
tool definitions and tool-call results, structured output with a JSON schema, streaming with thinking
chunks, cancellation, and the model chosen per call from the central configuration (a role names a model
slot such as `judge` or `synthesis`; the platform resolves it). The plugin's `HttpMainModelChatClient` and
`HttpStructuredInferenceClient` are retired; their test double `MockMainModelServer` becomes a port fake.

### 3.3 MCP: the tool protocol; Solon's server; transport-neutral dispatch

MCP stays as the protocol and the tool interface. Decision details:

- `McpToolContribution`, `McpToolCall`, `McpToolResult`, `McpServerRegistry` remain the contract between
  agents and the host. A role's tool catalog is a list of contributions.
- The loopback server stays Solon's `solon-ai-mcp` 3.10.1 on `solon-boot-jdkhttp`: it embeds on the JDK
  HTTP server without a servlet container, speaks streamable HTTP, supports `tools/list_changed` and has
  carried bot control and the ChatGPT connector in production. The official MCP Java SDK 0.17.2 (already
  on the classpath through ollama4j) offers only servlet transports and Spring modules on the server side;
  for a Swing application that is the heavier choice without a functional gain.
- The MCP client port returns: `McpToolClient`, `McpToolClientFactory`, `SolonMcpToolClientFactory` and
  their tests come back from git. It is needed for external MCP servers (the marketplace activates them).
  The official SDK's `HttpClientStreamableHttpTransport` client is a drop-in behind the same neutral port
  if Solon's client ever falls short.
- How a tool call is executed is a transport question: our own roles dispatch in-process through
  `ContributionToolInvoker` (same contract, no wire); external clients use the loopback server; a future
  remote agent uses an MCP client. The gates and receipts sit in the handlers and apply to all three.

### 3.4 Agent framework: none in the platform; Solon stays infrastructure; LangChain4j by spike only

Facts checked on 2026-10-30:

| Candidate | Finding |
|---|---|
| Solon `solon-ai-agent` 3.10.1 … 4.1.1 | `SimpleAgent`, `ReActAgent`, `TeamAgent` with `HierarchicalProtocol`, `SequentialProtocol`, `A2AProtocol` (an internal hand-over strategy, not the wire protocol), `ContractNetProtocol`, `MarketBasedProtocol`, `BlackboardProtocol`, `HITLTask`; runs on Solon's `ChatModel`, a concrete class whose `ChatDialect` builds the JSON and creates Solon's `HttpUtils` — it talks to Ollama through Solon's HTTP stack, not through ollama4j |
| Solon `solon-ai-a2a` | 3.5.1 … 3.10.7, then only 4.0.0-M1; package `io.a2a_preview` (a pre-1.0 spec); documentation page is a stub from 2025-08 |
| Solon `acp-sdk` 3.10.1 | Agent Client Protocol, stdio transports on both sides, transport SPI (`AcpClientTransport`, `AcpAgentTransport`); `solon-ai-acp` 4.1.1 adds WebSocket transports, so in-memory transports are in the SDK's spirit |
| LangChain4j | `ChatModel` is an interface, so an adapter over the host model port is feasible (unlike Solon's concrete class); `langchain4j-agentic` (supervisor, parallel, A2A integration) is marked experimental by its authors; `OllamaChatModel` would be another Ollama client |
| official A2A Java SDK `org.a2aproject.sdk` | 1.4.0.Final (2026-09-28), Java 17+, spec 1.0 and 0.3; client is plain Java (`Client.builder`, `JdkA2AHttpClient`); the server side depends on Jakarta CDI and Mutiny, reference servers are Quarkus, alternatives are Jakarta EE or Spring wiring, no plain-JDK integration documented |

Decision:

- **No agent framework in the platform.** Both LangChain4j and Solon's agents own the tool-execution
  loop and the model layer. Our policy guards live inside that loop, and the owner's control model
  (approvals, phase transitions) lives above it; both would have to be re-implemented against a framework's
  extension points.
- **Solon stays as protocol infrastructure**: the MCP server runtime, and the ACP SDK for as long as the
  ACP interface exists. Java 21 removes Solon's original selection reason (Java 8 compatibility) but adds
  no reason to replace a working embedded server.
- **LangChain4j core may be evaluated inside the plugin only**, as the engine of the role loop, through a
  `ChatModel` adapter over the host model port. The evaluation is a spike (section 5, item I): one new role
  built twice. It becomes a decision only with measured evidence; it never becomes a platform dependency
  and never replaces the acquisition pipeline.

### 3.5 Agent protocols: ACP as the interoperability adapter, A2A not now (OPEN)

ACP (Agent Client Protocol) and A2A (Agent2Agent) sit on different seams. ACP is the client-to-agent
session protocol (sessions, prompts, updates, cancel; the IDE and coding-agent ecosystem). A2A is
agent-to-agent task delegation (Agent Cards, Tasks with states, Artifacts, streaming).

Proposal for ACP (the owner decides):

- The ACP modules return from feacb5e0 unchanged: `acp-client-api`, `acp-solon-client`,
  `acp-demo-agent`, the adapter `AcpResearchSessionBackend` and its test.
- Both backends live behind the same `ResearchSessionBackend` port: the in-process backend is the default
  for our own agent; the ACP backend attaches external ACP agents (they are separate processes by nature)
  and remains selectable for our own agent as a process.
- `ResearchAgentMain` returns as a thin stdio main over `ResearchAgentSession`, so the research agent can be
  offered to ACP clients.
- An in-memory ACP transport (two small classes against the SDK SPI) is added only if one uniform adapter
  for own and foreign agents is wanted.

The three questions that decide between "restore as above", "restore and make ACP the only internal
path" and "do not restore": Should AskAI host foreign ACP agents? Should the research agent be usable from
ACP clients? Is crash and memory isolation of the research agent a requirement? Only the third question
forces a process boundary.

A2A: not now. Inside one JVM with our own roles, A2A over loopback repeats the two-hop cost that the
performance analysis measured. The task model (section 4) names its states after A2A 1.0 (`submitted`,
`working`, `input-required`, `completed`, `failed`, `canceled`, `rejected`) so that a later A2A adapter is a
mapping. If a foreign agent is wanted, the official SDK's client comes first; a server side only on real
need, because of its CDI dependency.

## 4. Target architecture

```text
AskAI platform (one JVM, Java 21)
  Model port v2 (ollama4j behind it)   chat+tools, schema output, streaming+thinking, cancel, model per role slot
  MCP server registry + loopback server (Solon)   research-control, service, browser-bridge, bot-control, connector
  MCP client port (Solon client; official SDK client as alternative)   external MCP servers (marketplace)
  Plugin host (PF4J), workspace/state store, local model runtime

Research plugin (jar + lib/: research-agent-runtime, browser-playwright, Playwright/GraalVM 25, Gson)
  PROJECT (domain, the only state authority)
    phases + state machine, approvals (user-owned), project store: sources, concept, outline, document
    NEW task model: work package = id, phase, role, state (A2A names), inputs, artifacts, model slot, receipts
  ORCHESTRATION (code)
    per-phase orchestrator = deterministic workflow; Planner role PROPOSES task lists, the user approves,
    the orchestrator executes; parallel only where I/O-bound (browser vs. model); every acquisition through
    the ONE funnel (WebSearchApplicationService: scope fence, SC1 gates, source acceptance)
  ROLES (model; each = system prompt + tool subset + schema + model slot)
    conversation/scoping (TeamAgent) | scope probe generator + chooser | search planner (new)
    acquisition runner (code + readiness judge) | source verifier (new) | writer/synthesis (new) | narrator
  TOOLS
    McpToolContributions published on the loopback server AND dispatched in-process (ContributionToolInvoker)
  TRANSPORTS / ADAPTERS behind ResearchSessionBackend
    in-process backend (default) | ACP adapter (interop; proposal) | later: A2A client adapter at the boundary
  EVALUATION
    benchmark questions with expected outcomes, token and wall time per role, the existing live gates
```

Why this shape:

- **Decomposition without protocol overhead.** The failure mode "one prompt with all tools keeps working
  after the task is done" is answered by narrow roles with small contexts and explicit tasks, not by a
  wire protocol. Roles are plugin objects; the orchestrator is code the user can read and the state
  machine can gate.
- **Reliability on local models** comes from the deterministic skeleton, schema-enforced outputs and the
  existing guards. The new task model makes retries, partial results and provenance explicit instead of
  implicit in chat text.
- **Efficiency** comes from fewer and shorter model calls and from the model slot per role: a small, fast
  model for judging and classification, the large model for synthesis. Parallel roles do not make a local
  Ollama faster; they add coverage and structure, so parallelism is reserved for I/O-bound work.
- **The user keeps the hand.** The planner proposes, the user approves; the orchestrator never advances a
  phase; the browser and the funnel stay single and gated.
- **Investments stay.** Slices 1 to 4 unchanged; the in-process agent core from Slice 5 becomes the
  default backend; ACP returns as an adapter; Gson code untouched.

## 5. Consequences

Positive: one model client and one model port; roles and tasks are first-class and measurable; the
platform gains nothing it does not need; the plugin can evolve (roles, planner, verifier, writer) without
touching the platform; interoperability stays open on both seams (ACP adapter, A2A-shaped task states).

Negative: the model port v2 is a real piece of platform work and the critical path; new roles add model
calls, so the benchmark harness has to exist before the roles do; restoring ACP keeps two backends alive,
which means two test fixtures; the `ResearchBackendMode.ACP` enum constant (persisted settings value) names
the productive mode and should eventually be renamed with a migration.

## 6. Cost compared to the status quo (develop at 5318f9b9)

Kept as is: Slices 1 to 4 (Java 21, ollama4j, model runtime and browser in-process), the in-process agent
core of Slice 5 (`ResearchAgentSession`, `ContributionToolInvoker`, `InProcessResearchSessionBackend`), the
timeout and translation fix. Reverted in effect: the two deletions of Slice 5 (ACP modules, MCP client
port). New: model port v2, roles, task model, orchestrator, benchmark. Removed from the status quo: the
plugin's two Ollama HTTP clients and Jackson in the plugin `lib/`.

| # | Work item | Effort (person-days, estimate) | Depends on | Risk |
|---|---|---|---|---|
| A | restore the MCP client port (5 files + 2 tests from git), re-run `:mcp-solon-runtime:test` | 0.5 | — | low |
| B | restore the ACP modules and `AcpResearchSessionBackend` as the second backend; thin `ResearchAgentMain` stdio main over `ResearchAgentSession`; mode selection in settings | 2 | decision 3.5 | medium: two backends, two fixtures |
| B' | alternative to B: plain revert of f1cf0df1 and 5318f9b9 (process-based agent only) | 0.5 | decision 3.5 | loses the in-process path |
| C | model port v2 in the platform on ollama4j; retire the plugin's two HTTP clients; `MockMainModelServer` → port fake; live gates re-run | 4–6 | — | medium: wire details (`think`, `tool_name`, schema) move behind one port |
| D | role interface; migrate the five existing roles (prompt, tool subset, schema, model slot) | 2–3 | C | low |
| E | task model in the project store, persistence, minimal task list in the UI | 3–5 | — | medium: store format, restore |
| F | per-phase orchestrator in code; Planner role; proposal → approval → execution | 3–5 | D, E | medium: must not weaken RA-P6 |
| G | new roles: search planner (2), source verifier (2–3), writer/synthesis (3) | 7–8 | D, E, F | medium: more model calls |
| H | benchmark harness: question set, expected outcomes, token and wall time per role | 2 | C | low |
| I | LangChain4j spike: one role built twice, compared on size, controllability, reliability with gpt-oss and gemma | 1–2 (optional) | C | low |
| J | JSON cleanup: `browser-playwright` Jackson → Gson, one Gson version constant | 0.5 | — | low |
| K | finish, test and commit the pending performance patches (idle keep-alive, capture retries) | 0.5 | — | low |
| L | docs and ADR follow-up (`research-mvp.md`, `research-agent-runtime.md`, `mcp-runtime.md`) | 0.5 | all | low |

Total without the optional spike: about 25 to 34 person-days. Critical path: C → D → F → G. A, B, J and K are
independent and can go first; H should exist before G so that every new role is measured against the
single-agent baseline. If the owner answers "no" to all three ACP questions, B and B' drop out and the ACP
modules stay archived in git.

## 7. Open decisions

1. ACP: restore as adapter (B), make it the only path (B'), or archive — the three questions in 3.5.
2. Default model slots per role and which local models fill them (settings, not constants).
3. Whether `SimpleJson` in the app is replaced by Gson (optional cleanup).
4. Renaming the persisted mode value `ACP` for the productive backend (needs a settings migration).
