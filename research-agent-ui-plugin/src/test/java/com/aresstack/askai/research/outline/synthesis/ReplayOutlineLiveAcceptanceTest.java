package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.TopicDendrogram;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import com.aresstack.askai.research.knowledge.topic.AnalysisPassage;
import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.knowledge.topic.TopicAnalyzer;
import com.aresstack.askai.research.knowledge.topic.TopicProfile;
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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test tooling (outside CI, not product code): Phase 3 replayed on the STORED corpus of a real book. The passages,
 * their stored vectors and the concept nodes with their query vectors are read from files; the topic tree, the
 * topic analysis, the brief and the outline are made by the product classes in the order of
 * {@code SharedTopicDiscovery.run} and {@code OutlineGeneration.propose}. A keep list narrows the corpus (the
 * "cleaned corpus" arm) and the deliverable can be set (the "length budget" arm).
 * <p>Runs through {@code :research-agent-ui-plugin:outlineLiveAcceptance --tests '*ReplayOutlineLiveAcceptanceTest'}
 * with {@code -Daskai.outline.live.replay.corpus=<json>} and {@code -Daskai.outline.live.replay.vectors=<raw
 * float32 little endian, passages x dim>}; without the vectors file every passage row carries its own
 * {@code vector}. Optional: {@code .keep} (a file with one passage id per line), {@code .deliverable},
 * {@code .out} (directory), {@code .analysisOnly} (stop after the topic analysis, which needs no model), and the
 * model properties of the live acceptance.
 * <p>The file format is shown by the small self-written sample in {@code src/test/resources/outline-replay};
 * {@link ReplayOutlineSampleTest} runs it through the topic analysis in the normal test run.
 */
public class ReplayOutlineLiveAcceptanceTest {

