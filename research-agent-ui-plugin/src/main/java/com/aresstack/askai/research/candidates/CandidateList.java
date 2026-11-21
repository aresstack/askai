package com.aresstack.askai.research.candidates;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Component 5: the candidates for one question, and what each ranking delivered. A list is {@link #isFailed()
 * failed} when it holds no candidate because a ranking failed: the caller keeps the question open and asks again, it
 * never reads that as "no evidence found". An empty list that is not failed means no passage of the stock's world
 * matched.
 */
public final class CandidateList {

    /** What one ranking delivered. */
    public enum PathState {
        /** It returned passages. */
        DELIVERED,
        /** It ran and returned nothing. */
        EMPTY,
        /** It could not run (no question vector of the stock's world) or broke off; see the notes. */
        FAILED
    }

    private final String question;
    private final CandidateSearchSettings settings;
    private final List<Candidate> candidates;
    private final PathState similarity;
    private final PathState keywords;
    private final int similarityRead;
    private final int keywordsRead;
    private final List<String> notes;

    CandidateList(String question, CandidateSearchSettings settings, List<Candidate> candidates,
                  PathState similarity, int similarityRead, PathState keywords, int keywordsRead,
                  List<String> notes) {
        this.question = question;
        this.settings = settings;
        this.candidates = Collections.unmodifiableList(new ArrayList<Candidate>(candidates));
        this.similarity = similarity;
        this.similarityRead = similarityRead;
        this.keywords = keywords;
        this.keywordsRead = keywordsRead;
        this.notes = Collections.unmodifiableList(new ArrayList<String>(notes));
    }

    public String getQuestion() {
        return question;
    }

    public CandidateSearchSettings getSettings() {
        return settings;
    }

    public CandidateSearchSettings.Mode getMode() {
        return settings.getMode();
    }

    /** The candidates; for {@link CandidateSearchSettings.Mode#UNION} the two lists interleaved, best first. */
    public List<Candidate> getCandidates() {
        return candidates;
    }

    public int size() {
        return candidates.size();
    }

    public List<String> passageIds() {
        List<String> ids = new ArrayList<String>(candidates.size());
        for (Candidate candidate : candidates) {
            ids.add(candidate.getPassageId());
        }
        return ids;
    }

    public PathState getSimilarity() {
        return similarity;
    }

    public PathState getKeywords() {
        return keywords;
    }

    /** Hits read from the similarity ranking (at most the depth). */
    public int getSimilarityRead() {
        return similarityRead;
    }

    /** Hits read from the key-word ranking (at most the depth). */
    public int getKeywordsRead() {
        return keywordsRead;
    }

    /** No candidate, and a ranking failed: an error to retry, never "no evidence found". */
    public boolean isFailed() {
        return candidates.isEmpty() && (similarity == PathState.FAILED || keywords == PathState.FAILED);
    }

    /** What went wrong or stood alone, in plain sentences; empty when both rankings delivered. */
    public List<String> getNotes() {
        return notes;
    }
}
