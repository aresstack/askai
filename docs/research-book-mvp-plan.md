# Research agent: plan to the first complete book (MVP)

| | |
|---|---|
| Status | Proposal for review. Nothing in this document is implemented yet. |
| Date | 2026-11-03, revised 2026-11-04 after an external architecture review ("Hardening after review" in section 0) |
| Code base | `develop` at `ab2847e` (after #105) |
| Goal | The research agent writes, for the first time, a complete book on a topic outlined in Phase 1. Every phase change is made by the user. |
| Default model | `gemma4:e2b`; alternatives `llama3.1:8b` and `gpt-oss:20b` (mainly for writing) |
| Target machine | One RTX 5080 with 16 GB, Ollama |
| Relation to other docs | Replaces the critical path of `mvp-roadmap.md`; section 10 maps its work packages to PRs, and section 2.2 lists its status corrections (AP 2 and AP 3 are built). `docs/research-state-machine.md` is outdated (7 phases); this plan uses the 4-phase graph that is in the code. |

## 0. Summary

**The state machine is already right.** The productive graph (`ResearchStateGraph`) has exactly the four phases in
the intended order, Concept (`scoping`) -> Sources (`research`) -> Outline (`outline`) -> Document (`draft`), with a
review gate at the end of Sources, Outline and Document and jump-back edges (`REOPEN_CONCEPT/SOURCES/OUTLINE`) from
every later state. Concept ends with one user step, `submit-scope`, which approves the concept and opens Sources. The
agent has no tool that changes a phase. The plan keeps the graph and its ids unchanged and builds what is missing
around it.

**What breaks today.** Phase 1 works end to end. After that the chain breaks at five places:

1. Completing Phase 1 freezes nothing and starts nothing. `FileConceptStore.approveCurrent` has no productive caller,
   and Phase 2 waits for a `researchQuestion` that only a dead code path ever sets. The next typed chat line becomes the
   literal search query.
2. Phase 2 can only be left through volatile run-outcome tags, and the "End research" tag dispatches `CANCEL`. From
   `research/cancelled` the only way on is `reopen-concept` and a new `submit-scope`.
3. `PROPOSE_OUTLINE` and `REQUEST_DRAFT_REVIEW` have no user command. Only the fake backend sends them, so a real
   session can never move forward from `outline/running` and never complete the document.
4. There is no user-owned outline, no chapter writer, no manuscript store and no citation registry. The only LLM
   writer (`WriterWorker`) runs inside the opt-in orchestrated search, and its drafts never reach the document.
5. Freeze exists only for the agent's MCP write tools. Every user edit path (concept editor, history restore, outline
   rebuild, document editor) ignores the phase, and the mission is not frozen at all. The blacklist has the opposite
   gap: its only user surface exists only in Concept.

**Small-model blockers.** Each Phase-1 inference carries a 20.8k-character system prompt (about 5,200 to 6,500 tokens)
plus the whole concept, an unbounded source list and the complete, never-compressed history. AskAI sends `num_ctx`
only when a request or slot context is set (both 0 on the default path); unless the server is configured otherwise,
current Ollama then uses 4,096 tokens on a GPU below about 23 GiB and truncates silently. `gemma4:e2b` most likely
thinks by default, because research calls other than the SERP layout repair never set `think`. All phases share one
model slot whose fallback is whatever model the chat window last selected. Source texts with umlauts are corrupted on
every read (UTF-8 written, ISO-8859-1 read). The retrieval models are English-centred: the embedding preset
`nomic-embed-text` and `cross-encoder/ms-marco-MiniLM-L6-v2`, the only reranker that AskAI's local runtime can run.

**The approach.** Structure is deterministic code; the small model gets small, bounded wording tasks with a fallback.
Code builds the search plan, the outline skeleton, the passage assignment, the evidence packs, the citation numbers
and the assembled book. The model expands queries, names clusters, writes briefs and writes section prose. Every model
call sends an explicit context, thinking mode and answer budget, and a prompt that does not fit fails visibly.

**User control.** Only the user changes phases; a connected bot can read the state and suggest a step, but every phase
command from a bot is refused (Q13). An approval records exactly what the user reviewed (concept with mission, source
set, outline, manuscript) before the state changes, and a failed record refuses the transition. Stop ends what is
running but never changes the phase, so Finish stays available.

**Hardening after review.** An external architecture review found weak spots that the plan now covers. A crash between
an approval and its effects is recovered from a transition journal that works as an outbox: every effect of a
transition, including the chained start of the next job, is recorded as open or done, and the next session open runs
only the open ones (D3, PR 06). Source texts are write-once, and approvals and citations name the source's content
checksum and the cited passage (D4, PR 14, PR 26). Numbers, years, percentages and quotes in the prose are checked in
code against the cited passage; whether a passage really supports a sentence is not checked in the MVP, and the book
says so (D8, PR 28). A deterministic quality report states whether the book is complete or an incomplete draft, and
the user still decides (D8, PR 30). The embedding model and the ranking of planned queries are chosen by a retrieval
benchmark with German, German-to-English and English queries, because both current presets are English-centred; PR 04
also estimates the effort of a multilingual path for the local model runtime (section 5.4). PR 04, PR 24 and PR 31
move into the MVP, the writer A/B adds one complete sample chapter per model, and the first-book run checks workflow,
data integrity and book quality separately (PR 32).

**What the plan can promise.** Workflows that are reproducible and resume after a restart or a crash, sources that
stay traceable from every citation, small models working on bounded tasks, part of the hallucinations caught in code,
and a book that states its own quality limits. Whether the book is good in substance only the live runs can show (PR
31, PR 32).

**The plan.** 37 pull requests in six milestones, each mergeable on its own (section 6). 26 are needed for the first
book, 5 implement agreed rules that do not block it (the Models tab, phase summaries, the Workflow tab, passage
revision, focused gap research), and 6 are quality work.

| Milestone | Result |
|---|---|
| M0 Honest foundations | Clean data (umlauts), visible token use and truncation, explicit context and thinking on every call, measured defaults and a retrieval benchmark in German and English |
| M1 Walking skeleton | Every gate from Concept to the finished book is reachable by user commands; transitions recover after a crash; concept and mission freeze; one model per phase, set in a new Models tab; the Revisions tab becomes the Workflow tab |
| M2 Concept to sources | A blacklist that filters; the frozen concept drives an autonomous, resumable search plan through the one acquisition funnel; the approved source set with write-once source texts; phase-scoped chat context |
| M3 Outline | "Generate outline" builds an outline from concept and clustered sources; the user edits it, regenerates single chapters and freezes it |
| M4 First book | Section-by-section writing with checked citations and claim checks, a one-click gap search, an assembled book with references and a quality report, the writer A/B with a sample chapter, and a live acceptance run on three levels |
| M5 After the first book | Mark a passage and revise it with a comment (like ChatGPT), focused gap research from Outline and Document, robustness, export, cleanup |

**Models.** `gemma4:e2b` for Concept, Sources and Outline and for all helper roles (thinking off, 32k context).
`llama3.1:8b` is the recommended Document model: it should fit next to `gemma4:e2b` in 16 GB (PR 04 measures it) and
has no reasoning tokens. `gpt-oss:20b` stays an opt-in writer, chosen only if a blind A/B on identical sections shows
clearly better German prose, because it cannot share the GPU with another model. Each phase gets its own model in a
new "Models" tab of the research settings, independent of the chat model (section 5). The embedding model and the
ranking of planned queries follow the retrieval benchmark of PR 04.

**Decisions needed first** (section 9 has all questions with recommendations):

| # | Question | Recommendation |
|---|---|---|
| Q2 | Blacklist frozen with the concept, or editable in every phase? | Editable in every phase: later entries can be corrected and filter future searches only; the blacklist recorded with the concept stays frozen |
| Q4 | Start the search and the writing automatically after the user's approval, or wait for an extra "Start"? | Start automatically; Stop is always available and never changes the phase |
| Q6 | Outline from concept chapters plus clustered sources, or purely from clusters? | Concept chapters plus clusters; pure clustering ignores the Phase-1 intent |
| Q10 | Document model before the A/B: `llama3.1:8b` or `gemma4:e2b`? | `llama3.1:8b` where it is installed, until the writer A/B of PR 31 decides |
| Q12 | Is an embedding model mandatory? | Yes, like the reranker today; the retrieval benchmark of PR 04 picks the model |
| Q13 | May a connected bot change phases? | No; a bot reads the state and may suggest a step, the user takes it |

## 1. Target model mapped onto the code

| Phase (owner) | State id | UI label | Main result | Frozen when | Exit (user) | Way back |
|---|---|---|---|---|---|---|
| 1 Concept | `scoping` | Concept | Concept mindmap and mission; the blacklist stays editable (Q2) | `SUBMIT_SCOPE`, together with mission, deliverable, working language and the blacklist of that moment | `submit-scope` (exists), which also starts the search | `REOPEN_CONCEPT` from any later state |
| 2 Search | `research` | Sources | Accepted sources with full text and passages, coverage per concept node | `APPROVE_EVIDENCE` records the accepted sources with their content checksums; later phases may add sources, tagged with their phase (Q3) | `finish-sources` -> `REQUEST_EVIDENCE_REVIEW` (new), then `approve` | `REOPEN_SOURCES` from Outline and Document |
| 3 Outline | `outline` | Outline | User-owned outline with stable chapter and section ids | read-only at the gate after `PROPOSE_OUTLINE`, frozen at `APPROVE_OUTLINE` | `finish-outline` -> `PROPOSE_OUTLINE` (new), then `approve`, which also starts the writing | `REOPEN_OUTLINE` from Document |
| 4 Write | `draft` | Document | Manuscript (sections with citations) and the assembled book | read-only at the gate after `REQUEST_DRAFT_REVIEW`, final at `APPROVE_FINAL` | `finish-document` -> `REQUEST_DRAFT_REVIEW` (new), then `approve` | none into `draft` itself (Q11) |

Inside each phase the existing inner states stay as they are: `new`, `waiting`, `running`, `waiting_approval`,
`paused`, `blocked`, `failed`, `completed`, `cancelled`. `research/waiting` and `draft/waiting` remain short steps
between the approval and the start of the phase's job, as `research/waiting` already is today. Once Stop no longer
pauses (PR 06), no productive path enters `paused`, `blocked` or `failed`; today only the fake backend dispatches
`BLOCK` and `FAIL`. They stay in the graph. The legacy ids `evidence`, `review` and `finalization` are remapped on
restore until PR 37 deletes the remap. Only display names may change (Q1).

## 2. Where the chain breaks today

All findings below were verified against the code; `path:line` refers to `develop` at `ab2847e`. Short paths:
`plugin` = `research-agent-ui-plugin/src/main/java/com/aresstack/askai/research`,
`runtime` = `research-agent-runtime/src/main/java/com/aresstack/askai/research/runtime`.

### 2.1 The user journey, step by step

| # | Step | What happens today | Status | Evidence |
|---|---|---|---|---|
| 1 | First message in a new research chat | `START` -> `scoping/running`; TeamAgent turn on the SCOPING profile with grammar-constrained concept actions (`ConceptToolRounds`, up to 4 tool rounds + 2 repairs). The first non-empty user message becomes the mission, even a greeting | works | `plugin/agent/ResearchAgentSession.java` (startScopingOnFirstMessage, recordMissionIfAbsent `:4084-4104`), `runtime/team/ConceptToolRounds.java` |
| 2 | Concept and blacklist | Concept = JSON mindmap of card names in `FileConceptStore` (working revisions + history; node ids in an identity sidecar). Blacklist = projection over `scope-draft.json`. Only facets created by the model's `exclude_topic` become search filters; entries the user types into the "out of scope" sky never filter search | partial | `plugin/scope/ScopeSweepPlanAssembler.java:54-70`, `research-domain/.../scope/ResearchScopeDraft.java:183-205` |
| 3 | Complete Phase 1 (`submit-scope`) | Dispatches `SUBMIT_SCOPE` + `START_RESEARCH` -> `research/running`. Approves only the legacy brief (skipped when blank). The concept is not snapshotted. `maybeStartResearchTurn` needs a non-empty `researchQuestion`, which only the dead `SCOPE_PROPOSAL` chain sets, so nothing starts while the chat list shows the session as busy | broken | `plugin/agent/ResearchAgentSession.java:1322-1332`, `:1364`; `plugin/store/FileConceptStore.java:362` (no caller) |
| 4 | Phase 2 search | In `research/running` every typed line becomes the single SERP query and the relevance query of one loop run (at most 8 sources, BM25 rerank by default). Concept-driven planning exists only in the opt-in orchestrated mode and only for first-level concept nodes | partial | `runtime/ResearchAgentSession.java:842-844`, `runtime/acquire/WebSearchApplicationService.java:393` |
| 5 | Complete Phase 2 | Only through the `review`/`limit` run-outcome tags of a loop run. They live in a volatile field, are lost on restart and never appear after orchestrated or manual searches. "End research" calls `cancel()` -> `research/cancelled` | broken | `plugin/agent/ResearchAgentSession.java:4955-5036` |
| 6 | Approve sources | `APPROVE_EVIDENCE` -> `outline/running`; nothing records which sources were approved | works | `plugin/state/oo/ResearchStateGraph.java:65` |
| 7 | Phase 3 outline | "Inhaltsverzeichnis erzeugen" in the Document tab's outline drawer clusters passages (agglomerative, cutoff 0.55) into a read-only projection and overwrites `artifacts/outline.md`. Works in every phase, ignores concept and blacklist (its gap analysis reads the legacy brief questions), needs an embedding model | partial | `plugin/agent/ResearchOutlineView.java:32`, `plugin/host/ProductiveResearchBackendFactory.java:558-594` (legacy brief questions in, `outline` artifact replaced), `plugin/host/KnowledgeProcessingSessionFactory.java` (pipeline) |
| 8 | Complete Phase 3 | `PROPOSE_OUTLINE` has no semantic command, tag, slash command or button. Only `FakeResearchSessionBackend` sends it. **Hard stop.** | broken | `plugin/agent/ResearchSemanticCommands.java:26-41`, `plugin/backend/FakeResearchSessionBackend.java:347` |
| 9 | Phase 4 writing | Unreachable. If reached: the DRAFT profile is advisory and read-only, `document_save` has no caller, the document changes only through the manual editor | missing | `runtime/team/PhaseAssistantProfileRegistry.java`, `plugin/mcp/ResearchToolPolicy.java:96-99` |
| 10 | Complete the book | `REQUEST_DRAFT_REVIEW` has no user command, so `APPROVE_FINAL` and `draft/completed` are unreachable | broken | `plugin/backend/FakeResearchSessionBackend.java:357` |

The productive end-to-end test (`ProductiveResearchMvpEndToEndTest`) dispatches the opening commands directly on the
resources, bypasses the UI session and stops at `research/waiting_approval`. Outline and Document transitions are
covered only by state-machine unit tests and by the fake backend, which switches phases by itself.

### 2.2 Cross-cutting findings

| Area | Today | Gap for the target |
|---|---|---|
| Transition handling | `ProductiveResearchSessionResources.dispatch` persists the memento before applying it and republishes the MCP tools (`plugin/host/ProductiveResearchSessionResources.java:412-438`); it is synchronized, and UI commands reach it from the event thread | No typed hook (from, to, command, actor) where freezes, run cancellation, offer reset, summaries or phase-entry work could attach. Commands carry no actor and no payload (the `request-changes` reason is dropped, `:4382-4385`); the opt-in bot control and the opt-in ChatGPT connector can both approve gates, through one shared gateway object (`plugin/agent/ResearchAgentSession.java:151-155`). Memento, concept store and phase journal are separate files with no common transition id; `phase-journal.json` is overwritten in place off the caller thread (`plugin/agent/ResearchAgentSession.java:4185`), and an unreadable journal loads as empty (`plugin/agent/ResearchPhaseJournal.java:83`), so nothing recovers a transition that a crash interrupted |
| Stop | The composer's Stop pauses the state unless a manual search or a review is in flight, then it cancels only that (`plugin/agent/ResearchAgentSession.java:5245-5257`) | In `<phase>/paused` the finish commands are not allowed and running-only write guards would lock the editors. Run offers reuse semantic names (`resume`, `retry`, `continue`, `:4959-4999`), so a rule that resolves semantic names first would break them |
| Freeze | Only the agent's MCP write tools are phase-gated (`ResearchToolPolicy.writable/requireWritable`) | Concept editor, history restore, outline rebuild, document save, source import, manual acceptance and the Sources view's Exclude ignore the phase. The mission and the deliverable live in `scope-draft.json`, outside the concept store. Approvals record no artifact revision |
| Jump-back | `REOPEN_*` edges exist from every later state, are persisted and republish tools | Nothing is unfrozen or marked stale. A running search keeps running after a reopen, its outcome re-arms offers in the earlier phase, and "End research" after reopening Concept produces `scoping/cancelled`, which has no way out |
| Revisions tab | Phase timeline (`ResearchStateView`, artifact id `state`) with clickable plates and the phase pill; transition counter; task list; orchestrator plate | Shows no artifact revisions; the counter falls back to 0 on every agent event; plates and pill dispatch the first allowed command of a phase, so a click on the current phase means "request changes" at gates, and every reopen runs without a confirmation; German strings in an English UI |
| MCP tools per phase | `ResearchToolPolicy` is the single enforced phase x state matrix (writes only when the phase matches and the state is `running`) | The in-app model gets tools only in Phase 1 (concept action channel). Outline and chapter tools do not exist; `outline_save` and `document_save` replace whole artifacts; profile tool lists are metadata only |
| Chat annotation | `ResearchPhaseJournal` maps each published message id to its phase and persists `phase-journal.json` (schema 1) | Only the opt-in bot `chat_history` reads it. The visible chat shows no phase, the model history has no phase field, transitions are not recorded |
| Model context | `PhaseContextAssembler` builds profile prompt, date, language, state block (with the full source list), scope fence, the whole concept and then the complete history | `PhaseContextPolicy` is "carried, not enforced" (`runtime/team/PhaseContextAssembler.java:11`, `:142-143`); `ResearchTeamAgent.history` is an unbounded list (`runtime/team/ResearchTeamAgent.java:64`), lost on restart, never summarised; Phase-1 machinery (fence, concept edit rules, concept move generator) leaks into Phases 2-4 |
| Context size | `OllamaModelPort` sends `num_ctx` from the request, else from the slot (`askai-app/.../modelport/OllamaModelPort.java:678-681`). Research roles pass 0 except in orchestrated tasks, and no slot has a context by default | The answer budget is one spawn-time value for all phases (`ASKAI_AGENT_MAX_OUTPUT_TOKENS`, default 4,096); `PortMainModelChat` returns only the content, so `prompt_eval_count`, `done_reason` and a cut-off answer are invisible. Host callers such as the Models page Load button and dictation send no `num_ctx` |
| Thinking | `PortMainModelChat` sets no thinking mode (`runtime/team/PortMainModelChat.java:22`); only the SERP layout repair sends Off (`runtime/inference/PortStructuredInferenceClient.java:76`) | Every other research role runs with the model default; for `gemma4:e2b` that is most likely "on", which costs latency and can empty the 24-token page judge. A reasoning-only answer already fails visibly (`OllamaModelPort.java:762-768`) |
| Models | Eight role slots (`runtime/inference/ResearchModelSlots.java:15-29`), editable in Settings -> AI models -> Model resources | All four phases run on the one `conversation` slot (`runtime/team/PhaseAssistantProfile.java:74-85`); Outline has no profile and uses a neutral fallback. Unset slots fall back to `ai.mainModel`, which any chat tab rewrites (also silently when a chat tab is restored). Without a chat model the session starts, but every model role is unavailable (`askai-app/.../localmodels/LocalInferenceConfigurationSnapshotProvider.java:70-74`). The narrator and the brief visualizer always use the chat model (`OllamaAgentInferencePort`) |
| Retrieval models | Setup recommends the embedding model `nomic-embed-text` (`askai-app/.../ui/setup/SetupActions.java:170`) and installs the reranker `cross-encoder/ms-marco-MiniLM-L6-v2` into AskAI's local runtime (`:172`), which the research runtime calls over HTTP behind the `CandidateReranker` seam | Both models are English-centred. The local runtime runs only BERT cross-encoders with a WordPiece tokenizer and an explicit mapping (`askai-app/.../localmodels/LocalModelCompatibilityAnalyzer.java:10-16`), so a multilingual reranker is runtime work. With the AI reranker selected, an unavailable reranker ends the search with `RERANKER_UNAVAILABLE` instead of a fallback (`runtime/acquire/WebSearchApplicationService.java:809-812`). Passage ids contain the embedding fingerprint (`research-knowledge-pipeline/.../PassageSegmentation.java:140`), so a model change renames every passage |
| Data integrity | Concept store: atomic V3 commit with history | Sources, passages, live-outline titles, visualisation: UTF-8 written, ISO-8859-1 read (`plugin/store/FileResearchSourceRepository.java:138-141`; RA-P007), so umlauts break on the first table-of-contents build and the damage grows with every source update. Unlocked compare-and-write (RA-P006). A corrupt `research-session.json` silently resets the project to `scoping/new`. Source texts are write-once only by convention: a changed page at the same URL becomes a new source with its own checksum (`plugin/capture/SourceAcceptanceService.java:166`), but `put` overwrites a record verbatim, and metadata changes keep no history |
| Docs | | `docs/research-state-machine.md` still shows 7 phases with Outline before Research; several javadocs say `SUBMIT_SCOPE -> OUTLINE`, "bot control on by default", `outline_save` "legacy only"; `mvp-roadmap.md` lists AP 2 and AP 3 as open although they are built |

