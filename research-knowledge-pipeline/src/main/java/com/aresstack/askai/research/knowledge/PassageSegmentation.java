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
    private final int maxEmbeddingChars;

    /**
     * PR 20: the default text budget of one embedding input, in characters. It keeps a passage inside the
     * smallest context of the supported embedding models (512 tokens of the local runtime's E5 models, about
     * two characters per token as a conservative bound for code and tables, with room for the role prefix), because embedding calls send {@code truncate=false}.
     */
    public static final int DEFAULT_MAX_EMBEDDING_CHARS = 1000;

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
        this(sentenceSegmentation, embeddings, segmentationVersion, languageCode, windowSize, boundaryThreshold,
                minPassageSentences, maxPassageSentences, boundaryMode, DEFAULT_MAX_EMBEDDING_CHARS);
    }

    public PassageSegmentation(SentenceSegmentationPort sentenceSegmentation, EmbeddingPort embeddings,
                               String segmentationVersion, String languageCode, int windowSize,
                               double boundaryThreshold, int minPassageSentences, int maxPassageSentences,
                               BoundaryMode boundaryMode, int maxEmbeddingChars) {
        if (maxEmbeddingChars < 100) {
            throw new IllegalArgumentException("embedding budget too small: " + maxEmbeddingChars);
        }
        this.maxEmbeddingChars = maxEmbeddingChars;
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
            for (List<Sentence> semantic : splitSemantically(blockSentences, block)) {
                for (Piece piece : fitToBudget(semantic)) {
                    EmbeddingPort.EmbeddingVector vector = embeddings.embedDocuments(
                            java.util.Collections.singletonList(piece.text)).get(0);
                    // Deterministic, version-aware passage id from its fachliche identity (capture + position +
                    // segmentation version + embedding model), so the same captureId + pipelineVersion +
                    // embeddingFingerprint reproduces exactly the same passage ids (reprocess-idempotent). A
                    // sentence cut to the budget adds the zero-padded piece number after "~", which sorts after
                    // "@", so the pieces keep their order in an id sort.
                    String passageId = capture.getCaptureId() + "#p" + piece.firstOrdinal
                            + (piece.part > 0 ? String.format(java.util.Locale.ROOT, "~%03d", piece.part) : "")
                            + "@" + segmentationVersion + "-" + vector.getModelFingerprint();
                    Passage passage = new Passage(passageId, capture.getCaptureId(), piece.sentenceIds,
                            block.getHeadingPath(), piece.text, vector.getModelFingerprint(),
                            segmentationVersion, languageCode);
                    allPassages.add(passage);
                    passageVectors.put(passage.getPassageId(), vector);
                }
            }
        }
        return new Result(allSentences, allPassages, passageVectors);
    }

    /** One embedding input: whole sentences, or one piece of a sentence longer than the budget. */
    private static final class Piece {
        final int firstOrdinal;
        final int part;
        final List<String> sentenceIds;
        final String text;

        Piece(int firstOrdinal, int part, List<String> sentenceIds, String text) {
            this.firstOrdinal = firstOrdinal;
            this.part = part;
            this.sentenceIds = sentenceIds;
            this.text = text;
        }
    }

    /**
     * PR 20: splits a passage longer than the embedding budget before it is embedded. Whole sentences are
     * grouped greedily; a single sentence above the budget (a code block or table row is one "sentence") is cut
     * at whitespace into numbered pieces that all name that sentence.
     */
    private List<Piece> fitToBudget(List<Sentence> sentences) {
        List<Piece> pieces = new ArrayList<Piece>();
        StringBuilder text = new StringBuilder();
        List<String> ids = new ArrayList<String>();
        int first = -1;
        for (Sentence sentence : sentences) {
            String sentenceText = sentence.getText();
            if (sentenceText.length() > maxEmbeddingChars) {
                if (text.length() > 0) {
                    pieces.add(new Piece(first, 0, ids, text.toString()));
                    text = new StringBuilder();
                    ids = new ArrayList<String>();
                }
                int part = 0;
                for (String chunk : cut(sentenceText, maxEmbeddingChars)) {
                    pieces.add(new Piece(sentence.getOrdinal(), part++,
                            java.util.Collections.singletonList(sentence.getSentenceId()), chunk));
                }
                continue;
            }
            int joined = text.length() == 0 ? sentenceText.length() : text.length() + 1 + sentenceText.length();
            if (text.length() > 0 && joined > maxEmbeddingChars) {
                pieces.add(new Piece(first, 0, ids, text.toString()));
                text = new StringBuilder();
                ids = new ArrayList<String>();
            }
            if (text.length() == 0) {
                first = sentence.getOrdinal();
            } else {
                text.append(' ');
            }
            text.append(sentenceText);
            ids.add(sentence.getSentenceId());
        }
        if (text.length() > 0) {
            pieces.add(new Piece(first, 0, ids, text.toString()));
        }
        return pieces;
    }

    /** Cuts a text into pieces of at most {@code budget} characters, preferring the last whitespace. */
    static List<String> cut(String text, int budget) {
        List<String> chunks = new ArrayList<String>();
        int from = 0;
        while (from < text.length()) {
            int to = Math.min(text.length(), from + budget);
            if (to < text.length()) {
                int space = text.lastIndexOf(' ', to);
                if (space > from + budget / 2) {
                    to = space;
                }
            }
            String chunk = text.substring(from, to).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            from = to;
        }
        return chunks;
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
            // truncate=false: boundary detection embeds at most the budget of each sentence
            String text = sentence.getText();
            texts.add(text.length() > maxEmbeddingChars ? text.substring(0, maxEmbeddingChars) : text);
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
