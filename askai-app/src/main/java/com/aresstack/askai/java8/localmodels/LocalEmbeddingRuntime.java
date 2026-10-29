package com.aresstack.askai.java8.localmodels;

import java.io.IOException;

/**
 * A thin seam over the local model runtime start: ensure the local runtime is running and return its base URL. Kept
 * separate so the snapshot provider does not embed the concrete {@link LocalModelRuntimeManager} and stays
 * unit-testable. The {@code virtualModelId} is passed for context/future use; the local runtime loads the model on
 * demand per request.
 */
public interface LocalEmbeddingRuntime {

    String ensureStarted(String virtualModelId) throws IOException;

    /** The productive adapter over {@link LocalModelRuntimeManager#ensureStarted()}. */
    static LocalEmbeddingRuntime over(final LocalModelRuntimeManager manager) {
        return new LocalEmbeddingRuntime() {
            public String ensureStarted(String virtualModelId) throws IOException {
                return manager.ensureStarted();
            }
        };
    }
}
