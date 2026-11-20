package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.modelport.OllamaModelPort;
import com.aresstack.askai.research.benchmark.BenchmarkModelPortAccess;
import com.aresstack.askai.research.benchmark.OllamaTokenMeter;
import com.aresstack.askai.research.drafting.RuntimeSectionWriter;
import com.aresstack.askai.research.drafting.SectionWriteRequest;
import com.aresstack.askai.research.drafting.SectionWritingJob;
import com.aresstack.askai.research.drafting.WriterSentenceVectors;
import com.aresstack.askai.research.drafting.WritingOutcome;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.runtime.orchestrator.InMemoryTaskBoard;
import com.aresstack.askai.research.runtime.writer.ClaimFlag;
import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.runtime.writer.SectionDraft;
import com.aresstack.askai.research.runtime.writer.SectionWriter;
import com.aresstack.askai.research.store.FileManuscriptStore;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;

/**
 * The writer A/B of plan PR 31 (section 5.4), opt-in through the {@code benchmark} task:
 * <pre>gradlew :research-benchmark:benchmark --tests '*WriterAbLiveTest*'
 *   -Daskai.benchmark.writerAb.input=path/to/chapter.json</pre>
 * Since stage 0 of the writer proposal the A/B writes the way the app writes: the assignment comes from the app's
 * code ({@link WriterAbPipeline}) with the app's writer settings ({@link WriterAbVariant}), the writer has the
 * app's sentence vectors (bge-m3 by default) for its repeat and claim checks, and the chapter run gives every
 * section the digest and the other written sections the app gives it. Each arm, and each pipeline variant of it,
 * writes the first three sections alone and then the whole chapter through the PR 29 writing job, in every run
 * ({@code -Daskai.benchmark.writerAb.runs}, 3 by default). Each run writes its own blind sheet, anonymous
 * measurements and quality figures and its key; the summary over all runs names the arms and is opened after the
 * rating. With {@code -Daskai.benchmark.writerAb.judge=<model>} a judge model checks whether the cited passages carry
 * each cited sentence ({@code -Daskai.benchmark.writerAb.judgeItems}, the chapter by default).
 * After the owner filled a sheet, a second call with {@code -Daskai.benchmark.writerAb.rated=<sheet>} and
 * {@code -Daskai.benchmark.writerAb.key=<key>} prints the resolved ranking and the preset the rule chooses.
 */
public class WriterAbLiveTest {

    private static final String P = "askai.benchmark.";
    /** {@code num_ctx} of every arm ({@code -Daskai.benchmark.writerAb.contextTokens}): the writer preset of 4.1. */
    static final int DEFAULT_CONTEXT_TOKENS = 16384;
    /** The sentence model of the writer checks and of the quality figures; the app's measured default. */
    static final String DEFAULT_SENTENCE_MODEL = "bge-m3";

    @Test
    public void decideFromTheOwnersRanking() throws IOException {
        String rated = System.getProperty(P + "writerAb.rated", "");
        String key = System.getProperty(P + "writerAb.key", "");
        Assume.assumeFalse("set -Daskai.benchmark.writerAb.rated and .key after the blind rating",
                rated.isEmpty() || key.isEmpty());
        String decision = WriterAbReport.decision(OwnerRanking.resolve(read(new File(rated)), read(new File(key))));
        File out = new File(new File(rated).getParentFile(), "writer-ab-decision.md");
        Files.write(out.toPath(), decision.getBytes(StandardCharsets.UTF_8));
        System.out.println(decision);
    }