### 2.3 Per phase

**Phase 1 (Concept) is the mature part.** Reusable: the concept store with history and approved revisions
(`FileConceptStore`; `approveCurrent` freezes the document, card names only, but is unused), `ConceptBranchService`
(validated, compile-before-swap edits), the conversation policy (AP 2) and `scope_probe` (AP 3), the effective mindmap
(stored concept minus blacklist), the SC1 scope gate inside the acquisition funnel. Missing: a freeze that covers the
concept and the Phase-1 fields of `scope-draft.json` (mission, deliverable), a working user blacklist (typed entries,
domain blocking, editable outside Phase 1), a visible and editable mission (today the first non-empty message, even a
greeting, becomes the mission, and nobody can correct it), and a prompt that fits a small model.

**Phase 2 (Sources) has a good per-query engine and no driver.** `WebSearchApplicationService` runs SERP ->
rerank -> scope gate -> park -> readiness -> full text -> accept -> link expansion, and `SourceAcceptanceService`
queues passages and embeddings automatically. Missing: concept-to-query planning on the default path, a persisted
per-node queue with coverage targets, provenance on agent-accepted sources (query, concept node, run id are dropped),
an engine-independent way to finish the phase, a record of the approved source set, and a chat that talks about
sources instead of turning every line into a search. The manual search scores with BM25 unless "Use AI reranker" is on
(default off, read once per session). The loop and the orchestrator use a weaker configuration than the manual search
(no model readiness judge, no SERP language, no provenance). A manual search marks an agent turn as in flight, so the
composer is busy for its whole run. A phase change in the middle of a run leaves the run browsing until a budget ends
it, because `source_accept` disappears and "Unknown tool" counts as an ordinary error.

**Phase 3 (Outline) has the analysis half.** Sentences -> semantic passages -> embeddings -> clustering -> live
projection work behind an explicit button. Missing: an outline the user owns (store, editor, stable chapter ids,
revisions), the link to the concept (the projection ignores it), single-chapter regeneration, the freeze, an Outline
assistant profile and tools. The clustering is roughly cubic in the number of passages and runs without progress or
cancel; titles are the first sentence of a representative passage.

**Phase 4 (Document) is the largest unbuilt block.** Reusable: `WriterWorker` (schema-bound section drafting with a
code-side citation check, today only an orchestrator by-product), the document page view with chapter hierarchy and
`[n]` badges (`DocumentPageView`, resolver unwired), the passage index (`SemanticKnowledgeIndex`, search side
unused), the task store (`FileResearchTaskStore`, one atomic file per task, resume rules in `ResearchTaskResumption`).
Missing: manuscript store, writer job, evidence retrieval per section, citation registry, assembly, export, revision
flow, and protection against losing unsaved edits (RA-P008). The Document tab edits `document.md` as one text.

### 2.4 Assets the plan builds on

| Need | Existing asset |
|---|---|
| Phase graph, gates, jump-back | `plugin/state/oo/ResearchStateGraph.java`, `OoResearchStateMachine`, memento `phases-v4` |
| One command vocabulary for tags, slash commands, plates, pill and bot | `plugin/agent/ResearchSemanticCommands.java` + `ResearchAgentSession.executeCommand` |
| Persist-before-apply chokepoint | `ProductiveResearchSessionResources.dispatch` |
| Per-phase MCP tools | `plugin/mcp/ResearchToolPolicy.java` (`toolsFor`, `requireWritable`) |
| Frozen revisions | `FileConceptStore.approveCurrent/latestApprovedContent` (document only; idempotent by content hash). New stores keep their ids inside the records and follow the write order of `commitPair`: history first, manifest swap last |
| Small-model tool channel | `ScopingPhaseOutputContract` + `ConceptToolRounds` (grammar-constrained JSON actions, cards addressed by exact names) |
| Acquisition funnel | `runtime/acquire/WebSearchApplicationService.java`, `ManualSourceAcceptancePort` (sends query and request id with every accepted source) |
| Ranking strategies | `runtime/rerank/CandidateReranker` with `SearchResultReranker` (the cross-encoder over HTTP), `Bm25CandidateReranker` and, for tests, `EngineOrderReranker` |
| Source checksums | `SourceAcceptanceService` stores the capture's content hash as the source checksum; a changed page at the same URL becomes a new source |
| Query generation | `runtime/orchestrator/SearchPlannerWorker.java` (JSON, rejects short/long/duplicate queries) |
| Resumable jobs | `FileResearchTaskStore` and `ResearchTask`. Its resume pass (`resumeAfterInterruption`) has no caller; today only the orchestrator resumes tasks, and only those of its own run |
| Clustering and passages | `TopicClusterer`, `LiveOutlineProjectionBuilder`, `ActiveKnowledgeCorpusReader`, `SemanticKnowledgeIndex` |
| Section writing with citation check | `runtime/orchestrator/WriterWorker.java` |
| Document rendering | `plugin/document/DocumentPageView.java`, `DocumentPageModel.java` |
| Phase attribution | `plugin/agent/ResearchPhaseJournal.java`, `ResearchChatHistoryProjection.java` |
| Context assembly seam | `runtime/team/PhaseContextAssembler.java`, `PhaseContextPolicy.java` |
| Model slots and residency | `ModelPortSettings`, `ModelResourceScheduler`, `ModelResourcesPanel`, `ChatModelWarmup` (#104) |

## 3. Design decisions

These decisions shape the PR plan. Each one keeps the existing graph and reuses an existing asset where one exists.

### D1. Keep the graph; give every exit a user command

The graph already has a review gate at the end of Sources, Outline and Document; Concept closes with `submit-scope`
alone. The plan adds only the commands that reach the gates, in the one vocabulary of `ResearchSemanticCommands`, so
buttons, slash commands, action tags, the phase pill, the Workflow tab and the bot all see the same commands:

| New semantic command | Command type | From -> to | Replaces |
|---|---|---|---|
| `finish-sources` | `REQUEST_EVIDENCE_REVIEW` | `research/running` -> `research/waiting_approval` | the volatile `review`/`limit` tags; "End research" (today `CANCEL`) |
| `finish-outline` | `PROPOSE_OUTLINE` | `outline/running` -> `outline/waiting_approval` | nothing (today unreachable) |
| `finish-document` | `REQUEST_DRAFT_REVIEW` | `draft/running` -> `draft/waiting_approval` | nothing (today unreachable) |

Further changes:

- The pattern is the same in Sources, Outline and Document: "Finish <phase>" makes the result read-only for review;
  "Approve" records it and releases the next phase; "Request changes" returns to `running` with the user's optional
  comment, which is kept in the journal and shown in the phase instead of being dropped as today. The Concept step
  offers "Approve concept & start search" while Concept is open, and Reopen Concept once a later phase is current.
- `submit-scope` keeps dispatching `START_RESEARCH`, and approving the outline also dispatches `START_DRAFTING`, so
  the search and the writing start right after the user's approval (Q4). The second command is the approval's last
  effect in the transition journal (D3): it is dispatched after the approval's dispatch has returned, so the
  approval's hooks run first, and a crash before it is recovered on the next session open. Both jobs start from the
  transition hook (D3), never from a chat line.
- Stop never changes the state. The composer's Stop cancels the agent turn in flight. "Stop search plan" and "Stop
  writing" (on the plan checklist, the Document tab and the Workflow step) cancel the job's current call and stop the
  job; the interrupted entry stays pending. The phase stays in `running`, so Finish, the editors, "Run search plan" and
  "Write remaining sections" stay available.
- No user surface dispatches `PAUSE` or `CANCEL` any more: `pause` and `cancel` leave the tags, the slash commands and
  `run_command`. `CANCEL` is terminal and leaves only the way back. The graph keeps both edges.
- `APPROVE_DRAFT` leaves `approve` (the graph has no edge for it).
- Run offers get ids that are never semantic names: `run-plan` (today `continue` and `resume`) and `retry-query`
  (today `retry`). Semantic names resolve first, and only a name that is not semantic may match a live offer, so a
  stale offer can never shadow a command and a semantic name never hides a live offer. Every phase change clears the
  offers (D3).
- The phase pill and the plates never dispatch for the current phase, and every reopen asks for the confirmation of
  D10, whichever surface sends it.

### D2. Only the user changes phases, and the code knows who asked

Commands carry an actor (`USER` or `BOT`), the surface that sent them and a correlation id. `executeCommand` takes the
actor from its caller: tags, slash commands, plates, pill and the Workflow tab pass `USER`. There are two external
surfaces, the per-session bot control and the app-wide ChatGPT connector, which reaches every live session through
`ResearchBotSessionDirectory`. Today one gateway object serves both; PR 06 gives each surface its own gateway, which
passes `BOT` and its surface.

A small, pure actor policy decides which actor may send which command. Every command that dispatches a state command
(the forward transitions, `continue`, `request-changes` and every `REOPEN_*`) is accepted only from `USER`. From `BOT`
it is refused with "Phase changes need the user": a connected bot can read the state, run the commands that dispatch
no state command (for example `generate-outline` or `run-plan`) while its surface is enabled, and suggest a phase
change in its answer, which the user then makes (Q13). The transition journal (D3) names actor and surface of every
transition. Commands that the code dispatches inside a user action carry that action's actor and correlation id:
`START` on the first message (also via `GREETING_DONE`), `START_RESEARCH` after `submit-scope` and `START_DRAFTING`
after the outline approval. The last two are effects of their approval in the transition journal, so a crash before
them is recovered. If such a chained `START_*` is refused, the state stays in `<phase>/waiting`, and `continue`
(labelled "Start search" or "Start writing") starts it.

The host dispatches no state command of its own. Today it sends only `PAUSE` and `RESUME` for Stop and resume, which
D1 removes; `FAIL` and `BLOCK` come only from the fake backend. The policy refuses every state command from a `HOST`
actor. The model never gets a phase tool. Jobs (search plan, writing) start from the user's transition, stop on Stop
or on any phase change, and never move the project into a gate themselves: when a job is done or fails, the phase
stays `running` with a message that names the result ("Ready for review" or what failed), and the user presses Finish.

### D3. One transition hook at the existing chokepoint, recovered from a journal

`ProductiveResearchSessionResources.dispatch` (`plugin/host/ProductiveResearchSessionResources.java:412`) already
persists before it applies. It gets a typed hook with two sides, keyed by the immutable transition (id, from, to,
command, actor, surface, comment, time and the memento revision that the sender saw):

- **before apply** (may refuse with a reason; the memento is not written): a command whose revision is outdated is
  refused with "The workflow changed. Check the current state and try again.", so a double click or two surfaces never
  approve twice; the actor policy (D2); the minimum content of each gate (Q24): `submit-scope` needs at least one
  first-level concept card, a mission and a ready embedding model (Q12), `finish-outline` at least one chapter with a
  section and no outline generation in flight, `finish-document` a text for every section of the approved outline
  unless the user marked it skipped; Sources has no minimum, but its review shows the uncovered concept nodes. As the
  last step, the gate commands record what the user approves (D4): `SUBMIT_SCOPE` the concept with the Phase-1 record,
  `APPROVE_EVIDENCE` the source set, `APPROVE_OUTLINE` the outline, `APPROVE_FINAL` the manuscript. If a record fails,
  the transition is refused with the reason.
- **after apply** (never vetoes): mark stale what the approval changed (D4); cancel the left phase's jobs, clear run
  offers and the busy flag; a persisted phase divider in the chat; phase summary (D7); model warm-up (D9); start the
  phase's job (search plan on `START_RESEARCH`, writing on `START_DRAFTING`); and, as the last effect of
  `SUBMIT_SCOPE` and of the outline approval, the chained `START_RESEARCH` or `START_DRAFTING` (D1). These effects are
  queued and run in this order on one worker thread after `dispatch` returns, because `dispatch` is synchronized and
  UI commands reach it from the event thread.

**Transition journal (an outbox).** Every transition is written in a fixed order: (1) a `prepared` record in
`transitions.jsonl` (one JSON line per record, appended and flushed to disk) with the transition and the hashes of
what it approves; (2) the approval records and pins, each carrying the transition id; (3) the memento, which stores
the id of its last transition and is the commit point; (4) a `committed` record that lists the transition's effects as
open; (5) a `done` record for each effect once it has run. On session open, before any job or command runs, a recovery
pass reads the journal. A `prepared` transition that the memento does not name never happened: its records are
ignored, and the valid pin of the phase stays the previous one. A transition that the memento names without a
`committed` record is committed now. Every open effect runs again. Effects are idempotent and keyed by the transition
id: stale marks are recomputed from the pins; a divider or a phase summary that exists for the id is not written
twice; a job start resumes the stored plan or the writing tasks and never repeats finished entries; a chained
`START_*` is dispatched only while the state is still `<phase>/waiting` and no later transition was committed,
otherwise it is closed as obsolete. Memento, manifest and `phase-journal.json` are written atomically and flushed
before the rename; transition journal lines are appended and flushed, and a torn last line is ignored on read. An
unreadable transition journal or memento is moved aside and blocks the session with a repair hint, instead of
resetting the project to `scoping/new` as a corrupt memento does today. An unreadable phase journal is moved aside and
reported, and the session opens without the phase marks of older messages; today it loads empty and the next save
overwrites it. The transition journal also feeds the transition timeline of the Workflow tab.

Jobs register in one `PhaseJobRegistry` under their phase and a run token. A transition that leaves the phase cancels
them, and outcomes that carry an old run token or another phase are dropped. Each hook is its own class, so the
5,260-line `ResearchAgentSession` only wires them.

### D4. Freeze means an approved record plus one write guard for user and agent

- Every later phase reads the concept approved at `SUBMIT_SCOPE` (via the existing `FileConceptStore.approveCurrent`
  and `latestApprovedContent`) and the outline approved at `APPROVE_OUTLINE`. `APPROVE_EVIDENCE` records, for every
  accepted source, its id, its content checksum (the capture hash that `SourceAcceptanceService` already stores) and
  its status; later phases read that set plus the sources added later. `APPROVE_FINAL` records the set of section
  revisions, the quality report and every cited source with its checksum.
- **Source texts are write-once.** `FileResearchSourceRepository` refuses to change the full text or the checksum of a
  source once they are set; only a parked candidate, which has no text yet, receives its text once. A changed page at
  the same URL already becomes a new source, and that stays so. Status, relevance and comment may change under the
  rules below; the sources pin keeps the status of its moment. Citations name source, checksum and passage (D8), so
  every citation can be checked against the text the writer saw.
- The concept approval becomes a Phase-1 record. It names the working revision it froze, so the concept node ids come
  from the identity history that `commitPair` already writes. It copies the mission, the deliverable, the working
  language and the blacklist of that moment, because `scope-draft.json` keeps no history. Later phases read mission,
  deliverable and book language from this record, never from the live scope draft or the toolbar language. The
  effective frozen concept is the approved concept minus the recorded blacklist.
- One small `BookManifest` (`book.json` in the project) holds these records, one pin per phase: transition id,
  approved revision, content hash, approval time and stale notes with their reasons; the manuscript pin also lists the
  model labels of its section revisions. Pins and notes are only appended, never rewritten, and the valid pin of a
  phase is its newest pin whose transition the memento has committed (D3). Active, awaiting approval and frozen are
  derived from the state, so the manifest never contradicts it. The Workflow tab and the book's front matter read the
  manifest; a damaged manifest fails closed and is never overwritten.
- One write guard covers every path, not only the MCP tools. Concept editor, raw JSON, history restore,
  `ConceptBranchService`, conflict resolution, the concept MCP tools and the scope write paths (mission edits,
  `applyScopeUpdate`, `recordMissionIfAbsent`, the exclusion commands apart from the blacklist rule below) work only
  in `scoping/running`. Outline editor, generation and regeneration work only in `outline/running`, whichever edge led
  there. Manuscript writes work only in `draft/running`. Removing or downgrading a source of the approved set works
  only in `research/running`. The rule is the one `ResearchToolPolicy.writable` already applies. The guard covers
  content writes (titles, briefs, provenance, section text); the stale marks that the after-apply hooks set are
  exempt.
- Each artifact view takes its mode from the position of its phase:

  | Position of the view's phase | Mode and message |
  |---|---|
  | running | editable |
  | Concept in `scoping/new` | read-only, "Send a first message to start." |
  | waiting | read-only, "Starting <phase>..." with the continue command |
  | at its review gate | read-only, "Waiting for your review. Approve or Request changes." |
  | paused, blocked or failed | read-only, with the resume or retry command |
  | Document in `draft/completed` | read-only, "Final." |
  | a later phase is current | read-only, "Frozen at revision N. Reopen <phase> to change it." with the reopen button |
  | an earlier phase is current | read-only, "Approved at revision N. It opens again when the workflow reaches <phase>.", "Draft revision N, not approved yet." when only working revisions exist, or "Not started yet." |

- On `REOPEN_X` nothing is deleted and nothing is marked yet; the later pins note "<phase> reopened". The next
  approval compares what changed and marks only the dependants stale: `SUBMIT_SCOPE` marks outline chapters of changed
  or removed concept nodes (by node id), `APPROVE_EVIDENCE` marks sections that cite a source outside the new approved
  set, `APPROVE_OUTLINE` marks sections whose own entry or chapter entry (title, brief, provenance) changed. Stale items
  keep their text, show "based on concept revision 3, current revision 4" and are offered for regeneration; nothing
  regenerates by itself.
- Sources stay additive: extra research may add sources in every phase, tagged with the phase that found them, and a
  source found later can be excluded in the phase that found it.
- The blacklist is the deliberate exception (Q2). Entries added after `SUBMIT_SCOPE` can be added, edited, switched
  off or removed in every phase, and an entry of the recorded blacklist can be switched off for future searches. These
  changes filter the SERP, page and link lanes of future searches only and never change the approved concept or the
  Phase-1 record; outline generation and evidence packs keep using the blacklist recorded at `SUBMIT_SCOPE`, and
  changing that record needs Concept reopened. An entry that equals a frozen concept card is refused with the hint to
  reopen Concept; any other conflict with a concept node is reported and never resolved by changing the concept. The
  agent's `blacklist_add` only adds.
- The research brief stops being a Phase-1 result: concept and mission are (roadmap AP 10).

### D5. Phase 2 is a deterministic plan from the frozen concept, run through the one funnel

- **Plan.** On `START_RESEARCH` the plan is built from the Phase-1 record (concept revision, mission, deliverable)
  minus the current blacklist. Every concept node gets a deterministic base query (node label, parent label, up to
  three mission keywords, at most 160 characters, blacklist terms removed). The planner role may add up to three
  variants per node (the `SearchPlannerWorker` contract: JSON, rejects empty, overlong and duplicate queries); any
  failure keeps the base query. The plan is stored in `FileResearchTaskStore`, one task per entry plus a plan record
  (concept revision, entry order) in its own file, and shown as a checklist with status and accepted sources per query;
  the user can drop, edit, add and re-run queries without an extra gate.
- **Run.** A host-side queue sends the queries one at a time to the manual search port below `requestManualWebSearch`
  (-> `WebSearchApplicationService`), with its own correlation ids, so every query gets the readiness judge, SERP
  language and scope gate of the manual search. There is no separate provider integration: planned queries, gap
  searches and typed searches all use this path and the search provider chosen in the Search tab. The queue does not
  set the agent-turn flag and posts no chat line per query; the checklist shows each entry. The composer stays
  available, so the user can talk to the Sources assistant while the plan runs. Today the manual search scores with
  BM25 unless "Use AI reranker" is on (default off, read once per session); that reranker is the cross-encoder that
  Setup installs, not a generative model. The manual-search service command gets a ranking choice, a visit limit and a
  per-query source cap (`ResearchServiceCommandWire` in both modules; the ACP interface stays untouched). Planned
  queries use the ranking that the retrieval benchmark of PR 04 picks for German and mixed-language queries: the
  cross-encoder, BM25, or the cosine similarity of the multilingual embedding model; its selection keeps the top N. If
  the chosen ranking is unavailable for a query (reranker stopped, out of memory), that query is ranked by BM25 and
  its entry says "ranked by keywords, reranker unavailable"; it never falls back to engine order. Typed searches keep
  the user's setting (Q25). This delivers the "reranked sources" the owner asked for (BM25 alone accepts every hit
  with a score above 0). The host tags every accepted source with the phase and plan entry of its request, through the
  `search_request_id` that `ManualSourceAcceptancePort` already sends.
