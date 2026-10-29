package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.localruntime.LocalModelRuntime;

import java.io.File;
import java.io.IOException;

/**
 * Host-side OWNER of the local model runtime: AskAI's virtual Ollama container for locally installed
 * models. Since the return to Java 21 the runtime runs INSIDE the application ({@link LocalModelRuntime}):
 * the manager starts it on demand, hands out its loopback base URL, brings it back after an AskAI restart
 * when local models exist, and closes it on AskAI shutdown. There is no global fixed port — the runtime
 * binds a free one per start — and no separate JVM any more.
 */
public final class LocalModelRuntimeManager {

    /** The inference backend of the in-process runtime (the CPU reference path, as before). */
    static final String BACKEND = "cpu";

    /** A failed start is not retried for this long — N queued callers fail fast instead of serially. */
    private static final long START_RETRY_BACKOFF_MILLIS = 15_000L;

    private final File modelRoot;
    private LocalModelRuntime runtime;
    private long lastStartFailureMillis;

    public LocalModelRuntimeManager() {
        this(LocalModelNames.localModelRoot());
    }

    public LocalModelRuntimeManager(File modelRoot) {
        this.modelRoot = modelRoot;
    }

    public File getModelRoot() {
        return modelRoot;
    }

    /** True when at least one locally installed model directory exists (restart-on-boot signal). */
    public boolean hasInstalledModels() {
        File[] children = modelRoot.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory() && new File(child, "askai-local-model.json").isFile()) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean isRunning() {
        return runtime != null;
    }

    /**
     * True when the runtime can serve at all. The runtime is part of the application now, so it is always
     * available; the method stays for the callers that only aggregate (model lists, boot start).
     */
    public boolean isAvailable() {
        return true;
    }

    /** The base URL of the RUNNING runtime, or null. */
    public synchronized String getBaseUrl() {
        return runtime == null ? null : runtime.baseUrl();
    }

    /** Start the runtime if it is not running; @return the base URL. */
    public synchronized String ensureStarted() throws IOException {
        if (runtime != null) {
            return runtime.baseUrl();
        }
        if (System.currentTimeMillis() - lastStartFailureMillis < START_RETRY_BACKOFF_MILLIS) {
            // The monitor serializes callers: without this, every model-list capability probe would
            // re-run the whole failing start and stall the queue behind it.
            throw new IOException("local model runtime start failed recently; retry postponed");
        }
        try {
            runtime = LocalModelRuntime.start(modelRoot.toPath(), BACKEND);
        } catch (IOException | RuntimeException failed) {
            lastStartFailureMillis = System.currentTimeMillis();
            throw new IOException("The local model runtime could not be started (model root "
                    + modelRoot.getAbsolutePath() + "): " + failed.getMessage(), failed);
        }
        return runtime.baseUrl();
    }

    /** Idempotent shutdown: unloads every model and frees the port. */
    public synchronized void stop() {
        if (runtime == null) {
            return;
        }
        try {
            runtime.close();
        } catch (RuntimeException failed) {
            System.err.println("[local-runtime] stop failed: " + failed.getMessage());
        }
        runtime = null;
    }
}
