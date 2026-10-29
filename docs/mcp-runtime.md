# MCP runtime (host side)

## Port and implementations

`:mcp-runtime-api` (no Solon/MCP-SDK types) defines the host's MCP surface:

- `McpServerRegistry` — register/unregister endpoints, update tool sets (`tools/list_changed`),
  `endpointUrl(handle)` for the client-facing URL (token embedded in the path).
- `McpEndpointHandle` — endpoint id + opaque random token; unregistering invalidates the token.
- `McpToolContribution` / `McpToolHandler` / `McpToolCall` / `McpToolResult` / `McpToolParameter` —
  flat tool schemas only (STRING/INTEGER/BOOLEAN), results are plain text, never raw HTML/binary.
- `InProcessMcpServerRegistry` — transport-free reference implementation for wiring/tests. It is NOT
  an MCP protocol implementation; `endpointUrl` returns an identity-only `inprocess:` URI.

`:mcp-solon-runtime` implements the port over the real Solon streamable-HTTP transport:

- `SolonMcpServerRuntime` — one Solon server on `127.0.0.1` with a free port; every endpoint lives
  under `/mcp/<id>/<token>`; `updateTools` emits `tools/list_changed`; shutdown is idempotent.

## Rules

- All endpoints bind to `127.0.0.1` only; every session endpoint carries a random, non-guessable
  token that is invalidated at session end (unregister).
- Tokens never appear in transcripts, tool bubbles, normal logs or `toString()`.
- No own MCP protocol implementation anywhere — Solon (`org.noear:solon-ai-mcp:3.10.1`) is the only
  wire implementation. The research agent itself needs no client: it dispatches in-process to the same
  tool contributions (`ContributionToolInvoker` in `:research-agent-runtime`).

## Verification

`SolonMcpServerRuntimeTest` (real client round-trip, token gate, tools/list_changed) covers the wire; the
research runtime's loop tests (36A/36C, 38 E2E) dispatch in-process through the same contributions.
