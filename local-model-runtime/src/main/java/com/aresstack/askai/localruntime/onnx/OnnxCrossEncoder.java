package com.aresstack.askai.localruntime.onnx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Scores (query, document) pairs with an installed ONNX cross-encoder: the pair template of the tokenizer
 * (BERT {@code [CLS] q [SEP] d [SEP]} with token types, XLM-R {@code <s> q </s></s> d </s>}), one logit per pair.
 * Callers serialise inference per instance.
 */
public final class OnnxCrossEncoder implements AutoCloseable {

    /** One scored document. Immutable. */
    public record Scored(int index, String document, float score) {
    }

    private final OnnxModelDescriptor descriptor;
    private final OnnxRuntimeOptions options;
    private final HfTokenizer tokenizer;
    private final OnnxEncoderSession session;
    private final long padId;
    private final int maxTokens;

    private OnnxCrossEncoder(OnnxModelDescriptor descriptor, OnnxRuntimeOptions options, HfTokenizer tokenizer,
                             OnnxEncoderSession session, long padId) {
        this.descriptor = descriptor;
        this.options = options;
        this.tokenizer = tokenizer;
        this.session = session;
        this.padId = padId;
        this.maxTokens = options.effectiveMaxTokens(descriptor.maxTokens());
    }

    public static OnnxCrossEncoder load(Path directory, OnnxModelDescriptor descriptor, OnnxRuntimeOptions options)
            throws IOException {
        HfTokenizer tokenizer = OnnxModelFiles.tokenizer(directory);
        long padId = OnnxModelFiles.padId(directory);
        OnnxEncoderSession session = OnnxEncoderSession.open(directory.resolve(OnnxModelDescriptor.LOCAL_GRAPH),
                options);
        return new OnnxCrossEncoder(descriptor, options, tokenizer, session, padId);
    }

    public OnnxModelDescriptor descriptor() {
        return descriptor;
    }

    public String executionProvider() {
        return session.executionProvider();
    }

    /**
     * Score every document against the query, best first; {@code topN <= 0} keeps all.
     */
    public List<Scored> rerank(String query, List<String> documents, int topN) throws IOException {
        List<Scored> scored = new ArrayList<Scored>(documents.size());
        for (int start = 0; start < documents.size(); start += options.batchSize()) {
            int end = Math.min(documents.size(), start + options.batchSize());
            int[][] ids = new int[end - start][];
            int[][] types = new int[end - start][];
            for (int i = start; i < end; i++) {
                HfTokenizer.PairEncoding pair = tokenizer.encodePair(query, documents.get(i), maxTokens);
                ids[i - start] = pair.ids;
                types[i - start] = pair.typeIds;
            }
            TokenBatch batch = TokenBatch.pad(ids, types, padId);
            EncoderOutput output = session.run(batch.ids, batch.mask, batch.typeIds);
            for (int row = 0; row < ids.length; row++) {
                scored.add(new Scored(start + row, documents.get(start + row), output.value(row, 0, 0)));
            }
        }
        scored.sort((a, b) -> Float.compare(b.score(), a.score()));
        if (topN > 0 && topN < scored.size()) {
            return Collections.unmodifiableList(new ArrayList<Scored>(scored.subList(0, topN)));
        }
        return Collections.unmodifiableList(scored);
    }

    @Override
    public void close() {
        session.close();
    }
}
