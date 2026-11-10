package com.aresstack.askai.research.host;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.FilePassageVectorStore;
import com.aresstack.askai.research.knowledge.processing.FileResearchProjectRepository;
import com.aresstack.askai.research.knowledge.processing.ResearchProjectPassageStore;
import com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader;
import com.aresstack.askai.research.knowledge.processing.live.SharedTopicDiscovery;
import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.outline.generate.ConceptOutlineNode;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * D6: Phase 2 and 3 share ONE evidence analysis. Snapshots are immutable and named by their state; the Sources phase
 * reads the latest, the outline takes one consistent snapshot; the background trigger respects its settings.
 */
public class EvidenceAnalysisTest {

    private static final String PROJECT = "p1";
    private static final String WORLD = "fpA";
    private static final int DIMENSION = 32;

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File dir;
    private ResearchProjectPassageStore store;
    private final Set<String> eligible = new LinkedHashSet<String>();
    private final List<ResearchSourceRecord> records = new ArrayList<ResearchSourceRecord>();
    private List<ConceptOutlineNode> concept;
    private long conceptRevision = 1L;
    private int nextCapture;
    private int queryCalls;
    private int queryTexts;
    private EvidenceAnalysisSettings settings = new EvidenceAnalysisSettings(TopicAnalysisSettings.defaults(), 3,
            4, 3, SharedTopicDiscovery.DEFAULT_CORPUS_LIMIT);

    @Before
    public void setUp() throws Exception {
        dir = folder.newFolder("corpus");
        store = new ResearchProjectPassageStore(new FileResearchProjectRepository(dir), PROJECT,
                new FilePassageVectorStore(dir));
        concept = Collections.singletonList(new ConceptOutlineNode("root", "Wearables",
                Arrays.asList(new ConceptOutlineNode("safety", "Safety", null),
                        new ConceptOutlineNode("law", "Law", null))));
    }

