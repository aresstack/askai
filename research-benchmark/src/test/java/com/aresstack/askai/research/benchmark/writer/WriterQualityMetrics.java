package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.research.runtime.writer.EvidencePassage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The measurements of the writer proposal (stage 0) for one written section: repetition by sentence embeddings,
 * restarts at paragraph starts, reach of the evidence-covered word target, short paragraphs, source blocks and
 * sentences that combine sources, and how much of the pack the text uses. A sentence counts as a repetition when its
 * embedding has a cosine of at least {@link #REPEAT_COSINE} to an earlier sentence of the same text (the
 * proposal's definition, calibrated for bge-m3). The figures are guard rails; the blind reading decides.
 */
public final class WriterQualityMetrics {

    /** The proposal's repetition threshold (bge-m3). */
    public static final double REPEAT_COSINE = 0.80;
    /** A paragraph under this many sentences counts as short. */
    static final int SHORT_PARAGRAPH = 3;

    private static final Pattern MARKER = Pattern.compile("\\[(\\d{1,3}(?:\\s*[,;]\\s*\\d{1,3})*)\\]");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*");
    private static final Pattern SENTENCE_END = Pattern.compile(
            "([.!?][\"'“”»«)]*(?:\\s*\\[[\\d,;\\s]+\\])*)\\s+(?=[\\p{Lu}\\p{N}\"'„“(\\[])");

    /** One sentence of a text: its paragraph, words and the pack numbers it cites. */
    static final class Sentence {
        final int paragraph;
        final boolean first;
        final String text;
        final String plain;
        final int words;
        final List<Integer> cited;

        Sentence(int paragraph, boolean first, String text) {
            this.paragraph = paragraph;
            this.first = first;
            this.text = text;
            this.plain = MARKER.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").trim();
            this.words = count(WORD.matcher(plain));
            List<Integer> numbers = new ArrayList<Integer>();
            Matcher m = MARKER.matcher(text);
            while (m.find()) {
                for (String n : m.group(1).split("[,;]")) {
                    numbers.add(Integer.parseInt(n.trim()));
                }
            }
            this.cited = Collections.unmodifiableList(numbers);
        }
    }

    private final int words;
    private final int wordTarget;
    private final int repeatedWords;
    private final int sentences;
    private final int restartParagraphs;
    private final int paragraphs;
    private final int shortParagraphs;
    private final int citedSentences;
    private final int sourceBlockSentences;
    private final int multiSourceSentences;
    private final int uncitedSentences;
    private final int passagesUsed;
    private final int packSize;

    private WriterQualityMetrics(int words, int wordTarget, int repeatedWords, int sentences, int restartParagraphs,
                                 int paragraphs, int shortParagraphs, int citedSentences, int sourceBlockSentences,
                                 int multiSourceSentences, int uncitedSentences, int passagesUsed, int packSize) {
        this.words = words;
        this.wordTarget = wordTarget;
        this.repeatedWords = repeatedWords;
        this.sentences = sentences;
        this.restartParagraphs = restartParagraphs;
        this.paragraphs = paragraphs;
        this.shortParagraphs = shortParagraphs;
        this.citedSentences = citedSentences;
        this.sourceBlockSentences = sourceBlockSentences;
        this.multiSourceSentences = multiSourceSentences;
        this.uncitedSentences = uncitedSentences;
        this.passagesUsed = passagesUsed;
        this.packSize = packSize;
    }

    /**
     * @param text       the section text with the pack's {@code [n]} markers
     * @param passages   the section's pack (numbers and sources)
     * @param wordTarget the word target the writer had ({@code SectionAssignment.getSectionWords()})
     * @param vectors    sentence embeddings (bge-m3, documents); {@code null} leaves the repetition figures at 0
     */
    public static WriterQualityMetrics measure(String text, List<EvidencePassage> passages, int wordTarget,
                                               Function<List<String>, List<float[]>> vectors) {
        List<Sentence> all = sentences(text);
        Map<Integer, String> sourceOf = new java.util.HashMap<Integer, String>();
        for (EvidencePassage passage : passages) {
            sourceOf.put(passage.getNumber(), passage.getSourceId());
        }
        int words = 0;
        int cited = 0;
        int blocks = 0;
        int multi = 0;
        int uncited = 0;
        Set<Integer> used = new HashSet<Integer>();
        Map<Integer, Integer> paragraphSizes = new java.util.TreeMap<Integer, Integer>();
        List<Integer> previous = null;
        for (Sentence s : all) {
            words += s.words;
            paragraphSizes.put(s.paragraph, paragraphSizes.containsKey(s.paragraph)
                    ? paragraphSizes.get(s.paragraph) + 1 : 1);
            Set<Integer> distinct = new LinkedHashSet<Integer>(s.cited);
            if (distinct.isEmpty()) {
                uncited++;
            } else {
                cited++;
                used.addAll(distinct);
                Set<String> sources = new HashSet<String>();
                for (Integer n : distinct) {
                    sources.add(sourceOf.containsKey(n) ? sourceOf.get(n) : "?" + n);
                }
                if (sources.size() >= 2) {
                    multi++;
                }
                if (distinct.size() == 1 && previous != null && previous.size() == 1
                        && previous.get(0).equals(distinct.iterator().next())) {
                    blocks++;
                }
            }
            previous = new ArrayList<Integer>(distinct);
        }
        int shortOnes = 0;
        for (int size : paragraphSizes.values()) {
            if (size < SHORT_PARAGRAPH) {
                shortOnes++;
            }
        }
        int repeated = 0;
        int restarts = 0;
        if (vectors != null && all.size() > 1) {
            List<String> plain = new ArrayList<String>();
            for (Sentence s : all) {
                plain.add(s.plain);
            }
            List<float[]> v = vectors.apply(plain);
            for (int i = 1; i < all.size(); i++) {
                double best = 0;
                for (int j = 0; j < i; j++) {
                    best = Math.max(best, cosine(v.get(i), v.get(j)));
                }
                if (best >= REPEAT_COSINE) {
                    repeated += all.get(i).words;
                    if (all.get(i).first && all.get(i).paragraph > 0) {
                        restarts++;
                    }
                }
            }
        }
        int packSize = sourceOf.size();
        used.retainAll(sourceOf.keySet());
        return new WriterQualityMetrics(words, wordTarget, repeated, all.size(), restarts, paragraphSizes.size(),
                shortOnes, cited, blocks, multi, uncited, used.size(), packSize);
    }

    /** The sentences of a text by paragraph; headings and GAP lines are left out. */
    static List<Sentence> sentences(String text) {
        List<Sentence> out = new ArrayList<Sentence>();
        int paragraph = 0;
        for (String block : (text == null ? "" : text).replace("\r\n", "\n").split("\n\\s*\n")) {
            String trimmed = block.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("GAP:")) {
                continue;
            }
            String joined = trimmed.replace('\n', ' ');
            boolean first = true;
            for (String part : SENTENCE_END.matcher(joined).replaceAll("$1\n").split("\n")) {
                String sentence = part.trim();
                if (!sentence.isEmpty()) {
                    out.add(new Sentence(paragraph, first, sentence));
                    first = false;
                }
            }
            paragraph++;
        }
        return out;
    }

    /**
     * The repeated words of a whole chapter: sentences of every section in order, each compared with all earlier
     * sentences of the chapter.
     */
    public static double chapterRepetition(List<String> sectionTexts, Function<List<String>, List<float[]>> vectors) {
        List<Sentence> all = new ArrayList<Sentence>();
        for (String text : sectionTexts) {
            all.addAll(sentences(text));
        }
        if (vectors == null || all.size() < 2) {
            return 0;
        }
        List<String> plain = new ArrayList<String>();
        int words = 0;
        for (Sentence s : all) {
            plain.add(s.plain);
            words += s.words;
        }
        List<float[]> v = vectors.apply(plain);
        int repeated = 0;
        for (int i = 1; i < all.size(); i++) {
            for (int j = 0; j < i; j++) {
                if (cosine(v.get(i), v.get(j)) >= REPEAT_COSINE) {
                    repeated += all.get(i).words;
                    break;
                }
            }
        }
        return words == 0 ? 0 : repeated / (double) words;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * (double) b[i];
            na += a[i] * (double) a[i];
            nb += b[i] * (double) b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static int count(Matcher matcher) {
        int n = 0;
        while (matcher.find()) {
            n++;
        }
        return n;
    }

    public int getWords() {
        return words;
    }

    public int getWordTarget() {
        return wordTarget;
    }

    /** Words in sentences that repeat an earlier sentence of the section, as a share of all words. */
    public double repetition() {
        return words == 0 ? 0 : repeatedWords / (double) words;
    }

    /** Words over the evidence-covered word target. */
    public double targetReach() {
        return wordTarget <= 0 ? 0 : words / (double) wordTarget;
    }

    /** Paragraphs (after the first) whose first sentence repeats something the section already said. */
    public int getRestartParagraphs() {
        return restartParagraphs;
    }

    public int getParagraphs() {
        return paragraphs;
    }

    public int getShortParagraphs() {
        return shortParagraphs;
    }

    public int getSentences() {
        return sentences;
    }

    /** Cited sentences that cite the same single passage as the sentence before, as a share of cited sentences. */
    public double sourceBlocks() {
        return citedSentences == 0 ? 0 : sourceBlockSentences / (double) citedSentences;
    }

    /** Sentences citing passages of at least two sources, as a share of cited sentences. */
    public double multiSource() {
        return citedSentences == 0 ? 0 : multiSourceSentences / (double) citedSentences;
    }

    public int getUncitedSentences() {
        return uncitedSentences;
    }

    /** Distinct pack passages the text cites, as a share of the pack. */
    public double packUse() {
        return packSize == 0 ? 0 : passagesUsed / (double) packSize;
    }

    public int getPackSize() {
        return packSize;
    }
}
