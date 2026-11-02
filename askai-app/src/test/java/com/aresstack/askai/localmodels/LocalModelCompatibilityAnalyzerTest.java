package com.aresstack.askai.localmodels;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The local-runtime gate: a model is RUNNABLE only from its ACTUAL configuration (bert +
 * BertForSequenceClassification + one label + WordPiece + proven runtime mapping) — never from the
 * repository name alone. bge-reranker/GTE/XLM-R-SentencePiece stay unsupported until a real
 * runtime path is proven.
 */
public class LocalModelCompatibilityAnalyzerTest {

    private final LocalModelCompatibilityAnalyzer analyzer = new LocalModelCompatibilityAnalyzer();

    private static final List<String> ALL_FILES =
            Arrays.asList("config.json", "tokenizer.json", "model.safetensors", "README.md");

    private static final String BERT_CONFIG = "{\"model_type\":\"bert\","
            + "\"architectures\":[\"BertForSequenceClassification\"],"
            + "\"num_labels\":1,\"id2label\":{\"0\":\"LABEL_0\"}}";
    private static final String WORDPIECE_TOKENIZER =
            "{\"model\":{\"type\":\"WordPiece\",\"vocab\":{}}}";

    @Test
    public void msMarcoMiniLmIsSupportedWithRuntimeMapping() {
        LocalModelCompatibilityResult result = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L6-v2", ALL_FILES, BERT_CONFIG,
                WORDPIECE_TOKENIZER);
        assertTrue(result.getReason(), result.isSupported());
        assertEquals("MS_MARCO_MINILM_L6", result.getRuntimeModelId());
        assertEquals("cross-encoder-ms-marco-MiniLM-L-6-v2", result.getRuntimeDirectoryName());
        assertEquals(LocalRuntimeCapability.RERANK, result.getCapability());
    }

    @Test
    public void theRealHuggingFaceIdMapsAndTheFormerTypoIdDoesNot() {
        // Regression (R0.5): the REAL repository is cross-encoder/ms-marco-MiniLM-L6-v2 (no dash
        // between L and 6). The runtime DIRECTORY keeps its own canonical L-6 name — three
        // separate values: HF id, runtime id, runtime directory.
        LocalModelCompatibilityResult real = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L6-v2", ALL_FILES, BERT_CONFIG,
                WORDPIECE_TOKENIZER);
        assertTrue(real.isSupported());
        assertEquals("MS_MARCO_MINILM_L6", real.getRuntimeModelId());
        assertEquals("cross-encoder-ms-marco-MiniLM-L-6-v2", real.getRuntimeDirectoryName());
        LocalModelCompatibilityResult typo = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L-6-v2", ALL_FILES, BERT_CONFIG,
                WORDPIECE_TOKENIZER);
        assertEquals("the former typo id must not silently map anymore",
                LocalModelCompatibilityResult.Status.UNKNOWN_CONFIGURATION, typo.getStatus());
    }

    @Test
    public void bgeRerankerStaysUnsupportedDespiteItsRerankerName() {
        // bge-reranker-base is XLM-RoBERTa with a SentencePiece/Unigram tokenizer — the NAME must
        // not unlock it.
        String xlmrConfig = "{\"model_type\":\"xlm-roberta\","
                + "\"architectures\":[\"XLMRobertaForSequenceClassification\"],\"num_labels\":1}";
        LocalModelCompatibilityResult result = analyzer.analyze("BAAI/bge-reranker-base",
                ALL_FILES, xlmrConfig, "{\"model\":{\"type\":\"Unigram\"}}");
        assertEquals(LocalModelCompatibilityResult.Status.UNSUPPORTED_ARCHITECTURE,
                result.getStatus());
    }

    @Test
    public void sentencePieceTokenizerIsRejectedEvenOnBert() {
        LocalModelCompatibilityResult result = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L6-v2", ALL_FILES, BERT_CONFIG,
                "{\"model\":{\"type\":\"Unigram\"}}");
        assertEquals(LocalModelCompatibilityResult.Status.UNSUPPORTED_TOKENIZER,
                result.getStatus());
        assertTrue(result.getReason().contains("WordPiece"));
    }

    @Test
    public void missingRequiredFilesAreReportedConcretely() {
        LocalModelCompatibilityResult result = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L6-v2",
                Arrays.asList("config.json", "tokenizer.json"), BERT_CONFIG, WORDPIECE_TOKENIZER);
        assertEquals(LocalModelCompatibilityResult.Status.MISSING_REQUIRED_FILES,
                result.getStatus());
        assertTrue(result.getReason().contains("model.safetensors"));
    }

    @Test
    public void unmappedBertModelStaysUnknownConfiguration() {
        // Structurally fine, but no PROVEN runtime mapping — never released by name pattern.
        LocalModelCompatibilityResult result = analyzer.analyze(
                "someone/other-bert-cross-encoder", ALL_FILES, BERT_CONFIG, WORDPIECE_TOKENIZER);
        assertEquals(LocalModelCompatibilityResult.Status.UNKNOWN_CONFIGURATION,
                result.getStatus());
        assertTrue(result.getReason().contains("no proven runtime mapping"));
    }

    @Test
    public void multiLabelClassifierIsNotARerankerHead() {
        String multiLabel = "{\"model_type\":\"bert\","
                + "\"architectures\":[\"BertForSequenceClassification\"],\"num_labels\":3}";
        LocalModelCompatibilityResult result = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L6-v2", ALL_FILES, multiLabel,
                WORDPIECE_TOKENIZER);
        assertEquals(LocalModelCompatibilityResult.Status.UNSUPPORTED_ARCHITECTURE,
                result.getStatus());
    }

    @Test
    public void unreadableConfigurationFailsTyped() {
        LocalModelCompatibilityResult result = analyzer.analyze(
                "cross-encoder/ms-marco-MiniLM-L6-v2", ALL_FILES, null, WORDPIECE_TOKENIZER);
        assertEquals(LocalModelCompatibilityResult.Status.UNKNOWN_CONFIGURATION,
                result.getStatus());
        assertEquals("empty file list never passes",
                LocalModelCompatibilityResult.Status.MISSING_REQUIRED_FILES,
                analyzer.analyze("x/y", Collections.<String>emptyList(), BERT_CONFIG,
                        WORDPIECE_TOKENIZER).getStatus());
    }
}
