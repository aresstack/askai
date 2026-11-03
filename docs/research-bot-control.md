# Research bot control (MCP)

How an external bot/script drives a running Research session. One endpoint, three tools, one
state-dependent command vocabulary — the SAME command processor the GUI buttons and slash commands use.

## Connecting

Each productive session writes `<projectDir>/service-endpoint.json`:

```json
{
  "endpointId": "research-bot.<sessionKey>.g<generation>",
  "url": "http://127.0.0.1:<port>/mcp/<sanitized-id>/<token>",
  "transport": "streamable",
  "token": "<per-session token>",
  "usage": "<the bot usage guide>"
}
```

- The token is part of the URL path — the `url` is everything a client needs.
- Streamable HTTP = MCP JSON-RPC (initialize → tools/list → tools/call). Plain `curl` cannot speak the
  handshake; use any MCP client (e.g. Solon's `McpClientProvider` from `org.noear:solon-ai-mcp`).
- The endpoint dies with the session (close invalidates the token); the file is overwritten on the next
  session start and simply goes stale after close.
- The toggle "Bot control via MCP" in the Research-Agent settings (gear menu) disables the endpoint for
  new sessions (default: on). Disabled sessions delete a stale descriptor file.

## Discovery ("help")

MCP has no separate help endpoint; the standard discovery is `tools/list` — every tool carries its full
usage contract in the description, and the descriptor file's `usage` field repeats the workflow guide.
(The MCP-standard `initialize.instructions` field would be the native place for server instructions; the
current Solon server API cannot set it.)

## The three tools

| Tool | Purpose |
|---|---|
| `session_state` | WHERE the session is and WHAT can be done now: phase/state/revision/pendingApproval/busy, the currently valid commands (identical to the UI buttons), the clickable decision buttons, and the search suggestions — each rendered as a directly executable `search "query"` pair. |
| `run_command` | Do ONE thing: `command` + `arguments`. No `command` = the arguments are a plain chat message (a normal TeamAgent turn). Unknown or currently-not-allowed commands are rejected with the reason AND the valid list — never silently, never treated as chat. |
| `chat_history` | The phase-attributed conversation record: finished phases as ONE summary (outcome narrative + message count), the current phase in detail; `raw=true` returns every recorded message. In-memory per session (empty after an app restart). |

## Command vocabulary

Always available: `search <query>`, `generate-visualization`, `generate-outline`, `review-sources`.

State-dependent (semantic names; the processor resolves them against the live state machine — internal
command enums are never exposed): `submit-scope`, `approve`, `request-changes`, `continue`, `retry`,
`resume`, `pause`, `cancel`. After a finished research run, its follow-up offers (e.g. `retry`,
`accept-limitation`, `end`, `review`, `sources`, `config`) are additionally valid until a decision
consumes them.

## Typical workflow

```
session_state                                    → phase=scoping, suggestions, valid commands
run_command arguments="Ich möchte zu X forschen" → chat turn; the agent builds the brief
run_command command=search arguments="X sensors" → real web search, sources are accepted
run_command command=review-sources               → agent summary of the new sources
run_command command=generate-outline             → topic discovery + outline rebuild
run_command command=submit-scope                 → approve the brief, research phase starts
chat_history                                     → phase summaries; raw=true for everything
```

Re-check `session_state` after every action — the valid command set follows the state machine.
