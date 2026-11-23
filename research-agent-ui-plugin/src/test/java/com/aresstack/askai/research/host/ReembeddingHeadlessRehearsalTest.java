package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.processing.FileSourceProcessingQueue;
import com.aresstack.askai.research.knowledge.processing.KnowledgeProcessingSettings;
import com.aresstack.askai.research.knowledge.processing.ProcessingReconciliation;
import com.aresstack.askai.research.knowledge.processing.QueueBackedKnowledgeProcessingScheduler;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.store.FileResearchSourceRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Rehearses the re-embedding stock test of point R ({@link ReembeddingStockTest}) in every build: a small workspace
 * that an older build processed in its embedding world, with what today's failed start left behind (a queued job of
 * the new world, vectors without a generation), one excluded source and one superseded capture, is opened twice
 * through the session start's knowledge path against a stand-in Ollama. The first opening re-embeds what is
 * eligible, the second writes no file, and every criterion of the stock test holds.
 */
public class ReembeddingHeadlessRehearsalTest {

    private static final String PROJECT = "com.aresstack.askai.research#363bee0e-84f9-417a-8033-aff34a857d2d";
    private static final int DIMENSION = 16;

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private HttpServer ollama;
    private String baseUrl;

    @Before
    public void startOllama() throws IOException {
        ollama = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ollama.createContext("/api/tags", exchange -> respond(exchange, "{\"models\":["
                + "{\"name\":\"bge-m3:latest\",\"model\":\"bge-m3:latest\",\"digest\":\"d-today\"},"
                + "{\"name\":\"old-embed:latest\",\"model\":\"old-embed:latest\",\"digest\":\"d-old\"}]}"));
        ollama.createContext("/api/embed", this::embed);
        ollama.start();
        baseUrl = "http://127.0.0.1:" + ollama.getAddress().getPort();
    }

    @After
    public void stopOllama() {
        ollama.stop(0);
    }

    @Test
    public void anOldWorkspaceIsReembeddedOnceAndItsSecondOpeningWritesNothing() throws Exception {
        File ws = temp.newFolder("com.aresstack.askai.research_363bee0e-84f9-417a-8033-aff34a857d2d");
        assertEquals(PROJECT, ReembeddingStock.projectIdOf(ws));
        FileResearchSourceRepository sources = new FileResearchSourceRepository(new File(ws, "sources"));
        for (int i = 1; i <= 4; i++) {
            sources.put(ResearchSourceRecord.builder("source-" + i).title("Quelle " + i)
                    .url("https://example.org/quelle-" + i).status(SourceStatus.ACCEPTED).capturedAt(1000L + i)
                    .fullText(text(i)).build());
        }
        write(new File(ws, "manuscript/sections/sec-1.md"), "# Kapitel 1\n\nEin geschriebener Abschnitt.\n");
        write(new File(ws, "book.json"), "{\"title\":\"Probe\"}\n");

        // an older build processed the stock in its own embedding world
        EmbeddingEndpointDescriptor old = ReembeddingStock.ollamaWorld(baseUrl, "old-embed:latest", 10_000L);
        ReembeddingStock.Opening older = ReembeddingStock.open(ws, PROJECT, old, "de", null, 60_000L,
                "aeltere Welt", System.out);
        assertTrue(older.drained);
        assertEquals(4, older.reconciled[0]);
        assertEquals(PROJECT, ReembeddingStock.projectIdOf(ws));

        // since then: one source excluded, one capture superseded, and what today's failed start left behind
        sources.put(sources.get("source-4").toBuilder().status(SourceStatus.EXCLUDED).build());
        write(new File(ws, "knowledge/superseded/retired.properties"),
                "captureId=cap-retired\nsourceId=source-retired\n");
        EmbeddingEndpointDescriptor today = ReembeddingStock.ollamaWorld(baseUrl, "bge-m3:latest", 10_000L);
        String world = HttpEmbeddingPortAdapter.documentFingerprint(today);
        new QueueBackedKnowledgeProcessingScheduler(new FileSourceProcessingQueue(new File(ws, "processing")),
                KnowledgeProcessingSettings.defaults(), world)
                .enqueue(ProcessingReconciliation.reconciledCaptureId("source-1"), "source-1", "de");
        File leftover = new File(new File(new File(new File(ws, "knowledge"), "derived"),
                hashOf(ws, ProcessingReconciliation.reconciledCaptureId("source-2"))), "leftover");
        write(new File(leftover, "vectors.bin"), "vectors of a generation that was never committed");

        JsonObject result = ReembeddingStock.run(ws, PROJECT, today, "de", null, 60_000L, System.out);
        System.out.println(ReembeddingStock.toJson(result));

        assertEquals(Collections.<String>emptyList(), ReembeddingStock.unmet(result));
        assertEquals(1, result.getAsJsonObject("vorher").getAsJsonObject("waisen").get("anzahl").getAsInt());
        JsonObject first = result.getAsJsonObject("ersteOeffnung");
        assertEquals(0, first.get("fehlendeJobs").getAsInt());
        // source-1 already has its queued job, source-4 is excluded
        assertEquals(2, first.get("neuEinzubetten").getAsInt());
        assertEquals(1, first.getAsJsonObject("jobUebergaenge").get("QUEUED -> COMPLETED").getAsInt());
        assertEquals(2, first.getAsJsonObject("jobUebergaenge").get("neu -> COMPLETED").getAsInt());
        JsonObject pointers = result.getAsJsonObject("nachZweiterOeffnung").getAsJsonObject("zeiger");
        assertEquals(4, pointers.get("gesamt").getAsInt());
        assertEquals(3, pointers.get("neu").getAsInt());
        assertEquals(1, pointers.get("altNichtZugelassen").getAsInt());
        JsonObject second = result.getAsJsonObject("zweiteOeffnung");
        assertEquals(0, second.get("fehlendeJobs").getAsInt());
        assertEquals(0, second.get("neuEinzubetten").getAsInt());
        assertEquals(0, second.getAsJsonObject("jobUebergaenge").size());
        assertEquals("not even rewritten: " + second.getAsJsonArray("dateien"), 0,
                second.getAsJsonArray("dateien").size());
    }

