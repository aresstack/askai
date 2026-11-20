package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.modelport.ModelPortSettings;
import com.aresstack.askai.modelport.OllamaModelPort;
import com.aresstack.askai.research.benchmark.BenchmarkModelPortAccess;
import com.aresstack.askai.research.benchmark.OllamaTokenMeter;
import com.aresstack.askai.research.drafting.RuntimeSectionWriter;
import com.aresstack.askai.research.drafting.SectionWriteRequest;
import com.aresstack.askai.research.drafting.SectionWritingJob;
import com.aresstack.askai.research.drafting.WritingOutcome;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.orchestrator.InMemoryTaskBoard;
import com.aresstack.askai.research.runtime.writer.ClaimFlag;
import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.runtime.writer.SectionDraft;
import com.aresstack.askai.research.runtime.writer.SectionWriter;

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

/**
 * The writer A/B of plan PR 31 (section 5.4), opt-in through the {@code benchmark} task:
 * <pre>gradlew :research-benchmark:benchmark --tests '*WriterAbLiveTest*'
 *   -Daskai.benchmark.writerAb.input=path/to/writer-ab-input.json</pre>
 * Each arm writes the first three sections alone and then the whole chapter through the PR 29 writing job (each
 * section receiving the digest of the one before), through the productive section writer on the product's model
 * port. The run writes the blind rating sheet, the anonymous measurements and the key into the report directory.
 * After the owner filled the sheet, a second call with {@code -Daskai.benchmark.writerAb.rated=<sheet>} and
 * {@code -Daskai.benchmark.writerAb.key=<key>} prints the resolved ranking and the preset the rule chooses.
 */
public class WriterAbLiveTest {

    private static final String P = "askai.benchmark.";
    /** {@code num_ctx} of every arm ({@code -Daskai.benchmark.writerAb.contextTokens}): the writer preset of 4.1. */
    static final int DEFAULT_CONTEXT_TOKENS = 16384;

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

