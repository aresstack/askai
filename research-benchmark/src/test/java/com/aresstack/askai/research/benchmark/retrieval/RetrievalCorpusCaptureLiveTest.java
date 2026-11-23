package com.aresstack.askai.research.benchmark.retrieval;

import com.aresstack.askai.plugin.api.service.HostNetwork;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures the retrieval benchmark's corpus on the owner's machine (plan 5.4 item 7): fetches every page of
 * {@code retrieval-sources.json} through {@link HostNetwork} (proxy and certificates of the host), extracts passages
 * with {@link CorpusExtractor} and writes {@code corpus.jsonl} into {@code -Daskai.benchmark.retrievalData}. The
 * page text is third-party and stays in that directory. At the end it resolves every query target and label against
 * the new corpus and fails with the list of terms that no longer match, so a changed page is noticed at once.
 * Run only by {@code gradlew :research-benchmark:benchmark --tests '*RetrievalCorpusCaptureLiveTest*'}.
 */
public class RetrievalCorpusCaptureLiveTest {

    @Test
    public void captureCorpus() throws Exception {
        String dataDir = System.getProperty("askai.benchmark.retrievalData", "").trim();
        Assume.assumeFalse("set -Daskai.benchmark.retrievalData to the local corpus directory", dataDir.isEmpty());
        File dir = new File(dataDir);
        dir.mkdirs();
        JsonObject sources;
        try (InputStream in = getClass().getResourceAsStream("/benchmark/retrieval/retrieval-sources.json")) {
            sources = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        Map<String, RetrievalBenchmarkData.Passage> corpus =
                new LinkedHashMap<String, RetrievalBenchmarkData.Passage>();
        List<String> lines = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (JsonElement element : sources.getAsJsonArray("sources")) {
            JsonObject source = element.getAsJsonObject();
            String id = source.get("id").getAsString();
            String language = source.get("language").getAsString();
            String url = source.get("url").getAsString();
            try {
                List<RetrievalBenchmarkData.Passage> passages = CorpusExtractor.extract(id, language, fetch(url));
                for (RetrievalBenchmarkData.Passage p : passages) {
                    corpus.put(p.id, p);
                    JsonObject line = new JsonObject();
                    line.addProperty("id", p.id);
                    line.addProperty("source", p.source);
                    line.addProperty("language", p.language);
                    line.addProperty("text", p.text);
                    lines.add(line.toString());
                }
                System.out.println("[retrieval-corpus] " + id + ": " + passages.size() + " passages");
                if (passages.isEmpty()) {
                    failed.add(id + " (no passages extracted)");
                }
            } catch (Exception ex) {
                failed.add(id + " (" + ex.getClass().getSimpleName() + ": " + ex.getMessage() + ")");
            }
        }
        // Every configured source belongs to the corpus: a missing distractor would make retrieval look easier.
        if (!failed.isEmpty()) {
            throw new AssertionError("corpus incomplete, nothing written; not captured: " + failed);
        }
        // Validate first (every target and 50 distinct labels), then replace corpus.jsonl atomically.
        RetrievalBenchmarkData.loadQueries().resolve(corpus);
        java.nio.file.Path target = new File(dir, "corpus.jsonl").toPath();
        java.nio.file.Path temp = new File(dir, "corpus.jsonl.tmp").toPath();
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        System.out.println("[retrieval-corpus] " + corpus.size() + " passages written to corpus.jsonl");
        System.out.println("[retrieval-corpus] all 24 targets and 50 distinct labels resolve");
    }

    private static String fetch(String url) throws Exception {
        HttpURLConnection c = HostNetwork.open(url);
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "AskAI research benchmark");
        c.setRequestProperty("Accept-Language", "de,en;q=0.8");
        int status = c.getResponseCode();
        if (status >= 300 && status < 400 && c.getHeaderField("Location") != null) {
            return fetch(new java.net.URL(new java.net.URL(url), c.getHeaderField("Location")).toString());
        }
        if (status != 200) {
            throw new java.io.IOException("HTTP " + status);
        }
        Charset charset = StandardCharsets.UTF_8;
        String type = c.getContentType();
        if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("charset=")) {
            try {
                charset = Charset.forName(type.substring(type.toLowerCase(java.util.Locale.ROOT)
                        .indexOf("charset=") + 8).replace("\"", "").trim());
            } catch (RuntimeException unknown) {
                charset = StandardCharsets.UTF_8;
            }
        }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), charset);
        }
    }
}
