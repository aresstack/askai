package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;
import com.aresstack.askai.java8.modelport.ModelPortSettingsRepository;
import com.aresstack.askai.java8.modelport.OllamaModelPort;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The live half of the spike (ADR 0001, item I): both search-planner twins, each aspect several times, on each
 * model, through the platform's own model port on ollama4j (as the benchmark does). Writes a Markdown report to
 * {@code build/reports/langchain4j-spike/}. Skips with a readable reason when Ollama or a model is missing.
 *
 * <pre>gradlew :research-agent-ui-plugin:langchain4jSpikeLive -Daskai.spike.models=gpt-oss:20b,gemma4:e2b</pre>
 * Options: {@code askai.spike.ollama} (default http://localhost:11434), {@code askai.spike.runs} (default 5),
 * {@code askai.spike.timeoutSeconds} (default 180).
 */
public class LangChain4jSpikeLiveTest {

    private static final List<String> ASPECTS = Arrays.asList(
            "heat pump subsidies in Germany since 2024",
            "health effects of microplastics in drinking water",
            "EU AI Act obligations for general purpose AI models",
            "history and current state of the Rhine shipping low water problem");

    /** Counts the port calls one twin makes (one call = one model turn; repairs are calls beyond the first). */
    private static final class CountingPort implements ModelPort {
        final ModelPort delegate;
        final AtomicInteger calls = new AtomicInteger();

        CountingPort(ModelPort delegate) {
            this.delegate = delegate;
        }

        public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
            return delegate.reserve(request);
        }

        public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
            calls.incrementAndGet();
            return delegate.chat(reservation, request, listener);
        }

        public ModelCall chat(ChatRequest request, ChatListener listener) {
            calls.incrementAndGet();
            return delegate.chat(request, listener);
        }

        public ResourceSnapshot resources() {
            return delegate.resources();
        }
    }

    /** The tally of one twin on one model. */
    private static final class Tally {
        final String twin;
        int tasks, completed, failed, retryable, calls, queries;
        long millis, tokens;
        final List<String> failures = new ArrayList<String>();

        Tally(String twin) {
            this.twin = twin;
        }

        String row(String model) {
            return "| " + model + " | " + twin + " | " + completed + "/" + tasks + " | " + failed + " | " + retryable
                    + " | " + (calls - tasks) + " | " + String.format("%.1f", completed == 0 ? 0.0
                    : (double) queries / completed) + " | " + (tasks == 0 ? 0 : millis / tasks) + " | "
                    + (tasks == 0 ? 0 : tokens / tasks) + " |";
        }
    }

    @Test
    public void bothTwinsPlanTheSameAspectsOnEachModel() throws Exception {
        String ollama = System.getProperty("askai.spike.ollama", "http://localhost:11434").replaceAll("/+$", "");
        String modelList = System.getProperty("askai.spike.models", "gpt-oss:20b,gemma4:e2b");
        int runs = Integer.getInteger("askai.spike.runs", 5);
        Duration timeout = Duration.ofSeconds(Integer.getInteger("askai.spike.timeoutSeconds", 180));
        List<String> installed = installedModels(ollama);
        List<String> models = new ArrayList<String>();
        for (String model : modelList.split(",")) {
            if (!model.trim().isEmpty()) {
                models.add(model.trim());
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String model : models) {
            if (!installed.contains(model)) {
                missing.add(model);
            }
        }
        Assume.assumeTrue("models not installed in " + ollama + ": " + missing + " (installed: " + installed
                + "); run `ollama pull <model>` or pass -Daskai.spike.models=", missing.isEmpty());

        StringBuilder report = new StringBuilder("# LangChain4j spike: search planner twins\n\n")
                .append("Ollama ").append(ollama).append(", ").append(runs).append(" runs x ")
                .append(ASPECTS.size()).append(" aspects per model, planner setting 3 queries, 1 repair.\n\n")
                .append("| model | twin | completed | failed | retryable | repair calls | queries/plan | ms/task "
                        + "| tokens/task |\n|---|---|---|---|---|---|---|---|---|\n");
        List<String> notes = new ArrayList<String>();
        for (String model : models) {
            File settings = Files.createTempDirectory("lc4j-spike").toFile();
            OllamaModelPort port = open(model, ollama, timeout, settings);
            try {
                Tally original = new Tally("own loop");
                Tally twin = new Tally("LangChain4j");
                for (int run = 0; run < runs; run++) {
                    for (String aspect : ASPECTS) {
                        // Alternate the order so neither twin always meets a warm model first.
                        if (run % 2 == 0) {
                            runOriginal(port, aspect, timeout, original);
                            runTwin(port, aspect, timeout, twin);
                        } else {
                            runTwin(port, aspect, timeout, twin);
                            runOriginal(port, aspect, timeout, original);
                        }
                    }
                }
                report.append(original.row(model)).append('\n').append(twin.row(model)).append('\n');
                for (Tally tally : Arrays.asList(original, twin)) {
                    for (String failure : tally.failures) {
                        notes.add(model + " / " + tally.twin + ": " + failure);
                    }
                }
            } finally {
                port.close();
            }
        }
        if (!notes.isEmpty()) {
            report.append("\nFailures:\n\n");
            for (String note : notes) {
                report.append("- ").append(note.replace('\n', ' ')).append('\n');
            }
        }
        File out = new File("build/reports/langchain4j-spike/report.md");
        out.getParentFile().mkdirs();
        Files.write(out.toPath(), report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(report);
        System.out.println("report: " + out.getAbsolutePath());
    }

    private static void runOriginal(ModelPort port, String aspect, Duration timeout, Tally tally) {
        CountingPort counting = new CountingPort(port);
        SpikeWorkerContext context = new SpikeWorkerContext(aspect, "", counting, timeout);
        long start = System.nanoTime();
        WorkerResult result = new SearchPlannerWorker(3, 1, 0).execute(context.task, context);
        book(tally, result, context, counting, start, context.tokens.get());
    }

    private static void runTwin(ModelPort port, String aspect, Duration timeout, Tally tally) {
        CountingPort counting = new CountingPort(port);
        SpikeWorkerContext context = new SpikeWorkerContext(aspect, "", counting, timeout);
        long start = System.nanoTime();
        LangChain4jSearchPlannerWorker twin = new LangChain4jSearchPlannerWorker(counting, timeout, 3, 1, 0);
        WorkerResult result;
        try {
            result = twin.execute(context.task, context);
        } catch (RuntimeException unexpected) {
            // An exception the twin did not map is itself a finding: count it as a failure with its type.
            result = WorkerResult.failed("unmapped " + unexpected.getClass().getSimpleName() + ": "
                    + unexpected.getMessage());
        }
        book(tally, result, context, counting, start, twin.tokens());
    }

    private static void book(Tally tally, WorkerResult result, SpikeWorkerContext context, CountingPort counting,
                             long startNanos, long tokens) {
        tally.tasks++;
        tally.calls += counting.calls.get();
        tally.millis += (System.nanoTime() - startNanos) / 1_000_000L;
        tally.tokens += tokens;
        switch (result.getKind()) {
            case COMPLETED:
                tally.completed++;
                tally.queries += context.plannedQueries().size();
                break;
            case RETRYABLE:
                tally.retryable++;
                tally.failures.add(result.getDetail());
                break;
            default:
                tally.failed++;
                tally.failures.add(result.getDetail());
        }
    }

    private static OllamaModelPort open(final String model, final String baseUrl, final Duration timeout,
                                        File settingsDir) {
        return new OllamaModelPort(new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                return Collections.singletonList("spike");
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                return "Ollama (spike)";
            }

            public String baseUrl(String endpointId) {
                return baseUrl;
            }

            public String baseUrlIfRunning(String endpointId) {
                return baseUrl;
            }
        }, new OllamaModelPort.SlotDirectory() {
            public String modelFor(ModelSlot slot) {
                return model;
            }

            public String endpointFor(String ignored) {
                return "spike";
            }

            public List<ModelSlot> knownSlots() {
                return Collections.emptyList();
            }

            public Duration defaultTimeout() {
                return timeout;
            }
        }, new ModelPortSettingsRepository(new File(settingsDir, "model-port.properties")), null);
    }

    /** The reachability probe (as the benchmark's): the installed model names, or a skip with the reason. */
    private static List<String> installedModels(String baseUrl) {
        String body;
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + "/api/tags").openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(5000);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                in.transferTo(bytes);
                body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
        } catch (IOException unreachable) {
            Assume.assumeTrue("no Ollama reachable at " + baseUrl + " (" + unreachable.getMessage()
                    + "); start Ollama or pass -Daskai.spike.ollama=<url>", false);
            return Collections.emptyList();
        }
        List<String> names = new ArrayList<String>();
        for (JsonElement model : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("models")) {
            names.add(model.getAsJsonObject().get("name").getAsString());
        }
        return names;
    }
}
