package com.aresstack.askai.localruntime;

import com.aresstack.windirectml.runtime.api.Backend;
import com.aresstack.windirectml.runtime.api.EmbeddingConfig;
import com.aresstack.windirectml.runtime.api.EmbeddingModelHandle;
import com.aresstack.windirectml.runtime.api.EmbeddingModelId;
import com.aresstack.windirectml.runtime.api.MlRuntime;
import com.aresstack.windirectml.runtime.api.RerankResult;
import com.aresstack.windirectml.runtime.api.RerankerConfig;
import com.aresstack.windirectml.runtime.api.RerankerModelHandle;
import com.aresstack.windirectml.runtime.api.RerankerModelId;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Owns the win-directml-java runtime for this sidecar. Embedding and reranker models load LAZILY on first
 * use and stay warm (reused by their virtual name); {@code keep_alive:0} / delete unload the specific
 * handle. Every inference takes a LEASE on its handle so an unload/delete never closes a handle while a
 * request is still running on it (no use-after-close under parallel UI / research / management calls).
 *
 * <p>The install smoke loads STRICTLY from the compiled {@code *.wdmlpack} (the CPU weight loaders open
 * {@code <dir>/encoder.wdmlpack} / {@code reranker.wdmlpack}, never the raw safetensors), so a successful
 * smoke really proves the package is runnable.</p>
 */
final class LocalModelEngine implements AutoCloseable {

    /** One rerank invocation result: scores plus the durations the Ollama API reports. */
    record Reranked(List<RerankResult> results, long loadDurationNanos, long totalDurationNanos) {
    }

    /** How a per-request E5 prefix is applied; RAW leaves the text untouched. */
    enum PrefixMode {
        RAW(""), QUERY("query: "), PASSAGE("passage: ");

        private final String prefix;

        PrefixMode(String prefix) {
            this.prefix = prefix;
        }

        String apply(String text) {
            return prefix.isEmpty() ? text : prefix + text;
        }
    }

    private final Backend backend;
    private final Object lock = new Object();
    private final Map<String, LoadedHandle> handles = new LinkedHashMap<>();
    private MlRuntime runtime;

    LocalModelEngine(Backend backend) {
        this.backend = backend;
    }

    Backend backend() {
        return backend;
    }

    // ------------------------------------------------------------------ inference

    /** Embed the (already prefix-transformed) inputs in order. */
    List<float[]> embed(LocalModel model, List<String> inputs) throws Exception {
        LoadedHandle handle = acquireEmbedding(model);
        try {
            handle.inferenceLock.lock();
            try {
                EmbeddingModelHandle embedding = (EmbeddingModelHandle) handle.handle;
                return embedding.embedBatch(inputs);
            } finally {
                handle.inferenceLock.unlock();
            }
        } finally {
            release(handle);
        }
    }

    int embeddingDimension(LocalModel model) throws Exception {
        LoadedHandle handle = acquireEmbedding(model);
        try {
            return ((EmbeddingModelHandle) handle.handle).dimension();
        } finally {
            release(handle);
        }
    }

    Reranked rerank(LocalModel model, String query, List<String> documents, int topN) throws Exception {
        long start = System.nanoTime();
        LoadedHandle handle = acquireReranker(model);
        long loadNanos = handle.loadDurationNanos;
        try {
            handle.inferenceLock.lock();
            try {
                RerankerModelHandle reranker = (RerankerModelHandle) handle.handle;
                List<RerankResult> results = reranker.rerank(query, documents, topN);
                return new Reranked(results, loadNanos, System.nanoTime() - start);
            } finally {
                handle.inferenceLock.unlock();
            }
        } finally {
            release(handle);
        }
    }

    // ------------------------------------------------------------------ install smoke (package-backed)

    /** Load an encoder STRICTLY from the compiled encoder.wdmlpack, embed a token, verify a finite vector. */
    void smokeLoadEncoder(Path modelDirectory, EmbeddingModelId modelId) throws Exception {
        MlRuntime smokeRuntime = MlRuntime.builder().backend(backend).build();
        try (EmbeddingModelHandle handle = smokeRuntime.loadEmbeddings(EmbeddingConfig.builder()
                .model(modelId)
                .modelDir(modelDirectory)
                .prefix(null) // no built-in prefix; the request decides raw/query/passage
                .build())) {
            if (!handle.isReady()) {
                throw new IllegalStateException("embedding model loaded but reports not ready");
            }
            float[] vector = handle.embed("smoke");
            if (vector == null || vector.length == 0 || vector.length != handle.dimension()) {
                throw new IllegalStateException("embedding smoke produced no usable vector");
            }
            for (float value : vector) {
                if (!Float.isFinite(value)) {
                    throw new IllegalStateException("embedding smoke produced a non-finite value");
                }
            }
        }
    }

    /** Load a reranker STRICTLY from the compiled reranker.wdmlpack and score a trivial pair. */
    void smokeLoadReranker(Path modelDirectory, RerankerModelId modelId) throws Exception {
        MlRuntime smokeRuntime = MlRuntime.builder().backend(backend).build();
        try (RerankerModelHandle handle = smokeRuntime.loadReranker(RerankerConfig.builder()
                .model(modelId)
                .modelDir(modelDirectory)
                .build())) {
            if (!handle.isReady()) {
                throw new IllegalStateException("reranker model loaded but reports not ready");
            }
            List<RerankResult> results = handle.rerank("smoke", List.of("a", "b"), 0);
            if (results == null || results.isEmpty()) {
                throw new IllegalStateException("reranker smoke produced no scores");
            }
        }
    }

    // ------------------------------------------------------------------ handle lifecycle

