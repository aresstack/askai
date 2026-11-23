# AskAI Research MVP – live interim acceptance (2026-11-09)

## Environment

- Branch: `develop`; tested at commit `34a27873` (bot onboarding fix).
- IntelliJ/Pyloros: available.
- Application: started via `askai [runWithDevPlugins]`, live connector listening on port 8082.
- Public connector: `sessions_list`, `session_create`, `run_command`, `session_state`, `chat_history`, `concept_json`, `scope_snapshot`, `technical_log` verified.
- Test session: `b6c5f106-6860-4627-8819-166b9934b5fc`, titled "MVP Bot-Onboarding Regression – 2026-11-09".
- Topic: German-language practical book about construction-site safety wearables and sensors, target audience safety professionals and site managers.

## Results

| Check | Result | Evidence / qualification |
| --- | --- | --- |
| Start application with development plugins | PASS | Run console shows local model services and ChatGPT connector bound to 8082 |
| Create a fresh session via connector | PASS | Returned `scoping/new` and a stable session ID |
| First bot chat message initializes Concept | PASS | `run_command` accepted; `scoping/new` -> `scoping/running` |
| Actual model interaction | PASS | `gemma4:e2b` produced 9 concept cards, 5 search suggestions and a German answer |
| Persist and read concept | PASS | `concept_json` revision 1; root and cards present |
| Rename a single concept card | PASS | Narrow request applied, `concept_json` revision 2 and changed root name |
| Bot phase approvals remain blocked | PASS | `submit-scope` rejected: "Phase changes need the user" |
| Manual web search with actual results | PASS (partial M2) | Search "DGUV Wearables Sensorik Arbeitsschutz Baustelle Gefährdungen" returned 24 SERP candidates, 24 retained, 6 near concept IN anchors; several pages and links were processed; agent generated a post-search summary |
| Source review conversational entry | PASS (partial) | `review-sources` accepted; a follow-up assistant response appeared |
| Scope calibration | NOT ACCEPTED | `check-scope` outcome `CALIBRATION_WEAK`, coverage 1/9, neighbor samples 2, negotiated posts 9, mission samples 9 |
| Finish M2 source approval and freeze | NOT RUN | Requires a user-authorized `submit-scope` phase transition; the bot deliberately cannot issue phase commands |
| M3/M4 end-to-end generation | NOT RUN LIVE | No approved concept / pinned sources in this test project; scripted tests are distinct from live acceptance |

The full `:research-agent-ui-plugin:test` suite succeeded on this checkout after the bot onboarding fix. This does not replace live source / outline / document acceptance.

## Findings requiring review

### A. False-positive compound restructuring classifier

A specific request to rename one concept card was refused with `concept mutations READ-ONLY this turn (compound restructuring request)` when the request contained the negative phrase "Keine weiteren Umstrukturierungen". The same rename **without** that phrase succeeded and persisted revision 2.

Suspected cause: `research-agent-runtime/.../ConceptTurnPolicy.java` uses substring matching on `RESTRUCTURE_VERBS`, including `umstrukturier`, without interpreting negation. Add a regression test for negated references while preserving the safety gate for actual compound rewriting.

### B. Incorrect claim of persisted deliverable length

User requested an 80–120 page book, then separately explicitly asked to store about 100 pages. The agent answered that it had saved the 100-page objective; `scope_snapshot` revision 4 still showed:

```text
DELIVERABLE
- size not stated yet
```

Trace reported `scope update -> APPLIED: [deliverable = unspecified size]`. The domain **does support** `ResearchDeliverable.LengthUnit.PAGES` and min/max length. Investigate the model's scope operation, validation or parsing and ensure success narration is tied to the resulting stored value.

### C. Incomplete scope calibration

The actual model-driven `check-scope` returned `CALIBRATION_WEAK` (1/9 anchor coverage). Do not label the Concept gate accepted. Check calibration probe quality and anchor granularity before treating M1 live as approved.

### D. Source citation verification remains open

The scoping search completed and produced topical summaries, but its final chat message did not expose passage-level, verifiable citations. Verify the evidence pack and source pins in the approved Sources phase; a conversational scoping summary alone is not a citation acceptance test.

## Acceptance verdict

- **M1**: implementation tests pass; initial live onboarding and atomic concept edits pass; Concept approval is **not yet accepted** due weak calibration and stored-length inconsistency.
- **M2**: search execution and response pass as a partial live smoke test; approved source set, source pin, blacklist scenarios and source evidence remain open.
- **M3**: no live acceptance yet (needs an approved Concept and source set).
- **M4**: no live book acceptance yet (needs M3 and real writer model calls).

## Next acceptance gate

After the Concept quality findings are addressed, have the human user review and approve the Concept using AskAI's UI. Continue the **same session** through Sources approval, Outline generation and approval, then full Document writing, integrity/citation checks, and Markdown book export. Do not impersonate a user approval through the bot connector.
