package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 2/3: characteristic terms of a passage set, deterministic and without a model: TF-IDF of the set's words
 * against the whole corpus, so words every passage uses (articles, the book's subject itself) sink and the words that
 * set this material apart rise. A word is a run of letters or digits of at least {@link #MIN_TERM_CHARS} characters
 * (the convention of the live projection's question coverage), lower-cased; pure numbers are skipped.
 */
public final class KeyTerms {

    /** The shortest word that counts as a term (the live projection's "meaningful token" rule). */
    public static final int MIN_TERM_CHARS = 4;

    private final Map<String, Integer> documentFrequency = new HashMap<String, Integer>();
    private final int documents;

    /** @param corpusTexts every passage text of the corpus (one document each) */
    public KeyTerms(Collection<String> corpusTexts) {
        int count = 0;
        for (String text : corpusTexts) {
            count++;
            for (String term : new HashSet<String>(terms(text))) {
                Integer seen = documentFrequency.get(term);
                documentFrequency.put(term, seen == null ? 1 : seen + 1);
            }
        }
        this.documents = count;
    }

    /** The {@code count} highest-scoring terms of {@code texts}, best first; ties by the term. */
    public List<String> of(Collection<String> texts, int count) {
        final Map<String, Integer> frequency = new HashMap<String, Integer>();
        for (String text : texts) {
            for (String term : terms(text)) {
                Integer seen = frequency.get(term);
                frequency.put(term, seen == null ? 1 : seen + 1);
            }
        }
        final Map<String, Double> score = new HashMap<String, Double>();
        for (Map.Entry<String, Integer> entry : frequency.entrySet()) {
            Integer df = documentFrequency.get(entry.getKey());
            double idf = Math.log((documents + 1.0) / ((df == null ? 0 : df) + 1.0)) + 1.0;
            score.put(entry.getKey(), entry.getValue() * idf);
        }
        List<String> ranked = new ArrayList<String>(score.keySet());
        Collections.sort(ranked, new Comparator<String>() {
            public int compare(String a, String b) {
                int byScore = Double.compare(score.get(b), score.get(a));
                return byScore != 0 ? byScore : a.compareTo(b);
            }
        });
        return ranked.size() > count ? new ArrayList<String>(ranked.subList(0, Math.max(0, count))) : ranked;
    }

    /** The words of a label path ("Book > Safety > Helmets"), in order, without duplicates. */
    public static List<String> labelWords(String path) {
        Set<String> seen = new java.util.LinkedHashSet<String>();
        for (String word : (path == null ? "" : path).split("[^\\p{L}\\p{Nd}]+")) {
            if (!word.isEmpty()) {
                seen.add(word);
            }
        }
        return new ArrayList<String>(seen);
    }

    static List<String> terms(String text) {
        List<String> terms = new ArrayList<String>();
        for (String word : (text == null ? "" : text).toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() >= MIN_TERM_CHARS && !word.matches("\\p{Nd}+")) {
                terms.add(word);
            }
        }
        return terms;
    }
}