    /**
     * Exports a chapter of a real project with its complete evidence packs ({@link WriterAbExport}):
     * {@code -Daskai.benchmark.writerAb.export.workspace=<project dir>}, {@code .export.out=<chapter.json>}, optional
     * {@code .export.chapter} (1), {@code .export.outlineRevision} (latest approved), {@code .export.sections} (4),
     * {@code .export.embeddingModel} (found from the installed models), {@code .export.settings}
     * ({@code key=value;...} writer settings, defaults otherwise). The project is copied first and only the copy is
     * read.
     */
    @Test
    public void exportTheChapter() throws Exception {
        String workspace = System.getProperty(P + "writerAb.export.workspace", "");
        Assume.assumeFalse("set -Daskai.benchmark.writerAb.export.workspace to a project directory",
                workspace.isEmpty());
        String ollama = System.getProperty(P + "ollamaUrl", "http://127.0.0.1:11434").replaceAll("/+$", "");
        File copy = Files.createTempDirectory("writer-ab-project").toFile();
        copyTree(new File(workspace).toPath(), copy.toPath());
        WriterAbExport export = new WriterAbExport(copy, ollama);
        String model = System.getProperty(P + "writerAb.export.embeddingModel", "").trim();
        List<String> candidates = model.isEmpty() ? installedEmbeddingModels(ollama) : Arrays.asList(model);
        WriterAbInput input = export.export(Integer.getInteger(P + "writerAb.export.outlineRevision", 0),
                Integer.getInteger(P + "writerAb.export.chapter", 1),
                Integer.getInteger(P + "writerAb.export.sections", 4),
                new WriterAbVariant("export", WriterAbVariant.settings(
                        System.getProperty(P + "writerAb.export.settings", ""))).targets(0),
                System.getProperty(P + "writerAb.export.language", ""),
                export.embeddingWorld(candidates, export.projectId()));
        File out = new File(System.getProperty(P + "writerAb.export.out", "build/writer-ab-chapter.json"));
        if (out.getAbsoluteFile().getParentFile() != null) {
            out.getAbsoluteFile().getParentFile().mkdirs();
        }
        write(out, input.toJson());
        System.out.println("A/B input written to " + out.getAbsolutePath() + ": " + input.origin);
        for (WriterAbInput.Section section : input.sections) {
            System.out.println("  " + section.id + " " + section.title + ": " + section.passages.size()
                    + " passages, " + section.glossary.size() + " glossary lines, target " + section.words);
        }
    }

