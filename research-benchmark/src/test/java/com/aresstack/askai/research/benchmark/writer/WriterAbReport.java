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