    @Test
    public void writeTheSamples() throws Exception {
        String inputPath = System.getProperty(P + "writerAb.input", "");
        Assume.assumeFalse("set -Daskai.benchmark.writerAb.input to the exported chapter (see README)",
                inputPath.isEmpty());
        WriterAbInput input = WriterAbInput.read(new File(inputPath));
        String ollama = System.getProperty(P + "ollamaUrl", "http://127.0.0.1:11434").replaceAll("/+$", "");
        long timeout = Long.getLong(P + "writerAb.callTimeoutMillis", 900_000L);
        List<String> only = list(System.getProperty(P + "models", ""));
        File reports = new File(System.getProperty(P + "reportDirectory", "build/benchmark-reports"));
        reports.mkdirs();

        List<WriterAbSample> samples = new ArrayList<WriterAbSample>();
        List<String> models = new ArrayList<String>();
        try (OllamaTokenMeter meter = OllamaTokenMeter.start(ollama, timeout)) {
            int contextTokens = Integer.getInteger(P + "writerAb.contextTokens", DEFAULT_CONTEXT_TOKENS);
            for (WriterAbArm arm : WriterAbArm.parse(System.getProperty(P + "writerAb.arms", ""))) {
                if (!only.isEmpty() && !only.contains(arm.getModel())) {
                    continue;
                }
                models.add(arm.getModel());
                File settings = Files.createTempDirectory("writer-ab").toFile();
                // the same context for every arm, so a large evidence pack is never cut for one model only
                write(new File(settings, "model-port.properties"),
                        "model." + arm.getModel() + ".contextTokens=" + contextTokens + "\n");
                OllamaModelPort port = BenchmarkModelPortAccess.open(arm.getModel(), meter.baseUrl(), timeout,
                        settings);
                SectionWriter writer = SectionWriter.onPort(port, Duration.ofMillis(timeout),
                        Boolean.getBoolean(P + "writerAb.log") ? new java.util.function.Consumer<String>() {
                            public void accept(String line) {
                                System.out.println("[writer] " + line);
                            }
                        } : null);
                // the product's writer checks repeats and carried claims with the sentence-embedding slot as well
                String sentenceModel = System.getProperty(P + "writerAb.sentenceModel", "").trim();
                if (!sentenceModel.isEmpty()) {
                    writer = writer.withSentenceVectors(new OllamaSentenceVectors(ollama, sentenceModel));
                }
                final SectionWriter sectionWriter = writer;
                int answerTokens = ModelPortSettings.writerPreset(arm.getModel()).getAnswerTokens();
                for (int i = 0; i < 3; i++) {
                    WriterAbInput.Section section = input.sections.get(i);
                    samples.add(measure(meter, input, "section " + (i + 1), arm.getModel(), section.passages.size(),
                            new Run() {
                                public List<SectionDraft> drafts() {
                                    return Arrays.asList(sectionWriter.write(input.assignment(section, arm,
                                            answerTokens, "")));
                                }
                            }));
                }
                samples.add(chapterByWritingJob(meter, input, arm, sectionWriter, answerTokens));
            }
        }
        Assume.assumeFalse("no arm selected by -Daskai.benchmark.models", models.isEmpty());

        Random random = new Random(Long.getLong(P + "writerAb.seed", System.nanoTime()));
        List<BlindAssignment> assignments = new ArrayList<BlindAssignment>();
        for (String item : Arrays.asList("section 1", "section 2", "section 3", WriterAbDecision.CHAPTER)) {
            assignments.add(BlindAssignment.shuffle(item, models, random));
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        write(new File(reports, "writer-ab-" + stamp + "-sheet.md"), WriterAbReport.ratingSheet(assignments, samples));
        write(new File(reports, "writer-ab-" + stamp + "-metrics.md"), WriterAbReport.metrics(assignments, samples));
        File key = new File(reports, "writer-ab-" + stamp + "-key.txt");
        write(key, WriterAbReport.key(assignments));
        System.out.println(WriterAbReport.metrics(assignments, samples));
        System.out.println("Rating sheet and key written to " + reports.getAbsolutePath()
                + " (open only the sheet before rating).");
    }

    private interface Run {
        List<SectionDraft> drafts();
    }

    private static WriterAbSample measure(OllamaTokenMeter meter, WriterAbInput input, String item, String model,
                                          int passages, Run run) throws InterruptedException {
        meter.awaitIdle(60_000L);
        OllamaTokenMeter.Usage before = meter.snapshot();
        long start = System.nanoTime();
        StringBuilder text = new StringBuilder();
        Map<String, Integer> counts = emptyCounts();
        for (SectionDraft draft : run.drafts()) {
            if (!draft.isOk()) {
                counts.put("failed sections", counts.get("failed sections") + 1);
                continue;
            }
            text.append(text.length() == 0 ? "" : "\n\n").append(draft.getText().trim());
            for (ClaimFlag flag : draft.getFlags()) {
                counts.put(flag.getKind().label(), counts.get(flag.getKind().label()) + 1);
            }
            counts.put("writer gaps", counts.get("writer gaps") + draft.getGaps().size());
            counts.put("stripped markers", counts.get("stripped markers") + draft.getStrippedMarkers().size());
            if (Boolean.getBoolean(P + "writerAb.log")) {
                System.out.println("[writer] " + item + ": " + draft.getCalls() + " calls, gaps " + draft.getGaps());
            }
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        meter.awaitIdle(60_000L);
        long tokens = meter.snapshot().minus(before).totalTokens();
        return new WriterAbSample(item, model, text.toString(), seconds, tokens,
                WriterSampleMetrics.measure(text.toString(), passages, input.language), counts);
    }


    /**
     * The sample chapter as the product writes it: the PR 29 writing job over a one-chapter outline, on a fresh
     * manuscript store, with the productive section writer adapter; each section's assignment gets the digest of the
     * section the job stored before it.
     */
    private static WriterAbSample chapterByWritingJob(OllamaTokenMeter meter, final WriterAbInput input,
                                                      final WriterAbArm arm, SectionWriter writer,
                                                      final int answerTokens) throws Exception {
        File dir = Files.createTempDirectory("writer-ab-manuscript").toFile();
        final ManuscriptWorkspace manuscript = new ManuscriptWorkspace(new FileManuscriptStore(dir));
        final Map<String, WriterAbInput.Section> byId = new LinkedHashMap<String, WriterAbInput.Section>();
        List<OutlineSection> sections = new ArrayList<OutlineSection>();
        for (WriterAbInput.Section section : input.sections) {
            byId.put(section.id, section);
            sections.add(new OutlineSection(section.id, "ab-chapter", sections.size() + 1, section.title,
                    new OutlineBrief(section.brief, section.keyPoints, section.words), OutlineProvenance.NONE,
                    false, ""));
        }
        Outline outline = new Outline(Arrays.asList(new OutlineChapter("ab-chapter", 1, "Sample chapter",
                new OutlineBrief(input.synopsis, new ArrayList<String>(), 0), OutlineProvenance.NONE, false, "",
                sections)));
        final List<String> order = new ArrayList<String>(byId.keySet());
        final boolean book = Boolean.parseBoolean(System.getProperty(P + "writerAb.book", "false"));
        final Map<String, List<com.aresstack.askai.research.runtime.writer.EvidencePassage>> given =
                new LinkedHashMap<String, List<com.aresstack.askai.research.runtime.writer.EvidencePassage>>();
        RuntimeSectionWriter adapter = new RuntimeSectionWriter(writer, new RuntimeSectionWriter.Assignments() {
            public SectionAssignment assignmentFor(SectionWriteRequest request) {
                String id = request.getSection().getId();
                int index = order.indexOf(id);
                String digest = "";
                if (index > 0) {
                    SectionRevision previous = manuscript.store().current(order.get(index - 1));
                    digest = previous == null ? "" : digest(previous.getText());
                }
                WriterAbInput.Section section = byId.get(id);
                if (!book) {
                    return input.assignment(section, arm, answerTokens, digest);
                }
                // spike of the book level: an evidence ledger (a passage an earlier section cited leaves this pack),
                // the written sections for the repeat check as in the product, and the neighbour sections' key points
                java.util.Set<String> cited = new java.util.HashSet<String>();
                List<String> written = new ArrayList<String>();
                for (int i = 0; i < index; i++) {
                    SectionRevision earlier = manuscript.store().current(order.get(i));
                    List<com.aresstack.askai.research.runtime.writer.EvidencePassage> pack = given.get(order.get(i));
                    if (earlier == null || pack == null) {
                        continue;
                    }
                    written.add(earlier.getText());
                    java.util.regex.Matcher m = MARKERS.matcher(earlier.getText());
                    while (m.find()) {
                        for (String n : m.group(1).split("[,;]")) {
                            int number = Integer.parseInt(n.trim());
                            if (number >= 1 && number <= pack.size()) {
                                cited.add(pack.get(number - 1).getPassageId());
                            }
                        }
                    }
                }
                List<com.aresstack.askai.research.runtime.writer.EvidencePassage> kept =
                        new ArrayList<com.aresstack.askai.research.runtime.writer.EvidencePassage>();
                for (com.aresstack.askai.research.runtime.writer.EvidencePassage passage : section.passages) {
                    if (!cited.contains(passage.getPassageId())) {
                        kept.add(new com.aresstack.askai.research.runtime.writer.EvidencePassage(kept.size() + 1,
                                passage.getSourceId(), passage.getSourceChecksum(), passage.getPassageId(),
                                passage.getText()));
                    }
                }
                given.put(id, kept);
                List<String> others = new ArrayList<String>();
                for (int i = 0; i < order.size(); i++) {
                    if (i != index) {
                        WriterAbInput.Section other = byId.get(order.get(i));
                        others.add((i < index ? "before this one: " : "after this one: ") + other.title + ": "
                                + String.join("; ", other.keyPoints));
                    }
                }
                System.out.println("[writer] book level: section " + (index + 1) + " keeps " + kept.size() + " of "
                        + section.passages.size() + " passages");
                return input.assignment(new WriterAbInput.Section(section.id, section.title, section.brief,
                        section.keyPoints, section.words, kept), arm, answerTokens, digest).toBuilder()
                        .writtenSections(written).otherSections(others).build();
            }
        });
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
        for (String id : order) {
            SectionRevision revision = manuscript.store().current(id);
            if (revision == null) {
                continue;
            }
            text.append(text.length() == 0 ? "" : "\n\n").append(revision.getText().trim());
            for (String line : revision.getClaimFlags()) {
                for (ClaimFlag.Kind kind : ClaimFlag.Kind.values()) {
                    if (line.startsWith(kind.label() + ":")) {
                        counts.put(kind.label(), counts.get(kind.label()) + 1);
                    }
                }
            }
            counts.put("writer gaps", counts.get("writer gaps") + revision.getGaps().size());
        }
        counts.remove("stripped markers");
        return new WriterAbSample(WriterAbDecision.CHAPTER, arm.getModel(), text.toString(), seconds, tokens,
                WriterSampleMetrics.measure(text.toString(), maxPassages(input), input.language), counts);
    }

    private static final java.util.regex.Pattern MARKERS =
            java.util.regex.Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");

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

    /** The last paragraph, cut to about 80 words: what the next section of the chapter continues from. */
    static String digest(String text) {
        List<String> paragraphs = WriterSampleMetrics.paragraphs(text == null ? "" : text);
        if (paragraphs.isEmpty()) {
            return "";
        }
        String[] words = paragraphs.get(paragraphs.size() - 1).split("\\s+");
        return String.join(" ", Arrays.copyOf(words, Math.min(words.length, 80)));
    }

    private static int maxPassages(WriterAbInput input) {
        int max = 0;
        for (WriterAbInput.Section section : input.sections) {
            max = Math.max(max, section.passages.size());
        }
        return max;
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
