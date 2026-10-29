# Research-Control MCP

The session-scoped MCP endpoint through which the external research agent works on the project.
Implemented in `research-agent-ui-plugin` (`research.mcp`), transported by the host's
`SolonMcpServerRuntime`.

## Identity and lifetime

- Endpoint id: `research.<sessionKey>.g<generationId>` — bound to the session AND the plugin
  generation, plus the registry's opaque random token in the URL path.
- Registered at session start, unregistered at session end/generation retirement → the old
  endpoint/token can never accept calls again.
- On every host state transition the tool set is recomputed and pushed (`tools/list_changed`).

## The single tool matrix (`ResearchToolPolicy`)

Always readable: `research_status`, `artifact_read(name)`, `source_list`.
Write tools are bound to phase AND run state (`running` only — never in waiting_approval / paused /
blocked / failed / terminal):

| Phase        | Write tools                                    |
|--------------|------------------------------------------------|
| SCOPING      | `concept_save`                                 |
| OUTLINE      | `outline_save`                                 |
| RESEARCH     | `source_accept`, `finding_add`, `notes_append` |
| DRAFT        | `draft_read`, `draft_save`                     |
| FINALIZATION | `final_read`, `final_save`                     |

There is deliberately **no phase-transition tool** (no advance_phase/approve_phase/set_state). The
agent signals readiness only as an event (a `PHASE_READY` line to the host); the host/plugin state machine is
the sole transition authority.

## Visibility ≠ authorization

`ResearchToolPolicy` computes visibility from (phaseId, stateId); every write HANDLER re-checks the
same rule against the live `ResearchControlContext` state at execution time — a transition between
`tools/list` and `tools/call` yields the readable rejection
`Not allowed in the current state (<phase>/<state>).`, which the agent loop maps to
APPROVAL_REQUIRED/STATE_CHANGED.

## Contracts

- `source_accept(capture_id)` returns the compact Commit-37 acceptance line verbatim:
  `status=ACCEPTED|ALREADY_ACCEPTED source_id=<id> title="..." passage_count=N duplicate=<bool>
  [index=STALE]` (unknown capture → error).
- `finding_add(source_id, text)` verifies the source exists, then appends
  `- [<sourceId>] <text>` to the `findings` artifact.
- Saves use optimistic locking (`expected_revision`).

## Verification

`ResearchControlEndpointTest`, `ResearchToolPolicyTest` (matrix + live re-check), the agent
round-trip test (readiness through this endpoint) and the 38 E2E (productive acceptance path
through these exact tools).