    /** One capture of {@code sourceId} with {@code count} passages along {@code direction}. */
    private void capture(String sourceId, int direction, int count) {
        String captureId = "cap-" + (nextCapture++);
        List<SourceCapture.StructuralBlock> blocks = Collections.singletonList(new SourceCapture.StructuralBlock(
                "b1", SourceCapture.BlockKind.PARAGRAPH, "Root", "text"));
        SourceCapture capture = new SourceCapture(captureId, sourceId, "https://x/" + captureId, 7L,
                "h-" + captureId, "T", "A", blocks);
        List<Sentence> sentences = new ArrayList<Sentence>();
        List<Passage> passages = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> vectors = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int i = 0; i < count; i++) {
            String sentenceId = captureId + "#s" + i;
            String text = "safety statement " + captureId + " number " + i + " about helmets.";
            sentences.add(new Sentence(sentenceId, captureId, "b1", i, text));
            String passageId = captureId + "#p" + i + "@seg-v1-" + WORLD;
            passages.add(new Passage(passageId, captureId, Collections.singletonList(sentenceId), "Root", text, WORLD,
                    "seg-v1", "en"));
            float[] values = new float[DIMENSION];
            values[direction] = 1f;
            values[(direction + 1 + i) % DIMENSION] = 0.5f;
            vectors.put(passageId, new EmbeddingPort.EmbeddingVector("m", WORLD, values));
        }
        try {
            store.store(capture, sentences, passages, vectors);
        } catch (Exception failed) {
            throw new IllegalStateException(failed);
        }
        eligible.add(sourceId);
    }

    private EvidenceAnalysis analysis(Executor background) {
        ActiveKnowledgeCorpusReader reader = new ActiveKnowledgeCorpusReader(new FileResearchProjectRepository(dir),
                new FilePassageVectorStore(dir), PROJECT, WORLD);
        EmbeddingPort embeddings = new EmbeddingPort() {
            public List<EmbeddingVector> embed(Role role, List<String> texts) {
                assertEquals("concept paths are embedded as queries", Role.QUERY, role);
                queryCalls++;
                queryTexts += texts.size();
                List<EmbeddingVector> result = new ArrayList<EmbeddingVector>();
                for (String text : texts) {
                    float[] values = new float[DIMENSION];
                    values[text.endsWith("Safety") ? 0 : 20] = 1f;
                    result.add(new EmbeddingVector("m", WORLD, values));
                }
                return result;
            }
        };
        return new EvidenceAnalysis(new EvidenceAnalysis.Inputs() {
            public String bookTitle() {
                return "The Smart Site";
            }

            public List<ConceptOutlineNode> conceptNodes() {
                return concept;
            }

            public long conceptRevision() {
                return conceptRevision;
            }

            public Set<String> eligibleSourceIds() {
                return new LinkedHashSet<String>(eligible);
            }

            public Collection<ResearchSourceRecord> sources() {
                return records;
            }

            public String embeddingFingerprint() {
                return WORLD;
            }

            public long now() {
                return 42L;
            }
        }, reader, embeddings, new Supplier<EvidenceAnalysisSettings>() {
            public EvidenceAnalysisSettings get() {
                return settings;
            }
        }, background);
    }

    @Test
    public void oneRunMeasuresTheConceptBelowTheFrameAndPublishesAnImmutableSnapshot() throws Exception {
        capture("s1", 0, 3);
        capture("s2", 0, 3);
        EvidenceAnalysis analysis = analysis(null);
        final List<EvidenceAnalysis.Snapshot> seen = new ArrayList<EvidenceAnalysis.Snapshot>();
        analysis.addListener(new Consumer<EvidenceAnalysis.Snapshot>() {
            public void accept(EvidenceAnalysis.Snapshot snapshot) {
                seen.add(snapshot);
            }
        });
        assertNull("nothing before the first run", analysis.latest());

        EvidenceAnalysis.Snapshot snapshot = analysis.analyze(ClusteringMonitor.NONE);

        assertTrue(snapshot.getId().matches("analysis-[0-9a-f]{16}"));
        assertEquals(6, snapshot.getPassages());
        assertEquals(42L, snapshot.getCreatedAt());
        assertTrue("the single root is the book frame", snapshot.hasBookFrame());
        assertEquals(2, snapshot.nodes().size());
        assertEquals(EvidenceReport.Status.OK, node(snapshot, "safety").getStatus());
        assertEquals(EvidenceReport.Status.MISSING, node(snapshot, "law").getStatus());
        assertEquals("The Smart Site > Wearables > Law", node(snapshot, "law").getPath());
        assertSame(snapshot, analysis.latest());
        assertEquals(Collections.singletonList(snapshot), seen);
        assertFalse(snapshot.text(snapshot.analysis().getTopics().get(0).getPassageIds().get(0)).isEmpty());
    }

    @Test
    public void theSameStateHasTheSameIdAndConceptPathsAreEmbeddedOnce() throws Exception {
        capture("s1", 0, 3);
        capture("s2", 0, 3);
        EvidenceAnalysis analysis = analysis(null);
        String first = analysis.analyze(ClusteringMonitor.NONE).getId();
        assertEquals(1, queryCalls);
        assertEquals(2, queryTexts);

        assertEquals(first, analysis.analyze(ClusteringMonitor.NONE).getId());
        assertEquals("cached label paths are not embedded again", 1, queryCalls);
        assertEquals("another session object with the same state names it alike", first,
                analysis(null).analyze(ClusteringMonitor.NONE).getId());

        conceptRevision = 2L;
        assertNotEquals("a new concept revision is a new state", first,
                analysis.analyze(ClusteringMonitor.NONE).getId());
        conceptRevision = 1L;
        settings = new EvidenceAnalysisSettings(TopicAnalysisSettings.defaults(), 4, 4, 3,
                SharedTopicDiscovery.DEFAULT_CORPUS_LIMIT);
        assertNotEquals("other settings are a new state", first, analysis.analyze(ClusteringMonitor.NONE).getId());
    }

    @Test
    public void theOutlineReusesTheCurrentSnapshotUntilTheCorpusChanges() throws Exception {
        capture("s1", 0, 3);
        capture("s2", 0, 3);
        EvidenceAnalysis analysis = analysis(null);
        EvidenceAnalysis.Snapshot current = analysis.analyze(ClusteringMonitor.NONE);

        assertSame("unchanged corpus: the outline works on the snapshot Phase 2 already has", current,
                analysis.snapshotFor(ClusteringMonitor.NONE));

        capture("s3", 20, 3);
        EvidenceAnalysis.Snapshot frozen = analysis.snapshotFor(ClusteringMonitor.NONE);
        assertNotEquals(current.getId(), frozen.getId());
        assertEquals(9, frozen.getPassages());
        assertEquals("the old snapshot never changes", 6, current.getPassages());

        eligible.remove("s3");
        assertEquals("an ineligible source leaves the analysis", current.getId(),
                analysis.snapshotFor(ClusteringMonitor.NONE).getId());
    }

    @Test
    public void theBackgroundTriggerWaitsForTheMinimumCorpusAndThenForEnoughNewPassages() throws Exception {
        final List<Runnable> queued = new ArrayList<Runnable>();
        EvidenceAnalysis analysis = analysis(new Executor() {
            public void execute(Runnable command) {
                queued.add(command);
            }
        });
        capture("s1", 0, 3);
        analysis.knowledgeChanged();
        analysis.knowledgeChanged();
        assertEquals("at most one pending check", 1, queued.size());
        queued.remove(0).run();
        assertNull("3 passages are below the minimum corpus of 4", analysis.latest());

        capture("s2", 0, 3);
        analysis.knowledgeChanged();
        queued.remove(0).run();
        assertEquals(6, analysis.latest().getPassages());

        capture("s3", 20, 2);
        analysis.knowledgeChanged();
        queued.remove(0).run();
        assertEquals("2 new passages are fewer than every 3", 6, analysis.latest().getPassages());

        capture("s4", 20, 1);
        analysis.knowledgeChanged();
        queued.remove(0).run();
        assertEquals(9, analysis.latest().getPassages());
    }

    @Test
    public void theDomainIsTheHostWithoutWww() {
        assertEquals("example.org", EvidenceAnalysis.host("https://www.Example.org/a?b"));
        assertEquals("", EvidenceAnalysis.host(""));
        assertEquals("", EvidenceAnalysis.host("not a url"));
    }

    private static EvidenceReport.NodeEvidence node(EvidenceAnalysis.Snapshot snapshot, String id) {
        for (EvidenceReport.NodeEvidence node : snapshot.nodes()) {
            if (node.getAnchorId().equals(id)) {
                return node;
            }
        }
        throw new AssertionError("no node " + id);
    }
}
