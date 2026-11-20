package com.aresstack.askai.research.benchmark.writer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * The Markdown of the writer A/B. The rating sheet and the metrics table name only letters; the key (letter to
 * model) is a separate text so the owner rates blind. Once the owner's ranking is in, {@link #decision} adds the
 * resolved ranking and the preset the rule chooses.
 */
public final class WriterAbReport {

    private WriterAbReport() {
    }

    /** The texts per item under their letters, in letter order, plus an empty ranking line per item. */
    public static String ratingSheet(List<BlindAssignment> assignments, List<WriterAbSample> samples) {
        StringBuilder md = new StringBuilder("# Writer A/B: blind rating sheet\n\n")
                .append("Rank the texts of each item, best first, on the line `Ranking:` (for example `B, A, C`).\n");
        for (BlindAssignment assignment : assignments) {
            md.append("\n## ").append(assignment.getItem()).append("\n\nRanking: \n");
            for (Map.Entry<String, String> entry : assignment.getLetterToModel().entrySet()) {
                WriterAbSample sample = find(samples, assignment.getItem(), entry.getValue());
                md.append("\n### Text ").append(entry.getKey()).append("\n\n")
                        .append(sample == null ? "_(no text)_" : sample.getText().trim()).append('\n');
            }
        }
        return md.toString();
    }

    /** The measurements per item and letter, without model names. */
    public static String metrics(List<BlindAssignment> assignments, List<WriterAbSample> samples) {
        TreeSet<String> countNames = new TreeSet<String>();
        for (WriterAbSample sample : samples) {
            countNames.addAll(sample.getQualityCounts().keySet());
        }
        StringBuilder md = new StringBuilder("# Writer A/B: measurements\n\n")
                .append("| Item | Text | Seconds | Tokens | Words | Citations | Valid | Uncited paragraphs | Drift |");
        for (String name : countNames) {
            md.append(' ').append(name).append(" |");
        }
        md.append("\n|---|---|---|---|---|---|---|---|---|");
        for (int i = 0; i < countNames.size(); i++) {
            md.append("---|");
        }
        md.append('\n');
        for (BlindAssignment assignment : assignments) {
            for (Map.Entry<String, String> entry : assignment.getLetterToModel().entrySet()) {
                WriterAbSample s = find(samples, assignment.getItem(), entry.getValue());
                md.append("| ").append(assignment.getItem()).append(" | ").append(entry.getKey()).append(" | ");
                if (s == null) {
                    md.append("failed | | | | | | |\n");
                    continue;
                }
                WriterSampleMetrics m = s.getMetrics();
                md.append(String.format(Locale.ROOT, "%.1f | %d | %d | %d | %.0f%% | %.0f%% | %.0f%% |",
                        s.getSeconds(), s.getTokens(), m.getWords(), m.getCitations(), 100 * m.citationValidity(),
                        100 * m.uncitedParagraphRate(), 100 * m.languageDrift()));
                for (String name : countNames) {
                    Integer value = s.getQualityCounts().get(name);
                    md.append(' ').append(value == null ? "" : String.valueOf(value)).append(" |");
                }
                md.append('\n');
            }
        }
        return md.toString();
    }

    /**
     * The quality figures of the writer proposal per item and letter, without names: repetition per section (mean
     * and highest) and over the sample, reach of the word target, restarts, short paragraphs, source blocks,
     * sentences with two sources, uncited sentences, pack use, gaps per section and the judged citation support.
     */
    public static String quality(List<BlindAssignment> assignments, List<WriterAbSample> samples) {
        StringBuilder md = new StringBuilder("# Writer A/B: quality figures\n\n")
                .append(QUALITY_HEADER).append(QUALITY_RULE);
        for (BlindAssignment assignment : assignments) {
            for (Map.Entry<String, String> entry : assignment.getLetterToModel().entrySet()) {
                WriterAbSample s = find(samples, assignment.getItem(), entry.getValue());
                md.append("| ").append(assignment.getItem()).append(" | ").append(entry.getKey()).append(" | ")
                        .append(s == null ? "failed |\n" : qualityCells(s) + "\n");
            }
        }
        md.append(QUALITY_LEGEND);
        return md.toString();
    }

    static final String QUALITY_HEADER = "| Item | Text | Words | Target reach | Repeated words per section (mean / max) "
            + "| Repeated words overall | Restarts | Short paragraphs | Source blocks | Two-source sentences "
            + "| Uncited sentences | Pack use | Gaps per section | Citations carried (judge) | Not carried |\n";
    static final String QUALITY_RULE = "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n";
    static final String QUALITY_LEGEND = "\nRepeated words: words in sentences whose bge-m3 embedding has a cosine of "
            + "at least 0.80 to an earlier sentence (per section, and over the whole sample). Restarts: paragraphs "
            + "whose first sentence repeats something already said. Source blocks: cited sentences citing the same "
            + "single passage as the sentence before. Citations carried: sentences the judge model found fully "
            + "supported by their cited passages, of all cited sentences it judged; not carried = unsupported "
            + "(partial in brackets).\n";