- **Coverage.** A concept node is covered once it has a set number of accepted sources from at least two domains. A
  node whose query budget is spent before that is exhausted: the plan runs no more automatic queries for it, but it
  stays uncovered. Uncovered nodes, exhausted or not, are named on the checklist, in the run outcome and at the review
  gate. Caps per query, per node and for the phase keep the run bounded.
- **Control.** "Stop search plan" cancels the current query, leaves it pending and stops the queue. At session start
  the store's resume pass (`resumeAfterInterruption`) makes an interrupted entry pending again; the plan runs again
  only on "Run search plan". Any phase change stops the queue. Two acquisitions never overlap: a `/search` or a
  `research:` loop turn started while the plan runs waits until the current entry ends. While the plan runs, the
  per-search auto review is off; the user's review action (`review-sources`) still works. Entry status maps onto
  `TaskState`: pending `SUBMITTED`, running `WORKING`, done `COMPLETED`, failed `FAILED`, skipped `CANCELED`; a re-run
  creates a new task for the same node. "Request changes" at the Sources gate shows its comment on the checklist and
  turns it into a priority entry that runs first.
- **After a jump back.** A later `START_RESEARCH` merges into the stored plan by concept node id, taken from the working
  revision that the Phase-1 record names. Entries of nodes whose base query is unchanged keep their status, edits and
  accepted sources; new nodes and nodes with a changed base query get pending entries; entries of removed or newly
  blacklisted nodes become skipped. Only pending entries run (Q23); "Search everything again" stays available.
- **Chat.** In `research/running` a typed line is a conversation with the Sources assistant, not a search query.
  "Search for X" adds a plan entry. The manual search (title bar, `/search`) keeps working in every phase.
- **Extra research later.** Outline and Document use the same queue: in the MVP one entry per gap from the Document
  tab (PR 29), later a focused plan for a chapter, a section or a gap (PR 34). New sources join the pool without
  reopening Sources and without changing approved results.
- The opt-in orchestrated mode is hidden and later removed (Q5); its planner and writer logic are extracted and reused.

### D6. The outline belongs to the user; code proposes, the model only names

- A new `FileOutlineStore` follows the `FileConceptStore` V3 pattern: chapters and sections with store-owned stable
  ids inside the records, title, brief (purpose, key points, word target), provenance (concept node, clusters,
  passages), a user-edited flag, working revisions, history and approved revisions.
- **"Generate outline"** (in `outline/running`, off the UI thread, with progress and cancel) builds the proposal
  without any model call. Chapters are the effective first-level concept nodes of the Phase-1 record, in concept
  order. Sections are their second-level nodes or, for leaf chapters, sub-clusters of the chapter's passages
  (`TopicClusterer`), titled "<chapter title>, part n" and flagged untitled until the user or the label call names
  them. Nodes below the second level are kept: their labels become key points of their section's default brief, in
  concept order, and passages assigned to them count for that section. Every passage of the approved source set and of
  sources accepted later is assigned to the nearest concept node by embedding similarity against the node's full label
  path (concept cards have names only, no description), above a calibrated threshold and with a margin over the
  second-best node. Leftover clusters that are large enough are listed as candidate chapters for the user to adopt.
  Sections with fewer than two sources are flagged thin; the flag pre-fills the manual search with the section title,
  and the focused plan follows in PR 34. Generation uses the blacklist recorded at `SUBMIT_SCOPE`. Regenerating merges
  by provenance and never overwrites titles the user edited.
- If the owner prefers pure clustering (Q6), the same store adopts the cluster projection instead; editor, freeze and
  writer stay the same.
- **The model's part** (`gemma4:e2b`, one short call per chapter, with a deterministic fallback): names candidate
  clusters and untitled sections, and writes the section briefs. **"Regenerate chapter"** takes an optional comment
  and, for a chapter built from sub-clusters, a number of sections (2 to 6): code re-clusters that chapter's passages
  into that many sections, and the comment steers labels and briefs. It keeps the chapter id; every other chapter stays
  byte-identical. Splits and merges of sections that come from concept nodes are made in the editor.
- **Direct editing like in the Concept tab**: rename, add, delete, move, indent and outdent, plus a raw JSON editor.
  The outline gets its own tab, so the tabs mirror the phases: Concept, Sources, Outline, Document, Workflow.
- `outline_save` (whole-artifact overwrite) is removed; the live projection stays an analysis view and never writes
  into the stored outline.

### D7. Phase-scoped chat context; every message stays stored

- The host transcript remains the full record. The phase journal (`ResearchPhaseJournal`, v2) keeps the phase of every
  message and one summary per phase; transitions with actor, surface, comment and approved revision live in the
  transition journal (D3).
- Each inference is assembled from fixed blocks (profile, date, language, the phase's artifact block), a
  `PHASE SUMMARIES` block for the other phases, the own-phase window and the new message. The whole history is never
  sent.
- **Compression in two steps.** First, tool rounds, repairs and nudges stay inside their turn; afterwards one `FINAL`
  entry keeps the visible answer plus a one-line receipt of the applied actions. Second, the window holds the newest
  `USER` and `FINAL` entries of the current phase within its token budget, and the last user message is always kept;
  entries that fall out of the window are compressed into one rolling summary of at most 600 tokens. The phase model
  writes it with thinking off and refreshes it after every eight dropped entries; if the call fails, the first
  sentence of each dropped user message is kept instead. Facts always come from the artifacts, so a weak summary costs
  conversation memory, never content.
- **Phase summaries** are written when the user completes a phase, and also when a phase is left by `REOPEN_*`
  (marked "left open"; the next summary of that phase replaces it). The model of the phase being left writes it
  (thinking off, JSON: decisions, preferences, open points) before the next phase's model is warmed up. If the call
  fails, a deterministic summary of the artifacts is stored instead. Facts such as mission, blacklist or chapter titles
  always come from the artifacts.
- Phase-1 machinery (scope fence, conversation policy, the current concept with its edit rules, the concept move path)
  is sent only in Concept. Later phases get a read-only concept summary. The state block shows counts per source
  status and the ten newest titles instead of the unbounded source list. Each phase's artifact block carries the latest
  change request until the next Finish.
- After a restart the plugin seeds the runtime with the current phase window and the summaries (a `seed_history`
  service command). User turns, review turns and service commands are serialised per session.
- Visible annotation: a persisted phase divider in the chat at every transition ("Phase 2: Sources, started by you").
  A badge on every bubble needs a host API change and comes later (Q26).

### D8. Writing is a resumable job of section-sized calls

- `START_DRAFTING` starts the writing job. Sections are written in outline order as tasks in `FileResearchTaskStore`,
  and each is saved as soon as it is written. At session start the resume pass makes an interrupted section pending
  again, and "Write remaining sections" continues. The job writes pending sections only; stale sections are listed
  under "Rewrite stale sections".
- Per section, code builds a bounded evidence pack from the approved source set and sources accepted later: passages
  from the section's provenance plus a semantic search on its brief, at most two per source, cut at sentence
  boundaries, numbered locally `[1]..[k]`, one number per passage; plus a book synopsis, a digest of the previous
  section and a glossary (the labels of the approved concept). Source text enters the prompt as quoted data (4.1 rule
  7).
- The writer answers in plain text with `[n]` markers, and code checks the answer in two stages. **Stage 1 (MVP):**
  unknown markers are stripped and logged; sentences and paragraphs without a marker are flagged as uncited; every
  number, unit, year, percentage and quotation of a sentence must occur in a passage that the sentence or its
  paragraph cites, after normalising decimal and thousands separators, percent signs and quotation marks, and a miss
  is flagged "unsupported number" or "unsupported quote", never silently removed. Proper names are not checked by
  capitalisation, because German capitalises every noun. `GAP:` lines become open points. A cut-off answer gets one
  continuation call. One repair sends an error list (uncited sentences, unsupported numbers and quotes), never the
  failed answer. **Stage 2 (after the MVP):** an entailment model checks whether the cited passage supports or
  contradicts the sentence; stage 1 passes a sentence that turns a decrease into an increase as long as the numbers
  match. A relevance cross-encoder cannot do this, and a multilingual entailment model needs the runtime path that PR
  04 estimates. Until then the book says that support was checked for numbers and quotes only.
- Every citation is stored as source id, source checksum, passage id and a hash of the passage text, so it stays
  checkable against the write-once source text, also after a re-embedding renames the passages (PR 20).
- Word target per call and evidence pack size are writer settings of the Document row (section 5.1). The host presets
  them by model: about 1,100 words and 8 passages of 300 tokens for `llama3.1:8b` and `gpt-oss:20b`, about 450 words
  and 6 x 250 for `gemma4:e2b` and every other model. The writer's answer budget follows the word target (about 2.3
  tokens per word plus 20 percent): 3,072 for 1,100 words and 1,200 for 450 words, plus about 1,000 reasoning tokens
  on `gpt-oss:20b`. Longer sections are split by their key points.
