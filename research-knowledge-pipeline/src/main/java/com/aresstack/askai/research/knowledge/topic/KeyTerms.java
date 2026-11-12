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
 * Phase 2/3: characteristic terms of a passage set, deterministic and without a model or word list. A term scores by
 * how much more often the set's passages use it than the corpus does, weighted by how rare it is in the corpus:
 * {@code df_set * ln(share_set / share_corpus) * ln(N / df_corpus)}, with the shares counted in passages. A term the
 * set uses no more often than the corpus scores nothing and is no key term: articles, auxiliaries and conjunctions of
 * any language, and the subject of the whole book, are spread over every topic alike. The rarity weight keeps a
 * function word out that a set uses only slightly more often (live run 4 after #203: "diese" and "nach" in nodes
 * whose sources were more often German than the rest of the corpus).
 * <p>
 * Live run 4 (2026-11-12): the former TF-IDF counted raw occurrences, so in a topic of 1681 passages "werden, oder,
 * eine" outweighed every word that sets the material apart.
 * <p>
 * A word is a run of letters or digits of at least {@link #MIN_TERM_CHARS} characters (the convention of the live
 * projection's question coverage), lower-cased; pure numbers are skipped.
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

    /**
     * The {@code count} highest-scoring terms of {@code texts}, best first; ties by the term. Fewer when fewer terms
     * are more frequent in the set than in the corpus (a set holding the whole corpus has none).
     */
    public List<String> of(Collection<String> texts, int count) {
        final Map<String, Integer> setFrequency = new HashMap<String, Integer>();
        int setDocuments = 0;
        for (String text : texts) {
            setDocuments++;
            for (String term : new HashSet<String>(terms(text))) {
                Integer seen = setFrequency.get(term);
                setFrequency.put(term, seen == null ? 1 : seen + 1);
            }
        }
        long corpus = Math.max(documents, setDocuments);
        final Map<String, Double> score = new HashMap<String, Double>();
        for (Map.Entry<String, Integer> entry : setFrequency.entrySet()) {
            long inSet = entry.getValue();
            Integer known = documentFrequency.get(entry.getKey());
            long inCorpus = Math.max(inSet, known == null ? 0 : known);
            // share_set > share_corpus, compared exactly in integers so an even spread never scores by rounding
            if (inSet * corpus > inCorpus * setDocuments) {
                score.put(entry.getKey(),
                        inSet * Math.log(((double) inSet * corpus) / ((double) inCorpus * setDocuments))
                                * Math.log((double) corpus / inCorpus));
            }
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
