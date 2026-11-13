package com.aresstack.askai.research.plan;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Coverage and caps of the search plan (plan PR 14, D5). A node is <em>covered</em> once it has
 * {@link Caps#getSourcesPerNode()} accepted sources from at least {@link #MIN_DOMAINS} domains <em>and</em> none of
 * its planned queries is still pending: the planner's variants are the angles of the node (measurement, safety,
 * law, …), and a node whose base query alone met the source minimum is not researched — the live run of
 * 2026-11-09 declared all five nodes covered after six of nineteen queries that way. A node whose query budget is
 * spent, or that has no pending entry left, without the minimum is <em>exhausted</em>: no further automatic query
 * runs for it in this cycle, and it stays uncovered until a later cycle covers it. Any other node is <em>open</em>.
 */
public final class SearchPlanCoverage {

    /** The distinct domains a covered node needs. */
    public static final int MIN_DOMAINS = 2;

    /** The caps per query, node, research cycle and phase. */
    public static final class Caps {
        private final int sourcesPerNode;
        private final int queriesPerNode;
        private final int phaseSources;
        private final int cycles;
        private final int preferenceQueries;

        /** One research cycle: the plan ends when its queries ran. */
        public Caps(int sourcesPerNode, int queriesPerNode, int phaseSources) {
            this(sourcesPerNode, queriesPerNode, phaseSources, 1);
        }

        /**
         * @param queriesPerNode the query budget of a node PER research cycle
         * @param phaseSources   the accepted sources a phase may collect through the plan; 0 = automatic, what the
         *                       node budgets allow ({@link #phaseSourcesFor})
         * @param cycles         how many research cycles the plan may run: after each cycle the still uncovered
         *                       nodes are planned again from another angle, until they are covered or the cycles
         *                       are spent
         */
        public Caps(int sourcesPerNode, int queriesPerNode, int phaseSources, int cycles) {
            this(sourcesPerNode, queriesPerNode, phaseSources, cycles, 0);
        }

        private Caps(int sourcesPerNode, int queriesPerNode, int phaseSources, int cycles, int preferenceQueries) {
            this.preferenceQueries = Math.max(0, preferenceQueries);
            this.sourcesPerNode = Math.max(1, sourcesPerNode);
            this.queriesPerNode = Math.max(1, queriesPerNode);
            this.phaseSources = Math.max(0, phaseSources);
            this.cycles = Math.max(1, cycles);
        }

        public int getCycles() {
            return cycles;
        }

        /** The query budget of a node up to and including the given cycle. */
        public int queriesUpTo(int cycle) {
            return (queriesPerNode + preferenceQueries) * Math.max(1, cycle);
        }

        /**
         * The same caps with the mission's publisher queries per node and cycle ({@link SourcePreference}) on top of
         * the planner's budget, so a preferred-source query is never skipped as over budget.
         */
        public Caps withPreferenceQueries(int perNodeAndCycle) {
            return new Caps(sourcesPerNode, queriesPerNode, phaseSources, cycles, perNodeAndCycle);
        }

        /** The mission's publisher queries per node and cycle on top of {@link #getQueriesPerNode()}. */
        public int getPreferenceQueries() {
            return preferenceQueries;
        }

        public int getSourcesPerNode() {
            return sourcesPerNode;
        }

        public int getQueriesPerNode() {
            return queriesPerNode;
        }

        /** The configured "Sources for the Sources phase"; 0 = automatic ({@link #phaseSourcesFor}). */
        public int getPhaseSources() {
            return phaseSources;
        }

        /**
         * The source limit of a phase of this plan: the configured number, or with 0 (automatic) what the node
         * budgets allow, every active node of the record with "Sources per node" for each of its queries in every
         * research cycle ({@link #queriesUpTo}). A fixed number below that ends the first cycle before the gaps get
         * theirs: live run 4 (2026-11-12) spent 60 sources on four of five nodes in cycle 1, the fifth stayed
         * uncovered and no second cycle could research it.
         */
        public int phaseSourcesFor(SearchPlanRecord record) {
            if (phaseSources > 0) {
                return phaseSources;
            }
            int nodes = 0;
            if (record != null) {
                for (SearchPlanRecord.Node node : record.getNodes()) {
                    if (node.isActive()) {
                        nodes++;
                    }
                }
            }
            long budget = (long) Math.max(1, nodes) * sourcesPerNode * queriesUpTo(cycles);
            return (int) Math.min(Integer.MAX_VALUE, budget);
        }
    }

