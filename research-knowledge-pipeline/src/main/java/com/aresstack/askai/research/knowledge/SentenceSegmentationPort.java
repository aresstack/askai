package com.aresstack.askai.research.knowledge;

import java.util.List;

/** Neutral sentence segmentation port — the OpenNLP adapter lives in {@code :research-text-opennlp}. */
public interface SentenceSegmentationPort {

    List<String> segment(String text);

    /**
     * Identity of the segmentation procedure that goes into stored findings (PR 38): {@code regex} for the
     * deterministic splitter, {@code opennlp-<model checksum>} for a trained model. A different fingerprint
     * marks findings derived from sentences of another segmenter as stale.
     */
    default String fingerprint() {
        return getClass().getName();
    }
}
