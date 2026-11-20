package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor;
import com.aresstack.askai.localmodels.AskAiOllamaModelDigestLookup;
import com.aresstack.askai.localmodels.HttpEmbeddingDimensionProbe;
import com.aresstack.askai.localmodels.OllamaEmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.processing.embedding.HttpEmbeddingPortAdapter;
import com.aresstack.askai.research.knowledge.processing.embedding.UrlConnectionEmbeddingHttpTransport;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Embedding ports of the writer A/B, resolved the way the host resolves an Ollama embedding model (installed digest,
 * probed dimension) and served by the productive batch adapter, so their vectors live in the app's world.
 */
final class WriterAbEmbeddings {

    private WriterAbEmbeddings() {
    }

    /** The host's descriptor of an installed Ollama embedding model. */
    static EmbeddingEndpointDescriptor descriptor(final String ollama, String model, long timeoutMillis)
            throws EmbeddingConfigurationException {
        return new OllamaEmbeddingConfigurationSnapshotProvider(
                new OllamaEmbeddingConfigurationSnapshotProvider.OllamaEndpoint() {
                    public String baseUrl() {
                        return ollama;
                    }
                }, new AskAiOllamaModelDigestLookup(), new HttpEmbeddingDimensionProbe((int) timeoutMillis),
                timeoutMillis).prepareForSession("writer-ab", new File("."), model).descriptor;
    }

    static HttpEmbeddingPortAdapter port(EmbeddingEndpointDescriptor descriptor) {
        return new HttpEmbeddingPortAdapter(descriptor,
                new UrlConnectionEmbeddingHttpTransport((int) descriptor.timeoutMillis));
    }

    /** Sentence vectors as documents, in batches, as a function for the quality figures. */
    static Function<List<String>, List<float[]>> documents(final EmbeddingPort port) {
        return new Function<List<String>, List<float[]>>() {
            public List<float[]> apply(List<String> texts) {
                List<float[]> out = new ArrayList<float[]>(texts.size());
                for (int from = 0; from < texts.size(); from += 64) {
                    for (EmbeddingPort.EmbeddingVector vector : port.embed(EmbeddingPort.Role.DOCUMENT,
                            texts.subList(from, Math.min(texts.size(), from + 64)))) {
                        out.add(vector.getValues());
                    }
                }
                return out;
            }
        };
    }
}
