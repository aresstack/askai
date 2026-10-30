# MCP runtime (host side)

## Port and implementations

`:mcp-runtime-api` (no Solon/MCP-SDK types) defines the host's MCP surface:

- `McpServerRegistry` — register/unregister endpoints, update tool sets (`tools/list_changed`),
  `endpointUrl(handle)` for the client-facing URL (token embedded in the path).
- `McpEndpointHandle` — endpoint id + opaque random token; unregistering invalidates the token.
- `McpToolContribution` / `McpToolHandler` / `McpToolCall` / `McpToolResult` / `McpToolParameter` —
  flat tool schemas only (STRING/INTEGER/BOOLEAN), results are plain text, never raw HTML/binary.
- `McpToolClient` / `McpToolClientFactory` — the neutral CLIENT port for tools on another MCP endpoint
  (external MCP servers the marketplace activates, a future remote agent). Tool failure and an
  unreachable endpoint stay distinguishable (`McpToolCallException.isEndpointUnavailable()`);
  `connect(url, transport, requestTimeoutSeconds)` sets the per-call budget.
- `InProcessMcpServerRegistry` — transport-free reference implementation for wiring/tests. It is NOT
  an MCP protocol implementation; `endpointUrl` returns an identity-only `inprocess:` URI.

`:mcp-solon-runtime` implements the port over the real Solon streamable-HTTP transport:

- `SolonMcpServerRuntime` — one Solon server on `127.0.0.1` with a free port; every endpoint lives
  under `/mcp/<id>/<token>`; `updateTools` emits `tools/list_changed`; shutdown is idempotent.
- `SolonMcpToolClientFactory` — the `McpToolClient` implementation over Solon's `McpClientProvider`
  (streamable HTTP). The per-call timeout applies to the MCP request AND to the HTTP socket read (Solon's
  read timeout would otherwise stay at 30 s); connect and the initialize handshake are bounded separately.
  `close()` releases the client's non-daemon scheduler thread. The URL carries the token and is never
  logged. The official MCP Java SDK's `HttpClientStreamableHttpTransport` client can replace it behind
  the same port if Solon's client ever falls short (ADR 0001, section 3.3).

## Host wiring

`AgentRuntimeServices` (askai-app) publishes the client port to plugins as a host service:
`McpToolClientFactory` is backed by `SolonMcpToolClientFactory`, next to the `ModelPort` service (see
research-agent-runtime.md). No role uses it yet: the research orchestrator and its workers call only the
host's own tools in-process; a role that needs an external MCP server gets it through this factory.
Activating marketplace entries as live clients is not wired either (the marketplace saves configurations
only).

## Transports

How a tool call is executed is a transport question; the contributions, their gates and their receipts
are the same on every lane:

| Caller | Lane |
|---|---|
| our own roles (research agent) | in-process: `ContributionToolInvoker` in `:research-agent-runtime`, no wire |
| external MCP clients (ChatGPT connector, bot control) | the Solon loopback server |
| external MCP servers, a future remote agent | the client port (`McpToolClient`) |

## Rules

- All endpoints bind to `127.0.0.1` only; every session endpoint carries a random, non-guessable
  token that is invalidated at session end (unregister).
- Tokens never appear in transcripts, tool bubbles, normal logs or `toString()`.
- No own MCP protocol implementation anywhere — Solon (`org.noear:solon-ai-mcp:3.10.1`) is the only
  wire implementation, on the server and on the client side. The research agent itself needs no
  client: it dispatches in-process to the same tool contributions.

## Verification

`SolonMcpServerRuntimeTest` (real client round-trip, token gate, tools/list_changed) covers the wire;
`SolonMcpToolClientTimeoutTest` pins the client's per-call and socket timeouts and
`SolonThreadDaemonDiagnosticTest` that `close()` frees the client's threads. `ToolTransportParityTest` in
`:research-agent-runtime` runs the same contributions in-process and over the loopback server with the
client and pins identical results (success text, tool failures, unavailable endpoint). The research
runtime's loop tests (36A/36C, 38 E2E) dispatch in-process through the same contributions.