    /** The quality cells of one sample (without the item and letter columns). */
    static String qualityCells(WriterAbSample s) {
        List<WriterAbSample.Section> sections = s.getSections();
        if (sections.isEmpty()) {
            return " | | | | | | | | | | | | |";
        }
        int words = 0;
        double reach = 0;
        double repeat = 0;
        double maxRepeat = 0;
        int restarts = 0;
        int shortOnes = 0;
        double blocks = 0;
        double multi = 0;
        int uncited = 0;
        double use = 0;
        int gaps = 0;
        int judged = 0;
        int supported = 0;
        int unsupported = 0;
        int partial = 0;
        for (WriterAbSample.Section section : sections) {
            WriterQualityMetrics q = section.quality;
            words += q.getWords();
            reach += q.targetReach();
            repeat += q.repetition();
            maxRepeat = Math.max(maxRepeat, q.repetition());
            restarts += q.getRestartParagraphs();
            shortOnes += q.getShortParagraphs();
            blocks += q.sourceBlocks();
            multi += q.multiSource();
            uncited += q.getUncitedSentences();
            use += q.packUse();
            gaps += section.gaps;
            judged += section.support.judged();
            supported += section.support.count(CitationSupportJudge.Verdict.SUPPORTED);
            unsupported += section.support.count(CitationSupportJudge.Verdict.UNSUPPORTED);
            partial += section.support.count(CitationSupportJudge.Verdict.PARTIAL);
        }
        int n = sections.size();
        return String.format(Locale.ROOT, "%d | %.0f%% | %.0f%% / %.0f%% | %s | %d | %d | %.0f%% | %.0f%% | %d | %.0f%% "
                        + "| %.1f | %s | %s |", words, 100 * reach / n, 100 * repeat / n, 100 * maxRepeat,
                s.getChapterRepetition() < 0 ? "" : String.format(Locale.ROOT, "%.0f%%",
                        100 * s.getChapterRepetition()), restarts, shortOnes, 100 * blocks / n, 100 * multi / n,
                uncited, 100 * use / n, gaps / (double) n,
                judged == 0 ? "" : String.format(Locale.ROOT, "%.0f%% of %d", 100.0 * supported / judged, judged),
                judged == 0 ? "" : unsupported + " (" + partial + ")");
    }

    /**
     * The named summary over all runs (open it only after the blind rating): per item and arm the mean and the
     * range of the main figures, then every sentence the judge found not carried, with its cited passages' numbers.
     */
    public static String summary(List<WriterAbSample> samples, String note) {
        java.util.LinkedHashMap<String, List<WriterAbSample>> byKey =
                new java.util.LinkedHashMap<String, List<WriterAbSample>>();
        for (WriterAbSample sample : samples) {
            String key = sample.getItem() + "\u0000" + sample.getModel();
            if (!byKey.containsKey(key)) {
                byKey.put(key, new ArrayList<WriterAbSample>());
            }
            byKey.get(key).add(sample);
        }
        StringBuilder md = new StringBuilder("# Writer A/B: summary over all runs (names the arms)\n\n")
                .append(note == null || note.isEmpty() ? "" : note + "\n\n")
                .append("Mean, and the range in brackets, over the runs of each arm.\n\n")
                .append("| Item | Arm | Runs | Words | Target reach | Repeated words per section | Repeated words "
                        + "overall | Restarts | Gaps per section | Two-source sentences | Uncited sentences "
                        + "| Citations carried (judge) | Claim flags | Tokens | Seconds |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (List<WriterAbSample> runs : byKey.values()) {
            WriterAbSample first = runs.get(0);
            List<double[]> rows = new ArrayList<double[]>();
            for (WriterAbSample s : runs) {
                rows.add(figures(s));
            }
            md.append("| ").append(first.getItem()).append(" | ").append(first.getModel()).append(" | ")
                    .append(runs.size()).append(" |");
            String[] formats = {"%.0f", "%.0f%%", "%.1f%%", "%.1f%%", "%.1f", "%.1f", "%.0f%%", "%.1f", "%.0f%%",
                    "%.1f", "%.0f", "%.0f"};
            for (int c = 0; c < formats.length; c++) {
                md.append(' ').append(meanRange(rows, c, formats[c])).append(" |");
            }
            md.append('\n');
        }
        md.append("\n## Sentences the judge found not carried\n");
        boolean any = false;
        for (WriterAbSample s : samples) {
            for (WriterAbSample.Section section : s.getSections()) {
                for (CitationSupportJudge.Finding f : section.support.findings) {
                    if (f.verdict != CitationSupportJudge.Verdict.UNSUPPORTED
                            && f.verdict != CitationSupportJudge.Verdict.PARTIAL) {
                        continue;
                    }
                    any = true;
                    md.append("\n- ").append(s.getItem()).append(", ").append(s.getModel()).append(", run ")
                            .append(s.getRun()).append(", section ").append(section.id).append(", ")
                            .append(f.verdict.name().toLowerCase(Locale.ROOT)).append(": ").append(f.sentence.trim())
                            .append(" (judge: ").append(f.reason).append(')');
                }
            }
        }
        md.append(any ? "\n" : "\nNone, or no judge was set.\n");
        return md.toString();
    }

