package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * #42 pins: ONE shared, phase-neutral topic discovery — the EXISTING builder math produces
 * the snapshot both phase 1 and the outline consume; the snapshot is fingerprint-pinned,
 * restart-safe (persisted, reloadable by a fresh instance) and its staleness follows the
 * corpus identity. Discovery never calls an embedding port — pure CPU over stored vectors.
 */
public class SharedTopicDiscoveryTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static Passage passage(String id, String text) {
        return new Passage(id, "cap-1", Collections.singletonList(id + "#s"), "Root", text,
                "fpA", "seg-v1", "en");
    }

    private static EmbeddingPort.EmbeddingVector vector(float x, float y) {
        return new EmbeddingPort.EmbeddingVector("m", "fpA", new float[] {x, y});
    }

    private static ActiveKnowledgeCorpusReader.Corpus corpus(int pairs) {
        List<Passage> passages = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> vectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int index = 0; index < pairs; index++) {
            String a = "cap-1#a" + index;
            String b = "cap-1#b" + index;
            passages.add(passage(a, "Waveguide displays project bright images " + index));
            passages.add(passage(b, "Battery life limits wearable runtime " + index));
            vectors.put(a, vector(1f, 0.01f * index));
            vectors.put(b, vector(0.01f * index, 1f));
        }
        return new ActiveKnowledgeCorpusReader.Corpus(passages, vectors,
                Collections.<String>emptyList());
    }

    private static final class MutableCorpus implements SharedTopicDiscovery.CorpusSource {
        ActiveKnowledgeCorpusReader.Corpus current = corpus(2);

        public ActiveKnowledgeCorpusReader.Corpus read() {
            return current;
        }
    }

    @Test
    public void refreshPersistsAFingerprintPinnedSnapshotTheSameMathAsTheOutline()
            throws Exception {
        MutableCorpus source = new MutableCorpus();
        LiveOutlineProjectionBuilder builder = new LiveOutlineProjectionBuilder();
        FileTopicSnapshotStore store = new FileTopicSnapshotStore(temp.newFolder("p"));
        SharedTopicDiscovery discovery =
                new SharedTopicDiscovery(source, builder, store, "fpA");

        assertNull("nothing persisted yet", discovery.current());
        assertTrue("content exists but no snapshot → stale", discovery.isStale());

        FileTopicSnapshotStore.TopicSnapshot snapshot = discovery.refresh(1000L);
        assertEquals(1L, snapshot.revision);
        assertEquals("fpA", snapshot.embeddingFingerprint);
        assertFalse("freshly discovered → current", discovery.isStale());

        // The SAME builder over the SAME corpus yields the SAME topics — one math, two consumers.
        List<LiveTopicProjection> direct = builder.discoverTopics(
                source.current.getPassages(), source.current.getVectors());
        assertEquals(direct.size(), snapshot.topics.size());
        for (int index = 0; index < direct.size(); index++) {
            assertEquals(direct.get(index).getClusterId(),
                    snapshot.topics.get(index).getClusterId());
        }
        assertTrue("the two-direction corpus clusters into at least two topics",
                snapshot.topics.size() >= 2);
    }

    @Test
    public void theSnapshotSurvivesARestartAndStalenessFollowsTheCorpus() throws Exception {
        MutableCorpus source = new MutableCorpus();
        java.io.File dir = temp.newFolder("p");
        SharedTopicDiscovery first = new SharedTopicDiscovery(source,
                new LiveOutlineProjectionBuilder(), new FileTopicSnapshotStore(dir), "fpA");
        first.refresh(1000L);

        // A FRESH instance over the same directory (the restart) reads the persisted snapshot.
        SharedTopicDiscovery reopened = new SharedTopicDiscovery(source,
                new LiveOutlineProjectionBuilder(), new FileTopicSnapshotStore(dir), "fpA");
        FileTopicSnapshotStore.TopicSnapshot loaded = reopened.current();
        assertEquals(1L, loaded.revision);
        assertFalse(reopened.isStale());

        // New passages → the SAME corpus-identity math flips the snapshot stale.
        source.current = corpus(3);
        assertTrue("a grown corpus makes the snapshot stale", reopened.isStale());
        FileTopicSnapshotStore.TopicSnapshot second = reopened.refresh(2000L);
        assertEquals("the revision counts on across restarts", 2L, second.revision);
        assertFalse(reopened.isStale());
    }

    @Test
    public void refreshPinnedReturnsOneCoherentCorpusTopicsPairForCombiningConsumers()
            throws Exception {
        final MutableCorpus source = new MutableCorpus();
        // A corpus source that MUTATES on every read (the concurrently ingesting worker):
        // whatever refreshPinned returns must still be ONE coherent pair.
        SharedTopicDiscovery discovery = new SharedTopicDiscovery(
                new SharedTopicDiscovery.CorpusSource() {
                    int reads;

                    public ActiveKnowledgeCorpusReader.Corpus read() {
                        source.current = corpus(2 + reads++);
                        return source.current;
                    }
                },
                new LiveOutlineProjectionBuilder(),
                new FileTopicSnapshotStore(temp.newFolder("p")), "fpA");
        SharedTopicDiscovery.Pinned pinned = discovery.refreshPinned(1000L);
        // The snapshot's identity IS the returned corpus — the outline consumer building
        // from pinned.corpus + pinned.snapshot can never straddle a generation.
        List<String> pinnedIds = new ArrayList<String>();
        for (Passage passage : pinned.corpus.getPassages()) {
            pinnedIds.add(passage.getPassageId());
        }
        assertEquals(com.aresstack.askai.research.knowledge.live.LiveOutlineProjection
                        .corpusFingerprintOf(pinnedIds), pinned.snapshot.corpusFingerprint);
        assertTrue("the further-grown source corpus reads as stale against the snapshot",
                discovery.isStale());
    }

    @Test
    public void aDifferentEmbeddingWorldIsStaleEvenWithTheSameCorpus() throws Exception {
        MutableCorpus source = new MutableCorpus();
        java.io.File dir = temp.newFolder("p");
        new SharedTopicDiscovery(source, new LiveOutlineProjectionBuilder(),
                new FileTopicSnapshotStore(dir), "fpA").refresh(1000L);
        SharedTopicDiscovery otherWorld = new SharedTopicDiscovery(source,
                new LiveOutlineProjectionBuilder(), new FileTopicSnapshotStore(dir), "fpB");
        assertTrue("a changed embedding fingerprint invalidates the snapshot",
                otherWorld.isStale());
    }

    /** The page rule sees each capture's passages in page order: the ordinal in the id, not the id's text order. */
    @Test
    public void aPageRuleSeesThePassagesInPageOrder() {
        java.util.List<com.aresstack.askai.research.domain.Passage> passages =
                new java.util.ArrayList<com.aresstack.askai.research.domain.Passage>();
        for (String id : new String[]{"cap-1#p10@v", "cap-1#p9~001@v", "cap-1#p9@v", "cap-1#p2@v", "cap-2#p1@v"}) {
            passages.add(new com.aresstack.askai.research.domain.Passage(id, id.substring(0, id.indexOf('#')),
                    java.util.Collections.<String>emptyList(), "", id, "fp", "v", "de"));
        }
        final java.util.List<java.util.List<String>> pages = new java.util.ArrayList<java.util.List<String>>();
        TopicAnalysisRequest.PassageFilter firstOfEachPage = new TopicAnalysisRequest.PassageFilter() {
            public boolean accepts(String text) {
                return true;
            }

            public java.util.Set<Integer> rejectedOnPage(java.util.List<String> page) {
                pages.add(page);
                return java.util.Collections.singleton(0);
            }

            public String key() {
                return "first";
            }
        };

        java.util.Set<String> rejected = SharedTopicDiscovery.rejectedOnPage(passages, firstOfEachPage);

        assertEquals(java.util.Arrays.asList("cap-1#p2@v", "cap-1#p9@v", "cap-1#p9~001@v", "cap-1#p10@v"),
                pages.get(0));
        assertEquals(new java.util.HashSet<String>(java.util.Arrays.asList("cap-1#p2@v", "cap-2#p1@v")), rejected);
    }
}
