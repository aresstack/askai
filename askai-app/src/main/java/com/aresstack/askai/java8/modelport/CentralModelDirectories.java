package com.aresstack.askai.java8.modelport;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.java8.config.AppConfigurationRepository;
import com.aresstack.askai.java8.localmodels.LocalModelNames;
import com.aresstack.askai.java8.localmodels.LocalModelRuntimeManager;

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
 *   <li>endpoint {@code ollama}: the configured Ollama base URL, read per call;</li>
 *   <li>endpoint {@code local-runtime}: AskAI's local model runtime, started on demand, serving
 *       {@code local/...} models (only when a runtime manager exists);</li>
 *   <li>a slot resolves to the model configured for it in the model-port settings, otherwise to the central
 *       main model (the chat-window selection), so every role behaves as before until the user splits
 *       them;</li>
 *   <li>the default call timeout is the user's main-model timeout setting.</li>
 * </ul>
 */
public final class CentralModelDirectories {

    public static final String OLLAMA = "ollama";
    public static final String LOCAL_RUNTIME = "local-runtime";

    private CentralModelDirectories() {
    }

    public static OllamaModelPort.EndpointDirectory endpoints(final AppConfigurationRepository central,
                                                              final LocalModelRuntimeManager localRuntime) {
        return new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                return localRuntime == null
                        ? Collections.singletonList(OLLAMA) : Arrays.asList(OLLAMA, LOCAL_RUNTIME);
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                return LOCAL_RUNTIME.equals(endpointId) ? "AskAI local model runtime" : "Ollama";
            }

            public String baseUrl(String endpointId) throws IOException {
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
                if (LOCAL_RUNTIME.equals(endpointId)) {
                    return localRuntime == null ? null : localRuntime.getBaseUrl();
                }
                String url = central.load().getOllamaBaseUrl();
                return url == null || url.trim().isEmpty() ? null : url.trim();
            }
        };
    }

    public static OllamaModelPort.SlotDirectory slots(final AppConfigurationRepository central,
                                                      final ModelPortSettingsRepository settings) {
        return new OllamaModelPort.SlotDirectory() {
            public String modelFor(ModelSlot slot) {
                String configured = settings.load().slotModel(slot.getName());
                if (!configured.isEmpty()) {
                    return configured;
                }
                String main = central.load().getAiModelSelections().getMainModel();
                return main == null ? "" : main.trim();
            }

            public String endpointFor(String model) {
                return LocalModelNames.isLocalModelName(model) ? LOCAL_RUNTIME : OLLAMA;
            }

            public List<ModelSlot> knownSlots() {
                List<ModelSlot> result = new ArrayList<ModelSlot>();
                for (String name : settings.load().knownSlotNames()) {
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

    /** The productive port over the central configuration. */
    public static OllamaModelPort productivePort(AppConfigurationRepository central,
                                                 LocalModelRuntimeManager localRuntime,
                                                 ModelPortSettingsRepository settings) {
        return new OllamaModelPort(endpoints(central, localRuntime), slots(central, settings), settings, null);
    }
}
