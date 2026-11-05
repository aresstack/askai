package com.aresstack.askai.research.knowledge;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.Sentence;
import com.aresstack.askai.research.domain.SourceCapture;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Captures → sentences → semantic passages. SOURCE STRUCTURE COMES FIRST: headings, list items, table
 * rows, quotes and code blocks are hard boundaries; only within paragraph-like blocks does the semantic
 * boundary detection refine further. The semantic signal is NOT {@code cos(s[n], s[n+1])} — a single
 * example sentence would split a section — but the similarity of a LEFT vs RIGHT sentence WINDOW (up to
 * {@link #windowSize} sentences each). Minimum/maximum passage sizes prevent mini passages and walls of
 * text. Every finished passage gets its OWN embedding (better than averaging its sentence vectors).
 */
public final class PassageSegmentation {

    /** The result: sentences and passages ready for {@code recordSentences}/{@code recordPassages}. */
    public static final class Result {
        private final List<Sentence> sentences;
        private final List<Passage> passages;
        private final Map<String, EmbeddingPort.EmbeddingVector> passageVectors;

        Result(List<Sentence> sentences, List<Passage> passages,
               Map<String, EmbeddingPort.EmbeddingVector> passageVectors) {
            this.sentences = sentences;
            this.passages = passages;
            this.passageVectors = passageVectors;
        }

        public List<Sentence> getSentences() {
            return sentences;
        }

        public List<Passage> getPassages() {
            return passages;
        }

        /** DERIVED view (rebuildable): passageId → its embedding, for clustering downstream. */
        public Map<String, EmbeddingPort.EmbeddingVector> getPassageVectors() {
            return passageVectors;
        }
    }

    /**
     * How passage boundaries are found. {@link #SEMANTIC} is the historical default: the source
     * STRUCTURE (line breaks / blocks) is always a hard boundary, and paragraph-like blocks are
     * additionally refined by the embedding-window topic-shift detection ("both" in user terms).
     * {@link #STRUCTURE_ONLY} skips the semantic refinement — passages come purely from the
     * breaks/blocks, no per-sentence embedding calls (passage embeddings are still computed, the
     * downstream clustering needs them).
     */
    public enum BoundaryMode {
        SEMANTIC,
        STRUCTURE_ONLY
    }

    private final SentenceSegmentationPort sentenceSegmentation;
    private final EmbeddingPort embeddings;
    private final String segmentationVersion;
    /** The research language whose sentence model drives this segmentation — stamped onto every passage. */
    private final String languageCode;
    private final int windowSize;
    private final double boundaryThreshold;
    private final int minPassageSentences;
    private final int maxPassageSentences;
    private final BoundaryMode boundaryMode;

    public PassageSegmentation(SentenceSegmentationPort sentenceSegmentation, EmbeddingPort embeddings,
                               String segmentationVersion) {
        this(sentenceSegmentation, embeddings, segmentationVersion, 3, 0.35, 2, 8);
    }

    public PassageSegmentation(SentenceSegmentationPort sentenceSegmentation, EmbeddingPort embeddings,
                               String segmentationVersion, int windowSize, double boundaryThreshold,
                               int minPassageSentences, int maxPassageSentences) {
        this(sentenceSegmentation, embeddings, segmentationVersion, "", windowSize, boundaryThreshold,
                minPassageSentences, maxPassageSentences);
    }

    public PassageSegmentation(SentenceSegmentationPort sentenceSegmentation, EmbeddingPort embeddings,
                               String segmentationVersion, String languageCode, int windowSize,
                               double boundaryThreshold, int minPassageSentences, int maxPassageSentences) {
        this(sentenceSegmentation, embeddings, segmentationVersion, languageCode, windowSize,
                boundaryThreshold, minPassageSentences, maxPassageSentences, BoundaryMode.SEMANTIC);
    }

    public PassageSegmentation(SentenceSegmentationPort sentenceSegmentation, EmbeddingPort embeddings,
                               String segmentationVersion, String languageCode, int windowSize,
                               double boundaryThreshold, int minPassageSentences, int maxPassageSentences,
                               BoundaryMode boundaryMode) {
        this.sentenceSegmentation = sentenceSegmentation;
        this.embeddings = embeddings;
        this.segmentationVersion = segmentationVersion == null ? "" : segmentationVersion;
        this.languageCode = languageCode == null ? "" : languageCode;
        this.windowSize = windowSize;
        this.boundaryThreshold = boundaryThreshold;
        this.minPassageSentences = minPassageSentences;
        this.maxPassageSentences = maxPassageSentences;
        this.boundaryMode = boundaryMode == null ? BoundaryMode.SEMANTIC : boundaryMode;
    }

    public Result segment(SourceCapture capture) {
        List<Sentence> allSentences = new ArrayList<Sentence>();
        List<Passage> allPassages = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> passageVectors =
                new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        int ordinal = 0;
        for (SourceCapture.StructuralBlock block : capture.getBlocks()) {
            List<Sentence> blockSentences = new ArrayList<Sentence>();
            for (String text : sentencesOf(block)) {
                int sentenceOrdinal = ordinal++;
                // Deterministic sentence id from its fachliche identity (capture + position).
                blockSentences.add(new Sentence(capture.getCaptureId() + "#s" + sentenceOrdinal,
                        capture.getCaptureId(), block.getBlockId(), sentenceOrdinal, text));
            }
            allSentences.addAll(blockSentences);
            if (blockSentences.isEmpty() || block.getKind() == SourceCapture.BlockKind.HEADING) {
                continue; // headings structure the heading path; they are no passage content
            }
            for (List<Sentence> passageSentences : splitSemantically(blockSentences, block)) {
                StringBuilder text = new StringBuilder();
                List<String> sentenceIds = new ArrayList<String>();
                for (Sentence sentence : passageSentences) {
                    if (text.length() > 0) {
                        text.append(' ');
                    }
                    text.append(sentence.getText());
                    sentenceIds.add(sentence.getSentenceId());
                }
                EmbeddingPort.EmbeddingVector vector = embeddings.embedDocuments(
                        java.util.Collections.singletonList(text.toString())).get(0);
                // Deterministic, version-aware passage id from its fachliche identity (capture + position +
                // segmentation version + embedding model), so the same captureId + pipelineVersion +
                // embeddingFingerprint reproduces exactly the same passage ids (reprocess-idempotent).
                String passageId = capture.getCaptureId() + "#p" + passageSentences.get(0).getOrdinal()
                        + "@" + segmentationVersion + "-" + vector.getModelFingerprint();
                Passage passage = new Passage(passageId, capture.getCaptureId(), sentenceIds,
                        block.getHeadingPath(), text.toString(), vector.getModelFingerprint(),
                        segmentationVersion, languageCode);
                allPassages.add(passage);
                passageVectors.put(passage.getPassageId(), vector);
            }
        }
        return new Result(allSentences, allPassages, passageVectors);
    }

    private List<String> sentencesOf(SourceCapture.StructuralBlock block) {
        switch (block.getKind()) {
            case CODE:
            case TABLE_ROW:
                // Kept whole: code and table rows are never split into pseudo sentences.
                return block.getText().trim().isEmpty()
                        ? java.util.Collections.<String>emptyList()
                        : java.util.Collections.singletonList(block.getText().trim());
            default:
                return sentenceSegmentation.segment(block.getText());
        }
    }

    /** Semantic refinement WITHIN one paragraph-like block (structure is already a hard boundary). */
    private List<List<Sentence>> splitSemantically(List<Sentence> sentences,
                                                   SourceCapture.StructuralBlock block) {
        List<List<Sentence>> passages = new ArrayList<List<Sentence>>();
        if (boundaryMode == BoundaryMode.STRUCTURE_ONLY
                || block.getKind() != SourceCapture.BlockKind.PARAGRAPH
                || sentences.size() <= minPassageSentences) {
            // STRUCTURE_ONLY: the break/block IS the passage — no per-sentence embedding calls.
            passages.add(sentences);
            return passages;
        }
        List<String> texts = new ArrayList<String>();
        for (Sentence sentence : sentences) {
            texts.add(sentence.getText());
        }
        List<EmbeddingPort.EmbeddingVector> vectors = embeddings.embedDocuments(texts);

        List<Sentence> current = new ArrayList<Sentence>();
        for (int i = 0; i < sentences.size(); i++) {
            current.add(sentences.get(i));
            boolean last = i == sentences.size() - 1;
            boolean maxReached = current.size() >= maxPassageSentences;
            boolean semanticBoundary = false;
            if (!last && current.size() >= minPassageSentences
                    && sentences.size() - i - 1 >= minPassageSentences) {
                semanticBoundary = boundaryStrength(vectors, i) < boundaryThreshold;
            }
            if (last || maxReached || semanticBoundary) {
                passages.add(current);
                current = new ArrayList<Sentence>();
            }
        }
        if (!current.isEmpty()) {
            passages.add(current);
        }
        return passages;
    }

    /** Similarity of the windows LEFT of the boundary (…i) and RIGHT of it (i+1…): low = topic shift. */
    private double boundaryStrength(List<EmbeddingPort.EmbeddingVector> vectors, int boundaryAfter) {
        int leftFrom = Math.max(0, boundaryAfter - windowSize + 1);
        int rightTo = Math.min(vectors.size(), boundaryAfter + 1 + windowSize);
        EmbeddingPort.EmbeddingVector left =
                VectorMath.mean(vectors.subList(leftFrom, boundaryAfter + 1));
        EmbeddingPort.EmbeddingVector right =
                VectorMath.mean(vectors.subList(boundaryAfter + 1, rightTo));
        return VectorMath.cosine(left, right);
    }
}
