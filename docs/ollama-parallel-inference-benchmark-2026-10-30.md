# Ollama: Parallel Inference Benchmark (RTX 5080, 2026-10-30)

## Purpose and status

Empirical measurements of parallel text generation with Ollama, relevant to AskAI's Research Agent, multi-worker execution and model/endpoint resource scheduling.

**Status:** Exploratory benchmark; **not** a finalized tuning decision. Numbers are measurements on one machine and workload, not generally valid model capacities. Twelve-request Llama 3.1 8B runs produced the highest **observed and repeated** aggregate throughput in this series (~758 tokens/s); the exact optimum (and Gemma's exact saturation threshold) has **not** been determined.

Related: [ADR 0001](../adr/0001-research-agent-architecture-after-java-21.md), especially its model/endpoint resource scheduler.

## Test setup and method

- **Hardware:** NVIDIA GeForce RTX 5080, **16 GB VRAM**, one GPU.
- **Inference:** Ollama server, tested via HTTP from Windows/PowerShell.
- **Script:** Existing `C:\tmp\ollama\ollama-parallel-async.ps1` (no change to the script required). It launches requests asynchronously through .NET `HttpClient`, rather than sequentially or through PowerShell `Start-Job` worker processes.
- **Test shape:** Warm-up, a single-request baseline, then N nearly simultaneous calls to the *same loaded model*.
- **Generation settings:** `num_ctx=8192`, `num_predict=512`, `temperature=0.2`, `stream=false`, `keep_alive=10m`. One identical English prompt about GPU inference batching for each request.
- **Server options:** `OLLAMA_NUM_PARALLEL` set to the intended test slot count before starting the server; `OLLAMA_FLASH_ATTENTION=1`; `OLLAMA_KV_CACHE_TYPE=q8_0`. Tests used the configured model directory `X:\ollama`.
- **Metric:** Aggregate output throughput = sum of generated tokens / elapsed batch wall time. The per-worker token rates are separate measurements. A displayed `100% GPU` in `ollama ps` indicates GPU model placement, **not** an independent measurement of the GPU's utilization or free VRAM.
- **Comparability:** Each configuration uses a different number of simultaneously running requests. Warm-up and request launch timings were checked. Gemma at 20 slots and Llama at 10 and 12 slots have two recorded asynchronous runs; other configurations have one run each.

Example invocation (the existing script takes `-Model` and `-Requests`):

```powershell
cd C:\tmp\ollama
powershell -ExecutionPolicy Bypass -File .\ollama-parallel-async.ps1 -Model "llama3.1:8b" -Requests 8
```

Server startup example (adjust the parallel limit to the test; **restart Ollama after changing it**):

```powershell
$env:OLLAMA_MODELS = "X:\ollama"
$env:OLLAMA_NUM_PARALLEL = "8"
$env:OLLAMA_MAX_LOADED_MODELS = "1"
$env:OLLAMA_FLASH_ATTENTION = "1"
$env:OLLAMA_KV_CACHE_TYPE = "q8_0"
ollama serve
```

## Results

### Gemma 4 E2B — `gemma4:e2b`

| Concurrent requests / slots | Single baseline (tokens/s) | Batch output tokens | Batch time (s) | Aggregate output (tokens/s) | Mean worker wall time (s) | Success | Launch spread |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 192.01 | 4,096 | 6.02 | **680.39** | 5.99 | 8/8 | 12.9 ms |
| 16 | 193.59 | 8,192 | 9.04 | **905.80** | 9.02 | 16/16 | 9.2 ms |
| 20, run 1 | 197.43 | 10,240 | 10.24 | **999.54** | 10.21 | 20/20 | 30.1 ms |
| 20, run 2 | 223.05 | 10,240 | 9.91 | **1,033.78** | 9.89 | 20/20 | 11.4 ms |
| 24 | 197.80 | 12,288 | 11.99 | **1,025.14** | 11.95 | 24/24 | 20.8 ms |
| 32 | 200.62 | 16,384 | 15.91 | **1,030.01** | 15.85 | 32/32 | 15.9 ms |

Mean aggregate throughput at 20 requests across the two runs: **1,016.66 tokens/s**.

**Observations:**

- Throughput increases strongly from 8 to 16 requests, and rises further by 20.
- At 20–32 requests, aggregate throughput is approximately **1,000–1,034 tokens/s**: a near-plateau, with little or no **demonstrated** additional throughput above 20 slots.
- More workers still increase individual request latency: approximately 9.9–10.2 s at 20 versus 15.85 s at 32.
- The two 20-request runs produced 999.54 and 1,033.78 tokens/s (about **3.4%** difference relative to the lower run). Small differences between 20, 24 and 32 are therefore not evidence of a meaningful ranking.
- **Working hypothesis:** The practical saturation region lies around **16–24 parallel requests** for this workload. Twenty is a plausible provisional throughput/latency compromise; the exact optimum has not been established.

### GPT-OSS 20B — `gpt-oss:20b`

| Concurrent requests / slots | Single baseline (tokens/s) | Batch output tokens | Batch time (s) | Aggregate output (tokens/s) | Mean worker wall time (s) | Success | Launch spread |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 2 | 162.51 | 1,024 | 4.32 | **237.14** | 4.31 | 2/2 | 5.3 ms |
| 8 | 159.56 | 4,096 | 26.87 | **152.46** | 26.43 | 8/8 | 8.9 ms |

`ollama ps` reported about **12 GB**, **100% GPU**, `CONTEXT 8192` for the tested model.

**Observations:**

- Two simultaneous requests improve aggregate throughput by approximately **46%** over that run's single-request baseline (237.14 versus 162.51 tokens/s).
- Eight simultaneous requests generate **less aggregate throughput** (152.46 tokens/s) than the single-request baseline (159.56 tokens/s), despite all requests succeeding.
- Hence **higher parallelism is not automatically beneficial** for this much larger model on this GPU. In contrast with Gemma, parallel scaling is poor here.
- No data for 3–7 slots were collected. It would be unwarranted to declare 2 slots the proven optimum.
- GPT-OSS reasoning-token use and other generation/prompt characteristics can affect comparability with other models. These tests measure generated tokens, not task quality or useful final-answer tokens.

### Llama 3.1 8B — `llama3.1:8b`

| Concurrent requests / slots | Single baseline (tokens/s) | Batch output tokens | Batch time (s) | Aggregate output (tokens/s) | Speedup vs. respective baseline | Mean worker wall time (s) | Success | Launch spread | `ollama ps` size |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 134.86 | 2,030 | 5.00 | **405.62** | **3.01×** | 4.86 | 4/4 | 5.7 ms | 7.2 GB |
| 8 | 144.16 | 4,066 | 6.36 | **639.74** | **4.44×** | 6.24 | 8/8 | 7.2 ms | 9.5 GB |
| 10, run 1 | 141.03 | 5,060 | 8.50 | **595.15** | **4.22×** | 8.18 | 10/10 | 8.2 ms | 10 GB |
| 10, run 2 | 147.13 | 5,120 | 7.23 | **708.46** | **4.82×** | 7.21 | 10/10 | 7.7 ms | 10 GB |
| 12, run 1 | 126.22 | 6,077 | 8.01 | **758.46** | **6.01×** | 7.90 | 12/12 | 10.9 ms | 11 GB |
| 12, run 2 | 126.59 | 6,099 | 8.05 | **757.51** | **5.98×** | 7.92 | 12/12 | 9.3 ms | 11 GB |
| 16 | 137.14 | 8,174 | 21.40 | **382.04** | **2.79×** | 21.34 | 16/16 | 11.0 ms | 14 GB |

All runs showed **100% GPU** and `CONTEXT 8192` in `ollama ps`. The second ten-request baseline produced **475 output tokens**, unlike the typical 512-token baseline. Both twelve-request single baselines produced 512 tokens, but were slower (126.22 and 126.59 tokens/s) than the earlier eight-/ten-request baselines. This is worth noting when comparing speedup multiples across configurations.

Some responses completed before the 512-token cap: the four-request batch generated 2,030 output tokens, the eight-request batch 4,066, the first ten-request batch 5,060 (one response with 504 tokens and one with 460), the two twelve-request batches 6,077 and 6,099, and the sixteen-request batch 8,174. The second ten-request batch generated the full 5,120 tokens.

**Observations:**

- At **four** simultaneous requests, aggregate throughput was **3.01×** its single-request baseline; at **eight**, it was **4.44×**.
- Two **ten-request** runs yielded **595.15** and **708.46 tokens/s** respectively. Their arithmetic mean was **651.81 tokens/s**, only ~**1.9%** above the single eight-request observation of **639.74 tokens/s**. However, the individual ten-request results differ by approximately **19% relative to the lower run**, so comparing the mean with a single eight-request run cannot establish a winner.
- **Twelve** concurrent requests achieved **758.46** and **757.51 tokens/s** in two consecutive measurements; mean **757.99 tokens/s**, a difference of approximately **0.13%** between the runs. This is approximately **18.5%** higher than the single eight-request measurement (**639.74 tokens/s**), and is the strongest and most repeatable throughput result for Llama in this series. Compared to a single-request baseline in the same runs, the measured speedup was approximately **6×**.
- At **sixteen**, aggregate throughput fell to **382.04 tokens/s**, and mean worker latency increased to **21.34 s**, versus **6.24 s** at eight. This is a large regression, but only one sixteen-request run was recorded.
- `ollama ps` sizes were **7.2 GB (4)**, **9.5 GB (8)**, **10 GB (10)**, **11 GB (12)**, and **14 GB (16)** on a 16 GB VRAM GPU. Increased memory pressure **might** contribute to the regression; actual VRAM pressure was not measured, and `ollama ps` size must not be equated with total GPU memory use.
- Llama shows a strong concurrency benefit similar to Gemma, but its exact optimal slot count remains unknown. **Twelve slots is the current best measured configuration**, while sixteen slots sharply underperform. Do not treat 12 as a proven global optimum.

### Comparison at eight concurrent requests

| Model | Single-request baseline (tokens/s) | Eight-request aggregate (tokens/s) | Speedup vs. own baseline | Mean worker wall time (s) |
| --- | ---: | ---: | ---: | ---: |
| Gemma 4 E2B | 192.01 | 680.39 | **3.54×** | 5.99 |
| GPT-OSS 20B | 159.56 | 152.46 | **0.96×** | 26.43 |
| Llama 3.1 8B | 144.16 | 639.74 | **4.44×** | 6.24 |

**Interpretation:** For this workload and GPU, Gemma and Llama gain substantial **batch throughput** from concurrent requests; GPT-OSS 20B gains only modestly at two concurrent requests and loses throughput at eight. The per-request response time generally increases with concurrency. Raw throughput alone does **not** measure research-task correctness, model quality or end-to-end agent latency.

## Implications for AskAI (observations, not an implementation mandate)

1. The Research Agent's deterministic task executor / resource scheduler should distinguish **model-specific practical concurrency** from a global worker count. An endpoint can host models with very different scaling behavior.
2. Distinguish the **client-side limit** (requests AskAI permits in flight) from **server-side Ollama slots** (`OLLAMA_NUM_PARALLEL`, an Ollama startup environment setting). ollama4j does not configure this server startup option through a normal inference request.
3. Concurrent requests are useful for independent worker tasks, but high batch throughput may be less important when orchestrator work has dependencies or needs low latency.
4. A server can be shared with other clients; AskAI's own configured capacity does not necessarily equal currently available server capacity.
5. Do **not** hardcode the values observed here as universal defaults for all models, GPUs, context lengths or remote Ollama instances.

## Limits and future experiments (not planned for this session)

- **Llama sweet spot unresolved:** Four slots gave 3.01×, eight gave 4.44×, ten gave 4.22× or 4.82× across two runs, and twelve gave approximately 6× across two highly consistent runs; sixteen regressed substantially. If optimizing later, consider a few repeated trials between 12 and 16 (e.g. 13–14), alongside controlled repeats at eight and ten. No additional tests are required for the present architectural note.
- **Gemma saturation threshold unresolved:** Repeat 16, 20, 24 with multiple runs and compare medians as well as worker latency.
- **GPT-OSS threshold unresolved:** Test 3–4 slots if more precision becomes useful; the 2-versus-8 results already show that eight is disadvantageous under current conditions.
- Measure actual VRAM use (e.g., `nvidia-smi`), prompt-processing time, output generation time, time-to-first-token and p50/p95 latencies; `ollama ps` is not a VRAM utilization trace.
- Test real Research Agent prompts and varied context lengths, tools and generation lengths. Keep reasoning effort controlled for GPT-OSS.
- Keep measurements on different hosts, GPUs, quantizations or remote endpoints in separate series.

**Methodology note:** Earlier PowerShell `Start-Job` experiments were not included. Their staggered launches and process-start overhead make them noncomparable to the asynchronous `HttpClient` results recorded here.
