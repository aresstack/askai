package com.aresstack.askai.localruntime.onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * One loaded ONNX encoder graph. Inputs by name: {@code input_ids}, {@code attention_mask}, {@code token_type_ids},
 * {@code position_ids}. Output: a pooled sentence vector ({@code sentence_embedding}, {@code dense_vecs}) when the
 * export computes it, otherwise the per-token states ({@code last_hidden_state}/{@code token_embeddings}), otherwise
 * the first output (cross-encoder {@code logits}). Adapted from enterprise-ai-client text-onnx
 * {@code OnnxTextEncoderRuntime}; the session options come from {@link OnnxRuntimeOptions}.
 */
final class OnnxEncoderSession implements AutoCloseable {

    private final OrtEnvironment environment;
    private final OrtSession session;
    private final String executionProvider;

    private OnnxEncoderSession(OrtEnvironment environment, OrtSession session, String executionProvider) {
        this.environment = environment;
        this.session = session;
        this.executionProvider = executionProvider;
    }

    static OnnxEncoderSession open(Path graph, OnnxRuntimeOptions options) throws IOException {
        if (!Files.isRegularFile(graph)) {
            throw new IOException("ONNX graph missing: " + graph);
        }
        try {
            OrtEnvironment environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions sessionOptions = new OrtSession.SessionOptions();
            try {
                if (options.intraOpThreads() > 0) {
                    sessionOptions.setIntraOpNumThreads(options.intraOpThreads());
                }
                if (options.interOpThreads() > 0) {
                    sessionOptions.setInterOpNumThreads(options.interOpThreads());
                }
                sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                String provider = OnnxRuntimeOptions.ExecutionProvider.CPU.token();
                if (options.executionProvider() == OnnxRuntimeOptions.ExecutionProvider.DIRECTML) {
                    try {
                        sessionOptions.addDirectML(0);
                        provider = OnnxRuntimeOptions.ExecutionProvider.DIRECTML.token();
                    } catch (OrtException | RuntimeException | LinkageError unavailable) {
                        System.err.println("[local-runtime] DirectML provider unavailable in this ONNX Runtime build,"
                                + " using the CPU: " + unavailable.getMessage());
                    }
                }
                OrtSession session = environment.createSession(graph.toAbsolutePath().toString(), sessionOptions);
                return new OnnxEncoderSession(environment, session, provider);
            } finally {
                sessionOptions.close();
            }
        } catch (OrtException failed) {
            throw new IOException("ONNX Runtime cannot load " + graph.getFileName() + ": " + failed.getMessage(),
                    failed);
        } catch (UnsatisfiedLinkError | ExceptionInInitializerError | NoClassDefFoundError nativeFailure) {
            throw new IOException("ONNX Runtime did not start: " + nativeFailure, nativeFailure);
        }
    }

    /** The provider the session really runs on. */
    String executionProvider() {
        return executionProvider;
    }

    /** Run one padded batch. {@code typeIds} may be {@code null} (zeros). */
    EncoderOutput run(long[][] ids, long[][] mask, long[][] typeIds) throws IOException {
        int rows = ids.length;
        int length = rows == 0 ? 0 : ids[0].length;
        long[] shape = {rows, length};
        Map<String, OnnxTensor> inputs = new HashMap<String, OnnxTensor>();
        try {
            Set<String> names = session.getInputNames();
            if (!names.contains("input_ids")) {
                throw new IOException("graph is not a text encoder (inputs " + names + ")");
            }
            inputs.put("input_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(flat(ids)), shape));
            if (names.contains("attention_mask")) {
                inputs.put("attention_mask", OnnxTensor.createTensor(environment, LongBuffer.wrap(flat(mask)), shape));
            }
            if (names.contains("token_type_ids")) {
                long[] types = typeIds == null ? new long[rows * length] : flat(typeIds);
                inputs.put("token_type_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(types), shape));
            }
            if (names.contains("position_ids")) {
                long[] positions = new long[rows * length];
                for (int i = 0; i < positions.length; i++) {
                    positions[i] = i % length;
                }
                inputs.put("position_ids", OnnxTensor.createTensor(environment, LongBuffer.wrap(positions), shape));
            }
            for (String name : names) {
                if (!inputs.containsKey(name)) {
                    throw new IOException("graph needs the unsupported input " + name);
                }
            }
            try (OrtSession.Result result = session.run(inputs)) {
                return output(result, session.getOutputNames(), rows, length);
            }
        } catch (OrtException failed) {
            throw new IOException("ONNX Runtime: " + failed.getMessage(), failed);
        } finally {
            for (OnnxTensor tensor : inputs.values()) {
                tensor.close();
            }
        }
    }

    private static EncoderOutput output(OrtSession.Result result, Set<String> names, int rows, int length)
            throws IOException {
        String chosen = null;
        for (String candidate : new String[]{"sentence_embedding", "dense_vecs", "last_hidden_state",
                "token_embeddings", "logits"}) {
            if (names.contains(candidate)) {
                chosen = candidate;
                break;
            }
        }
        OnnxValue value = chosen == null ? result.get(0) : result.get(chosen).orElse(result.get(0));
        if (!(value instanceof OnnxTensor tensor)) {
            throw new IOException("encoder output is not a tensor");
        }
        long[] shape = tensor.getInfo().getShape();
        FloatBuffer buffer = tensor.getFloatBuffer();
        if (buffer == null) {
            throw new IOException("encoder output is not float (" + tensor.getInfo() + ")");
        }
        float[] values = new float[buffer.remaining()];
        buffer.get(values);
        if (shape.length == 2 && shape[0] == rows) {
            return EncoderOutput.pooled(values, rows, (int) shape[1]);
        }
        if (shape.length == 1 && shape[0] == rows) {
            return EncoderOutput.pooled(values, rows, 1);
        }
        if (shape.length == 3 && shape[0] == rows && shape[1] == length) {
            return EncoderOutput.perToken(values, rows, length, (int) shape[2]);
        }
        throw new IOException("unexpected encoder output shape " + java.util.Arrays.toString(shape));
    }

    private static long[] flat(long[][] matrix) {
        int rows = matrix.length;
        int length = rows == 0 ? 0 : matrix[0].length;
        long[] out = new long[rows * length];
        for (int r = 0; r < rows; r++) {
            System.arraycopy(matrix[r], 0, out, r * length, length);
        }
        return out;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException ignored) {
            // closing anyway
        }
    }
}
