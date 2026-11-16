package com.aresstack.askai.localruntime.onnx;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Embeds texts with an installed ONNX model: pure-Java tokenizer from {@code tokenizer.json}, ONNX Runtime encoder,
 * CLS or mean pooling and L2 normalisation as the catalog describes. Pooling adapted from enterprise-ai-client
 * text-local {@code LocalEmbeddingAdapter}. Callers serialise inference per instance.
 */
public final class OnnxTextEmbedder implements AutoCloseable {

    /** Which role the texts play; decides the catalog prefix. */
    public enum Role {
        RAW, QUERY, PASSAGE
    }

    private final OnnxModelDescriptor descriptor;
    private final OnnxRuntimeOptions options;
    private final HfTokenizer tokenizer;
    private final OnnxEncoderSession session;
    private final long padId;
    private final int maxTokens;
    private int dimension;

    private OnnxTextEmbedder(OnnxModelDescriptor descriptor, OnnxRuntimeOptions options, HfTokenizer tokenizer,
                             OnnxEncoderSession session, long padId) {
        this.descriptor = descriptor;
        this.options = options;
        this.tokenizer = tokenizer;
        this.session = session;
        this.padId = padId;
        this.maxTokens = options.effectiveMaxTokens(descriptor.maxTokens());
    }

    /** Load the model files from its installation directory. */
    public static OnnxTextEmbedder load(Path directory, OnnxModelDescriptor descriptor, OnnxRuntimeOptions options)
            throws IOException {
        HfTokenizer tokenizer = OnnxModelFiles.tokenizer(directory);
        long padId = OnnxModelFiles.padId(directory);
        OnnxEncoderSession session = OnnxEncoderSession.open(directory.resolve(OnnxModelDescriptor.LOCAL_GRAPH),
                options);
        return new OnnxTextEmbedder(descriptor, options, tokenizer, session, padId);
    }

    public OnnxModelDescriptor descriptor() {
        return descriptor;
    }

    /** The provider the session really runs on ({@code cpu} or {@code directml}). */
    public String executionProvider() {
        return session.executionProvider();
    }

    /** The vector width, probed with one embedding on first use. */
    public int dimension() throws IOException {
        if (dimension == 0) {
            dimension = embed(List.of("dimension"), Role.RAW).get(0).length;
        }
        return dimension;
    }

    /** Embed the texts in order; the role decides the catalog prefix (E5 {@code query: }/{@code passage: }). */
    public List<float[]> embed(List<String> texts, Role role) throws IOException {
        List<float[]> vectors = new ArrayList<float[]>(texts.size());
        for (int start = 0; start < texts.size(); start += options.batchSize()) {
            List<String> part = texts.subList(start, Math.min(texts.size(), start + options.batchSize()));
            vectors.addAll(embedBatch(part, role));
        }
        if (!vectors.isEmpty()) {
            dimension = vectors.get(0).length;
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> texts, Role role) throws IOException {
        int[][] rows = new int[texts.size()][];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = tokenizer.encodeTruncated(prefixed(texts.get(i), role), maxTokens);
        }
        TokenBatch batch = TokenBatch.pad(rows, null, padId);
        EncoderOutput output = session.run(batch.ids, batch.mask, null);
        List<float[]> vectors = new ArrayList<float[]>(rows.length);
        for (int row = 0; row < rows.length; row++) {
            float[] vector = output.pooled() ? pooledRow(output, row) : pool(output, batch, row, descriptor.pooling());
            if (descriptor.normalize()) {
                normalizeInPlace(vector);
            }
            vectors.add(vector);
        }
        return vectors;
    }

    String prefixed(String text, Role role) {
        switch (role) {
            case QUERY:
                return descriptor.queryPrefix() + text;
            case PASSAGE:
                return descriptor.passagePrefix() + text;
            default:
                return text;
        }
    }

    private static float[] pooledRow(EncoderOutput output, int row) {
        float[] vector = new float[output.width()];
        for (int d = 0; d < vector.length; d++) {
            vector[d] = output.value(row, 0, d);
        }
        return vector;
    }

    static float[] pool(EncoderOutput output, TokenBatch batch, int row, OnnxModelDescriptor.Pooling pooling) {
        int width = output.width();
        float[] vector = new float[width];
        if (pooling == OnnxModelDescriptor.Pooling.CLS) {
            for (int d = 0; d < width; d++) {
                vector[d] = output.value(row, 0, d);
            }
            return vector;
        }
        int count = 0;
        for (int p = 0; p < output.length(); p++) {
            if (batch.mask[row][p] == 0) {
                continue;
            }
            count++;
            for (int d = 0; d < width; d++) {
                vector[d] += output.value(row, p, d);
            }
        }
        if (count > 0) {
            for (int d = 0; d < width; d++) {
                vector[d] /= count;
            }
        }
        return vector;
    }

    static void normalizeInPlace(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += value * value;
        }
        double norm = Math.sqrt(sum);
        if (norm > 1e-12) {
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) (vector[i] / norm);
            }
        }
    }

    @Override
    public void close() {
        session.close();
    }
}
