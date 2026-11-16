package com.aresstack.askai.localruntime.onnx;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OnnxTokenizerAndPoolingTest {

    /** A tiny SentencePiece/Unigram tokenizer in the XLM-R shape (bge-m3, e5-base-sts-en-de). */
    private static final String XLMR = "{"
            + "\"added_tokens\":["
            + "{\"id\":0,\"content\":\"<s>\",\"special\":true},"
            + "{\"id\":1,\"content\":\"<pad>\",\"special\":true},"
            + "{\"id\":2,\"content\":\"</s>\",\"special\":true},"
            + "{\"id\":3,\"content\":\"<unk>\",\"special\":true}],"
            + "\"normalizer\":null,"
            + "\"pre_tokenizer\":{\"type\":\"Metaspace\",\"replacement\":\"\\u2581\",\"prepend_scheme\":\"always\"},"
            + "\"post_processor\":{\"type\":\"RobertaProcessing\",\"sep\":[\"</s>\",2],\"cls\":[\"<s>\",0]},"
            + "\"model\":{\"type\":\"Unigram\",\"unk_id\":3,\"vocab\":["
            + "[\"<s>\",0.0],[\"<pad>\",0.0],[\"</s>\",0.0],[\"<unk>\",0.0],"
            + "[\"\\u2581Helm\",-2.0],[\"\\u2581pflicht\",-3.0],[\"\\u2581Helmpflicht\",-4.0],[\"\\u2581Bau\",-2.5],"
            + "[\"stelle\",-3.0],[\"\\u2581\",-1.0]]}}";

    /** A tiny WordPiece tokenizer in the BERT shape with a TemplateProcessing pair (ms-marco cross-encoder). */
    private static final String BERT = "{"
            + "\"added_tokens\":["
            + "{\"id\":0,\"content\":\"[PAD]\",\"special\":true},"
            + "{\"id\":1,\"content\":\"[UNK]\",\"special\":true},"
            + "{\"id\":2,\"content\":\"[CLS]\",\"special\":true},"
            + "{\"id\":3,\"content\":\"[SEP]\",\"special\":true}],"
            + "\"normalizer\":{\"type\":\"BertNormalizer\",\"lowercase\":true},"
            + "\"pre_tokenizer\":{\"type\":\"BertPreTokenizer\"},"
            + "\"post_processor\":{\"type\":\"TemplateProcessing\","
            + "\"single\":[{\"SpecialToken\":{\"id\":\"[CLS]\",\"type_id\":0}},{\"Sequence\":{\"id\":\"A\",\"type_id\":0}},"
            + "{\"SpecialToken\":{\"id\":\"[SEP]\",\"type_id\":0}}],"
            + "\"pair\":[{\"SpecialToken\":{\"id\":\"[CLS]\",\"type_id\":0}},{\"Sequence\":{\"id\":\"A\",\"type_id\":0}},"
            + "{\"SpecialToken\":{\"id\":\"[SEP]\",\"type_id\":0}},{\"Sequence\":{\"id\":\"B\",\"type_id\":1}},"
            + "{\"SpecialToken\":{\"id\":\"[SEP]\",\"type_id\":1}}],"
            + "\"special_tokens\":{\"[CLS]\":{\"id\":\"[CLS]\",\"ids\":[2]},\"[SEP]\":{\"id\":\"[SEP]\",\"ids\":[3]}}},"
            + "\"model\":{\"type\":\"WordPiece\",\"unk_token\":\"[UNK]\",\"continuing_subword_prefix\":\"##\","
            + "\"vocab\":{\"[PAD]\":0,\"[UNK]\":1,\"[CLS]\":2,\"[SEP]\":3,\"helmet\":4,\"rules\":5,\"site\":6,\"##s\":7}}}";

    @Test
    public void unigramSingleUsesRobertaSpecials() throws Exception {
        HfTokenizer tokenizer = HfTokenizer.parse(XLMR);
        int[] ids = tokenizer.encode("Helmpflicht Baustelle", true);
        assertEquals(0, ids[0]);
        assertEquals(2, ids[ids.length - 1]);
        assertTrue("Viterbi picks the whole piece", Arrays.stream(ids).anyMatch(id -> id == 6));
    }

    @Test
    public void unigramPairUsesDoubleSeparator() throws Exception {
        HfTokenizer tokenizer = HfTokenizer.parse(XLMR);
        HfTokenizer.PairEncoding pair = tokenizer.encodePair("Helm", "Bau", 64);
        assertArrayEquals(new int[]{0, 4, 2, 2, 7, 2}, pair.ids);
        assertArrayEquals(new int[]{0, 0, 0, 0, 0, 0}, pair.typeIds);
    }

    @Test
    public void wordPiecePairCarriesTokenTypes() throws Exception {
        HfTokenizer tokenizer = HfTokenizer.parse(BERT);
        HfTokenizer.PairEncoding pair = tokenizer.encodePair("Helmet rules", "sites", 64);
        assertArrayEquals(new int[]{2, 4, 5, 3, 6, 7, 3}, pair.ids);
        assertArrayEquals(new int[]{0, 0, 0, 0, 1, 1, 1}, pair.typeIds);
    }

    @Test
    public void pairIsCutToTheLimitLongestSideFirst() throws Exception {
        HfTokenizer tokenizer = HfTokenizer.parse(BERT);
        HfTokenizer.PairEncoding pair = tokenizer.encodePair("helmet", "site site site site", 6);
        assertEquals(6, pair.ids.length);
        assertEquals(4, pair.ids[1]);
    }

    @Test
    public void clsPoolingTakesTheFirstTokenAndMeanSkipsPadding() {
        // 1 row, 3 positions (last one padding), width 2
        EncoderOutput output = EncoderOutput.perToken(new float[]{1, 2, 3, 4, 100, 100}, 1, 3, 2);
        TokenBatch batch = TokenBatch.pad(new int[][]{{5, 6}}, null, 1);
        // pad to length 3 by hand: TokenBatch pads to the longest row, so build a 3-wide batch
        TokenBatch wide = TokenBatch.pad(new int[][]{{5, 6}, {5, 6, 7}}, null, 1);
        assertEquals(2, batch.ids[0].length);
        assertArrayEquals(new float[]{1, 2},
                OnnxTextEmbedder.pool(output, wide, 0, OnnxModelDescriptor.Pooling.CLS), 0f);
        assertArrayEquals(new float[]{2, 3},
                OnnxTextEmbedder.pool(output, wide, 0, OnnxModelDescriptor.Pooling.MEAN), 0f);
        assertEquals(1L, wide.ids[0][2]);
        assertEquals(0L, wide.mask[0][2]);
    }

    @Test
    public void normalisationProducesUnitVectors() {
        float[] vector = {3, 4};
        OnnxTextEmbedder.normalizeInPlace(vector);
        assertArrayEquals(new float[]{0.6f, 0.8f}, vector, 1e-6f);
    }
}
