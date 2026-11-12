package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.List;
import java.util.Map;

/**
 * Persists the sentences, passages AND their embedding vectors of one processed capture as a single canonical
 * generation. The productive adapter records sentences/passages onto the {@code research-domain}
 * {@code ResearchProject} aggregate (via {@code recordSentences}/{@code recordPassages}) and the vectors through
 * a {@link PassageVectorStore}, saving through {@code ResearchProjectRepository} — the project directory stays
 * the single source of truth; the semantic index is a rebuildable projection of exactly this data.
 *
 * <p>A generation is complete only once sentences + passages + vectors are all on disk; only THEN does the
 * capture's active pointer swap (the single commit point).</p>
 */
public interface PassageStore {

    void store(SourceCapture capture, List<Sentence> sentences, List<Passage> passages,
               Map<String, EmbeddingPort.EmbeddingVector> passageVectors);

    /**
     * The captures of {@code sourceId} that a newer capture of the same source replaced: one active capture per
     * source, the newest wins. Their passages must leave the semantic index. A store without history has none.
     */
    default java.util.Set<String> supersededCaptureIds(String sourceId) {
        return java.util.Collections.emptySet();
    }
}
