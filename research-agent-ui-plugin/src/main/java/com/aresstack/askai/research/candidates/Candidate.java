package com.aresstack.askai.research.candidates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Component 5: one passage the evidence judge should read for a question, with its place in both rankings of the
 * stock. A rank counts from 1; 0 means the ranking did not reach the passage within the depth read (for key words:
 * no key word of the question stands in it, or it lies deeper).
 */
public final class Candidate {

    /** Which list brought the passage in. */
    public enum Via {
        /** The similarity list. */
        SIMILARITY,
        /** The key-word list. */
        KEYWORDS,
        /** Both lists (the passage itself, or another passage with the same text). */
        BOTH,
        /** The fused order of {@link CandidateSearchSettings.Mode#FUSION}. */
        FUSION
    }

    private final String passageId;
    private final String sourceId;
    private final String captureId;
    private final String headingPath;
    private final String text;
    private final String textKey;
    private final int similarityRank;
    private final int keywordRank;
    private final double similarity;
    private final double keywordScore;
    private final Via via;
    private final List<String> sameTextPassageIds;

    Candidate(String passageId, String sourceId, String captureId, String headingPath, String text, String textKey,
              int similarityRank, double similarity, int keywordRank, double keywordScore, Via via,
              List<String> sameTextPassageIds) {
        this.passageId = passageId;
        this.sourceId = sourceId;
        this.captureId = captureId;
        this.headingPath = headingPath;
        this.text = text;
        this.textKey = textKey;
        this.similarityRank = similarityRank;
        this.similarity = similarity;
        this.keywordRank = keywordRank;
        this.keywordScore = keywordScore;
        this.via = via;
        this.sameTextPassageIds = Collections.unmodifiableList(new ArrayList<String>(sameTextPassageIds));
    }

    public String getPassageId() {
        return passageId;
    }

    /** The source as the index stores it (quelleId); empty when the index knows none. */
    public String getSourceId() {
        return sourceId;
    }

    public String getCaptureId() {
        return captureId;
    }

    public String getHeadingPath() {
        return headingPath;
    }

    /** The passage text as the index returned it. */
    public String getText() {
        return text;
    }

    /** {@link CandidateText#key} of the text. */
    public String getTextKey() {
        return textKey;
    }

    /** The rank by similarity to the question, from 1; 0 when the similarity ranking did not reach it. */
    public int getSimilarityRank() {
        return similarityRank;
    }

    /** The cosine similarity to the question; {@code NaN} without a similarity rank. */
    public double getSimilarity() {
        return similarity;
    }

    /** The rank by key words, from 1; 0 when the key-word ranking did not reach it. */
    public int getKeywordRank() {
        return keywordRank;
    }

    /** The key-word score of the text index; {@code NaN} without a key-word rank. */
    public double getKeywordScore() {
        return keywordScore;
    }

    public Via getVia() {
        return via;
    }

    /** Other passages with the same text that the lists left out for this one (traceability only). */
    public List<String> getSameTextPassageIds() {
        return sameTextPassageIds;
    }

    @Override
    public String toString() {
        return passageId + " (" + sourceId + ", similarity " + similarityRank + ", key words " + keywordRank + ", "
                + via + ")";
    }
}