    /** The derived folder name of a capture, read from its active pointer. */
    private static String hashOf(File ws, String captureId) throws IOException {
        File[] pointers = new File(new File(ws, "knowledge"), "active").listFiles();
        for (File pointer : pointers == null ? new File[0] : pointers) {
            Properties p = ReembeddingStock.properties(pointer);
            if (captureId.equals(p.getProperty("captureId"))) {
                return pointer.getName().substring(0, pointer.getName().length() - ".properties".length());
            }
        }
        throw new AssertionError("no active pointer for " + captureId);
    }

    private static String text(int source) {
        StringBuilder text = new StringBuilder();
        for (int s = 1; s <= 14; s++) {
            text.append("Quelle ").append(source).append(" beschreibt im Satz ").append(s)
                    .append(" eine Beobachtung zu Sensoren auf Baustellen und ihren Daten. ");
            if (s % 5 == 0) {
                text.append("\n\n");
            }
        }
        return text.toString().trim();
    }

    /** A deterministic vector per model and text, so the two worlds differ and every run repeats itself. */
    private void embed(HttpExchange exchange) throws IOException {
        JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8)).getAsJsonObject();
        String model = request.get("model").getAsString();
        JsonArray rows = new JsonArray();
        for (JsonElement input : request.getAsJsonArray("input")) {
            JsonArray vector = new JsonArray();
            long seed = (model + "\n" + input.getAsString()).hashCode();
            for (int d = 0; d < DIMENSION; d++) {
                seed = seed * 6364136223846793005L + 1442695040888963407L;
                vector.add(((seed >>> 33) % 2000L) / 1000.0 - 1.0);
            }
            rows.add(vector);
        }
        JsonObject response = new JsonObject();
        response.addProperty("model", model);
        response.add("embeddings", rows);
        respond(exchange, response.toString());
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void write(File file, String content) throws IOException {
        Files.createDirectories(file.getParentFile().toPath());
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }
}
