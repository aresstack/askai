package com.aresstack.askai.research.benchmark;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renders one benchmark run as Markdown: environment and settings, a summary per subject, model and question
 * (the stability measurement across repetitions), every single run, the resume scenario, and the method
 * notes. A value that could not be measured is printed as {@code n/a}, never as zero.
 */
public final class BenchmarkReport {

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC);

    private final Instant startedAt;
    private final BenchmarkSettings settings;
    private final int questionSetVersion;
    private final Map<String, String> environment = new LinkedHashMap<String, String>();
    private final Map<String, String> subjects = new LinkedHashMap<String, String>();
    private final List<BenchmarkScorer.RunScores> runs = new ArrayList<BenchmarkScorer.RunScores>();
    private final List<BenchmarkScorer.RunScores> resumeRuns = new ArrayList<BenchmarkScorer.RunScores>();
    private final Map<String, String> questionPurposes = new LinkedHashMap<String, String>();

    public BenchmarkReport(Instant startedAt, BenchmarkSettings settings, int questionSetVersion) {
        this.startedAt = startedAt;
        this.settings = settings;
        this.questionSetVersion = questionSetVersion;
    }

    public void environment(String key, String value) {
        environment.put(key, value);
    }

    public void subject(BenchmarkSubject subject) {
        subjects.put(subject.name(), subject.description());
    }

    public void question(BenchmarkQuestion question) {
        questionPurposes.put(question.getId(), question.getPurpose());
    }

    public void add(BenchmarkScorer.RunScores scores) {
        runs.add(scores);
    }

    public void addResume(BenchmarkScorer.RunScores scores) {
        resumeRuns.add(scores);
    }

    /** Write the report into the configured directory; returns the file. */
    public File write() throws IOException {
        File directory = settings.reportDirectory;
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create the report directory " + directory.getAbsolutePath());
        }
        File file = new File(directory, "research-benchmark-" + FILE_STAMP.format(startedAt) + ".md");
        Files.write(file.toPath(), render().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    public String render() {
        StringBuilder md = new StringBuilder();
        md.append("# Research benchmark report\n\n");
        md.append("- Started: ").append(startedAt).append('\n');
        md.append("- Question set version: ").append(questionSetVersion).append('\n');
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            md.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        md.append("\n## Subjects\n\n");
        for (Map.Entry<String, String> subject : subjects.entrySet()) {
            md.append("- `").append(subject.getKey()).append("`: ").append(subject.getValue()).append('\n');
        }
        md.append("\n## Settings\n\n| Setting | Value |\n|---|---|\n");
        for (Map.Entry<String, String> entry : settings.effectiveValues().entrySet()) {
            md.append("| `").append(BenchmarkSettings.PREFIX).append(entry.getKey()).append("` | ")
                    .append(cell(entry.getValue())).append(" |\n");
        }
        md.append("\n## Questions\n\n");
        for (Map.Entry<String, String> question : questionPurposes.entrySet()) {
            md.append("- `").append(question.getKey()).append("`: ").append(question.getValue()).append('\n');
        }
        renderSummary(md);
        renderRuns(md);
        renderResume(md);
        renderMethod(md);
        return md.toString();
    }

    private void renderSummary(StringBuilder md) {
        md.append("\n## Summary per subject, model and question\n\n");
        md.append("Stability is the share of successful runs (expected stop reason, every fact covered, no "
                + "distractor accepted) and the spread of the page recall across the repetitions.\n\n");
        md.append("| Subject | Model | Question | Runs | Successful | Recall min–max | Precision | Facts | "
                + "Supported claims | Contradictions | Gaps | Tool calls ok | Tokens (in/out) | Wall s | "
                + "First source s | Stop reasons |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        Map<String, List<BenchmarkScorer.RunScores>> groups = new LinkedHashMap<String, List<BenchmarkScorer.RunScores>>();
        for (BenchmarkScorer.RunScores scores : runs) {
            String key = scores.record.getSubject() + "\u0000" + scores.record.getModel() + "\u0000"
                    + scores.record.getQuestionId();
            groups.computeIfAbsent(key, k -> new ArrayList<BenchmarkScorer.RunScores>()).add(scores);
        }
        for (List<BenchmarkScorer.RunScores> group : groups.values()) {
            BenchmarkRunRecord first = group.get(0).record;
            int successful = 0;
            double recallMin = Double.MAX_VALUE;
            double recallMax = 0.0;
            List<Double> precision = new ArrayList<Double>();
            List<Double> facts = new ArrayList<Double>();
            List<Double> supported = new ArrayList<Double>();
            List<Double> tools = new ArrayList<Double>();
            List<Double> wall = new ArrayList<Double>();
            List<Double> firstSource = new ArrayList<Double>();
            long promptTokens = 0;
            long outputTokens = 0;
            long modelCalls = 0;
            int contradictionsDetected = 0;
            int contradictionsPlanted = 0;
            int gapsDetected = 0;
            int gapsPlanted = 0;
            Map<String, Integer> stops = new TreeMap<String, Integer>();
            for (BenchmarkScorer.RunScores scores : group) {
                if (scores.successful()) {
                    successful++;
                }
                recallMin = Math.min(recallMin, scores.pageRecall);
                recallMax = Math.max(recallMax, scores.pageRecall);
                addIfPresent(precision, scores.sourcePrecision);
                addIfPresent(facts, scores.factCoverage());
                addIfPresent(supported, scores.supportedShare());
                addIfPresent(tools, scores.toolCallSuccess);
                wall.add(scores.record.getWallMillis() / 1000.0);
                if (scores.record.getMillisToFirstSource() >= 0) {
                    firstSource.add(scores.record.getMillisToFirstSource() / 1000.0);
                }
                promptTokens += scores.record.getUsage().promptTokens;
                outputTokens += scores.record.getUsage().outputTokens;
                modelCalls += scores.record.getUsage().calls;
                contradictionsDetected += scores.contradictionsDetected;
                contradictionsPlanted += scores.contradictionsPlanted;
                gapsDetected += scores.gapsDetected;
                gapsPlanted += scores.gapsPlanted;
                String stop = scores.record.isFinished() ? scores.record.getStopReason() : "unfinished";
                stops.merge(stop.isEmpty() ? "none" : stop, 1, Integer::sum);
            }
            int n = group.size();
            md.append("| `").append(first.getSubject()).append("` | ").append(cell(first.getModel())).append(" | `")
                    .append(first.getQuestionId()).append("` | ").append(n).append(" | ")
                    .append(successful).append('/').append(n).append(" | ")
                    .append(percent(recallMin)).append("–").append(percent(recallMax)).append(" | ")
                    .append(percentOrNa(mean(precision))).append(" | ")
                    .append(percentOrNa(mean(facts))).append(" | ")
                    .append(percentOrNa(mean(supported))).append(" | ")
                    .append(countOrNa(contradictionsDetected, contradictionsPlanted)).append(" | ")
                    .append(countOrNa(gapsDetected, gapsPlanted)).append(" | ")
                    .append(percentOrNa(mean(tools))).append(" | ")
                    .append(modelCalls == 0 ? "n/a" : (promptTokens / n) + " / " + (outputTokens / n)).append(" | ")
                    .append(decimalOrNa(mean(wall))).append(" | ")
                    .append(decimalOrNa(mean(firstSource))).append(" | ")
                    .append(cell(stops.toString())).append(" |\n");
        }
        if (groups.isEmpty()) {
            md.append("| n/a | | | 0 | | | | | | | | | | | | |\n");
        }
        md.append("\nTokens are means per run, counted from Ollama's `prompt_eval_count` and `eval_count`; "
                + "`n/a` means the subject made no model call that the meter saw.\n");
    }

    private void renderRuns(StringBuilder md) {
        md.append("\n## Single runs\n\n");
        md.append("| Subject | Model | Question | # | Finished | Stop reason | Sufficient | Accepted (hosts) | "
                + "Precision | Distractors | Recall | Facts | Claims supported | Contradictions | Gaps | "
                + "Tool calls (failed) | Model calls (failed) | Tokens in/out | Wall s | Failure |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (BenchmarkScorer.RunScores scores : runs) {
            BenchmarkRunRecord record = scores.record;
            OllamaTokenMeter.Usage usage = record.getUsage();
            md.append("| `").append(record.getSubject()).append("` | ").append(cell(record.getModel()))
                    .append(" | `").append(record.getQuestionId()).append("` | ").append(record.getRepetition())
                    .append(" | ").append(record.isFinished() ? "yes" : "no")
                    .append(" | ").append(cell(record.getStopReason().isEmpty() ? "n/a" : record.getStopReason()))
                    .append(" | ").append(scores.sufficient ? "yes" : "no")
                    .append(" | ").append(scores.acceptedSources).append(" (").append(scores.acceptedHosts).append(')')
                    .append(" | ").append(percentOrNa(scores.sourcePrecision))
                    .append(" | ").append(scores.distractorsAccepted)
                    .append(" | ").append(percent(scores.pageRecall))
                    .append(" | ").append(countOrNa(scores.factsCovered, scores.factsExpected))
                    .append(" | ").append(scores.claims == 0 ? "n/a (no claims)"
                            : scores.supportedClaims + "/" + scores.claims)
                    .append(" | ").append(countOrNa(scores.contradictionsDetected, scores.contradictionsPlanted))
                    .append(" | ").append(countOrNa(scores.gapsDetected, scores.gapsPlanted))
                    .append(" | ").append(record.getToolCalls()).append(" (").append(record.getToolFailures()).append(')')
                    .append(" | ").append(usage.calls).append(" (").append(usage.failedCalls).append(')')
                    .append(" | ").append(usage.calls == 0 ? "n/a" : usage.promptTokens + "/" + usage.outputTokens)
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", record.getWallMillis() / 1000.0))
                    .append(" | ").append(cell(record.getFailure())).append(" |\n");
        }
    }

    private void renderResume(StringBuilder md) {
        md.append("\n## Resume after interruption\n\n");
        if (resumeRuns.isEmpty()) {
            md.append("Not run (`").append(BenchmarkSettings.PREFIX).append("resume=false` or no run reached it).\n");
            return;
        }
        md.append("The run is cancelled after its first accepted source and then continued. A good resume "
                + "finishes sufficient, persists no source twice and does not revisit captured pages.\n\n");
        md.append("| Subject | Model | Question | Sources at interruption | Resumed | Stop reason after | "
                + "Sufficient after | Duplicate sources | Revisited pages | Wall s | Note |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (BenchmarkScorer.RunScores scores : resumeRuns) {
            BenchmarkRunRecord record = scores.record;
            BenchmarkRunRecord.ResumeOutcome resume = record.getResume();
            if (resume == null) {
                md.append("| `").append(record.getSubject()).append("` | ").append(cell(record.getModel()))
                        .append(" | `").append(record.getQuestionId()).append("` | n/a | no | n/a | n/a | n/a | n/a | ")
                        .append(String.format(Locale.ROOT, "%.1f", record.getWallMillis() / 1000.0)).append(" | ")
                        .append(cell(record.getFailure())).append(" |\n");
                continue;
            }
            md.append("| `").append(record.getSubject()).append("` | ").append(cell(record.getModel()))
                    .append(" | `").append(record.getQuestionId()).append("` | ")
                    .append(resume.acceptedBeforeInterruption).append(" | ").append(resume.resumed ? "yes" : "no")
                    .append(" | ").append(cell(resume.stopReasonAfterResume.isEmpty() ? "n/a"
                            : resume.stopReasonAfterResume))
                    .append(" | ").append(resume.sufficientAfterResume ? "yes" : "no")
                    .append(" | ").append(resume.duplicateSources)
                    .append(" | ").append(resume.revisitedPages)
                    .append(" | ").append(String.format(Locale.ROOT, "%.1f", resume.wallMillis / 1000.0))
                    .append(" | ").append(cell(resume.note)).append(" |\n");
        }
    }

    private void renderMethod(StringBuilder md) {
        md.append("\n## Method\n\n");
        md.append("- Every question runs in its own local fixture world: a SERP engine and one loopback server per "
                + "corpus host. The corpus is invented, so a statement is only supported by a corpus page.\n");
        md.append("- Precision: accepted sources that are expected relevant pages. Recall: expected relevant pages "
                + "that were accepted. Facts: expected facts whose keywords all occur in one accepted source.\n");
        md.append("- Supported claims: sentences of the subject's statements with at least ")
                .append(settings.claimMinimumContentWords).append(" content words; supported when one accepted "
                        + "source contains at least ")
                .append(percent(settings.claimSupportThreshold)).append(" of them (lexical, under-counts "
                        + "paraphrases). Sentences that report missing evidence are not claims.\n");
        md.append("- Contradictions and gaps: structured reports when the subject has them, otherwise a text "
                + "heuristic over the statements (both values named, or one value with a conflict word; a gap "
                + "keyword in a sentence with a gap marker).\n");
        md.append("- The subject gets the full question. The baseline has no outline input, so its gap "
                + "detection measures what it reports unprompted; a subject that takes an outline gets the "
                + "question's outline.\n");
        md.append("- Tool calls: the run's tool-call count from the progress events; failed = ERROR and "
                + "PROBLEM_REPORTED events. Model calls and tokens: the recording pass-through in front of Ollama.\n");
    }

    // ------------------------------------------------------------------ formatting

    private static void addIfPresent(List<Double> values, Double value) {
        if (value != null) {
            values.add(value);
        }
    }

    private static Double mean(List<Double> values) {
        if (values.isEmpty()) {
            return null;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.size();
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0);
    }

    static String percentOrNa(Double value) {
        return value == null ? "n/a" : percent(value);
    }

    private static String decimalOrNa(Double value) {
        return value == null ? "n/a" : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String countOrNa(int part, int whole) {
        return whole == 0 ? "n/a" : part + "/" + whole;
    }

    private static String cell(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }
}
