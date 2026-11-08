package com.aresstack.askai.research.plan;

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
 * <li>Priority entries run first, then the plan order. Entries of covered nodes and of nodes whose query budget is
 * spent are skipped; the phase cap stops the queue. Planned queries keep the recorded working language.</li>
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

        /** The queue ran out of entries or hit the phase cap; {@code outcome} names the uncovered nodes. */
        void finished(Outcome outcome);
    }

    /** The end of a run. */
    public static final class Outcome {
        private final int nodes;
        private final List<String> uncovered;
        private final boolean phaseCapReached;

        Outcome(int nodes, List<String> uncovered, boolean phaseCapReached) {
            this.nodes = nodes;
            this.uncovered = Collections.unmodifiableList(uncovered);
            this.phaseCapReached = phaseCapReached;
        }

        public int getNodes() {
            return nodes;
        }

        /** The labels of the uncovered nodes, exhausted or not, in concept order. */
        public List<String> getUncovered() {
            return uncovered;
        }

        public boolean isPhaseCapReached() {
            return phaseCapReached;
        }

        /** The one outcome line (English). */
        public String text() {
            StringBuilder sb = new StringBuilder("Search plan finished: ")
                    .append(nodes - uncovered.size()).append(" of ").append(nodes).append(" nodes covered.");
            if (phaseCapReached) {
                sb.append(" The source limit of the Sources phase is reached.");
            }
            if (!uncovered.isEmpty()) {
                sb.append(" Uncovered: ");
                for (int i = 0; i < uncovered.size(); i++) {
                    sb.append(i > 0 ? ", " : "").append(uncovered.get(i));
                }
                sb.append('.');
            }
            return sb.append(" Finish Sources when you are ready.").toString();
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
    private volatile SearchPlanCoverage.Caps caps;

    private boolean running;
    private SearchPlanEntry current;
    private boolean stopRequested;
    private final Map<String, String> entryByRequest = new HashMap<String, String>();
    private final Map<String, String> phaseByRequest = new HashMap<String, String>();
    private final List<Runnable> deferred = new ArrayList<Runnable>();

    public SearchPlanQueue(SearchPlanStore store, SearchPort port, Sources sources, Busy busy, Listener listener,
                           Executor executor, Clock clock, SearchPlanCoverage.Caps caps) {
        this.store = store;
        this.port = port;
        this.sources = sources;
        this.busy = busy;
        this.listener = listener;
        this.executor = executor;
        this.clock = clock;
        this.caps = caps;
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
                } else if (stopRequested) {
                    backToPending(entry, now);
                } else {
                    store.save(entry.failedRun(detail == null || detail.isEmpty() ? "The search failed" : detail,
                            now));
                }
            } catch (IOException | CorruptProjectFileException | RuntimeException failed) {
                System.err.println("[search-plan] entry " + entry.getId() + " not recorded: " + failed.getMessage());
            }
            current = null;
            stopRequested = false;
        }
        listener.changed();
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
        synchronized (this) {
            if (!running || current != null || busy.busy()) {
                return; // a busy acquisition calls kick() when it ends
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
                boolean capReached = SearchPlanCoverage.phaseSources(entries.values()) >= c.getPhaseSources();
                if (!capReached) {
                    List<String> ids = new ArrayList<String>(record.getPriority());
                    ids.addAll(record.getOrder());
                    for (String id : ids) {
                        SearchPlanEntry entry = entries.get(id);
                        if (entry == null || entry.getStatus() != SearchPlanEntry.Status.PENDING) {
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
                            if (node.getQueriesRun() >= c.getQueriesPerNode()) {
                                skip(entries, entry, NODE_BUDGET);
                                continue;
                            }
                        }
                        int have = node == null ? 0 : node.getSources().size();
                        int phaseLeft = c.getPhaseSources() - SearchPlanCoverage.phaseSources(entries.values());
                        perQueryCap = Math.max(1, Math.min(c.getSourcesPerNode() - have, phaseLeft));
                        next = entry;
                        break;
                    }
                }
                if (next == null) {
                    running = false;
                    outcome = outcome(record, store.loadEntries(), c, capReached);
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
        if (outcome != null) {
            listener.finished(outcome);
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

    /** The outcome of the plan as it stands. */
    public Outcome outcome() throws CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null) {
            return new Outcome(0, Collections.<String>emptyList(), false);
        }
        Map<String, SearchPlanEntry> entries = store.loadEntries();
        return outcome(record, entries, caps,
                SearchPlanCoverage.phaseSources(entries.values()) >= caps.getPhaseSources());
    }

    private Outcome outcome(SearchPlanRecord record, Map<String, SearchPlanEntry> entries,
                            SearchPlanCoverage.Caps c, boolean capReached) {
        Map<String, SearchPlanCoverage.NodeCoverage> coverage =
                SearchPlanCoverage.of(record, entries.values(), c, sources);
        List<String> uncovered = new ArrayList<String>();
        for (SearchPlanRecord.Node node : record.getNodes()) {
            SearchPlanCoverage.NodeCoverage nc = coverage.get(node.getId());
            if (nc != null && nc.getState() != SearchPlanCoverage.State.COVERED) {
                uncovered.add(node.getLabel());
            }
        }
        return new Outcome(coverage.size(), uncovered, capReached);
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

    /** Add a priority entry (the "Request changes" comment at the gate); it runs first. */
    public synchronized String addPriority(String phase, String query) throws IOException, CorruptProjectFileException {
        SearchPlanRecord record = store.loadRecord();
        if (record == null || blank(query)) {
            return null;
        }
        SearchPlanEntry added = SearchPlanEntry.submit(newId(), phase == null ? record.getPhase() : phase, "", "",
                query, SearchPlanEntry.Origin.PRIORITY, clock.now());
        store.save(added);
        List<String> priority = new ArrayList<String>(record.getPriority());
        priority.add(added.getId());
        store.saveRecord(record.withOrder(record.getOrder(), priority));
        listener.changed();
        return added.getId();
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
