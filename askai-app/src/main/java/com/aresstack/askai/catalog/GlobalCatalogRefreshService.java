package com.aresstack.askai.catalog;

import com.aresstack.audio.profile.AudioProcessingProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A single global catalog refresh, observed by the UI. Subscribers (chat tabs, the batch panel) register
 * once and receive every {@link GlobalCatalogSnapshot}; they are never asked to re-query Ollama themselves.
 * The refresh runs off the EDT on a single-thread executor, cannot run twice in parallel, and marshals all
 * subscriber callbacks back onto the EDT through the supplied {@code uiExecutor}. Each catalog is loaded
 * independently, so one failing source does not discard the others.
 */
public final class GlobalCatalogRefreshService {

    /** Loads one catalog; throwing marks that catalog as failed (the others still apply). */
    public interface CatalogLoader<T> {
        List<T> load() throws Exception;
    }

    public interface Listener {
        void onRefreshStarted();

        void onCatalogRefreshed(GlobalCatalogSnapshot snapshot);
    }

    private final CatalogLoader<String> chatModelsLoader;
    private final CatalogLoader<String> audioModelsLoader;
    private final CatalogLoader<AudioProcessingProfile> profilesLoader;
    private final Consumer<Runnable> uiExecutor;
    private final ExecutorService executor;
    private final List<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    public GlobalCatalogRefreshService(CatalogLoader<String> chatModelsLoader,
                                       CatalogLoader<String> audioModelsLoader,
                                       CatalogLoader<AudioProcessingProfile> profilesLoader,
                                       Consumer<Runnable> uiExecutor) {
        if (chatModelsLoader == null || audioModelsLoader == null || profilesLoader == null || uiExecutor == null) {
            throw new IllegalArgumentException("Loaders and uiExecutor must not be null.");
        }
        this.chatModelsLoader = chatModelsLoader;
        this.audioModelsLoader = audioModelsLoader;
        this.profilesLoader = profilesLoader;
        this.uiExecutor = uiExecutor;
        this.executor = Executors.newSingleThreadExecutor(new RefreshThreadFactory());
    }

    public void subscribe(Listener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void unsubscribe(Listener listener) {
        listeners.remove(listener);
    }

    /**
     * Start a refresh unless one is already running.
     *
     * @return true if a refresh was started, false if one was already in progress (no parallel refresh)
     */
    public boolean refresh() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        notifyUi(new Consumer<Listener>() {
            public void accept(Listener listener) {
                listener.onRefreshStarted();
            }
        });
        executor.execute(new Runnable() {
            public void run() {
                final GlobalCatalogSnapshot snapshot = loadSnapshot();
                try {
                    notifyUi(new Consumer<Listener>() {
                        public void accept(Listener listener) {
                            listener.onCatalogRefreshed(snapshot);
                        }
                    });
                } finally {
                    running.set(false);
                }
            }
        });
        return true;
    }

    /** @return whether a refresh is currently in progress (for tests / diagnostics). */
    public boolean isRunning() {
        return running.get();
    }

    private GlobalCatalogSnapshot loadSnapshot() {
        List<String> failures = new ArrayList<String>();
        boolean modelsLoaded = true;
        List<String> chatModels = Collections.emptyList();
        try {
            chatModels = chatModelsLoader.load();
        } catch (Exception ex) {
            modelsLoaded = false;
            failures.add("Models: " + message(ex));
        }
        boolean audioModelsLoaded = true;
        List<String> audioModels = Collections.emptyList();
        try {
            audioModels = audioModelsLoader.load();
        } catch (Exception ex) {
            audioModelsLoaded = false;
            failures.add("Audio models: " + message(ex));
        }
        boolean profilesLoaded = true;
        List<AudioProcessingProfile> profiles = Collections.emptyList();
        try {
            profiles = profilesLoader.load();
        } catch (Exception ex) {
            profilesLoaded = false;
            failures.add("Audio profiles: " + message(ex));
        }
        return new GlobalCatalogSnapshot(modelsLoaded, chatModels, audioModelsLoaded, audioModels,
                profilesLoaded, profiles, failures);
    }

    private void notifyUi(final Consumer<Listener> action) {
        uiExecutor.accept(new Runnable() {
            public void run() {
                for (Listener listener : listeners) {
                    action.accept(listener);
                }
            }
        });
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null ? throwable.toString() : throwable.getMessage();
    }

    private static final class RefreshThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-global-refresh-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
