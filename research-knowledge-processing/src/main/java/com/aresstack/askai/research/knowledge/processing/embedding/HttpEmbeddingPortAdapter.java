package com.aresstack.askai.research.knowledge.processing.embedding;

import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The productive {@link EmbeddingPort}: it POSTs a BATCH {@code /api/embed} request built from the injected
 * {@link EmbeddingEndpointDescriptor} and validates the response STRICTLY. It knows ONLY the descriptor — no
 * host runtime manager, no model catalog, no AskAI settings, no Swing. Batch in / batch out. Each call's
 * {@link EmbeddingPort.Role} selects the model card's prompt ({@link EmbeddingPromptScheme}): as text for Ollama
 * models, through {@code input_type} for the local runtime's E5 models, nothing for raw calls. There is NO zero-vector fallback, NO retry on another model, and NO legacy
 * {@code /api/embeddings} path — a malformed or inconsistent response fails hard (a retryable EMBEDDING error).
 *
 * <p>Every returned vector is tagged with the descriptor's {@link EmbeddingEndpointDescriptor#embeddingFingerprint()}
 * combined with the applied prompt scheme ({@link #fingerprint(EmbeddingPort.Role)}), so downstream passages and the
 * reprocessing key are version-aware and raw vectors are never compared with prompted ones.</p>
 */
public final class HttpEmbeddingPortAdapter implements EmbeddingPort {

    private final EmbeddingEndpointDescriptor descriptor;
    private final EmbeddingHttpTransport transport;
    private final EmbeddingPromptScheme scheme;

    public HttpEmbeddingPortAdapter(EmbeddingEndpointDescriptor descriptor, EmbeddingHttpTransport transport) {
        this.descriptor = descriptor;
        this.transport = transport;
        this.scheme = EmbeddingPromptScheme.forModel(descriptor.modelId);
    }

    /** The prompt scheme of the descriptor's model. */
    public EmbeddingPromptScheme scheme() {
        return scheme;
    }

    /** The fingerprint that vectors of this role carry (documents and queries share it; raw keeps the base). */
    public String fingerprint(EmbeddingPort.Role role) {
        return scheme.effective(role).fingerprint(descriptor.embeddingFingerprint());
    }

    /** The fingerprint of document (and query) vectors of a descriptor: the knowledge pipeline's vector world. */
    public static String documentFingerprint(EmbeddingEndpointDescriptor descriptor) {
        return EmbeddingPromptScheme.forModel(descriptor.modelId).fingerprint(descriptor.embeddingFingerprint());
    }

    @Override
    public List<EmbeddingVector> embed(EmbeddingPort.Role role, List<String> texts) {
        if (role == null) {
            throw new IllegalArgumentException("every embedding call states its role");
        }
        List<String> inputs = texts == null ? new ArrayList<String>() : texts;
        List<String> prompted = new ArrayList<String>(inputs.size());
        for (String text : inputs) {
            prompted.add(scheme.apply(role, text));
        }
        String body = buildRequest(descriptor.modelId, prompted, scheme.inputType(role));
        String response;
        try {
            response = transport.post(descriptor.endpointUrl(), body);
        } catch (IOException ex) {
            throw new EmbeddingException("embedding request to " + descriptor.endpointUrl()
                    + " failed: " + ex.getMessage(), ex);
        }
        List<float[]> matrix = parseEmbeddingMatrix(response);
        if (matrix.size() != inputs.size()) {
            throw new EmbeddingException("embedding count " + matrix.size()
                    + " does not match input count " + inputs.size());
        }
        String fingerprint = fingerprint(role);
        List<EmbeddingVector> vectors = new ArrayList<EmbeddingVector>();
        for (float[] values : matrix) {
            validateVector(values);
            vectors.add(new EmbeddingVector(descriptor.modelId, fingerprint, values));
        }
        return vectors;
    }

    private void validateVector(float[] values) {
        if (values.length == 0) {
            throw new EmbeddingException("empty embedding vector");
        }
        if (values.length != descriptor.embeddingDimension) {
            throw new EmbeddingException("embedding dimension " + values.length
                    + " does not match the descriptor's " + descriptor.embeddingDimension);
        }
        for (float f : values) {
            if (Float.isNaN(f) || Float.isInfinite(f)) {
                throw new EmbeddingException("embedding vector holds a non-finite value");
            }
        }
    }

    // ------------------------------------------------------------------ request/response wire (testable)

    static String buildRequest(String modelId, List<String> inputs) {
        return buildRequest(modelId, inputs, "raw");
    }

    static String buildRequest(String modelId, List<String> inputs, String inputType) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":").append(jsonString(modelId)).append(",\"input\":[");
        for (int i = 0; i < inputs.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonString(inputs.get(i)));
        }
        // PR 20: never let the runtime cut a text silently; passages are split to fit before embedding.
        sb.append("],\"input_type\":").append(jsonString(inputType)).append(",\"truncate\":false}");
        return sb.toString();
    }

    /** Parse the {@code "embeddings"} numeric matrix (a list of number arrays) from an /api/embed response. */
    static List<float[]> parseEmbeddingMatrix(String json) {
        if (json == null) {
            throw new EmbeddingException("empty embedding response");
        }
        int key = json.indexOf("\"embeddings\"");
        if (key < 0) {
            throw new EmbeddingException("embedding response has no 'embeddings' field");
        }
        int colon = json.indexOf(':', key + "\"embeddings\"".length());
        int start = colon < 0 ? -1 : json.indexOf('[', colon);
        if (start < 0) {
            throw new EmbeddingException("embedding response 'embeddings' is not an array");
        }
        try {
            int[] pos = {start + 1};
            List<float[]> rows = new ArrayList<float[]>();
            skipWs(json, pos);
            if (json.charAt(pos[0]) == ']') {
                return rows; // empty matrix (caller rejects the count)
            }
            while (true) {
                skipWs(json, pos);
                if (json.charAt(pos[0]) != '[') {
                    throw new EmbeddingException("expected a vector array in 'embeddings'");
                }
                rows.add(readRow(json, pos));
                skipWs(json, pos);
                char c = json.charAt(pos[0]++);
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw new EmbeddingException("malformed 'embeddings' array");
            }
            return rows;
        } catch (IndexOutOfBoundsException | NumberFormatException malformed) {
            throw new EmbeddingException("malformed embedding response", malformed);
        }
    }

    private static float[] readRow(String json, int[] pos) {
        pos[0]++; // consume '['
        List<Float> row = new ArrayList<Float>();
        skipWs(json, pos);
        if (json.charAt(pos[0]) == ']') {
            pos[0]++;
            return new float[0];
        }
        while (true) {
            skipWs(json, pos);
            row.add(readNumber(json, pos));
            skipWs(json, pos);
            char c = json.charAt(pos[0]++);
            if (c == ',') {
                continue;
            }
            if (c == ']') {
                break;
            }
            throw new EmbeddingException("malformed vector array");
        }
        float[] values = new float[row.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = row.get(i);
        }
        return values;
    }

    private static float readNumber(String json, int[] pos) {
        int begin = pos[0];
        while (pos[0] < json.length()) {
            char c = json.charAt(pos[0]);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                pos[0]++;
            } else {
                break;
            }
        }
        if (pos[0] == begin) {
            throw new EmbeddingException("expected a number in a vector");
        }
        return Float.parseFloat(json.substring(begin, pos[0]));
    }

    private static void skipWs(String json, int[] pos) {
        while (pos[0] < json.length() && Character.isWhitespace(json.charAt(pos[0]))) {
            pos[0]++;
        }
    }

    private static String jsonString(String value) {
        String v = value == null ? "" : value;
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
