package com.aresstack.askai.batch.service;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reload the audio-processing profiles a batch run may use. Reads from the profile source (injected as a
 * {@link Supplier}, so this service never touches the file system or JSON directly) off the EDT and
 * publishes a {@link BatchProfileCatalogLoadedEvent}; the UI forwards it to the EDT itself. Kept separate
 * from the model catalog so the two loads succeed or fail independently.
 */
public final class BatchProfileCatalogService {

    private final Supplier<List<AudioProcessingProfile>> profileSupplier;
    private final ExecutorService executor;

    public BatchProfileCatalogService(Supplier<List<AudioProcessingProfile>> profileSupplier) {
        if (profileSupplier == null) {
            throw new IllegalArgumentException("Profile supplier must not be null.");
        }
        this.profileSupplier = profileSupplier;
        this.executor = Executors.newSingleThreadExecutor(new CatalogThreadFactory());
    }

    /** Load the profiles asynchronously and hand the result to {@code observer}. */
    public void loadAsync(final Consumer<BatchProfileCatalogLoadedEvent> observer) {
        executor.execute(new Runnable() {
            public void run() {
                observer.accept(resolveCatalog());
            }
        });
    }

    private BatchProfileCatalogLoadedEvent resolveCatalog() {
        try {
            return BatchProfileCatalogLoadedEvent.loaded(profileSupplier.get());
        } catch (Exception ex) {
            return BatchProfileCatalogLoadedEvent.failed(
                    ex.getMessage() == null ? ex.toString() : ex.getMessage());
        }
    }

    private static final class CatalogThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-batch-profiles-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
