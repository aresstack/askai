# ADR 0001: Research agent architecture after the return to Java 21

- Status: **Proposed** (decision pending; the ACP question in section 3.5 is explicitly open)
- Date: 2026-10-30, revision 2 (same day). Revision 2 records the agreed corrections: the Research
  Orchestrator as a model role with an iterative cycle, the deterministic task executor, resource scheduling
  for one GPU and for many endpoints, the strategy names, the completed task model and the revised costs.
- Authors: Miguel Angelo Fernández Iglesias (owner), Claude (analysis and draft), reviewed with GPT
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

### 1.2 Status quo on `develop`

| Commit | Content | Standing |
|---|---|---|
| 01401f1f | Java 21 for the whole build, Gradle 8.14.6, CI on 21 | accepted |
| fc5cada9 | official `io.github.ollama4j:ollama4j:1.1.7` instead of the in-house Java-8 fork | accepted |
| dc7e78cd | local model runtime in-process (library `local-model-runtime`) | accepted |
| feacb5e0 | Playwright browser in-process inside the plugin (library `browser-playwright`) | accepted |
| f1cf0df1 | research agent in-process (`ResearchAgentSession`, `ContributionToolInvoker`, `InProcessResearchSessionBackend`); the ACP modules `acp-client-api`, `acp-solon-client`, `acp-demo-agent`, the adapter `AcpResearchSessionBackend` and the MCP client port (`McpToolClient`, `McpToolClientFactory`, `SolonMcpToolClientFactory`) were DELETED | **disputed**: the deletions were not covered by the mandate |
| 5318f9b9 | per-call browser timeout works in-process (hung call → generation terminated → retry once), research settings tab in English | accepted |
| a8d220dd | browser idle keep-alive and SERP capture retries as settings (performance options 1 and 2 of the analysis) | accepted, tested (15 + 6 + 59 targeted tests green) |
| a2c6ee90 | this ADR, revision 1 | superseded by revision 2 |

The mandate for the Java 21 work was "Java 21 for everything, official ollama4j, no sidecar processes".
It covered the process cut, not the removal of interfaces the owner had built. All deleted code is intact
in git at feacb5e0.

### 1.3 What the research agent is today

"One agent" is true only at session level. Behind one session run five model roles, orchestrated by code
and by the host state machine, which is the sole transition authority (phases, approvals):

| Role | Classes | Call shape |
|---|---|---|
| conversation, scoping, outline dialogue | `ResearchTeamAgent`, `PhaseAssistantProfile` (a dedicated profile exists for SCOPING only; the other phases use the neutral fallback) | chat with tools, text wire `#RSX1#`/`#RSC1#` |
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
implementations in one JVM, a leftover of the former process boundary.

Performance (measured, `docs/search-performance-analysis.md`): about 42 s until the first opened hit,
about 95 s until the summary starts; the model summary dominates. The test machine is one RTX 5080 with
16 GB; the models in use are `gemma4:e2b` (4.6 to 7.5 GB depending on quantization, 128K context, native
tool calling and thinking modes) and `gpt-oss:20b` (about 13 GB). The two do not fit into 16 GB at the
same time.

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
8. Parallelism starts on one GPU. A loaded model serves several inference slots (`OLLAMA_NUM_PARALLEL`);
   the architecture must exploit that today and scale to several endpoints later without changing the
   orchestrator or the workers. Only capacities and resource control change.
9. The orchestrator is intelligent inside a deterministic envelope: rounds, budgets, gates and approvals
   bound its initiative; code executes its decisions.

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

