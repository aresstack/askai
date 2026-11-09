package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.VectorMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PR 20: keeps a corpus above the clustering limit within it. Every source keeps its best passages: within a
 * capture, passages rank by cosine to the capture's mean vector (the most typical first, ties by passage id);
 * captures take turns, one passage per turn, captures of ⭐-marked sources first in each round, the rest in
 * capture-id order. The result keeps the reader's passage-id order, so downstream stays deterministic.
 */
final class CorpusLimit {

    private CorpusLimit() {
    }

    static ActiveKnowledgeCorpusReader.Corpus apply(ActiveKnowledgeCorpusReader.Corpus corpus, int limit) {
        List<Passage> passages = corpus.getPassages();
        if (passages.size() <= limit) {
            return corpus;
        }
        final Map<String, EmbeddingPort.EmbeddingVector> vectors = corpus.getVectors();
        Set<String> starred = new HashSet<String>(corpus.getUserRelevantPassageIds());
        Map<String, List<Passage>> byCapture = new LinkedHashMap<String, List<Passage>>();
        Set<String> starredCaptures = new HashSet<String>();
        for (Passage passage : passages) {
            List<Passage> list = byCapture.get(passage.getCaptureId());
            if (list == null) {
                list = new ArrayList<Passage>();
                byCapture.put(passage.getCaptureId(), list);
            }
            list.add(passage);
            if (starred.contains(passage.getPassageId())) {
                starredCaptures.add(passage.getCaptureId());
            }
        }
        List<String> captureOrder = new ArrayList<String>(byCapture.keySet());
        Collections.sort(captureOrder);
        List<String> ordered = new ArrayList<String>();
        for (String id : captureOrder) {
            if (starredCaptures.contains(id)) {
                ordered.add(id);
            }
        }
        for (String id : captureOrder) {
            if (!starredCaptures.contains(id)) {
                ordered.add(id);
            }
        }
        List<List<Passage>> ranked = new ArrayList<List<Passage>>();
        for (String id : ordered) {
            ranked.add(rankByTypicality(byCapture.get(id), vectors));
        }
        Set<String> kept = new HashSet<String>();
        for (int round = 0; kept.size() < limit; round++) {
            boolean any = false;
            for (List<Passage> list : ranked) {
                if (round < list.size() && kept.size() < limit) {
                    kept.add(list.get(round).getPassageId());
                    any = true;
                }
            }
            if (!any) {
                break;
            }
        }
        List<Passage> keptPassages = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> keptVectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        List<String> keptStarred = new ArrayList<String>();
        for (Passage passage : passages) {
            if (kept.contains(passage.getPassageId())) {
                keptPassages.add(passage);
                keptVectors.put(passage.getPassageId(), vectors.get(passage.getPassageId()));
                if (starred.contains(passage.getPassageId())) {
                    keptStarred.add(passage.getPassageId());
                }
            }
        }
        return new ActiveKnowledgeCorpusReader.Corpus(keptPassages, keptVectors, keptStarred);
    }

    private static List<Passage> rankByTypicality(List<Passage> capture,
                                                  final Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        List<EmbeddingPort.EmbeddingVector> members = new ArrayList<EmbeddingPort.EmbeddingVector>();
        for (Passage passage : capture) {
            members.add(vectors.get(passage.getPassageId()));
        }
        final EmbeddingPort.EmbeddingVector centre = VectorMath.mean(members);
        final Map<String, Double> score = new LinkedHashMap<String, Double>();
        for (Passage passage : capture) {
            score.put(passage.getPassageId(), VectorMath.cosine(vectors.get(passage.getPassageId()), centre));
        }
        List<Passage> sorted = new ArrayList<Passage>(capture);
        Collections.sort(sorted, new Comparator<Passage>() {
            public int compare(Passage a, Passage b) {
                int byScore = Double.compare(score.get(b.getPassageId()), score.get(a.getPassageId()));
                return byScore != 0 ? byScore : a.getPassageId().compareTo(b.getPassageId());
            }
        });
        return sorted;
    }
}
