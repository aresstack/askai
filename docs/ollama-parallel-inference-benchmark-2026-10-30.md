# Ollama: Parallel Inference Benchmark (RTX 5080, 2026-10-30)

## Purpose and status

Empirical measurements of parallel text generation with Ollama, relevant to AskAI's Research Agent, multi-worker execution and model/endpoint resource scheduling.

**Status:** Exploratory benchmark; **not** a finalized tuning decision. Numbers are measurements on one machine and workload, not generally valid model capacities. The optimal parallelism for Llama 3.1 8B (and the exact saturation threshold of Gemma) has **not** been determined.

Related: [ADR 0001](../adr/0001-research-agent-architecture-after-java-21.md), especially its model/endpoint resource scheduler.

## Test setup and method

- **Hardware:** NVIDIA GeForce RTX 5080, **16 GB VRAM**, one GPU.
- **Inference:** Ollama server, tested via HTTP from Windows/PowerShell.
- **Script:** Existing `C:\tmp\ollama\ollama-parallel-async.ps1` (no change to the script required). It launches requests asynchronously through .NET `HttpClient`, rather than sequentially or through PowerShell `Start-Job` worker processes.
- **Test shape:** Warm-up, a single-request baseline, then N nearly simultaneous calls to the *same loaded model*.
- **Generation settings:** `num_ctx=8192`, `num_predict=512`, `temperature=0.2`, `stream=false`, `keep_alive=10m`. One identical English prompt about GPU inference batching for each request.
- **Server options:** `OLLAMA_NUM_PARALLEL` set to the intended test slot count before starting the server; `OLLAMA_FLASH_ATTENTION=1`; `OLLAMA_KV_CACHE_TYPE=q8_0`. Tests used the configured model directory `X:\ollama`.
- **Metric:** Aggregate output throughput = sum of generated tokens / elapsed batch wall time. The per-worker token rates are separate measurements. A displayed `100% GPU` in `ollama ps` indicates GPU model placement, **not** an independent measurement of the GPU's utilization or free VRAM.
- **Comparability:** Each configuration uses a different number of simultaneously running requests. Warm-up and request launch timings were checked. Except for 20-slot Gemma, only one valid asynchronous run per configuration is recorded.

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

| Concurrent requests / slots | Single baseline (tokens/s) | Batch output tokens | Batch time (s) | Aggregate output (tokens/s) | Mean worker wall time (s) | Success | Launch spread |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 8 | 144.16 | 4,066 | 6.36 | **639.74** | 6.24 | 8/8 | 7.2 ms |

`ollama ps` reported about **9.5 GB**, **100% GPU**, `CONTEXT 8192`. Six responses generated 512 tokens, two completed at 496 and 498 tokens respectively, resulting in 4,066 output tokens.

**Observations:**

- At eight simultaneous requests, aggregate throughput is **4.44×** the single-request baseline (639.74 versus 144.16 tokens/s).
- Llama shows a **qualitatively similar parallel scaling benefit to Gemma** at eight workers, although its measured aggregate output is somewhat lower (Gemma: 680.39 tokens/s at eight).
- **Only eight-way parallelism was tested for Llama** (plus the single-request baseline). We **do not know** whether the speedup was already attained with four workers, whether eight is optimal, or whether even more concurrency increases throughput. These remain follow-up questions, not results.

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

- **Llama sweet spot unresolved:** Repeat tests with 2, 4, 8, 12, 16 (and perhaps higher) concurrent slots. In particular, test whether four already achieves a roughly fourfold gain.
- **Gemma saturation threshold unresolved:** Repeat 16, 20, 24 with multiple runs and compare medians as well as worker latency.
- **GPT-OSS threshold unresolved:** Test 3–4 slots if more precision becomes useful; the 2-versus-8 results already show that eight is disadvantageous under current conditions.
- Measure actual VRAM use (e.g., `nvidia-smi`), prompt-processing time, output generation time, time-to-first-token and p50/p95 latencies; `ollama ps` is not a VRAM utilization trace.
- Test real Research Agent prompts and varied context lengths, tools and generation lengths. Keep reasoning effort controlled for GPT-OSS.
- Keep measurements on different hosts, GPUs, quantizations or remote endpoints in separate series.

**Methodology note:** Earlier PowerShell `Start-Job` experiments were not included. Their staggered launches and process-start overhead make them noncomparable to the asynchronous `HttpClient` results recorded here.
