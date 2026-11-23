# research-benchmark

The research benchmark harness of ADR 0001 (work item H). Test sources and fixtures only: no main source
set, and nothing in the product depends on this module.

It measures a research **subject** on a fixed question set with expected outcomes and writes one Markdown
report per run. Today there is one subject, the **baseline**: the single in-process research agent as it
runs on `develop`, driven through the productive host wiring (`ProductiveResearchBackendFactory` →
`ProductiveResearchSessionResources` → `ResearchSessionBackend`). The orchestrator (lane F) and every new
worker role (lane G) are added as further `BenchmarkSubject`s and compared against that baseline on the same
questions.

## Running

```
gradlew :research-benchmark:test        # model-free unit tests (fixtures, scorer, report, meter)
gradlew :research-benchmark:benchmark   # the live benchmark
```

The live benchmark needs a reachable Ollama, an installed rerank-capable local model (the autonomous
research loop always uses the session's mandatory model reranker) and a browser for the configured channel.
Without one of them it skips and prints the reason. A benchmark measures; it fails only when the harness
itself cannot produce a report.

Settings are `askai.benchmark.*` system properties; the `benchmark` task forwards them from the command line.

| Setting | Default | Meaning |
|---|---|---|
| `ollamaUrl` | `http://127.0.0.1:11434` | Ollama of the main model |
| `models` | first installed chat model | comma-separated models, e.g. `gemma4:e2b,gpt-oss:20b` |
| `repetitions` | 3 | runs per question and model (stability) |
| `runTimeoutSeconds` | 300 | upper bound of one run |
| `resume` | true | run the resume-after-interruption scenario once per question and model |
| `interruptTimeoutSeconds` | 180 | wait for the first source before interrupting |
| `cancelSettleSeconds` | 360 | a cancelled run must report its outcome within this time, otherwise the resume scenario fails instead of running two prompts at once |
| `inferenceTimeoutMillis` | 300000 | model-call timeout in the subject's inference descriptor |
| `questions` | all | comma-separated question ids |
| `reportDir` | `build/reports/research-benchmark` | where reports go |
| `browserChannel` | `$ASKAI_TEST_BROWSER_CHANNEL` or `chrome` | browser of the subject |
| `localModelRoot` | `%APPDATA%/.askai/models/local` | installed local models (the reranker) |
| `rerankTopN` | 10 | top-N of the reranker snapshot |
| `serpResults` | 8 | result blocks per fixture SERP |
| `claimSupportThreshold` | 0.6 | share of a claim's content words a source must contain |
| `claimMinimumContentWords` | 5 | shorter sentences are not counted as claims |

Example: `gradlew :research-benchmark:benchmark -Daskai.benchmark.models=gemma4:e2b,gpt-oss:20b -Daskai.benchmark.repetitions=5`

## How it measures

- **Fixture world.** Every question runs in its own local world: a SERP engine (`/find?q=`, the structured
  `li(h2(a),p)` layout) and one loopback server per corpus host, passed to the browser with
  `--domain-key-mode=host-port --search-url=...` exactly like the MVP end-to-end test. The corpus is invented
  (fictional products, places, alloys), so a statement can only be supported by a corpus page.
- **Question set** (`src/test/resources/benchmark/questions.json`, versioned): relevant pages, distractor
  pages, facts with keywords, planted contradictions, planted gaps (outline sections no page covers), and the
  sufficiency rule. Loading validates the file completely (a fact must be on its page, a gap on no page).
- **Metrics** (ADR section 6, H): source precision and distractors, page recall and fact coverage,
  sufficiency, share of supported claims, contradiction and gap detection (structured when a subject reports
  them, otherwise a text heuristic), tool-call success, model calls and tokens, wall time and time to the
  first source, stability across repetitions and models, and resume after interruption (cancel after the
  first accepted source, continue, count duplicate sources and revisited pages).
- **Input.** The baseline is started with the full question (`research: <question>`). It has no outline
  input, so its gap detection measures what it reports unprompted.
- **Environment vs. result.** A run whose session cannot start, or whose browser cannot start, is marked as
  an environment failure; if every run fails that way, the benchmark skips instead of reporting poor scores.
- **Tokens.** The subject's inference descriptor points at a recording pass-through in front of Ollama
  (`OllamaTokenMeter`); it relays requests and streams unchanged and sums Ollama's `prompt_eval_count` and
  `eval_count`. The subject's code is not touched. A run's usage is taken only after its last model call has
  finished, so a call that outlives its run is not charged to the next one.

## Measurement protocol and retrieval benchmark (plan PR 04)

Two more live tests run in the same opt-in `benchmark` task and never in `test`:

- `ModelProtocolLiveTest`: characters per token (English, German) and tokens per German word, `load_duration` for
  every switch between `askai.benchmark.protocolModels`, total GPU memory from `nvidia-smi` after each load, and
  whether `truncate=false` makes an overlong chat and embedding call fail.
- `RetrievalBenchmarkLiveTest`: known-item retrieval in three groups (DE→DE, DE→EN, EN→EN) for every
  `askai.benchmark.embeddingCandidates` model through the productive `HttpEmbeddingPortAdapter` with the prompts of
  its model card (the first candidate also once raw), the concept assignment of the labelled passages, and the
  ranking of planned queries by BM25, the installed cross-encoder, embedding similarity and reciprocal rank fusion.
  Queries, target ids and labels are checked in (`benchmark/retrieval/retrieval-queries.json`); the corpus and the
  search results are third-party text and stay in `askai.benchmark.retrievalData` (`corpus.jsonl`,
  `serp/<queryId>.json`). Without queries or data it skips.

Results go to `docs/ollama-model-budget-measurements.md`.

## Writer A/B (plan PR 31)

Opt-in probe `WriterAbLiveTest` in the `benchmark` task. Since stage 0 of the writer proposal (November 2026) it
writes the way the app writes: only the retrieval of the evidence pack is replaced by an exported pack, everything
after it is the app's code (`WriterAbPipeline`): the section context of `ProductiveSectionContexts` (synopsis =
the chapter's purpose, digest = the start of the previous section's current text, the other written sections for the
repeat check, the book language as the concept pins it), the pack budgets of `SectionContextBuilder.pack` and the
writer targets of `EvidenceAssignments.assignment` read by `ResearchRuntimeSettings.loadWriterTargets` (passages
per part, 40 words per passage, top-up, repeat and synthesis criteria, citation format of the model). The writer gets
the app's sentence vectors (`bge-m3` by default, `-Daskai.benchmark.writerAb.sentenceModel`).
`WriterAbParityTest` pins that the A/B sends the writer exactly the requests the app sends.

