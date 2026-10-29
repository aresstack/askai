package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.generation.LoadedGenerationHandle;
import com.aresstack.askai.localruntime.generation.LocalGenerationBackend;
import com.aresstack.askai.localruntime.generation.LocalGenerationException;
import com.aresstack.askai.localruntime.generation.LocalGenerationLoadRequest;
import com.aresstack.askai.localruntime.generation.LocalGenerationRequest;
import com.aresstack.askai.localruntime.generation.LocalGenerationResult;
import com.aresstack.askai.localruntime.generation.LocalGenerationRuntimePort;
import com.aresstack.askai.localruntime.generation.LocalGenerationTokenListener;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;

import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns at most ONE warm generation model behind the AskAI {@link LocalGenerationRuntimePort}. Generation is
 * serialized (a single memory-intensive generation runtime at a time): a request for a different model
 * unloads the previous one first, and {@code keep_alive:0} / unload waits for any in-flight generation to
 * finish before closing the handle (no use-after-close). On this development branch the port is
 * {@code NotLinkedGenerationRuntimePort}, so every load fails with RUNTIME_NOT_LINKED until the productive
 * runtime is linked behind the SAME port.
 */
final class LocalGenerationEngine implements AutoCloseable {

    private final LocalGenerationRuntimePort port;
    private final LocalGenerationBackend backend;
    // One lock serializes generation AND unload, so an unload never closes a handle mid-generation.
    private final ReentrantLock lock = new ReentrantLock();
    private LoadedGenerationHandle warm;
    private String warmVirtualName;

    LocalGenerationEngine(LocalGenerationRuntimePort port, LocalGenerationBackend backend) {
        this.port = port;
        this.backend = backend;
    }

    /** Whether a productive generation runtime is linked behind the port (drives the UI pending state). */
    boolean isGenerationLinked() {
        return port.isLinked();
    }

    /**
     * Install-time compile + package-backed smoke-load for a generation model (does NOT keep it warm).
     * Any currently-warm generation model is unloaded first (single-memory policy).
     */
    void compileAndSmokeLoad(LocalModel model) throws LocalGenerationException {
        lock.lock();
        try {
            closeWarm();
            port.compileAndSmokeLoad(loadRequest(model));
        } finally {
            lock.unlock();
        }
    }

    LocalGenerationResult generate(LocalModel model, LocalGenerationRequest request,
                                   LocalGenerationTokenListener listener) throws LocalGenerationException {
        lock.lock();
        try {
            LoadedGenerationHandle handle = ensureWarm(model);
            if (listener == null) {
                return handle.generate(request);
            }
            handle.generate(request, listener);
            return null; // the terminal result was delivered through the listener
        } finally {
            lock.unlock();
        }
    }

    /** keep_alive:0 / explicit unload: waits (via the lock) for any in-flight generation, then closes. */
    void unload(String virtualName) {
        lock.lock();
        try {
            if (warm != null && warm.virtualName().equals(virtualName)) {
                closeWarm();
            }
        } finally {
            lock.unlock();
        }
    }

    /** The virtual name of the warm generation model, or null. */
    String loadedVirtualName() {
        lock.lock();
        try {
            return warmVirtualName;
        } finally {
            lock.unlock();
        }
    }

    private LoadedGenerationHandle ensureWarm(LocalModel model) throws LocalGenerationException {
        String virtualName = model.virtualName();
        if (warm != null && virtualName.equals(warmVirtualName)) {
            return warm;
        }
        closeWarm(); // single-model policy: unload the previous generation model first
        warm = port.load(loadRequest(model));
        warmVirtualName = virtualName;
        return warm;
    }

    private LocalGenerationLoadRequest loadRequest(LocalModel model) {
        LocalRuntimeModelDescriptor descriptor =
                LocalModelCatalog.findByRepositoryId(model.manifest().getHuggingFaceRepository());
        String chatTemplate = descriptor == null ? "" : descriptor.chatTemplate();
        return LocalGenerationLoadRequest.builder(model.virtualName(), model.directory())
                .runtimeModelId(model.runtimeModelId())
                .runtimeFamily(model.runtimeFamily())
                .chatTemplate(chatTemplate)
                .backend(backend)
                .build();
    }

    private void closeWarm() {
        if (warm != null) {
            try {
                warm.close();
            } catch (RuntimeException ex) {
                System.err.println("[local-runtime] generation close failed for " + warmVirtualName
                        + ": " + ex.getMessage());
            }
            warm = null;
            warmVirtualName = null;
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closeWarm();
        } finally {
            lock.unlock();
        }
    }
}
