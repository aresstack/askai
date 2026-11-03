package com.aresstack.askai.research.benchmark;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scores one {@link BenchmarkRunRecord} against the question's expected outcome — the metrics of ADR 0001,
 * section 6, work item H. Pure and deterministic, so it is unit tested without a model.
 *
 * <ul>
 *   <li><b>Source quality</b>: precision of the accepted sources (share that are expected relevant pages) and
 *   the number of accepted distractors.</li>
 *   <li><b>Source completeness</b>: recall of the relevant pages, coverage of the expected facts (a fact is
 *   covered when all its keywords occur in one accepted source), and the run budget's sufficiency rule.</li>
 *   <li><b>Supported statements</b>: the subject's statements are split into sentences; a sentence with at
 *   least {@code claimMinimumContentWords} content words is a claim, and a claim is supported when one
 *   accepted source contains at least {@code claimSupportThreshold} of its content words. Sentences that
 *   report missing evidence are not claims. Lexical, so it under-counts paraphrases; it is the same
 *   yardstick for every subject.</li>
 *   <li><b>Contradiction and gap detection</b>: structured reports when the subject has them (later roles
 *   emit schema output); otherwise a text heuristic over the statements — a contradiction counts when both
 *   values are named, or one value next to a conflict word; a gap counts when a gap keyword shares a sentence
 *   with a gap marker such as "no source" or "not covered".</li>
 *   <li><b>Tool calls, tokens, wall time</b> are taken from the record as observed.</li>
 * </ul>
 */
