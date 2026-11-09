package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.transition.CorruptProjectFileException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The host-side search plan queue (plan PR 14, D5). It runs one pending entry at a time through the manual search
 * port, with its own correlation ids, so every planned query gets the readiness judge, the SERP language and the
 * scope gate of the manual search, and the search provider of the Search tab. It sets no agent-turn flag and posts
 * no chat line per query.
 * <ul>
 * <li>Two acquisitions never overlap: the queue starts an entry only while no other search or research turn runs
 * ({@link Busy}), and a typed search or research-loop turn started while an entry runs waits until that entry ends
 * ({@link #deferUntilEntryEnds}).</li>
 * <li>"Stop search plan" ({@link #stop}) cancels the current query, leaves it pending and stops the queue; "Run
 * search plan" ({@link #run}) continues. A phase change stops the queue through its registered job.</li>
 * <li>Priority entries run first, then the plan order. Every planned query of a node runs (the variants are the
 * node's angles; a node is covered only once they ran, {@link SearchPlanCoverage}); entries of nodes whose query
 * budget is spent are skipped; the phase cap stops the queue. Planned queries keep the recorded working language.</li>
 * <li>Research cycles (M2): when no pending entry is left and nodes are still uncovered, the queue asks the
 * {@link Replanner} for the queries of the next cycle ({@link SearchPlanCycles}) while cycles are left, and runs
 * them. The {@link Outcome} says why the plan ended: every node covered, the research budget spent, the phase
 * source limit reached, or no planner answer — never "covered" for a stop.</li>
 * </ul>
 */
public final class SearchPlanQueue {

    static final String NODE_COVERED = "The node is covered";
    static final String NODE_BUDGET = "The query budget of the node is spent";
    static final String STOPPED = "Stopped by the user";
    static final String KEYWORD_FALLBACK = "Ranked by keywords, reranker unavailable";

    /** The ranking a planned query asks for until the retrieval benchmark of PR 04 fixes another one. */
    public static final String PLANNED_RANKING = "reranker";

    /** The manual search port as the queue sees it. */
    public interface SearchPort {
        /** Start a search; @return its request id. */
        String search(String query, String language, String ranking, int visitLimit, int sourceCap);

        /** Cancel the running search. */
        void cancel();
    }

    /** The accepted sources of a search request. */
    public interface Sources extends SearchPlanCoverage.Domains {
        List<String> acceptedBy(String requestId);
    }

    /** Whether another acquisition (a typed search, a review, a research turn) runs right now. */
    public interface Busy {
        boolean busy();
    }

    /** What the queue tells the session. */
    public interface Listener {
        /** The plan or its status changed (checklist refresh). */
        void changed();

        /** The plan ended; {@code outcome} says why and names the uncovered nodes. */
        void finished(Outcome outcome);
    }

    /**
     * Plans the queries of the next research cycle for the still uncovered nodes (the planner role over
     * {@code plan_queries}, minutes on a small model). It answers on any thread; a {@code null} answer or an
     * exception ends the plan with {@link Reason#PLANNER_UNAVAILABLE}. The session fills in the book title and the
     * blacklist of the frozen concept ({@link ConceptQueryPlan.Request#withBook}).
     */
    public interface Replanner {
        void replan(ConceptQueryPlan.Request request, Replanned callback);
    }

    /** The planner's answer for a cycle. */
    public interface Replanned {
        void done(ConceptQueryPlan.Result result);
    }

    /** Why the plan ended. */
    public enum Reason {
        /** Every active node is covered. */
        COVERED,
        /** Nodes are uncovered and the research budget (cycles × queries per node) is spent. */
        BUDGET,
        /** The source limit of the Sources phase stopped the queue. */
        PHASE_CAP,
        /** The planner gave no usable query for the next cycle (model unavailable or an unusable answer). */
        PLANNER_UNAVAILABLE,
        /**
         * {@link #MAX_CONSECUTIVE_FAILURES} planned searches in a row (retries included) failed before any page
         * was searched (the engine blocked or the browser is gone): the plan pauses, every entry stays pending.
         */
        SEARCH_FAILED
    }

    /** Planned searches that may fail in a row before the queue pauses with {@link Reason#SEARCH_FAILED}. */
    static final int MAX_CONSECUTIVE_FAILURES = 3;

    /**
     * The outcome of the plan: the reason it ended, how many nodes are covered, which are not, and what it cost
     * (cycles, queries run, sources and domains). {@link #text()} is the one chat line, {@link #report()} the
     * gap report stored next to the plan.
     */
    public static final class Outcome {
        private final Reason reason;
        private final int nodes;
        private final List<String> uncovered;
        private final int cycles;
        private final int queriesRun;
        private final int sources;
        private final int domains;
        private final List<String> nodeLines;
        private final String detail;

        Outcome(Reason reason, int nodes, List<String> uncovered, int cycles, int queriesRun, int sources,
                int domains, List<String> nodeLines) {
            this(reason, nodes, uncovered, cycles, queriesRun, sources, domains, nodeLines, "");
        }

        Outcome(Reason reason, int nodes, List<String> uncovered, int cycles, int queriesRun, int sources,
                int domains, List<String> nodeLines, String detail) {
            this.detail = detail == null ? "" : detail;
            this.reason = reason;
            this.nodes = nodes;
            this.uncovered = Collections.unmodifiableList(uncovered);
            this.cycles = cycles;
            this.queriesRun = queriesRun;
            this.sources = sources;
            this.domains = domains;
            this.nodeLines = Collections.unmodifiableList(nodeLines);
        }

        public Reason getReason() {
            return reason;
        }

        public int getNodes() {
            return nodes;
        }

        /** The labels of the uncovered nodes, exhausted or not, in concept order. */
        public List<String> getUncovered() {
            return uncovered;
        }

        public boolean isPhaseCapReached() {
            return reason == Reason.PHASE_CAP;
        }

        /** The research cycles the plan ran. */
        public int getCycles() {
            return cycles;
        }

        public int getQueriesRun() {
            return queriesRun;
        }

        /** The sources the plan accepted (counted once) and the distinct domains they come from. */
        public int getSources() {
            return sources;
        }

        public int getDomains() {
            return domains;
        }

        /** The one outcome line (English). */
        public String text() {
            int covered = nodes - uncovered.size();
            String cost = sources + (sources == 1 ? " source" : " sources") + " from " + domains
                    + (domains == 1 ? " domain" : " domains") + ", " + queriesRun
                    + (queriesRun == 1 ? " query" : " queries") + " run in " + cycles
                    + (cycles == 1 ? " research cycle" : " research cycles");
            StringBuilder sb = new StringBuilder();
            switch (reason) {
                case COVERED:
                    sb.append("Search plan finished: all ").append(nodes).append(nodes == 1 ? " node is" : " nodes are")
                            .append(" covered (").append(cost).append("). Finish Sources when you are ready.");
                    return sb.toString();
                case PHASE_CAP:
                    sb.append("Search plan stopped: the source limit of the Sources phase is reached (")
                            .append(cost).append("); ").append(covered).append(" of ").append(nodes)
                            .append(" nodes covered.");
                    break;
                case SEARCH_FAILED:
                    sb.append("Search plan paused: ").append(MAX_CONSECUTIVE_FAILURES)
                            .append(" planned searches in a row failed before any page was searched")
                            .append(detail.isEmpty() ? "" : " (last: " + detail + ")").append("; ")
                            .append(covered).append(" of ").append(nodes).append(" nodes covered (").append(cost)
                            .append(").");
                    break;
                case PLANNER_UNAVAILABLE:
                    sb.append("Search plan stopped: the planner wrote no new queries for research cycle ")
                            .append(cycles + 1).append(" (model unavailable or no usable answer); ")
                            .append(covered).append(" of ").append(nodes).append(" nodes covered (").append(cost)
                            .append(").");
                    break;
                case BUDGET:
                default:
                    sb.append("Search plan stopped by its research budget, not because the topic is exhausted: ")
                            .append(covered).append(" of ").append(nodes).append(" nodes covered (").append(cost)
                            .append(").");
                    break;
            }
            if (!uncovered.isEmpty()) {
                sb.append(" Still uncovered: ");
                for (int i = 0; i < uncovered.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(uncovered.get(i));
                }
                sb.append('.');
            }
            switch (reason) {
                case PHASE_CAP:
                    sb.append(" Raise \"Sources for the Sources phase\" and run the plan again, or finish Sources "
                            + "with these gaps on record.");
                    break;
                case PLANNER_UNAVAILABLE:
                    sb.append(" Run the search plan again to retry, add queries for these nodes yourself, or finish "
                            + "Sources with these gaps on record.");
                    break;
                case SEARCH_FAILED:
                    sb.append(" The remaining queries stay pending: check the search engine (a blocked engine needs "
                            + "a pause or a fallback engine in the browser settings), then run the plan again, or "
                            + "finish Sources with these gaps on record.");
                    break;
                default:
                    sb.append(" Add queries for these nodes, raise \"Research cycles\" and run the plan again, or "
                            + "finish Sources with these gaps on record.");
                    break;
            }
            return sb.toString();
        }

        /** The gap report stored as {@code search-plan/outcome.md}: the outcome line and one line per node. */
        public String report() {
            StringBuilder sb = new StringBuilder("# Search plan outcome\n\n").append(text()).append("\n\n");
            sb.append("Reason: ").append(reason.name().toLowerCase(java.util.Locale.ROOT)).append('\n');
            sb.append("\n## Coverage per concept node\n\n");
            for (String line : nodeLines) {
                sb.append("- ").append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /** The clock (tests). */
    public interface Clock {
        long now();
    }

    private final SearchPlanStore store;
    private final SearchPort port;
    private final Sources sources;
    private final Busy busy;
    private final Listener listener;
    private final Executor executor;
    private final Clock clock;
    private final Replanner replanner;
    private volatile SearchPlanCoverage.Caps caps;

    private boolean running;
    private SearchPlanEntry current;
    private boolean stopRequested;
    /** Planned queries that failed in a row since the last one that searched (see {@link Reason#SEARCH_FAILED}). */
    private int failedStreak;
    private String lastFailure = "";
    /** A later research cycle is being planned (the queue is running without a running entry). */
    private boolean replanning;
    /** Guards a planner answer against a stop or a restart meanwhile. */
    private int replanGeneration;
    private final Map<String, String> entryByRequest = new HashMap<String, String>();
    private final Map<String, String> phaseByRequest = new HashMap<String, String>();
    private final List<Runnable> deferred = new ArrayList<Runnable>();

    /** A queue without a replanner runs one research cycle. */
    public SearchPlanQueue(SearchPlanStore store, SearchPort port, Sources sources, Busy busy, Listener listener,
                           Executor executor, Clock clock, SearchPlanCoverage.Caps caps) {
        this(store, port, sources, busy, listener, executor, clock, caps, null);
    }

    public SearchPlanQueue(SearchPlanStore store, SearchPort port, Sources sources, Busy busy, Listener listener,
                           Executor executor, Clock clock, SearchPlanCoverage.Caps caps, Replanner replanner) {
        this.store = store;
        this.port = port;
        this.sources = sources;
        this.busy = busy;
        this.listener = listener;
        this.executor = executor;
        this.clock = clock;
        this.caps = caps;
        this.replanner = replanner;
    }

    public void setCaps(SearchPlanCoverage.Caps caps) {
        if (caps != null) {
            this.caps = caps;
        }
    }

    public SearchPlanStore store() {
        return store;
    }

    /** Whether the queue runs (an entry may be running or the queue may be waiting for another search). */
    public synchronized boolean isRunning() {
        return running;
    }

    /** Whether a planned query runs right now. */
    public synchronized boolean hasRunningEntry() {
        return current != null;
    }

    /** Whether the queue waits for the planner of the next research cycle. */
    public synchronized boolean isReplanning() {
        return replanning;
    }

    /** Whether the request id belongs to a planned query. */
    public synchronized boolean owns(String requestId) {
        return requestId != null && entryByRequest.containsKey(requestId);
    }

    /** The plan entry of a planned query's request ("" otherwise). */
    public synchronized String entryFor(String requestId) {
        String id = requestId == null ? null : entryByRequest.get(requestId);
        return id == null ? "" : id;
    }

    /** The phase of a planned query's request ("" otherwise). */
    public synchronized String phaseFor(String requestId) {
        String phase = requestId == null ? null : phaseByRequest.get(requestId);
        return phase == null ? "" : phase;
    }

    /** "Run search plan": start or continue the queue. */
    public void run() {
        synchronized (this) {
            if (running) {
                return;
            }
            running = true;
            stopRequested = false;
            failedStreak = 0; // a new run gets its own chances
        }
        listener.changed();
        scheduleNext();
    }

    /** "Stop search plan" (and any phase change): cancel the current query, leave it pending, stop the queue. */
    public void stop() {
        boolean cancel;
        synchronized (this) {
            if (!running && current == null) {
                return;
            }
            running = false;
            cancel = current != null;
            stopRequested = cancel;
            if (replanning) {
                replanning = false;
                replanGeneration++; // a planner answer that arrives later is dropped
            }
        }
        if (cancel) {
            port.cancel();
        }
        listener.changed();
    }

    /** Another acquisition ended: the queue may start its next entry. */
    public void kick() {
        synchronized (this) {
            if (current != null || (!running && deferred.isEmpty())) {
                return;
            }
        }
        scheduleNext();
    }

    /**
     * Run {@code work} once the running entry ends, or right away when none runs.
     * @return {@code true} when it was deferred
     */
    public boolean deferUntilEntryEnds(Runnable work) {
        synchronized (this) {
            if (current != null || !deferred.isEmpty()) {
                deferred.add(work);
                return true;
            }
        }
        work.run();
        return false;
    }

    /** A lifecycle event of a planned query ({@code started|progress|completed|failed}). */
    public void onSearchEvent(String requestId, String subKind, String detail, boolean keywordFallback) {
        if (!"completed".equals(subKind) && !"failed".equals(subKind)) {
            return;
        }
        Outcome paused = null;
        synchronized (this) {
            if (current == null || !requestId.equals(current.getRequestId())) {
                return; // a late event of an entry that already ended
            }
            long now = clock.now();
            SearchPlanEntry entry = current;
            try {
                // sources accepted before a stop or a failure still count for coverage and caps
                for (String sourceId : sources.acceptedBy(requestId)) {
                    entry = entry.withSource(sourceId, now);
                }
                if ("completed".equals(subKind)) {
                    if (keywordFallback) {
                        entry = entry.withNote(KEYWORD_FALLBACK, now);
                    }
                    entry = entry.done(now);
                    store.save(entry);
                    failedStreak = 0;
                } else if (stopRequested) {
                    backToPending(entry, now);
                } else {
                    String reason = detail == null || detail.isEmpty() ? "The search failed" : detail;
                    store.save(entry.failedRun(reason, now));
                    failedStreak++;
                    lastFailure = reason;
                    if (failedStreak >= MAX_CONSECUTIVE_FAILURES && running) {
                        // live run 2026-11-09: Google blocked the search browser after 13 queries and the plan
                        // then burnt 30 queries of three cycles in 2.5 minutes, each failing in 5 s, and called the
                        // result "budget". A failing engine is not a research result: pause, keep the rest pending.
                        running = false;
                        SearchPlanRecord record = store.loadRecord();
                        Map<String, SearchPlanEntry> all = store.loadEntries();
                        if (record != null) {
                            SearchPlanCoverage.Caps c = caps;
                            paused = outcome(record, all, c, SearchPlanCoverage.of(record, all.values(), c, sources),
                                    Reason.SEARCH_FAILED, lastFailure);
                        }
                    }
                }
            } catch (IOException | CorruptProjectFileException | RuntimeException failed) {
                System.err.println("[search-plan] entry " + entry.getId() + " not recorded: " + failed.getMessage());
            }
            current = null;
            stopRequested = false;
        }
        listener.changed();
        if (paused != null) {
            finish(paused);
            return;
        }
        scheduleNext();
    }

    /**
     * Release the oldest deferred acquisition, one at a time: the next one waits until this one made acquisition
     * busy and ended again. @return {@code true} when one was released
     */
    private boolean releaseOneDeferred() {
        Runnable work;
        synchronized (this) {
            if (current != null || deferred.isEmpty() || busy.busy()) {
                return false;
            }
            work = deferred.remove(0);
        }
        try {
            work.run();
        } catch (RuntimeException failed) {
            System.err.println("[search-plan] deferred work failed: " + failed.getMessage());
        }
        return true;
    }

    private void backToPending(SearchPlanEntry entry, long now) throws IOException, CorruptProjectFileException {
        if (entry.canRequeue()) {
            store.save(entry.requeued(STOPPED, now));
            return;
        }
        // retries spent: a new pending task takes the stopped one's place in the order
        SearchPlanRecord record = store.loadRecord();
        SearchPlanEntry again = SearchPlanEntry.submit(newId(), entry.getPhase(), entry.getNodeId(),
                entry.getNodeLabel(), entry.getQuery(), entry.getOrigin(), now);
        store.save(again);
        store.save(entry.skipped(STOPPED, now));
        if (record != null) {
            store.saveRecord(replaceInOrder(record, entry.getId(), again.getId()));
        }
    }

    private void scheduleNext() {
        executor.execute(new Runnable() {
            public void run() {
                dispatchNext();
            }
        });
    }

    void dispatchNext() {
        if (releaseOneDeferred()) {
            return; // the released acquisition runs first; its end kicks the queue again
        }
        Outcome outcome = null;
        ConceptQueryPlan.Request nextCycle = null;
        synchronized (this) {
            if (!running || current != null || replanning || busy.busy()) {
                return; // a busy acquisition calls kick() when it ends; a planner answer re-enters here
            }
            try {
                SearchPlanRecord record = store.loadRecord();
                if (record == null) {
                    running = false;
                    return;
                }
                Map<String, SearchPlanEntry> entries = store.loadEntries();
                SearchPlanCoverage.Caps c = caps;
                SearchPlanEntry next = null;
                int perQueryCap = c.getSourcesPerNode();
                boolean capReached = false;
                {
                    List<String> ids = new ArrayList<String>(record.getPriority());
                    ids.addAll(record.getOrder());
                    for (String id : ids) {
                        SearchPlanEntry entry = entries.get(id);
                        if (entry == null || entry.getStatus() != SearchPlanEntry.Status.PENDING) {
                            continue;
                        }
                        int phaseUsed = SearchPlanCoverage.phaseSources(entries.values(), entry.getPhase());
                        if (phaseUsed >= c.getPhaseSources()) {
                            capReached = true; // this entry's phase spent its budget; another phase may not have
                            continue;
                        }
                        Map<String, SearchPlanCoverage.NodeCoverage> coverage =
                                SearchPlanCoverage.of(record, entries.values(), c, sources);
                        SearchPlanCoverage.NodeCoverage node = coverage.get(entry.getNodeId());
                        boolean userOwned = entry.getOrigin() == SearchPlanEntry.Origin.USER
                                || entry.getOrigin() == SearchPlanEntry.Origin.PRIORITY;
                        if (node == null && !userOwned) {
                            skip(entries, entry, SearchPlanMerger.NODE_REMOVED);
                            continue;
                        }
                        if (node != null && !userOwned) {
                            if (node.getState() == SearchPlanCoverage.State.COVERED) {
                                skip(entries, entry, NODE_COVERED);
                                continue;
                            }
                            if (node.getQueriesRun() >= c.queriesUpTo(record.getCycle())) {
                                skip(entries, entry, NODE_BUDGET); // the budget of this cycle
                                continue;
                            }
                        }
                        // Every planned query may add up to the node's source number: the variants are the
                        // node's angles, not spare queries for a base query that came back short.
                        int phaseLeft = c.getPhaseSources() - phaseUsed;
                        perQueryCap = Math.max(1, Math.min(c.getSourcesPerNode(), phaseLeft));
                        next = entry;
                        break;
                    }
                }
                if (next == null) {
                    Map<String, SearchPlanEntry> all = store.loadEntries();
                    Map<String, SearchPlanCoverage.NodeCoverage> coverage =
                            SearchPlanCoverage.of(record, all.values(), c, sources);
                    nextCycle = capReached || replanner == null || record.getCycle() >= c.getCycles() ? null
                            : SearchPlanCycles.nextCycleRequest(record, all.values(), coverage, c);
                    if (nextCycle != null) {
                        replanning = true; // the queue stays running without an entry
                    } else {
                        running = false;
                        outcome = outcome(record, all, c, coverage, capReached ? Reason.PHASE_CAP : null);
                    }
                } else {
                    String requestId = port.search(next.getQuery(), record.getLanguage(), PLANNED_RANKING,
                            visitLimit(perQueryCap), perQueryCap);
                    entryByRequest.put(requestId, next.getId());
                    phaseByRequest.put(requestId, next.getPhase());
                    current = next.started(requestId, clock.now());
                    store.save(current);
                }
            } catch (CorruptProjectFileException corrupt) {
                running = false;
                System.err.println("[search-plan] " + corrupt.getMessage());
            } catch (IOException | RuntimeException failed) {
                running = false;
                System.err.println("[search-plan] the queue stopped: " + failed.getMessage());
            }
        }
        listener.changed();
        if (nextCycle != null) {
            startReplan(nextCycle);
        }
        if (outcome != null) {
            finish(outcome);
        }
    }

    private void finish(Outcome outcome) {
        try {
            store.saveOutcome(outcome.report());
        } catch (IOException failed) {
            System.err.println("[search-plan] outcome not stored: " + failed.getMessage());
        }
        listener.finished(outcome);
    }

    /** Ask the planner for the next cycle; its answer (any thread) is applied on the queue's executor. */
    private void startReplan(final ConceptQueryPlan.Request request) {
        final int generation;
        synchronized (this) {
            generation = replanGeneration;
        }
        final Replanned callback = new Replanned() {
            public void done(final ConceptQueryPlan.Result result) {
                executor.execute(new Runnable() {
                    public void run() {
                        applyReplan(generation, request, result);
                    }
                });
            }
        };
        try {
            replanner.replan(request, callback);
        } catch (RuntimeException failed) {
            System.err.println("[search-plan] re-plan failed: " + failed.getMessage());
            callback.done(null);
        }
    }

    /** The planner's answer: new entries and the next cycle, or the honest end of the plan. */
    void applyReplan(int generation, ConceptQueryPlan.Request request, ConceptQueryPlan.Result result) {
        Outcome outcome = null;
        synchronized (this) {
            if (generation != replanGeneration || !replanning || !running) {
                return; // stopped meanwhile: the answer is dropped, the plan stays where the user left it
            }
            replanning = false;
            try {
                SearchPlanRecord record = store.loadRecord();
                Map<String, SearchPlanEntry> all = store.loadEntries();
                if (record == null) {
                    running = false;
                    return;
                }
                SearchPlanCycles.Result cycle = SearchPlanCycles.apply(record, all.values(), request, result,
                        request.getBlacklist(), ids(), clock.now());
                if (cycle.getAdded().isEmpty()) {
                    running = false;
                    SearchPlanCoverage.Caps c = caps;
                    outcome = outcome(record, all, c, SearchPlanCoverage.of(record, all.values(), c, sources),
                            Reason.PLANNER_UNAVAILABLE);
                } else {
                    store.saveAll(cycle.getAdded());
                    store.saveRecord(cycle.getRecord());
                }
            } catch (IOException | CorruptProjectFileException | RuntimeException failed) {
                running = false;
                System.err.println("[search-plan] the next cycle could not be stored: " + failed.getMessage());
            }
        }
        listener.changed();
        if (outcome != null) {
            finish(outcome);
        } else if (isRunning()) {
            scheduleNext();
        }
    }

    /** How many ranked hits a planned query visits at most: three per source it may accept, 3 to 20. */
    static int visitLimit(int sourceCap) {
        return Math.max(3, Math.min(20, sourceCap * 3));
    }

    private void skip(Map<String, SearchPlanEntry> entries, SearchPlanEntry entry, String reason) throws IOException {
        SearchPlanEntry skipped = entry.skipped(reason, clock.now());
        store.save(skipped);
        entries.put(skipped.getId(), skipped);
    }

    /** The outcome of the plan as it stands (the gate and the Sources view read the uncovered nodes from it). */
    public Outcome outcome() throws CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null) {
            return new Outcome(Reason.COVERED, 0, Collections.<String>emptyList(), 0, 0, 0, 0,
                    Collections.<String>emptyList());
        }
        Map<String, SearchPlanEntry> entries = store.loadEntries();
        SearchPlanCoverage.Caps c = caps;
        boolean capReached = SearchPlanCoverage.phaseSources(entries.values(), record.getPhase())
                >= c.getPhaseSources();
        return outcome(record, entries, c, SearchPlanCoverage.of(record, entries.values(), c, sources),
                capReached ? Reason.PHASE_CAP : null);
    }

    /** @param forced the reason when the caller knows it; {@code null} derives COVERED or BUDGET from coverage */
    private Outcome outcome(SearchPlanRecord record, Map<String, SearchPlanEntry> entries,
                            SearchPlanCoverage.Caps c, Map<String, SearchPlanCoverage.NodeCoverage> coverage,
                            Reason forced) {
        return outcome(record, entries, c, coverage, forced, "");
    }

    private Outcome outcome(SearchPlanRecord record, Map<String, SearchPlanEntry> entries,
                            SearchPlanCoverage.Caps c, Map<String, SearchPlanCoverage.NodeCoverage> coverage,
                            Reason forced, String detail) {
        List<String> uncovered = new ArrayList<String>();
        List<String> lines = new ArrayList<String>();
        java.util.Set<String> allSources = new java.util.LinkedHashSet<String>();
        java.util.Set<String> allDomains = new java.util.HashSet<String>();
        for (SearchPlanRecord.Node node : record.getNodes()) {
            SearchPlanCoverage.NodeCoverage nc = coverage.get(node.getId());
            if (nc == null) {
                continue;
            }
            boolean covered = nc.getState() == SearchPlanCoverage.State.COVERED;
            if (!covered) {
                uncovered.add(node.getLabel());
            }
            allSources.addAll(nc.getSources());
            allDomains.addAll(nc.getDomains());
            lines.add(node.getLabel() + ": " + (covered ? "covered" : "uncovered") + " (" + nc.getSources().size()
                    + (nc.getSources().size() == 1 ? " source" : " sources") + " from " + nc.getDomains().size()
                    + (nc.getDomains().size() == 1 ? " domain" : " domains") + ", " + nc.getQueriesRun()
                    + (nc.getQueriesRun() == 1 ? " query" : " queries") + " run"
                    + (nc.getDomains().isEmpty() ? "" : ": " + String.join(", ", nc.getDomains())) + ")");
        }
        int run = 0;
        for (SearchPlanEntry entry : entries.values()) {
            if (entry.getStatus() == SearchPlanEntry.Status.DONE || entry.getStatus() == SearchPlanEntry.Status.FAILED) {
                run++;
            }
        }
        Reason reason = forced != null ? forced : uncovered.isEmpty() ? Reason.COVERED : Reason.BUDGET;
        return new Outcome(reason, coverage.size(), uncovered, record.getCycle(), run, allSources.size(),
                allDomains.size(), lines, detail);
    }

    // ---- the user's edits (checklist) --------------------------------------------------------------------

    /** Drop a pending entry (it becomes skipped). */
    public synchronized boolean drop(String entryId) throws IOException, CorruptProjectFileException {
        SearchPlanEntry entry = store.loadEntries().get(entryId);
        if (entry == null || entry.getStatus() != SearchPlanEntry.Status.PENDING) {
            return false;
        }
        store.save(entry.skipped("Dropped by the user", clock.now()));
        listener.changed();
        return true;
    }

    /** Edit a pending entry's query: the old entry is skipped, a user entry takes its place. */
    public synchronized String edit(String entryId, String query) throws IOException, CorruptProjectFileException {
        SearchPlanEntry entry = store.loadEntries().get(entryId);
        if (entry == null || entry.getStatus() != SearchPlanEntry.Status.PENDING || blank(query)) {
            return null;
        }
        long now = clock.now();
        SearchPlanEntry edited = SearchPlanEntry.submit(newId(), entry.getPhase(), entry.getNodeId(),
                entry.getNodeLabel(), query, SearchPlanEntry.Origin.USER, now);
        store.save(edited);
        store.save(entry.skipped("Edited by the user", now));
        SearchPlanRecord record = store.loadRecord();
        if (record != null) {
            store.saveRecord(replaceInOrder(record, entryId, edited.getId()));
        }
        listener.changed();
        return edited.getId();
    }

    /** Add a user entry for a node (empty node id: a free entry) at the end of the plan. */
    public synchronized String add(String nodeId, String query) throws IOException, CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null || blank(query)) {
            return null;
        }
        SearchPlanRecord.Node node = nodeId == null ? null : record.node(nodeId);
        SearchPlanEntry added = SearchPlanEntry.submit(newId(), record.getPhase(), node == null ? "" : node.getId(),
                node == null ? "" : node.getLabel(), query, SearchPlanEntry.Origin.USER, clock.now());
        store.save(added);
        List<String> order = new ArrayList<String>(record.getOrder());
        order.add(added.getId());
        store.saveRecord(record.withOrder(order, record.getPriority()));
        listener.changed();
        return added.getId();
    }

    /**
     * Plan PR 15: the Sources assistant's {@code request_research}. {@code node} may name a concept node by id or
     * by label (case-insensitive); an unknown or blank node makes a free entry. @return the new entry id, or
     * {@code null} without a plan or with a blank query
     */
    public synchronized String addForNode(String node, String query) throws IOException, CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null) {
            return null;
        }
        return add(resolveNode(record, node), query);
    }

    private static String resolveNode(SearchPlanRecord record, String node) {
        String wanted = node == null ? "" : node.trim();
        if (wanted.isEmpty()) {
            return null;
        }
        if (record.node(wanted) != null) {
            return wanted;
        }
        for (SearchPlanRecord.Node candidate : record.getNodes()) {
            if (candidate.getLabel().trim().equalsIgnoreCase(wanted)) {
                return candidate.getId();
            }
        }
        return null;
    }

    /** How many entries the Sources block lists at most (the rest is counted). */
    static final int OVERVIEW_ENTRIES = 15;

    /**
     * Plan PR 15: the Sources block of the assistant's context — coverage per node and the bounded plan in run
     * order. {@code ""} without a plan.
     */
    public String overview() throws CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null) {
            return "";
        }
        Map<String, SearchPlanEntry> all = store.loadEntries();
        Map<String, SearchPlanCoverage.NodeCoverage> coverage =
                SearchPlanCoverage.of(record, all.values(), caps, sources);
        StringBuilder sb = new StringBuilder("Search plan coverage per concept node:\n");
        for (SearchPlanRecord.Node node : record.getNodes()) {
            SearchPlanCoverage.NodeCoverage nc = coverage.get(node.getId());
            if (nc != null) {
                sb.append("- ").append(node.getLabel()).append(": ")
                        .append(nc.getState().name().toLowerCase(java.util.Locale.ROOT)).append('\n');
            }
        }
        List<SearchPlanEntry> entries = entriesInOrder();
        int[] counts = new int[SearchPlanEntry.Status.values().length];
        for (SearchPlanEntry entry : entries) {
            counts[entry.getStatus().ordinal()]++;
        }
        sb.append("Search plan entries: ").append(entries.size());
        for (SearchPlanEntry.Status status : SearchPlanEntry.Status.values()) {
            if (counts[status.ordinal()] > 0) {
                sb.append(", ").append(counts[status.ordinal()]).append(' ')
                        .append(status.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        sb.append(isRunning() ? " (the plan is running)\n" : " (the plan is not running)\n");
        int shown = 0;
        for (SearchPlanEntry entry : entries) {
            if (shown == OVERVIEW_ENTRIES) {
                sb.append("- … ").append(entries.size() - shown).append(" more\n");
                break;
            }
            sb.append("- [").append(entry.getStatus().name().toLowerCase(java.util.Locale.ROOT)).append("] ")
                    .append(entry.getQuery());
            if (!entry.getNodeLabel().isEmpty()) {
                sb.append(" (").append(entry.getNodeLabel()).append(')');
            }
            sb.append('\n');
            shown++;
        }
        return sb.toString().trim();
    }

    /** Add a priority entry (the "Request changes" comment at the gate); it runs first. */
    public synchronized String addPriority(String phase, String query) throws IOException, CorruptProjectFileException {
        return addPriority(newId(), phase, query, "");
    }

    /**
     * Add a priority entry under a fixed id (idempotent: an existing id is kept as it is). {@code label} is shown
     * on the checklist, e.g. the comment of "Request changes" at the gate (plan PR 14).
     */
    public synchronized String addPriority(String entryId, String phase, String query, String label)
            throws IOException, CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null || blank(query)) {
            return null;
        }
        if (store.loadEntries().containsKey(entryId)) {
            if (!record.getPriority().contains(entryId)) {
                // a replay after a crash between the task and the record write: link the entry again
                List<String> priority = new ArrayList<String>(record.getPriority());
                priority.add(entryId);
                store.saveRecord(record.withOrder(record.getOrder(), priority));
                listener.changed();
            }
            return entryId;
        }
        SearchPlanEntry added = SearchPlanEntry.submit(entryId, phase == null ? record.getPhase() : phase, "",
                label == null ? "" : label, query, SearchPlanEntry.Origin.PRIORITY, clock.now());
        store.save(added);
        List<String> priority = new ArrayList<String>(record.getPriority());
        priority.add(added.getId());
        store.saveRecord(record.withOrder(record.getOrder(), priority));
        listener.changed();
        return added.getId();
    }

    /** A gate comment as a search query: the first words that fit the query limit. */
    public static String priorityQuery(String comment) {
        String text = comment == null ? "" : comment.replaceAll("\\s+", " ").trim();
        int max = com.aresstack.askai.research.domain.plan.ConceptQueryPlan.MAX_QUERY_LENGTH;
        if (text.length() > max) {
            int cut = text.lastIndexOf(' ', max);
            text = text.substring(0, cut > 0 ? cut : max).trim();
        }
        return text;
    }

    /** Re-run an ended entry: a new pending task for the same node and query right after it. */
    public synchronized String rerun(String entryId) throws IOException, CorruptProjectFileException {
        SearchPlanEntry entry = store.loadEntries().get(entryId);
        SearchPlanRecord record = store.loadRecord();
        if (entry == null || record == null || entry.getStatus() == SearchPlanEntry.Status.PENDING
                || entry.getStatus() == SearchPlanEntry.Status.RUNNING) {
            return null;
        }
        // an explicit re-run is the user's request: coverage and the node budget do not skip it
        SearchPlanEntry again = SearchPlanEntry.submit(newId(), entry.getPhase(), entry.getNodeId(),
                entry.getNodeLabel(), entry.getQuery(), SearchPlanEntry.Origin.USER, clock.now());
        store.save(again);
        List<String> order = new ArrayList<String>(record.getOrder());
        int at = order.indexOf(entryId);
        order.add(at < 0 ? order.size() : at + 1, again.getId());
        store.saveRecord(record.withOrder(order, record.getPriority()));
        listener.changed();
        return again.getId();
    }

    /** "Search everything again". */
    public synchronized int searchAgain() throws IOException, CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null) {
            return 0;
        }
        SearchPlanMerger.Result result = SearchPlanMerger.searchAgain(record, store.loadEntries(), ids(),
                clock.now());
        store.saveAll(result.getChanged());
        store.saveRecord(result.getRecord());
        listener.changed();
        return result.getAdded();
    }

    /** The entries in run order (priority first), for the checklist. */
    public List<SearchPlanEntry> entriesInOrder() throws CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        List<SearchPlanEntry> out = new ArrayList<SearchPlanEntry>();
        if (record == null) {
            return out;
        }
        Map<String, SearchPlanEntry> entries = new LinkedHashMap<String, SearchPlanEntry>(store.loadEntries());
        for (String id : record.getPriority()) {
            SearchPlanEntry e = entries.remove(id);
            if (e != null) {
                out.add(e);
            }
        }
        for (String id : record.getOrder()) {
            SearchPlanEntry e = entries.remove(id);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static SearchPlanRecord replaceInOrder(SearchPlanRecord record, String oldId, String newId) {
        List<String> order = new ArrayList<String>(record.getOrder());
        List<String> priority = new ArrayList<String>(record.getPriority());
        int at = order.indexOf(oldId);
        if (at >= 0) {
            order.add(at + 1, newId);
        } else if ((at = priority.indexOf(oldId)) >= 0) {
            priority.add(at + 1, newId);
        } else {
            order.add(newId);
        }
        return record.withOrder(order, priority);
    }

    static SearchPlanMerger.IdSource ids() {
        return new SearchPlanMerger.IdSource() {
            public String next() {
                return newId();
            }
        };
    }

    static String newId() {
        return "plan-" + java.util.UUID.randomUUID().toString().replace("-", "");
    }

    private static boolean blank(String text) {
        return text == null || text.trim().isEmpty();
    }
}
