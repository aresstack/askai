package com.aresstack.askai.java8.modelport;

import com.aresstack.askai.java8.client.OllamaRequestException;
import com.aresstack.askai.java8.client.OllamaResidentModel;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The RESIDENCY view of the model port (ADR 0001, 4.5): which models each endpoint reports as loaded
 * ({@code GET /api/ps}: {@code size}, {@code size_vram}, {@code expires_at}, {@code context_length}).
 *
 * <p>Advisory by design. Ollama does not report the KV-cache share or the slot count, may unload a model under
 * memory pressure before {@code expires_at}, and its eviction order is internal. The view is cached and
 * refreshed in the background; readers never block on a probe. The last loaded size seen per model is kept
 * after it unloads, because memory planning needs to know how big a model is before it is loaded again.</p>
 */
final class ResidencyMonitor implements AutoCloseable {

    /** Where the loaded models come from. */
    interface Source {
        List<String> endpointIds();

        /** The loaded models of an endpoint, or null when the endpoint is not running (nothing to ask). */
        List<OllamaResidentModel> loaded(String endpointId) throws OllamaRequestException;
    }

    /** One loaded model. */
    static final class Loaded {
        final String endpointId;
        final String model;
        final long sizeBytes;
        final long vramBytes;
        final Instant expiresAt;
        final int contextLength;

        Loaded(String endpointId, String model, long sizeBytes, long vramBytes, Instant expiresAt,
               int contextLength) {
            this.endpointId = endpointId;
            this.model = model;
            this.sizeBytes = sizeBytes;
            this.vramBytes = vramBytes;
            this.expiresAt = expiresAt;
            this.contextLength = contextLength;
        }
    }

    /** A cached view; {@code known} is false until a probe of every running endpoint succeeded. */
    static final class View {
        final Instant capturedAt;
        final List<Loaded> loaded;
        final boolean known;

        View(Instant capturedAt, List<Loaded> loaded, boolean known) {
            this.capturedAt = capturedAt;
            this.loaded = Collections.unmodifiableList(loaded);
            this.known = known;
        }
    }

    private final Source source;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "model-port-residency");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean refreshing = new AtomicBoolean();
    private final Map<String, Long> lastKnownSizes = new ConcurrentHashMap<String, Long>();
    private volatile View view = new View(Instant.EPOCH, new ArrayList<Loaded>(), false);

    ResidencyMonitor(Source source) {
        this.source = source;
    }

    View view() {
        return view;
    }

    /** The loaded size last seen for a model on an endpoint (contexts included), or -1 if never seen. */
    long lastKnownSize(String endpointId, String model) {
        Long size = lastKnownSizes.get(endpointId + "\n" + model);
        return size == null ? -1L : size;
    }

    /** Starts a background refresh when the cached view is older than {@code maxAge}; never blocks. */
    void refreshIfOlderThan(Duration maxAge) {
        if (view.capturedAt.plus(maxAge).isAfter(Instant.now())) {
            return;
        }
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            worker.submit(() -> {
                try {
                    refreshNow();
                } finally {
                    refreshing.set(false);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException closed) {
            refreshing.set(false);
        }
    }

    /** Probes every endpoint now (on the calling thread). */
    void refreshNow() {
        List<Loaded> loaded = new ArrayList<Loaded>();
        boolean known = true;
        for (String endpointId : source.endpointIds()) {
            List<OllamaResidentModel> models;
            try {
                models = source.loaded(endpointId);
            } catch (OllamaRequestException unreachable) {
                known = false;
                continue;
            } catch (RuntimeException unexpected) {
                known = false;
                continue;
            }
            if (models == null) {
                continue; // not running: nothing loaded there
            }
            for (OllamaResidentModel model : models) {
                loaded.add(new Loaded(endpointId, model.getName(), model.getSizeBytes(), model.getVramBytes(),
                        instant(model.getExpiresAt()), model.getContextLength()));
                if (model.getSizeBytes() > 0) {
                    lastKnownSizes.put(endpointId + "\n" + model.getName(), model.getSizeBytes());
                }
            }
        }
        view = new View(Instant.now(), loaded, known);
    }

    @Override
    public void close() {
        worker.shutdownNow();
    }

    private static Instant instant(String text) {
        if (text == null || text.trim().isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text.trim()).toInstant();
        } catch (DateTimeParseException unparsable) {
            return null;
        }
    }
}
