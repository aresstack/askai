package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * D6: Phase 2 and Phase 3 share ONE topic analysis in the shared discovery. Snapshots are immutable, named by their
 * state and kept as a history; Phase 2 reads the latest, Phase 3 takes one consistent snapshot; the background step
 * respects its gating; sources still being processed make a snapshot unsettled.
 */
public class SharedTopicAnalysisTest {

    private static final int DIMENSION = 32;

    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    private final List<Passage> passages = new ArrayList<Passage>();
    private final Map<String, EmbeddingPort.EmbeddingVector> vectors =
            new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
    private final Map<String, String> sourceOf = new LinkedHashMap<String, String>();
    private final Set<String> eligible = new LinkedHashSet<String>();
    private final Set<String> pending = new LinkedHashSet<String>();
    private long conceptRevision = 1L;
    private int minimumCorpus = 4;
    private int everyNew = 3;
    private boolean conceptApproved = true;
    private TopicAnalysisSettings settings = TopicAnalysisSettings.defaults();
    private TopicAnalysisRequest.PassageFilter passageFilter = TopicAnalysisRequest.PassageFilter.ALL;
    private int next;

    /** {@code count} passages of {@code sourceId} along {@code direction}, each with its own second direction. */
    private void source(String sourceId, int direction, int count) {
        for (int i = 0; i < count; i++) {
            String id = String.format("p%03d", next++);
            passages.add(new Passage(id, "cap-" + sourceId, Collections.singletonList(id + "#s"), "Root",
                    "helmets statement " + id + " from " + sourceId, "fpA", "seg-v1", "en"));
            float[] values = new float[DIMENSION];
            values[direction] = 1f;
            values[(direction + 1 + i) % DIMENSION] = 0.5f;
            vectors.put(id, new EmbeddingPort.EmbeddingVector("m", "fpA", values));
            sourceOf.put(id, sourceId);
        }
        eligible.add(sourceId);
    }

    private static EmbeddingPort.EmbeddingVector axis(int direction) {
        float[] values = new float[DIMENSION];
        values[direction] = 1f;
        return new EmbeddingPort.EmbeddingVector("m", "fpA", values);
    }

    private SharedTopicDiscovery discovery(File dir) {
        SharedTopicDiscovery discovery = new SharedTopicDiscovery(new SharedTopicDiscovery.CorpusSource() {
            public ActiveKnowledgeCorpusReader.Corpus read() {
                return new ActiveKnowledgeCorpusReader.Corpus(new ArrayList<Passage>(passages),
                        new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>(vectors),
                        Collections.<String>emptyList(), new LinkedHashMap<String, String>(sourceOf));
            }
        }, new LiveOutlineProjectionBuilder(), new FileTopicSnapshotStore(dir), "fpA");
        discovery.setAnalysisInputs(new SharedTopicDiscovery.AnalysisInputs() {
            public TopicAnalysisRequest request() {
                if (!conceptApproved) {
                    return null;
                }
                List<ConceptAnchor> anchors = Arrays.asList(
                        new ConceptAnchor("safety", "Safety", "Book > Safety", 1, "", axis(0)),
                        new ConceptAnchor("law", "Law", "Book > Law", 1, "", axis(20)));
                Map<String, String> domains = new LinkedHashMap<String, String>();
                for (String sourceId : eligible) {
                    domains.put(sourceId, sourceId + ".example");
                }
                return new TopicAnalysisRequest("The Smart Site", true, anchors, conceptRevision, null,
                        new LinkedHashSet<String>(eligible), domains, pending, settings, 3,
                        minimumCorpus, everyNew, SharedTopicDiscovery.DEFAULT_CORPUS_LIMIT, passageFilter);
            }
        });
        return discovery;
    }

    @Test
    public void oneRunMeasuresTheConceptPublishesAndPersistsAnImmutableSnapshot() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        File dir = temp.newFolder("p");
        SharedTopicDiscovery discovery = discovery(dir);
        final List<TopicAnalysisSnapshot> seen = new ArrayList<TopicAnalysisSnapshot>();
        discovery.addAnalysisListener(new Consumer<TopicAnalysisSnapshot>() {
            public void accept(TopicAnalysisSnapshot snapshot) {
                seen.add(snapshot);
            }
        });
        assertNull(discovery.latestAnalysis());

        TopicAnalysisSnapshot snapshot = discovery.analyze(null);

