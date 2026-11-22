package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.reranker.RerankerCapability;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationException;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor;
import com.aresstack.askai.agent.model.reranker.RerankerProvider;
import com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics;
import com.aresstack.askai.agent.model.reranker.RerankerSelectionConfiguration;
import com.aresstack.askai.modelport.ModelProviderSettings;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The reranker of a session: the API's {@code rerank} role on {@code POST <base>/rerank} while the OpenAI-compatible
 * API is chosen (as Space Harvester's {@code KipitzRerankAdapter} does), otherwise AskAI's local runtime
 * ({@code local}, may be {@code null} where there is none). The API arm writes the same per-session snapshot file;
 * its descriptor is secret-free, the key travels per request. Scores are read as raw logits: only their order counts,
 * the selection keeps the top {@value LocalRerankerEndpointDescriptorFactory#DEFAULT_MAXIMUM_SELECTED_CANDIDATES}.
 */
public final class ApiAwareRerankerSnapshotProvider implements RerankerConfigurationSnapshotProvider {

    private static final String SNAPSHOT_FILE_NAME = "reranker-config.json";

    private final RerankerConfigurationSnapshotProvider local;
    private final Supplier<ModelProviderSettings> settings;
    private final LongSupplier timeoutMillis;

    public ApiAwareRerankerSnapshotProvider(RerankerConfigurationSnapshotProvider local,
                                            Supplier<ModelProviderSettings> settings, LongSupplier timeoutMillis) {
        this.local = local;
        this.settings = settings;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                           String selectedModel)
            throws RerankerConfigurationException {
        ModelProviderSettings current = settings.get();
        if (current.usesApi()) {
            return api(current, sessionDirectory);
        }
        if (local == null) {
            throw new RerankerConfigurationException("No reranker is available: AskAI's local model runtime is "
                    + "missing and the OpenAI-compatible API is not chosen.");
        }
        return local.prepareForSession(sessionId, sessionDirectory, selectedModel);
    }

    @Override
    public RerankerConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                           String selectedModel, String languageCode)
            throws RerankerConfigurationException {
        ModelProviderSettings current = settings.get();
        if (current.usesApi() || local == null) {
            return prepareForSession(sessionId, sessionDirectory, selectedModel);
        }
        return local.prepareForSession(sessionId, sessionDirectory, selectedModel, languageCode);
    }

    private RerankerConfigurationSnapshot api(ModelProviderSettings current, File sessionDirectory)
            throws RerankerConfigurationException {
        String baseUrl = current.getApiBaseUrl();
        String model = current.apiRoleModel(ModelProviderSettings.ROLE_RERANK);
        if (baseUrl.isEmpty() || model.isEmpty()) {
            throw new RerankerConfigurationException("The OpenAI-compatible API is chosen but has no "
                    + (baseUrl.isEmpty() ? "base URL" : "reranker model") + "; set it under Settings -> AI models "
                    + "-> Model provider.");
        }
        RerankerConfigurationDocument document = RerankerConfigurationDocument.current(0L,
                new RerankerEndpointDescriptor(RerankerProvider.OPENAI_COMPATIBLE, baseUrl, model,
                        Arrays.asList(RerankerCapability.RERANK), RerankerScoreSemantics.RAW_LOGIT,
                        timeoutMillis.getAsLong(), RerankerSelectionConfiguration.topN(
                        LocalRerankerEndpointDescriptorFactory.DEFAULT_MAXIMUM_SELECTED_CANDIDATES)));
        File target = new File(sessionDirectory, SNAPSHOT_FILE_NAME);
        try {
            LocalRerankerConfigurationSnapshotWriter.write(document, target);
        } catch (IOException ex) {
            throw new RerankerConfigurationException("The reranker session snapshot could not be written to "
                    + target + ": " + ex.getMessage(), ex);
        }
        System.err.println("[reranker] OpenAI-compatible API model=" + model);
        return new RerankerConfigurationSnapshot(target.getAbsoluteFile(), document);
    }
}
