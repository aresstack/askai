package com.aresstack.askai.plugin.host;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.localmodels.LocalModelRuntimeManager;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

/**
 * A5e: the host service map publishes the reranker snapshot provider whenever a local model runtime is
 * present, and omits it (rather than a broken stub) when there is none.
 */
public class AgentRuntimeServicesRerankerTest {

    @Test
    public void publishesTheRerankerProviderWhenALocalRuntimeIsPresent() {
        AgentRuntimeServices services = new AgentRuntimeServices(
                new LocalModelRuntimeManager(new File("build/tmp/does-not-matter")));
        try {
            assertNotNull("the reranker snapshot provider is published to plugins",
                    services.asServiceMap().get(RerankerConfigurationSnapshotProvider.class));
            assertNotNull("the reranker model catalog for the EXPLICIT selection is published too",
                    services.asServiceMap().get(
                            com.aresstack.askai.agent.model.reranker.RerankerModelCatalog.class));
        } finally {
            services.shutdown();
        }
    }

    @Test
    public void omitsTheRerankerProviderWithoutALocalRuntime() {
        AgentRuntimeServices services = new AgentRuntimeServices(null);
        try {
            assertNull(services.asServiceMap().get(RerankerConfigurationSnapshotProvider.class));
            assertNull(services.asServiceMap().get(
                    com.aresstack.askai.agent.model.reranker.RerankerModelCatalog.class));
        } finally {
            services.shutdown();
        }
    }
}
