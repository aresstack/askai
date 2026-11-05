# Ollama model budget measurements (plan PR 04)

This report collects the measurements of `docs/research-book-mvp-plan.md` section 5.4 on the target machine
(RTX 5080, 16 GB). **Status: no value is measured yet.** Every cell below says which probe measures it. The probes
need the owner's GPU, so they did not run in CI or in the cloud container, and no number in this file is an
estimate presented as a measurement. Every default of sections 4.1, 4.2 and 5.2 stays **provisional** until a
one-line follow-up adopts the measured value.

How to run (normal `test` stays model-free; both probes run only in the opt-in task):

```
gradlew :research-benchmark:benchmark --tests '*ModelProtocolLiveTest*'
gradlew :research-benchmark:benchmark --tests '*RetrievalBenchmarkLiveTest*' -Daskai.benchmark.retrievalData=<local data directory>
```

Settings: `askai.benchmark.ollamaUrl`, `protocolModels` (default `gemma4:e2b,llama3.1:8b,gpt-oss:20b`),
`protocolEmbeddingModel` (default `nomic-embed-text`), `embeddingCandidates` (the current preset first),
`visitTopN` (default 5), `localModelRoot`, `reportDir`. The reports are written without machine paths; paste their
tables here together with the Ollama version and the server settings of 5.3.

## Environment

| Item | Value |
|---|---|
| Ollama version | open (the reports print it) |
| `OLLAMA_FLASH_ATTENTION` | open |
| `OLLAMA_KV_CACHE_TYPE` | open (5.3 recommends `q8_0`) |
| `OLLAMA_MAX_LOADED_MODELS` | open (5.3: unset or at least 3) |
| `OLLAMA_NUM_PARALLEL` | open (measured at 1 and 2, item 3) |
| `OLLAMA_CONTEXT_LENGTH` | open (5.3: 32768 for dictation) |

## Questions of section 5.4

| # | Question | Probe | Status |
|---|---|---|---|
| 1 | Does `gemma4:e2b` think when `think` is omitted, with and without `format`? Parse rate per role contract (N = 20) with thinking omitted vs off, including chapter briefs with local section indexes | not built in this PR: the role contracts of the chapter briefs and the writer do not exist before PR 22/27/28; the existing contracts are measured by the research benchmark's baseline subject | open |
| 2 | Characters per token for English and German per model, tokens per German word, cached prefixes | `ModelProtocolLiveTest` (prompt_eval_count of fixed prose minus an empty baseline); cached-prefix counting is read from the same `[model-call]` log lines of PR 01 | open |
| 3 | Total GPU memory (`nvidia-smi`) with gemma4:e2b at 32k beside llama3.1:8b at 16k and 32k, q8_0 KV, `OLLAMA_NUM_PARALLEL` 1 and 2, reranker and two browsers open | `ModelProtocolLiveTest` samples `nvidia-smi` after every model switch; the server settings and the open browsers are set by hand for each run, and llama3.1:8b at 32k needs `-Daskai.benchmark.numCtx.llama3.1:8b=32768` | open |
| 4 | `load_duration` for every switch between gemma4:e2b, llama3.1:8b and gpt-oss:20b | `ModelProtocolLiveTest` (two rounds through all models) | open |
| 5 | How the chat template renders several, and non-leading, system messages | manual: `ollama show --template` per model and one request with two system messages; not automated in this PR | open |
| 6 | Phase-1 live gate on gemma4:e2b (N = 10) and the four open checks of roadmap AP 1 | the existing live gate run (`research-benchmark` baseline subject); its result is recorded here | open |
| 7 | Retrieval benchmark: Recall@5, Recall@10, MRR per group (DE→DE, DE→EN, EN→EN) and candidate; concept assignment of 50 labelled passages; embedding time per 100 passages; GPU memory; longest passage against the model's context; hit rate and "selects nothing" per ranking strategy | `RetrievalBenchmarkLiveTest`; needs the owner's 24 queries in `research-benchmark/src/test/resources/benchmark/retrieval/retrieval-queries.json` and the local corpus of about 300 passages (`corpus.jsonl`) plus 20 search results per query (`serp/<queryId>.json`) | open: topic and queries not chosen yet |
| 8 | One multilingual cross-encoder (`bge-reranker-v2-m3`) on the queries of item 7, with a reference implementation outside AskAI | outside AskAI by design (the plan forbids a new engine without measured need); score with the same query file and compare with the table of item 7 | open |
| 9 | Does the installed Ollama honour `truncate=false` on chat and embedding calls? | `ModelProtocolLiveTest` (an overlong prompt must fail with an HTTP error) | open |

## Retrieval benchmark (item 7)

Candidates (5.2): `nomic-embed-text` (current preset, prompted and once raw as before PR 04),
`nomic-embed-text-v2-moe`, `bge-m3`, `embeddinggemma`, `snowflake-arctic-embed2`, `granite-embedding:278m`.
Prompt schemes come from the model cards (`EmbeddingPromptScheme`): nomic `search_query: `/`search_document: `,
embeddinggemma `task: search result | query: `/`title: none | text: `, snowflake-arctic-embed2 `query: ` for
queries only. **`bge-m3` and `granite-embedding:278m` have no known prompt scheme and are embedded raw.** The local
runtime's E5 models get `input_type=query`/`passage`.

