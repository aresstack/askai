# Migration — standalone research workspace → chat agent

This documents the migration performed across Commits 10–19 on `feature/pf4j-research-clickdummy`.

## Product model change

Before: selecting Questing → Research swapped the whole center to a **standalone research workspace** with
its own chat/activity column, composer, outline tree and toolbar.

After: AskAI stays one chat. The Research Agent **extends** the shared chat with slash commands, agent
activity (in the normal transcript), artifacts, specialized artifact views and a state visualization. The
chat component and composer are physically the same across Yapping↔Questing.

## Removed UI (Commit 17)

- `ResearchWorkspaceInstance` (the full shell) and its views: toolbar, outline tree, main tabs, composer.
- `ResearchWorkspaceFactory`, `ResearchWorkspacePluginExtension`.
- `ResearchDemoData` and the `research.domain` package (outline/section/source/finding/problem) — only the
  removed shell used them.
- Legacy tests for the shell and the workspace-focused classloader test.

`extensions.idx` now lists only `ResearchAgentPluginExtension`. Plugin id
(`com.aresstack.askai.research`) and the plugin jar are unchanged.

## Changed extension

Research is discovered **only** as an `AgentPluginExtension`. The generic `WorkspacePluginExtension` path
remains in the host for other/future plugins; Research no longer needs it. The host treats a plugin as
selectable via its agent extension alone (agent-only plugins get a catalog entry from a bridged descriptor).

## State model migration (Commit 14)

The free `(ResearchPhase, ResearchRunState)` pair is replaced by a hierarchical OO state pattern
(`ResearchPhaseState → PhaseState`). The `ResearchStateMachine` port is unchanged; `DefaultResearchStateMachine`
is now a facade. Persisted state is a **memento of ids only** (`phaseId`, `stateId`, `continuationStateId`,
`revision`, `pendingApprovalId`) — see `docs/research-state-machine.md`. Old code that constructed arbitrary
phase/run-state pairs is gone; illegal combinations are now rejected by `ResearchStateFactory`.

## Persistence keys & project paths

- Chat/host state keys are unchanged (`chat.interactionMode`, `chat.questingAgentId`, artifact-area
  `chat.artifactArea.visible/width`).
- New file-backed project store layout (`docs/research-artifacts.md`):
  `project/artifacts/*.md (+ .meta)`, `project/sources/*.properties`, `project/state/research-session.json`.

## Fallbacks / not-yet-migrated

- The live `ResearchAgentSession` still uses in-memory artifact/source stores; the file store is implemented
  and tested but not yet wired into the running session (RA-P001, RA-P002 in `problems.md`).
- No non-migratable runtime state: the fake backend starts a fresh run on activation; restart-restore is
  available at the store level and is the remaining wiring step.

## Research MVP (Commits 30–39)

The clickdummy backend gained a full productive sibling; nothing user-visible was removed:

- New modules: `mcp-runtime-api`, `mcp-solon-runtime`, `mcp-marketplace`, `browser-api`,
  `browser-static-http`, `browser-mcp-server-java21` (the ONE Java-21 process), `acp-client-api`,
  `acp-solon-client`, `acp-demo-agent`, `research-agent-runtime` (the external agent process).
- `ResearchSessionBackend` now has two implementations behind the same port: the deterministic
  `FakeResearchSessionBackend` (clickdummy, still the UI default — see problems.md MCP-P007) and the
  productive `AcpResearchSessionBackend` created by
  `research.host.ProductiveResearchBackendFactory` (generation-scoped endpoints, browser sidecar,
  file-persisted sources).
- `ResearchControlContext.acceptCapture` now returns the compact Commit-37 acceptance line
  (status/source_id/…); `source_accept` forwards it verbatim (was: a bespoke "accepted sourceId=…"
  string).
- The browser sidecar ships as a THIN jar + `lib/` directory (a fat jar breaks GraalJS/Truffle —
  see problems.md MCP-P005/packaging).
- Nothing research-related is packed into the AskAI fat jar; shared APIs stay compileOnly in the
  plugin and are host-provided.

See docs/research-mvp.md for the full chain, prerequisites and the manual acceptance checklist.

### Commit 40 — productive mode configurable from AskAI

`ResearchRuntimeSettings` (typed, persisted via the workspace state store) + the **Runtime** view in the
Research workspace select FAKE vs. productive ACP; `ResearchAgentSessionFactory` switches strictly on the
validated mode, and AskAI now provides the host runtime services (lazy Solon MCP runtime, MCP tool-client
factory, ACP connector) through the new neutral `AgentHostContext.getService` lookup. MCP-P007 resolved;
chat-driven phase transitions in the productive mode remain open as MCP-P008.

### Commit 41 — chat commands bridge to the productive state machine

`ResearchSessionCommandPort` (text → submitPrompt; structured actions → dispatch with the existing
`ResearchCommandType`, structured `ResearchCommandDispatchResult`) is now the single seam between the chat
UI and a research session. In productive mode the session owns its resources and routes commands to the
authoritative host state machine (tool set republished, view model mirrored); the ACP backend stays a pure
transport adapter. New `/do <command>` slash command completes exactly the live-allowed commands.
MCP-P008 resolved.

## Java 21 return — one process (2026-10)

AskAI runs on Java 21 again and everything that used to be a side process now lives inside the app:

- `ollama4j-java8` (the in-house Java-8 port) is gone; the official `io.github.ollama4j:ollama4j:1.1.7`
  client is used directly (`AskAiOllamaClient` patches the raw chat body for `think`/`tool_name`).
- `local-model-runtime-sidecar-java21` → `:local-model-runtime` (library). `LocalModelRuntimeManager`
  starts `LocalModelRuntime` in-process; the loopback HTTP API stays for all consumers.
- `browser-mcp-server-java21` → `:browser-playwright` (library). The research plugin bundles it and starts
  `PlaywrightBrowserRuntime` in-process on the first browser command; `BrowserToolSet` serves the flat
  `web_*` tool set with the former MCP text contracts. `BrowserMcpSidecarProcess` is gone.
- `research-agent-runtime` is a library: `ResearchAgentSession` replaces the `ResearchAgentMain` process,
  `ContributionToolInvoker` dispatches to the host's tool contributions directly (`SolonToolInvoker` gone).
  The plugin's `InProcessResearchSessionBackend` replaces `AcpResearchSessionBackend`; the package
  `research.acp` became `research.agentwire`.
- Removed modules: `acp-client-api`, `acp-solon-client`, `acp-demo-agent`; removed ports `McpToolClient`,
  `McpToolClientFactory`, `SolonMcpToolClientFactory` (no MCP client is needed in-process; the loopback MCP
  server stays for external clients such as bot control).
- Runtime settings keep only the browser channel, headless mode and the development loopback policy; the
  agent-Java/agent-jar/sidecar-jar fields and `researchRuntimeDist` are gone. `:askai-app:fatJar` and
  `:research-agent-ui-plugin:pluginDistribution` produce everything.
