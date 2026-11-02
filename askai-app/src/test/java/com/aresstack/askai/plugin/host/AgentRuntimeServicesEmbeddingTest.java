package com.aresstack.askai.plugin.host;

import com.aresstack.askai.agent.model.embedding.EmbeddingConfigurationSnapshotProvider;
import com.aresstack.askai.config.AppConfigurationRepository;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * The embedding model is PROVIDER-CROSSING (Ollama or AskAI-local), so its snapshot provider is published
 * whenever a central config is present — the Ollama arm needs only the central Ollama endpoint, NOT a local
 * model runtime. Without a central config there is no provider.
 */
public class AgentRuntimeServicesEmbeddingTest {

    private static AppConfigurationRepository tempConfig() throws IOException {
        File dir = Files.createTempDirectory("askai-embed-svc").toFile();
        return new AppConfigurationRepository(new File(dir, "askai.properties"));
    }

    @Test
    public void publishesTheEmbeddingProviderWithACentralConfigEvenWithoutALocalRuntime() throws IOException {
        AgentRuntimeServices services = new AgentRuntimeServices(null, tempConfig());
        try {
            assertNotNull("the embedding snapshot provider is published (Ollama arm needs no local runtime)",
                    services.asServiceMap().get(EmbeddingConfigurationSnapshotProvider.class));
        } finally {
            services.shutdown();
        }
    }

    @Test
    public void omitsTheEmbeddingProviderWithoutACentralConfig() {
        AgentRuntimeServices services = new AgentRuntimeServices(null);
        try {
            assertNull(services.asServiceMap().get(EmbeddingConfigurationSnapshotProvider.class));
        } finally {
            services.shutdown();
        }
    }
}
