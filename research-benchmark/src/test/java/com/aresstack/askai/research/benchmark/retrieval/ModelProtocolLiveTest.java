package com.aresstack.askai.research.benchmark.retrieval;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The model probes of the measurement protocol that need nothing but Ollama (plan 5.4 items 2, 3, 4 and 9), run
 * only by {@code gradlew :research-benchmark:benchmark}: characters per token for English and German and tokens per
 * German word, {@code load_duration} for every switch between the phase models, total GPU memory after each load
 * ({@code nvidia-smi}; {@code size_vram} would show only Ollama), and whether {@code truncate=false} makes an overlong
 * chat or embedding call fail. Skips when Ollama is not reachable. The report has no machine paths.
 */
public class ModelProtocolLiveTest {

    private static final String P = "askai.benchmark.";

    @Test
    public void modelProtocol() throws Exception {
        String ollama = System.getProperty(P + "ollamaUrl", "http://127.0.0.1:11434").replaceAll("/+$", "");
        // One call may take this long (default 180 s): a cold load of gpt-oss:20b takes well under a minute, so a
        // longer wait is a stuck load and ends with a clear message instead of blocking the run for 15 minutes.
        int callSeconds = Integer.getInteger(P + "callTimeoutSeconds", 180);
        ProtocolProbes probes = new ProtocolProbes(ollama, callSeconds * 1000);
        String version = probes.version();
        Assume.assumeFalse("Ollama not reachable at the configured URL", "unknown".equals(version));
        List<String> models = ProtocolProbes.models(System.getProperty(P + "protocolModels",
                "gemma4:e2b,llama3.1:8b,gpt-oss:20b"));
        String embeddingModel = System.getProperty(P + "protocolEmbeddingModel", "nomic-embed-text");
        JsonObject samples;
        try (InputStream in = getClass().getResourceAsStream("/benchmark/retrieval/prose-samples.json")) {
            samples = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        String en = samples.get("en").getAsString();
        String de = samples.get("de").getAsString();
        int germanWords = de.trim().split("\\s+").length;

        StringBuilder md = new StringBuilder("# Model protocol\n\nOllama ").append(version)
                .append(". GPU memory is the nvidia-smi total of all processes.\n\n");
        md.append("## Characters per token (item 2)\n\n| Model | EN chars/token | DE chars/token | DE tokens/word |\n"
                + "|---|---|---|---|\n");
        for (String model : models) {
            log("token probe " + model);
            long enTokens = probes.promptTokens(model, en, 8192);
            long deTokens = probes.promptTokens(model, de, 8192);
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", model,
                    ratio(en.length(), enTokens), ratio(de.length(), deTokens),
                    deTokens <= 0 ? "?" : String.format(Locale.ROOT, "%.2f", deTokens / (double) germanWords)));
        }
        // Before each switch every loaded model is unloaded (keep_alive 0) and the test waits for /api/ps to be
        // empty: load_duration is then a cold load, and a hang is attributed to the load, not to an eviction.
        // After each load /api/ps must list exactly the requested model, which is the reload proof (and for
        // llama3.1:8b the protocol half of the phase.draft check).
        md.append("\n## Model switches (items 3 and 4)\n\n"
                + "| From | To | num_ctx | load_duration ms | GPU MiB after | /api/ps after | Reloaded |\n"
                + "|---|---|---|---|---|---|---|\n");
        String previous = "(start)";
        List<String> failures = new ArrayList<String>();
        for (int round = 0; round < 2; round++) {
            for (String model : models) {
                int numCtx = numCtx(model);
                log("unloading before switch " + previous + " -> " + model);
                List<String> left = probes.unloadAll(60_000);
                if (!left.isEmpty()) {
                    log("still loaded after 60 s: " + left);
                }
                log("loading " + model + " num_ctx=" + numCtx);
                long started = System.currentTimeMillis();
                String load;
                String ps;
                String reloaded;
                try {
                    long ms = probes.loadDurationMillis(model, numCtx);
                    load = ms < 0 ? "failed" : String.valueOf(ms);
                    List<ProtocolProbes.LoadedModel> loaded = probes.loadedModels();
                    ps = loaded.toString().replace('|', '/');
                    reloaded = reloadVerdict(model, ms, loaded);
                } catch (ProtocolProbes.ProbeTimeoutException timeout) {
                    load = "timeout";
                    ps = "-";
                    reloaded = "no: " + timeout.getMessage().replace('|', '/');
                    failures.add(previous + " -> " + model + ": " + timeout.getMessage());
                }
                log(model + " load_duration=" + load + " ms, wall " + (System.currentTimeMillis() - started)
                        + " ms, ps " + ps);
                long gpu = ProtocolProbes.gpuMemoryUsedMiB();
                md.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s |%n", previous, model,
                        numCtx, load, gpu < 0 ? "not measured" : String.valueOf(gpu), ps, reloaded));
                previous = model;
            }
        }
        md.append("\n## truncate=false (item 9)\n\n| Call | Model | HTTP status | Failed as required |\n"
                + "|---|---|---|---|\n");
        if (!models.isEmpty()) {
            ProtocolProbes.Answer chat = probes.overlongChat(models.get(0), 2048);
            md.append(String.format(Locale.ROOT, "| chat | %s | %d | %s |%n", models.get(0), chat.status,
                    truncationVerdict(probes.shortChat(models.get(0), 2048), chat)));
        }
        ProtocolProbes.Answer embed = probes.overlongEmbed(embeddingModel, 20000);
        md.append(String.format(Locale.ROOT, "| embed | %s | %d | %s |%n", embeddingModel, embed.status,
                truncationVerdict(probes.overlongEmbed(embeddingModel, 5), embed)));

        File dir = new File(System.getProperty(P + "reportDir", "build/reports/research-benchmark"));
        dir.mkdirs();
        File out = new File(dir, "model-protocol-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".md");
        Files.write(out.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(md);
        System.out.println("[model-protocol] report " + out.getName());
        if (!failures.isEmpty()) {
            throw new AssertionError("model switch timed out (report " + out.getName() + "): " + failures);
        }
    }

    /**
     * Reload proof of one switch: Ollama reported a positive {@code load_duration} and {@code /api/ps} lists the
     * requested model (fully in VRAM, otherwise the GPU share is named).
     */
    static String reloadVerdict(String model, long loadMillis, List<ProtocolProbes.LoadedModel> loaded) {
        if (loadMillis < 0) {
            return "no: generate failed";
        }
        for (ProtocolProbes.LoadedModel m : loaded) {
            if (m.name.equals(model) || m.name.equals(model + ":latest")) {
                if (loadMillis == 0) {
                    return "no: load_duration 0, model was still loaded";
                }
                return m.gpuPercent() >= 100 ? "yes" : "yes, only " + m.gpuPercent() + "% on GPU";
            }
        }
        return "no: not in /api/ps";
    }

    private static void log(String line) {
        System.out.println("[model-protocol " + new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date())
                + "] " + line);
    }

    /**
     * The context of one model: {@code -Daskai.benchmark.numCtx.<model>=32768} overrides the presets of PR 03
     * (gemma4:e2b 32768, llama3.1:8b and gpt-oss:20b 16384, others 8192), e.g. for llama3.1:8b at 32k (item 3).
     */
    static int numCtx(String model) {
        Integer override = Integer.getInteger(P + "numCtx." + model);
        if (override != null && override > 0) {
            return override;
        }
        if ("gemma4:e2b".equals(model)) {
            return 32768;
        }
        return "llama3.1:8b".equals(model) || "gpt-oss:20b".equals(model) ? 16384 : 8192;
    }

    /**
     * Item 9 verdict: only a context-length rejection counts. A failing short control call (model missing or not
     * loadable) makes the check unavailable, and any other error stays unclear.
     */
    static String truncationVerdict(ProtocolProbes.Answer control, ProtocolProbes.Answer overlong) {
        if (control == null || control.status != 200) {
            return "unavailable: control call failed (HTTP " + (control == null ? "?" : control.status) + ")";
        }
        if (overlong.status == 200) {
            return "no: it answered";
        }
        String body = overlong.body == null ? "" : overlong.body.toLowerCase(Locale.ROOT);
        if (body.contains("context") || body.contains("length") || body.contains("too long")
                || body.contains("exceed")) {
            return "yes";
        }
        return "unclear: HTTP " + overlong.status + " "
                + (overlong.body == null ? "" : overlong.body.replace('|', '/').replaceAll("\\s+", " ")
                .substring(0, Math.min(120, overlong.body.length())));
    }

    private static String ratio(int chars, long tokens) {
        return tokens <= 0 ? "?" : String.format(Locale.ROOT, "%.2f", chars / (double) tokens);
    }
}
