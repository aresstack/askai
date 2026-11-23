package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.domain.IdSequence;
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
 * no-op that writes nothing.</p>
 *
 * <p>Point R (2026-11-21): the save holds ONLY the stored capture with its new derivation. Before, the whole
 * project was loaded and saved again, so a capture derived anew (re-embedding after a model switch, a new
 * segmentation version) carried its old passages next to the new ones, the repository refused the mix, every
 * capture of an old workspace failed at PASSAGE_PERSISTENCE and left its vectors behind, and every other capture's
 * pointer was rewritten. The capture's immutability is still checked against the project before anything is
 * written; vectors a failed save leaves behind are removed, also those of earlier failed attempts.</p>
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
        // 0. captures are immutable: a known capture id with other content is refused before anything is written.
        repository.load(projectId).recordSourceCapture(capture);
        // leftovers of earlier attempts whose save failed: vectors without a generation
        vectorStore.removeOrphans(capture.getCaptureId());
        // 1. vectors first, into the SAME generation directory (keyed by the passages' derivation identity).
        if (passages != null && !passages.isEmpty()) {
            Passage any = passages.get(0);
            vectorStore.store(capture.getCaptureId(), any.getSegmentationPipelineVersion(),
                    any.getEmbeddingFingerprint(), any.getLanguageCode(), passageVectors);
        }
        // 2. sentences/passages/manifest + the atomic active-pointer swap = the single commit point. The save holds
        // this capture and its new derivation only: the passages of an older derivation (another embedding world,
        // another segmentation version) stay on disk as history and are no longer active; the other captures of
        // the project are not touched.
        ResearchProject derivation = new ResearchProject(projectId, IdSequence.counting());
        derivation.recordSourceCapture(capture);
        derivation.recordSentences(sentences);
        derivation.recordPassages(passages);
        try {
            repository.save(derivation);
        } catch (RuntimeException failed) {
            vectorStore.removeOrphans(capture.getCaptureId());
            throw failed;
        }
    }

    @Override
    public java.util.Set<String> supersededCaptureIds(String sourceId) {
        // Includes a capture just stored when it lost against a newer active capture of its source.
        return repository.supersededCaptureIds(sourceId);
    }
}
