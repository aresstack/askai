package com.aresstack.askai.research.host;

import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.knowledge.processing.live.TopicAnalysisSnapshot;
import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.ScopeAlignment;
import com.aresstack.askai.research.plan.CoverageQuality;
import com.aresstack.askai.research.plan.EvidenceGaps;
import com.aresstack.askai.research.plan.SearchPlanEntry;
import com.aresstack.askai.research.plan.SearchPlanQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Plan T4 gap review, the session side: at a cycle boundary it asks the shared topic analysis (phase 3's
 * {@link SharedTopicDiscovery}) for the snapshot of the material read so far and hands it to {@link EvidenceGaps}.
 * It computes nothing itself: statuses, scope alignment and the pending sources come from the snapshot.
 *
 * <p>While sources still wait for processing the snapshot is not settled; the review then waits for the next
 * snapshot, at most the configured time, and without a settled one the cycle plans from the source counts.
 */
public final class TopicAnalysisGapReview implements SearchPlanQueue.GapReview {

    /** What the review reads from the session. */
    public interface Inputs {
        /** The session's shared topic analysis, or {@code null} without the knowledge capability. */
        SharedTopicDiscovery discovery();

        /** The typed blacklist of the frozen concept. */
        List<String> blacklist();

        long waitMillis();

        /** How often the review looks again while sources are in processing (also without a new snapshot). */
        long recheckMillis();

        int maxNewTopics();

        int maxFailedQueries();

        /**
         * The domain of an accepted source ("" when unknown), for the share of a node's passages from the mission's
         * preferred sources ({@link CoverageQuality}). The default knows none: the share bar then finds no preferred
         * passage.
         */
        default String domainOf(String sourceId) {
            return "";
        }
    }

    private final Inputs inputs;

    public TopicAnalysisGapReview(Inputs inputs) {
        this.inputs = inputs;
    }

    public EvidenceGaps.Gaps atCycleBoundary(Collection<String> activeNodes, Collection<SearchPlanEntry> entries) {
        return atCycleBoundary(activeNodes, entries, CoverageQuality.OFF);
    }

    /**
     * The gaps of the settled snapshot under the plan's own bar: a node the analysis calls OK is WEAK when its
     * distinct passages or the share of its passages from the mission's preferred sources stay below the settings.
     */
    @Override
    public EvidenceGaps.Gaps atCycleBoundary(Collection<String> activeNodes, Collection<SearchPlanEntry> entries,
                                             CoverageQuality quality) {
        SharedTopicDiscovery discovery = inputs.discovery();
        if (discovery == null) {
            return EvidenceGaps.Gaps.NONE;
        }
        TopicAnalysisSnapshot snapshot = settledSnapshot(discovery, System.currentTimeMillis() + inputs.waitMillis(),
                inputs.recheckMillis());
        return EvidenceGaps.of(view(snapshot, inputs), activeNodes, entries, inputs.blacklist(),
                inputs.maxNewTopics(), inputs.maxFailedQueries(), quality);
    }

    /**
     * The snapshot of the current material; waits for a settled one until {@code deadline}. It looks again at every
     * new snapshot and at least every {@code recheck} ms: a processed source that adds few passages publishes no
     * snapshot by itself, but {@link SharedTopicDiscovery#analysisFor} then runs once on the changed corpus.
     */
    static TopicAnalysisSnapshot settledSnapshot(SharedTopicDiscovery discovery, long deadline, long recheck) {
        final Object signal = new Object();
        Consumer<TopicAnalysisSnapshot> wake = new Consumer<TopicAnalysisSnapshot>() {
            public void accept(TopicAnalysisSnapshot published) {
                synchronized (signal) {
                    signal.notifyAll();
                }
            }
        };
        discovery.addAnalysisListener(wake);
        try {
            while (true) {
                TopicAnalysisSnapshot snapshot;
                try {
                    snapshot = discovery.analysisFor(null); // the latest one when it is current, else a fresh run
                } catch (Exception unavailable) {
                    System.err.println("[search-plan] gap review: no analysis (" + unavailable.getMessage() + ")");
                    return null;
                }
                long left = deadline - System.currentTimeMillis();
                if (snapshot == null || snapshot.isSettled() || left <= 0) {
                    return snapshot;
                }
                System.err.println("[search-plan] gap review waits for " + snapshot.getPendingSourceIds().size()
                        + " sources in processing");
                discovery.requestAtCycleBoundary(); // the runner analyses again once the processed sources land
                synchronized (signal) {
                    try {
                        signal.wait(Math.max(1L, Math.min(left, recheck <= 0 ? left : recheck)));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return snapshot;
                    }
                }
            }
        } finally {
            discovery.removeAnalysisListener(wake);
        }
    }

    /** The snapshot as the gap review reads it; {@code null} stays {@code null}. */
    static EvidenceGaps.Analysis view(final TopicAnalysisSnapshot snapshot) {
        return view(snapshot, null);
    }

