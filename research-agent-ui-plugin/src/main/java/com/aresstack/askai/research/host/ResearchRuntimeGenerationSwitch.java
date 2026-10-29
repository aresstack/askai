package com.aresstack.askai.research.host;

import com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider;
import com.aresstack.askai.mcp.api.McpServerRegistry;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Prepare-then-publish generation switching for the productive research runtime. {@link #switchTo} fully
 * PREPARES the next generation (configuration validated) BEFORE it is
 * published; only after publishing is the previous generation locked for new sessions and its resources
 * closed. A preparation failure throws and leaves the previously active generation completely untouched.
 */
public final class ResearchRuntimeGenerationSwitch {

    private final McpServerRegistry registry;
    private final RerankerConfigurationSnapshotProvider rerankerSnapshots;
    private final AtomicLong generationIds = new AtomicLong();
    private volatile ResearchRuntimeGeneration active;

    public ResearchRuntimeGenerationSwitch(McpServerRegistry registry,
                                           RerankerConfigurationSnapshotProvider rerankerSnapshots) {
        this.registry = registry;
        this.rerankerSnapshots = rerankerSnapshots;
    }

    public ResearchRuntimeGeneration getActive() {
        return active;
    }

    /**
     * a) prepare fully, b) publish only on success, c) lock the old generation, d)+e) close its sessions,
     * endpoints and processes. @throws IllegalArgumentException when the new config is unusable — the old
     * generation stays active and untouched.
     */
    public synchronized ResearchRuntimeGeneration switchTo(ResearchRuntimeConfig config) {
        // a) prepare (validate everything the new generation needs; nothing published yet).
        List<String> problems = config.validate();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(
                    "New research runtime generation is not usable, keeping the current one: " + problems);
        }
        long id = generationIds.incrementAndGet();
        ResearchRuntimeGeneration next = new ResearchRuntimeGeneration(id,
                new ProductiveResearchBackendFactory(registry, config, id,
                        rerankerSnapshots));
        // b) publish.
        ResearchRuntimeGeneration previous = active;
        active = next;
        // c–e) retire the previous generation (lock first, then close sessions/endpoints/processes).
        if (previous != null) {
            previous.retire();
        }
        return next;
    }

    /** Shut the runtime down entirely (retires the active generation). Idempotent. */
    public synchronized void shutdown() {
        ResearchRuntimeGeneration current = active;
        active = null;
        if (current != null) {
            current.retire();
        }
    }
}