    @Test
    public void replayStoredCorpus() throws Exception {
        String corpusPath = System.getProperty("askai.outline.live.replay.corpus", "");
        Assume.assumeTrue("replay skipped (no -Daskai.outline.live.replay.corpus)", !corpusPath.isEmpty());
        String url = System.getProperty("askai.outline.live.url", OutlineLiveAcceptanceTest.DEFAULT_URL);
        String model = System.getProperty("askai.outline.live.model", OutlineLiveAcceptanceTest.DEFAULT_MODEL);
        int numCtx = Integer.getInteger("askai.outline.live.numCtx", OutlineLiveAcceptanceTest.DEFAULT_NUM_CTX);
        String deliverable = System.getProperty("askai.outline.live.replay.deliverable", "");
        String keepPath = System.getProperty("askai.outline.live.replay.keep", "");
        boolean analysisOnly = Boolean.getBoolean("askai.outline.live.replay.analysisOnly");
        File out = new File(System.getProperty("askai.outline.live.replay.out", "build/outline-replay"));
        Files.createDirectories(out.toPath());

        JsonObject corpus = JsonParser.parseString(new String(Files.readAllBytes(new File(corpusPath).toPath()),
                StandardCharsets.UTF_8)).getAsJsonObject();
        String vectorsPath = System.getProperty("askai.outline.live.replay.vectors", "");
        ByteBuffer raw = vectorsPath.isEmpty() ? null : ByteBuffer.wrap(Files.readAllBytes(
                new File(vectorsPath).toPath())).order(ByteOrder.LITTLE_ENDIAN);
        Set<String> keep = null;
        if (!keepPath.isEmpty()) {
            keep = new HashSet<String>();
            for (String line : Files.readAllLines(new File(keepPath).toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty()) {
                    keep.add(line.trim());
                }
            }
        }
        final StoredCorpus stored = StoredCorpus.read(corpus, raw, keep);
        System.out.println("[outline-replay] passages=" + stored.passages.size() + " of " + stored.total
                + " anchors=" + stored.anchors.size() + " deliverable='" + deliverable + "' model=" + model);

        TopicAnalysis analysis = stored.analyse();
        JsonArray topics = new JsonArray();
        int significant = 0;
        for (TopicProfile topic : analysis.getTopics()) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", topic.getTopicId());
            entry.addProperty("title", topic.getTitle());
            entry.addProperty("significant", topic.isSignificant());
            entry.addProperty("novel", topic.isNovel());
            entry.addProperty("concept", topic.getConceptAnchorId());
            JsonArray members = new JsonArray();
            for (String id : topic.getPassageIds()) {
                members.add(id);
            }
            entry.add("members", members);
            topics.add(entry);
            significant += topic.isSignificant() ? 1 : 0;
        }
        Files.write(new File(out, "topics.json").toPath(), topics.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[outline-replay] topics=" + analysis.getTopics().size() + " significant=" + significant);
        if (analysisOnly) {
            return;
        }

        // The order of OutlineGeneration.propose.
        List<String> blacklist = Collections.<String>emptyList();
        SynthesisBrief brief = SynthesisBrief.of(new SynthesisContext(corpus.get("title").getAsString(),
                corpus.get("mission").getAsString(), deliverable, corpus.get("language").getAsString(), blacklist),
                analysis, new SynthesisBrief.Texts() {
                    public String text(String passageId) {
                        return stored.texts.get(passageId);
                    }
                }, OutlineSynthesisSettings.defaults());
        System.out.println("[outline-replay] brief topics=" + brief.getTopics().size() + " omitted="
                + brief.getOmittedTopics().size() + " outOfScope=" + brief.getOutOfScopeTopics().size()
                + " furniture=" + brief.getFurnitureTopics().size() + " requirements="
                + brief.getRequirements().size());

        final StringBuilder log = new StringBuilder();
        final StringBuilder prompts = new StringBuilder();
        long started = System.currentTimeMillis();
        Outline outline = new OutlineProposer(new OllamaChat(url, model, numCtx, prompts),
                OutlineSynthesisSettings.defaults(), OutlineLengthSettings.defaults(),
                new OutlineSynthesizer.Telemetry() {
                    public void call(String step, String m, int budget, int attempt, boolean ok, String detail) {
                        String line = "[outline-replay] step=" + step + " model=" + m + " budget=" + budget
                                + " attempt=" + attempt + " ok=" + ok + " " + detail;
                        System.out.println(line);
                        log.append(line).append('\n');
                    }
                }).propose(brief, "replay", new ArrayList<String>(), null);
        log.append("seconds ").append((System.currentTimeMillis() - started) / 1000).append('\n');

        Files.write(new File(out, "outline.json").toPath(), OutlineJson.write(outline).getBytes(
                StandardCharsets.UTF_8));
        Files.write(new File(out, "prompts.log").toPath(), prompts.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder text = new StringBuilder();
        int sections = 0;
        long words = 0;
        for (OutlineChapter chapter : outline.getChapters()) {
            text.append("CHAPTER ").append(chapter.getTitle()).append(" (").append(chapter.getBrief()
                    .getWordTarget()).append(" words)\n");
            for (OutlineSection section : chapter.getSections()) {
                sections++;
                words += section.getBrief().getWordTarget();
                text.append("   SECTION ").append(section.getTitle()).append(" (").append(section.getBrief()
                        .getWordTarget()).append(" words, ").append(section.getProvenance().getPassageIds().size())
                        .append(" passages)\n");
            }
        }
        text.append("TOTAL chapters ").append(outline.getChapters().size()).append(", sections ").append(sections)
                .append(", section words ").append(words).append('\n');
        for (OutlineReport.Finding finding : outline.getReport().getFindings()) {
            text.append("FINDING ").append(finding.isBlocking() ? "BLOCKING " : "warning ").append(
                    finding.getMessage()).append('\n');
        }
        for (OutlineReport.Gap gap : outline.getReport().getGaps()) {
            text.append("GAP ").append(gap.getRequirement()).append('\n');
        }
        for (String note : outline.getReport().getNotes()) {
            text.append("NOTE ").append(note).append('\n');
        }
        text.append("fromAnalysisOnly ").append(outline.getReport().isFromAnalysisOnly()).append('\n');
        Files.write(new File(out, "outline.txt").toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(out, "calls.log").toPath(), log.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("[outline-replay] chapters=" + outline.getChapters().size() + " sections=" + sections
                + " words=" + words + " out=" + out.getAbsolutePath());
    }

    /** The stored corpus of a replay: the kept passages with their vectors, the concept nodes and the frame. */
    static final class StoredCorpus {
        private static final String WORLD = "replay";

        /** Passages in the corpus file, before the keep list. */
        final int total;
        final List<Passage> passages = new ArrayList<Passage>();
        final List<AnalysisPassage> analysisPassages = new ArrayList<AnalysisPassage>();
        final Map<String, String> texts = new LinkedHashMap<String, String>();
        final Map<String, EmbeddingPort.EmbeddingVector> vectors
                = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        final List<ConceptAnchor> anchors = new ArrayList<ConceptAnchor>();
        final EmbeddingPort.EmbeddingVector frame;

        private StoredCorpus(int total, EmbeddingPort.EmbeddingVector frame) {
            this.total = total;
            this.frame = frame;
        }

        /**
         * Reads the corpus. The vectors come from {@code raw} (float32 little endian, one row per passage of the
         * file) or, without it, from the {@code vector} of every passage row. {@code keep} null keeps everything.
         */
        static StoredCorpus read(JsonObject corpus, ByteBuffer raw, Set<String> keep) {
            int dim = corpus.get("dim").getAsInt();
            JsonArray rows = corpus.getAsJsonArray("passages");
            if (raw != null && raw.capacity() != rows.size() * dim * 4) {
                throw new IllegalStateException("vector file holds " + raw.capacity() + " bytes, expected "
                        + rows.size() * dim * 4);
            }
            StoredCorpus stored = new StoredCorpus(rows.size(), new EmbeddingPort.EmbeddingVector("replay", WORLD,
                    floats(corpus.getAsJsonArray("frameVector"))));
            for (int i = 0; i < rows.size(); i++) {
                JsonObject row = rows.get(i).getAsJsonObject();
                String id = row.get("id").getAsString();
                if (keep != null && !keep.contains(id)) {
                    continue;
                }
                float[] vector;
                if (raw == null) {
                    vector = floats(row.getAsJsonArray("vector"));
                    if (vector.length != dim) {
                        throw new IllegalStateException("passage " + id + " carries " + vector.length
                                + " vector values, expected " + dim);
                    }
                } else {
                    vector = new float[dim];
                    for (int d = 0; d < dim; d++) {
                        vector[d] = raw.getFloat((i * dim + d) * 4);
                    }
                }
                String text = row.get("text").getAsString();
                stored.passages.add(new Passage(id, row.get("captureId").getAsString(),
                        Collections.singletonList("s-" + i), row.get("heading").getAsString(), text, WORLD));
                stored.analysisPassages.add(new AnalysisPassage(id, row.get("sourceId").getAsString(),
                        row.get("domain").getAsString(), text));
                stored.texts.put(id, text);
                stored.vectors.put(id, new EmbeddingPort.EmbeddingVector("replay", WORLD, vector));
            }
            for (JsonElement element : corpus.getAsJsonArray("anchors")) {
                JsonObject anchor = element.getAsJsonObject();
                stored.anchors.add(new ConceptAnchor(anchor.get("id").getAsString(),
                        anchor.get("label").getAsString(), anchor.get("path").getAsString(), 1, "",
                        new EmbeddingPort.EmbeddingVector("replay", WORLD, floats(anchor.getAsJsonArray("vector")))));
            }
            return stored;
        }

        /** The order of SharedTopicDiscovery.run (the corpus is below the corpus limit, so nothing is omitted). */
        TopicAnalysis analyse() {
            TopicAnalysisSettings settings = TopicAnalysisSettings.defaults();
            TopicDendrogram.SplitRule split = new TopicAnalyzer(settings, frame).conceptSplit(vectors, anchors);
            LiveTopicTree tree = new LiveOutlineProjectionBuilder().with(settings.getTopicDistance(),
                    settings.getRepresentatives()).discoverTopicTree(passages, vectors, ClusteringMonitor.NONE,
                    split);
            return new TopicAnalyzer(settings, frame).analyze(analysisPassages, vectors, tree, anchors, null,
                    ClusteringMonitor.NONE);
        }
    }

    private static float[] floats(JsonArray values) {
        float[] vector = new float[values.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = values.get(i).getAsFloat();
        }
        return vector;
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
        if (status != 200) {
            throw new IllegalStateException("Ollama answered " + status + " for " + target);
        }
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

    /** The chat of the live acceptance, with every prompt and answer kept for the report. */
    private static final class OllamaChat implements MainModelChat {
        private final String url;
        private final String model;
        private final int numCtx;
        private final StringBuilder prompts;
        private int calls;

        OllamaChat(String url, String model, int numCtx, StringBuilder prompts) {
            this.url = url;
            this.model = model;
            this.numCtx = numCtx;
            this.prompts = prompts;
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
            int number = ++calls;
            for (ChatMessage message : messages) {
                JsonObject entry = new JsonObject();
                entry.addProperty("role", message.getRole().name().toLowerCase());
                entry.addProperty("content", message.getContent());
                wire.add(entry);
                prompts.append("===== CALL ").append(number).append(' ').append(message.getRole().name())
                        .append(" =====\n").append(message.getContent()).append('\n');
            }
            body.add("messages", wire);
            body.add("format", schemaJson == null ? new com.google.gson.JsonPrimitive("json")
                    : JsonParser.parseString(schemaJson));
            JsonObject options = new JsonObject();
            options.addProperty("temperature", temperature);
            options.addProperty("num_predict", maxOutputTokens);
            options.addProperty("num_ctx", numCtx);
            body.add("options", options);
            long started = System.currentTimeMillis();
            try {
                JsonObject answer = post(url + "/api/chat", body);
                String content = answer.getAsJsonObject("message").get("content").getAsString();
                prompts.append("===== CALL ").append(number).append(" ANSWER (").append(
                        System.currentTimeMillis() - started).append(" ms, prompt tokens ").append(
                        answer.has("prompt_eval_count") ? answer.get("prompt_eval_count").getAsString() : "?")
                        .append(") =====\n").append(content).append('\n');
                return MainModelChatResult.ok(content);
            } catch (Exception failed) {
                prompts.append("===== CALL ").append(number).append(" FAILED ").append(failed.getMessage())
                        .append('\n');
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
