package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.config.AppConfiguration;
import com.aresstack.askai.config.AppConfigurationRepository;
import com.aresstack.askai.localmodels.LocalModelNames;
import com.aresstack.askai.localmodels.LocalModelRuntimeManager;
import com.aresstack.askai.modelport.provider.ModelProvider;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.aresstack.askai.net.ConfiguredConnections;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The productive endpoint pool and slot resolution of the model port, from AskAI's central configuration
 * (ADR 0001, 3.2 and 4.5):
 *
 * <ul>
 *   <li>endpoint {@code ollama}: the configured Ollama base URL, read per call (the default provider);</li>
 *   <li>endpoint {@code openai-compatible}: the configured OpenAI-compatible API instead of Ollama, when the user
 *       chose it as the model provider ({@link ModelProviderSettings}); its own model per slot, its own
 *       capacity, many models side by side;</li>
 *   <li>endpoint {@code local-runtime}: AskAI's local model runtime, started on demand, serving
 *       {@code local/...} models (only when a runtime manager exists), with either provider;</li>
 *   <li>a slot resolves to the model configured for it, else its parent slot's, else the {@code default}
 *       slot's (plan 5.1), in the chosen provider's slot models; the chat-window selection is never used;</li>
 *   <li>the default call timeout is the user's main-model timeout setting.</li>
 * </ul>
 */
public final class CentralModelDirectories {

    public static final String OLLAMA = "ollama";
    public static final String LOCAL_RUNTIME = "local-runtime";
    public static final String OPENAI_COMPATIBLE = "openai-compatible";

    private CentralModelDirectories() {
    }

    /** The pool with Ollama only (no provider choice). */
    public static OllamaModelPort.EndpointDirectory endpoints(final AppConfigurationRepository central,
                                                              final LocalModelRuntimeManager localRuntime) {
        return endpoints(central, localRuntime, null, null);
    }

    /**
     * The pool with the user's provider choice: {@code openai-compatible} instead of {@code ollama} while the API is
     * chosen, served by {@code api}; the local runtime with either.
     *
     * @param providers the provider settings; null = Ollama only
     * @param api       the OpenAI-compatible provider; null = Ollama only
     */
    public static OllamaModelPort.EndpointDirectory endpoints(final AppConfigurationRepository central,
                                                              final LocalModelRuntimeManager localRuntime,
                                                              final ModelProviderSettingsRepository providers,
                                                              final ModelProvider api) {
        return new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                String main = usesApi(providers, api) ? OPENAI_COMPATIBLE : OLLAMA;
                return localRuntime == null
                        ? Collections.singletonList(main) : Arrays.asList(main, LOCAL_RUNTIME);
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return OPENAI_COMPATIBLE.equals(endpointId) ? ModelEndpoint.Kind.REMOTE : ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                if (OPENAI_COMPATIBLE.equals(endpointId)) {
                    return ModelProviderKind.OPENAI_COMPATIBLE.label();
                }
                return LOCAL_RUNTIME.equals(endpointId) ? "AskAI local model runtime" : "Ollama";
            }

            public ModelProvider provider(String endpointId) {
                return OPENAI_COMPATIBLE.equals(endpointId) ? api : null;
            }

            public String baseUrl(String endpointId) throws IOException {
                if (OPENAI_COMPATIBLE.equals(endpointId)) {
                    String url = providers == null ? "" : providers.load().getApiBaseUrl();
                    if (url.isEmpty()) {
                        throw new IOException("no base URL of the OpenAI-compatible API is set (Settings -> AI "
                                + "models -> Model provider)");
                    }
                    return url;
                }
                if (LOCAL_RUNTIME.equals(endpointId)) {
                    if (localRuntime == null) {
                        throw new IOException("no local model runtime is available");
                    }
                    return localRuntime.ensureStarted();
                }
                String url = central.load().getOllamaBaseUrl();
                if (url == null || url.trim().isEmpty()) {
                    throw new IOException("no Ollama base URL is configured");
                }
                return url.trim();
            }