    /** The coverage state of a node. */
    public enum State { COVERED, EXHAUSTED, OPEN }

    /** Resolves the domain of an accepted source ("" when unknown). */
    public interface Domains {
        String of(String sourceId);
    }

    /** What one node has so far. */
    public static final class NodeCoverage {
        private final String nodeId;
        private final State state;
        private final Set<String> sources;
        private final Set<String> domains;
        private final int queriesRun;

        NodeCoverage(String nodeId, State state, Set<String> sources, Set<String> domains, int queriesRun) {
            this.nodeId = nodeId;
            this.state = state;
            this.sources = Collections.unmodifiableSet(sources);
            this.domains = Collections.unmodifiableSet(domains);
            this.queriesRun = queriesRun;
        }

        public String getNodeId() {
            return nodeId;
        }

        public State getState() {
            return state;
        }

        public Set<String> getSources() {
            return sources;
        }

        public Set<String> getDomains() {
            return domains;
        }

        public int getQueriesRun() {
            return queriesRun;
        }
    }

    private SearchPlanCoverage() {
    }

    /** The coverage of every active node of the record, in concept order. */
    public static Map<String, NodeCoverage> of(SearchPlanRecord record, Collection<SearchPlanEntry> entries,
                                               Caps caps, Domains domains) {
        Map<String, NodeCoverage> out = new LinkedHashMap<String, NodeCoverage>();
        for (SearchPlanRecord.Node node : record.getNodes()) {
            if (!node.isActive()) {
                continue;
            }
            Set<String> sources = new LinkedHashSet<String>();
            Set<String> hosts = new HashSet<String>();
            int run = 0;
            boolean pending = false;
            for (SearchPlanEntry entry : entries) {
                if (!node.getId().equals(entry.getNodeId())) {
                    continue;
                }
                SearchPlanEntry.Status status = entry.getStatus();
                // the planner's queries only: worker searches have their own budget (SearchPlanQueue.Team)
                if (entry.getOrigin() != SearchPlanEntry.Origin.WORKER
                        && (status == SearchPlanEntry.Status.DONE || status == SearchPlanEntry.Status.FAILED
                        || status == SearchPlanEntry.Status.RUNNING)) {
                    run++;
                }
                if (status == SearchPlanEntry.Status.PENDING || status == SearchPlanEntry.Status.RUNNING) {
                    pending = true;
                }
                for (String sourceId : entry.getAcceptedSourceIds()) {
                    if (sources.add(sourceId)) {
                        String domain = domains.of(sourceId);
                        if (domain != null && !domain.trim().isEmpty()) {
                            hosts.add(domain.trim().toLowerCase(java.util.Locale.ROOT));
                        }
                    }
                }
            }
            State state;
            if (sources.size() >= caps.getSourcesPerNode() && hosts.size() >= MIN_DOMAINS && !pending) {
                state = State.COVERED;
            } else if (run >= caps.queriesUpTo(caps.getCycles()) || !pending) {
                // exhausted for now: the queue plans another cycle for it while cycles are left
                state = State.EXHAUSTED;
            } else {
                state = State.OPEN;
            }
            out.put(node.getId(), new NodeCoverage(node.getId(), state, sources, hosts, run));
        }
        return out;
    }

    /** The accepted sources of every entry of the plan, counted once. */
    public static int phaseSources(Collection<SearchPlanEntry> entries) {
        Set<String> all = new HashSet<String>();
        for (SearchPlanEntry entry : entries) {
            all.addAll(entry.getAcceptedSourceIds());
        }
        return all.size();
    }

    /** The accepted sources of the entries of one phase (a Document gap search has its own budget, PR 29). */
    public static int phaseSources(Collection<SearchPlanEntry> entries, String phase) {
        Set<String> all = new HashSet<String>();
        for (SearchPlanEntry entry : entries) {
            if (phase == null || phase.equals(entry.getPhase())) {
                all.addAll(entry.getAcceptedSourceIds());
            }
        }
        return all.size();
    }
}
