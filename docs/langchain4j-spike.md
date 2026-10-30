# LangChain4j spike (ADR 0001, work item I)

Status: spike result, input for the ADR decision. The ADR text itself is unchanged; the section proposed for it
is at the end of this page.

## What was built

One worker role built twice, as ADR 0001 section 3.4 asks: the **search planner** of lane G
(`SearchPlannerWorker`, slot `planner`, no tools, schema-bound answer, one repair round as a setting).

| | Own loop (product) | LangChain4j twin (spike) |
|---|---|---|
| Class | `research-agent-runtime` `SearchPlannerWorker` | `research-agent-ui-plugin` source set `langchain4jSpike`: `LangChain4jSearchPlannerWorker` |
| Model access | `WorkerContext.chat()` → `PortMainModelChat` → host `ModelPort` | `ModelPortChatModel` (a LangChain4j `ChatModel` over the host `ModelPort`) |
| Loop | a `for` loop: call, strict reader, send the reason back | `AiServices` proxy with chat memory; the repair round is an `OutputGuardrail` that re-prompts |
| Prompt, schema, strict reader | shared byte for byte (the twin sits in the same package and calls them) | same |

Only the engine differs. `SearchPlannerTwinTest` runs both twins against the same scripted model port and pins
that they send **identical requests** to the port (slot, schema, temperature, limits, every message) and reach
the same result: valid plan, repair after a repeated query, failure after a second invalid answer, retryable
model failure.

The spike lives in its own source set. LangChain4j is on neither the plugin's `runtimeClasspath` nor its
`lib/`; `pluginDistribution` and the host-API guard are untouched (checked: 60 jars, no `langchain4j-*`, no
`jackson-*`).

Controllability was probed separately, because the planner has no tools: `GuardedToolLoopTest` drives
LangChain4j's tool loop with concept-board shaped tools and our guards (`GuardedToolExecutors`).

## Results

### Code size (non-blank, non-comment lines)

| Part | Own loop | LangChain4j |
|---|---|---|
| Role loop (`execute` and helpers) | 38 | 68 |
| Prompt, schema, strict reader | 75 (shared) | 75 (shared) |
| One-time model adapter | 0 (`PortMainModelChat`, exists) | 156 (`ModelPortChatModel`) |
| Tool guards for tool-using roles | inside the loop | 79 (`GuardedToolExecutors`, executor wrappers) |

LangChain4j makes this role larger, not smaller: our worker loop is already small, and every rule (strict
reader, repair reason, budget, cancel) has to be translated into a framework extension point.

### Controllability

| Guard / need | Pluggable in LangChain4j 1.22.0? |
|---|---|
| Receipts per tool call | yes, by wrapping each `ToolExecutor` (or observing via `afterToolExecution`) |
| Post-probe sensor lock (refuse a mutation before the host) | yes, but only by wrapping the `ToolExecutor`; `beforeToolExecution` is a `Consumer` and cannot veto |
| Exact-repetition guard | yes, same place |
| Tool-round budget with a graceful close of the turn | **no**: `maxSequentialToolsInvocations` throws; the turn's text is lost and the caller must build the close itself (our `ConceptToolRounds` closes the turn with what it has) |
| Repair round with the reader's reason | yes, `OutputGuardrail.reprompt`; **`maxRetries` counts attempts, not repairs** (`maxRetries(1)` sends nothing back; found by the twin test) |
| Schema limits from settings (`maxItems`, `minLength`, `maxLength`) | **not in the typed schema model** (`JsonArraySchema`/`JsonStringSchema` have no limits); only as `JsonRawSchema`, which the adapter passes verbatim |
| Booking tokens on the task budget | **not through today's `WorkerContext`**: it hands out the product chat transport, while the twin needs the port. The twin counts its own tokens; booking them would need a runtime interface change (not made) |
| Cancel | between attempts (guardrail returns `fatal`) or by interrupting the calling thread; no handle on the AI service call |
| Model access, slots, scheduling, residency | stay in the platform: the adapter is one port call on one slot, LangChain4j never sees a model name |

### Dependencies it would bring into the plugin `lib/` (resolved for `dev.langchain4j:langchain4j:1.22.0`)

| Jar | Size |
|---|---|
| `langchain4j-1.22.0`, `langchain4j-core-1.22.0` | 455 KB + 776 KB |
| `jackson-databind-2.22.1`, `jackson-core-2.22.1`, `jackson-annotations-2.22` | 2.3 MB |
| `opennlp-tools-2.5.11` | 1.4 MB, **conflicts** with the plugin's `opennlp-tools:1.9.4` (sentence resolver); Gradle would upgrade it |
| `mutiny-zero-1.3.1`, `slf4j-api-2.0.18`, `jspecify-1.0.1` | 127 KB |

About 5 MB in total. Jackson back in the plugin `lib/` contradicts ADR 3.1 and section 6 ("removed: Jackson in
the plugin `lib/`"), and the OpenNLP major-version upgrade would touch the knowledge pipeline.

### Reliability with `gpt-oss:20b` and `gemma4:e2b`

The model-free part (identical wire requests) means that on the same model both twins see the same input, so a
difference in reliability can only come from the engine. The live run needs Ollama and both models and
therefore runs on the owner's machine:

```text
gradlew :research-agent-ui-plugin:langchain4jSpikeLive -Daskai.spike.models=gpt-oss:20b,gemma4:e2b
```

Options: `-Daskai.spike.runs=5` (runs per aspect, four aspects), `-Daskai.spike.ollama=http://localhost:11434`,
`-Daskai.spike.timeoutSeconds=180`. It goes through the platform's own `OllamaModelPort` (as the benchmark
does), alternates which twin goes first, and writes
`research-agent-ui-plugin/build/reports/langchain4j-spike/report.md` (completed/failed/retryable, repair calls,
queries per plan, ms and tokens per task, every failure reason). Without Ollama it skips with "no Ollama
reachable at ...", with a missing model it names the model.

## Proposed ADR section (for the owner; not applied)

> **3.4.1 LangChain4j spike result (work item I, 2026-10-30)**
>
> The search planner was built a second time with LangChain4j 1.22.0 (`AiServices`, an `OutputGuardrail` for
> the repair round) over a `ChatModel` adapter on the host model port, in a separate plugin source set. Both
> twins send identical requests to the port. Findings: the role loop grows from 38 to 68 lines plus a 156-line
> adapter; receipts, the sensor lock and the repetition guard can be plugged in only by wrapping each
> `ToolExecutor`; the tool-round budget ends with an exception instead of a graceful close; settings-driven
> schema limits need raw schemas; per-task token booking would need a change to `WorkerContext`; bundling would
> add about 5 MB to the plugin `lib/`, including Jackson and an OpenNLP 2.x that conflicts with the plugin's
> 1.9.4.
>
> Decision: LangChain4j is **not adopted** as the engine of the role loop. The roles keep the own loop over the
> model port. The spike source set stays as the measurement fixture; the question is reopened only if the live
> comparison (`langchain4jSpikeLive` on `gpt-oss:20b` and `gemma4:e2b`) shows a reliability advantage of the
> LangChain4j twin, or a future role needs a LangChain4j capability the own loop lacks.
