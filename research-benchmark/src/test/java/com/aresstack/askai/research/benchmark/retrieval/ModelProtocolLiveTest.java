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
        ProtocolProbes probes = new ProtocolProbes(ollama, 900_000);
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
            long enTokens = probes.promptTokens(model, en, 8192);
            long deTokens = probes.promptTokens(model, de, 8192);
            md.append(String.format(Locale.ROOT, "| %s | %s | %s | %s |%n", model,
                    ratio(en.length(), enTokens), ratio(de.length(), deTokens),
                    deTokens <= 0 ? "?" : String.format(Locale.ROOT, "%.2f", deTokens / (double) germanWords)));
        }
        md.append("\n## Model switches (items 3 and 4)\n\n| From | To | num_ctx | load_duration ms | GPU MiB after |\n"
                + "|---|---|---|---|---|\n");
        String previous = "(start)";
        for (int round = 0; round < 2; round++) {
            for (String model : models) {
                int numCtx = "gemma4:e2b".equals(model) ? 32768 : 16384;
                long load = probes.loadDurationMillis(model, numCtx);
                long gpu = ProtocolProbes.gpuMemoryUsedMiB();
                md.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %s |%n", previous, model, numCtx,
                        load < 0 ? "failed" : String.valueOf(load), gpu < 0 ? "not measured" : String.valueOf(gpu)));
                previous = model;
            }
        }
        md.append("\n## truncate=false (item 9)\n\n| Call | Model | HTTP status | Failed as required |\n"
                + "|---|---|---|---|\n");
        if (!models.isEmpty()) {
            ProtocolProbes.Answer chat = probes.overlongChat(models.get(0), 2048);
            md.append(String.format(Locale.ROOT, "| chat | %s | %d | %s |%n", models.get(0), chat.status,
                    chat.status >= 400 ? "yes" : "no: it answered"));
        }
        ProtocolProbes.Answer embed = probes.overlongEmbed(embeddingModel, 20000);
        md.append(String.format(Locale.ROOT, "| embed | %s | %d | %s |%n", embeddingModel, embed.status,
                embed.status >= 400 ? "yes" : "no: it answered"));

        File dir = new File(System.getProperty(P + "reportDir", "build/reports/research-benchmark"));
        dir.mkdirs();
        File out = new File(dir, "model-protocol-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".md");
        Files.write(out.toPath(), md.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println(md);
        System.out.println("[model-protocol] report " + out.getName());
    }

    private static String ratio(int chars, long tokens) {
        return tokens <= 0 ? "?" : String.format(Locale.ROOT, "%.2f", chars / (double) tokens);
    }
}
