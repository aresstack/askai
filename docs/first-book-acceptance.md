# First-book acceptance run (plan PR 32)

Status: **not run yet.** This report is filled in from the live run on the owner's machine. Every value below stays
`open` until it is measured; nothing in it is estimated or synthetic. The report holds no private paths and no user
names.

## Setup

| Item | Value |
|---|---|
| GPU | RTX 5080 (16 GB) |
| Concept / scoping model | `gemma4:e2b` |
| Document model (PR 31 decision) | open |
| Embedding model (PR 04 decision) | `bge-m3` (benchmark: Recall@10 = 1.0 DE and EN) |
| Topic (German, owner's choice) | open |
| AskAI commit | open |

## How to run the gate

1. Run the book once in the app: Concept -> plan -> sources -> outline -> sections -> completed, with the restarts,
   kills, Stop and reopen listed under "Workflow".
2. Run the opt-in gate (it skips without an endpoint and never starts, changes or downloads a model):

   ```
   ASKAI_ACCEPTANCE_OLLAMA=http://localhost:11434 \
   ASKAI_ACCEPTANCE_MODELS=gemma4:e2b,bge-m3,<document model> \
   ASKAI_ACCEPTANCE_PROJECT=<book project directory> \
   ./gradlew :research-agent-ui-plugin:test --tests '*FirstBookAcceptanceGateTest'
   ```

3. Copy `research-agent-ui-plugin/build/first-book-acceptance/integrity.md` into "Data integrity" below.

## Measurements

| Measurement | Value |
|---|---|
| Minutes per phase (Concept / Sources / Outline / Document) | open |
| Queries | open |
| Accepted sources (German share / English share) | open |
| Passages | open |
| Clustering time | open |
| Sections | open |
| Tokens per section (min / median / max) | open |
| Model swaps | open |
| Total GPU memory (peak, `ollama ps`) | open |
| Truncation warnings | open |
| Turns whose window dropped own-phase messages | open |
| ... of them checked for a dropped fact the answer missed (10) | open |

Open live checks of roadmap AP 5: open.

## Level 1: Workflow

| Check | Result |
|---|---|
| Every phase entered and left by user commands only (`state/transitions.jsonl`) | open |
| App restart in every phase resumes it | open |
| Stop and one reopen with a new approval work | open |
| App killed during an approval recovers without repeating finished work | open |
| App killed while a section is written recovers without repeating finished work | open |

## Level 2: Data integrity

Filled from `BookIntegrityCheck` after the restarts: every pin matches its content hash; every citation resolves to
source and checksum and its stored text is found in that source; every `[n]` resolves; umlauts and typographic quotes
are intact.

open

No write lost: open.

## Level 3: Book quality

| Check | Result |
|---|---|
| `document.md` has contents, every chapter and section, numbered citations, abbreviation list, open points | open |
| No unknown marker and no truncation on writer calls | open |
| Quality report lists every uncited paragraph, unsupported number and quote, near-copy, repetition | open |
| Owner checks 20 random citations (target: at least 17 support their sentence) | open |
| German sources found for the German topic; English sources in the right chapters | open |
| Owner read-through: no contradiction between chapters, or it is listed | open |

## Quality report

open

## Gaps and defects

Every miss of a level is filed here as a defect with its cause.

| # | Level | Defect | Cause | Filed as |
|---|---|---|---|---|
| | | | | |
