package com.aresstack.askai.research.benchmark;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.java8.modelport.ModelPortSettingsRepository;
import com.aresstack.askai.java8.modelport.OllamaModelPort;

import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * The host's model port as the benchmark provides it (ADR 0001, 3.2): the platform's own {@link
 * OllamaModelPort}, every slot filled by the benchmarked model, its one endpoint being the token meter. Since
 * lane C the in-process agent reaches models only through this port, so every subject gets one; the
 * scheduler, capacity (product default, one call per model) and residency are the product's.
 */
final class BenchmarkModelPort {

    static final String ENDPOINT = "benchmark";

    private BenchmarkModelPort() {
    }

    /** {@code settingsDir} holds the port's settings file; absent means the product defaults. */
    static OllamaModelPort open(final String model, final String meterBaseUrl, final long timeoutMillis,
                                File settingsDir) {
        return new OllamaModelPort(new OllamaModelPort.EndpointDirectory() {
            public List<String> endpointIds() {
                return Collections.singletonList(ENDPOINT);
            }

            public ModelEndpoint.Kind kind(String endpointId) {
                return ModelEndpoint.Kind.LOCAL;
            }

            public String label(String endpointId) {
                return "Ollama (token meter)";
            }

            public String baseUrl(String endpointId) {
                return meterBaseUrl;
            }

            public String baseUrlIfRunning(String endpointId) {
                return meterBaseUrl;
            }
        }, new OllamaModelPort.SlotDirectory() {
            public String modelFor(ModelSlot slot) {
                return model;
            }

            public String endpointFor(String ignored) {
                return ENDPOINT;
            }

            public List<ModelSlot> knownSlots() {
                return Collections.emptyList();
            }

            public Duration defaultTimeout() {
                return Duration.ofMillis(timeoutMillis);
            }
        }, new ModelPortSettingsRepository(new File(settingsDir, "model-port.properties")), null);
    }
}
