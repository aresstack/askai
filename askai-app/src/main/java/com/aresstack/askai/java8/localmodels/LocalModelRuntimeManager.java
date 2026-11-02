package com.aresstack.askai.java8.localmodels;

import com.aresstack.askai.localruntime.LocalModelRuntime;

import java.io.File;
import java.io.IOException;

/**
 * Host-side OWNER of the local model runtime. Since askai #49 the runtime runs inside the AskAI JVM: AskAI starts
 * it on first use (never the research agent), provides its loopback base URL (the Ollama-compatible endpoint the
 * reranker, embedding and generation clients talk to), starts it again after a failed start once the backoff has
 * passed, and stops it on AskAI shutdown. There is no child process and no global fixed port.
 */
public final class LocalModelRuntimeManager {

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

    /** The runtime is part of AskAI itself, so it can always serve (it is started on demand). */
    public boolean isAvailable() {
        return true;
    }

    /** The base URL of the RUNNING runtime, or null. */
    public synchronized String getBaseUrl() {
        return runtime != null ? runtime.baseUrl() : null;
    }

    /** Start the runtime in this JVM if it is not running; @return the base URL. */
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
            runtime = LocalModelRuntime.start(modelRoot.toPath(), "cpu");
        } catch (IOException | RuntimeException | LinkageError failed) {
            lastStartFailureMillis = System.currentTimeMillis();
            throw new IOException("The local model runtime could not be started: " + failed, failed);
        }
        System.err.println("[local-runtime] ready on " + runtime.baseUrl() + " backend=" + runtime.backend()
                + " modelRoot=" + modelRoot.getAbsolutePath());
        return runtime.baseUrl();
    }

    /** Idempotent shutdown: stops the endpoint and releases the loaded models. */
    public synchronized void stop() {
        if (runtime == null) {
            return;
        }
        try {
            runtime.close();
        } finally {
            runtime = null;
        }
    }
}