    /** The virtual names of the currently warm handles (drives /api/ps). */
    Set<String> loadedVirtualNames() {
        synchronized (lock) {
            return new java.util.LinkedHashSet<>(handles.keySet());
        }
    }

    boolean isLoaded(String virtualName) {
        synchronized (lock) {
            return handles.containsKey(virtualName);
        }
    }

    /** keep_alive:0 / explicit unload: mark closing (no new leases); close now if idle, else when idle. */
    void unload(String virtualName) {
        LoadedHandle handle;
        synchronized (lock) {
            handle = handles.get(virtualName);
        }
        if (handle != null && handle.markClosingAndMaybeClose()) {
            forget(virtualName, handle);
        }
    }

    /**
     * Ensure the handle is unloaded before its files are deleted: stop new leases, let running inference
     * finish, then close. Bounded wait so a wedged inference cannot block deletion forever.
     */
    void unloadForDelete(String virtualName) {
        LoadedHandle handle;
        synchronized (lock) {
            handle = handles.get(virtualName);
        }
        if (handle == null) {
            return;
        }
        if (handle.markClosingAndMaybeClose()) {
            forget(virtualName, handle);
            return;
        }
        long deadline = System.nanoTime() + 30_000_000_000L; // 30s
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            if (handle.closeIfIdle()) {
                forget(virtualName, handle);
                return;
            }
        }
        System.err.println("[local-runtime] delete: handle for " + virtualName
                + " still busy after wait; leaving it to close on the last release");
    }

    private LoadedHandle acquireEmbedding(LocalModel model) throws Exception {
        return acquire(model, true);
    }

    private LoadedHandle acquireReranker(LocalModel model) throws Exception {
        return acquire(model, false);
    }

    private LoadedHandle acquire(LocalModel model, boolean embedding) throws Exception {
        String virtualName = model.virtualName();
        while (true) {
            LoadedHandle existing;
            synchronized (lock) {
                existing = handles.get(virtualName);
            }
            if (existing != null) {
                if (existing.acquire()) {
                    return existing;
                }
                // It is closing; drop it and load a fresh one.
                forget(virtualName, existing);
            }
            LoadedHandle loaded = load(model, embedding);
            synchronized (lock) {
                LoadedHandle race = handles.get(virtualName);
                if (race == null) {
                    handles.put(virtualName, loaded);
                    loaded.acquire();
                    return loaded;
                }
                // Another thread loaded it first; discard ours and retry the fast path.
                loaded.forceClose();
            }
        }
    }

    private LoadedHandle load(LocalModel model, boolean embedding) throws Exception {
        synchronized (lock) {
            if (runtime == null) {
                runtime = MlRuntime.builder().backend(backend).build();
            }
        }
        long start = System.nanoTime();
        AutoCloseable handle;
        if (embedding) {
            handle = runtime.loadEmbeddings(EmbeddingConfig.builder()
                    .model(EmbeddingModelId.valueOf(model.runtimeModelId()))
                    .modelDir(model.directory())
                    .prefix(null)
                    .build());
        } else {
            handle = runtime.loadReranker(RerankerConfig.builder()
                    .model(RerankerModelId.valueOf(model.runtimeModelId()))
                    .modelDir(model.directory())
                    .build());
        }
        long loadNanos = System.nanoTime() - start;
        System.err.println("[local-runtime] loaded " + model.virtualName() + " on backend " + backend);
        return new LoadedHandle(model.virtualName(), handle, loadNanos);
    }

    private void release(LoadedHandle handle) {
        if (handle.release()) {
            forget(handle.virtualName, handle);
        }
    }

    private void forget(String virtualName, LoadedHandle handle) {
        synchronized (lock) {
            if (handles.get(virtualName) == handle) {
                handles.remove(virtualName);
            }
        }
    }

    @Override
    public void close() {
        List<LoadedHandle> all;
        synchronized (lock) {
            all = new ArrayList<>(handles.values());
            handles.clear();
        }
        for (LoadedHandle handle : all) {
            handle.forceClose();
        }
    }

    /** A warm handle with a lease count so unload/delete never race an in-flight inference. */
    private static final class LoadedHandle {
        private final String virtualName;
        private final AutoCloseable handle;
        private final long loadDurationNanos;
        private final ReentrantLock inferenceLock = new ReentrantLock();
        private int activeLeases;
        private boolean closing;
        private boolean closed;

        LoadedHandle(String virtualName, AutoCloseable handle, long loadDurationNanos) {
            this.virtualName = virtualName;
            this.handle = handle;
            this.loadDurationNanos = loadDurationNanos;
        }

        synchronized boolean acquire() {
            if (closing || closed) {
                return false;
            }
            activeLeases++;
            return true;
        }

        /** @return true when this release closed the handle (caller must forget it). */
        synchronized boolean release() {
            activeLeases--;
            if (closing && activeLeases == 0 && !closed) {
                doClose();
                return true;
            }
            return false;
        }

        /** @return true when the handle was closed immediately (it was idle). */
        synchronized boolean markClosingAndMaybeClose() {
            closing = true;
            if (activeLeases == 0 && !closed) {
                doClose();
                return true;
            }
            return false;
        }

        synchronized boolean closeIfIdle() {
            if (activeLeases == 0 && !closed) {
                closing = true;
                doClose();
                return true;
            }
            return false;
        }

        synchronized void forceClose() {
            if (!closed) {
                doClose();
            }
        }

        private void doClose() {
            closed = true;
            try {
                handle.close();
            } catch (Exception ex) {
                System.err.println("[local-runtime] close failed for " + virtualName + ": "
                        + ex.getMessage());
            }
            System.err.println("[local-runtime] unloaded " + virtualName);
        }
    }
}
