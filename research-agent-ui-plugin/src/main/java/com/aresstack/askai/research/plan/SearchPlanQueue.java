package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.transition.CorruptProjectFileException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * The host-side search plan queue (plan PR 14, D5). It runs pending entries through the manual search port, with
 * its own correlation ids, so every planned query gets the readiness judge, the SERP language and the scope gate of
 * the manual search, and the search provider of the Search tab. It sets no agent-turn flag and posts no chat line
 * per query.
 * <ul>
 * <li>The research team (plan T5, {@link Team}): with the team on, up to {@link Team#workers()} entries run at the
 * same time, each in its own worker browser of the runtime; their searches still go one after another through the
 * one web search tool, only the page work runs side by side. With the team off exactly one entry runs at a
 * time.</li>
 * <li>A typed search or a research turn never overlaps the plan: the queue starts an entry only while no other
 * search or research turn runs ({@link Busy}), and a typed search or research-loop turn started while entries run
 * waits until they have ended ({@link #deferUntilEntryEnds}); no new entry starts while one waits.</li>
 * <li>"Stop search plan" ({@link #stop}) cancels the running queries, leaves them pending and stops the queue; "Run
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
    static final String NO_TEAM_BROWSER = "No worker browser for the research team: runs in the chat browser";
    static final String WAITS_FOR_TAB = "Every tab of the research team was taken: runs when a search ends";
    /** The sub-kind of a page worker's message to the orchestrator (plan T4). */
    static final String TEAM_NOTE = "team_note";
    /** The runtime's failure reason when the chat has no worker browser for the research team. */
    static final String NO_WORKER_BROWSER = "NO_WORKER_BROWSER";
    /**
     * The runtime's failure reason when a team query got no tab of the team's page browser within the worker
     * browsers' wait setting: the running searches hold them all. No failing engine: the query waits for a search
     * to end and keeps the team.
     */
    static final String TEAM_TABS_BUSY = "TEAM_TABS_BUSY";

    /** The ranking a planned query asks for until the retrieval benchmark of PR 04 fixes another one. */
    public static final String PLANNED_RANKING = "reranker";

    /** The manual search port as the queue sees it. */
    public interface SearchPort {
        /** Start a search; @return its request id. */
        String search(String query, String language, String ranking, int visitLimit, int sourceCap);

        /**
         * Start a planned query of the research team (plan T5): the search goes through the one web search tool as
         * always, its pages are read by a page worker in its own worker browser. {@code nodeLabel} is the concept
         * node the query belongs to (the page worker's brief). A port without a team runs it like any search.
         *
         * @return its request id
         */
        default String searchAsTeam(String query, String language, String ranking, int visitLimit, int sourceCap,
                                    String nodeLabel) {
            return search(query, language, ranking, visitLimit, sourceCap);
        }

        /** Cancel the running search. */
        void cancel();
    }

    /** The accepted sources of a search request. */
    public interface Sources extends SearchPlanCoverage.Domains {
        List<String> acceptedBy(String requestId);
    }

    /**
     * The research team of the Sources phase (plan T5): how many planned queries may run at the same time, each in
     * its own worker browser. {@code 0} switches the team off: one entry at a time in the chat browser, as before.
     * Read at every dispatch, so a settings change applies to the next entry.
     */
    public interface Team {
        int workers();

        /** The next search starts when fewer pages than this are still open (setting; default the worker count). */
        default int nextSearchBelow() {
            return workers();
        }

        /** No team: one entry at a time. */
        Team OFF = new Team() {
            public int workers() {
                return 0;
            }
        };
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

    /**
     * Plan T4 gap review: at every cycle boundary the orchestrator reads the shared evidence analysis of the material
     * read so far and hands its gaps to the planner of the next cycle. {@link #NONE} plans from the source counts.
     */
    public interface GapReview {
        GapReview NONE = new GapReview() {
            public EvidenceGaps.Gaps atCycleBoundary(Collection<String> activeNodes,
                                                     Collection<SearchPlanEntry> entries) {
                return EvidenceGaps.Gaps.NONE;
            }
        };

        /**
         * The gaps of the analysis of the material read so far; {@link EvidenceGaps.Gaps#NONE} when none is settled.
         * Called outside the queue's lock on its executor: it may wait (bounded) for the shared analysis to catch up.
         */
        EvidenceGaps.Gaps atCycleBoundary(Collection<String> activeNodes, Collection<SearchPlanEntry> entries);
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
    private volatile Team team = Team.OFF;
    /** The mission's source preference: its publisher queries join every later research cycle. */
    private volatile SourcePreference preference = SourcePreference.NONE;
    private volatile GapReview gapReview = GapReview.NONE;
    private volatile Executor reviewer;
    /** The gaps the running re-plan was asked for (the decision of this cycle boundary). */
    private EvidenceGaps.Gaps replanGaps = EvidenceGaps.Gaps.NONE;
    /** The gaps of the latest cycle boundary: a gap node is not covered, whatever its source count says. */
    private volatile EvidenceGaps.Gaps lastGaps = EvidenceGaps.Gaps.NONE;
    /** Set when the runtime had no worker browser for a team query: this run goes on one query at a time. */
    private volatile boolean noWorkerBrowser;
    /**
     * Set when a team query got no free tab while other searches ran: no new search starts until one of them ends
     * and gives its tabs back (live run 4, 2026-11-10).
     */
    private boolean waitForTabs;

    private boolean running;
    /** The entries running right now, by request id, in start order (at most one with the team off). */
    private final Map<String, SearchPlanEntry> runningEntries = new LinkedHashMap<String, SearchPlanEntry>();
    /** The source cap each running entry was started with: the phase budget they may still use. */
    private final Map<String, Integer> runningCaps = new HashMap<String, Integer>();
    /** Plan T4: the still open pages of each running team search, as the runtime last reported them. */
    private final Map<String, Integer> openPages = new HashMap<String, Integer>();
    /** Running entries the user stopped: their end puts them back to pending instead of failing them. */
    private final java.util.Set<String> stopping = new java.util.HashSet<String>();
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

    /** The mission's source preference for the later research cycles; {@code null} = none. */
    public void setSourcePreference(SourcePreference preference) {
        this.preference = preference == null ? SourcePreference.NONE : preference;
    }

    /** The research team (plan T5); {@link Team#OFF} keeps one entry at a time. */
    public void setTeam(Team team) {
        this.team = team == null ? Team.OFF : team;
    }

    /** Plan T4: the gap review of the shared evidence analysis at every cycle boundary. */
    public void setGapReview(GapReview review) {
        setGapReview(review, null);
    }

    /**
     * @param reviewer where the review runs: it may wait for the analysis and must never block the queue's executor
     *                 (the UI thread in the app); {@code null} runs it on a thread of its own. The result goes back to
     *                 the queue's executor.
     */
    public void setGapReview(GapReview review, Executor reviewer) {
        this.gapReview = review == null ? GapReview.NONE : review;
        this.reviewer = reviewer;
    }

    /** How many entries may run at once: the team's workers, at least one. */
    private int slots() {
        return teamOn() ? Math.max(1, team.workers()) : 1;
    }

    /**
     * Plan T4: the research team reads the hits of the running search side by side; the next search starts only
     * when fewer than the team's threshold of pages are still open, so the searches build on each other and the
     * workers never run dry at the end of one. A search whose hits are not known yet counts as full.
     */
    private boolean teamStillReading() {
        if (!teamOn() || runningEntries.isEmpty()) {
            return false;
        }
        if (waitForTabs) {
            return true; // the running searches hold every tab: the next starts when one of them ends
        }
        int open = 0;
        for (String requestId : runningEntries.keySet()) {
            Integer pages = openPages.get(requestId);
            if (pages == null) {
                return true; // its search is still running or its hits are not ranked yet
            }
            open += pages;
        }
        return open >= Math.max(1, team.nextSearchBelow());
    }

    /** Plan T4: the runtime's count of the still open pages of a running team search. */
    public void onOpenPages(String requestId, int open) {
        synchronized (this) {
            if (requestId == null || !runningEntries.containsKey(requestId)) {
                return;
            }
            openPages.put(requestId, Math.max(0, open));
        }
        scheduleNext();
    }

    /**
     * Plan T4: a page worker read a hit and suggested a new search. One the Weidezaun and the blacklist let through
     * becomes a WORKER entry of the same node that runs before every planned entry, unless the node's query budget
     * of this cycle is spent or the query is already in the plan; every other outcome stays visible on the entry
     * that found it.
     */
    public void onTeamQuery(String requestId, String query, String rejected) {
        synchronized (this) {
            SearchPlanEntry from = requestId == null ? null : runningEntries.get(requestId);
            if (from == null || blank(query)) {
                return;
            }
            String q = query.replaceAll("\\s+", " ").trim();
            try {
                String outcome;
                if (rejected != null && !rejected.trim().isEmpty()) {
                    outcome = "search \"" + q + "\" dropped: " + rejected.trim();
                } else {
                    outcome = addWorkerEntry(from, q);
                }
                SearchPlanEntry noted = from.withTeamNote(outcome, clock.now());
                if (noted != from) {
                    store.save(noted);
                    runningEntries.put(requestId, noted);
                }
                System.err.println("[search-plan] " + outcome);
            } catch (IOException | CorruptProjectFileException | RuntimeException failed) {
                System.err.println("[search-plan] worker query \"" + q + "\" not recorded: " + failed.getMessage());
                return;
            }
        }
        listener.changed();
        scheduleNext();
    }

    private String addWorkerEntry(SearchPlanEntry from, String query) throws IOException, CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null) {
            return "search \"" + query + "\" dropped: no plan";
        }
        if (query.length() < ConceptQueryPlan.MIN_QUERY_LENGTH || query.length() > ConceptQueryPlan.MAX_QUERY_LENGTH) {
            return "search \"" + query + "\" dropped: not a usable query length";
        }
        Map<String, SearchPlanEntry> entries = store.loadEntries();
        int ofNode = 0;
        for (SearchPlanEntry entry : entries.values()) {
            if (entry.getQuery().equalsIgnoreCase(query)) {
                return "search \"" + query + "\" dropped: already in the plan";
            }
            if (from.getNodeId().equals(entry.getNodeId()) && entry.getStatus() != SearchPlanEntry.Status.SKIPPED) {
                ofNode++;
            }
        }
        if (!from.getNodeId().isEmpty() && ofNode >= caps.queriesUpTo(record.getCycle())) {
            return "search \"" + query + "\" dropped: the node's query budget of this cycle is spent";
        }
        SearchPlanEntry added = SearchPlanEntry.submit(newId(), from.getPhase(), from.getNodeId(), from.getNodeLabel(),
                query, SearchPlanEntry.Origin.WORKER, clock.now());
        store.save(added);
        // WORKER entries run before every planned entry, in the order the workers found them
        List<String> order = new ArrayList<String>(record.getOrder());
        int at = order.size();
        for (int i = 0; i < order.size(); i++) {
            SearchPlanEntry entry = entries.get(order.get(i));
            if (entry != null && entry.getStatus() == SearchPlanEntry.Status.PENDING
                    && entry.getOrigin() != SearchPlanEntry.Origin.WORKER) {
                at = i;
                break;
            }
        }
        order.add(at, added.getId());
        store.saveRecord(record.withOrder(order, record.getPriority()));
        return "search \"" + query + "\" added for \"" + from.getNodeLabel() + "\"";
    }

    /** Whether the planned queries run as team work (each in its own worker browser). */
    private boolean teamOn() {
        return !noWorkerBrowser && team.workers() > 0;
    }

    /**
     * Plan T4: the runtime's report that a page worker of a running query wrote to the orchestrator. The note is
     * kept on the entry, so the next research cycle's planner reads what the team found and missed.
     */
    private void recordTeamNote(String requestId, String note) {
        synchronized (this) {
            SearchPlanEntry entry = requestId == null ? null : runningEntries.get(requestId);
            if (entry == null || note == null || note.trim().isEmpty()) {
                return;
            }
            try {
                SearchPlanEntry noted = entry.withTeamNote(note, clock.now());
                if (noted != entry) {
                    store.save(noted);
                    runningEntries.put(requestId, noted);
                }
            } catch (IOException | RuntimeException failed) {
                System.err.println("[search-plan] team note of " + entry.getId() + " not recorded: "
                        + failed.getMessage());
                return;
            }
        }
        listener.changed();
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
        return !runningEntries.isEmpty();
    }

    /** How many planned queries run right now (the research team runs several at once). */
    public synchronized int runningEntryCount() {
        return runningEntries.size();
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
            failedStreak = 0; // a new run gets its own chances
            noWorkerBrowser = false; // and tries the research team again
            waitForTabs = false;
        }
        listener.changed();
        scheduleNext();
    }

    /** "Stop search plan" (and any phase change): cancel the running queries, leave them pending, stop the queue. */
    public void stop() {
        boolean cancel;
        synchronized (this) {
            if (!running && runningEntries.isEmpty()) {
                return;
            }
            running = false;
            cancel = !runningEntries.isEmpty();
            stopping.addAll(runningEntries.keySet());
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
            if ((!running && deferred.isEmpty()) || runningEntries.size() >= slots() || teamStillReading()) {
                return;
            }
        }
        scheduleNext();
    }

    /**
     * Run {@code work} once the running entries have ended, or right away when none runs.
     * @return {@code true} when it was deferred
     */
    public boolean deferUntilEntryEnds(Runnable work) {
        synchronized (this) {
            if (!runningEntries.isEmpty() || !deferred.isEmpty()) {
                deferred.add(work);
                return true;
            }
        }
        work.run();
        return false;
    }

    /** A lifecycle event of a planned query ({@code started|progress|completed|failed}). */
    public void onSearchEvent(String requestId, String subKind, String detail, boolean keywordFallback) {
        if (TEAM_NOTE.equals(subKind)) {
            recordTeamNote(requestId, detail);
            return;
        }
        if (!"completed".equals(subKind) && !"failed".equals(subKind)) {
            return;
        }
        Outcome paused = null;
        synchronized (this) {
            SearchPlanEntry ended = requestId == null ? null : runningEntries.get(requestId);
            if (ended == null) {
                return; // a late event of an entry that already ended
            }
            long now = clock.now();
            SearchPlanEntry entry = ended;
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
                } else if (stopping.contains(requestId)) {
                    backToPending(entry, STOPPED, now);
                } else if (TEAM_TABS_BUSY.equals(detail) && runningEntries.size() > 1) {
                    // the other running searches hold every tab of the team: not a failing engine. The query goes
                    // back to pending and the next search starts when one of them ends and gives its tabs back.
                    System.err.println("[search-plan] every tab of the research team is taken: \""
                            + entry.getQuery() + "\" runs when a search ends");
                    waitForTabs = true;
                    backToPending(entry, WAITS_FOR_TAB, now);
                } else if (NO_WORKER_BROWSER.equals(detail) || TEAM_TABS_BUSY.equals(detail)) {
                    // the chat has no worker browser, or no tab came free although no other search holds one:
                    // the plan goes on one query at a time in the chat browser instead of failing every query
                    System.err.println("[search-plan] no worker browser for the research team: one query at a time");
                    noWorkerBrowser = true;
                    backToPending(entry, NO_TEAM_BROWSER, now);
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
            // a search that read with tabs of the team gives them back when it ends; one that got none frees nothing
            boolean gotNoTab = "failed".equals(subKind)
                    && (TEAM_TABS_BUSY.equals(detail) || NO_WORKER_BROWSER.equals(detail));
            runningEntries.remove(requestId);
            runningCaps.remove(requestId);
            openPages.remove(requestId);
            stopping.remove(requestId);
            if (!gotNoTab || runningEntries.isEmpty()) {
                waitForTabs = false; // the tabs of the ended search are free for the next one
            }
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
            if (!runningEntries.isEmpty() || deferred.isEmpty() || busy.busy()) {
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

    private void backToPending(SearchPlanEntry entry, String why, long now)
            throws IOException, CorruptProjectFileException {
        if (entry.canRequeue()) {
            store.save(entry.requeued(why, now));
            return;
        }
        // retries spent: a new pending task takes the stopped one's place in the order
        SearchPlanRecord record = store.loadRecord();
        SearchPlanEntry again = SearchPlanEntry.submit(newId(), entry.getPhase(), entry.getNodeId(),
                entry.getNodeLabel(), entry.getQuery(), entry.getOrigin(), now);
        store.save(again);
        store.save(entry.skipped(why, now));
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
        Boolean boundary = null;
        int boundaryGeneration = 0;
        boolean fillMore = false;
        synchronized (this) {
            if (!running || replanning || busy.busy() || !deferred.isEmpty()
                    || runningEntries.size() >= slots() || teamStillReading()) {
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
                        int phaseUsed = SearchPlanCoverage.phaseSources(entries.values(), entry.getPhase())
                                + reservedSources(entry.getPhase());
                        if (phaseUsed >= c.phaseSourcesFor(record)) {
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
                            // running entries are saved as RUNNING and already count in getQueriesRun()
                            if (node.getQueriesRun() >= c.queriesUpTo(record.getCycle())) {
                                skip(entries, entry, NODE_BUDGET); // the budget of this cycle
                                continue;
                            }
                        }
                        // Every planned query may add up to the node's source number: the variants are the
                        // node's angles, not spare queries for a base query that came back short.
                        int phaseLeft = c.phaseSourcesFor(record) - phaseUsed;
                        perQueryCap = Math.max(1, Math.min(c.getSourcesPerNode(), phaseLeft));
                        next = entry;
                        break;
                    }
                }
                if (next == null && !runningEntries.isEmpty()) {
                    // nothing more to start now: the running entries end first, then the plan decides how it goes on
                } else if (next == null) {
                    // the cycle boundary: the gap review may wait for the shared analysis, so it runs outside the
                    // lock; the queue stays running without an entry meanwhile
                    replanning = true;
                    boundary = Boolean.valueOf(capReached);
                    boundaryGeneration = replanGeneration;
                } else {
                    String requestId = teamOn()
                            ? port.searchAsTeam(next.getQuery(), record.getLanguage(), PLANNED_RANKING,
                                    visitLimit(perQueryCap), perQueryCap, next.getNodeLabel())
                            : port.search(next.getQuery(), record.getLanguage(), PLANNED_RANKING,
                                    visitLimit(perQueryCap), perQueryCap);
                    entryByRequest.put(requestId, next.getId());
                    phaseByRequest.put(requestId, next.getPhase());
                    SearchPlanEntry started = next.started(requestId, clock.now());
                    runningEntries.put(requestId, started);
                    runningCaps.put(requestId, perQueryCap);
                    store.save(started);
                    fillMore = runningEntries.size() < slots();
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
        if (boundary != null) {
            cycleBoundary(boundaryGeneration, boundary.booleanValue());
        }
        if (outcome != null) {
            finish(outcome);
        }
        if (fillMore) {
            scheduleNext(); // the team has a free worker: start the next planned query beside the running ones
        }
    }

    /**
     * The end of a research cycle: the gap review reads the shared evidence analysis off the queue's executor (it may
     * wait until the analysis holds the material read so far), then the queue plans the next cycle from the coverage and the gaps, or ends
     * the plan with its honest outcome. A stop meanwhile drops the boundary.
     */
    private void cycleBoundary(int generation, boolean capReached) {
        SearchPlanRecord record;
        Map<String, SearchPlanEntry> all;
        synchronized (this) {
            if (generation != replanGeneration || !replanning || !running) {
                return;
            }
            try {
                record = store.loadRecord();
                all = store.loadEntries();
            } catch (CorruptProjectFileException corrupt) {
                running = false;
                replanning = false;
                System.err.println("[search-plan] " + corrupt.getMessage());
                return;
            }
            if (record == null) {
                running = false;
                replanning = false;
                return;
            }
        }
        if (gapReview == GapReview.NONE) {
            planNextCycle(generation, capReached, EvidenceGaps.Gaps.NONE);
            return;
        }
        final SearchPlanRecord reviewed = record;
        final Map<String, SearchPlanEntry> reviewedEntries = all;
        final int gen = generation;
        final boolean cap = capReached;
        Runnable review = new Runnable() {
            public void run() {
                final EvidenceGaps.Gaps gaps = gapReview(reviewed, reviewedEntries.values());
                executor.execute(new Runnable() {
                    public void run() {
                        planNextCycle(gen, cap, gaps);
                    }
                });
            }
        };
        Executor runOn = reviewer;
        if (runOn != null) {
            runOn.execute(review);
        } else {
            Thread thread = new Thread(review, "search-plan-gap-review");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** The second half of a cycle boundary, on the queue's executor: the next cycle or the plan's outcome. */
    private void planNextCycle(int generation, boolean capReached, EvidenceGaps.Gaps gaps) {
        SearchPlanRecord record;
        Map<String, SearchPlanEntry> all;
        ConceptQueryPlan.Request nextCycle = null;
        Outcome outcome = null;
        synchronized (this) {
            if (generation != replanGeneration || !replanning || !running) {
                return; // stopped while the review waited
            }
            try {
                record = store.loadRecord();
                all = store.loadEntries();
                SearchPlanCoverage.Caps c = caps;
                Map<String, SearchPlanCoverage.NodeCoverage> coverage =
                        SearchPlanCoverage.of(record, all.values(), c, sources);
                nextCycle = capReached || replanner == null || record.getCycle() >= c.getCycles() ? null
                        : SearchPlanCycles.nextCycleRequest(record, all.values(), coverage, c, gaps);
                replanGaps = gaps;
                if (nextCycle == null) {
                    replanning = false;
                    running = false;
                    outcome = outcome(record, all, c, coverage, capReached ? Reason.PHASE_CAP : null);
                }
            } catch (CorruptProjectFileException | RuntimeException failed) {
                replanning = false;
                running = false;
                System.err.println("[search-plan] the cycle boundary failed: " + failed.getMessage());
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

    /** The sources the running entries of {@code phase} may still add (their caps): reserved, not yet counted. */
    private int reservedSources(String phase) {
        int reserved = 0;
        for (Map.Entry<String, SearchPlanEntry> active : runningEntries.entrySet()) {
            if (active.getValue().getPhase().equals(phase)) {
                Integer cap = runningCaps.get(active.getKey());
                reserved += cap == null ? 0 : cap;
            }
        }
        return reserved;
    }

    private void finish(Outcome outcome) {
        try {
            store.saveOutcome(outcome.report());
        } catch (IOException failed) {
            System.err.println("[search-plan] outcome not stored: " + failed.getMessage());
        }
        listener.finished(outcome);
    }

    /** The gaps of this cycle boundary, logged as the boundary's decision; a failing review plans without gaps. */
    private EvidenceGaps.Gaps gapReview(SearchPlanRecord record, Collection<SearchPlanEntry> entries) {
        List<String> active = new ArrayList<String>();
        for (SearchPlanRecord.Node node : record.getNodes()) {
            if (node.isActive()) {
                active.add(node.getId());
            }
        }
        EvidenceGaps.Gaps gaps;
        try {
            gaps = gapReview.atCycleBoundary(active, entries);
        } catch (RuntimeException failed) {
            System.err.println("[search-plan] gap review failed: " + failed.getMessage());
            return EvidenceGaps.Gaps.NONE;
        }
        if (gaps == null || gaps.getAnalysisId().isEmpty()) {
            lastGaps = EvidenceGaps.Gaps.NONE;
            System.err.println("[search-plan] gap review cycle " + record.getCycle()
                    + ": no settled analysis, the next cycle plans from the source counts");
            return EvidenceGaps.Gaps.NONE;
        }
        StringBuilder line = new StringBuilder("[search-plan] gap review cycle ").append(record.getCycle())
                .append(" analysis ").append(gaps.getAnalysisId()).append(':');
        for (Map.Entry<String, List<String>> gap : gaps.getEvidenceByNode().entrySet()) {
            SearchPlanRecord.Node node = record.node(gap.getKey());
            line.append(" [").append(node == null ? gap.getKey() : node.getLabel()).append(": ")
                    .append(gap.getValue().isEmpty() ? "" : gap.getValue().get(0)).append(']');
        }
        line.append(" covered=").append(gaps.getCoveredNodes().size());
        for (String topic : gaps.getNewTopics()) {
            line.append(" [new topic: ").append(topic).append(']');
        }
        for (String dropped : gaps.getDropped()) {
            line.append(" [").append(dropped).append(']');
        }
        System.err.println(line);
        lastGaps = gaps;
        return gaps;
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
                        request.getBlacklist(), ids(), clock.now(), preference);
                if (cycle.getAdded().isEmpty()) {
                    running = false;
                    SearchPlanCoverage.Caps c = caps;
                    outcome = outcome(record, all, c, SearchPlanCoverage.of(record, all.values(), c, sources),
                            Reason.PLANNER_UNAVAILABLE);
                } else {
                    store.saveAll(noteGaps(cycle.getAdded()));
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

    /**
     * The cycle's new entries, each one planned for a gap carrying that gap and the analysis on its note, so the
     * decision stays traceable: which analysis, which gap, which query.
     */
    private List<SearchPlanEntry> noteGaps(List<SearchPlanEntry> added) {
        EvidenceGaps.Gaps gaps = replanGaps;
        List<SearchPlanEntry> noted = new ArrayList<SearchPlanEntry>();
        long now = clock.now();
        for (SearchPlanEntry entry : added) {
            List<String> gap = gaps.getEvidenceByNode().get(entry.getNodeId());
            if (gap == null || gap.isEmpty()) {
                noted.add(entry);
                continue;
            }
            String note = "gap " + gap.get(0) + " (analysis " + gaps.getAnalysisId() + ")";
            System.err.println("[search-plan] query \"" + entry.getQuery() + "\" for \"" + entry.getNodeLabel()
                    + "\": " + note);
            noted.add(entry.withNote(note, now));
        }
        return noted;
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
                >= c.phaseSourcesFor(record);
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
            List<String> gap = lastGaps.getEvidenceByNode().get(node.getId());
            boolean covered = nc.getState() == SearchPlanCoverage.State.COVERED && gap == null;
            if (!covered) {
                uncovered.add(node.getLabel());
            }
            allSources.addAll(nc.getSources());
            allDomains.addAll(nc.getDomains());
            lines.add(node.getLabel() + ": " + (covered ? "covered" : "uncovered") + " (" + nc.getSources().size()
                    + (nc.getSources().size() == 1 ? " source" : " sources") + " from " + nc.getDomains().size()
                    + (nc.getDomains().size() == 1 ? " domain" : " domains") + ", " + nc.getQueriesRun()
                    + (nc.getQueriesRun() == 1 ? " query" : " queries") + " run"
                    + (nc.getDomains().isEmpty() ? "" : ": " + String.join(", ", nc.getDomains())) + ")"
                    + (gap == null || gap.isEmpty() ? ""
                    : "; analysis " + lastGaps.getAnalysisId() + ": " + gap.get(0)));
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
