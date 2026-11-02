package com.aresstack.askai.plugin.host;

import com.aresstack.askai.agent.model.nlp.NlpModelCatalog;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

/**
 * The NLP model catalog is a GLOBAL AskAI resource (its own store, not the local model runtime store), so it is
 * published to plugins even without a local model runtime.
 */
public class AgentRuntimeServicesNlpTest {

    @Test
    public void publishesTheNlpCatalogEvenWithoutALocalRuntime() {
        AgentRuntimeServices services = new AgentRuntimeServices(null);
        try {
            assertNotNull("the NLP model catalog is published to plugins",
                    services.asServiceMap().get(NlpModelCatalog.class));
        } finally {
            services.shutdown();
        }
    }
}
