package com.aresstack.askai.java8.plugin.host;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.solon.SolonMcpServerRuntime;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationException;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshot;
import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.nlp.NlpModelCatalog;
import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.agent.model.reranker.RerankerModelCatalog;
import com.aresstack.askai.agent.model.session.ActiveResearchSessionRegistry;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.localmodels.HttpEmbeddingDimensionProbe;
import com.aresstack.askai.java8.localmodels.LocalActiveResearchSessionRegistry;
import com.aresstack.askai.java8.localmodels.LocalEmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalEmbeddingModelCatalog;
import com.aresstack.askai.java8.localmodels.LocalEmbeddingRuntime;
import com.aresstack.askai.java8.localmodels.LocalInferenceConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.AskAiOllamaModelDigestLookup;
import com.aresstack.askai.java8.localmodels.LocalModelNames;
import com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager;
import com.aresstack.askai.java8.localmodels.OllamaEmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalNlpConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalNlpModelCatalog;
import com.aresstack.askai.java8.localmodels.LocalNlpModelStore;
import com.aresstack.askai.java8.localmodels.LocalRerankerConfigurationSnapshotProvider;
import com.aresstack.askai.java8.localmodels.LocalRerankerModelCatalog;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The app-level owner of the host runtime services handed to agent plugins via
 * {@code AgentHostContext.getService}: the Solon MCP server runtime (LAZY — the loopback HTTP server only
 * starts when a plugin actually registers an endpoint, i.e. when the productive research mode is used) and
 * the model/NLP/speech services. The research agent and the browser run inside the plugin now, so there is
 * no tool-client factory and no agent connector any more. One instance per application; {@link #shutdown()}
 * stops the MCP runtime if it was ever started.
 */
public final class AgentRuntimeServices {

    private final LazyRegistry registry = new LazyRegistry();
    /** Publishes the mandatory per-session reranker snapshot from the local model runtime. */
    private final RerankerConfigurationSnapshotProvider rerankerSnapshots;
    /** Lists the installed rerank-capable models for the EXPLICIT selection in the settings UI. */
    private final RerankerModelCatalog rerankerCatalog;
    /** Publishes the per-session structured-inference descriptor from the central main model (optional). */
    private final InferenceConfigurationSnapshotProvider inferenceSnapshots;
    /** Publishes the per-session EMBEDDING descriptor for the continuous knowledge pipeline (optional). */
    private final EmbeddingConfigurationSnapshotProvider embeddingSnapshots;
    /** Resolves the selected installed NLP (sentence-detection) model to a descriptor (optional). */
    private final NlpConfigurationSnapshotProvider nlpSnapshots;
    /** Lists the installed NLP models for the EXPLICIT per-language selection in the settings UI. */
    private final NlpModelCatalog nlpCatalog;
    /** Tracks running research sessions so their descriptors can be re-published on a model change. */
    private final LocalActiveResearchSessionRegistry activeSessions;
    /** Speech output (read-aloud) with an installed Piper model voice; inactive → plugin default voice. */
    private final com.aresstack.askai.agent.model.speech.SpeechSynthesisPort speechSynthesis =
            new com.aresstack.askai.java8.tts.PiperReadAloudService();

    /** The embedding descriptor request timeout (local runtime; probe + calls). */
    private static final long EMBEDDING_TIMEOUT_MILLIS = 60_000L;

    /** @deprecated retained only for callers without the local model runtime (no reranker service). */
    @Deprecated
    public AgentRuntimeServices() {
        this(null);
    }

    /** @deprecated legacy overload without the central config; the reranker selection is then plugin-driven. */
    @Deprecated
    public AgentRuntimeServices(LocalModelRuntimeManager localModelRuntime) {
        this(localModelRuntime, null);
    }

    /**
     * @param localModelRuntime the app's local model runtime manager; when present a mandatory reranker
     *                          snapshot provider is published for productive research sessions
     * @param centralConfig     the central AskAI configuration store; the reranker snapshot provider takes
     *                          its selection from {@code ai.rerankerModel} (AskAI → Configuration → AI models)
     */
    public AgentRuntimeServices(LocalModelRuntimeManager localModelRuntime,
                                AppConfigurationRepository centralConfig) {
        this.rerankerSnapshots = localModelRuntime == null ? null
                : new LocalRerankerConfigurationSnapshotProvider(localModelRuntime, centralConfig);
        this.rerankerCatalog = localModelRuntime == null ? null
                : new LocalRerankerModelCatalog(localModelRuntime);
        // Inference uses the central main model, which may live on the LOCAL runtime or a REMOTE Ollama —
        // so it is published whenever there is a central config, even without a local runtime.
        this.inferenceSnapshots = centralConfig == null ? null
                : new LocalInferenceConfigurationSnapshotProvider(localModelRuntime, centralConfig);
        // The embedding model is provider-crossing (Ollama OR AskAI-local), so it is published whenever there is
        // a central config — the Ollama arm needs only the central Ollama endpoint, not a local runtime.
        this.embeddingSnapshots = centralConfig == null ? null
                : embeddingProvider(localModelRuntime, centralConfig);
        // NLP models are a global AskAI resource in their OWN store (not the local runtime store), so the
        // catalog is always available; the snapshot provider needs the central selection to resolve.
        LocalNlpModelStore nlpStore = new LocalNlpModelStore();
        this.nlpCatalog = new LocalNlpModelCatalog(nlpStore);
        this.nlpSnapshots = centralConfig == null ? null
                : new LocalNlpConfigurationSnapshotProvider(nlpStore, centralConfig);
        // The active-session registry re-publishes descriptors on a central model change; it needs a
        // central config to know what to publish and is only useful alongside the snapshot providers.
        this.activeSessions = centralConfig == null ? null
                : new LocalActiveResearchSessionRegistry(inferenceSnapshots, rerankerSnapshots, centralConfig);
        System.err.println("[agent-runtime] localRuntime=" + (localModelRuntime != null)
                + " centralConfig=" + (centralConfig != null)
                + " reranker.snapshotProvider.published=" + (rerankerSnapshots != null)
                + " embedding.published=" + (embeddingSnapshots != null)
                + " inference.published=" + (inferenceSnapshots != null)
                + " nlp.catalog.published=" + (nlpCatalog != null)
                + " nlp.snapshotProvider.published=" + (nlpSnapshots != null));
    }

    /**
     * The productive, PROVIDER-CROSSING embedding snapshot provider. {@code ai.embeddingsModel} is deliberately
     * provider-agnostic, so the EXPLICIT central selection (AskAI → Configuration → AI models) is dispatched by
     * the model's provider: a {@code local/...} id → the AskAI local runtime
     * ({@link LocalEmbeddingConfigurationSnapshotProvider}); everything else → the existing Ollama endpoint's
     * {@code /api/embed} ({@link OllamaEmbeddingConfigurationSnapshotProvider}). There is NO fallback between them
     * (a configured Ollama model is never looked up in the local catalog) and NO guessing — an empty / removed /
     * unreachable selection is a typed {@link EmbeddingConfigurationException}.
     */
    private static EmbeddingConfigurationSnapshotProvider embeddingProvider(
            LocalModelRuntimeManager localModelRuntime, final AppConfigurationRepository centralConfig) {
        // Ollama arm: reuses the central Ollama base URL (read per call) + installed-model digest; no local runtime.
        final EmbeddingConfigurationSnapshotProvider ollama =
                new OllamaEmbeddingConfigurationSnapshotProvider(
                        new OllamaEmbeddingConfigurationSnapshotProvider.OllamaEndpoint() {
                            public String baseUrl() {
                                return centralConfig == null ? "" : centralConfig.load().getOllamaBaseUrl();
                            }
                        },
                        new AskAiOllamaModelDigestLookup(),
                        new HttpEmbeddingDimensionProbe((int) EMBEDDING_TIMEOUT_MILLIS),
                        EMBEDDING_TIMEOUT_MILLIS);
        // Local-runtime arm: only when a local model runtime exists (AskAI-local embedding models).
        final EmbeddingConfigurationSnapshotProvider local = localModelRuntime == null ? null
                : new LocalEmbeddingConfigurationSnapshotProvider(
                        new LocalEmbeddingModelCatalog(localModelRuntime),
                        LocalEmbeddingRuntime.over(localModelRuntime),
                        new HttpEmbeddingDimensionProbe((int) EMBEDDING_TIMEOUT_MILLIS),
                        EMBEDDING_TIMEOUT_MILLIS);
        return new EmbeddingConfigurationSnapshotProvider() {
            public EmbeddingConfigurationSnapshot prepareForSession(String sessionId, File sessionDirectory,
                                                                    String selectedModel)
                    throws EmbeddingConfigurationException {
                String central = centralConfig == null ? ""
                        : centralConfig.load().getAiModelSelections().getEmbeddingsModel();
                String centralTrimmed = central == null ? "" : central.trim();
                String effective = !centralTrimmed.isEmpty() ? centralTrimmed
                        : (selectedModel == null ? "" : selectedModel.trim());
                if (LocalModelNames.isLocalModelName(effective)) {
                    if (local == null) {
                        throw new EmbeddingConfigurationException(
                                EmbeddingConfigurationException.Reason.MODEL_NOT_FOUND,
                                "local embedding model '" + effective + "' is selected but no local model "
                                        + "runtime is available");
                    }
                    return local.prepareForSession(sessionId, sessionDirectory, effective);
                }
                return ollama.prepareForSession(sessionId, sessionDirectory, effective);
            }
        };
    }

    /** The running-session registry, for AskAI to trigger a descriptor refresh on a model change. */
    public LocalActiveResearchSessionRegistry activeSessionRegistry() {
        return activeSessions;
    }

    /** The service map for DefaultAgentHostContext (neutral interface types as keys). */
    public Map<Class<?>, Object> asServiceMap() {
        Map<Class<?>, Object> services = new LinkedHashMap<Class<?>, Object>();
        services.put(McpServerRegistry.class, registry);
        if (rerankerSnapshots != null) {
            services.put(RerankerConfigurationSnapshotProvider.class, rerankerSnapshots);
        }
        if (rerankerCatalog != null) {
            services.put(RerankerModelCatalog.class, rerankerCatalog);
        }
        if (inferenceSnapshots != null) {
            services.put(InferenceConfigurationSnapshotProvider.class, inferenceSnapshots);
        }
        if (embeddingSnapshots != null) {
            services.put(EmbeddingConfigurationSnapshotProvider.class, embeddingSnapshots);
        }
        if (nlpCatalog != null) {
            services.put(NlpModelCatalog.class, nlpCatalog);
        }
        if (nlpSnapshots != null) {
            services.put(NlpConfigurationSnapshotProvider.class, nlpSnapshots);
        }
        if (activeSessions != null) {
            services.put(ActiveResearchSessionRegistry.class, activeSessions);
        }
        services.put(com.aresstack.askai.agent.model.speech.SpeechSynthesisPort.class, speechSynthesis);
        return services;
    }

    public void shutdown() {
        registry.shutdown();
        // FINAL app teardown: also stop the process-global Solon server so its non-daemon HTTP-Dispatcher
        // thread dies and the JVM exits naturally. Without this the app hangs on close once research booted
        // Solon; without the plugin Solon never boots and the app already exits cleanly.
        SolonMcpServerRuntime.stopSharedServer();
    }

    /** Starts the real Solon MCP runtime on first use; thread-safe; idempotent shutdown. */
    private static final class LazyRegistry implements McpServerRegistry {
        private volatile SolonMcpServerRuntime runtime;

        private SolonMcpServerRuntime runtime() {
            SolonMcpServerRuntime current = runtime;
            if (current == null) {
                synchronized (this) {
                    if (runtime == null) {
                        runtime = new SolonMcpServerRuntime();
                    }
                    current = runtime;
                }
            }
            return current;
        }

        public McpEndpointHandle registerEndpoint(McpEndpointDefinition definition) {
            return runtime().registerEndpoint(definition);
        }

        public void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> tools) {
            runtime().updateTools(handle, tools);
        }

        public void unregisterEndpoint(McpEndpointHandle handle) {
            runtime().unregisterEndpoint(handle);
        }

        public String endpointUrl(McpEndpointHandle handle) {
            return runtime().endpointUrl(handle);
        }

        public java.util.List<String> toolNames(com.aresstack.askai.mcp.api.McpEndpointHandle handle) {
            return runtime().toolNames(handle);
        }

        public java.util.Map<String, String> toolCatalog(
                com.aresstack.askai.mcp.api.McpEndpointHandle handle) {
            return runtime().toolCatalog(handle);
        }

        void shutdown() {
            SolonMcpServerRuntime current = runtime;
            if (current != null) {
                current.shutdown();
            }
        }
    }
}
