# Writer model blind comparison (17 Nov 2026)

Which local Ollama model should fill the Document writer slot (`phase.draft.writer`)?
Result: **gemma4:12b**, now the shipped default of that slot (`ModelPortSettings.DEFAULT_SLOT_MODELS`).

## Setup

- Hardware: RTX 5080 (16 GB), Ollama 0.40.13, server settings unchanged.
- Input: the three sections of chapter 1 of live run 4 (wearables / digital construction site), each with the
  evidence pack of its latest revision (22, 13 and 15 passages, cut at 1,500 characters), target 1,100 words,
  language German.
- Harness: `research-benchmark` `WriterAbLiveTest`, seed 20261117, "Words per call" 450, `num_ctx` 16,384,
  gpt-oss thinking `low`. Every model got the same sections and the same prompts.
- Arms: `-Daskai.benchmark.writerAb.arms=gemma4:e2b|450|,gemma4:12b|450|,llama3.1:8b|450|,gpt-oss:20b|450|low`.
- Rating: the writer's own claim check (stage 2: "citation does not carry the claim", uncited sentences,
  unsupported numbers and quotes, topic drift) plus a blind reading of texts A–D before the key was opened.
- Total run time: 13.5 minutes.

## Results

Sums over the three single sections and the chapter run:

| Model | Citation does not carry the claim (sections / chapter) | Uncited sentences (sections / chapter) | Words per second (chapter) | Readability (blind) |
|---|---|---|---|---|
| gemma4:12b | 3 / 3 | 3 / 2 | 31 | best: coherent paragraphs, clean terms, correct attribution |
| gemma4:e2b | 1 / 1 | 6 / 0 | 63 | patchwork: retells source after source, many repetitions |
| llama3.1:8b | 12 / 14 | 19 / 20 | 25 | weak: verbatim quotes, broken paragraphs, 3 % drift |
| gpt-oss:20b | 21 / 15 | 40 / 56 | 30 | fluent, but invents connections no source makes |

Observations:

- **gemma4:12b** is the only model that writes real expository prose with transitions while staying with its
  sources. Citations almost always carry the sentence.
- **gpt-oss:20b** hallucinates the most: it builds its own syntheses (e.g. wearable data fed into BIM models to
  simulate CO2 emissions) that no passage states, and often leaves them uncited. This explains the many uncited
  sentences of run 4.
- **gemma4:e2b** scores clean because it paraphrases closely, but it only strings sources together and repeats the
  same statements; not usable as book text.
- **llama3.1:8b** copies source sentences in quotes, cuts paragraphs and has the second most citation faults.
- **All models** repeat content across the 450-word part calls, because each part starts the topic anew. That is a
  pipeline issue (parts do not know what earlier parts said), not a model issue.

## Decision and follow-ups

1. Writer slot default: `gemma4:12b` (q4_K_M, about 8 GB, fits next to bge-m3). A model set for the writer slot or
   for `phase.draft` still wins; only the app-wide `default` slot no longer replaces it. Context preset 16,384.
2. gpt-oss:20b is not used as writer; it stays available for planning and checking.
3. Pipeline: give each part call what earlier parts already covered instead of filtering near-repetitions afterwards.
4. Limits: one chapter, one seed, automatic checks plus one blind reading. ellamind SUI-1 24B is evaluated later
   (issue #233).
