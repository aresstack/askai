package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.google.gson.JsonObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Point R on a stored stock (architect, 2026-11-21): opens COPIES of real workspaces twice, headless, through the
 * session start's own knowledge path with real embeddings ({@link ReembeddingStock}), and writes one
 * {@code result.json} per copy before it checks the criteria fixed before the run: both openings ran (K0), no job
 * failed (K1), no orphan vectors afterwards (K2), every active pointer of an eligible capture on the new derivation
 * (K3), the second opening changes no file (K4), manuscript, sources and captures keep their content (K5).
 *
 * <p>Properties ({@code -Daskai.stock.reembed.*}): {@code workspaces} (copies, separated by {@code ;}; required,
 * never a folder inside the app's {@code .askai} folder), {@code out} (default
 * {@code build/component-stock/reembed}), {@code url} (default {@code http://127.0.0.1:11434}), {@code embedModel}
 * (default {@code bge-m3:latest}, the app's id), {@code expectWorld} (stop before opening anything when the resolved
 * fingerprint differs), {@code nlpModels} (the app's {@code models/nlp/opennlp} folder; without it the regex
 * segmenter is used), {@code language} (default {@code de}), {@code projectId} (one copy only; otherwise the id
 * stored in the copy) and {@code timeoutMinutes} per opening (default 180).</p>
 */
public class ReembeddingStockTest {

    private static final String P = "askai.stock.reembed.";

    @Test
    public void opensEachWorkspaceCopyTwice() throws Exception {
        String list = System.getProperty(P + "workspaces", "").trim();
        Assume.assumeTrue("re-embedding stock test skipped (no -D" + P + "workspaces)", !list.isEmpty());
        List<File> copies = new ArrayList<File>();
        for (String path : list.split(";")) {
            if (!path.trim().isEmpty()) {
                copies.add(new File(path.trim()).getAbsoluteFile());
            }
        }
        for (File copy : copies) {
            assertTrue("not a research workspace (no knowledge folder): " + copy,
                    new File(copy, "knowledge").isDirectory());
            assertFalse("only copies are opened, and " + copy + " lies in the app's .askai folder",
                    copy.getAbsolutePath().replace('\\', '/').toLowerCase(Locale.ROOT).contains("/.askai/"));
        }
        File out = new File(System.getProperty(P + "out", "build/component-stock/reembed")).getAbsoluteFile();
        String language = System.getProperty(P + "language", "de").trim();
        long timeout = Long.parseLong(System.getProperty(P + "timeoutMinutes", "180").trim()) * 60_000L;
        String nlpRoot = System.getProperty(P + "nlpModels", "").trim();
        NlpConfigurationSnapshotProvider nlp = null;
        if (!nlpRoot.isEmpty()) {
            File model = ReembeddingStock.sentenceModel(new File(nlpRoot), language);
            assertTrue("no sentence model for '" + language + "' at " + model, model.isFile());
            nlp = ReembeddingStock.openNlp(new File(nlpRoot));
        }
        // the app's embedding timeout (AgentRuntimeServices.EMBEDDING_TIMEOUT_MILLIS)
        EmbeddingEndpointDescriptor descriptor = ReembeddingStock.ollamaWorld(
                System.getProperty(P + "url", "http://127.0.0.1:11434"),
                System.getProperty(P + "embedModel", "bge-m3:latest"), 60_000L);
        String world = HttpEmbeddingPortAdapter.documentFingerprint(descriptor);
        System.out.println("[reembed] embedding world " + world + " (" + descriptor.modelId + ", dimension "
                + descriptor.embeddingDimension + ")");
        String expected = System.getProperty(P + "expectWorld", "").trim();
        assertTrue("the embedding world " + world + " is not the expected " + expected + "; nothing was opened",
                expected.isEmpty() || expected.equals(world));
        String projectIdOverride = System.getProperty(P + "projectId", "").trim();
        assertTrue("-D" + P + "projectId names the project of ONE copy", projectIdOverride.isEmpty()
                || copies.size() == 1);

        Map<String, List<String>> unmet = new LinkedHashMap<String, List<String>>();
        for (File copy : copies) {
            String projectId = projectIdOverride.isEmpty() ? ReembeddingStock.projectIdOf(copy) : projectIdOverride;
            System.out.println("[reembed] opening " + copy + " as " + projectId);
            JsonObject result = ReembeddingStock.run(copy, projectId, descriptor, language, nlp, timeout,
                    System.out);
            File dir = new File(out, copy.getName());
            Files.createDirectories(dir.toPath());
            File json = new File(dir, "result.json");
            Files.write(json.toPath(), ReembeddingStock.toJson(result).getBytes(StandardCharsets.UTF_8));
            List<String> failed = ReembeddingStock.unmet(result);
            System.out.println("[reembed] " + copy.getName() + ": " + (failed.isEmpty() ? "all criteria met"
                    : "criteria not met " + failed) + " -> " + json);
            if (!failed.isEmpty()) {
                unmet.put(copy.getName(), failed);
            }
        }
        assertTrue("criteria not met: " + unmet, unmet.isEmpty());
    }
}
