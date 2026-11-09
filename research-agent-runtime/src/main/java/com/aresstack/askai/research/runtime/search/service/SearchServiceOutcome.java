package com.aresstack.askai.research.runtime.search.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What one {@link SearchJob} came to. A paused or blocked engine is its own status, never "no results". */
public final class SearchServiceOutcome {

    public enum Status {
        /** The engine answered and at least one new page was queued. */
        QUEUED,
        /** The engine answered, but every hit was already known, dropped or there were none. */
        NOTHING_NEW,
        /** The same query (normalised) already ran for this project; no engine request was made. */
        DUPLICATE_QUERY,
        /** The engines are paused after a challenge; no request was made. {@link #getPauseReason()} says why. */
        ENGINE_PAUSED,
        /** The engine answered this request with a challenge; the engines are now paused. */
        ENGINE_BLOCKED,
        /** The search failed technically (strategy error, unextractable SERP). */
        TECHNICAL_PROBLEM,
        /** The caller cancelled before the engine was asked. */
        CANCELLED
    }

    private final Status status;
    private final List<PageCandidate> queued;
    private final int hits;
    private final int alreadyKnown;
    private final int dropped;
    private final String pauseReason;
    private final long pausedUntil;
    private final List<String> diagnostics;

    SearchServiceOutcome(Status status, List<PageCandidate> queued, int hits, int alreadyKnown, int dropped,
                         String pauseReason, long pausedUntil,
                         List<String> diagnostics) {
        this.status = status;
        this.queued = Collections.unmodifiableList(new ArrayList<PageCandidate>(queued));
        this.hits = hits;
        this.alreadyKnown = alreadyKnown;
        this.dropped = dropped;
        this.pauseReason = pauseReason == null ? "" : pauseReason;
        this.pausedUntil = pausedUntil;
        this.diagnostics = Collections.unmodifiableList(new ArrayList<String>(diagnostics));
    }

    static SearchServiceOutcome of(Status status, String pauseReason, long pausedUntil, List<String> diagnostics) {
        return new SearchServiceOutcome(status, Collections.<PageCandidate>emptyList(), 0, 0, 0,
                pauseReason, pausedUntil, diagnostics);
    }

    public Status getStatus() {
        return status;
    }

    /** The pages this job put on the queue, in engine order. */
    public List<PageCandidate> getQueued() {
        return queued;
    }

    /** Hits the engine returned. */
    public int getHits() {
        return hits;
    }

    /** Hits whose page was already in the project's URL registry. */
    public int getAlreadyKnown() {
        return alreadyKnown;
    }

    /** Hits dropped: engine wrappers without a resolver, duplicates within the batch, no URL. */
    public int getDropped() {
        return dropped;
    }

    /** Why the engines are paused (empty when they are not). */
    public String getPauseReason() {
        return pauseReason;
    }

    /** Until when the engines are paused (epoch millis; 0 when not paused). */
    public long getPausedUntil() {
        return pausedUntil;
    }

    public List<String> getDiagnostics() {
        return diagnostics;
    }
}
