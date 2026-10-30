package com.aresstack.askai.research.benchmark;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What one research run of a subject produced, as raw observations. Scoring is separate
 * ({@link BenchmarkScorer}), so a record can be re-scored when the metrics evolve.
 */
public final class BenchmarkRunRecord {

    /** One accepted (persisted) source. */
    public static final class AcceptedSource {
        public final String url;
        public final String title;
        public final String text;
        /** The corpus page behind {@link #url}, or null; set by {@link #resolvePages} while the world runs. */
        String pageId;

        public AcceptedSource(String url, String title, String text) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
            this.text = text == null ? "" : text;
        }

        public String getPageId() {
            return pageId;
        }
    }

    /** Outcome of the resume-after-interruption scenario. */
    public static final class ResumeOutcome {
        /** Accepted sources when the run was interrupted. */
        public final int acceptedBeforeInterruption;
        /** Whether the subject accepted the continuation at all. */
        public final boolean resumed;
        /** Stop reason after the continuation ("" when it never finished). */
        public final String stopReasonAfterResume;
        public final boolean sufficientAfterResume;
        /** URLs persisted more than once across the interruption. */
        public final int duplicateSources;
        /** Pages the continuation visited again although they were already captured before the interruption. */
        public final int revisitedPages;
        public final long wallMillis;
        public final String note;

        public ResumeOutcome(int acceptedBeforeInterruption, boolean resumed, String stopReasonAfterResume,
                             boolean sufficientAfterResume, int duplicateSources, int revisitedPages,
                             long wallMillis, String note) {
            this.acceptedBeforeInterruption = acceptedBeforeInterruption;
            this.resumed = resumed;
            this.stopReasonAfterResume = stopReasonAfterResume == null ? "" : stopReasonAfterResume;
            this.sufficientAfterResume = sufficientAfterResume;
            this.duplicateSources = duplicateSources;
            this.revisitedPages = revisitedPages;
            this.wallMillis = wallMillis;
            this.note = note == null ? "" : note;
        }
    }

    String subject = "";
    String model = "";
    String questionId = "";
    int repetition;
    /** False when the run never produced a structured outcome (timeout, start failure). */
    boolean finished;
    String failure = "";
    /** True when the environment, not the subject, failed (no browser, session could not start). */
    boolean environmentFailure;
    String stopReason = "";
    boolean evidenceSufficient;
    int pagesVisited;
    int distinctHosts;
    long wallMillis;
    /** Milliseconds from the prompt to the first accepted source, or -1. */
    long millisToFirstSource = -1;
    int toolCalls;
    int toolFailures;
    OllamaTokenMeter.Usage usage = new OllamaTokenMeter.Usage(0, 0, 0, 0, 0);
    final List<AcceptedSource> acceptedSources = new ArrayList<AcceptedSource>();
    /** URLs the subject visited (captures), in order. */
    final List<String> visitedUrls = new ArrayList<String>();
    /** The subject's user-facing statements (assistant messages); claims are taken from these. */
    final List<String> statements = new ArrayList<String>();
    /**
     * Contradictions and gaps the subject reported in STRUCTURED form (later roles emit them as schema
     * output). Null means the subject has no such output, and the scorer falls back to its text heuristic.
     */
    List<String> reportedContradictions;
    List<String> reportedGaps;
    ResumeOutcome resume;

    public BenchmarkRunRecord(String subject, String model, String questionId, int repetition) {
        this.subject = subject;
        this.model = model;
        this.questionId = questionId;
        this.repetition = repetition;
    }

    /**
     * Map every accepted source to its corpus page. Called while the fixture world still runs, because its
     * URLs carry that world's ports.
     */
    public void resolvePages(BenchmarkScorer.FixtureWorldIndex world) {
        for (AcceptedSource source : acceptedSources) {
            BenchmarkQuestion.Page page = world.pageForUrl(source.url);
            source.pageId = page == null ? null : page.getId();
        }
    }

    public String getSubject() {
        return subject;
    }

    public String getModel() {
        return model;
    }

    public String getQuestionId() {
        return questionId;
    }

    public int getRepetition() {
        return repetition;
    }

    public boolean isFinished() {
        return finished;
    }

    public String getFailure() {
        return failure;
    }

    public boolean isEnvironmentFailure() {
        return environmentFailure;
    }

    public String getStopReason() {
        return stopReason;
    }

    public boolean isEvidenceSufficient() {
        return evidenceSufficient;
    }

    public int getPagesVisited() {
        return pagesVisited;
    }

    public long getWallMillis() {
        return wallMillis;
    }

    public long getMillisToFirstSource() {
        return millisToFirstSource;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public int getToolFailures() {
        return toolFailures;
    }

    public OllamaTokenMeter.Usage getUsage() {
        return usage;
    }

    public List<AcceptedSource> getAcceptedSources() {
        return Collections.unmodifiableList(acceptedSources);
    }

    public List<String> getVisitedUrls() {
        return Collections.unmodifiableList(visitedUrls);
    }

    public List<String> getStatements() {
        return Collections.unmodifiableList(statements);
    }

    public ResumeOutcome getResume() {
        return resume;
    }
}
