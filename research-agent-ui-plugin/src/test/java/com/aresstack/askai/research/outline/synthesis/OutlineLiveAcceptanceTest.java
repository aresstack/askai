package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import com.aresstack.askai.research.knowledge.topic.AnalysisPassage;
import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.knowledge.topic.TopicAnalyzer;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineJson;
import com.aresstack.askai.research.outline.OutlineReport;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase 3 LIVE acceptance (outside CI): scenario A and E against a local Ollama. A small hand-written test corpus (it
 * is a test fixture, not research) of four independent areas under ONE concept root plus a requirement without
 * evidence is embedded with the embedding model, analysed like the Sources phase does, and outlined by the synthesis
 * model through exactly the path of "Generate outline" ({@link OutlineProposer}). The outline, its report and every
 * model call are printed and written to {@code build/outline-live/}.
 * <p>Runs only with {@code -Daskai.outline.live=true} ({@code ./gradlew :research-agent-ui-plugin:outlineLiveAcceptance}),
 * otherwise it is skipped. Properties: {@code askai.outline.live.url} (default {@value #DEFAULT_URL}),
 * {@code askai.outline.live.model} (default {@value #DEFAULT_MODEL}, the fallback of the outline slots),
 * {@code askai.outline.live.embedding} (default {@value #DEFAULT_EMBEDDING}), {@code askai.outline.live.numCtx}
 * (default {@value #DEFAULT_NUM_CTX}).
 */
public class OutlineLiveAcceptanceTest {

    static final String DEFAULT_URL = "http://127.0.0.1:11434";
    static final String DEFAULT_MODEL = "gemma4:e2b";
    static final String DEFAULT_EMBEDDING = "bge-m3";
    static final int DEFAULT_NUM_CTX = 32768;

    /** Four areas, two aspects each, three passages per aspect from three websites. */
    private static final String[][][] AREAS = {
            {
                    {"Smart helmets with built-in impact sensors record the force of every blow to the head and "
                            + "alert the site manager when a threshold is exceeded.",
                            "Sensor helmets log head impacts on the construction site and send a warning after a "
                                    + "hard hit so that the worker can be examined.",
                            "Impact-detecting hard hats measure acceleration during falls and notify supervisors "
                                    + "automatically."},
                    {"Helmet-mounted cooling fans and sweat sensors help to prevent heat stress during summer "
                            + "work on roofs.",
                            "Heat stress is a growing risk on building sites; helmets that measure skin "
                                    + "temperature can warn before heat exhaustion sets in.",
                            "Cooling liners and temperature monitoring in safety helmets reduce heat illness "
                                    + "among roofers."}
            },
            {
                    {"Ultra-wideband tags worn on the vest locate workers to within thirty centimetres and stop "
                            + "an excavator when someone enters its danger zone.",
                            "Proximity warning systems between wearables and heavy machinery prevent collisions "
                                    + "on busy sites.",
                            "Real-time location of workers near cranes and diggers triggers an alarm on the "
                                    + "machine and on the worker's band."},
                    {"Gas detectors clipped to the collar measure oxygen, carbon monoxide and hydrogen sulphide "
                            + "in shafts and tunnels.",
                            "Personal gas monitors warn workers in confined spaces before toxic concentrations "
                                    + "become dangerous.",
                            "Wearable multi-gas sensors are mandatory for sewer and tunnel work in many "
                                    + "countries."}
            },
            {
                    {"Under the GDPR, location data from workers' wearables is personal data and may only be "
                            + "processed for a stated purpose.",
                            "Employers need a legal basis and a data protection impact assessment before they "
                                    + "track employees with wearables.",
                            "Works councils in Germany have a right of co-determination when technical devices "
                                    + "can monitor employee performance."},
                    {"Workers fear that safety wearables will be used to measure their productivity and "
                            + "breaks.",
                            "Acceptance of wearables on sites depends on transparent rules about who can see the "
                                    + "data.",
                            "Trust grows when the data stays on the device and only alarms are transmitted."}
            },
            {
                    {"A smart helmet costs between three hundred and eight hundred euros, several times the "
                            + "price of a standard hard hat.",
                            "Subscription fees for the cloud platform add a monthly cost per worker to the "
                                    + "hardware price.",
                            "Battery replacement and device management are hidden running costs of site "
                                    + "wearables."},
                    {"Fewer accidents lower insurance premiums and downtime, which can pay back the "
                            + "investment within two years.",
                            "The return on investment of safety wearables comes mainly from avoided lost-time "
                                    + "injuries.",
                            "Insurers offer discounts to contractors who can document a lower accident rate "
                                    + "with wearable data."}
            }
    };

    @Test
    public void fourAreasUnderOneRootAndAMissingRequirementLive() throws Exception {
        Assume.assumeTrue("live acceptance skipped (run with -Daskai.outline.live=true)",
                Boolean.getBoolean("askai.outline.live"));
        String url = System.getProperty("askai.outline.live.url", DEFAULT_URL);
        String model = System.getProperty("askai.outline.live.model", DEFAULT_MODEL);
        String embedding = System.getProperty("askai.outline.live.embedding", DEFAULT_EMBEDDING);
        int numCtx = Integer.getInteger("askai.outline.live.numCtx", DEFAULT_NUM_CTX);
        File out = new File("build/outline-live");
        Files.createDirectories(out.toPath());

        List<Passage> passages = new ArrayList<Passage>();
        List<AnalysisPassage> analysisPassages = new ArrayList<AnalysisPassage>();
        final Map<String, String> texts = new LinkedHashMap<String, String>();
        List<String> ordered = new ArrayList<String>();
        int index = 0;
        for (int area = 0; area < AREAS.length; area++) {
            for (int aspect = 0; aspect < AREAS[area].length; aspect++) {
                for (int i = 0; i < AREAS[area][aspect].length; i++) {
                    String id = String.format("p%03d", index++);
                    String text = AREAS[area][aspect][i];
                    String source = "area" + area + "-site" + i;
                    passages.add(new Passage(id, "cap-" + source, Collections.singletonList("s-" + id), "", text,
                            "live"));
                    analysisPassages.add(new AnalysisPassage(id, source, source + ".example", text));
                    texts.put(id, text);
                    ordered.add(text);
                }
            }
        }
        List<float[]> vectors = embed(url, embedding, ordered);
        Map<String, EmbeddingPort.EmbeddingVector> byId = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int i = 0; i < passages.size(); i++) {
            byId.put(passages.get(i).getPassageId(), new EmbeddingPort.EmbeddingVector(embedding, "live",
                    vectors.get(i)));
        }
        // ONE concept root (the book frame) with one requirement that the research does not cover.
        float[] training = embed(url, embedding, Collections.singletonList(
                "Wearables on construction sites > Training apprentices to use wearables")).get(0);
        List<ConceptAnchor> anchors = Collections.singletonList(new ConceptAnchor("c-training",
                "Training apprentices", "Wearables on construction sites > Training apprentices", 1, "",
                new EmbeddingPort.EmbeddingVector(embedding, "live", training)));

        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(passages, byId,
                ClusteringMonitor.NONE);
        TopicAnalysis analysis = new TopicAnalyzer(TopicAnalysisSettings.defaults()).analyze(analysisPassages, byId,
                tree, anchors, null, ClusteringMonitor.NONE);
        diagnose(url, embedding, passages, vectors, training, analysis, out);
        SynthesisBrief brief = SynthesisBrief.of(new SynthesisContext("Wearables on construction sites",
                "Explain how wearables make construction sites safer and what stands in their way",
                "about 60 pages", "English", Collections.<String>emptyList()), analysis, new SynthesisBrief.Texts() {
                    public String text(String passageId) {
                        return texts.get(passageId);
                    }
                }, OutlineSynthesisSettings.defaults());
        System.out.println("[outline-live] topics=" + brief.getTopics().size() + " groups="
                + brief.getGroupTopicKeys().size() + " requirements=" + brief.getRequirements().size());

        final StringBuilder log = new StringBuilder();
        Outline outline = new OutlineProposer(new OllamaChat(url, model, numCtx), OutlineSynthesisSettings.defaults(),
                OutlineLengthSettings.defaults(), new OutlineSynthesizer.Telemetry() {
                    public void call(String step, String m, int budget, int attempt, boolean ok, String detail) {
                        String line = "[outline-live] step=" + step + " model=" + m + " attempt=" + attempt + " ok="
                                + ok + " " + detail;
                        System.out.println(line);
                        log.append(line).append('\n');
                    }
                }).propose(brief, "live", null, null);

        String json = OutlineJson.write(outline);
        Files.write(new File(out, "outline.json").toPath(), json.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "calls.log").toPath(), log.toString().getBytes(StandardCharsets.UTF_8));
        for (OutlineChapter chapter : outline.getChapters()) {
            System.out.println("[outline-live] " + chapter.getTitle() + " (" + chapter.getBrief().getWordTarget()
                    + " words)");
            for (OutlineSection section : chapter.getSections()) {
                System.out.println("[outline-live]    " + section.getTitle() + " (" + section.getBrief()
                        .getWordTarget() + " words, " + section.getProvenance().getPassageIds().size()
                        + " passages)");
            }
        }
        for (OutlineReport.Finding finding : outline.getReport().getFindings()) {
            System.out.println("[outline-live] finding " + (finding.isBlocking() ? "BLOCKING " : "warning ")
                    + finding.getMessage());
        }
        for (String note : outline.getReport().getNotes()) {
            System.out.println("[outline-live] note " + note);
        }

        assertFalse("the model gave a valid chapter plan", outline.getReport().isFromAnalysisOnly());
        assertTrue("four independent areas are not one chapter", outline.getChapters().size() >= 2);
        boolean trainingGap = false;
        for (OutlineReport.Gap gap : outline.getReport().getGaps()) {
            trainingGap |= gap.getRequirement().startsWith("Training");
        }
        assertTrue("the requirement without evidence is a visible gap", trainingGap);
        for (OutlineReport.Finding finding : outline.getReport().getFindings()) {
            assertFalse(finding.getMessage(), finding.isBlocking());
        }
    }

    /**
     * Calibration data for the embedding model (written to {@code analysis.txt}): each passage's cosine similarity
     * to the requirement anchor and to the bare book frame, the similarity between the areas, and what the analysis
     * made of it (topics, sub-topics, the requirement's coverage). Live outline acceptance 2026-11-12 (bge-m3): the
     * four areas formed one topic and the requirement without evidence counted as covered.
     */
    private static void diagnose(String url, String embedding, List<Passage> passages, List<float[]> vectors,
                                 float[] requirement, TopicAnalysis analysis, File out) throws Exception {
        float[] frame = embed(url, embedding, Collections.singletonList("Wearables on construction sites")).get(0);
        StringBuilder text = new StringBuilder("embedding " + embedding + "\n");
        text.append("passage  area  sim(requirement)  sim(frame)\n");
        for (int i = 0; i < passages.size(); i++) {
            text.append(String.format(java.util.Locale.ROOT, "%s  %d  %.3f  %.3f%n", passages.get(i).getPassageId(),
                    i / 6, cosine(vectors.get(i), requirement), cosine(vectors.get(i), frame)));
        }
        text.append("mean similarity between areas (row x column)\n");
        for (int a = 0; a < AREAS.length; a++) {
            for (int b = 0; b < AREAS.length; b++) {
                double sum = 0;
                int count = 0;
                for (int i = a * 6; i < a * 6 + 6; i++) {
                    for (int j = b * 6; j < b * 6 + 6; j++) {
                        if (i != j) {
                            sum += cosine(vectors.get(i), vectors.get(j));
                            count++;
                        }
                    }
                }
                text.append(String.format(java.util.Locale.ROOT, "%.3f ", sum / count));
            }
            text.append('\n');
        }
        for (com.aresstack.askai.research.knowledge.topic.TopicProfile topic : analysis.getTopics()) {
            text.append("topic ").append(topic.getTopicId()).append(' ').append(topic.getPassageIds().size())
                    .append(" passages, sub-topics");
            for (com.aresstack.askai.research.knowledge.topic.Subtopic subtopic : topic.getSubtopics()) {
                text.append(' ').append(subtopic.getPassageIds());
            }
            text.append('\n');
        }
        for (com.aresstack.askai.research.knowledge.topic.ConceptCoverage coverage : analysis.getCoverage()) {
            text.append("requirement ").append(coverage.getLabel()).append(": ").append(coverage.getStatus())
                    .append(", ").append(coverage.getDistinctPassageCount()).append(" distinct passages ")
                    .append(coverage.getPassageIds()).append('\n');
        }
        System.out.print(text.toString().replaceAll("(?m)^", "[outline-live] "));
        Files.write(new File(out, "analysis.txt").toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static List<float[]> embed(String url, String model, List<String> inputs) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        JsonArray input = new JsonArray();
        for (String text : inputs) {
            input.add(text);
        }
        body.add("input", input);
        JsonObject answer = post(url + "/api/embed", body);
        List<float[]> vectors = new ArrayList<float[]>();
        for (JsonElement row : answer.getAsJsonArray("embeddings")) {
            JsonArray values = row.getAsJsonArray();
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = values.get(i).getAsFloat();
            }
            vectors.add(vector);
        }
        return vectors;
    }

    private static JsonObject post(String target, JsonObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(target).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream stream = connection.getOutputStream()) {
            stream.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
        int status = connection.getResponseCode();
        Assume.assumeTrue("Ollama answered " + status + " for " + target + " (model installed?)", status == 200);
        try (InputStream stream = connection.getInputStream()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            return JsonParser.parseString(new String(buffer.toByteArray(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    /** The synthesis chat against Ollama's chat endpoint with structured output (thinking off where supported). */
    private static final class OllamaChat implements MainModelChat {
        private final String url;
        private final String model;
        private final int numCtx;

        OllamaChat(String url, String model, int numCtx) {
            this.url = url;
            this.model = model;
            this.numCtx = numCtx;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            JsonObject body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("stream", false);
            if (!model.startsWith("llama3.1")) {
                body.addProperty("think", false);
            }
            JsonArray wire = new JsonArray();
            for (ChatMessage message : messages) {
                JsonObject entry = new JsonObject();
                entry.addProperty("role", message.getRole().name().toLowerCase());
                entry.addProperty("content", message.getContent());
                wire.add(entry);
            }
            body.add("messages", wire);
            body.add("format", schemaJson == null ? new com.google.gson.JsonPrimitive("json")
                    : JsonParser.parseString(schemaJson));
            JsonObject options = new JsonObject();
            options.addProperty("temperature", temperature);
            options.addProperty("num_predict", maxOutputTokens);
            options.addProperty("num_ctx", numCtx);
            body.add("options", options);
            try {
                JsonObject answer = post(url + "/api/chat", body);
                return MainModelChatResult.ok(answer.getAsJsonObject("message").get("content").getAsString());
            } catch (Exception failed) {
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        String.valueOf(failed.getMessage()));
            }
        }

        public int contextTokens() {
            return numCtx;
        }

        public String modelName() {
            return model;
        }
    }

}