            public String baseUrlIfRunning(String endpointId) {
                if (OPENAI_COMPATIBLE.equals(endpointId)) {
                    String url = providers == null ? "" : providers.load().getApiBaseUrl();
                    return url.isEmpty() ? null : url;
                }
                if (LOCAL_RUNTIME.equals(endpointId)) {
                    return localRuntime == null ? null : localRuntime.getBaseUrl();
                }
                String url = central.load().getOllamaBaseUrl();
                return url == null || url.trim().isEmpty() ? null : url.trim();
            }
        };
    }

    /** Slots resolved in the Ollama slot models only (no provider choice). */
    public static OllamaModelPort.SlotDirectory slots(final AppConfigurationRepository central,
                                                      final ModelPortSettingsRepository settings) {
        return slots(central, settings, null);
    }

    /**
     * Slots resolved in the chosen provider's slot models: the API's while the OpenAI-compatible API is chosen
     * (nothing shipped: a slot without a model there is honestly unavailable), else Ollama's.
     *
     * @param providers the provider settings; null = Ollama only
     */
    public static OllamaModelPort.SlotDirectory slots(final AppConfigurationRepository central,
                                                      final ModelPortSettingsRepository settings,
                                                      final ModelProviderSettingsRepository providers) {
        return new OllamaModelPort.SlotDirectory() {
            public String modelFor(ModelSlot slot) {
                // exact -> parent -> default (plan 5.1); never the chat model (ai.mainModel)
                if (providers == null) {
                    return settings.load().resolveModel(slot.getName());
                }
                return providers.load().resolveModel(slot.getName(), settings.load());
            }

            public boolean checksInstalledModels() {
                return true;
            }

            public String endpointFor(String model) {
                if (LocalModelNames.isLocalModelName(model)) {
                    return LOCAL_RUNTIME;
                }
                return providers != null && providers.load().usesApi() ? OPENAI_COMPATIBLE : OLLAMA;
            }

            public String noModelMessage(ModelSlot slot) {
                if (providers != null && providers.load().usesApi()) {
                    return "No model of the OpenAI-compatible API for the "
                            + ModelPortSettings.describe(slot.getName()) + ". Set it under Settings -> AI models -> "
                            + "Model provider (the default slot is enough for every task).";
                }
                return OllamaModelPort.noModelMessage(slot);
            }

            public List<ModelSlot> knownSlots() {
                List<ModelSlot> result = new ArrayList<ModelSlot>();
                List<String> names = new ArrayList<String>(settings.load().knownSlotNames());
                if (providers != null) {
                    for (String name : providers.load().getApiSlotModels().keySet()) {
                        if (!names.contains(name)) {
                            names.add(name);
                        }
                    }
                }
                for (String name : names) {
                    try {
                        result.add(ModelSlot.of(name));
                    } catch (IllegalArgumentException invalid) {
                        // a hand-edited invalid slot key never breaks the resource view
                    }
                }
                return result;
            }

            public Duration defaultTimeout() {
                return Duration.ofSeconds(central.load().getAiModelSelections().getMainModelTimeoutSeconds());
            }
        };
    }

    /**
     * A port over ONE Ollama endpoint where every slot resolves to one model, with default settings: for live
     * gates that run a model role against a local Ollama without the app's configuration.
     */
    public static OllamaModelPort fixedModelPort(final String baseUrl, final String model,
                                                 final Duration timeout) {
        ModelPortSettingsRepository defaults = new ModelPortSettingsRepository(
                new java.io.File(System.getProperty("java.io.tmpdir"), "askai-model-port-defaults-absent.properties"));
        return new OllamaModelPort(new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                return Collections.singletonList(OLLAMA);
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                return "Ollama";
            }

            public String baseUrl(String endpointId) {
                return baseUrl;
            }

            public String baseUrlIfRunning(String endpointId) {
                return baseUrl;
            }
        }, new OllamaModelPort.SlotDirectory() {
            public String modelFor(ModelSlot slot) {
                return model;
            }

            public String endpointFor(String ignored) {
                return OLLAMA;
            }

            public List<ModelSlot> knownSlots() {
                return Collections.emptyList();
            }

            public Duration defaultTimeout() {
                return timeout;
            }
        }, defaults, null);
    }

    /** The productive port over the central configuration, with Ollama only. */
    public static OllamaModelPort productivePort(AppConfigurationRepository central,
                                                 LocalModelRuntimeManager localRuntime,
                                                 ModelPortSettingsRepository settings) {
        return new OllamaModelPort(endpoints(central, localRuntime), slots(central, settings), settings, null);
    }

    /**
     * The productive port over the central configuration and the user's provider choice: Ollama (default) or the
     * OpenAI-compatible API, switched per call, so a save in the settings applies to the next call.
     */
    public static OllamaModelPort productivePort(AppConfigurationRepository central,
                                                 LocalModelRuntimeManager localRuntime,
                                                 ModelPortSettingsRepository settings,
                                                 ModelProviderSettingsRepository providers) {
        OpenAiCompatibleModelProvider api = apiProvider(providers, central);
        return new OllamaModelPort(endpoints(central, localRuntime, providers, api),
                slots(central, settings, providers), settings, null);
    }

    /**
     * The OpenAI-compatible provider over the provider settings (read per request) and AskAI's network settings
     * (proxy, certificate trust, User-Agent).
     */
    public static OpenAiCompatibleModelProvider apiProvider(final ModelProviderSettingsRepository providers,
                                                            final AppConfigurationRepository central) {
        return new OpenAiCompatibleModelProvider(new OpenAiCompatibleModelProvider.Settings() {
            public int defaultCapacity() {
                return providers.load().getApiCapacity();
            }

            public int contextTokens(String model) {
                return providers.load().apiContextOf(model);
            }

            public int connectTimeoutMillis() {
                return (int) Math.min(Integer.MAX_VALUE, providers.load().getConnectTimeoutSeconds() * 1000L);
            }

            public int readTimeoutMillis() {
                return (int) Math.min(Integer.MAX_VALUE, providers.load().getReadTimeoutSeconds() * 1000L);
            }

            public int retries() {
                return providers.load().getRetries();
            }

            public char[] apiKey() throws IOException {
                return providers.apiKey();
            }
        }, apiConnections(central));
    }

    /** Connections to the API through AskAI's network settings (proxy, certificate trust, User-Agent). */
    public static OpenAiCompatibleModelProvider.ConnectionOpener apiConnections(
            final AppConfigurationRepository central) {
        if (central == null) {
            return OpenAiCompatibleModelProvider.PLAIN;
        }
        return url -> {
            AppConfiguration configuration = central.load();
            return new ConfiguredConnections(configuration.getProxyConfiguration(),
                    configuration.getCertificateTrustConfiguration(), configuration.getHttpClientConfiguration())
                    .open(url);
        };
    }

    private static boolean usesApi(ModelProviderSettingsRepository providers, ModelProvider api) {
        return providers != null && api != null && providers.load().usesApi();
    }
}
