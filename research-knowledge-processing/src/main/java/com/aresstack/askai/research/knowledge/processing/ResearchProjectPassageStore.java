package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.ResearchProject;
import com.aresstack.askai.research.domain.ResearchProjectRepository;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.List;
import java.util.Map;

/**
 * The productive {@link PassageStore}: records one processed capture's sentences, passages and vectors as a
 * single canonical generation. Sentences/passages go onto the {@code research-domain} {@link ResearchProject}
 * aggregate and are saved through the {@link ResearchProjectRepository}; the vectors go to a {@link
 * PassageVectorStore}, co-located in the SAME generation directory. The project directory stays the single
 * source of truth; embedding/index views are rebuildable projections and are NOT written here.
 *
 * <p>ONE capture = ONE commit, and a generation is complete only with all three parts: the vectors are written
 * FIRST (into the generation dir), THEN the repository writes sentences/passages/manifest and atomically swaps
 * the capture's active pointer LAST. A crash before that swap leaves the previous generation fully active
 * (never new-passages-with-old-vectors). All record-ops are idempotent, so re-storing the same derivation is a
 * no-op.</p>
 */
public final class ResearchProjectPassageStore implements PassageStore {

    private final ResearchProjectRepository repository;
    private final String projectId;
    private final PassageVectorStore vectorStore;

    public ResearchProjectPassageStore(ResearchProjectRepository repository, String projectId,
                                       PassageVectorStore vectorStore) {
        if (repository == null || vectorStore == null) {
            throw new IllegalArgumentException("repository and vectorStore are required");
        }
        this.repository = repository;
        this.projectId = projectId;
        this.vectorStore = vectorStore;
    }

    @Override
    public void store(SourceCapture capture, List<Sentence> sentences, List<Passage> passages,
                      Map<String, EmbeddingPort.EmbeddingVector> passageVectors) {
        // 1. vectors first, into the SAME generation directory (keyed by the passages' derivation identity).
        if (passages != null && !passages.isEmpty()) {
            Passage any = passages.get(0);
            vectorStore.store(capture.getCaptureId(), any.getSegmentationPipelineVersion(),
                    any.getEmbeddingFingerprint(), any.getLanguageCode(), passageVectors);
        }
        // 2. sentences/passages/manifest + the atomic active-pointer swap = the single commit point.
        ResearchProject project = repository.load(projectId);
        project.recordSourceCapture(capture);
        project.recordSentences(sentences);
        project.recordPassages(passages);
        repository.save(project);
    }

    @Override
    public java.util.Set<String> supersededCaptureIds(String sourceId) {
        // Includes a capture just stored when it lost against a newer active capture of its source.
        return repository.supersededCaptureIds(sourceId);
    }
}