    /**
     * The snapshot as the gap review reads it, each node with its passages per source domain (the snapshot names the
     * source of a passage, {@code inputs} its domain); {@code null} stays {@code null}.
     */
    static EvidenceGaps.Analysis view(final TopicAnalysisSnapshot snapshot, Inputs inputs) {
        if (snapshot == null) {
            return null;
        }
        final List<EvidenceGaps.NodeEvidence> nodes = new ArrayList<EvidenceGaps.NodeEvidence>();
        for (EvidenceReport.NodeEvidence node : snapshot.nodes()) {
            nodes.add(new EvidenceGaps.NodeEvidence(node.getAnchorId(), status(node.getStatus()), node.getPassages(),
                    node.getDistinctPassages(), node.getSources(), node.getDomains(), node.getKeyTerms(),
                    passagesByDomain(snapshot, node, inputs)));
        }
        final List<EvidenceGaps.NewTopic> topics = new ArrayList<EvidenceGaps.NewTopic>();
        for (EvidenceReport.ResearchArea area : snapshot.novelAreas()) {
            ScopeAlignment scope = area.getScopeAlignment() == null ? ScopeAlignment.UNKNOWN
                    : area.getScopeAlignment();
            topics.add(new EvidenceGaps.NewTopic(area.getTitle(), area.getKeyTerms(), area.getDistinctPassages(),
                    scope(scope.getVerdict()), scope.getNearestOutLabel()));
        }
        return new EvidenceGaps.Analysis() {
            public String id() {
                return snapshot.getId();
            }

            public boolean settled() {
                return snapshot.isSettled();
            }

            public List<EvidenceGaps.NodeEvidence> nodes() {
                return Collections.unmodifiableList(nodes);
            }

            public List<EvidenceGaps.NewTopic> newTopics() {
                return Collections.unmodifiableList(topics);
            }
        };
    }

    /** The node's passages per source domain; empty without inputs or coverage. */
    static Map<String, Integer> passagesByDomain(TopicAnalysisSnapshot snapshot, EvidenceReport.NodeEvidence node,
                                                 Inputs inputs) {
        Map<String, Integer> byDomain = new LinkedHashMap<String, Integer>();
        if (inputs == null || node.getCoverage() == null || snapshot.analysis() == null) {
            return byDomain;
        }
        Map<String, String> domainOfSource = new java.util.HashMap<String, String>();
        for (String passageId : node.getCoverage().getPassageIds()) {
            String sourceId = snapshot.analysis().sourceOf(passageId);
            String domain = domainOfSource.get(sourceId);
            if (domain == null) {
                String resolved = sourceId.isEmpty() ? "" : inputs.domainOf(sourceId);
                domain = resolved == null ? "" : resolved.trim().toLowerCase(java.util.Locale.ROOT);
                domainOfSource.put(sourceId, domain);
            }
            Integer count = byDomain.get(domain);
            byDomain.put(domain, count == null ? 1 : count + 1);
        }
        return byDomain;
    }

    static EvidenceGaps.Status status(EvidenceReport.Status status) {
        return status == null ? EvidenceGaps.Status.OK : EvidenceGaps.Status.valueOf(status.name());
    }

    static EvidenceGaps.Scope scope(ScopeAlignment.Verdict verdict) {
        return verdict == null ? EvidenceGaps.Scope.UNCLASSIFIED : EvidenceGaps.Scope.valueOf(verdict.name());
    }

    /** The adapter over the session's settings store. */
    public static Inputs inputs(final Supplier<SharedTopicDiscovery> discovery, final Supplier<List<String>> blacklist,
                                final com.aresstack.askai.plugin.api.service.WorkspaceStateStore store) {
        return inputs(discovery, blacklist, store, null);
    }

    /**
     * The adapter over the session's settings store and its sources.
     *
     * @param domains the domain of an accepted source; {@code null} = unknown
     */
    public static Inputs inputs(final Supplier<SharedTopicDiscovery> discovery, final Supplier<List<String>> blacklist,
                                final com.aresstack.askai.plugin.api.service.WorkspaceStateStore store,
                                final java.util.function.Function<String, String> domains) {
        return new Inputs() {
            public String domainOf(String sourceId) {
                if (domains == null) {
                    return "";
                }
                String domain = domains.apply(sourceId);
                return domain == null ? "" : domain;
            }

            public SharedTopicDiscovery discovery() {
                return discovery.get();
            }

            public List<String> blacklist() {
                List<String> terms = blacklist.get();
                return terms == null ? Collections.<String>emptyList() : terms;
            }

            public long waitMillis() {
                return 1000L * ResearchRuntimeSettings.loadGapWaitSeconds(store);
            }

            public long recheckMillis() {
                return 1000L * ResearchRuntimeSettings.loadGapRecheckSeconds(store);
            }

            public int maxNewTopics() {
                return ResearchRuntimeSettings.loadGapNewTopics(store);
            }

            public int maxFailedQueries() {
                return ResearchRuntimeSettings.loadGapFailedQueries(store);
            }
        };
    }
}