### Input: export a chapter with its complete packs

```
gradlew :research-benchmark:benchmark --tests '*WriterAbLiveTest.exportTheChapter*'
  -Daskai.benchmark.writerAb.export.workspace=<project directory>
  -Daskai.benchmark.writerAb.export.out=chapter.json
  [-Daskai.benchmark.writerAb.export.chapter=1] [-Daskai.benchmark.writerAb.export.sections=4]
  [-Daskai.benchmark.writerAb.export.outlineRevision=<n>] [-Daskai.benchmark.writerAb.export.embeddingModel=bge-m3]
```

The project is copied first; the packs come from the app's pack builder over the project's index, active passages,
accepted sources and acronym glossary, with the pack settings of the writer defaults (`.export.settings=key=value;...`
changes them, and the pack settings are written per section: a run whose arm asks for other pack settings stops
and asks for a new export). The mission's source preference is applied as in the app (mission of the newest pin
in `book.json`, on by default). Not reproduced: the sections of earlier chapters the app has in its manuscript when
it writes a later chapter. Format 2:

```json
{"format": 2, "language": "de", "origin": "...",
 "chapter": {"id": "...", "title": "...", "purpose": "..."},
 "sections": [{"id": "s1", "title": "...", "brief": "...", "keyPoints": ["..."], "words": 1100, "glossary": ["..."],
               "passages": [{"number": 1, "passageId": "...", "sourceId": "...", "checksum": "...", "text": "..."}]}]}
```

Format 1 (the chapter of 17 November: `synopsis`, a book-wide `glossary`, the cited passages only) is still read.

### Run

```
gradlew :research-benchmark:benchmark --tests '*WriterAbLiveTest.writeTheSamples*' -Daskai.benchmark.writerAb.input=chapter.json
  -Daskai.benchmark.writerAb.arms=gemma4:12b [-Daskai.benchmark.writerAb.runs=3]
  [-Daskai.benchmark.writerAb.variants=app,parts:research.writer.partContext=true]
  [-Daskai.benchmark.writerAb.judge=<judge model>] [-Daskai.benchmark.writerAb.judgeItems=chapter]
  [-Daskai.benchmark.writerAb.chapterOnly=true] [-Daskai.benchmark.writerAb.sentenceModel=bge-m3]
```

Arms are `model|words|thinking` entries (`#` instead of `|` from a Windows batch file: `gpt-oss:20b##low`); an empty word count keeps the app's setting (450); without `writerAb.arms` the arms are gemma4:e2b, llama3.1:8b and
gpt-oss:20b (thinking low), all on that setting, because one exported chapter holds the packs of one word target. A variant is a set of
writer settings against the app's defaults (`name:key=value;key=value`, or `-Daskai.benchmark.writerAb.variant.<name>=...`);
every arm runs every variant, in an order that rotates per run, and the blind sheet hides arm and variant. Each arm
writes sections 1-3 alone (on an empty manuscript) and the whole chapter through the PR 29 writing job, in every run;
`chapterOnly` writes only the chapter (the app's path; half the time). The writer's log goes to
`writer-ab-<time>-writer.log`, and its top-ups, top-up words, dropped top-ups, removed repeats and repairs are
counted per sample; a writer that loses its sentence vectors stops the run. The judge reads each cited sentence
with up to two sentences before it as context, and its unreadable answers are counted apart. The judge is unloaded
after every sample and an arm after its turn (`keep_alive` 0), so two big models never share memory, and a sample's
seconds leave out Ollama's `load_duration`. Every writer call goes to `writer-ab-<time>-calls.jsonl` with its kind
(draft, top-up, repair, continuation) and answer text, and with a judge every judged sentence, carried ones included,
goes to `writer-ab-<time>-run<n>-judge.jsonl` (it names the arms), so verdicts can be split by draft and top-up and
the judge can be checked blind in both directions. Per run the report directory
receives `writer-ab-<time>-run<n>-sheet.md` (blind, shuffled letters per item), `-metrics.md` (time, tokens, citation
validity, uncited paragraphs, drift, claim-check and gap counts, and the quality figures: repeated words per section
and overall by bge-m3 cosine >= 0.80, restarts, reach of the word target, short paragraphs, source blocks,
two-source sentences, pack use, and with a judge the share of cited sentences whose passages carry them) and
`-key.txt`; `writer-ab-<time>-summary.md` gives mean and range per arm over the runs and lists every sentence the
judge found not carried. It names the arms: open it only after the rating. Fill one `Ranking:` line per item in a
sheet without opening the key, then:

```
gradlew :research-benchmark:benchmark --tests '*WriterAbLiveTest*' -Daskai.benchmark.writerAb.rated=sheet.md -Daskai.benchmark.writerAb.key=key.txt
```

writes `writer-ab-decision.md` with the resolved ranking and the `phase.draft` preset of the rule of section 5.4.
