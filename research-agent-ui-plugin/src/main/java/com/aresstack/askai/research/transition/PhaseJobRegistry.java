package com.aresstack.askai.research.transition;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The running jobs per phase and run token (D3). A transition that leaves a phase cancels its jobs and moves
 * the run token on, so an outcome that carries an old token or another phase is dropped.
 */
public final class PhaseJobRegistry {

    /** A registered job; {@link #cancel()} must be safe to call more than once. */
    public interface Job {
        void cancel();
    }

    private static final class Entry {
        final String phaseId;
        final long token;
        final Job job;

        Entry(String phaseId, long token, Job job) {
            this.phaseId = phaseId;
            this.token = token;
            this.job = job;
        }
    }

    private final List<Entry> entries = new ArrayList<Entry>();
    private long runToken;

    /** The current run token; it changes on every phase change. */
    public synchronized long currentToken() {
        return runToken;
    }

    /** Register a job of a phase under the current token; @return that token. */
    public synchronized long register(String phaseId, Job job) {
        entries.add(new Entry(phaseId, runToken, job));
        return runToken;
    }

    /** Remove a finished job. */
    public synchronized void unregister(Job job) {
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            if (it.next().job == job) {
                it.remove();
            }
        }
    }

    /** Whether an outcome of this phase and token still belongs to the live run. */
    public synchronized boolean isCurrent(String phaseId, long token, String livePhaseId) {
        return token == runToken && phaseId != null && phaseId.equals(livePhaseId);
    }

    /** Cancel every job of the left phase, move the token on; @return how many jobs were cancelled. */
    public int leavePhase(String phaseId) {
        List<Job> cancel = new ArrayList<Job>();
        synchronized (this) {
            runToken++;
            for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
                // every registered job belongs to the phase that is being left
                cancel.add(it.next().job);
                it.remove();
            }
        }
        for (Job job : cancel) {
            try {
                job.cancel();
            } catch (RuntimeException ignored) {
                // a failing cancel must never stop the transition's other effects
            }
        }
        return cancel.size();
    }

    public synchronized int size() {
        return entries.size();
    }
}