| Candidate | DE→DE R@5 / R@10 / MRR | DE→EN R@5 / R@10 / MRR | EN→EN R@5 / R@10 / MRR | Concept assignment (of 50) | ms / 100 passages | GPU MiB | Longest passage / context |
|---|---|---|---|---|---|---|---|
| nomic-embed-text (raw, before PR 04) | open | open | open | open | open | open | open |
| nomic-embed-text (nomic-v1) | open | open | open | open | open | open | open |
| nomic-embed-text-v2-moe (nomic-v1) | open | open | open | open | open | open | open |
| bge-m3 (raw: no known scheme) | open | open | open | open | open | open | open |
| embeddinggemma (embeddinggemma-v1) | open | open | open | open | open | open | open |
| snowflake-arctic-embed2 (arctic-v1) | open | open | open | open | open | open | open |
| granite-embedding:278m (raw: no known scheme) | open | open | open | open | open | open | open |

Ranking of planned queries (target in the visited top N):

| Strategy | DE→DE | DE→EN | EN→EN | Mean | Selects nothing |
|---|---|---|---|---|---|
| BM25 (`Bm25CandidateReranker`) | open | open | open | open | open |
| Installed cross-encoder (ms-marco-MiniLM, English) | open | open | open | open | open |
| Embedding similarity (query role vs title and snippet) | open | open | open | open | n/a |
| BM25 + embedding by reciprocal rank (`ReciprocalRankFusion`, k = 60) | open | open | open | open | open |

Rules (5.4 item 7): among the candidates that fit beside the phase models and whose DE→EN Recall@10 is not below
the current preset's, the best mean Recall@10 becomes the embedding preset; planned queries get the strategy with the
best mean hit rate. The report prints the qualifying candidate; the fit is decided from the GPU column.

## Defaults of sections 4.1, 4.2 and 5.2

| Section | Default | Current value | Measured by | Status |
|---|---|---|---|---|
| 4.1 rule 1 | `num_ctx` gemma4:e2b | 32,768 | item 3 | provisional |
| 4.1 rule 1 | `num_ctx` llama3.1:8b | 16,384 (32,768 only if item 3 shows the fit) | item 3 | provisional |
| 4.1 rule 1 | `num_ctx` gpt-oss:20b | 16,384 | item 3 | provisional |
| 4.1 rule 1 | `num_ctx` other models | 8,192 | item 3 | provisional |
| 4.1 rule 2 | `truncate=false` is honoured | assumed not; the guard does not rely on it | item 9 | open |
| 4.1 rule 3 | reasoning added to gpt-oss contract budgets | 1,000 tokens | item 1 (on gpt-oss) | provisional |
| 4.1 rule 3 | Auto thinking for gemma4:e2b = off | off | item 1 | provisional |
| 4.1 rule 4 | token estimate | characters / 3.2 | item 2 | provisional |
| 4.2 | Concept chat turn answer | 4,096 | items 1, 6 | provisional |
| 4.2 | Scope probe generator / chooser | 8,192 / 1,024 | item 1 | provisional |
| 4.2 | Sources chat turn answer | 1,536 | item 1 | provisional |
| 4.2 | Post-search review answer | 3,072 in Sources, 4,096 in Concept | items 1, 6 | provisional |
| 4.2 | Query expansion answer | 256 | item 1 | provisional |
| 4.2 | Page readiness judge answer | 24 | item 1 | provisional |
| 4.2 | SERP layout repair answer | 2,000 | item 1 | provisional |
| 4.2 | Cluster and section label answer | 160 | item 1 | provisional |
| 4.2 | Chapter briefs answer | 150 per section plus 100 | item 1 | provisional |
| 4.2 | Outline chat turn answer | 1,536 | item 1 | provisional |
| 4.2 | Section writer llama3.1:8b | 3,072 (up to 1,100 words) | item 2 (tokens per German word, 2.3 assumed) | provisional |
| 4.2 | Section writer gemma4:e2b | 1,200 (up to 450 words) | item 2 | provisional |
| 4.2 | Section writer gpt-oss:20b | 4,096 including about 1,000 reasoning | items 1, 2 | provisional |
| 4.2 | Rewrite section part / revise passage | the writer's budget | item 2 | provisional |
| 4.2 | Document chat turn answer | 1,536 (3,072 on gpt-oss) | item 1 | provisional |
| 4.2 | Phase summary answer | 600 (plus about 1,000 on gpt-oss) | item 1 | provisional |
| 4.2 | Rolling summary answer | 600 | item 1 | provisional |
| 5.2 | Concept model | gemma4:e2b | items 3, 6 | provisional |
| 5.2 | Sources, planner model | gemma4:e2b | item 6 | provisional |
| 5.2 | Helper roles model | gemma4:e2b | item 1 | provisional |
| 5.2 | Outline model | gemma4:e2b | item 1 | provisional |
| 5.2 | Document model | llama3.1:8b if installed, otherwise gemma4:e2b (fits beside gemma4:e2b: about 12 to 14 GB together, unmeasured) | items 3, 4 | provisional |
| 5.2 | Phase summaries model | the model of the phase being left | item 4 | provisional |
| 5.2 | Embedding preset | nomic-embed-text | item 7 | provisional |
| 5.2 | Ranking of planned queries | today's selection (cross-encoder when enabled, else BM25) | item 7 | provisional |

## What PR 04 changed in the product

- `EmbeddingPort` has roles: `embedDocuments`, `embedQueries` and `embedRaw` (the old `embed(texts)` is gone, so no
  call site keeps an implicit role). Passages and the sentence windows of the passage boundaries are documents; the
  scope fence (anchors, `scope_probe` terms, sweep probes, `SearchScopeGate` candidates) stays raw.
- `HttpEmbeddingPortAdapter` adds the model card's prompts per role. The prompt scheme is part of the vector
  fingerprint of documents and queries, so projects embedded before PR 04 are re-embedded (PR 20); the raw world
  keeps the descriptor fingerprint, so the scope fence's calibration stays valid.
- `ReciprocalRankFusion` is ported from the enterprise AI client with its tests.
