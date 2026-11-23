package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** PR 20: a corpus above the limit keeps the best passages of every source and reports the rest. */
public class CorpusLimitTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    /** Capture {@code cap-k} has {@code sizes[k]} passages; passage 0 of each capture is the outlier. */
    private static ActiveKnowledgeCorpusReader.Corpus corpus(int[] sizes, List<String> starred) {
        List<Passage> passages = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int k = 0; k < sizes.length; k++) {
            for (int i = 0; i < sizes[k]; i++) {
                String id = "cap-" + k + "#p" + i;
                passages.add(new Passage(id, "cap-" + k, Collections.singletonList(id + "#s"), "", "Text " + id,
                        "fpA", "seg-v1", "en"));
                float[] v = i == 0 ? new float[] {0f, 1f, 0f} : new float[] {1f, 0.01f * i, k};
                vectors.put(id, new EmbeddingPort.EmbeddingVector("m", "fpA", v));
            }
        }
        return new ActiveKnowledgeCorpusReader.Corpus(passages, vectors, starred);
    }

    private static Map<String, Integer> perCapture(ActiveKnowledgeCorpusReader.Corpus corpus) {
        Map<String, Integer> counts = new HashMap<String, Integer>();
        for (Passage passage : corpus.getPassages()) {
            Integer c = counts.get(passage.getCaptureId());
            counts.put(passage.getCaptureId(), c == null ? 1 : c + 1);
        }
        return counts;
    }

    @Test
    public void belowTheLimitTheCorpusIsUnchanged() {
        ActiveKnowledgeCorpusReader.Corpus corpus = corpus(new int[] {3, 3}, Collections.<String>emptyList());
        assertSame(corpus, CorpusLimit.apply(corpus, 6));
    }

    @Test
    public void everySourceKeepsItsShareAndTheOutlierGoesFirst() {
        ActiveKnowledgeCorpusReader.Corpus limited =
                CorpusLimit.apply(corpus(new int[] {10, 2, 6}, Collections.<String>emptyList()), 8);
        assertEquals(8, limited.getPassages().size());
        Map<String, Integer> counts = perCapture(limited);
        assertEquals(Integer.valueOf(2), counts.get("cap-1"));
        assertEquals(Integer.valueOf(3), counts.get("cap-0"));
        assertEquals(Integer.valueOf(3), counts.get("cap-2"));
        for (Passage passage : limited.getPassages()) {
            if (!passage.getCaptureId().equals("cap-1")) {
                assertFalse("the least typical passage is dropped first", passage.getPassageId().endsWith("#p0"));
            }
            assertTrue(limited.getVectors().containsKey(passage.getPassageId()));
        }
        assertEquals(8, limited.getVectors().size());
    }

    @Test
    public void starredSourcesGetTheirTurnFirst() {
        ActiveKnowledgeCorpusReader.Corpus limited = CorpusLimit.apply(
                corpus(new int[] {4, 4, 4}, Collections.singletonList("cap-2#p1")), 4);
        Map<String, Integer> counts = perCapture(limited);
        assertEquals(Integer.valueOf(2), counts.get("cap-2"));
    }

    @Test
    public void refreshReportsTheLeftOutPassagesAndStaysFreshAndCancelPersistsNothing() throws Exception {
        final ActiveKnowledgeCorpusReader.Corpus whole = corpus(new int[] {10, 10}, Collections.<String>emptyList());
        FileTopicSnapshotStore store = new FileTopicSnapshotStore(temp.newFolder("p"));
        SharedTopicDiscovery discovery = new SharedTopicDiscovery(new SharedTopicDiscovery.CorpusSource() {
            public ActiveKnowledgeCorpusReader.Corpus read() {
                return whole;
            }
        }, new LiveOutlineProjectionBuilder(), store, "fpA", 12);

        try {
            discovery.refreshPinned(1L, new ClusteringMonitor() {
                public void progress(String stage, long done, long total) {
                }

                public boolean isCancelled() {
                    return true;
                }
            });
            fail("cancel must abort");
        } catch (ClusteringMonitor.ClusteringCancelledException expected) {
            assertNull("a cancelled run persists nothing", discovery.current());
        }

        SharedTopicDiscovery.Pinned pinned = discovery.refreshPinned(2L, ClusteringMonitor.NONE);
        assertEquals(8, pinned.omittedPassages);
        assertEquals(12, pinned.corpus.getPassages().size());
        assertFalse("a limited run describes the whole corpus", discovery.isStale());
    }

    @Test
    public void aCancelDuringTheLastProgressStepPersistsNothing() throws Exception {
        final ActiveKnowledgeCorpusReader.Corpus whole = corpus(new int[] {3, 3}, Collections.<String>emptyList());
        SharedTopicDiscovery discovery = new SharedTopicDiscovery(new SharedTopicDiscovery.CorpusSource() {
            public ActiveKnowledgeCorpusReader.Corpus read() {
                return whole;
            }
        }, new LiveOutlineProjectionBuilder(), new FileTopicSnapshotStore(temp.newFolder("late")), "fpA");
        final boolean[] cancelled = {false};
        try {
            discovery.refreshPinned(1L, new ClusteringMonitor() {
                public void progress(String stage, long done, long total) {
                    if (done == total) {
                        cancelled[0] = true;
                    }
                }

                public boolean isCancelled() {
                    return cancelled[0];
                }
            });
            fail("a late cancel must abort");
        } catch (ClusteringMonitor.ClusteringCancelledException expected) {
            assertNull(discovery.current());
        }
    }

    @Test
    public void anOutlineFromALimitedCorpusKeepsTheWholeCorpusIdentity() throws Exception {
        final ActiveKnowledgeCorpusReader.Corpus whole = corpus(new int[] {10, 10}, Collections.<String>emptyList());
        SharedTopicDiscovery discovery = new SharedTopicDiscovery(new SharedTopicDiscovery.CorpusSource() {
            public ActiveKnowledgeCorpusReader.Corpus read() {
                return whole;
            }
        }, new LiveOutlineProjectionBuilder(), new FileTopicSnapshotStore(temp.newFolder("o")), "fpA", 12);
        SharedTopicDiscovery.Pinned pinned = discovery.refreshPinned(1L, ClusteringMonitor.NONE);
        List<String> wholeIds = new ArrayList<String>();
        for (Passage passage : whole.getPassages()) {
            wholeIds.add(passage.getPassageId());
        }
        assertEquals(com.aresstack.askai.research.knowledge.live.LiveOutlineProjection.corpusFingerprintOf(wholeIds),
                new LiveOutlineProjectionBuilder().buildOutline(1L, "fpA", 1L, pinned.corpus.getPassages(),
                        pinned.snapshot.topics, Collections.<String>emptyList(),
                        pinned.snapshot.corpusFingerprint).getCorpusFingerprint());
    }
}
