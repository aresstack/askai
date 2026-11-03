package com.aresstack.askai.ui;

import com.aresstack.askai.localmodels.LocalModelNames;
import com.aresstack.askai.service.OllamaService;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Keeps the composer's chat model warm: when a chat starts the selected model is loaded right away (not on the
 * first message), and switching the model unloads the previous one and loads the new one. Reuses the same
 * {@link OllamaService#loadModel}/{@link OllamaService#unloadModel} calls as the Models page.
 * <p>
 * All load/unload calls run one after another on a single background thread (never on the EDT), each waiting for
 * the previous one to complete, so a quick A → B → A switch cannot end with A unloaded. A model is only unloaded
 * when no other open chat still uses it and it is not needed elsewhere (e.g. as dictation model); local
 * (in-process) models are never touched. A failed load is forgotten so the next activation retries it.
 */
final class ChatModelWarmup {

    /** Chat models in use per open chat session, shared by all chats of this process. */
    private static final Map<String, Integer> IN_USE = new HashMap<String, Integer>();
    private static final ExecutorService QUEUE = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-model-warmup");
        thread.setDaemon(true);
        return thread;
    });
    private static final long STEP_TIMEOUT_MINUTES = 10;

    private final OllamaService ollamaService;
    private final Supplier<String> keepAlive;
    private final Predicate<String> neededElsewhere;
    private final Consumer<String> status;
    private final ExecutorService queue;
    private String active;

    ChatModelWarmup(OllamaService ollamaService, Supplier<String> keepAlive,
                    Predicate<String> neededElsewhere, Consumer<String> status) {
        this(ollamaService, keepAlive, neededElsewhere, status, QUEUE);
    }

    ChatModelWarmup(OllamaService ollamaService, Supplier<String> keepAlive,
                    Predicate<String> neededElsewhere, Consumer<String> status, ExecutorService queue) {
        this.ollamaService = ollamaService;
        this.keepAlive = keepAlive;
        this.neededElsewhere = neededElsewhere;
        this.status = status;
        this.queue = queue;
    }

    /** Makes {@code modelName} the loaded chat model; a repeated call with the same model does nothing. */
    synchronized void activate(String modelName) {
        if (modelName == null || modelName.trim().isEmpty() || modelName.equals(active)) {
            return;
        }
        String previous = active;
        active = modelName;
        acquire(modelName);
        boolean unloadPrevious = previous != null && release(previous) && !neededElsewhere.test(previous);
        if (unloadPrevious && isOllama(previous)) {
            enqueue(false, previous);
        }
        if (isOllama(modelName)) {
            enqueue(true, modelName);
        }
    }

    /** Called when the chat closes: frees its model for unloading decisions of the other chats (no unload). */
    synchronized void release() {
        if (active != null) {
            release(active);
            active = null;
        }
    }

    synchronized String activeModel() {
        return active;
    }

    private static boolean isOllama(String name) {
        return !LocalModelNames.isLocalModelName(name);
    }

    private static void acquire(String name) {
        synchronized (IN_USE) {
            Integer count = IN_USE.get(name);
            IN_USE.put(name, count == null ? 1 : count + 1);
        }
    }

    /** @return true when no chat uses {@code name} any more */
    private static boolean release(String name) {
        synchronized (IN_USE) {
            Integer count = IN_USE.get(name);
            if (count == null || count <= 1) {
                IN_USE.remove(name);
                return true;
            }
            IN_USE.put(name, count - 1);
            return false;
        }
    }

    private static boolean inUse(String name) {
        synchronized (IN_USE) {
            return IN_USE.containsKey(name);
        }
    }

    private void enqueue(final boolean load, final String name) {
        queue.execute(() -> {
            if (!load && inUse(name)) {
                return; // re-selected (here or in another chat) meanwhile: keep it loaded
            }
            final CountDownLatch done = new CountDownLatch(1);
            OllamaService.ActionListener listener = new OllamaService.ActionListener() {
                @Override
                public void onComplete(String message) {
                    done.countDown();
                }

                @Override
                public void onError(Exception ex) {
                    if (load) {
                        forgetFailedLoad(name);
                    }
                    status.accept("Could not " + (load ? "load " : "unload ") + name + ": " + ex.getMessage());
                    done.countDown();
                }
            };
            if (load) {
                ollamaService.loadModel(name, keepAlive.get(), listener);
            } else {
                ollamaService.unloadModel(name, listener);
            }
            try {
                done.await(STEP_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private synchronized void forgetFailedLoad(String name) {
        if (name.equals(active)) { // only the latest selection; a stale failure never resets a newer one
            release(name);
            active = null;
        }
    }
}