        assertTrue(snapshot.getId().matches("analysis-[0-9a-f]{16}"));
        assertEquals(SharedTopicDiscovery.ANALYSIS_VERSION, snapshot.getAnalysisVersion());
        assertEquals(6, snapshot.getPassages());
        assertEquals(1L, snapshot.getConceptRevision());
        assertEquals("no fence", -1L, snapshot.getScopeRevision());
        assertTrue(snapshot.isSettled());
        assertEquals(EvidenceReport.Status.OK, node(snapshot, "safety").getStatus());
        assertEquals(EvidenceReport.Status.MISSING, node(snapshot, "law").getStatus());
        assertSame(snapshot, discovery.latestAnalysis());
        assertEquals(Collections.singletonList(snapshot), seen);
        assertFalse(snapshot.text("p000").isEmpty());

        List<FileTopicSnapshotStore.AnalysisRecord> history = discovery.analysisHistory();
        assertEquals(1, history.size());
        assertEquals(snapshot.getId(), history.get(0).id);
        assertEquals("OK", history.get(0).nodeStatus.get("safety"));
        assertEquals("MISSING", history.get(0).nodeStatus.get("law"));
        assertEquals("a fresh instance reads the same history", 1, discovery(dir).analysisHistory().size());
    }

    /**
     * Live run 4: the topic distance is the request's setting, and a topic holding the material of two independent
     * concept nodes is split along the tree. A distance that merges everything still gives the two concept areas.
     */
    @Test
    public void theTopicDistanceSettingAndTheConceptSplitReachTheSharedAnalysis() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        source("s3", 20, 3);
        source("s4", 20, 3);
        double everything = 1.5;
        TopicAnalysisSettings d = TopicAnalysisSettings.defaults();
        settings = new TopicAnalysisSettings(d.getDuplicateDistance(), d.getSubtopicDistance(), everything,
                d.getConceptThreshold(), d.getConceptMargin(), d.getConceptFrameLead(), d.getMinimumPassages(),
                d.getMinimumSources(),
                d.getMinimumDomains(), d.getMinimumChildCoverage(), d.getMaximumDominantShare(),
                d.getMajorTopicShare(), d.getRepresentatives(), everything);
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        TopicAnalysisSnapshot snapshot = discovery.analyze(null);
        assertEquals("safety and law stay two topics", 2, snapshot.analysis().getTopics().size());
        assertEquals("the legacy discovery cuts the same topics", 2,
                discovery.refreshPinned(0L).snapshot.topics.size());
    }

    /** Live run 4: navigation and contact passages are no material; the request's passage filter keeps them out. */
    @Test
    public void passagesTheFilterRejectsAreNoMaterialAndNoTopic() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        source("imprint", 10, 4);
        TopicAnalysisSnapshot all = discovery(temp.newFolder("all")).analyze(null);
        assertEquals(10, all.getPassages());

        passageFilter = new TopicAnalysisRequest.PassageFilter() {
            public boolean accepts(String text) {
                return !text.endsWith("from imprint");
            }

            public String key() {
                return "no-imprint";
            }
        };
        SharedTopicDiscovery discovery = discovery(temp.newFolder("prose"));
        TopicAnalysisSnapshot prose = discovery.analyze(null);
        assertEquals("the imprint passages are left out", 6, prose.getPassages());
        assertEquals(1, prose.analysis().getTopics().size());
        assertNotEquals("a changed rule is a new state", all.getId(), prose.getId());
        assertEquals("the legacy discovery reads the same material", 6,
                discovery.refreshPinned(0L).corpus.getPassages().size());
    }

    @Test
    public void theHistoryKeepsEveryStateAndTheSameStateHasTheSameId() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        String first = discovery.analyze(null).getId();
        assertEquals("the same state is the same snapshot", first, discovery.analyze(null).getId());
        assertEquals(1, discovery.analysisHistory().size());

        source("s3", 20, 3);
        TopicAnalysisSnapshot second = discovery.analyze(null);
        assertNotEquals(first, second.getId());
        assertEquals("one source is material without confirmation", EvidenceReport.Status.UNCONFIRMED,
                node(second, "law").getStatus());
        source("s4", 20, 3);
        TopicAnalysisSnapshot third = discovery.analyze(null);
        assertEquals(EvidenceReport.Status.OK, node(third, "law").getStatus());
        conceptRevision = 2L;
        assertNotEquals("a new concept revision is a new state", third.getId(), discovery.analyze(null).getId());

        List<FileTopicSnapshotStore.AnalysisRecord> history = discovery.analysisHistory();
        assertEquals("nothing was overwritten", 4, history.size());
        assertEquals("the law node was missing first, then unconfirmed, then covered", "MISSING",
                recordOf(history, first).nodeStatus.get("law"));
        assertEquals("UNCONFIRMED", recordOf(history, second.getId()).nodeStatus.get("law"));
        assertEquals("OK", recordOf(history, third.getId()).nodeStatus.get("law"));
    }

    @Test
    public void phaseThreeReusesTheCurrentSnapshotUntilTheStateChanges() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        TopicAnalysisSnapshot current = discovery.analyze(null);
        assertSame(current, discovery.analysisFor(null));

        source("s3", 20, 3);
        TopicAnalysisSnapshot frozen = discovery.analysisFor(null);
        assertNotEquals(current.getId(), frozen.getId());
        assertEquals("the old snapshot never changes", 6, current.getPassages());

        eligible.remove("s3");
        assertEquals("an ineligible source leaves the analysis", current.getId(),
                discovery.analysisFor(null).getId());
        assertEquals(6, discovery.latestAnalysis().getPassages());
    }

    @Test
    public void theBackgroundStepWaitsForTheMinimumCorpusThenForEnoughNewPassagesOrACycleBoundary()
            throws Exception {
        final List<String> triggers = new ArrayList<String>();
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        discovery.setAnalysisTrigger(new KnowledgeProjectionInvalidator() {
            public void knowledgeChanged() {
                triggers.add("changed");
            }

            public void sourceRelevanceChanged(String sourceId) {
            }
        });
        source("s1", 0, 3);
        assertNull("3 passages are below the minimum corpus of 4", discovery.analyzeIfDue());

        source("s2", 0, 3);
        assertEquals(6, discovery.analyzeIfDue().getPassages());

        source("s3", 20, 2);
        assertNull("2 new passages are fewer than every 3", discovery.analyzeIfDue());

        discovery.requestAtCycleBoundary();
        assertEquals("the boundary asks the runner, it never runs on the caller", 1, triggers.size());
        assertEquals("at a cycle boundary any change is due", 8, discovery.analyzeIfDue().getPassages());
        discovery.requestAtCycleBoundary();
        assertNull("an unchanged corpus is not analysed again", discovery.analyzeIfDue());

        source("s4", 20, 3);
        assertEquals(11, discovery.analyzeIfDue().getPassages());
    }

    @Test
    public void sourcesStillBeingProcessedMakeTheSnapshotUnsettled() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        pending.add("s9");
        TopicAnalysisSnapshot snapshot = discovery(temp.newFolder("p")).analyze(null);
        assertFalse("the law gap may still be filled by the pending source", snapshot.isSettled());
        assertEquals(Collections.singleton("s9"), snapshot.getPendingSourceIds());
    }

    @Test
    public void aQueueChangeIsANewStateSoPhaseThreeNeverGetsAStaleSettledSnapshot() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        TopicAnalysisSnapshot settled = discovery.analyze(null);
        assertTrue(settled.isSettled());

        pending.add("s9"); // accepted and queued, no passage yet
        TopicAnalysisSnapshot queued = discovery.analysisFor(null);
        assertNotEquals("the pending source is part of the state", settled.getId(), queued.getId());
        assertFalse(queued.isSettled());
    }

    @Test
    public void aClosedSessionStopsTheBackgroundRun() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        Thread.currentThread().interrupt(); // what shutdownNow does to the runner thread
        try {
            discovery.analyzeIfDue();
            fail("an interrupted background run must stop");
        } catch (com.aresstack.askai.research.knowledge.ClusteringMonitor.ClusteringCancelledException stopped) {
            assertNull("nothing was published", discovery.latestAnalysis());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void withoutAnApprovedConceptThereIsNothingToAnalyse() throws Exception {
        source("s1", 0, 3);
        source("s2", 0, 3);
        conceptApproved = false;
        SharedTopicDiscovery discovery = discovery(temp.newFolder("p"));
        assertNull(discovery.analyzeIfDue());
        try {
            discovery.analyze(null);
            fail("an analysis needs the concept");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("concept"));
        }
    }

    private static EvidenceReport.NodeEvidence node(TopicAnalysisSnapshot snapshot, String id) {
        for (EvidenceReport.NodeEvidence node : snapshot.nodes()) {
            if (node.getAnchorId().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("no node " + id);
    }

    private static FileTopicSnapshotStore.AnalysisRecord recordOf(List<FileTopicSnapshotStore.AnalysisRecord> history,
                                                                   String id) {
        for (FileTopicSnapshotStore.AnalysisRecord record : history) {
            if (record.id.equals(id)) {
                return record;
            }
        }
        throw new AssertionError("no record " + id);
    }
}