    /** The installed Ollama models whose name says they embed, the app's bge-m3 first. */
    private static List<String> installedEmbeddingModels(String ollama) throws IOException {
        java.net.HttpURLConnection connection =
                (java.net.HttpURLConnection) new java.net.URL(ollama + "/api/tags").openConnection();
        List<String> models = new ArrayList<String>();
        try (java.io.InputStream in = connection.getInputStream()) {
            com.google.gson.JsonObject tags = com.google.gson.JsonParser.parseString(
                    new String(readAll(in), StandardCharsets.UTF_8)).getAsJsonObject();
            for (com.google.gson.JsonElement m : tags.getAsJsonArray("models")) {
                String name = m.getAsJsonObject().get("name").getAsString();
                String lower = name.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("embed") || lower.contains("bge") || lower.contains("e5")
                        || lower.contains("minilm") || lower.contains("gte")) {
                    models.add(lower.startsWith("bge-m3") ? 0 : models.size(), name);
                }
            }
        }
        return models;
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) > 0; ) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static void copyTree(final java.nio.file.Path from, final java.nio.file.Path to) throws IOException {
        try (java.util.stream.Stream<java.nio.file.Path> paths = Files.walk(from)) {
            for (java.nio.file.Path path : (Iterable<java.nio.file.Path>) paths::iterator) {
                java.nio.file.Path target = to.resolve(from.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(path, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    @Test
    public void writeTheSamples() throws Exception {
        String inputPath = System.getProperty(P + "writerAb.input", "");
        Assume.assumeFalse("set -Daskai.benchmark.writerAb.input to the exported chapter (see README)",
                inputPath.isEmpty());
        final WriterAbInput input = WriterAbInput.read(new File(inputPath));
        String ollama = System.getProperty(P + "ollamaUrl", "http://127.0.0.1:11434").replaceAll("/+$", "");
        long timeout = Long.getLong(P + "writerAb.callTimeoutMillis", 900_000L);
        List<String> only = list(System.getProperty(P + "models", ""));
        File reports = new File(System.getProperty(P + "reportDirectory", "build/benchmark-reports"));
        reports.mkdirs();
        int runs = Math.max(1, Integer.getInteger(P + "writerAb.runs", 3));
        List<WriterAbVariant> variants = WriterAbVariant.parse(System.getProperty(P + "writerAb.variants", ""),
                variantOverrides());
        String sentenceModel = System.getProperty(P + "writerAb.sentenceModel", DEFAULT_SENTENCE_MODEL).trim();
        EmbeddingPort sentences = sentenceModel.isEmpty() ? null
                : WriterAbEmbeddings.port(WriterAbEmbeddings.descriptor(ollama, sentenceModel, 600_000L));
        Function<List<String>, List<float[]>> vectors = sentences == null ? null
                : WriterAbEmbeddings.documents(sentences);
        String judgeModel = System.getProperty(P + "writerAb.judge", "").trim();
        CitationSupportJudge judge = judgeModel.isEmpty() ? null
                : new CitationSupportJudge(ollama, judgeModel, (int) timeout);
        List<String> judgeItems = list(System.getProperty(P + "writerAb.judgeItems", WriterAbDecision.CHAPTER));

        List<WriterAbArm> arms = new ArrayList<WriterAbArm>();
        for (WriterAbArm arm : WriterAbArm.parse(System.getProperty(P + "writerAb.arms", ""))) {
            if (only.isEmpty() || only.contains(arm.getModel())) {
                arms.add(arm);
            }
        }
        Assume.assumeFalse("no arm selected by -Daskai.benchmark.models", arms.isEmpty());
        boolean variantNames = variants.size() > 1 || !WriterAbVariant.APP.equals(variants.get(0).getName());
        List<String> labels = new ArrayList<String>();
        for (WriterAbArm arm : arms) {
            for (WriterAbVariant variant : variants) {
                labels.add(label(arm, variant, variantNames));
            }
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Random random = new Random(Long.getLong(P + "writerAb.seed", System.nanoTime()));
        List<WriterAbSample> all = new ArrayList<WriterAbSample>();
        try (OllamaTokenMeter meter = OllamaTokenMeter.start(ollama, timeout)) {
            int contextTokens = Integer.getInteger(P + "writerAb.contextTokens", DEFAULT_CONTEXT_TOKENS);
            Map<String, SectionWriter> writers = new LinkedHashMap<String, SectionWriter>();
            for (WriterAbArm arm : arms) {
                if (writers.containsKey(arm.getModel())) {
                    continue;
                }
                File settings = Files.createTempDirectory("writer-ab").toFile();
                // the same context for every arm, so a large evidence pack is never cut for one model only
                write(new File(settings, "model-port.properties"),
                        "model." + arm.getModel() + ".contextTokens=" + contextTokens + "\n");
                OllamaModelPort port = BenchmarkModelPortAccess.open(arm.getModel(), meter.baseUrl(), timeout,
                        settings);
                writers.put(arm.getModel(), SectionWriter.onPort(port, Duration.ofMillis(timeout), null)
                        .withSentenceVectors(WriterSentenceVectors.of(sentences)));
            }
            for (int run = 1; run <= runs; run++) {
                List<WriterAbSample> samples = new ArrayList<WriterAbSample>();
                for (WriterAbArm arm : arms) {
                    for (WriterAbVariant variant : variants) {
                        String label = label(arm, variant, variantNames);
                        WriterAbPipeline pipeline = new WriterAbPipeline(input,
                                variant.targets(arm.getWordsPerCall()), arm.getModel());
                        SectionWriter writer = writers.get(arm.getModel());
                        for (int i = 0; i < 3; i++) {
                            String item = "section " + (i + 1);
                            samples.add(alone(meter, input, item, label, writer, pipeline, input.sections.get(i).id,
                                    vectors, judgeItems.contains(item) ? judge : null, run));
                        }
                        samples.add(chapterByWritingJob(meter, input, label, writer, pipeline, vectors,
                                judgeItems.contains(WriterAbDecision.CHAPTER) ? judge : null, run));
                        System.out.println("writer A/B run " + run + ": " + label + " done");
                    }
                }
                List<BlindAssignment> assignments = new ArrayList<BlindAssignment>();
                for (String item : Arrays.asList("section 1", "section 2", "section 3", WriterAbDecision.CHAPTER)) {
                    assignments.add(BlindAssignment.shuffle(item, labels, random));
                }
                String prefix = "writer-ab-" + stamp + (runs > 1 ? "-run" + run : "");
                write(new File(reports, prefix + "-sheet.md"), WriterAbReport.ratingSheet(assignments, samples));
                write(new File(reports, prefix + "-metrics.md"), WriterAbReport.metrics(assignments, samples)
                        + "\n" + WriterAbReport.quality(assignments, samples));
                write(new File(reports, prefix + "-key.txt"), WriterAbReport.key(assignments));
                all.addAll(samples);
            }
        }
        String note = "Input: " + inputPath + " (format " + input.format + (input.origin.isEmpty() ? ""
                : ", " + input.origin) + "). Variants: " + describe(variants) + ". Sentence model: "
                + (sentenceModel.isEmpty() ? "none" : sentenceModel) + ". Judge: "
                + (judge == null ? "none" : judge.getModel() + " on " + judgeItems) + ". Runs: " + runs + ".";
        String summary = WriterAbReport.summary(all, note);
        write(new File(reports, "writer-ab-" + stamp + "-summary.md"), summary);
        System.out.println(summary);
        System.out.println("Rating sheets and keys written to " + reports.getAbsolutePath()
                + " (open only the sheets before rating; the summary names the arms).");
    }

    static String label(WriterAbArm arm, WriterAbVariant variant, boolean variantNames) {
        String model = arm.getModel() + (arm.getWordsPerCall() > 0 ? "|" + arm.getWordsPerCall() : "");
        return variantNames ? model + " @" + variant.getName() : model;
    }

    /** One section written alone, as the app writes it on an empty manuscript. */
    private static WriterAbSample alone(OllamaTokenMeter meter, WriterAbInput input, String item, String label,
                                        SectionWriter writer, WriterAbPipeline pipeline, String sectionId,
                                        Function<List<String>, List<float[]>> vectors, CitationSupportJudge judge,
                                        int run) throws Exception {
        File dir = Files.createTempDirectory("writer-ab-alone").toFile();
        SectionAssignment assignment = pipeline.alone(sectionId, new FileManuscriptStore(dir));
        meter.awaitIdle(60_000L);
        OllamaTokenMeter.Usage before = meter.snapshot();
        long start = System.nanoTime();
        SectionDraft draft = writer.write(assignment);
        double seconds = (System.nanoTime() - start) / 1e9;
        meter.awaitIdle(60_000L);
        long tokens = meter.snapshot().minus(before).totalTokens();
        Map<String, Integer> counts = emptyCounts();
        String text = "";
        List<WriterAbSample.Section> sections = new ArrayList<WriterAbSample.Section>();
        if (!draft.isOk()) {
            counts.put("failed sections", 1);
        } else {
            text = draft.getText().trim();
            for (ClaimFlag flag : draft.getFlags()) {
                counts.put(flag.getKind().label(), counts.get(flag.getKind().label()) + 1);
            }
            counts.put("writer gaps", draft.getGaps().size());
            counts.put("stripped markers", draft.getStrippedMarkers().size());
            sections.add(section(sectionId, text, assignment, draft.getGaps().size(), vectors, judge));
        }
        return new WriterAbSample(item, label, text, seconds, tokens,
                WriterSampleMetrics.measure(text, assignment.getPassages().size(), input.language), counts)
                .with(sections, -1, run);
    }

    private static WriterAbSample.Section section(String id, String text, SectionAssignment assignment, int gaps,
                                                  Function<List<String>, List<float[]>> vectors,
                                                  CitationSupportJudge judge) {
        return new WriterAbSample.Section(id, text, assignment.getPassages(), gaps,
                WriterQualityMetrics.measure(text, assignment.getPassages(), assignment.getSectionWords(), vectors),
                judge == null ? null : judge.judge(text, assignment.getPassages()));
    }

    /**
     * The sample chapter as the app writes it: the PR 29 writing job over a one-chapter outline, on a fresh
     * manuscript store, with the productive section writer adapter and the app's assignments; each section gets the
     * digest of the previous section's text and the other written sections.
     */
    private static WriterAbSample chapterByWritingJob(OllamaTokenMeter meter, WriterAbInput input, String label,
                                                      SectionWriter writer, WriterAbPipeline pipeline,
                                                      Function<List<String>, List<float[]>> vectors,
                                                      CitationSupportJudge judge, int run) throws Exception {
        File dir = Files.createTempDirectory("writer-ab-manuscript").toFile();
        ManuscriptWorkspace manuscript = new ManuscriptWorkspace(new FileManuscriptStore(dir));
        final RuntimeSectionWriter.Assignments app = pipeline.assignments(manuscript.store());
        final Map<String, SectionAssignment> used = new LinkedHashMap<String, SectionAssignment>();
        RuntimeSectionWriter adapter = new RuntimeSectionWriter(writer, new RuntimeSectionWriter.Assignments() {
            public SectionAssignment assignmentFor(SectionWriteRequest request) throws Exception {
                SectionAssignment assignment = app.assignmentFor(request);
                used.put(request.getSection().getId(), assignment);
                return assignment;
            }
        });
        Outline outline = new Outline(Arrays.asList(input.outlineChapter()));
        SectionWritingJob job = new SectionWritingJob(new InMemoryTaskBoard(), manuscript, adapter, null, null);

        meter.awaitIdle(60_000L);
        OllamaTokenMeter.Usage before = meter.snapshot();
        long start = System.nanoTime();
        WritingOutcome outcome = job.writeRemaining(outline);
        double seconds = (System.nanoTime() - start) / 1e9;
        meter.awaitIdle(60_000L);
        long tokens = meter.snapshot().minus(before).totalTokens();

        StringBuilder text = new StringBuilder();
        Map<String, Integer> counts = emptyCounts();
        counts.put("failed sections", outcome.getRemaining().size());
        List<WriterAbSample.Section> sections = new ArrayList<WriterAbSample.Section>();
        List<String> texts = new ArrayList<String>();
        int maxPassages = 0;
        for (WriterAbInput.Section s : input.sections) {
            SectionRevision revision = manuscript.store().current(s.id);
            SectionAssignment assignment = used.get(s.id);
            if (revision == null || assignment == null) {
                continue;
            }
            String sectionText = revision.getText().trim();
            text.append(text.length() == 0 ? "" : "\n\n").append(sectionText);
            texts.add(sectionText);
            maxPassages = Math.max(maxPassages, assignment.getPassages().size());
            for (String line : revision.getClaimFlags()) {
                for (ClaimFlag.Kind kind : ClaimFlag.Kind.values()) {
                    if (line.startsWith(kind.label() + ":")) {
                        counts.put(kind.label(), counts.get(kind.label()) + 1);
                    }
                }
            }
            counts.put("writer gaps", counts.get("writer gaps") + revision.getGaps().size());
            sections.add(section(s.id, sectionText, assignment, revision.getGaps().size(), vectors, judge));
        }
        counts.remove("stripped markers");
        return new WriterAbSample(WriterAbDecision.CHAPTER, label, text.toString(), seconds, tokens,
                WriterSampleMetrics.measure(text.toString(), maxPassages, input.language), counts)
                .with(sections, WriterQualityMetrics.chapterRepetition(texts, vectors), run);
    }

    private static Map<String, Integer> emptyCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("failed sections", 0);
        for (ClaimFlag.Kind kind : ClaimFlag.Kind.values()) {
            counts.put(kind.label(), 0);
        }
        counts.put("writer gaps", 0);
        counts.put("stripped markers", 0);
        return counts;
    }

    private static Map<String, String> variantOverrides() {
        Map<String, String> overrides = new LinkedHashMap<String, String>();
        String prefix = P + "writerAb.variant.";
        for (String name : System.getProperties().stringPropertyNames()) {
            if (name.startsWith(prefix)) {
                overrides.put(name.substring(prefix.length()), System.getProperty(name));
            }
        }
        return overrides;
    }

    private static String describe(List<WriterAbVariant> variants) {
        List<String> parts = new ArrayList<String>();
        for (WriterAbVariant variant : variants) {
            parts.add(variant.getName() + (variant.getSettings().isEmpty() ? " (app defaults)"
                    : " " + variant.getSettings()));
        }
        return String.join(", ", parts);
    }

    private static List<String> list(String csv) {
        List<String> result = new ArrayList<String>();
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) {
                result.add(part.trim());
            }
        }
        return result;
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }
}
