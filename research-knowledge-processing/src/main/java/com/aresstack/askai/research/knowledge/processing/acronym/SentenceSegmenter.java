package com.aresstack.askai.research.knowledge.processing.acronym;

import com.aresstack.askai.research.knowledge.RegexSentenceSegmenter;
import com.aresstack.askai.research.knowledge.SentenceSegmentationPort;

import java.util.List;

/**
 * The sentences the {@link AcronymExtractor} aligns long forms in. A small adapter onto AskAI's
 * {@link SentenceSegmentationPort} (OpenNLP when the language model is installed, else the regex segmenter); the
 * extractor locates each returned sentence in the paragraph again, so the findings keep character spans of the
 * source text. {@link #id()} is the port's fingerprint and goes into stored findings.
 */
public interface SentenceSegmenter {

    /** The sentences of one paragraph in reading order (never {@code null}). */
    List<String> sentences(String paragraph);

    /** Fingerprint of the procedure, e.g. {@code regex} or {@code opennlp-<model checksum>}. */
    String id();

    /** Adapter onto the pipeline port. */
    static SentenceSegmenter of(final SentenceSegmentationPort port) {
        if (port == null) {
            throw new IllegalArgumentException("port must not be null");
        }
        return new SentenceSegmenter() {
            @Override
            public List<String> sentences(String paragraph) {
                return port.segment(paragraph);
            }

            @Override
            public String id() {
                return port.fingerprint();
            }
        };
    }

    /** The deterministic regex segmenter of the pipeline (no model). */
    static SentenceSegmenter splitter() {
        return of(new RegexSentenceSegmenter());
    }
}