public final class BenchmarkScorer {

    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+|\\n+");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N},.]+");
    private static final String[] CONFLICT_WORDS = {"contradict", "conflict", "inconsistent", "disagree",
            "differ", "discrepan", "whereas", "however"};
    private static final String[] GAP_MARKERS = {"no source", "no sources", "not found", "no evidence",
            "missing", "gap", "not covered", "could not find", "unclear", "no information", "open question",
            "unknown"};
    private static final Set<String> STOP_WORDS = new HashSet<String>(java.util.Arrays.asList(
            "the", "and", "for", "with", "that", "this", "from", "are", "was", "were", "has", "have", "had",
            "its", "it's", "into", "than", "then", "there", "their", "they", "which", "what", "when", "where",
            "who", "how", "about", "also", "been", "being", "can", "could", "should", "would", "will", "not",
            "but", "all", "any", "some", "more", "most", "such", "only", "over", "under", "after", "before",
            "these", "those", "our", "your", "you", "per", "one", "two"));

    private final BenchmarkSettings settings;

    public BenchmarkScorer(BenchmarkSettings settings) {
        this.settings = settings;
    }

    /** Score a run whose accepted sources were resolved with {@link BenchmarkRunRecord#resolvePages}. */
    public RunScores score(BenchmarkQuestion question, BenchmarkRunRecord record) {
        BenchmarkQuestion.Expected expected = question.getExpected();
        RunScores scores = new RunScores(record);

        // ---- source quality and completeness ----
        Set<String> acceptedPages = new HashSet<String>();
        Set<String> hosts = new HashSet<String>();
        int relevantAccepted = 0;
        for (BenchmarkRunRecord.AcceptedSource source : record.getAcceptedSources()) {
            hosts.add(hostOf(source.url));
            String pageId = source.getPageId();
            if (pageId != null && acceptedPages.add(pageId) && expected.getRelevantPages().contains(pageId)) {
                relevantAccepted++;
            }
            if (pageId != null && expected.getDistractorPages().contains(pageId)) {
                scores.distractorsAccepted++;
            }
        }
        int accepted = record.getAcceptedSources().size();
        scores.acceptedSources = accepted;
        scores.acceptedHosts = hosts.size();
        scores.sourcePrecision = accepted == 0 ? null : ratio(relevantAccepted, accepted);
        scores.pageRecall = ratio(relevantAccepted, expected.getRelevantPages().size());
        scores.sufficient = accepted >= expected.getMinimumSources()
                && hosts.size() >= expected.getMinimumDistinctHosts();
        scores.expectedStop = record.isFinished() && expected.getStopReason().equals(record.getStopReason());

        int covered = 0;
        for (BenchmarkQuestion.Fact fact : expected.getFacts()) {
            for (BenchmarkRunRecord.AcceptedSource source : record.getAcceptedSources()) {
                if (BenchmarkQuestionSet.containsAll(source.title + "\n" + source.text, fact.getKeywords())) {
                    covered++;
                    break;
                }
            }
        }
        scores.factsCovered = covered;
        scores.factsExpected = expected.getFacts().size();

        // ---- supported statements ----
        for (String claim : claims(record.getStatements())) {
            scores.claims++;
            if (supported(claim, record.getAcceptedSources())) {
                scores.supportedClaims++;
            }
        }

        // ---- contradictions and gaps ----
        String output = String.join("\n", record.getStatements()).toLowerCase(Locale.ROOT);
        List<String> sentences = sentences(record.getStatements());
        scores.contradictionsPlanted = expected.getContradictions().size();
        scores.contradictionMethod = record.reportedContradictions == null ? "text heuristic" : "structured";
        for (BenchmarkQuestion.Contradiction contradiction : expected.getContradictions()) {
            if (record.reportedContradictions != null
                    ? mentionsStructured(record.reportedContradictions, contradiction)
                    : mentionsContradiction(output, sentences, contradiction)) {
                scores.contradictionsDetected++;
            }
        }
        scores.gapsPlanted = expected.getGaps().size();
        scores.gapMethod = record.reportedGaps == null ? "text heuristic" : "structured";
        for (BenchmarkQuestion.Gap gap : expected.getGaps()) {
            if (record.reportedGaps != null ? mentionsStructuredGap(record.reportedGaps, gap)
                    : mentionsGap(sentences, gap)) {
                scores.gapsDetected++;
            }
        }

        // ---- lane G worker roles: what the verifier's rejections did to the source quality ----
        BenchmarkRunRecord.RoleObservation roles = record.getRoles();
        if (roles != null) {
            Set<String> rejectedPages = new HashSet<String>(roles.rejectedPageIds);
            for (String page : rejectedPages) {
                if (expected.getDistractorPages().contains(page)) {
                    scores.distractorsRejected++;
                }
                if (expected.getRelevantPages().contains(page)) {
                    scores.relevantRejected++;
                }
            }
            int kept = 0;
            int keptRelevant = 0;
            Set<String> counted = new HashSet<String>();
            for (BenchmarkRunRecord.AcceptedSource source : record.getAcceptedSources()) {
                String page = source.getPageId();
                if (roles.getRejectedUrls().contains(source.url) || (page != null && rejectedPages.contains(page))) {
                    continue;
                }
                kept++;
                if (page != null && counted.add(page) && expected.getRelevantPages().contains(page)) {
                    keptRelevant++;
                }
            }
            scores.verifiedPrecision = roles.getVerifierPackages() == 0 || kept == 0 ? null
                    : ratio(keptRelevant, kept);
        }

        // ---- tool calls ----
        scores.toolCallSuccess = record.getToolCalls() == 0 ? null
                : ratio(Math.max(0, record.getToolCalls() - record.getToolFailures()), record.getToolCalls());
        return scores;
    }

    /**
     * The sentences of the statements that are long enough to be claims. A sentence that reports missing
     * evidence (a gap marker) is not a factual claim and needs no source.
     */
    List<String> claims(List<String> statements) {
        List<String> out = new ArrayList<String>();
        for (String sentence : sentences(statements)) {
            if (contentWords(sentence).size() >= settings.claimMinimumContentWords
                    && !containsAny(sentence.toLowerCase(Locale.ROOT), GAP_MARKERS)) {
                out.add(sentence);
            }
        }
        return out;
    }

    boolean supported(String claim, List<BenchmarkRunRecord.AcceptedSource> sources) {
        Set<String> words = contentWords(claim);
        if (words.isEmpty()) {
            return false;
        }
        for (BenchmarkRunRecord.AcceptedSource source : sources) {
            Set<String> sourceWords = contentWords(source.title + " " + source.text);
            int hit = 0;
            for (String word : words) {
                if (sourceWords.contains(word)) {
                    hit++;
                }
            }
            if ((double) hit / words.size() >= settings.claimSupportThreshold) {
                return true;
            }
        }
        return false;
    }

    static List<String> sentences(List<String> statements) {
        List<String> out = new ArrayList<String>();
        for (String statement : statements) {
            for (String sentence : SENTENCE_END.split(statement)) {
                if (!sentence.trim().isEmpty()) {
                    out.add(sentence.trim());
                }
            }
        }
        return out;
    }

    static Set<String> contentWords(String text) {
        Set<String> out = new HashSet<String>();
        for (String raw : NON_WORD.split(text.toLowerCase(Locale.ROOT))) {
            String word = trimPunctuation(raw);
            if (word.length() >= 3 && !STOP_WORDS.contains(word)) {
                out.add(word);
            }
        }
        return out;
    }

    private static String trimPunctuation(String word) {
        int start = 0;
        int end = word.length();
        while (start < end && (word.charAt(start) == ',' || word.charAt(start) == '.')) {
            start++;
        }
        while (end > start && (word.charAt(end - 1) == ',' || word.charAt(end - 1) == '.')) {
            end--;
        }
        return word.substring(start, end);
    }

    private static boolean mentionsContradiction(String output, List<String> sentences,
                                                 BenchmarkQuestion.Contradiction contradiction) {
        String a = contradiction.getValueA().toLowerCase(Locale.ROOT);
        String b = contradiction.getValueB().toLowerCase(Locale.ROOT);
        if (output.contains(a) && output.contains(b)) {
            return true;
        }
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if ((lower.contains(a) || lower.contains(b)) && containsAny(lower, CONFLICT_WORDS)) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsStructured(List<String> reported, BenchmarkQuestion.Contradiction contradiction) {
        for (String entry : reported) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.contains(contradiction.getValueA().toLowerCase(Locale.ROOT))
                    || lower.contains(contradiction.getValueB().toLowerCase(Locale.ROOT))
                    || lower.contains(contradiction.getSubject().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsGap(List<String> sentences, BenchmarkQuestion.Gap gap) {
        for (String sentence : sentences) {
            String lower = sentence.toLowerCase(Locale.ROOT);
            if (containsAny(lower, GAP_MARKERS) && containsAny(lower, gap.getKeywords())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentionsStructuredGap(List<String> reported, BenchmarkQuestion.Gap gap) {
        for (String entry : reported) {
            String lower = entry.toLowerCase(Locale.ROOT);
            if (lower.contains(gap.getSection().toLowerCase(Locale.ROOT)) || containsAny(lower, gap.getKeywords())) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String[] needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String hostOf(String url) {
        try {
            URI uri = URI.create(url.trim());
            return uri.getHost() + ":" + uri.getPort();
        } catch (RuntimeException invalid) {
            return url;
        }
    }

    private static double ratio(int part, int whole) {
        return whole == 0 ? 1.0 : (double) part / whole;
    }

    /** Resolves accepted URLs to corpus pages: {@link FixtureWorld} in a live run, a map in unit tests. */
    public interface FixtureWorldIndex {
        BenchmarkQuestion.Page pageForUrl(String url);
    }

    /** The scores of one run. Null ratios mean "not applicable" (nothing to measure), never zero. */
    public static final class RunScores {
        public final BenchmarkRunRecord record;
        public int acceptedSources;
        public int acceptedHosts;
        public Double sourcePrecision;
        public int distractorsAccepted;
        public double pageRecall;
        public int factsCovered;
        public int factsExpected;
        public boolean sufficient;
        public boolean expectedStop;
        public int claims;
        public int supportedClaims;
        public int contradictionsPlanted;
        public int contradictionsDetected;
        public String contradictionMethod = "";
        public int gapsPlanted;
        public int gapsDetected;
        public String gapMethod = "";
        public Double toolCallSuccess;
        /** Lane G: distractor and relevant pages the source verifier rejected. */
        public int distractorsRejected;
        public int relevantRejected;
        /** Lane G: precision of the accepted sources the verifier did not reject; null without a verifier. */
        public Double verifiedPrecision;

        RunScores(BenchmarkRunRecord record) {
            this.record = record;
        }

        public Double factCoverage() {
            return factsExpected == 0 ? null : (double) factsCovered / factsExpected;
        }

        public Double supportedShare() {
            return claims == 0 ? null : (double) supportedClaims / claims;
        }

        /** A run counts as successful when it stopped as expected, covered every fact and took no distractor. */
        public boolean successful() {
            Double coverage = factCoverage();
            return expectedStop && (coverage == null || coverage == 1.0) && distractorsAccepted == 0;
        }
    }
}
