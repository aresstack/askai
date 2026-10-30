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
- **Tokens.** The subject's inference descriptor points at a recording pass-through in front of Ollama
  (`OllamaTokenMeter`); it relays requests and streams unchanged and sums Ollama's `prompt_eval_count` and
  `eval_count`. The subject's code is not touched.