    /** words, reach, repetition, overall, restarts, gaps, multi, uncited, carried, claim flags, tokens, seconds. */
    private static double[] figures(WriterAbSample s) {
        double[] f = new double[12];
        List<WriterAbSample.Section> sections = s.getSections();
        int n = Math.max(1, sections.size());
        int judged = 0;
        int supported = 0;
        for (WriterAbSample.Section section : sections) {
            f[0] += section.quality.getWords();
            f[1] += 100 * section.quality.targetReach() / n;
            f[2] += 100 * section.quality.repetition() / n;
            f[4] += section.quality.getRestartParagraphs();
            f[5] += section.gaps / (double) n;
            f[6] += 100 * section.quality.multiSource() / n;
            f[7] += section.quality.getUncitedSentences();
            judged += section.support.judged();
            supported += section.support.count(CitationSupportJudge.Verdict.SUPPORTED);
        }
        f[3] = s.getChapterRepetition() < 0 ? Double.NaN : 100 * s.getChapterRepetition();
        f[8] = judged == 0 ? Double.NaN : 100.0 * supported / judged;
        int flags = 0;
        for (Map.Entry<String, Integer> count : s.getQualityCounts().entrySet()) {
            if (!count.getKey().equals("writer gaps") && !count.getKey().equals("stripped markers")
                    && !count.getKey().equals("failed sections")) {
                flags += count.getValue();
            }
        }
        f[9] = flags;
        f[10] = s.getTokens();
        f[11] = s.getSeconds();
        return f;
    }

    private static String meanRange(List<double[]> rows, int column, String format) {
        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int n = 0;
        for (double[] row : rows) {
            if (Double.isNaN(row[column])) {
                continue;
            }
            sum += row[column];
            min = Math.min(min, row[column]);
            max = Math.max(max, row[column]);
            n++;
        }
        if (n == 0) {
            return "";
        }
        String mean = String.format(Locale.ROOT, format, sum / n);
        return n == 1 ? mean : mean + " (" + String.format(Locale.ROOT, format, min) + "–"
                + String.format(Locale.ROOT, format, max) + ")";
    }

    /** The key, kept apart from the sheet: one line {@code item: A=model, B=model, ...} per item. */
    public static String key(List<BlindAssignment> assignments) {
        StringBuilder text = new StringBuilder();
        for (BlindAssignment assignment : assignments) {
            text.append(assignment.getItem()).append(':');
            List<String> pairs = new ArrayList<String>();
            for (Map.Entry<String, String> entry : assignment.getLetterToModel().entrySet()) {
                pairs.add(entry.getKey() + "=" + entry.getValue());
            }
            text.append(' ').append(String.join(", ", pairs)).append('\n');
        }
        return text.toString();
    }

    /** The resolved ranking per item and the preset of the decision rule. */
    public static String decision(Map<String, List<String>> resolvedRankings) {
        StringBuilder md = new StringBuilder("# Writer A/B: decision\n\n| Item | Ranking (best first) |\n|---|---|\n");
        for (Map.Entry<String, List<String>> entry : resolvedRankings.entrySet()) {
            md.append("| ").append(entry.getKey()).append(" | ").append(String.join(", ", entry.getValue()))
                    .append(" |\n");
        }
        md.append("\nDocument preset (`phase.draft`): `").append(WriterAbDecision.decide(resolvedRankings))
                .append("`\n");
        return md.toString();
    }

    private static WriterAbSample find(List<WriterAbSample> samples, String item, String model) {
        for (WriterAbSample sample : samples) {
            if (sample.getItem().equals(item) && sample.getModel().equals(model)) {
                return sample;
            }
        }
        return null;
    }
}
