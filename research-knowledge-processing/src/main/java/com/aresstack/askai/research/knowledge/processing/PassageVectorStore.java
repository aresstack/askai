package com.aresstack.askai.research.knowledge.processing;

import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.util.List;
import java.util.Map;

/**
 * Persists the FINAL passage embedding vectors as a CANONICAL, versioned part of a capture's processing
 * generation — the missing half of "passage fact + its computed vector = persistent". The domain {@code Passage}
 * keeps only the model fingerprint (a vector is a rebuildable projection of the TEXT, but re-deriving it means
 * calling the embedding model again); persisting the vectors here makes the semantic index rebuildable WITHOUT
 * re-embedding.
 *
 * <p>Vectors live in the SAME generation directory as the sentences/passages, keyed by the derivation identity
 * (captureId + segmentationPipelineVersion + embeddingFingerprint + languageCode). They are written BEFORE the repository swaps
 * the capture's active pointer, so a generation is "complete" only once sentences + passages + vectors are all
 * on disk — the active pointer stays the single commit point (a crash before it keeps the previous generation
 * fully active). Loading returns exactly the requested generation's vectors, so superseded generations never
 * bleed into the active search space.</p>
 */
public interface PassageVectorStore {

    /**
     * Persist the passage vectors of one generation. All vectors must share {@code embeddingFingerprint} and the
     * same dimension. An empty map writes nothing (an empty capture has no vectors).
     */
    void store(String captureId, String segmentationPipelineVersion, String embeddingFingerprint,
               String languageCode, Map<String, EmbeddingPort.EmbeddingVector> passageVectors);

    /**
     * Load the vectors of a specific generation as neutral floats (passageId → vector), for rebuilding the
     * semantic index without re-embedding. Returns empty when that generation has no persisted vectors.
     */
    Map<String, float[]> load(String captureId, String segmentationPipelineVersion,
                              String embeddingFingerprint, String languageCode);

    /** The passage ids of a generation, in the persisted (deterministic) order. */
    List<String> passageIds(String captureId, String segmentationPipelineVersion,
                            String embeddingFingerprint, String languageCode);

    /**
     * Point R (2026-11-21): removes the vectors of {@code captureId} that belong to no generation, because the save
     * that should have followed them failed (before the fix, every re-embedding of an old workspace left one such
     * {@code vectors.bin} per capture). A generation the repository wrote is never touched. Returns how many
     * leftovers were removed; a store without such leftovers removes none.
     */
    default int removeOrphans(String captureId) {
        return 0;
    }
}
