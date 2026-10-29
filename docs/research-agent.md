# Research Agent

The Research Agent is a chat agent plugin (`com.aresstack.askai.research`) that extends the shared AskAI
chat. It is selected under **Questing → Research Agent**. There is no standalone research workspace anymore
(removed in Commit 17); Research is discovered only via `AgentPluginExtension`.

## What it adds

- **Slash commands** to steer the session (see below).
- **Agent activity** in the normal chat (thinking, tool activity, approval requests, problems).
- **Artifacts** in the shared, collapsible artifact area (issue #32 catalog):
  - **Fragestellung** (ResearchBrief — the canonical scoping artifact), **Visualisierung** (derived Mermaid),
    **Inhaltsverzeichnis** (derived outline projection), and the ONE Markdown work product `document.md`
    (DRAFT and FINALIZATION both work on it).
  - Structured: **Sources** (a writing manager) and **State** (state-machine visualization).
  - Legacy `concept/research-notes/findings/draft/final` files in old projects stay on disk but get no tabs.
- A deterministic **fake backend** (`FakeResearchSessionBackend`) drives a simulated run; the productive
  `InProcessResearchSessionBackend` implements the same `ResearchSessionBackend` port without UI changes.

## Slash commands

| Command | Effect |
|---------|--------|
| `/status` | Show the current phase and run state in the chat. |
| `/approve` | Approve the pending approval gate (only when one is pending). |
| `/request-changes` | Reject the pending proposal and return to the working step. |
| `/pause` / `/resume` | Pause/resume the active run. |
| `/cancel` | Cancel the session. |
| `/open <artifact>` | Reveal an artifact tab (completes artifact ids). |

Typing `/` opens a completion popup on the shared composer (Up/Down navigate, Tab accepts, Enter executes,
Escape closes). A slash line is executed as a command and is never sent to the model.

## Flow

```
Questing + Research Agent selected
  → AgentSessionCoordinator resolves the agent extension, creates/reactivates the session
  → composer routes prompts to the session; slash commands to the command registry
  → backend events → AgentConversationSink → the SAME chat transcript
  → artifacts open in the shared artifact area
```

## Headless / bot control

External bots drive a session over the per-session bot-control MCP endpoint (`run_command`,
`session_state`, `chat_history`) — the same command processor the buttons and slash commands use.
See `docs/research-bot-control.md` for the connection recipe, discovery and the command vocabulary.
See `docs/research-chatgpt-connector.md` for exposing AskAI as its OWN ChatGPT connector (OAuth + public MCP endpoint behind the reverse proxy).

Switching back to Yapping deactivates (keeps) the session; disabling the plugin closes it and falls back to
Yapping. See `docs/plugin-lifecycle.md`.
