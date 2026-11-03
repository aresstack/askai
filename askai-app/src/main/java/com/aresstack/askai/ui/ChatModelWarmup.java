package com.aresstack.askai.ui;

import com.aresstack.askai.localmodels.LocalModelNames;
import com.aresstack.askai.service.OllamaService;

import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Keeps the composer's chat model warm: when a chat starts the selected model is loaded right away (not on the
 * first message), and switching the model unloads the previous one and loads the new one. Reuses the same
 * {@link OllamaService#loadModel}/{@link OllamaService#unloadModel} calls as the Models page; both run
 * asynchronously in the service, so the UI never blocks. Local (in-process) models and models that are still
 * needed elsewhere (e.g. the dictation model) are never unloaded.
 */
final class ChatModelWarmup {

    private final OllamaService ollamaService;
    private final Supplier<String> keepAlive;
    private final Predicate<String> neededElsewhere;
    private final Consumer<String> status;
    private String active;

    ChatModelWarmup(OllamaService ollamaService, Supplier<String> keepAlive,
                    Predicate<String> neededElsewhere, Consumer<String> status) {
        this.ollamaService = ollamaService;
        this.keepAlive = keepAlive;
        this.neededElsewhere = neededElsewhere;
        this.status = status;
    }

    /** Makes {@code modelName} the loaded chat model; a repeated call with the same model does nothing. */
    synchronized void activate(String modelName) {
        if (modelName == null || modelName.trim().isEmpty() || modelName.equals(active)) {
            return;
        }
        String previous = active;
        active = modelName;
        if (previous != null && isOllama(previous) && !neededElsewhere.test(previous)) {
            ollamaService.unloadModel(previous, listener("unload", previous));
        }
        if (isOllama(modelName)) {
            ollamaService.loadModel(modelName, keepAlive.get(), listener("load", modelName));
        }
    }

    synchronized String activeModel() {
        return active;
    }

    private static boolean isOllama(String name) {
        return !LocalModelNames.isLocalModelName(name);
    }

    private OllamaService.ActionListener listener(final String verb, final String name) {
        return new OllamaService.ActionListener() {
            @Override
            public void onComplete(String message) {
                // silent on success: the chat simply answers faster
            }

            @Override
            public void onError(Exception ex) {
                status.accept("Could not " + verb + " " + name + ": " + ex.getMessage());
            }
        };
    }
}
