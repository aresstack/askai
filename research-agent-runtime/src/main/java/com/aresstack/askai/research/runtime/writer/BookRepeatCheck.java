package com.aresstack.askai.research.runtime.writer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Component 12 of the build plan of 21.11.2026: finds the same statement at two places of the book. Input: every
 * sentence of the book in reading order, each with its section and the passages it cites. Output: pairs of sentences
 * with their similarity, one for each sentence that an earlier section already says.
 *
 * <p>The rules are {@link RepeatCheck}'s, so the book and the section writer share one notion of a repeat (settings
 * {@code research.writer.repeat.*}): a later sentence with at least {@link RepeatCheck.Criteria#getMinWords() min
 * words} content words repeats an earlier one when the earlier sentence states every number it states and either
 * holds at least {@link RepeatCheck.Criteria#getMinShare() min share} of its content words ({@link Reason#WORDS}) or
 * reaches {@link RepeatCheck.Criteria#getMinSimilarity() min similarity} in the sentence-embedding slot
 * ({@link Reason#MEANING}: the same statement in other words). The same text twice is a repeat whatever its length
 * ({@link Reason#SAME_TEXT}). The section writer's check sees one section at a time while it writes; this check reads
 * the finished book.</p>
 *
 * <p>Citing the same passage at two places is allowed and never counts towards a repeat: a pair carries the shared
 * passages for the report only. In the book of 17 Nov, 400 of the 546 sentences on a re-cited passage say something
 * else.</p>
 *
 * <p>The similarity bound is not calibrated yet; it is fixed on the book of 17 Nov before the component test counts.
 * So that a bound can be read off a book, every checked sentence also gets its nearest earlier sentence with both
 * measures, repeat or not ({@link Result#getNearest()}).</p>
 *
 * <p>Errors: the sentence vectors come in batches, and a failed batch gets one repair attempt (the number of attempts
 * is an option). When it fails again, the check asks the model no more, keeps what the word rules find and reports
 * itself {@link Status#INCOMPLETE} with the reason: a failed model never reads as "no repetition". The check keeps
 * no state between calls; it is as thread-safe as the injected sentence model.</p>
 */
public final class BookRepeatCheck {

    /** Whether every rule ran on every sentence. */
    public enum Status {
        /** Every rule the criteria switch on ran on every sentence. */
        COMPLETE,
        /** The meaning rule is on, but some or all sentences have no vector (no model, or the model failed). */
        INCOMPLETE
    }

    /** Why a later sentence counts as a repeat. */
    public enum Reason {
        /** The same text (case, spacing, closing punctuation and markers ignored). */
        SAME_TEXT,
        /** The earlier sentence holds at least min share of its content words. */
        WORDS,
        /** The earlier sentence reaches min similarity: the same statement in other words. */
        MEANING
    }

    /** How the book is read; immutable. */
    public static final class Options {
        /** Default sentences per call of the sentence model. */
        public static final int DEFAULT_BATCH_SIZE = 32;
        /** Default calls per batch: the first and one repair attempt (rules of the build plan). */
        public static final int DEFAULT_ATTEMPTS = 2;

        private final boolean withinSection;
        private final int batchSize;
        private final int attempts;

        /**
         * @param withinSection also compare with the earlier sentences of the same section (default: only earlier
         *                      sections; the section writer's own check covers a section while it is written)
         * @param batchSize     sentences per call of the sentence model
         * @param attempts      calls per batch before the check reports itself incomplete
         */
        public Options(boolean withinSection, int batchSize, int attempts) {
            if (batchSize < 1 || attempts < 1) {
                throw new IllegalArgumentException("a batch needs at least one sentence and one attempt");
            }
            this.withinSection = withinSection;
            this.batchSize = batchSize;
            this.attempts = attempts;
        }

        public static Options defaults() {
            return new Options(false, DEFAULT_BATCH_SIZE, DEFAULT_ATTEMPTS);
        }

        public boolean isWithinSection() {
            return withinSection;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public int getAttempts() {
            return attempts;
        }

        @Override
        public String toString() {
            return (withinSection ? "all earlier sentences" : "earlier sections") + ", " + batchSize
                    + " per batch, " + attempts + " attempts";
        }
    }

    /** One sentence of the book; immutable. */
    public static final class Sentence {
        private final String sectionId;
        private final String text;
        private final List<String> passageIds;

        /**
         * @param sectionId  the section it stands in
         * @param text       the sentence as written, markers allowed
         * @param passageIds the passages it cites
         */
        public Sentence(String sectionId, String text, List<String> passageIds) {
            this.sectionId = sectionId == null ? "" : sectionId;
            this.text = text == null ? "" : text;
            this.passageIds = passageIds == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(passageIds));
        }

        public String getSectionId() {
            return sectionId;
        }

        public String getText() {
            return text;
        }

        public List<String> getPassageIds() {
            return passageIds;
        }

        /** The sentence without its markers, as the rules read it. */
        public String plainText() {
            return new CitationValidator.Sentence(text, Collections.<Integer>emptySet()).plainText();
        }

        @Override
        public String toString() {
            return sectionId + ": " + text;
        }
    }

    /**
     * A later sentence and an earlier one with both measures; immutable. In {@link Result#getPairs()} the earlier
     * sentence says the same ({@link #getReason()} is set); in {@link Result#getNearest()} it is the most similar
     * earlier sentence, repeat or not.
     */
    public static final class Pair {
        private final int earlierIndex;
        private final int laterIndex;
        private final Sentence earlier;
        private final Sentence later;
        private final double share;
        private final double similarity;
        private final boolean numbersCovered;
        private final Reason reason;
        private final List<String> sharedPassageIds;
        private final int matches;

        Pair(int earlierIndex, int laterIndex, Sentence earlier, Sentence later, double share, double similarity,
             boolean numbersCovered, Reason reason, List<String> sharedPassageIds, int matches) {
            this.earlierIndex = earlierIndex;
            this.laterIndex = laterIndex;
            this.earlier = earlier;
            this.later = later;
            this.share = share;
            this.similarity = similarity;
            this.numbersCovered = numbersCovered;
            this.reason = reason;
            this.sharedPassageIds = Collections.unmodifiableList(sharedPassageIds);
            this.matches = matches;
        }

        /** The earlier sentence's position in the book (0-based). */
        public int getEarlierIndex() {
            return earlierIndex;
        }

        /** The later sentence's position in the book (0-based). */
        public int getLaterIndex() {
            return laterIndex;
        }

        public Sentence getEarlier() {
            return earlier;
        }

        public Sentence getLater() {
            return later;
        }

        /** Share of the later sentence's content words whose stem the earlier sentence holds (0..1). */
        public double getShare() {
            return share;
        }

        /** Cosine of the two sentence vectors; {@code -1} when one of them has no vector. */
        public double getSimilarity() {
            return similarity;
        }

        /** Whether the earlier sentence states every number the later one states. */
        public boolean isNumbersCovered() {
            return numbersCovered;
        }

        /** Why the later sentence repeats the earlier one; {@code null} for a nearest sentence that is no repeat. */
        public Reason getReason() {
            return reason;
        }

        /** Passages both sentences cite: reported, never a reason. */
        public List<String> getSharedPassageIds() {
            return sharedPassageIds;
        }

        /** How many earlier sentences the later one repeats (pairs only; 0 for a nearest that is no repeat). */
        public int getMatches() {
            return matches;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "#%d -> #%d %s share %.2f similarity %.3f", earlierIndex, laterIndex,
                    reason == null ? "-" : reason.name(), share, similarity);
        }
    }

    /** What the check found; immutable. */
    public static final class Result {
        private final Status status;
        private final String problem;
        private final int sentences;
        private final int checked;
        private final int withoutVector;
        private final int words;
        private final int repeatedWords;
        private final List<Pair> pairs;
        private final List<Pair> nearest;
        private final RepeatCheck.Criteria criteria;
        private final Options options;

        Result(Status status, String problem, int sentences, int checked, int withoutVector, int words,
               int repeatedWords, List<Pair> pairs, List<Pair> nearest, RepeatCheck.Criteria criteria,
               Options options) {
            this.status = status;
            this.problem = problem;
            this.sentences = sentences;
            this.checked = checked;
            this.withoutVector = withoutVector;
            this.words = words;
            this.repeatedWords = repeatedWords;
            this.pairs = Collections.unmodifiableList(pairs);
            this.nearest = Collections.unmodifiableList(nearest);
            this.criteria = criteria;
            this.options = options;
        }

        public Status getStatus() {
            return status;
        }

        /** Why the check is incomplete; empty when complete. */
        public String getProblem() {
            return problem;
        }

        /** Sentences in the book. */
        public int getSentences() {
            return sentences;
        }

        /** Sentences with enough content words for the word and meaning rules. */
        public int getChecked() {
            return checked;
        }

        /** Sentences the meaning rule needed a vector for and did not get one. */
        public int getWithoutVector() {
            return withoutVector;
        }

        /** Words of the book (letters and digits). */
        public int getWords() {
            return words;
        }

        /** Words of the sentences that repeat an earlier one. */
        public int getRepeatedWords() {
            return repeatedWords;
        }

        /** Share of the book's words in repeating sentences (0..1). */
        public double getRepeatedWordShare() {
            return words == 0 ? 0 : repeatedWords / (double) words;
        }

        /** One pair per repeating sentence, in reading order of the later sentence. */
        public List<Pair> getPairs() {
            return pairs;
        }

        /** For every checked sentence with an earlier sentence to compare: the most similar one, repeat or not. */
        public List<Pair> getNearest() {
            return nearest;
        }

        public RepeatCheck.Criteria getCriteria() {
            return criteria;
        }

        public Options getOptions() {
            return options;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "%s: %d of %d sentences repeat (%.1f %% of the words), %d checked, "
                            + "%d without vector%s", status, pairs.size(), sentences, 100 * getRepeatedWordShare(),
                    checked, withoutVector, problem.isEmpty() ? "" : " (" + problem + ")");
        }
    }

    /** The prepared measures of one sentence. */
    private static final class Prepared {
        final Sentence sentence;
        final String plain;
        final String key;
        final List<String> content;
        final Set<String> stems;
        final Set<String> numbers;
        final int words;
        float[] unit;

        Prepared(Sentence sentence, String plain, String key, List<String> content, Set<String> stems,
                 Set<String> numbers, int words) {
            this.sentence = sentence;
            this.plain = plain;
            this.key = key;
            this.content = content;
            this.stems = stems;
            this.numbers = numbers;
            this.words = words;
        }
    }

    private final RepeatCheck.Criteria criteria;
    private final ClaimCarryCheck words;
    private final SentenceVectors vectors;
    private final Options options;
    private final Consumer<String> log;

    /**
     * @param criteria the repeat bounds, as the section writer's check reads them; the defaults when {@code null}
     * @param carry    the claim check's word and stem lengths; the defaults when {@code null}
     * @param vectors  the sentence-embedding slot; {@code null} leaves only the word rules (reported as incomplete
     *                 while the criteria switch the meaning rule on)
     * @param options  how the book is read; the defaults when {@code null}
     * @param log      progress and errors; may be {@code null}
     */
    public BookRepeatCheck(RepeatCheck.Criteria criteria, ClaimCarryCheck.Criteria carry, SentenceVectors vectors,
                           Options options, Consumer<String> log) {
        this.criteria = criteria == null ? RepeatCheck.Criteria.defaults() : criteria;
        this.words = new ClaimCarryCheck(carry);
        this.vectors = vectors;
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
    }

    /**
     * The sentences of one written section, with the passages their markers stand for: paragraphs are separated by a
     * blank line, markers written after the full stop belong to the sentence before (as the writer checks split).
     *
     * @param passageOfMarker the passage behind each marker number of the section; a number without a passage is
     *                        left out
     */
    public static List<Sentence> sentencesOf(String sectionId, String text, Map<Integer, String> passageOfMarker) {
        List<Sentence> out = new ArrayList<Sentence>();
        for (String paragraph : (text == null ? "" : text).split("\\n\\s*\\n")) {
            for (CitationValidator.Sentence sentence
                    : CitationValidator.sentences(paragraph.replace('\n', ' ').trim())) {
                List<String> passages = new ArrayList<String>();
                for (Integer marker : sentence.getCited()) {
                    String passage = passageOfMarker == null ? null : passageOfMarker.get(marker);
                    if (passage != null && !passage.isEmpty() && !passages.contains(passage)) {
                        passages.add(passage);
                    }
                }
                out.add(new Sentence(sectionId, sentence.getText(), passages));
            }
        }
        return out;
    }

    /** Checks the book: each sentence against every earlier one (of earlier sections, unless the options widen it). */
    public Result check(List<Sentence> book) {
        List<Sentence> sentences = book == null ? Collections.<Sentence>emptyList() : book;
        int n = sentences.size();
        List<Prepared> prepared = new ArrayList<Prepared>(n);
        int bookWords = 0;
        for (Sentence sentence : sentences) {
            Prepared p = prepare(sentence);
            prepared.add(p);
            bookWords += p.words;
        }

        boolean meaning = criteria.getMinSimilarity() > 0;
        String problem = "";
        int withoutVector = 0;
        if (meaning) {
            problem = vectors == null ? "no sentence model: only the word rules ran" : embed(prepared);
            for (Prepared p : prepared) {
                if (!p.stems.isEmpty() && p.unit == null) {
                    withoutVector++;
                }
            }
            if (withoutVector > 0 && problem.isEmpty()) {
                problem = withoutVector + " sentences without a vector";
            }
        }

        List<Pair> pairs = new ArrayList<Pair>();
        List<Pair> nearest = new ArrayList<Pair>();
        int checked = 0;
        int repeatedWords = 0;
        for (int j = 0; j < n; j++) {
            Prepared later = prepared.get(j);
            boolean eligible = later.content.size() >= criteria.getMinWords();
            if (eligible) {
                checked++;
            }
            int best = -1;
            Reason bestReason = null;
            double bestShare = 0;
            double bestSimilarity = -1;
            int matches = 0;
            int near = -1;
            double nearShare = 0;
            double nearSimilarity = -1;
            boolean nearNumbers = false;
            Reason nearReason = null;
            for (int i = 0; i < j; i++) {
                Prepared earlier = prepared.get(i);
                if (!options.isWithinSection()
                        && earlier.sentence.getSectionId().equals(later.sentence.getSectionId())) {
                    continue;
                }
                boolean sameText = !later.key.isEmpty() && later.key.equals(earlier.key);
                if (!eligible && !sameText) {
                    continue;
                }
                boolean numbersCovered = earlier.numbers.containsAll(later.numbers);
                double share = eligible ? words.overlap(later.content, earlier.stems) : 0;
                double similarity = later.unit == null || earlier.unit == null ? -1 : dot(later.unit, earlier.unit);
                Reason reason = null;
                if (sameText) {
                    reason = Reason.SAME_TEXT;
                } else if (numbersCovered && share >= criteria.getMinShare()) {
                    reason = Reason.WORDS;
                } else if (numbersCovered && meaning && similarity >= criteria.getMinSimilarity()) {
                    reason = Reason.MEANING;
                }
                if (eligible && (near < 0 || similarity > nearSimilarity
                        || (similarity == nearSimilarity && share > nearShare))) {
                    near = i;
                    nearShare = share;
                    nearSimilarity = similarity;
                    nearNumbers = numbersCovered;
                    nearReason = reason;
                }
                if (reason == null) {
                    continue;
                }
                matches++;
                if (best < 0 || rank(reason, similarity, share) > rank(bestReason, bestSimilarity, bestShare)) {
                    best = i;
                    bestReason = reason;
                    bestShare = share;
                    bestSimilarity = similarity;
                }
            }
            if (near >= 0) {
                nearest.add(pair(prepared, near, j, nearShare, nearSimilarity, nearNumbers, nearReason,
                        nearReason == null ? 0 : matches));
            }
            if (best >= 0) {
                Prepared earlier = prepared.get(best);
                pairs.add(pair(prepared, best, j, bestShare, bestSimilarity,
                        earlier.numbers.containsAll(later.numbers), bestReason, matches));
                repeatedWords += later.words;
            }
        }
        Status status = problem.isEmpty() ? Status.COMPLETE : Status.INCOMPLETE;
        Result result = new Result(status, problem, n, checked, withoutVector, bookWords, repeatedWords, pairs,
                nearest, criteria, options);
        log("repeat check: " + result);
        return result;
    }

    /** Orders candidates for the one pair of a later sentence: the same text first, then the most similar. */
    private static double rank(Reason reason, double similarity, double share) {
        if (reason == Reason.SAME_TEXT) {
            return 10;
        }
        return similarity >= 0 ? 2 + similarity : share;
    }

    private static Pair pair(List<Prepared> prepared, int earlier, int later, double share, double similarity,
                             boolean numbersCovered, Reason reason, int matches) {
        Sentence e = prepared.get(earlier).sentence;
        Sentence l = prepared.get(later).sentence;
        List<String> shared = new ArrayList<String>();
        for (String passage : l.getPassageIds()) {
            if (e.getPassageIds().contains(passage) && !shared.contains(passage)) {
                shared.add(passage);
            }
        }
        return new Pair(earlier, later, e, l, share, similarity, numbersCovered, reason, shared, matches);
    }

    private Prepared prepare(Sentence sentence) {
        String plain = sentence.plainText();
        List<String> content = words.contentWords(plain);
        Set<String> stems = words.stemsOf(plain);
        int count = 0;
        for (String token : plain.split("[^\\p{L}\\p{Nd}]+")) {
            if (!token.isEmpty()) {
                count++;
            }
        }
        return new Prepared(sentence, plain, CitationValidator.pointKey(sentence.getText()), content, stems,
                RepeatCheck.numbers(plain), count);
    }

    /**
     * Fetches a unit vector for every sentence with words, each distinct text once, in batches. A batch gets
     * {@link Options#getAttempts()} calls; when the last one fails, no further batch is asked.
     *
     * @return the problem, empty when every batch came back
     */
    private String embed(List<Prepared> prepared) {
        Map<String, List<Prepared>> byText = new HashMap<String, List<Prepared>>();
        List<String> texts = new ArrayList<String>();
        for (Prepared p : prepared) {
            if (p.stems.isEmpty()) {
                continue;
            }
            List<Prepared> same = byText.get(p.plain);
            if (same == null) {
                same = new ArrayList<Prepared>();
                byText.put(p.plain, same);
                texts.add(p.plain);
            }
            same.add(p);
        }
        int dimension = -1;
        for (int from = 0; from < texts.size(); from += options.getBatchSize()) {
            List<String> batch = texts.subList(from, Math.min(texts.size(), from + options.getBatchSize()));
            List<float[]> answer = null;
            String failure = "";
            for (int attempt = 1; attempt <= options.getAttempts() && answer == null; attempt++) {
                try {
                    List<float[]> embedded = vectors.embed(Collections.unmodifiableList(new ArrayList<String>(batch)));
                    failure = invalid(embedded, batch.size(), dimension);
                    if (failure.isEmpty()) {
                        answer = embedded;
                    }
                } catch (Exception ex) {
                    failure = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                }
                if (answer == null) {
                    log("repeat check: sentence model failed on batch " + (from / options.getBatchSize() + 1)
                            + ", attempt " + attempt + " of " + options.getAttempts() + ": " + failure);
                }
            }
            if (answer == null) {
                return "sentence model failed: " + failure;
            }
            for (int k = 0; k < batch.size(); k++) {
                float[] unit = unit(answer.get(k));
                dimension = answer.get(k).length;
                for (Prepared p : byText.get(batch.get(k))) {
                    p.unit = unit;
                }
            }
        }
        return "";
    }

    /** Why an answer of the sentence model cannot be used; empty when it can. */
    private static String invalid(List<float[]> embedded, int expected, int dimension) {
        if (embedded == null || embedded.size() != expected) {
            return "sentence model returned " + (embedded == null ? 0 : embedded.size()) + " vectors for " + expected
                    + " sentences";
        }
        Set<Integer> dimensions = new LinkedHashSet<Integer>();
        for (float[] v : embedded) {
            if (v == null || v.length == 0) {
                return "sentence model returned an empty vector";
            }
            dimensions.add(v.length);
        }
        if (dimensions.size() > 1 || (dimension > 0 && !dimensions.contains(dimension))) {
            return "sentence model returned vectors of different dimensions " + dimensions;
        }
        return "";
    }

    /** The vector scaled to length 1, so a cosine is a dot product; a zero vector stays zero (cosine 0). */
    private static float[] unit(float[] v) {
        double norm = 0;
        for (float x : v) {
            norm += x * (double) x;
        }
        float[] out = new float[v.length];
        if (norm == 0) {
            return out;
        }
        double scale = 1 / Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) {
            out[i] = (float) (v[i] * scale);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * (double) b[i];
        }
        return sum;
    }

    private void log(String line) {
        if (log != null) {
            log.accept(line);
        }
    }
}