- A deterministic `BookAssembler` renders `document.md`: title, an honest front matter (models, date, number of
  sources, the approved concept and outline revisions, the book status, "support checked for numbers and quotes
  only"), contents, chapters, citations renumbered globally by first use, open points and a references list that marks
  sources accepted after the Sources approval. Orphaned sections are not assembled. The `[n]` badges in
  `DocumentPageView` resolve to the sources.
- **Quality report.** Every assembly also computes a deterministic report: skipped, stale and thin sections; the share
  of uncited sentences and paragraphs; unsupported numbers and quotes; open gaps; concept nodes left uncovered at the
  Sources approval; sources accepted after it; near-copies (twelve or more consecutive words of a cited passage
  without quotation marks); repeated passages across sections (word-shingle overlap); glossary terms the book never
  uses; words written against the target. The book is "complete" when no section is missing, skipped or stale, every
  paragraph cites a passage, and no unsupported number or quote, near-copy or open gap remains; otherwise it is an
  "incomplete draft" with the counts. The `finish-document` confirmation and the Document gate show the report, the
  user may approve either way (Q27), and the front matter prints the status.
- MVP revision: "Rewrite section" and "Rewrite chapter" with an optional comment, one key-point part per call. Final
  review uses the existing gate: `finish-document` makes the manuscript read-only, "Request changes" returns to
  `running` with the comment, which can open "Rewrite section" for the sections the user picks, and `approve` completes
  the book.
- The ChatGPT-like flow (mark a passage, comment, review the replacement as a diff, accept or reject) follows in M5 on
  the same section revisions.

### D9. One model per phase, independent of the chat model

Section 5 has the details: phase slots with role sub-slots, a research default preset to `gemma4:e2b`, no fallback to
the chat model, a context value per model checked against the minimum of every role bound to it, thinking per role,
answer budgets per role, and a "Models" tab in the research settings.

### D10. The Revisions tab becomes the Workflow tab

- A stepper Concept, Sources, Outline, Document. Each step shows its status (from the state) and its pin (from the
  `BookManifest`), progress (search plan, sections written), the phase summary, the latest change request, the phase
  model and the user commands legal at that moment: Finish, Approve, Request changes, Reopen, and "Stop search plan"
  or "Stop writing" while a job runs. The Concept step has no Finish or Request changes; it offers "Approve concept &
  start search" while Concept is open and Reopen once a later phase is current.
- Reopen asks for confirmation and names what may become stale and which jobs stop ("Outline and Document may become
  stale. The running search stops. Nothing is deleted."). Cancel dispatches nothing. The same confirmation guards the
  frozen banner, the pill and the plates.
- A transition timeline from the transition journal (D3): who, through which surface, when, which revision. The
  revision counter reads the persisted memento; today agent events reset it to 0.
- Revision lists live in the artifact tabs, with view and restore only while the phase is open: the Concept tab
  already has one, and the Outline and Document tabs get theirs with their stores. The Workflow steps link to them.
- The phase pill and the plates never dispatch for the current phase (once Finish has a semantic name, a click on the
  current phase would otherwise finish it), and at a gate the pill offers Approve and Request changes by name.
- One English label provider for the stepper, the pill, the chat list and the action tags. The name "Workflow" is
  Q14.

### D11. One tool set per phase, offered to the small model as constrained actions

`ResearchToolPolicy` stays the only phase x state matrix. Each phase profile derives its action union (the JSON
grammar the model answers in) from the tools the policy offers at that moment, limited to the assistant column of its
phase below. This reuses the Phase-1 pattern of `ConceptToolRounds`: one action per answer, one union variant per
action type, required fields per variant. Phase 1 addresses cards by exact names and facets and conflicts by ids.
Local indexes `[1..k]` are new for Outline; PR 04 measures their parse rate. Native tool calling stays out of the MVP.

| Phase | Writes the policy offers (only while the phase is `running`) | In-app assistant gets | Reads | When |
|---|---|---|---|---|
| Concept | today's set: `concept_add_cards`, `concept_rename`, `concept_move_leaf`, `exclude_topic`, `resolve_concept_conflict`, `offer_searches`, `scope_probe`; the legacy `concept_add` goes | the same | concept, blacklist, scope fence | as today |
| Sources | `request_research` (adds plan entries), `blacklist_add` (the agent only adds), `source_accept` and `source_park` (the `research:` loop only) | `request_research`, `blacklist_add` | search plan, coverage per node, bounded source list | PR 14, PR 15, PR 16 |
| Outline | `outline_regenerate_chapter`; later one-action rename, add, move and remove | from PR 25, each call as a proposal the user accepts or rejects | outline, coverage per chapter | PR 24 (button and policy tool), PR 25 (assistant) |
| Document | `section_rewrite` with a comment, `document_read`; `document_save` goes in PR 30 | `section_rewrite` | outline, section, writing job status | PR 29, PR 30 |
| Every phase | outside Sources, `request_research` creates an offer that the user starts | the same | `research_status`, frozen `concept_read`, bounded `source_list`, `outline_read` | PR 34 |

The concept move path and the concept edit rules are limited to Concept (PR 16). The opt-in bot endpoint keeps its
three tools (`run_command`, `session_state`, `chat_history`) and gets no phase tool; its `run_command` uses the
semantic commands and the actor rule of D2.

## 4. Context and token budget

### 4.1 Rules

1. **Context is a property of the model.** One `num_ctx` per model (presets: `gemma4:e2b` 32,768, `llama3.1:8b`
   16,384, `gpt-oss:20b` 16,384, other models 8,192), sent by every caller: the research roles and every host caller
   of `AskAiOllamaClient` (main chat, party bot, warm-up, the Models page, the Actions page and the setup test
   question). Dictation cannot send it, because it uses the OpenAI-compatible transcription endpoint (section 5.3).
   Different values for the same model make Ollama reload the runner. Each role has a minimum context (last column of
   4.2): the blocks that rule 2 never trims, plus its answer budget, plus 1,024. The Models tab shows, per model, the
   largest minimum of the roles bound to it; when the model's context is smaller, the row shows a warning and a "Raise
   to N" button. The host never raises a context by itself, because that changes the VRAM fit, and a call whose
   minimum exceeds the model's context fails before it is sent.
2. **Fail loud.** Prompt + answer budget + 1,024 tokens margin must fit into `num_ctx`, which the assembler reads from
   `SlotBinding`. The assembler trims the history window first, then optional blocks (coverage lists, the ten newest
   source titles), never the profile, the artifact block or the user message. If it still does not fit, the call fails
   with "Context too large for <model>" and nothing is sent. Research calls send `truncate=false` (PR 17), so Ollama
   refuses instead of cutting the prompt. They send no `shift`: it is a runner setting, and a different value would
   reload the model. PR 04 checks that the installed Ollama honours `truncate=false`; the guard does not rely on it.
3. **Thinking per role.** Auto resolves per model from `/api/show`: no `think` field without the thinking capability;
   for a thinking model, Off where the model accepts `false`, otherwise Low (a small table of level-only families,
   today `gpt-oss`, covers servers that do not report the accepted values). So `gemma4:e2b` gets off, `gpt-oss:20b` low
   and `llama3.1:8b` no field. Contract roles (judge, planner, labels, briefs, summaries) force off (low on `gpt-oss`),
   and the helper rows refuse a model that cannot turn thinking off. On `gpt-oss:20b` every contract role adds its
   measured reasoning (PR 04) to its answer budget, 1,000 tokens until then. An answer cut off at the limit
   (`done_reason` length) is a failure for roles with a JSON contract and a flagged result for prose; a reasoning-only
   answer already fails visibly today.
4. **Telemetry first.** Every call logs block sizes, `prompt_eval_count`, `eval_count`, `done_reason`, durations, the
   model label and the effective `num_ctx`. A warning fires when the measured `prompt_eval_count` plus the answer budget
   exceeds `num_ctx`, because only then can Ollama shift the context. Until the measurement PR calibrates it, tokens
   are estimated as characters / 3.2 (conservative for German).
5. **Stateless jobs.** Query planning, labels, briefs, chapter regeneration, section writing and summaries carry no chat
   history; they get exactly the artifact slices they need.
6. **Budgets per role.** Every row of 4.2 has its own answer budget. Roles on one slot never share one: the Sources chat
   and the review both run on `phase.research`, and `ResearchTeamAgent` takes the budget per call from the role. Helper
   roles keep the budgets and settings they have today.
7. **Source text is data.** Page and source text (review context, page judge, labels and briefs, evidence packs, the
   reviser) enters a prompt only inside a delimited block that marks it as quoted material, never as instructions. A
   model answer can only take the actions of its phase's action union (D11): none of them changes a phase, and each is
   visible and reversible (plan entries, blacklist entries, section revisions). Writer output passes the checks of D8
   before it is stored.

### 4.2 Budget per phase and role (tokens)

| Phase / role | Model | `num_ctx` | Prompt blocks (cap) | Answer | History | Minimum `num_ctx` |
|---|---|---|---|---|---|---|
| Concept chat turn, including up to 4 tool rounds | gemma4:e2b | 32,768 | profile about 6,500 (20.8k characters, unchanged until measured); date and language 230; state 450; fence and policy 1,000; compact concept 2,500; summaries of other phases up to 1,800 (only after a jump back); window 4,000; in-turn tool feedback up to 6,000 (one full concept copy plus the answers of earlier rounds); repair note 1,500 (PR 17 stops echoing the failed answer; until then up to 4,200); user 1,000. Cap about 25,000, typical about 12,000 | 4,096 | Own phase, newest first | about 26,100 |
| Scope probe generator and advice chooser | gemma4:e2b | 32,768 | system 700; compact concept 1,500; instruction 300. Cap 2,500 | generator 8,192 (existing setting "Topic generation token budget", live-tuned); chooser 1,024 (existing setting "Question choice token budget") | none | about 11,700 |
| Sources chat turn | gemma4:e2b | 32,768 | profile 660; date and language 230; concept summary 1,200; blacklist 200; coverage per node 600; run status 300; change request 300; summaries of other phases up to 1,800 (Concept; after a jump back also Outline and Document); window 3,000; user 1,000. Cap about 9,300 | 1,536 | Own phase; other phases as summaries | about 8,300 |
| Post-search review (the user's review action) | gemma4:e2b | 32,768 | profile 660; date and language 230; concept summary 1,200; review context 4,500 (12 sources x 1,200 characters, existing setting); newest own-phase entries 1,500; instruction 300. Cap about 8,400 | 3,072 in Sources (summary only), 4,096 in Concept (summary and suggestions); both provisional until PR 04 measures them | Own phase, 1,500 | about 11,000 in Sources, 12,000 in Concept |
| Query expansion | gemma4:e2b | 32,768 | system 350; node path 200; mission keywords 100; base query 60; queries already run 200; blacklist 100. Cap about 1,000 | 256 | none | about 2,300 |
| Page readiness judge | gemma4:e2b | 32,768 | system 150; page excerpt 2,000 | 24 (in code) | none | about 3,200 |
| SERP layout repair | gemma4:e2b | 32,768 | system 300; SERP digest 4,000 | 2,000 (existing browser setting `aiLayoutResolver.maximumOutputTokens`) | none | about 7,400 |
| Cluster and section label | gemma4:e2b | 32,768 | system 250; concept path 150; 3 passages x 250; language. Cap about 1,200 | 160 | none | about 2,400 |
| Chapter briefs, regenerate one chapter | gemma4:e2b | 32,768 | system 450; book synopsis 300; chapter 100; sections by local index 200; 6 passages x 250; blacklist 100; comment 300. Cap about 3,000 | 150 per section plus 100 (700 for four sections); a chapter with more than six sections is briefed in two calls | none; other chapters stay in the store | about 5,100 |
| Outline chat turn | gemma4:e2b | 32,768 | profile 800; date and language 230; concept summary 1,200; outline (ids and titles) 2,000; coverage per chapter 400; change request 300; summaries of other phases up to 1,800; window 3,000; user 1,000. Cap about 10,700 | 1,536 | Own phase; other phases as summaries | about 9,900 |
| Section writer | llama3.1:8b | 16,384 | rules 550; synopsis 300; chapter and section brief 400; neighbour titles 60; previous-section digest 300; glossary 200; evidence 8 x 300. Cap about 4,300 | 3,072 (up to 1,100 words) | none | about 11,500 with its continuation |
| Section writer, small preset | gemma4:e2b | 32,768 | the same blocks with evidence 6 x 250. Cap about 3,400 | 1,200 (up to 450 words) | none | about 6,900 with its continuation |
| Section writer, opt-in | gpt-oss:20b | 16,384 | as for llama3.1:8b. Cap about 4,300 | 4,096 including about 1,000 reasoning (provisional) | none | about 13,600 with its continuation |
| Rewrite section part (PR 29) | Document model | its value | the section writer's blocks plus the current text of the part (up to the per-call word target) and the comment 300. Cap about 7,200 on llama3.1:8b, 4,900 on the small preset | the writer's budget; a cut-off part gets one continuation without the old text | none | about 11,800 on llama3.1:8b, 7,200 on the small preset (each with its continuation) |
| Revise passage (PR 33) | Document model | its value | passage up to the per-call word target; two paragraphs on either side about 1,000; comment 300; cited evidence 2,000; rules 550. Cap about 6,500 (5,100 on the small preset) | the writer's budget | none | about 10,600 (7,300 on the small preset) |
| Document chat turn | Document model | its value | profile 610; date and language 230; compact outline 1,500; job status and current section 300; change request 300; summaries of other phases up to 1,800; window 3,000; user 1,000. Cap about 8,700 | 1,536 (3,072 on gpt-oss) | Own phase; other phases as summaries | about 8,300 (9,900 on gpt-oss) |
| Phase summary | the model of the phase being left | its value | system 300; the phase's USER and FINAL entries 5,000; artifact facts 500. Cap about 5,800 | 600 (plus about 1,000 reasoning on gpt-oss, provisional) | n/a; deterministic fallback | about 7,500 (8,500 on gpt-oss) |
| Rolling summary of older own-phase entries | the phase's model | its value | system 250; previous rolling summary 600; dropped entries up to 3,000. Cap about 3,900 | 600 | n/a; first sentences as fallback | about 5,500 |

Every "window" budget includes the rolling summary of older own-phase entries (at most 600 tokens, D7). Summaries of
other phases count 600 for each phase that has one. The minimum leaves out the window and the coverage lists, because
rule 2 trims them first. The word-to-token ratios of the writer rows (about 2.3 tokens per German word) are
provisional until PR 04 measures them.

`gemma4:e2b` gets 32k although most prompts stay far below it: its KV cache is small (estimated 0.2 to 0.4 GiB per slot
at 32k), so the margin is cheap. Its weak long-context recall is a reason not to fill the context, not a reason to
allocate less. With `llama3.1:8b` at its 16,384 preset every Document role fits, but a Concept turn does not.

### 4.3 What is stored and what is sent

| | Stored | Sent to the model |
|---|---|---|
| Every chat message | yes, host transcript; phase and kind in the journal | only within the own-phase window |
| Tool rounds within a turn | technical log | collapsed into one `FINAL` entry with an action receipt |
| Older own-phase messages | yes | compressed into one rolling summary of at most 600 tokens |
| Other phases | yes | only their phase summary, plus the approved artifacts |
| After a restart | yes | the current phase window and the summaries are seeded into the runtime |
| After a jump back | yes | the reopened phase gets its own previous window plus the summaries of the later phases, including a "left open" summary of the phase the user just left |

## 5. Models

### 5.1 One model per phase in the settings (the owner's addition)

- **Slots.** Four phase slots named after the phase ids, `phase.scoping`, `phase.research`, `phase.outline` and
  `phase.draft` (shown as Concept, Sources, Outline, Document), with role sub-slots that inherit the model and the
  thinking mode from them, never the answer budget: `phase.research.planner`, `phase.outline.labels`,
  `phase.outline.briefs`, `phase.draft.writer` and `phase.<id>.summary`. A `default` slot (preset `gemma4:e2b`)
  closes the chain. The helper slots `scope`, `judge` and `layout-repair` stay app-wide, because a reasoning model
  would break their 24 to 8,192-token budgets. The slots of the opt-in orchestrator stay until it is removed;
  `conversation` goes. The chat slot follows the live phase id, not the profile: a turn in phase X uses `phase.X`, also
  when the profile registry falls back to its neutral profile (Outline until PR 25, and the opening greeting, which
  runs in Concept). The narrator and the visualizer use the active phase slot too, never the chat model.
- **Resolution.** Exact slot, then the parent (strip the last segment), then `default`, then a clear error: "No model
  for the Document phase. Set it under Research settings -> Models." The host then checks the resolved model against
  the installed models (the cached list that the Models tab also reads); a missing model fails before the call with
  "Model <name> for the <phase> phase is not installed. Install it under Models -> Setup." A research session never
  falls back to the chat model. Today an unset slot uses `ai.mainModel`, which every chat tab rewrites (also when a tab
  is restored); without a chat model the session starts, but every model role is unavailable
  (`askai-app/.../localmodels/LocalInferenceConfigurationSnapshotProvider.java:70-74`). Both go: the inference
  descriptor is written from the `default` slot.
- **Presets.** Written once, when the Models tab first opens: `phase.draft` = `llama3.1:8b` only if it is installed;
  otherwise it stays empty and resolves to `default`. Until the tab exists (PR 09), slots, contexts, thinking
  modes and budgets are set in the Model resources panel (PR 03, PR 08), which lists every slot of `ModelPortSettings`.
- **Answer budgets are per role** (4.1 rule 6). The phase row's "Chat answer budget" applies to that phase's chat
  turn only; `OllamaModelPort` fills it, and the slot's thinking mode, when a request leaves them unset. Every other
  role sends its own budget. The Document row also holds the writer settings (word target per call, evidence pack
  size), preset by model; the writer's answer budget follows the word target (D8).
- **Per model:** context (4.1 rule 1), checked against the largest minimum of the roles bound to it.
- **UI.** A "Models" tab in the research settings, after "General", built with `ComicForm.fit` and added to
  `ResearchSettingsFitTest`:
  - "Model per phase": a "Default" row (model and thinking) above the rows Concept, Sources, Outline, Document; label
    left; model, thinking (only the values the model supports) and chat answer budget in the same row; the Document row
    also has the word target per call and the evidence pack size. A model that is not installed shows "Install..." in
    its row, which opens Models -> Setup with that model preselected; installing itself stays there.
  - "Context per model": one row per model in use with its context and the largest role minimum; when the context is
    smaller, a warning and a "Raise to N" button in the same row; a warning when one model would get two values.
  - "Helper roles": scope check, page judge, SERP repair, each "Same as default" unless set; their answer budgets stay
    where they are today (Runtime tab, code, browser settings).
  - The note "App-wide. Independent of the chat model." and a warning when the Document model cannot stay loaded
    next to the default model (from the model sizes; measured values once PR 04 exists).
  - The chat answer budget per phase replaces the global "Answer budget" row of the Runtime tab, which PR 03 removes.
    The Model resources panel stays the expert view of all slots, capacities and the memory budget.
- **Plumbing.** The plugin does not handle model names. The host provides the row editor through the plugin API (a
  host service in `agent-model-runtime-api`, implemented in `askai-app` over `ModelPortSettingsRepository`), and the
  settings page reaches it through a host-service lookup that `AgentSettingsContribution.createPluginSettingsComponent`
  gets in PR 09. The plugin passes slot ids and labels only. `SlotBinding` reports the context and a display label of
  the bound model, `ChatResult` carries the same label, and `ModelPort.warm(slot)` loads a slot's model with its
  context. Labels are text only: they go into logs, messages and records, and no code branches on them (an amendment
  to ADR 0001, section 3.2). `ResearchTeamAgent` takes the chat of the active phase through `RoleChatFactory`.
- **Scope.** App-wide for the MVP; a per-book override can follow (Q15).
- **Switching on 16 GB.** User phase changes are the only planned switch points. Entering a phase whose model differs
  warms that model through `ModelPort.warm(slot)` and shows "Loading <model>..." in the chat. Research tabs stop
  warming the composer's model (#104), and a model bound to a slot is never unloaded by a chat tab. A setting that
  unloads the previous phase's model is not planned yet; the measurements of PR 04 decide whether it is needed.
- **Traceability.** Every written section stores the model label, thinking level, prompt hash and evidence ids (PR 26),
  and the technical log names the model of every call (PR 01). The chat record keeps the author "Agent"; a model label
  on every bubble needs the host API change of Q26.

### 5.2 Recommended defaults

| Phase / role | Default | Alternative | Thinking | `num_ctx` | Answer | Why |
|---|---|---|---|---|---|---|
| Concept | gemma4:e2b | llama3.1:8b with its context raised to 32,768, only if PR 04 shows that it fits beside gemma4:e2b, and only if the live gate drops below 80% or more than 10% of answers fail to parse | off | 32,768 | 4,096 | Interactive and latency-bound; live-tuned on gemma; grammar-constrained JSON |
| Sources, planner | gemma4:e2b | llama3.1:8b | off | 32,768 | chat 1,536, review 3,072, planner 256 | Short schema-bound calls; the deterministic base query covers every failure |
| Helper roles (scope, judge, layout repair) | gemma4:e2b | llama3.1:8b; never gpt-oss:20b | off, mandatory | 32,768 | 24 to 8,192 (existing settings) | A reasoning model silently disables the judge |
| Outline (chat, labels, briefs) | gemma4:e2b | llama3.1:8b if the briefs are poor | off | 32,768 | chat 1,536, labels 160, briefs 150 per section plus 100 | Structure is code; the model words one chapter at a time |
| Document (writer, rewrite, chat) | llama3.1:8b if installed, otherwise gemma4:e2b (preset written when the Models tab first opens) | gpt-oss:20b with thinking low, after the A/B | none on llama3.1:8b, off on gemma4:e2b, low on gpt-oss | 16,384; gemma4:e2b keeps 32,768 | writer 3,072 (1,200 on gemma4:e2b), chat 1,536 (writer 4,096 and chat 3,072 on gpt-oss) | Should fit beside gemma4:e2b (about 12 to 14 GB together, to be measured), no reasoning tokens, German officially supported |
| Phase summaries | the model of the phase being left | gemma4:e2b | off (low on gpt-oss) | its value | 600 | Already loaded at the transition |
| Embeddings | the winner of the retrieval benchmark (PR 04); until then nomic-embed-text, the Setup preset | multilingual candidates from the Ollama library, for example nomic-embed-text-v2-moe, bge-m3, embeddinggemma, snowflake-arctic-embed2 or granite-embedding:278m | n/a | the model's own; passages must fit it | n/a | Required for scope gate, outline assignment and evidence packs; a change re-embeds the project (PR 20) and recalibrates the thresholds of the scope gate and the outline assignment |
| Ranking of planned queries | the strategy that wins the retrieval benchmark (Q25) | the installed cross-encoder (English), BM25, or embedding similarity; a multilingual cross-encoder only after the runtime path of section 5.4 | n/a | n/a | n/a | Decides which pages are visited; BM25 is the labelled fallback while the chosen strategy is unavailable |

Single-request generation speed on the target machine (`docs/ollama-parallel-inference-benchmark-2026-10-30.md`):
`gemma4:e2b` 192 to 223 tokens/s, `gpt-oss:20b` 160 to 163, `llama3.1:8b` 126 to 147. A full section call therefore
takes about 6 s on `gemma4:e2b` (1,200 tokens), about 22 s on `llama3.1:8b` (3,072) and about 26 s on `gpt-oss:20b`
(4,096), plus prefill.

Why not `gpt-oss:20b` as the default writer: it needs about 12 GB and cannot stay resident beside `gemma4:e2b` on
16 GB. Because extra research is allowed in every phase and the research roles run on `gemma4:e2b`, every gap search
while writing would unload and reload up to 14 GB, a cost never measured on this machine. It also always reasons, so
its answer budget must include the reasoning. If it wins the A/B clearly, the plan supports it: gap research is then
queued and run in batches between chapters.

Not evaluated, but worth one measurement: `gemma4:12b` (about 8 GB) might serve every phase as one model without any
swap.

### 5.3 Ollama server settings to verify on the RTX 5080

- `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0` (halves the KV cache).
- `OLLAMA_MAX_LOADED_MODELS` unset (Ollama's default is 3 per GPU) or at least 3, because `gemma4:e2b`,
  `llama3.1:8b` and the embedding model are all Ollama models. The benchmark's example used 1.
- `OLLAMA_NUM_PARALLEL` one global value of 1 or 2; it is read at server start and cannot follow the phase.
- `OLLAMA_CONTEXT_LENGTH` equal to the context of the dictation model (32768 for `gemma4:e2b`), because the
  transcription endpoint cannot send `num_ctx`. After PR 03 every other caller sends `num_ctx`, so the global value
  never reaches `llama3.1:8b` or `gpt-oss:20b`.
- Optional, after measuring that both models fit: an Ollama memory budget in Model resources, so AskAI may run calls
  to both models at the same time. Taking turns between two resident models needs no budget.

### 5.4 Measure before fixing defaults

PR 04 runs a protocol on the target machine and checks the results in, without machine paths:

1. Does `gemma4:e2b` think when `think` is omitted, with and without `format`? Parse rate per contract (N = 20),
   including the chapter briefs with local section indexes, with thinking omitted vs off.
2. Characters per token for English and German per model, tokens per German word in prose, and how cached prefixes are
   counted.
3. Total GPU memory, measured with `nvidia-smi` while a scripted probe alternates `gemma4:e2b` research calls and
   `llama3.1:8b` writer calls, with the reranker and two browsers open (PR 32 repeats it during the real writing run):
   `gemma4:e2b` at 32k together with `llama3.1:8b` at 16k and at 32k, q8_0 KV, `OLLAMA_NUM_PARALLEL` 1 and 2.
   `size_vram` shows only Ollama models.
4. `load_duration` for every switch between `gemma4:e2b`, `llama3.1:8b` and `gpt-oss:20b`.
5. How the chat template renders several, and non-leading, system messages (the assembler sends several today).
6. The Phase-1 live gate on `gemma4:e2b` (N = 10) as the baseline that later context changes must not undercut; it
   also records the four open checks of roadmap AP 1.
7. **Retrieval benchmark.** 24 known-item queries on one topic of the owner's choice, in three groups of eight: German
   queries for German passages, German queries for English passages, and English queries for English passages. Each
   query has one known target passage in a fixed corpus of about 300 passages captured from German and English pages;
   the corpus stays in the benchmark's local data directory, because it is third-party text, while queries, target ids
   and results are checked in. For the current preset and each multilingual candidate of 5.2: Recall@5, Recall@10 and
   mean reciprocal rank per group; how many of 50 passages labelled by the owner land under the right concept node
   when each goes to the nearest node by cosine against its full label path (the assignment rule of PR 22); embedding
   time per 100 passages; GPU memory; the longest passage in tokens against the model's context. For the ranking of
   planned queries, the same queries against lists of 20 search results (title and snippet) each: BM25, the installed
   cross-encoder and embedding similarity, scored by whether the target lands in the visited top N, and how often a
   strategy selects nothing (`NO_SEMANTIC_MATCHES`). Rules: among the candidates that fit beside the phase models and
   whose German-to-English Recall@10 is not below the current preset's, the one with the best mean Recall@10 over the
   three groups becomes the embedding preset; planned queries get the ranking strategy with the best mean hit rate
   over the three groups.
8. **A multilingual path for the local runtime.** What AskAI's local model runtime needs to run a cross-encoder with
   an XLM-R architecture and a SentencePiece tokenizer (tokenizer, model mapping, a reference test), recorded as an
   effort estimate, together with the hit rate of one multilingual cross-encoder on the queries of item 7, scored once
   with a reference implementation outside AskAI. If it clearly beats the chosen strategy, a follow-up PR adds the
   path; the same path would carry the entailment model of D8 stage 2. The `CandidateReranker` seam and the HTTP
   reranker client stay model-neutral either way, so today's runtime limit does not shape the retrieval design.
9. **Truncation.** Whether the installed Ollama honours `truncate=false` on chat and embedding calls: an overlong
   probe must fail instead of answering.

Writer A/B (PR 31): three identical sections with the same 8 x 300 evidence packs (they fit the 32k of `gemma4:e2b`),
written by `gemma4:e2b` (450-word parts), `llama3.1:8b` (1,100 words) and `gpt-oss:20b` (1,100 words, thinking low),
plus one complete sample chapter of three to four sections per model, written by the writing job from the same outline
and evidence, because single sections say little about repetition, transitions and the line of argument; shuffled and
anonymous for the owner's blind rating; time, tokens, citation validity, uncited-paragraph rate, the counts of the
quality report and language drift recorded. Rule: `llama3.1:8b` stays the Document preset if it wins the sample
chapter and at least two of the three sections against `gemma4:e2b`; `gpt-oss:20b` only if it clearly beats both.

## 6. Milestones and pull requests

Every PR targets `develop`, keeps the build green and can be reviewed on its own. Labels: **MVP** means the first
complete book cannot be produced without it; **Rule** implements an agreed rule (the owner's target picture) that the
first book does not need; **Plus** is quality, measurement or cleanup. Sizes: S up to about 300 changed lines, M up to
about 1,000, L more. "Done when" lists the acceptance criteria the reviewer checks.

### 6.1 Overview

| PR | Title | Milestone | Depends on | Size | Label |
|---|---|---|---|---|---|
| 01 | Model call telemetry | M0 | | M | MVP |
| 02 | UTF-8 in every project store | M0 | | M | MVP |
| 03 | Explicit context per model, thinking per role, answer budgets | M0 | 01 | M | MVP |
| 04 | Measurement protocol and retrieval benchmark on the target GPU | M0 | 01 | L | MVP |
| 05 | User exits for every phase; walk-through test | M1 | | M | MVP |
| 06 | Transition hook with actor and crash recovery; phase-change hygiene | M1 | 05 | L | MVP |
| 07 | Concept freeze; concept and mission are the Phase-1 result | M1 | 06 | M | MVP |
| 08 | One research model per phase, independent of the chat model | M1 | 03, 06 | M | MVP |
| 09 | "Models" tab in the research settings | M1 | 08 | M | Rule |
| 10 | Revisions tab becomes the Workflow tab; English strings | M1 | 06, 07 | M | Rule |
| 11 | Documentation in line with the code | M1 | 05, 06 | S | Plus |
| 12 | A blacklist that filters in every phase; embedding model required | M2 | 04, 06, 07 | M | MVP |
| 13 | Concept query planner | M2 | 01, 03, 08 | M | MVP |
| 14 | Search plan queue and the approved source set | M2 | 04, 06, 07, 12, 13 | L | MVP |
| 15 | Sources chat is a conversation; dead `SCOPE_PROPOSAL` path removed | M2 | 14, 16 | M | MVP |
| 16 | Phase-gated context blocks; bounded state block | M2 | 06, 07 | M | MVP |
| 17 | Phase-tagged history window and overflow guard | M2 | 01, 03 | M | MVP |
| 18 | Phase summaries, rolling summary, restart seeding | M2 | 06, 08, 10, 15, 17 | L | Rule |
| 19 | "Block this site" and blacklist flags in later phases | M2 | 12, 14 | S | Plus |
| 20 | Knowledge readiness and fast clustering | M3 | 02, 04 | M | MVP |
| 21 | Outline store with stable ids and freeze | M3 | 02, 06, 07, 16 | L | MVP |
| 22 | Generate outline from concept and clustered sources | M3 | 07, 14, 20, 21 | L | MVP |
| 23 | Outline tab and editor | M3 | 21 | M | MVP |
| 24 | Chapter labels, briefs and single-chapter regeneration | M3 | 03, 08, 22, 23 | M | MVP |
| 25 | Outline assistant with one-action changes | M3 | 08, 16, 21, 24 | M | Plus |
| 26 | Manuscript store with section revisions | M4 | 14, 21 | M | MVP |
| 27 | Evidence packs per section | M4 | 01, 02, 22, 26 | M | MVP |
| 28 | Section writer with checked citations | M4 | 03, 08, 27 | M | MVP |
| 29 | Writing job; rewrite a section or a chapter with a comment; one-click gap search | M4 | 06, 14, 16, 26, 28 | L | MVP |
| 30 | Book assembler, citations, quality report and Markdown export | M4 | 26, 28 | L | MVP |
| 31 | Writer A/B with a sample chapter and the Document default | M4 | 04, 08, 28, 29 | M | MVP |
| 32 | First-book acceptance run and report | M4 | every MVP PR | M | MVP |
| 33 | Revise a marked passage with a comment | M5 | 29, 30 | L | Rule |
| 34 | Gap research from Outline and Document | M5 | 14, 15, 29 | M | Rule |
| 35 | Store robustness | M5 | 02 | M | Plus |
| 36 | DOCX and PDF export | M5 | 30 | M | Plus |
| 37 | Legacy cleanup | M5 | 15, 29 | M | Plus |

**Order.** The longest chain has eleven PRs: 05 -> 06 -> 07 -> 12 -> 14 -> 22 -> 27 -> 28 -> 29 -> 31 -> 32; PR 04,
08, 13, 16, 20, 21 and 26 join on the way. M0, the context PRs 16 and 17 and the outline editor run beside it. PR 05
goes first: `ResearchAgentSession` (5,260 lines) is a merge hotspot, and the other PRs rebase on it. PR 04 runs early:
its retrieval benchmark picks the embedding preset and the ranking of planned queries before PR 12, 14 and 20 build on
them, and its Phase-1 live gate is the baseline that PR 07, 12, 16 and 17 must not undercut. M1, M2 and M3 each end
with a live checkpoint on real data before the next milestone starts, so recovery, search and retrieval quality are
measured before the outline and the writing depend on them. After PR 07 the walking skeleton exists: a user can walk
all four phases to `draft/completed` with a first message, one concept card, an empty outline and an empty document;
PR 07, 12, 21 and 29 add the matching fixtures to `ProductiveBookJourneyTest`. PR 08 is MVP: the planner, the writer
and the Document model of the first book run on its phase slots. PR 09 can follow; until then slots, contexts,
thinking modes and budgets are set in the Model resources panel (PR 03, PR 08). PR 31 runs before PR 32, so the
acceptance run writes with the Document model that the sample chapters chose.

### 6.2 M0 Honest foundations

**PR 01 Model call telemetry** (M, MVP)
- Change: `MainModelChatResult` carries the token usage, the finish reason and the length of the thinking text, which
  `PortMainModelChat` drops today. `ChatResult` gets the display label of the model that answered and the load and
  prompt-evaluation durations; `OllamaChatCompletion` parses `load_duration` and `prompt_eval_duration`. A new
  `PromptBlockMeter` counts the named blocks of the final request: those of `PhaseContextAssembler` (profile, date,
  language, state, fence, concept, history) and those that `ResearchTeamAgent` appends (tool feedback, repair note,
  greeting instruction, user message). `ResearchTeamAgent` and the orchestrator log one technical
  line per call: phase, role, slot, model label, block sizes, `prompt_eval_count`, `eval_count`, `done_reason`,
  durations and the effective `num_ctx` (from `SlotBinding`). Warnings fire on `done_reason=length`, when the estimate
  plus the answer budget exceeds `num_ctx` minus 1,024, and when the measured `prompt_eval_count` plus the answer
  budget exceeds `num_ctx`. The request bodies stay unchanged in this PR.
- Done when: a fake port that returns usage (5,000, 300), finish reason `length` and a model label produces exactly
  one log line with all fields plus a warning naming the role; the request goldens are byte-identical; the block sizes
  add up to the characters of the assembled messages.

**PR 02 UTF-8 in every project store** (M, MVP)
- Change: a public helper `Utf8Properties.load(File)` in `research-knowledge-processing` (UTF-8 bytes into
  `Properties.load(Reader)`, the pattern of `ResearchProjectMetadataStore`) replaces the ISO-8859-1 readers of
  `FileResearchProjectRepository`, `FileSourceProcessingQueue` and `FileLiveOutlineProjectionStore`.
  `FileTopicSnapshotStore` writes UTF-8 and reads through it (RA-P007). In the plugin, `StoreIo.loadUtf8Properties`
  delegates to it for `FileResearchSourceRepository` and `FileVisualizationStore`. `FileScopingProjectionStore`,
  `FileResearchBriefStore`, `FileArtifactStore`, `FileConceptStore` (two readers) and `ImportedSourceStore` read
  through the same helper; `ImportedSourceStore` stores its properties into a `StringWriter` instead of decoding them
  as ISO-8859-1. Their values are ASCII today, so no data changes. The writers keep their hand-built content. The
  derived stores bump their schema numbers so projections rebuild. `FileResearchSourceRepository.update` runs under a
  per-source lock and reports an I/O error as an error, not as a conflict. No repair of text that is already
  corrupted: dev projects are recreated (no backward compatibility).
- Done when: in every store, `Grüße „Zitat“ – ß` plus an emoji survives three read-modify-write cycles; no
  `Properties.load(InputStream)` or ISO-8859-1 remains in a project store (grep in the PR description); two threads
  updating one source lose no field.

**PR 03 Explicit context per model, thinking per role, answer budgets** (M, MVP)
- Change: `ModelPortSettings` gets a context per model (`model.<name>.contextTokens`, presets in section 4.1), a
  thinking mode per slot (Auto, Off, Low, Medium, High) and a chat answer budget per slot, which PR 08 applies to the
  phase slots; the per-slot context goes, and `ModelResourcesPanel` edits the context per model and the thinking mode
  and answer budget per slot until PR 09 adds the Models tab. `OllamaModelPort` sends the model's `num_ctx`
  on every call; a request's context is only a minimum, and a larger one fails with `CONTEXT_TOO_LARGE` before the
  HTTP call. It fills a request's thinking mode (when `DEFAULT`) and answer budget (when 0) from the slot's settings on
  every call, as it does for the context today. Research roles leave both unset, with two exceptions: the contract
  roles of 4.1 rule 3 send off (the port sends low where a model cannot switch thinking off, as `gpt-oss:20b`), and
  roles with their own budget send it. Helper roles keep their existing budgets and settings: page judge 24 (in code),
  scope generator and chooser (Runtime tab), SERP repair (browser settings); a slot setting never overrides them.
  `ASKAI_AGENT_MAX_OUTPUT_TOKENS`, `ResearchTeamAgent.setMaxOutputTokens` and the global "Answer budget" row of
  `ResearchRuntimeSettingsPanel` go; `ResearchTeamAgent` takes the budget per
  call from the role (chat turn, tool round, review). Auto thinking resolves as in 4.1 rule 3. An answer cut off at
  the limit fails for roles with a JSON contract and is flagged for prose. `num_ctx` is added inside
  `AskAiOllamaClient` for chat, generate and load, looked up per model, so every host caller sends the same value:
  main chat, party bot, warm-up, the Models page Load button, the Actions page and the setup test question.
  `SlotBinding` reports the context and a display label of the bound model, and `ModelPort` gets `warm(slot)`, which
  loads the slot's model with its context.
- Done when: request goldens show `num_ctx` 32,768 and `think` false for a research call on `gemma4:e2b`, and 32,768
  for the main chat, the warm-up and the Models page Load button on the same model; `gpt-oss:20b` gets `think` low and
  `llama3.1:8b` no `think` field; contract roles never send a thinking value other than off (low on `gpt-oss:20b`);
  the scope generator keeps its 8,192-token budget; an oversized request fails without an HTTP call; a settings change
  applies to the next call without a restart; live, alternating chat and research calls no longer reload the runner
  (`ollama ps`), and a dictation followed by a research call on `gemma4:e2b` causes no reload (the research call's
  `load_duration` stays near zero).

**PR 04 Measurement protocol and retrieval benchmark on the target GPU** (L, MVP)
- Change: opt-in probes in `research-benchmark` that run only in the benchmark task: the role contracts (scope probe,
  post-search review, query planner, chapter briefs with local section indexes, writer, page judge) N = 20 per model
  with thinking omitted vs off; characters per token for English and German and tokens per German word in prose; total
  GPU memory with `nvidia-smi` per context and parallel setting, with the reranker and two browsers open, including
  `llama3.1:8b` at 32,768; `load_duration` for every model switch; how a prompt with several system messages is
  rendered; the Phase-1 live gate N = 10, which also records the four open checks of roadmap AP 1; the retrieval
  benchmark with its three query groups, the effort estimate for a multilingual runtime path and the truncation check
  (section 5.4, items 7 to 9). The results go to `docs/ollama-model-budget-measurements.md` with the Ollama version
  and server settings and without machine paths. A one-line follow-up sets the embedding preset in `SetupActions` and
  the ranking of planned queries by the rules of section 5.4. The PR is MVP because PR 12, PR 14 and PR 20 build on
  these two choices: retrieval quality is measured before the book generation is built.
- Done when: the normal test run stays model-free; the report answers every question of section 5.4 and has a row for
  every default of sections 4.1, 4.2 and 5.2; the retrieval part gives Recall@5, Recall@10 and the mean reciprocal
  rank per group and candidate, and the hit rate per ranking strategy; a default that lands before the report stays
  marked provisional until a one-line follow-up adopts the measured value.

### 6.3 M1 Walking skeleton

**PR 05 User exits for every phase; walk-through test** (M, MVP)
- Change: `finish-sources`, `finish-outline` and `finish-document` in `ResearchSemanticCommands` (D1), and on every
  hand-maintained surface: the action tags in `ResearchAgentSession`, the slash commands in `ResearchChatCommands` and
  the bot's `RUN_COMMAND_DESCRIPTION` in `ResearchBotSessionTools`. English labels in `ResearchPlaybook.actionLabel`:
  "Approve concept & start search" (the `submit-scope` label moves there from `availableActionTags`, and the
  `/submit-scope` description follows), "Finish sources", "Approve sources & continue to Outline", "Finish outline",
  "Approve outline & start writing", "Finish document", "Approve final book", "Request changes...", and `continue` per
  phase ("Start search", "Start writing"). `request-changes` takes an optional comment at all three gates, from the
  slash or bot arguments or from a one-line dialog on the tag; PR 06 carries it in `ResearchCommand`. The `end` run
  offer dispatches `finish-sources` instead of `cancel()`; `cancel` and `pause` leave the tags, the slash commands and
  `run_command`; `APPROVE_DRAFT` leaves `approve`. Approving the outline also dispatches `START_DRAFTING`, as
  `submit-scope` already dispatches `START_RESEARCH`. Run offers get ids that are never semantic names, `run-plan`
  (today `continue` and `resume`) and `retry-query` (today `retry`), also in `RUN_COMMAND_DESCRIPTION`; semantic names
  resolve first, and only a name that is not semantic may match a live offer. The phase pill and the Revisions plates
  never dispatch for the current phase. The fake backend stops switching phases by itself. A new
  `ProductiveBookJourneyTest` drives `ResearchAgentSession.executeCommand` over the productive resources with a fake
  runtime.
- Done when: the journey test reaches `draft/completed` with a first chat message, one concept card, then
  `submit-scope`, `finish-sources`, `approve`, `finish-outline`, `approve`, `finish-document`, `approve`; every new tag
  is offered exactly in the state where its command is allowed, and a slash command outside that state is refused with
  a reason and changes nothing; no tag, slash command, `run_command` name or run offer dispatches `CANCEL` or
  `PAUSE` (the composer's Stop follows in PR 06), and `end` leads to
  `research/waiting_approval`; `run_command` accepts the new names and rejects `cancel` and `pause`; with a live
  `run-plan` offer in `research/running`, `run_command run-plan` runs it and `run_command continue` is rejected with
  the valid names; choosing the current phase in the pill changes nothing, and in `research/running` the pill offers
  no `finish-sources`; at the Sources gate, "Request changes" with a comment returns to `research/running`.

**PR 06 Transition hook with actor and crash recovery; phase-change hygiene** (L, MVP)
- Change: `ResearchCommand` carries an actor, the sending surface, a correlation id, the memento revision that the
  surface showed and the user's optional comment (D2). `executeCommand` takes the actor from its caller: tags, slash
  commands, plates, pill and the Workflow tab pass `USER`; each external surface (bot control, ChatGPT connector) gets
  its own gateway, which passes `BOT` and its surface. A pure actor policy refuses every state command from `BOT` with
  "Phase changes need the user" (Q13) and every state command from `HOST`, and `RUN_COMMAND_DESCRIPTION` lists only
  the commands a bot may run; a command with an outdated revision is refused with the message of D3.
  `ProductiveResearchSessionResources.dispatch` calls a typed `PhaseTransitionHook` with the immutable transition:
  `beforeApply` may refuse with a reason (the memento is not written, the reason is shown), `afterApply` queues its
  effects, which run in order on one worker thread after `dispatch` returns (D3). The transition journal of D3
  (`transitions.jsonl`) records `prepared`, `committed` and `done` per transition and effect, and the memento stores
  its last transition id. Journal lines are appended and flushed, and a torn last line is ignored on read; the memento
  and `phase-journal.json` are written atomically and flushed before the rename (PR 07 does the same for the
  manifest). An unreadable transition journal or memento is moved aside and blocks the session with a repair hint
  instead of resetting the project to `scoping/new`, and an unreadable phase journal is moved aside and reported; this
  handling and the atomic phase-journal write moved here from PR 35. The recovery pass of D3 runs on session open
  before any job or command. The chained `START_RESEARCH` and `START_DRAFTING` are the approval's last effects and
  carry its actor and correlation id; if one is refused, the state stays in `<phase>/waiting`. A `PhaseJobRegistry`
  holds the running jobs per phase and run token. First hooks: cancel the agent turn, any search and every registered
  job of the left phase on every phase change including `REOPEN_*`; clear run offers and the in-flight flag; drop late
  outcomes with an old run token or another phase; a persisted phase divider in the chat through
  `AgentConversationSink.appendInfoMessage` ("Phase 3: Outline, started by you"), written once per transition id. A
  `request-changes` comment is posted in the chat as the user's note, without starting a model turn. The composer's
  Stop cancels the agent turn and any search without a state change (D1). In productive mode `busy` means "agent turn
  in flight", not "state is running". `maybeStartResearchTurn` is restricted to `research/running` until PR 15 deletes
  it.
- Done when: a refusing test hook leaves state and memento unchanged, writes no `committed` record and shows its
  reason; a fault-injection test stops the dispatch after each write step (`prepared`, a test record that stands in
  for the pins of PR 07, PR 14, PR 21 and PR 26, memento, `committed`, each effect) and reopens the session: state,
  test record and dividers are consistent, every effect of a committed transition has its result exactly once, and a
  transition that never committed leaves no valid record; after a kill between `APPROVE_OUTLINE` and `START_DRAFTING`,
  reopening dispatches `START_DRAFTING` once; two Approve clicks that carry the same revision approve once and refuse
  the second with the message of D3; a corrupt transition journal or memento keeps its file, shows the repair hint and
  starts nothing; a corrupt phase journal is kept and reported, and the session opens; a torn last journal line is
  ignored; `reopen-concept` during a running turn leaves the composer idle and the late outcome re-arms nothing; the
  dividers survive a restart; `busy` is false in `outline/running` when nothing runs; Stop during a turn leaves the
  state in `running`; after `submit-scope` the transition journal shows `SUBMIT_SCOPE` and `START_RESEARCH`, both with
  actor `USER`; a table test covers every command, actor and surface: a `HOST` state command is refused, and a `BOT`
  `approve` is refused with "Phase changes need the user", also through the ChatGPT connector with the connector on;
  `/request-changes too long` at the Outline gate puts "too long" into the journal and the chat and starts no model
  turn.

**PR 07 Concept freeze; concept and mission are the Phase-1 result** (M, MVP)
- Change: the `BookManifest` of D4 (`book.json`, append-only pins, atomic UTF-8 writes flushed before the rename,
  fail-closed load). The Concept tab shows the mission as one editable row above the tree. A `beforeApply` hook
  refuses `SUBMIT_SCOPE` on a concept without first-level cards or with an empty mission; this replaces the
  no-content-gate test (`ScopingBriefApprovalTest.anEmptyOrBlankScopeNeverBlocksTheUsersSubmitScope`) and reverses
  that rule for these two structural cases, and the `submit-scope` tag shows the reason while it is disabled. The last
  `beforeApply` step calls `FileConceptStore.approveCurrent` and appends the Phase-1 record as a concept pin under the
  transition id: approved revision, content hash, the working revision it froze (so the node ids come from its
  identity history), and copies of mission, deliverable, working language and blacklist; a failed approval refuses the
  transition. The `afterApply` hook names the revision in the divider. A reader for the effective frozen concept
  (approved revision minus the recorded blacklist, with mission, deliverable and language from the record) is the only
  way later phases see Phase 1. The brief approval branch of `approveScopingBriefAndContinue` goes (roadmap AP 10).
  One write guard (the rule of `ResearchToolPolicy.writable`) stands in front of every Phase-1 write:
  `ConceptBranchService`, the Concept tab handlers (`ResearchBriefViewContribution`, `ConceptPaperView` for raw JSON),
  history restore, conflict resolution, the concept tools and the scope write paths of `ResearchAgentSession` (mission
  edits, `applyScopeUpdate`, `recordMissionIfAbsent`, the exclusion commands). Outside `scoping/running` only the
  blacklist edits of D4 pass (Q2): entries added after `SUBMIT_SCOPE` can be added, edited, switched off or removed,
  and a recorded entry can be switched off for future searches; an entry that equals a frozen concept card is refused
  with the hint to reopen Concept, and any other conflict with a concept node is reported and never resolved by
  changing the concept. The Concept tab takes its mode from the D4 table. The reopen confirmation of D10 is built here
  and guards every surface that sends a reopen: frozen banner, phase pill, plates and, later, the Workflow tab.
  `REOPEN_CONCEPT` appends the note "Concept reopened" to the later pins; nothing is deleted.
- Done when: submit on an empty concept or an empty mission is refused with a reason; a greeting as the first message
  shows as the mission and can be edited before submit; after submit the manifest's concept pin equals
  `latestApprovedContent` (revision and hash), names its working revision, and its node ids equal that revision's
  identity; the divider names the revision; editing the working concept or the blacklist afterwards does not change
  what later phases read, and switching the session language does not change the working language of the Phase-1
  record; in Sources, Outline and Document every concept or mission write (UI, tool, service) is refused, a later
  blacklist entry can be added, edited, switched off and removed, and switching off a recorded entry changes the next
  search but not the Phase-1 record; a reopen from the pill asks first; after a reopen and an edit the next submit
  creates revision N + 1, and without an edit it keeps revision N and the divider says so; the fault-injection test of
  PR 06 passes with the concept pin; `ProductiveBookJourneyTest` still reaches `draft/completed`.

**PR 08 One research model per phase, independent of the chat model** (M, MVP)
- Change: the slots of section 5.1 in `ResearchModelSlots` and `ModelPortSettings`; the phase slots join
  `ModelPortSettings.DEFAULT_SLOTS`, so the Model resources panel lists them. The chat slot follows the live phase id,
  not the profile: a turn in phase X uses `phase.X`, also when the registry falls back to the neutral profile (Outline
  until PR 25, and the opening greeting, which runs in Concept); `ResearchTeamAgent` takes the chat of the active phase
  through `RoleChatFactory`. Resolution exact -> parent -> `default` -> clear error, in `CentralModelDirectories`; no
  fallback to `ai.mainModel`; a resolved model that is not installed fails before the call with the error of 5.1, and a
  helper slot (`scope`, `judge`, `layout-repair`) whose model cannot turn thinking off fails before the call with "The
  <role> needs a model that can turn thinking off." The
  provider writes the inference descriptor from the `default` slot and the call timeout, never from the chat model, so
  `hasInference()` holds in every research session; a chat model change no longer calls `refreshInference`. A restored
  chat tab no longer writes its model into `ai.mainModel`. `ChatModelWarmup` does not warm the composer model in
  research tabs and never unloads a model bound to a slot. The narrator and the visualizer call the model port on the
  active phase slot; research sessions no longer use `OllamaAgentInferencePort`. The writer settings of the Document
  slot (word target per call, evidence pack size) get their presets by model (D8). The `afterApply` hook warms the next
  phase's model through `ModelPort.warm(slot)` and shows "Loading <model>...".
- Done when: with only `phase.draft` set to `llama3.1:8b`, Document turns use it while the other phases use the
  default `gemma4:e2b`; with only `phase.outline` set, an Outline chat turn uses it; changing a chat tab's model
  changes no research request (recorded-request test); with no chat model set, research roles still work; a slot whose
  model is not installed fails before the call with the install hint; with `default` set to `gpt-oss:20b` and no helper
  override, a page judge call fails before it is sent with the thinking reason; opening a research tab loads no composer
  model.

**PR 09 "Models" tab in the research settings** (M, Rule)
- Change: a host service in `agent-model-runtime-api` (implemented in `askai-app` over `ModelPortSettingsRepository`
  and the list of installed models) reads and writes slot model, thinking, chat answer budget, the writer settings and
  the context per model, reports the largest role minimum per model, refuses with the reason in the row a model that
  cannot turn thinking off for a helper row (or for the Default row while a helper row follows it), and opens Models ->
  Setup with a model
  preselected. `AgentSettingsContribution.createPluginSettingsComponent` gets a host-service lookup
  (`agent-workspace-api`); the session-aware page takes the service from the session's host context; without the
  service, the Models tab shows "Model settings are not available." The plugin's new `ResearchModelsSettingsPanel` has
  the three forms of section 5.1, built with `ComicForm.fit`, as a tab after "General"; it passes slot ids and labels
  only. The `phase.draft` preset is written when the tab first opens (`llama3.1:8b` only if it is installed).
  "Install..." in a row only opens Models -> Setup; no new pull code.
- Done when: `ResearchSettingsFitTest` covers the tab at 540 to 1,600 px and after shrinking, with a fake service that
  offers three models; choosing `llama3.1:8b` for Document persists `phase.draft` and the next Document call uses it; a
  model whose context is below its largest role minimum shows the warning and "Raise to N", and nothing raises it by
  itself; choosing `gpt-oss:20b` for the page judge, or for Default while the judge follows Default, is refused with
  the reason; "Install..." opens Models -> Setup with the model preselected, grep finds no model pull in the research
  plugin, and PR 09 adds none to the host; the plugin code contains no model names; all strings are English.

**PR 10 Revisions tab becomes the Workflow tab; English strings** (M, Rule)
- Change: `ResearchStateView` becomes the Workflow tab of D10: a stepper with status (from the state) and pin (from
  the manifest), the progress and the Stop command of every job in the `PhaseJobRegistry` (PR 06), so the search plan
  (PR 14) and the writing job (PR 29) appear without further changes, phase model, latest change request, phase
  summary (once PR 18 exists) and the legal user commands per phase; the transition timeline from the transition
  journal; links to the revision lists of the artifact tabs. The revision counter reads the memento. At a gate the
  pill names Approve and Request changes. One English label provider serves stepper, pill, chat list and tags. Every
  user-visible string in `research-agent-ui-plugin` becomes English, including the `isGerman()` branches of labels and
  tooltips: for example `ResearchSourcesView` (labels, buttons, tooltips and the status, relevance and reliability
  names), `ResearchOutOfScopeSky`, `ResearchPhaseAccessory`, `ResearchChatCommands`, `ResearchOutlineView`,
  `ResearchPhaseToolbarContribution`, the scoping approval texts, the web search line and card and the thinking and
  problem texts of `ResearchAgentSession`, and the tag tooltip of `ScopingSupportView`. Agent texts that follow the
  session language are not UI text.
- Done when: every legal user command of the current state (the `ResearchSemanticCommands` names that resolve now) is
  reachable from the tab, and `PAUSE`, `BLOCK`, `FAIL` and `CANCEL` never appear; reopen needs a confirmation and Cancel
  dispatches nothing; the timeline shows actor, surface and revision; the revision label equals the memento revision
  after agent events; every label is English in a German session; a grep in the PR description finds no German
  literal in the research plugin except parser keywords for German user input and agent texts that follow the session
  language; the layout tests pass.

**PR 11 Documentation in line with the code** (S, Plus)
- Change: `docs/research-state-machine.md` describes the 4-phase graph and the new commands; ADR 0001 gets its role
  table and, in section 3.2, the amendment that `SlotBinding` and `ChatResult` carry model labels as text only; stale
  javadoc (`SUBMIT_SCOPE` leading to Outline, bot control "on by default", `outline_save` "legacy only");
  `docs/research-control-mcp.md` lists the tools that `ResearchToolPolicy` offers; `docs/research-bot-control.md` and
  `docs/research-chatgpt-connector.md` list the semantic names that `run_command` accepts (only commands that dispatch
  no state command), the run offer ids, that bots cannot change phases (D2), and that bot control is off by default;
  `mvp-roadmap.md` points to this plan and marks AP 2 and AP 3 done.
- Done when: no document names a command or state the graph does not have; the documented tool list equals the policy
  for every phase and state; the documented `run_command` names equal the commands that the actor policy accepts from
  `BOT`.

Checkpoint after M1 (no PR): walk all four phases live with a first message, one concept card and the empty outline
and document; kill the app once during an approval and once in `research/waiting`, and check that the recovery of PR
06 completes both.

### 6.4 M2 From concept to sources

**PR 12 A blacklist that filters in every phase; embedding model required** (M, MVP)
- Change: the sky's "+ Add" (`ResearchPhaseAccessory`, `ResearchOutOfScopeSky`) uses the same host command as the
  `exclude_topic` tool instead of `addScopeExclusion`, so a typed term becomes an excluded facet and an OUT anchor
  (`ResearchScopeDraft`, `ScopeSweepPlanAssembler`), with the concept-conflict check. A typed term is also kept as a
  plain exclusion (`ResearchScopeDraft.getExclusions()`), and an entry that is a host name (a dot and no space) blocks
  that domain and is matched against the URL host. Only these typed entries feed a deterministic term and domain
  filter on SERP title, snippet and URL, which runs before rerank and again after fetch, so the blacklist also works
  without the semantic gate; facets from the conversation stay with the semantic gate (roadmap AP 6, section 10). The
  sky is shown in every phase and follows the blacklist rule of D4 (Q2): after `SUBMIT_SCOPE`, later entries can be
  added, edited, switched off and removed, and a recorded entry can be switched off for future searches; the Phase-1
  record never changes. Setup (`SetupWizardPanel`, `AskAiSetupActions`) lists the embedding model as required ("needed
  for Sources filtering and the Outline"), with the preset that PR 04 chose; installing uses the existing installer
  (the wizard's "Download model" and Models -> Setup), with no new pull code. A `beforeApply` check on `SUBMIT_SCOPE`
  refuses with "Sources need an embedding model. Open Models -> Setup." while none is ready, and "Check again"
  re-resolves it without a new chat. Without one, the outline drawer and the exclusion warning name the concrete
  reason. New strings are English.
- Done when: a candidate matching a typed term is rejected in the SERP, page and link lanes
  (`SearchScopeControlFunnelTest`); a typed host name never reaches the visit stage; an excluded facet without a typed
  term is filtered only by the semantic gate; no search runs unfiltered without saying so; in Sources an added entry
  rejects matching hits in the next query, switching it off lets them through again, and the Phase-1 record stays
  unchanged; without an embedding model `submit-scope` is refused with the setup hint, and after installing it
  succeeds in the same chat; `ProductiveBookJourneyTest` provides a ready fake embedding model and still reaches
  `draft/completed`.

**PR 13 Concept query planner** (M, MVP)
- Change: prompt, schema and reader of `SearchPlannerWorker` move into a `QueryPlanner`, which the worker delegates to.
  A new service command `plan_queries` on the existing prompt channel (`ResearchServiceCommand`,
  `ResearchServiceCommandWire` in both modules; the ACP interface stays untouched), handled like `handleGenerateProbes`.
  Request: language, book title, the concept nodes with path and parent, the base query per node, the queries already
  run, the blacklist and a maximum per node. One planner call per node on `phase.research.planner` with thinking off and
  256 answer tokens; one repair, then the deterministic base query. It never throws.
- Done when: `SearchPlannerWorkerTest` is unchanged and green; the wire round-trip passes in both modules; with a fake
  model every node gets its base query plus at most the maximum of valid variants (3 to 160 characters, no duplicates,
  no blacklisted term); invalid output keeps the base query; queries are in the requested language.

**PR 14 Search plan queue and the approved source set** (L, MVP)
- Change:
  - Plan: an `afterApply` hook on `START_RESEARCH` builds the plan of D5 from the Phase-1 record and stores it in
    `FileResearchTaskStore`: one task per entry (node, query, origin base or variant, accepted sources) and a plan
    record (concept revision, entry order) in its own file next to the tasks, because `orchestrator/run.json` belongs
    to the orchestrated run. Status maps onto `TaskState` (pending `SUBMITTED`, running `WORKING`, done `COMPLETED`,
    failed `FAILED`, skipped `CANCELED`); re-run and retry create a new task for the same node. On session open,
    before the queue can start, `resumeAfterInterruption` returns entries left `WORKING` to `SUBMITTED`, or to `FAILED`
    when their retries are spent. A later `START_RESEARCH` merges into the stored plan by concept node id (D5);
    "Search everything again" stays available.
  - Queue: a host-side queue runs one entry at a time through the manual search port below `requestManualWebSearch`
    (-> `WebSearchApplicationService`) with its own correlation ids: readiness judge, SERP language and scope gate as
    in the manual search. It does not set the agent-turn flag and posts no chat line per query; the plan checklist in
    the Sources tab shows each entry's status and accepted sources, and the user can drop, edit, add and re-run
    entries. The composer stays available while the plan runs. "Stop search plan" on the checklist cancels the current
    query, leaves it pending and stops the queue; the composer's Stop stops only the agent turn. A `/search` or a
    research-loop turn (`research:`, and until PR 15 any typed line in `research/running`) started while the plan runs
    waits until the current entry ends. While the plan runs, the per-search auto review is off. "Run search plan"
    continues; any phase change stops the queue. The queue registers in the `PhaseJobRegistry` with its progress and
    its Stop.
  - Wire: the manual search request and its service command (`ResearchServiceCommandWire` in both modules) get the
    ranking choice, the visit limit and the per-query source cap. Planned queries use the ranking fixed after PR 04
    and visit the top N; when it is unavailable, that query is ranked by BM25 and its entry says so (D5). If PR 04
    picked embedding similarity, a `CandidateReranker` over the embedding port joins `SearchResultReranker` and
    `Bm25CandidateReranker`. The "Use AI reranker" setting applies to typed searches only, and its label says so
    (Q25). There is no separate provider integration: the queue uses the manual search path and the provider of the
    Search tab. The host tags each accepted source with the phase and plan entry of its request, through the
    `search_request_id` that `ManualSourceAcceptancePort` already sends, and `ResearchSourceRecord` stores both.
  - Coverage and caps: coverage per node (covered, exhausted or open) and caps per query, node and phase (D5); new
    rows in the Search settings tab ("Sources per node", "Queries per node", "Sources for the Sources phase") through
    `ComicForm.fit`. At the end one outcome names uncovered nodes and offers `finish-sources`; the state stays
    `research/running`. At the review gate the Sources view and the gate message list the uncovered nodes.
  - Gate: "Request changes" at the gate shows its comment on the checklist and adds it as a priority entry that runs
    first. The last `beforeApply` step of `APPROVE_EVIDENCE` appends the approved source set (id, content checksum and
    status of every accepted source) as a sources pin under the transition id; a failed record refuses the transition.
    `FileResearchSourceRepository` refuses to change the full text or the checksum of a source once they are set (D4);
    only a parked candidate receives its text once. A source of the approved set can be excluded or downgraded only in
    `research/running`; elsewhere the Sources view and the tools refuse with "Reopen Sources to remove an approved
    source." Sources found later carry their phase tag and can be excluded in the phase that found them. New strings
    are English.
- Done when: after `submit-scope` the plan lists only nodes of the approved revision and no blacklisted node; queries
  run one after another and two acquisitions never overlap; while the plan runs, the composer stays available and a
  typed line or `research:` starts no second acquisition, and "Stop search plan" stops the queue; the caps hold;
  accepted sources carry query, node and phase; planned queries keep the recorded working language after a language
  switch; a node whose queries bring no accepted source ends exhausted and is listed as uncovered at the gate; the
  wire round-trip passes in both modules; with "Use AI reranker" off, a planned query uses the ranking fixed after PR
  04 and a typed search BM25; with the reranker stopped, a planned query is ranked by BM25 and labelled so; after an
  app kill during a query, that entry is pending again and "Run search plan" continues with it; `finish-sources` or a
  reopen during a query cancels it; the approved set equals the sources accepted at that moment, with their checksums,
  and can be read after a restart; overwriting the text of an accepted source is refused, and a changed page becomes a
  new source; after approval, excluding an approved source in `outline/running` is refused, and excluding a source
  found in Outline works; reopen Concept, rename one leaf node, submit: only that node's entries run, and done entries
  of unchanged nodes are not searched again; `ResearchSettingsFitTest` stays green.

**PR 15 Sources chat is a conversation; dead `SCOPE_PROPOSAL` path removed** (M, MVP)
- Change: the runtime's `scopeProposal` wire, the `SCOPE_PROPOSAL` event type and its mapping are deleted. In the
  plugin session, `handleScopeProposal`, `autoAdvanceTowardsResearch`, `maybeStartResearchTurn`, the field
  `researchQuestion` and its restore from the project metadata are deleted too, with `ResearchScopeCommitService` and
  `ConfirmedResearchScope`, which only that chain uses. The runtime's proposed and confirmed question and aspects
  (`reconcileConfirmedScope`) go with it, and the state block no longer shows them. The `run-plan` and `retry-query`
  offers drive the plan queue. The runtime's prompt routing drops `|| hostIsInResearchRunning()`, so a typed line in
  `research/running` reaches the Sources assistant. Its block (plan, coverage, the latest change request) joins the
  seam of PR 16, and its action union holds `request_research` (adds plan entries) and `blacklist_add` on the add-only
  path of PR 07. `research:` outside `research/running` is refused before any browser call, with a pointer to
  `/search`. `ContributionToolInvoker` maps "Unknown tool" after a phase change to a state-changed result. The
  orchestrated-mode checkbox is hidden (Q5).
- Done when: grep finds no `SCOPE_PROPOSAL` or `scopeProposal` in main code, and the plugin's `ResearchAgentSession`
  has no `researchQuestion`; a typed line in `research/running` gets an answer and starts no acquisition; "search for
  X" adds a plan entry; `research: x` in `outline/running` is refused without a browser call.

**PR 16 Phase-gated context blocks; bounded state block** (M, MVP)
- Change: scope fence, conversation policy, the current concept with its edit rules and the concept move path are sent
  only in Concept; later phases get a read-only concept summary rendered from the effective frozen concept of PR 07
  (approved concept cards, mission, deliverable and the recorded blacklist) and one artifact
  block per phase as a seam: PR 15 adds the Sources block (plan, coverage), PR 21 the Outline block and PR 29 the
  Document block (job status, current section). Each block carries the phase's latest change request until the next
  Finish. The state block shows counts per source status and the ten newest titles instead of every source. A
  profile's action union is the live `ResearchToolPolicy` list limited to the D11 row of its phase; `source_accept`
  and `source_park` stay for the `research:` loop only, and the legacy `concept_add` goes.
- Done when: snapshot tests per phase show which blocks are sent; a Document prompt contains no fence and no concept
  edit rules; "move this paragraph to chapter 2" in Document does not start the concept move path; with 200 sources the
  state block stays under 450 tokens; no action union outside the `research:` loop contains `source_accept`.

**PR 17 Phase-tagged history window and overflow guard** (M, MVP)
- Change: the runtime `ChatMessage` gets phase and kind (`USER`, `FINAL`, `TOOL_ROUND`, `REPAIR`, `NUDGE`); tool
  rounds collapse into one `FINAL` entry with an action receipt (D7). `PhaseContextAssembler` builds the window from
  the newest own-phase `USER` and `FINAL` entries within the budgets of section 4.2, keeps the last user message, and
  trims optional blocks before it fails with "Context too large for <model>". Research calls send `truncate=false`, a
  new `ChatRequest` flag that `OllamaModelPort` writes into the request body; no `shift` is sent. If PR 04 finds that
  the installed Ollama ignores the flag, the guard alone protects, as designed. The parse repair of
  `ResearchTeamAgent` stops echoing the failed answer (improvement 6); the Phase-1 live gate (PR 04) must not drop.
  Access to the TeamAgent history is serialised per session.
- Done when: deterministic assembler tests per phase (blocks, budget, the last user message never dropped); a
  40-turn Phase-1 conversation stays within budget; an oversized prompt fails visibly and sends nothing; request
  goldens show `truncate` false for research calls; a review turn and a chat turn never interleave history.

**PR 18 Phase summaries, rolling summary, restart seeding** (L, Rule)
- Change: one summary per phase in `ResearchPhaseJournal`. A `summarize_phase` service command runs after
  `SUBMIT_SCOPE`, `APPROVE_EVIDENCE`, `APPROVE_OUTLINE` and `APPROVE_FINAL`, and when a phase is left by `REOPEN_*`
  (marked "left open"; the next summary of that phase replaces it). It runs on `phase.<id>.summary` of the phase being
  left, before the next phase's model is warmed up (JSON: decisions, preferences, open points); on failure a
  deterministic summary of the artifacts is stored. The `PHASE SUMMARIES` block goes into every prompt of another
  phase. The rolling summary of D7 compresses own-phase entries that fall out of the window. On session open the
  plugin seeds the runtime with the current phase window and the summaries through a `seed_history` service command,
  read from `ChatSessionHistoryReader`. The summaries appear on the Workflow steps.
- Done when: the Sources chat sees the Concept summary and none of the Concept messages; after `reopen-outline` from
  `draft/running` the Outline prompt contains a Document summary and none of the Document messages; after a 60-turn
  Concept chat the window stays within budget and older turns appear as one rolling summary; a restart reproduces the
  same prompt; a failed summary call stores the deterministic summary; the host transcript still holds every message.

**PR 19 "Block this site" and blacklist flags in later phases** (S, Plus)
- Change: "Block this site" on search hits and in the Sources view adds a host-name entry (PR 12). Blacklist hits are
  counted in the coverage report. The Outline and Document views flag chapters and sources that an entry added after
  `SUBMIT_SCOPE` would hit; the user removes them by reopening.
- Done when: a blocked domain never reaches the visit stage; the approved concept revision is unchanged; a later entry
  that matches a chapter flags it without changing the outline; blacklist hits appear in the coverage report.

Checkpoint after M2 (no PR): a live Phase-2 run on a German topic of the owner's choice with the telemetry of PR 01
and the ranking chosen by PR 04, to check the caps, the corpus size that PR 20 must handle, the share of German and
English sources found and the open live checks of roadmap AP 5.

### 6.5 M3 Outline

**PR 20 Knowledge readiness and fast clustering** (M, MVP)
- Change: `TopicClusterer` uses normalised vectors, a cached similarity matrix, Lance-Williams average-linkage updates
  and a nearest-neighbour chain: average linkage is reducible, so the chain finds the same merges in O(n²) time and
  memory, while a scan of all pairs for every merge would stay cubic. Ties break by the lower index. Above a corpus
  limit (a setting, default 6,000 passages, about 144 MB as a full float matrix) the best-ranked passages per source
  are kept and the rest is reported. Progress and cancel run through `SharedTopicDiscovery.refreshPinned`. One
  embedding model, the one PR 04 chose, is pinned per project by fingerprint; a change re-embeds instead of mixing
  vectors and renames every passage id (`PassageSegmentation`), which citations survive through their passage text
  hash (D8). Passages longer than the embedding model's context are split before embedding, and embedding calls send
  `truncate=false`. Failures reach the outline drawer instead of only the error stream. Clustering runs only on
  request in `outline/running`. The background topic runner (#42) and its setting are removed; topic answers outside
  Outline read the last stored snapshot.
- Done when: a parity test gives the same clusters as today's algorithm on three seeded corpora without ties; 6,000
  vectors of 768 dimensions cluster in under 60 s (slow-tagged test); the drawer shows progress, Cancel and failure
  messages; a corpus above the limit reports how many passages it left out.

**PR 21 Outline store with stable ids and freeze** (L, MVP)
- Change: `FileOutlineStore` on the `FileConceptStore` V3 pattern (working copy, history, approved revisions,
  expected-revision writes, UTF-8; the ids live inside the records). Immutable domain records for chapters and
  sections: store-owned id, parent, order, title, brief (purpose, key points, word target), provenance (concept node,
  clusters, passages), user-edited flag, stale flag with its reason. The outline is writable whenever the state is
  `outline/running`, whichever edge led there; there is no separate lock flag. Hooks: the last `beforeApply` step of
  `APPROVE_OUTLINE` approves the working copy and appends it as an outline pin under the transition id, and a failed
  approval refuses the transition; after a `SUBMIT_SCOPE` that follows a reopen, chapters of changed or removed
  concept nodes are marked stale by node id (taken from the working revision the Phase-1 record names).
  `finish-outline` is refused while no chapter has a section (Q24). The `outline` artifact is rendered from the store,
  never from the live projection; `outline_save` is removed, and a read-only `outline_read` is offered in every phase.
  The Outline block (ids, titles, coverage per chapter) joins the seam of PR 16. `ProductiveBookJourneyTest` creates a
  one-section outline before `finish-outline`.
- Done when: `finish-outline` on a chapter without a section is refused; ids stay stable across three edit, approve
  and reopen cycles; writes outside `outline/running` are
  refused, and writes after `REQUEST_OUTLINE_CHANGES` or after `APPROVE_EVIDENCE` into `outline/running` are allowed;
  the approved revision is readable after a restart; rebuilding the live projection never changes the stored outline;
  non-ASCII titles round-trip; after a reopen of Concept and one renamed node, only that node's chapter is stale; the
  journey test still reaches `draft/completed`.

**PR 22 Generate outline from concept and clustered sources** (L, MVP)
- Change: a `ConceptSeededOutlineBuilder` builds the proposal of D6 without a model call: chapters from the effective
  first-level concept nodes of the Phase-1 record in concept order; sections from second-level nodes or sub-clusters
  (titled "<chapter title>, part n" and flagged untitled); nodes below the second level become key points of their
  section's default brief, and passages assigned to them count for that section; passages from the approved source set
  plus sources accepted later, minus excluded sources, with the blacklist recorded at `SUBMIT_SCOPE`. Every passage
  goes to the nearest node of the approved concept revision, by cosine against outline anchors whose text is the
  node's full label path (book title, parent labels, label); the scope fence anchors stay as they are. A passage is
  assigned only above a threshold and with a margin over the second-best node; both are calibrated on the corpus of
  the M2 checkpoint and recorded in the PR, and `topicAssignmentThreshold` is not reused. Leftover clusters that meet
  `minimumPassagesPerTopic` and `minimumDistinctSourcesPerTopic` are listed as candidates. Each section gets a default
  brief: purpose from the node's label path and the mission, key points from the best passages, and a word target from
  the deliverable's target length (a page counts as 300 words, provisional), spread over the sections and weighted by
  their number of sources, or 400 to 1,100 words without a target length (Q7). Sections with fewer than two sources
  are flagged thin; the flag pre-fills the manual search with the section title, and the focused plan follows in PR
  34. "Generate outline" in `outline/running` runs off the UI thread with progress and cancel and merges by provenance
  without overwriting edited titles. The existing `generate-outline` command (the outline button, `executeCommand`,
  the bot's `run_command`) runs this generation and is refused outside `outline/running`; `finish-outline` is refused
  while it runs. The unwired `OutlineProposalBuilder` is reused or deleted, together with the domain
  `researchQuestion` it carries.
- Done when: the same input gives a byte-identical outline in concept order; no model is called; a renamed section
  keeps its title after regeneration; thin sections are flagged; a third-level node appears as a key point of its
  section's default brief; candidates are listed, not inserted; the anchors come from the approved revision; an
  excluded source contributes no passage; `finish-outline` during a generation is refused.

**PR 23 Outline tab and editor** (M, MVP)
- Change: an Outline tab between Sources and Document. A tree editor with rename, add, delete, move, indent and
  outdent, and a raw JSON editor with Save and Discard (the `ConceptPaperView` pattern: unsaved text survives a
  refresh, strict parser, expected-revision save). Known ids keep their provenance; new entries get fresh ids. Edits
  set the user-edited flag. The "Generate outline" button moves here from the Document tab's outline drawer. The tab
  takes its mode from the D4 table, shows the latest change request above the editor and lists the outline revisions
  with view and restore while the phase is open.
- Done when: rename, reorder, delete and add round-trip and keep the ids and provenance of untouched entries; invalid
  JSON, duplicate ids and empty titles are rejected with a reason; a background refresh never discards unsaved text;
  the tab is read-only after `finish-outline`.

**PR 24 Chapter labels, briefs and single-chapter regeneration** (M, MVP)
- Change: an `OutlineBriefWorker` on `phase.outline.labels` and `phase.outline.briefs` (thinking off). A label call per
  candidate cluster and per untitled section returns title and summary; a brief call per chapter addresses its
  sections by local index and returns purpose and three to five key points (150 answer tokens per section plus 100; a
  chapter with more than six sections is briefed in two calls). Code checks that every index occurs once; one repair
  with an error list; otherwise the default brief stays, and the chapter shows "Brief not generated" with a retry.
  "Regenerate chapter" takes an optional comment and, for a chapter built from sub-clusters, a number of sections (2 to
  6): code re-clusters that chapter's passages into that many sections, the comment steers labels and briefs, and the
  chapter keeps its id. Splits and merges of sections that come from concept nodes are made in the editor (PR 23).
  `ResearchToolPolicy` offers the same operation as `outline_regenerate_chapter` in `outline/running`. "Add suggested
  chapter" in the Outline tab adopts a labelled candidate cluster. `finish-outline` is refused while a regeneration
  runs.
- Done when: with a fake chat that returns invalid JSON twice the default brief remains, "Brief not generated" shows
  and the outline stays approvable; regenerating a sub-cluster chapter with 4 sections gives 4 sections in that chapter
  only, and every other chapter stays byte-identical; each call stays within its budget (telemetry); labels are short
  noun phrases in the book language; an adopted candidate becomes a chapter with fresh ids; `finish-outline` during a
  regeneration is refused.

**PR 25 Outline assistant with one-action changes** (M, Plus)
- Change: an Outline profile on `phase.outline` (prompt about 800 tokens, compact outline, summaries) with
  `outline_read`, `outline_regenerate_chapter` and a change tool that takes one action per call (rename, add, move or
  remove; the `ConceptToolRounds` pattern). Every agent change, a regenerated chapter included, is a proposal: the user
  sees old and new side by side and accepts or rejects it.
- Done when: an Outline turn contains no concept tools; a proposed change or regenerated chapter is applied only after
  the user accepts it.

Checkpoint after M3 (no PR): generate the outline on the corpus of the M2 checkpoint with the embedding model chosen
by PR 04; record the clustering time, the calibrated threshold and margin and the share of assigned passages, and
regenerate one chapter.

### 6.6 M4 First book

**PR 26 Manuscript store with section revisions** (M, MVP)
- Change: `FileManuscriptStore`: per section id (from the outline) a list of revisions with text, a citation map from
  each local marker to source id, source checksum, passage id and passage text hash (D8), the flags of the claim check
  (filled by PR 28), gaps, model label, thinking level, prompt hash, evidence ids, the fingerprint of the outline
  entry and of its chapter entry it was written from (title, brief, provenance), and time; status pending, written,
  failed, skipped, stale or orphan ("Skip section" in the Document tab marks a section skipped, "Unskip" makes it
  pending again); expected-revision writes; UTF-8. Content writes only in `draft/running` (the stale and orphan marks
  of the transition hooks are exempt); read-only after `finish-document` until "Request changes"; the last
  `beforeApply` step of `APPROVE_FINAL` pins the set of section revisions and their model labels in the manifest.
  Stale marking (D4): `APPROVE_OUTLINE` compares the approved outline with the fingerprints; a section whose own or
  chapter entry changed becomes stale, a new id is pending, and a deleted id is kept as an orphan that is not
  assembled. `APPROVE_EVIDENCE` marks sections stale that cite a source outside the new approved set. `REOPEN_OUTLINE`
  marks nothing. The Document tab edits one section at a time: Save writes a section revision through
  `FileManuscriptStore`; the editor shows that section's local markers and keeps local text on a save conflict and on
  the Edit/Page toggle (RA-P008); the tab lists a section's revisions with view and restore while the phase is open.
  `document.md` is generated and read-only in the tab, and the tab takes its mode from the D4 table.
- Done when: concurrent job and user saves lose neither text and the conflict shows both; a revision whose citation
  map lacks a checksum or a passage is refused; writes are refused outside `draft/running`; sections of unchanged
  outline entries keep their text after an outline reopen; after a jump back to Concept from Document, one renamed
  chapter and a new outline approval, exactly that chapter's sections are stale and nothing rewrites them by itself;
  the fault-injection test of PR 06 passes with the manuscript pin and its stale marks.

**PR 27 Evidence packs per section** (M, MVP)
- Change: a deterministic `SectionContextBuilder`: passages from the section's provenance plus
  `SemanticKnowledgeIndex.semanticSearch` on its brief, drawn from the approved source set plus sources accepted
  later, minus excluded sources and sources hit by the blacklist recorded at `SUBMIT_SCOPE`; deduplicated, at most two
  per source, cut at sentence boundaries, with passage size and count from the writer settings (presets 8 x 300 or 6 x
  250), numbered locally `[1]..[k]`, one number per passage, inside a quoted data block (4.1 rule 7); plus book
  synopsis, previous-section digest, glossary (the labels of the approved concept) and the book language from the
  Phase-1 record.
- Done when: the same store state gives an identical pack; the pack fits the budget of each writer preset; every local
  number maps to one passage of an accepted source and carries that source's checksum.

**PR 28 Section writer with checked citations** (M, MVP)
- Change: a `SectionWriter` on `phase.draft.writer` with the rules of D8: use only the numbered passages, cite `[n]`
  after each claim, no headings or lists, write in the book language, aim for the word target, end with `GAP:` lines.
  Plain text, no JSON. A `CitationValidator` strips and logs unknown markers, flags uncited sentences and paragraphs
  and parses gaps, and a `ClaimSupportCheck` runs stage 1 of D8: every number, unit, year, percentage and quotation of
  a sentence must occur in a passage cited by that sentence or its paragraph; misses are flagged, never removed. The
  repair lists uncited sentences and unsupported numbers and quotes. A cut-off answer gets one continuation from the
  last full sentence; one repair sends an error list without the failed answer. The word target per call comes from
  the writer settings (presets about 1,100 words for `llama3.1:8b` and `gpt-oss:20b`, about 450 for `gemma4:e2b` and
  every other model), and the answer budget follows it (D8); longer sections are split by key points. `WriterWorker`
  stays for the orchestrated path until PR 37.
- Done when: a reply citing `[9]` with k = 6 has the marker stripped and logged; a sentence with "15 %" that cites a
  passage without the number 15 is flagged "unsupported number", while "15,5 %" matches "15.5 percent" in an English
  passage; a quotation missing from its cited passage is flagged "unsupported quote"; capitalised German nouns raise
  no flag; a reply without citations gets one repair and is then stored with uncited flags; a cut-off answer gets
  exactly one continuation without a duplicated sentence; first-call prompts stay within 3,400 tokens on the small
  preset and 4,300 on the large one, and a continuation adds only the cut-off answer; the prompt names the book
  language of the Phase-1 record at its start and its end; with only `phase.draft` set, the writer uses that model
  through `phase.draft.writer`.

**PR 29 Writing job; rewrite a section or a chapter with a comment; one-click gap search** (L, MVP)
- Change: a `write_sections` service command started by the `afterApply` hook on `START_DRAFTING`. The runtime job
  works through the pending sections of the approved outline in order, as tasks in `FileResearchTaskStore` reached
  through the existing `TaskBoard` port (`ProjectTaskBoard`), and saves each section as soon as it is written, through
  a `ManuscriptPort` that is injected like `setTaskBoard`. It posts "Writing 3/24: <title>". "Stop writing" (Document
  tab; the Workflow step through the job registry) cancels the section call in flight, and that section stays pending;
  a phase change stops the job, which registers in the `PhaseJobRegistry`. On session open the resume pass returns a
  section left `WORKING` to pending, and "Write remaining sections" continues. Stale sections are listed under
  "Rewrite stale sections", which writes a new revision of each on request. The job ends with one outcome that offers
  `finish-document` when every section has text, otherwise "Write remaining sections" and the list of failed sections;
  the state stays `draft/running`, and the job never dispatches a phase command. "Rewrite section" with an optional
  comment reruns the section writer per key-point part, with the comment and the current text of that part, keeps the
  writer's word target and budget (a cut-off part gets one continuation without the old text), writes a new revision
  of that one section and shows the difference. "Rewrite chapter" rewrites every section of one chapter with one
  comment, one section per call. The Document assistant gets `section_rewrite`. `finish-document` is refused while
  sections have neither text nor the user's "Skip" mark, and its confirmation names stale sections. A "Request
  changes" comment is shown on the Document tab and can open "Rewrite section" for the sections the user picks.
  "Research this gap" on an open point (a writer `GAP:` line or a thin section) adds one entry to the PR-14 queue,
  with the section title and the gap as its base query, and runs it in `draft/running`; new sources join the pool and
  carry the plan entry that names the section, so "Rewrite section" can cite them, and PR 34 builds focused plans on
  it. The Document block (job status, current section) joins the seam of PR 16.
- Done when: after an app kill while section 6 is written, the job writes section 6 again and does not rewrite 1 to 5;
  the dispatch log shows no phase command from the job; a section that fails twice is listed, and the job completes
  and offers "Write remaining sections"; a rewrite touches exactly one section; "Rewrite chapter" touches only that
  chapter's sections; "Research this gap" in `draft/running` runs one query through the queue without a phase change,
  and a following "Rewrite section" can cite the new source; `ProductiveBookJourneyTest` reaches `draft/completed`
  with every section written by a scripted writer.

**PR 30 Book assembler, citations, quality report and Markdown export** (L, MVP)
- Change: a deterministic `BookAssembler` and `CitationRegistry` render `document.md`: title from the concept, the
  front matter of D8 (the writer models from the section revisions, date, number of sources, the approved concept and
  outline revisions, the book status, "support checked for numbers and quotes only"), contents, chapters and sections
  in approved-outline order without orphans, local markers renumbered globally by first use, open points from the gaps
  and the skipped sections, and a references list (title, site, URL, access date) from `FileResearchSourceRepository`
  that marks sources accepted after the Sources approval. Every citation resolves through source id and checksum; a
  mismatch is reported, never silently re-pointed. A `BookQualityReport` is computed on every assembly as in D8 and
  gives the status "complete" or "incomplete draft"; the Document tab shows it, the `finish-document` confirmation and
  the gate message name its warnings, and the last `beforeApply` step of `APPROVE_FINAL` adds the report and every
  cited source with its checksum to the manuscript pin of PR 26. The assembler is the only writer of `document.md`: it
  runs on one serial executor after every manuscript change, so it needs no lock, and `document_save` leaves
  `ResearchToolPolicy` (the agent uses `section_rewrite`). `DocumentPageView` resolves `[n]` to the source and reveals
  it in the Sources tab. "Export book..." saves the Markdown to a file the user chooses.
- Done when: a golden test renders a fixture of two chapters with two sections each exactly; rewriting a section that
  cites the same sources in the same order keeps every number, and any other rewrite renumbers the book
  deterministically with every `[n]` still resolving; every `[n]` has exactly one reference and no reference is
  unused; a fixture with one skipped section, one unsupported number, one near-copy and one repeated paragraph reports
  each and the status "incomplete draft", and the user can still approve it with the warnings shown; a fixture without
  findings reports "complete"; a source accepted in Document is marked in the references; the final pin lists every
  cited source with its checksum; the exported file equals `document.md`; `document_save` is offered in no state.

**PR 31 Writer A/B with a sample chapter and the Document default** (M, MVP)
- Change: the A/B of section 5.4 as an opt-in benchmark probe: three identical sections and one complete sample
  chapter per model, the chapter written by the writing job from the same outline and evidence. It runs before the
  acceptance run of PR 32, and a one-line follow-up sets the `phase.draft` preset by the decision rule.
- Done when: the report holds the metrics, the quality-report counts and the owner's blind ranking of the sections and
  the sample chapter for all three models, without names; the preset follows the rule.

**PR 32 First-book acceptance run and report** (M, MVP)
- Change: one run on the RTX 5080 with `gemma4:e2b`, the Document model chosen by PR 31 (set in Model resources, or in
  the Models tab once PR 09 exists) and the embedding model chosen by PR 04, on a German topic of the owner's choice:
  Concept -> plan -> sources -> outline -> sections -> completed. An opt-in live gate skips unless an Ollama endpoint
  is configured. The report `docs/first-book-acceptance.md` (without private paths or user names) records minutes per
  phase, queries, accepted sources with their German and English share, passages, clustering time, sections, tokens
  per section, model swaps, total GPU memory, truncation warnings, the quality report, gaps, defects, and the turns
  whose window dropped own-phase messages, ten of them checked for a dropped fact the answer missed. It also covers
  the open live checks of roadmap AP 5. The run is judged on three levels:
  - Workflow: every phase is entered and left by user commands only; an app restart in every phase resumes it; Stop
    and one reopen with a new approval work; the app is killed once during an approval and once while a section is
    written, and both recover without repeating finished work.
  - Data integrity: after the restarts every pin matches its content hash; every citation resolves to source, checksum
    and passage, and every `[n]` to a reference; umlauts and typographic quotes are intact; no write is lost.
  - Book quality: `document.md` has contents, every chapter and section, numbered citations and open points; no
    unknown marker and no truncation on writer calls; the quality report lists every uncited paragraph, unsupported
    number and quote, near-copy and repetition; the owner checks 20 randomly drawn citations against their passages
    (provisional target: at least 17 support their sentence); German sources are found for the German topic, and
    English sources land in the right chapters; a read-through by the owner finds no contradiction between chapters,
    or the report lists it.
- Done when: each level is met, or every miss is filed as a defect with its cause; the report is checked in.

### 6.7 M5 After the first book

**PR 33 Revise a marked passage with a comment** (L, Rule)
- Change: paragraph anchors in `DocumentPageView`. A selection opens "Comment and revise", which stores a change
  request (section, paragraph range, selected text, comment, status). A `revise_passage` service command
  (`ResearchServiceCommand`, `ResearchServiceCommandWire` in both modules) carries section id, paragraph range,
  selected text and comment; the reply returns over `ResearchRunWire`. A chat turn learns the selected section from the
  Document block, not from `ResearchPrompt.activeSectionId`, which the in-process backend drops. A reviser on the
  Document model gets the passage with two paragraphs on either side, the comment and the cited evidence (4.2), and
  answers with the same validator. The replacement appears side by side or as a diff; accept writes a new section
  revision and re-assembles, reject changes nothing.
- Done when: an accepted revision changes only the selected paragraphs and keeps the citations valid; reject leaves the
  manuscript unchanged; history allows undo.

**PR 34 Gap research from Outline and Document** (M, Rule)
- Change: the one-click gap search of PR 29 becomes a focused plan: `QueryPlanner` adds variants to the section title
  plus the gap, and the entries run through the PR-14 queue in the current phase; the section's evidence pack (PR 27)
  also draws on sources whose plan entry names that section; "Rewrite section" follows. "Research this topic" on a
  concept card and "Research this chapter" in the Outline tab work the same way. Sources added after the approval are
  listed as "Added after approval" in the Sources view and leave the sources pin unchanged. Outside Sources the
  agent's `request_research` (PR 15) creates an offer the user starts. With a Document model that cannot share the GPU
  (`gpt-oss:20b`), gap searches are queued and run between chapters.
- Done when: from `draft/running` a focused plan adds sources to that section without a phase change, and the rewrite
  cites them; with `gpt-oss:20b` queued searches cause one unload and load per batch (telemetry).

**PR 35 Store robustness** (M, Plus)
- Change: locked compare-and-write in `FileArtifactStore` and the source repository (RA-P006);
  `HostMarkdownArtifactView` and the source drafts keep local text on a conflict (RA-P008); the remaining stores
  (concept, outline, manuscript, tasks) flush before the rename like the files of PR 06; restart semantics of running
  states are documented. The handling of corrupt files and the atomic write of `phase-journal.json` moved to PR 06.
- Done when: concurrent writes never lose an update; a conflict in the host Markdown view keeps the local text; a
  write interrupted before its rename leaves the previous file readable.

**PR 36 DOCX and PDF export** (M, Plus)
- Change: export the assembled book as DOCX (a minimal WordprocessingML package written with `java.util.zip`, or
  Apache POI, which the license list already allows) and as PDF with a simple layout on the PDFBox that the plugin
  already has for text extraction (line breaks, pages, headings, an embedded Unicode font).
- Done when: both files contain contents, chapters, citations and references; umlauts and typographic quotes survive in
  both.

**PR 37 Legacy cleanup** (M, Plus)
- Change: delete `DefaultResearchStateMachine`, the 7-phase enum, `APPROVE_DRAFT`, `LegacyResearchStateMigration` with
  its call in `SessionStateFileStore` and the legacy ids in `ResearchStateIds` (the restore remap of `evidence`,
  `review` and `finalization`; an old memento is reported as unreadable, Q20), the FINALIZATION profile and its mention
  in `ResearchToolPolicy`, the research brief artifact with its events and the domain `researchQuestion` fields, the
  brief visualization (or it reads the concept), and the outline types the book path no longer uses after PR 21
  (`OutlineProposal`, `OutlineRevision`, `ResearchOutline`, where unused). After the owner confirms (Q5), the
  orchestrated mode goes with its slots, `orchestrator/run.json` and the run-record part of the `TaskBoard` port and
  of `ProjectTaskBoard` (its planner and writer logic were extracted in PR 13 and PR 28); `FileResearchTaskStore`, its
  tasks and the task part of `TaskBoard`, which the writing job uses, stay.
- Done when: the build is green; grep finds none of the deleted types; `ProductiveBookJourneyTest` stays green.

## 7. Improvement proposals

The owner asked for suggestions. Most of them are already folded into the PRs; the PR number says where, and items
that no PR carries yet say so.

1. **Structure in code, wording by the model.** A 2B model fails most often on structure (counts, ids, optional
   fields). With queries, outline skeleton, passage assignment, citation numbers and assembly in code, a model failure
   costs quality, never the book (PR 13, 22, 27, 30).
2. **Stable ids from concept node to citation.** Concept node -> plan entry -> source provenance -> outline section ->
   manuscript section -> citation. With these links a jump back shows exactly what became stale, a single chapter can
   be regenerated, and the coverage of every concept node is measurable in every phase (PR 07, 14, 21, 26).
3. **Coverage per concept node as the common measure.** It bounds the search, is the main information at the Sources
   gate, flags thin sections in the outline and seeds gap research. It informs the user's decision and never switches a
   phase by itself (PR 14, 22, 34).
4. **One decision per call.** The Phase-1 pattern (`ConceptToolRounds`, the small move inference) for every later
   phase: one action per answer, required fields only. Phase 1 uses exact names; later phases use local indexes
   `[1..k]`, which are new and measured first (D11, PR 04, 24, 25).
5. **Prose as plain text; gaps are output, not failure.** Long JSON strings break on `gemma4:e2b`. Plain text with
   `[n]` markers is shorter and checkable in code. Missing evidence becomes "Open points" and seeds gap research
   instead of being papered over (PR 28, 30, 34).
6. **Repair once, without echo.** Resending the failed answer doubles the prompt and primes the same mistake, so the
   one repair sends an error list only, and a deterministic fallback follows (PR 13, 17, 24, 28). An escalation after a
   failed repair (one more call on the Document slot only if its model is already loaded, never with a swap) is not
   planned yet.
7. **One context per model, thinking by capability, fail loud.** This removes hidden runner reloads, keeps the 24-token
   judge from being eaten by reasoning, and turns silent truncation into a visible error (PR 03, 17).
8. **Answer budgets from telemetry.** PR 03 replaces the global 4,096 with a budget per role. PR 01 logs the real
   answer lengths and PR 04 measures them; a one-line follow-up after PR 04 sets each role's budget to its measured
   95th percentile times 1.5.
9. **Prompt diet for Phase 1.** The concept tool rules (8.7k characters) travel with every Concept call. Sending only
   the rules of the detected turn mode would save about 2,000 tokens per call. Not planned yet: a candidate follow-up
   after PR 04, measured against the live-gate baseline, because Phase 1 is live-tuned.
10. **One leading system message, volatile state next to the user message.** Chat templates differ in how they render
    several or non-leading system messages, and a stable prefix is cache-friendly. Not planned yet: it depends on the
    template check in PR 04.
11. **Size from the deliverable.** Phase 1 already records a target length (`ResearchDeliverable`). The Phase-1 record
    freezes it, and the outline scales its section word targets to it, so the book has the size the user asked for
    (PR 07, 22).
12. **Tabs mirror the phases.** Concept, Sources, Outline, Document, Workflow: each phase's main result sits where the
    user approves it. Today the outline hides in a drawer of the Document tab (PR 10, 23).
13. **Content-bound approvals that survive a crash.** An approval records exactly what the user reviewed, with its
    hash, before the state changes (the manuscript pin also names the models that wrote it and every cited source); a
    failed record refuses the transition. Pins are appended under their transition id, and the transition journal
    works as an outbox, so a crash never leaves an approval half done. The Workflow steps, the phase dividers and the
    book's front matter show the pins, so every later artifact has its provenance (PR 06, 07, 10, 14, 21, 26, 30).
14. **Search only what changed after a jump back.** Reopening Concept for one new topic must not repeat hours of
    search. The plan is merged by concept node id, and only new or changed nodes are searched (PR 14).
15. **The search plan as a visible checklist.** Status and accepted sources per query give control (drop, edit, re-run)
    without an extra gate, and make unattended time predictable (PR 14).
16. **Rank by measurement.** Setup already requires a reranker, but the installed cross-encoder is English, and so is
    the embedding preset. A retrieval benchmark with German, German-to-English and English queries picks the embedding
    model and the ranking of planned queries, BM25 stays the labelled fallback, and the effort of a multilingual
    runtime path is estimated, so today's runtime limit does not decide the retrieval design (PR 04, PR 14, Q25).
17. **An honest front matter.** "Written by <model> from N sources; concept revision 4, outline revision 2; status:
    incomplete draft, 3 open points; support checked for numbers and quotes only" sets expectations without the cost
    of a verification pass (PR 30).
18. **Batch by model and show the model state.** On 16 GB the expensive event is a swap. Work is batched per model,
    and the chat shows "Loading <model>..." (PR 08, 34).
19. **Delete instead of maintain.** No backward compatibility is needed, so dead and duplicate paths go: the
    `SCOPE_PROPOSAL` chain with the chat-started research turn (`maybeStartResearchTurn`), `outline_save`, the
    self-switching fake backend, the legacy state machine with its restore remap, and after confirmation the
    orchestrated mode (PR 05, 15, 21, 37).
20. **One walk-through test in CI.** `ProductiveBookJourneyTest` runs without a GPU and catches every future break of
    the phase chain; live runs stay on the target machine (PR 05; PR 07, 12, 21 and 29 extend its fixtures).
21. **Shrink `ResearchAgentSession` along the hook.** Every hook class moved out of the 5,260-line class makes the next
    change cheaper, without a big-bang refactoring (PR 06 onwards).
22. **Stop is not a phase command.** Today the composer's Stop pauses the phase when no search or review is in
    flight, and `paused` offers no Finish. Stop now ends what is running and leaves the phase in `running`; the user
    commands `pause` and `cancel` go, and "End research" no longer cancels the phase (PR 05, 06).
23. **Phase 1 owns everything later phases read.** Mission, deliverable, working language and the blacklist of that
    moment are frozen with the concept. A greeting can no longer become the mission of every search, and a later
    language switch cannot change the book language (PR 07).
24. **Write-once sources, passage-level citations.** A source's text never changes once stored, and every citation
    names source, checksum and passage, so a reviewer can check any sentence against exactly the text the writer saw,
    also after a re-embedding (PR 14, 26, 27, 30).
25. **Check what code can check, and say what it cannot.** Numbers, years, percentages and quotes are compared with
    the cited passage in code; semantic support needs an entailment model and comes later, and until then the book
    says so (PR 28, PR 30).
26. **A quality report instead of a silent "done".** The book is "complete" or an "incomplete draft" by deterministic
    counts, shown before the final approval; the user decides (PR 30, Q27).
27. **A deterministic book ledger (not planned yet).** A Java component, not a model, that records terms with the
    section that defines them and the key points each section covered, feeds a short "covered so far" block to the
    writer and adds a terminology report. It follows after the first book if the repetition count of PR 30 shows the
    need.
28. **Source text is data.** Page and source text enters prompts only as a quoted block, and model answers can only
    take visible, reversible actions of their phase (4.1 rule 7).

## 8. Risks

| Risk | Mitigation |
|---|---|
| `gemma4:e2b` writes weak German prose or weak JSON moves | Section-sized calls from evidence only; the writer A/B (PR 31); a phase model is a settings change (PR 08) |
| Two assumptions are unverified: `gemma4:e2b` thinks by default, and `think=false` is honoured together with `format` | PR 04 measures both first; Auto resolves per model from `/api/show`, and contract roles force thinking off; telemetry logs the thinking length per call |
| Local indexes `[1..k]` are new for the small model | PR 04 measures the parse rate of the brief contract; PR 24 keeps the default brief on any failure |
| `gemma4:e2b` at 32k and `llama3.1:8b` at 16k may not fit together with the embedding model, the reranker and two browsers | PR 04 measures total GPU memory with `nvidia-smi`. Fallbacks: q8_0 KV and flash attention, `OLLAMA_NUM_PARALLEL=1`, then `llama3.1:8b` at 12,288, where every Document role of 4.2 fits. If even that does not fit: 8,192 with the small writer preset (450 words, 6 x 250, writer answer budget 1,200) and a Document chat answer budget of 1,280; then the writer, its continuation, the rewrite per part and the Document chat (about 8,050) fit |
| With `gpt-oss:20b` as writer every research or helper call swaps 12 to 14 GB | Opt-in only after the A/B; gap searches batched between chapters; "Loading <model>..." in the chat |
| Hallucinated or misattributed content | Evidence-only prompts; markers validated in code; numbers, years, percentages and quotes checked against the cited passage (PR 28); uncited sentences and paragraphs flagged; citations with source checksum and passage; references built from the store; the quality report and the front matter (PR 30); 20 citations checked by the owner (PR 32); the user's final gate. A sentence that reverses what its passage says is not caught before stage 2 of D8 |
| German and cross-language research ranked by English-centred models, so the right sources are never visited | The retrieval benchmark of PR 04 (German, German to English, English) picks the embedding model and the ranking before PR 12, PR 14 and PR 20 build on them; the M2 checkpoint checks the live share of German and English sources; PR 04 estimates a multilingual runtime path |
| A crash between an approval and its effects leaves a half-done transition | Transition journal as an outbox, recovery on session open, idempotent effects, fault-injection tests (D3, PR 06) |
| Prompt injection through page or source text | Source text only as quoted data; action unions without phase changes; every model action visible and reversible; writer output checked in code (4.1 rule 7) |
| The writer copies source text | Near-copies of twelve words or more without quotation marks are flagged in the quality report (PR 30); usage rights stay the user's responsibility before publication |
| A sound architecture is taken for a good book | The plan promises traceability, recovery and visible limits, not quality; the writer A/B with a sample chapter (PR 31) and the three-level acceptance run (PR 32) measure it |
| Unattended search hits CAPTCHAs and slow sites; book-scale search time is unmeasured | The existing CAPTCHA wait; failed entries are marked and can be re-run; caps; a persisted, resumable plan; "Finish sources" at any time, because Stop never leaves `running`; measured at the M2 checkpoint |
| BM25 accepts every hit with a score above 0 | Planned queries use the ranking that wins the retrieval benchmark and its top N (Q25), with BM25 only as the labelled fallback; the scope gate with a working blacklist, the readiness judge and the user's Sources gate |
| The first message, even a greeting, becomes the mission and enters every base query | The Concept tab shows the mission as an editable row, `submit-scope` refuses an empty mission, and the Phase-1 record freezes it (PR 07) |
| A connected bot or the ChatGPT connector approves a gate without the user | Every state command from `BOT` is refused (Q13); the transition journal names actor and surface (PR 06) |
| Clustering is slow or memory-hungry on large corpora | PR 20: nearest-neighbour chain (O(n²) time and memory), progress, cancel and a corpus limit that keeps the best passages per source |
| Changing the embedding model silently empties the outline corpus or breaks citations | Pinned fingerprint per project and re-embedding (PR 20); citations keep a passage text hash and the source checksum, so renamed passages do not break them (D8) |
| Phase-scoped context changes live-tuned Phase-1 behaviour | The live gate before and after (PR 04, PR 17); if it drops, keep the newest `FINAL` entry as canonical JSON |
| `truncate=false` turns silent overflows into visible errors | Switched on together with the budgeted window (PR 17); the guard trims optional blocks first, names the cause and does not rely on the flag, which PR 04 checks against the installed Ollama. No `shift` is sent, because it is a runner setting that would reload the model; PR 01 warns when a call could reach the limit while answering |
| A 2B model drops or invents decisions in phase summaries | Facts come from the artifacts; deterministic fallback; raw messages stay stored; summaries visible on the Workflow steps |
| Long jobs starve helper calls (the scheduler waits before the call timeout) | Section-sized calls (about 6 s on `gemma4:e2b`, about 22 s on `llama3.1:8b`, about 26 s on `gpt-oss:20b`, plus prefill); the writing job yields between sections |
| A phase change while a job writes | The hook cancels the job through the `PhaseJobRegistry`; writes outside `running` are refused; outcomes with an old run token or of another phase are dropped |
| Approving a different revision than the one the user reviewed | Writes only in `running`, so the result is read-only at the gate; commands carry the revision the user saw (D3); the approval records revision and hash before the state changes, and a failed record refuses the transition (PR 06, 07, 14, 21, 26) |
| Writer saves and user edits collide (RA-P008) | Section-level expected-revision writes; the Document editor keeps local text on a conflict (PR 26); the host Markdown view and the source drafts follow in PR 35 |
| `ResearchAgentSession` is a merge hotspot | New behaviour in new classes (hooks, queue, stores, writer, assembler); PR 05 and PR 06 land first |
| Tests pin the dead `SCOPE_PROPOSAL` chain, the brief approval and the empty `submit-scope` | PR 07 and PR 15 rework them in the same PR; `ProductiveBookJourneyTest` becomes the guard |
| Live behaviour differs from CI (no GPU in CI) | Fake ports in CI; live checkpoints: the Phase-1 gate and the measurements of PR 04, the checkpoints after M1, M2 and M3, the writer A/B (PR 31) and PR 32 |
| Existing test projects carry broken umlauts and old state | No migration code (owner rule); new projects after PR 02 (Q20) |

## 9. Open questions

Each question has a recommendation, and the plan follows it until the owner decides otherwise. **Q2, Q4, Q6, Q10, Q12
and Q13 should be decided first**, because the earliest PRs depend on them.

| # | Question | Recommendation |
|---|---|---|
| Q1 | Phase names in the UI: keep Concept, Sources, Outline, Document? | Keep them; they match the tabs and artifacts. The state ids stay unchanged either way |
| **Q2** | Is the blacklist frozen with the concept, or editable in every phase? | Editable in every phase. Entries added after `SUBMIT_SCOPE` can be added, edited, switched off or removed, and an entry of the recorded blacklist can be switched off for future searches; these changes filter future searches only. The blacklist recorded at `SUBMIT_SCOPE` stays part of the Phase-1 record, which outline generation and evidence packs use; changing it needs Concept reopened |
| Q3 | May later phases add sources (manual search, gap research)? | Yes, additively and tagged with the phase. `APPROVE_EVIDENCE` records the approved source set; removing or downgrading one of those sources needs Sources reopened, while a source found later can be excluded in the phase that found it |
| **Q4** | Start the search right after `submit-scope` and the writing right after the outline approval, or wait for an extra "Start"? | Start automatically: the approval is the user's decision. The plan is visible as a checklist; Stop is always available and never changes the phase, so Finish stays available |
| Q5 | Phase-2 engine: the deterministic queue or the opt-in orchestrated mode? | The queue. Hide the orchestrated mode now (PR 15) and delete it after the owner confirms (PR 37); `FileResearchTaskStore` stays for the plan and the writing job |
| **Q6** | Outline from concept chapters plus clustered sources, or purely from clusters? | Concept chapters plus clusters; pure clustering ignores the intent of Phase 1. The store and editor support both |
| Q7 | Size of the first book? | The deliverable's target length if Phase 1 recorded one (a page counts as 300 words, provisional); otherwise 6 to 8 chapters with 3 to 4 sections of 400 to 1,100 words (about 7k to 35k words) from 20 to 40 sources |
| Q8 | Keep the two-step gates (finish, review, approve) of Sources, Outline and Document, including the Sources review before the outline? | Yes, as in the graph; Concept keeps its single step `submit-scope`. The review state is where the user checks the result, and "Request changes" returns to the phase with a comment |
| Q9 | In Phase 4 the agent writes into the manuscript by itself. Is that the accepted exception to "the user owns every write"? | Yes; the user controls it through section revisions, rewrites and the final gate |
| **Q10** | Document model before the A/B: `llama3.1:8b` or `gemma4:e2b`? | `llama3.1:8b` where it is installed: the Models tab writes it as the Document preset when it first opens. Otherwise the Document phase uses the research default `gemma4:e2b`. Provisional until the writer A/B of PR 31, which runs before the first-book run; `gpt-oss:20b` only if the A/B shows clearly better prose |
| Q11 | Is `draft/completed` final? The graph has no edge back into `draft` | Final for the MVP; reopening Outline, Sources or Concept still works, and sections whose outline entry did not change keep their text |
| **Q12** | Is an embedding model mandatory? | Yes, like the reranker today: the blacklist gate, the outline and the evidence packs need vectors. Setup lists it as required, `submit-scope` waits for it, and it is installed under Models -> Setup. The retrieval benchmark of PR 04 (German, German to English, English) decides which model, because the current preset is English-centred |
| **Q13** | May the bot control or the ChatGPT connector dispatch phase commands? | No. A bot reads the state, runs commands that dispatch no state command while its surface is enabled, and may suggest a phase change in its answer; the user makes it. No setting allows more, and the internal agent never gets a phase tool |
| Q14 | Rename the Revisions tab to "Workflow"? | Yes; it shows phases, gates, jobs and the transition timeline, and links to the revision lists in the artifact tabs |
| Q15 | Phase models app-wide or per book? | App-wide for the MVP; a per-book override later |
| Q16 | Should phase summaries be visible and editable? | Visible on the Workflow steps once PR 10 and PR 18 exist; editable later |
| Q17 | After a restart: continue the conversation or start fresh? | Continue, seeded with the current phase window and the summaries |
| Q18 | Book language? | The Phase-1 working language, recorded at `SUBMIT_SCOPE` and stated in every writer prompt; sources in other languages are paraphrased and cited |
| Q19 | Export format for the MVP? | Markdown; DOCX and PDF in M5 |
| Q20 | Existing test projects with broken umlauts or old states: repair or start new? | Start new projects; no migration code, and PR 37 deletes the existing state migration |
| Q21 | Which Ollama version and server settings run on the target machine? | Record them in the PR-04 report; recommended settings in section 5.3 |
| Q22 | Should the reviewed plan become an ADR? | Yes, one ADR for the book pipeline: freeze, phase models, context rules |
| Q23 | After a jump back to Concept and a new submit: search everything again or only what changed? | Only new and changed concept nodes by default, merged by node id; "Search everything again" as an option |
| Q24 | What is the minimum to finish each phase? | Concept: one first-level card, a mission and a ready embedding model. Sources: none, but the review lists uncovered nodes. Outline: one chapter with a section and no generation in flight. Document: every section written or marked "Skip"; whether the book is complete is the status of the quality report, not a precondition (Q27) |
| Q25 | Which ranking should planned queries use? | The strategy that wins the retrieval benchmark of PR 04 (the installed cross-encoder, BM25 or embedding similarity), with BM25 as the labelled fallback while the winner is unavailable; "Use AI reranker" then applies to typed searches, and its label says so |
| Q26 | A phase badge on every chat bubble (a host API change)? | After M4; until then the persisted phase dividers carry the annotation |
| Q27 | May the user approve the final book while the quality report says "incomplete draft"? | Yes; the confirmation names the warnings, and the front matter prints the status |

## 10. Relation to existing documents

| Document | Relation |
|---|---|
| `mvp-roadmap.md` (2026-08-31) | This plan replaces its critical path; the table below maps its work packages. The roadmap itself is not changed by this PR; PR 11 points it here. |
| `docs/research-state-machine.md` | Outdated: 7 phases, Outline before Research. PR 11 replaces it with the 4-phase graph of `ResearchStateGraph`. |
| `docs/adr/0001-research-agent-architecture-after-java-21.md` | Still valid for the runtime and model residency. PR 11 updates its role table and amends section 3.2: `SlotBinding` and `ChatResult` carry model labels as text only. |
| `docs/research-control-mcp.md` | PR 11 aligns the tool list with `ResearchToolPolicy`. |
| `docs/research-bot-control.md`, `docs/research-chatgpt-connector.md` | Partly outdated: bot control is off by default in the code, and `pause` and `cancel` leave `run_command` (PR 05). PR 11 updates the command list, the run offer ids and the default, and documents that bots cannot change phases (D2). |
| `problems.md` | RA-P007 in PR 02; RA-P008 in PR 26 for the Document tab, with `HostMarkdownArtifactView` and the source drafts in PR 35; RA-P006 in PR 35 (the per-source lock already in PR 02). |
| `docs/ollama-parallel-inference-benchmark-2026-10-30.md` | Source of the speed figures in section 5.2; PR 04 writes the measurements of section 5.4 into `docs/ollama-model-budget-measurements.md`. |

| Roadmap work package | In this plan |
|---|---|
| AP 1 Live acceptance of the scope seam | No recorded verdict. Its four checks join the Phase-1 live gate of PR 04, which runs before PR 07, 12, 16 and 17 change Phase 1. |
| AP 2 Conversation policy, AP 3 `scope_probe` | Built (section 2.2); PR 11 marks them done. |
| AP 4 Transition from scoping to research planning | PR 13 to PR 15 |
| AP 5 Live acceptance of the productive research | Its open live checks join the M2 checkpoint and PR 32. |
| AP 6 From the fence to search control | PR 12 and PR 19. PR 12 deliberately adds the word filter that AP 6 ruled out, because the owner asked that blacklisted hits be ignored directly. It applies only to terms and domains the user typed into the blacklist, never to facets from the conversation; the semantic gate stays the main boundary. |
| AP 7 Knowledge consolidation | Passages, embeddings, `ActiveKnowledgeCorpusReader` and the topic projection are built and feed PR 20 and PR 22. Numbers and quotes are checked against the cited passages (PR 28) and reported (PR 30); semantic claims and contradictions are not part of the MVP. Writer gaps (PR 28), the one-click gap search (PR 29) and gap research (PR 34) stand in for corpus gaps. |
| AP 8 Outline | PR 20 to PR 24 |
| AP 9 Writing and report | PR 26 to PR 30 |
| AP 10 Concept as the only Phase-1 result | PR 07 |
| AP 11 Restart and restore | PR 18 and the restart checks of PR 32 |
| AP 12 End-to-end MVP acceptance | PR 32 |
