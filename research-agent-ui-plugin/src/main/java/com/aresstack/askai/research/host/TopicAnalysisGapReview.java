package com.aresstack.askai.research.host;

import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.knowledge.processing.live.TopicAnalysisSnapshot;
import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.ScopeAlignment;
import com.aresstack.askai.research.plan.EvidenceGaps;
import com.aresstack.askai.research.plan.SearchPlanEntry;
import com.aresstack.askai.research.plan.SearchPlanQueue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
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
    }

    private final Inputs inputs;

    public TopicAnalysisGapReview(Inputs inputs) {
        this.inputs = inputs;
    }

    public EvidenceGaps.Gaps atCycleBoundary(Collection<String> activeNodes, Collection<SearchPlanEntry> entries) {
        SharedTopicDiscovery discovery = inputs.discovery();
        if (discovery == null) {
            return EvidenceGaps.Gaps.NONE;
        }
        TopicAnalysisSnapshot snapshot = settledSnapshot(discovery, System.currentTimeMillis() + inputs.waitMillis(),
                inputs.recheckMillis());
        return EvidenceGaps.of(view(snapshot), activeNodes, entries, inputs.blacklist(), inputs.maxNewTopics(),
                inputs.maxFailedQueries());
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
        if (snapshot == null) {
            return null;
        }
        final List<EvidenceGaps.NodeEvidence> nodes = new ArrayList<EvidenceGaps.NodeEvidence>();
        for (EvidenceReport.NodeEvidence node : snapshot.nodes()) {
            nodes.add(new EvidenceGaps.NodeEvidence(node.getAnchorId(), status(node.getStatus()), node.getPassages(),
                    node.getDistinctPassages(), node.getSources(), node.getDomains(), node.getKeyTerms()));
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

    static EvidenceGaps.Status status(EvidenceReport.Status status) {
        return status == null ? EvidenceGaps.Status.OK : EvidenceGaps.Status.valueOf(status.name());
    }

    static EvidenceGaps.Scope scope(ScopeAlignment.Verdict verdict) {
        return verdict == null ? EvidenceGaps.Scope.UNCLASSIFIED : EvidenceGaps.Scope.valueOf(verdict.name());
    }

    /** The adapter over the session's settings store. */
    public static Inputs inputs(final Supplier<SharedTopicDiscovery> discovery, final Supplier<List<String>> blacklist,
                                final com.aresstack.askai.plugin.api.service.WorkspaceStateStore store) {
        return new Inputs() {
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