Consequence: `AgentInferencePort` grows from "one prompt in, stream out" to a full model port ("model port
v2"): chat with tool definitions and tool-call results, structured output with a JSON schema, streaming with
thinking chunks, cancellation, the model chosen per call from the central configuration (a role names a
model slot such as `judge` or `synthesis`; the platform resolves it to a model on an endpoint), and the
resource scheduling of section 4.5 (endpoint pool, capacity per model and endpoint, residency). The plugin's
`HttpMainModelChatClient` and `HttpStructuredInferenceClient` are retired; their test double
`MockMainModelServer` becomes a port fake.

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
  remote agent uses an MCP client. The gates and receipts sit in the handlers and apply to all three. A
  parity test executes the same contributions once in-process and once over the loopback server and pins
  identical results.

### 3.4 Agent framework: none in the platform; Solon stays infrastructure; LangChain4j by spike only

Facts checked on 2026-10-30:

| Candidate | Finding |
|---|---|
| Solon `solon-ai-agent` 3.10.1 … 4.1.1 | `SimpleAgent`, `ReActAgent`, `TeamAgent` with `HierarchicalProtocol`, `SequentialProtocol`, `A2AProtocol` (an internal hand-over strategy, not the wire protocol), `ContractNetProtocol`, `MarketBasedProtocol`, `BlackboardProtocol`, `HITLTask`; `TeamAgent.of(ChatModel)` requires Solon's `ChatModel`, a concrete class whose `ChatDialect` builds the JSON and creates Solon's `HttpUtils`; the supervisor node runs on it; members implement the `Agent` interface (`name()`, `role()`, `call(Prompt, AgentSession)`); teams run as `solon-flow` graphs (node types START, END, ACTIVITY, EXCLUSIVE, INCLUSIVE, PARALLEL, LOOP); semantics are chat turns (`finishMarker`, `maxTurns`, session windows), no task states, artifacts, budgets or resource control |
| Solon `solon-ai-a2a` | 3.5.1 … 3.10.7, then only 4.0.0-M1; package `io.a2a_preview` (a pre-1.0 spec); documentation page is a stub from 2025-08 |
| Solon `acp-sdk` 3.10.1 | Agent Client Protocol, stdio transports on both sides, transport SPI (`AcpClientTransport`, `AcpAgentTransport`); `solon-ai-acp` 4.1.1 adds WebSocket transports, so in-memory transports are in the SDK's spirit |
| LangChain4j | `ChatModel` is an interface, so an adapter over the host model port is feasible (unlike Solon's concrete class); `langchain4j-agentic` (supervisor, parallel, A2A integration) is marked experimental by its authors; `OllamaChatModel` would be another Ollama client; its MCP client would bind our own roles to our own server over HTTP again |
| official A2A Java SDK `org.a2aproject.sdk` | 1.4.0.Final (2026-09-28), Java 17+, spec 1.0 and 0.3; client is plain Java (`Client.builder`, `JdkA2AHttpClient`); the server side depends on Jakarta CDI and Mutiny, reference servers are Quarkus, alternatives are Jakarta EE or Spring wiring, no plain-JDK integration documented |

Decision:

- **No agent framework in the platform.** Both LangChain4j and Solon's agents own the tool-execution
  loop and the model layer. Our policy guards live inside that loop, and the owner's control model
  (approvals, phase transitions) lives above it; both would have to be re-implemented against a framework's
  extension points.
- **Solon stays as protocol infrastructure**: the MCP server runtime, and the ACP SDK for as long as the
  ACP interface exists. Java 21 removes Solon's original selection reason (Java 8 compatibility) but adds
  no reason to replace a working embedded server. Solon's TeamAgent is not the execution engine; its
  cooperation protocols are used as concepts and names (section 4.3).
- **LangChain4j core may be evaluated inside the plugin only**, as the engine of the role loop, through a
  `ChatModel` adapter over the host model port. The evaluation is a spike (section 6, item I): one new role
  built twice. It becomes a decision only with measured evidence; it never becomes a platform dependency,
  never replaces the acquisition pipeline, and never takes control of model access, GPU resources or the
  research funnel.

### 3.5 Agent protocols: ACP as the interoperability adapter, A2A not now (OPEN)

ACP (Agent Client Protocol) and A2A (Agent2Agent) sit on different seams. ACP is the client-to-agent
session protocol (sessions, prompts, updates, cancel; the IDE and coding-agent ecosystem). A2A is
agent-to-agent task delegation (Agent Cards, Tasks with states, Artifacts, streaming). ACP is a separate
interoperability decision and is not mixed into the internal multi-role architecture of section 4.

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
performance analysis measured. The task states of section 4.4 are our own domain enumeration; their names
happen to match A2A 1.0, so that a later A2A adapter is a mapping, not a dependency. If a foreign agent is
wanted, the official SDK's client comes first; a server side only on real need, because of its CDI
dependency.

## 4. Target architecture

### 4.1 Layers

```text
AskAI platform (one JVM, Java 21)
  Model port v2 (ollama4j behind it)
    chat+tools, schema output, streaming+thinking, cancel, model slot per role,
    RESOURCE SCHEDULING: endpoint pool, capacity per model and endpoint, GPU memory budget, residency
  MCP server registry + loopback server (Solon)   research-control, service, browser-bridge, bot-control, connector
  MCP client port (Solon client; official SDK client as alternative)   external MCP servers (marketplace)
  Plugin host (PF4J), workspace/state store, local model runtime

Research plugin (jar + lib/: research-agent-runtime, browser-playwright, Playwright/GraalVM 25, Gson)
  PROJECT (domain, the only state authority)                                  = BLACKBOARD
    phases + state machine, approvals (user-owned), project store: sources, concept, outline, document
    TASK MODEL: work packages with dependencies, state, budgets, artifacts, receipts (section 4.4)
  RESEARCH ORCHESTRATOR (model role, iterative)                               = HIERARCHICAL
    plan → delegate → evaluate results → detect gaps and contradictions → re-plan → quality check → close
    inside the envelope: approved plan and budget, max rounds, scope gates, user approval on overrun
  TASK EXECUTOR (deterministic code)
    dependencies, permissions, budgets, retries, resume; ready tasks → resource scheduler → workers;
    every acquisition through the ONE funnel (WebSearchApplicationService: scope fence, SC1 gates, acceptance)
  WORKER ROLES (model; each = system prompt + tool subset + schema + model slot + context size)
    conversation/scoping (TeamAgent, per-phase profiles) | scope probe generator + chooser
    search planner (new) | web/literature/technical research workers (acquisition runner + readiness judge)
    source verifier (new) | writer/synthesis (new) | narrator
  TOOLS
    McpToolContributions published on the loopback server AND dispatched in-process (ContributionToolInvoker)
  TRANSPORTS / ADAPTERS behind ResearchSessionBackend
    in-process backend (default) | ACP adapter (interop; proposal, section 3.5) | later: A2A client adapter
  EVALUATION
    benchmark questions with expected outcomes, GPT's eight metrics (section 6, H), the existing live gates
```

### 4.2 Research Orchestrator and Task Executor

The Research Orchestrator is a model role, not a one-shot planner. In the research phase it analyses the
approved question and concept, decomposes them into work packages, selects and briefs workers, evaluates
their results, detects gaps and contradictions against the outline, defines follow-up packages, checks
result quality and closes the research when the sufficiency criteria hold. It is the intelligent component.

The Task Executor is deterministic code. It owns the task graph, the permissions (tool subsets per role),
the budgets, retries and resumption, and it hands ready tasks to the resource scheduler. The orchestrator
decides what should happen; the executor decides whether, when and with which resources it may happen.

The envelope that keeps a local model from looping:

- The user approves the initial plan and the budget of a research run (tasks, tokens, time; settings with
  defaults). Inside the approved plan and budget the orchestrator iterates autonomously. A re-plan that
  changes the approved scope or exceeds the budget goes back to the user.
- Max orchestrator rounds per run is a setting (the concept tool-round budget is the precedent).
- Every orchestrator decision is a structured output with a schema: task lists, evaluations, gap lists,
  verdicts. Code never interprets free text.
- Gap detection and sufficiency are partly deterministic evidence the orchestrator judges ON, not INSTEAD
  of: coverage of outline sections by accepted sources, open questions per section, the sufficiency rule of
  the run budget (today ≥3 sources on ≥2 hosts). The model's verdict never replaces these measurements.
- Phase transitions stay with the user and the host state machine; the orchestrator never advances a phase.

### 4.3 Strategies (concepts taken from Solon's TeamAgent, not its engine)

| Name | Meaning here | Default |
|---|---|---|
| HIERARCHICAL | the orchestrator delegates to worker roles and evaluates their results | yes, the research phase |
| SEQUENTIAL | a dependency chain inside a work package (for example probe → read → verify); never a global "one at a time" rule | where dependencies exist |
| BLACKBOARD | the project store as the shared workspace: sources, concept, outline, document, tasks, receipts | always |

Rejected: SWARM (contradicts user-owned control), CONTRACT_NET and MARKET_BASED (presuppose competing
resources; with one GPU there is nothing to auction), A2A hand-over (a hand-off between roles the
orchestrator controls anyway).

### 4.4 Task model

A work package in the project store carries: id, phase, role, dependencies on other tasks, state, inputs
(the brief, references to project artifacts), outputs (artifacts: findings, source references, verdicts,
text), model slot, context size, time and token budget, retry counter and retry limit, receipts of the tool
calls, timestamps and the assigned endpoint.

States are our own enumeration, defined by the research domain: `submitted`, `working`, `input-required`,
`completed`, `failed`, `canceled`, `rejected`. The names match A2A 1.0 by choice; that is a convention
noted here, not a dependency.

Persistence and resumption: tasks and their artifacts are persisted as they change. After an interruption
the executor resumes the run: `completed` tasks are never re-executed, `working` tasks are marked
interrupted and re-queued within their retry limit, `input-required` tasks wait for the user, and the
orchestrator continues from the persisted state. Budgets are accounted across the interruption.

### 4.5 Resource scheduling (one GPU today, many endpoints later)

Parallelism is a property of available model slots and resources, not of the number of GPUs. Ollama serves
several concurrent requests of one loaded model from shared weights with separate contexts in one batch
(`OLLAMA_NUM_PARALLEL`, default 1; required memory scales with parallel slots times context length). For a
small model a single decode stream is bandwidth-bound and leaves the GPU's compute largely idle, so several
streams raise the aggregate throughput. For a model that fills the VRAM there is no room for extra contexts
and little gain.

The platform's model port therefore owns a resource scheduler:

- **Endpoint pool.** Each endpoint (a local Ollama, the local runtime, later cloud endpoints) has a GPU
  memory budget and the models it can serve. Model slots of roles are mapped to endpoints.
- **Capacity per model and endpoint.** The maximum number of concurrent calls to one model on one endpoint
  is a setting (mirrors `OLLAMA_NUM_PARALLEL` there); default 1; the measured value for a machine is entered
  by the user. The scheduler never sends more concurrent calls than the capacity, so nothing waits
  invisibly in Ollama's queue.
- **Residency.** `GET /api/ps` reports the loaded models with `size`, `size_vram`, `expires_at` and
  `context_length`; it reports neither the KV-cache share nor the slot count. The request parameter
  `keep_alive` sets the regular dwell time of a model after a call; it does not prevent an unload under
  memory pressure. Ollama's eviction order is an internal, version-dependent implementation (it prefers idle
  models and short session durations, not a least-recently-used order) and is not an architecture contract.
  The scheduler is therefore advisory: it groups tasks by model, keeps the active model warm with
  `keep_alive`, and plans a model switch as a deliberate phase (for example all judgements with
  `gemma4:e2b`, then the synthesis with `gpt-oss:20b`), because the two cannot stay loaded together in 16 GB
  and every switch costs seconds.
- **Other resources are separate capacities.** Browser slots (one Camoufox generation per session today;
  parallel web workers need a generation pool or a shared queue, a later work item), reranker and embedding
  worker CPU. Parallel work that is I/O-bound (browser, HTTP) does not consume model capacity.
- **The executor runs every ready task up to the capacities.** On one GPU with capacity 1 that is
  sequential; with capacity 4 four independent workers run at once; with several endpoints the same code
  spreads over them. The orchestrator and the worker roles do not know the difference.

Measurement protocol that sets the capacity default for the test machine (RTX 5080, 16 GB, `gemma4:e2b`):
start Ollama with `OLLAMA_NUM_PARALLEL=4`, `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`; send
four identical requests with a realistic role context at once; compare tokens per second per request and in
total against a single request; read the loaded size in `ollama ps` (it includes the contexts of all slots,
not the slot count) and keep it under the VRAM. Four slots and a two- to three-fold aggregate throughput are
the hypothesis to confirm, not a given. Until measured, the default stays 1.

### 4.6 Why this shape

- **Decomposition without protocol overhead.** The failure mode "one prompt with all tools keeps working
  after the task is done" is answered by an orchestrator with narrow worker roles, small contexts and
  explicit tasks, not by a wire protocol. Roles are plugin objects; the executor is code the user can read
  and the state machine can gate.
- **Reliability on local models** comes from the deterministic skeleton, schema-enforced outputs, the
  existing guards and the envelope of section 4.2. The task model makes retries, partial results and
  provenance explicit instead of implicit in chat text.
- **Efficiency** comes from fewer and shorter model calls, from the model slot per role (a small, fast
  model for judging and classification, the large model for synthesis), and from using the slots a single
  GPU already offers. Parallelism is reserved for independent tasks and I/O-bound work and bounded by the
  measured capacities.
- **The user keeps the hand.** The orchestrator proposes and iterates inside an approved plan and budget;
  the user approves the plan, overruns and phase transitions; the browser and the funnel stay single and
  gated.
- **Investments stay.** Slices 1 to 4 unchanged; the in-process agent core from Slice 5 becomes the
  default backend; ACP returns as an adapter (pending the owner's decision); Gson code untouched.

## 5. Consequences

Positive: one model client and one model port with resource scheduling; roles, tasks and capacities are
first-class, measurable and settings-driven; the platform gains nothing it does not need; the plugin can
evolve (orchestrator, planner, verifier, writer) without touching the platform; the same code runs on one
GPU and on many endpoints; interoperability stays open on both seams (ACP adapter, A2A-shaped task states).

Negative: the model port v2 with the scheduler is a real piece of platform work and the critical path; an
iterative orchestrator adds model calls and a new failure mode (looping), which the envelope and the
benchmark must contain before new roles are added; restoring ACP keeps two backends alive, which means two
test fixtures; the `ResearchBackendMode.ACP` enum constant (persisted settings value) names the productive
mode and should eventually be renamed with a migration; the scheduler depends on Ollama behaviour that is
observable but not contractual.

## 6. Cost compared to the status quo (develop after a8d220dd)

Kept as is: Slices 1 to 4 (Java 21, ollama4j, model runtime and browser in-process), the in-process agent
core of Slice 5 (`ResearchAgentSession`, `ContributionToolInvoker`, `InProcessResearchSessionBackend`), the
timeout and translation fix, the performance patches. Reverted in effect: the two deletions of Slice 5 (ACP
modules, MCP client port). New: model port v2 with resource scheduling, roles, task model, orchestrator,
executor, benchmark. Removed from the status quo: the plugin's two Ollama HTTP clients and Jackson in the
plugin `lib/`.

| # | Work item | Effort (person-days, estimate) | Depends on | Risk |
|---|---|---|---|---|
| A | restore the MCP client port (5 files + 2 tests from git), re-run `:mcp-solon-runtime:test`; transport parity test in-process vs loopback | 1 | — | low |
| B | restore the ACP modules and `AcpResearchSessionBackend` as the second backend; thin `ResearchAgentMain` stdio main over `ResearchAgentSession`; mode selection in settings | 2 | decision 3.5 | medium: two backends, two fixtures |
| B' | alternative to B: plain revert of f1cf0df1 and 5318f9b9 (process-based agent only) | 0.5 | decision 3.5 | loses the in-process path |
| C | model port v2 in the platform on ollama4j: chat+tools, schema output, streaming+thinking, cancel, model slots, endpoint pool, capacity per model and endpoint, residency via `/api/ps` and `keep_alive`, browser-slot capacity; retire the plugin's two HTTP clients; `MockMainModelServer` → port fake; live gates re-run | 5–8 | measurement protocol 4.5 | medium: wire details (`think`, `tool_name`, schema) move behind one port |
| D | role interface (prompt, tool subset, schema, model slot, context size); migrate the five existing roles; per-phase assistant profiles | 2–3 | C | low |
| E | task model in the project store: dependencies, states, budgets, retries, artifacts, receipts; persistence and resume; minimal task list in the UI | 4–6 | — | medium: store format, resume semantics |
| F | Research Orchestrator role with the iterative cycle + Task Executor + approval envelope (plan and budget approval, max rounds, overrun back to the user) | 5–8 | D, E, H | medium: must not weaken RA-P6; looping contained by the envelope |
| G | new worker roles: search planner (2), source verifier (2–3), writer/synthesis (3) | 7–8 | D, E, F | medium: more model calls |
| H | benchmark harness: question set with expected outcomes; metrics: quality and completeness of sources, share of supported claims, contradiction and gap detection, tool-call success, tokens, wall time, stability on small models, resumption after interruption | 2 | C | low |
| I | LangChain4j spike: one worker role built twice, compared on size, controllability, reliability with `gpt-oss:20b` and `gemma4:e2b` | 1–2 (optional) | C | low |
| J | JSON cleanup: `browser-playwright` Jackson → Gson, one Gson version constant | 0.5 | — | low |
| K | performance patches (idle keep-alive, capture retries) | done (a8d220dd) | — | — |
| L | docs and ADR follow-up (`research-mvp.md`, `research-agent-runtime.md`, `mcp-runtime.md`) | 0.5 | all | low |

Total without the optional spike: about 29 to 40 person-days. Critical path: C → D → F → G, with H before F
so that every orchestrator verdict and every new role is measured against the single-agent baseline. A, J
and E are independent of C and can run in parallel with it; B waits for the owner's ACP decision. If the
owner answers "no" to all three ACP questions, B and B' drop out and the ACP modules stay archived in git.

## 7. Open decisions

1. ACP: restore as adapter (B), make it the only path (B'), or archive: the three questions in 3.5.
2. Default model slots per role and which local models fill them (settings, not constants).
3. The capacity default for the test machine after the measurement protocol of section 4.5 has run.
4. Whether `SimpleJson` in the app is replaced by Gson (optional cleanup).
5. Renaming the persisted mode value `ACP` for the productive backend (needs a settings migration).
