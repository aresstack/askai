package com.aresstack.askai.research.benchmark.writer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The deterministic measurements of one written sample (a section or the sample chapter): words, paragraphs,
 * citation validity against the evidence pack's numbered passages [1]..[k], the share of paragraphs without a
 * citation and the language drift (paragraphs whose function words point to the other language).
 */
public final class WriterSampleMetrics {

    private static final Pattern CITATION = Pattern.compile("\\[(\\d+(?:\\s*[,;]\\s*\\d+)*)\\]");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'’-]*");
    private static final Set<String> GERMAN = new HashSet<String>(Arrays.asList(
            "der", "die", "das", "und", "ist", "nicht", "mit", "von", "den", "dem", "ein", "eine", "auf", "für",
            "sich", "auch", "werden", "wird", "sind", "oder", "bei", "nach", "durch", "zu", "im", "als"));
    private static final Set<String> ENGLISH = new HashSet<String>(Arrays.asList(
            "the", "and", "is", "not", "with", "of", "a", "an", "on", "for", "are", "or", "by", "to", "in",
            "as", "that", "this", "which", "be", "from", "it", "was", "were", "have", "has"));

    private final int words;
    private final int paragraphs;
    private final int citations;
    private final int invalidCitations;
    private final int uncitedParagraphs;
    private final int driftedParagraphs;

    private WriterSampleMetrics(int words, int paragraphs, int citations, int invalidCitations,
                                int uncitedParagraphs, int driftedParagraphs) {
        this.words = words;
        this.paragraphs = paragraphs;
        this.citations = citations;
        this.invalidCitations = invalidCitations;
        this.uncitedParagraphs = uncitedParagraphs;
        this.driftedParagraphs = driftedParagraphs;
    }

    /**
     * @param text          the written Markdown; headings are not counted as paragraphs
     * @param passageCount  k, the number of passages in the evidence pack
     * @param bookLanguage  "de" or "en"; any other value disables the drift count
     */
    public static WriterSampleMetrics measure(String text, int passageCount, String bookLanguage) {
        String language = bookLanguage == null ? "" : bookLanguage.trim().toLowerCase(Locale.ROOT);
        int words = 0;
        int paragraphs = 0;
        int citations = 0;
        int invalid = 0;
        int uncited = 0;
        int drifted = 0;
        for (String paragraph : paragraphs(text == null ? "" : text)) {
            paragraphs++;
            words += count(WORD.matcher(CITATION.matcher(paragraph).replaceAll(" ")));
            Matcher marker = CITATION.matcher(paragraph);
            int here = 0;
            while (marker.find()) {
                for (String number : marker.group(1).split("[,;]")) {
                    here++;
                    int n = Integer.parseInt(number.trim());
                    if (n < 1 || n > passageCount) {
                        invalid++;
                    }
                }
            }
            citations += here;
            if (here == 0) {
                uncited++;
            }
            if (isDrifted(paragraph, language)) {
                drifted++;
            }
        }
        return new WriterSampleMetrics(words, paragraphs, citations, invalid, uncited, drifted);
    }

    static List<String> paragraphs(String text) {
        List<String> result = new ArrayList<String>();
        for (String block : text.replace("\r\n", "\n").split("\n\\s*\n")) {
            String trimmed = block.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private static boolean isDrifted(String paragraph, String language) {
        if (!"de".equals(language) && !"en".equals(language)) {
            return false;
        }
        int german = 0;
        int english = 0;
        Matcher word = WORD.matcher(paragraph.toLowerCase(Locale.ROOT));
        while (word.find()) {
            String w = word.group();
            if (GERMAN.contains(w)) {
                german++;
            }
            if (ENGLISH.contains(w)) {
                english++;
            }
        }
        return "de".equals(language) ? english > german : german > english;
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

    public int getParagraphs() {
        return paragraphs;
    }

    public int getCitations() {
        return citations;
    }

    public int getInvalidCitations() {
        return invalidCitations;
    }

    /** Valid citations / all citations; 1.0 when the sample cites nothing (the uncited rate shows that). */
    public double citationValidity() {
        return citations == 0 ? 1.0 : (citations - invalidCitations) / (double) citations;
    }

    public double uncitedParagraphRate() {
        return paragraphs == 0 ? 0.0 : uncitedParagraphs / (double) paragraphs;
    }

    public double languageDrift() {
        return paragraphs == 0 ? 0.0 : driftedParagraphs / (double) paragraphs;
    }
}
