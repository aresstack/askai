package com.aresstack.askai.service;

import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.client.OllamaChatCompletion;
import com.aresstack.askai.client.OllamaChatStreamListener;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.client.OllamaPullProgress;
import com.aresstack.askai.client.OllamaRunningModelInfo;
import com.aresstack.askai.localmodels.LocalModelNames;
import com.aresstack.askai.localmodels.LocalModelRuntimeManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The VIRTUAL Ollama container (R0.3): aggregates the remote Ollama server and AskAI's local model
 * runtime into ONE {@link OllamaService} — the single source of every model list in the UI. Lists
 * are fault-ISOLATED per source (a dead remote server never hides local models and vice versa; only
 * when ALL sources fail the call errors). Every per-model operation routes to the container that
 * OWNS the model by its name namespace — there is no hidden fallback: a local model is never sent
 * to the remote server.
 */
public final class VirtualOllamaContainerService implements OllamaService {

    private final RemoteOllamaContainerSource remote;
    private final LocalAskAiContainerSource local;
    private final ExecutorService executor;

    public VirtualOllamaContainerService(AskAiModel model, LocalModelRuntimeManager localManager) {
        this.remote = new RemoteOllamaContainerSource(model);
        this.local = new LocalAskAiContainerSource(localManager);
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    /** All sources in display order (Remote first, Local second). */
    public List<OllamaContainerSource> getSources() {
        List<OllamaContainerSource> sources = new ArrayList<OllamaContainerSource>();
        sources.add(remote);
        sources.add(local);
        return sources;
    }

    // ------------------------------------------------------------------ aggregated lists

    @Override
    public Task listModelNames(final ModelNamesListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    List<String> names = new ArrayList<String>();
                    for (OllamaModelInfo info : loadInstalledModelsNow()) {
                        names.add(info.getDisplayName());
                    }
                    listener.onModelNames(names);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task listChatModelNames(final ModelNamesListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    listener.onModelNames(loadChatModelNamesNow());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    /**
     * CHAT-capable model names: remote models unfiltered; LOCAL models only when their /api/show
     * capabilities include completion — a rerank-only local model never appears in a chat
     * dropdown, while a locally installed chat model stays selectable. A local model whose
     * capabilities cannot be read is EXCLUDED (unknown local capabilities never unlock chat).
     */
    public List<String> loadChatModelNamesNow() throws Exception {
        List<String> names = new ArrayList<String>();
        for (OllamaModelInfo info : loadInstalledModelsNow()) {
            if (info.isLocal() && !localModelCanChat(info.getDisplayName())) {
                continue;
            }
            names.add(info.getDisplayName());
        }
        return names;
    }

    private boolean localModelCanChat(String modelName) {
        try {
            java.util.List<String> capabilities = loadModelInfoNow(modelName).getCapabilities();
            // Capability-based, from /api/show: a chat-usable model advertises completion or chat.
            return capabilities.contains("completion") || capabilities.contains("chat");
        } catch (Exception unknown) {
            return false;
        }
    }

    /**
     * EMBEDDING-capable model names: a model appears only when its /api/show capabilities include
     * "embedding". Unlike chat, embedding is a capability EVERY source must advertise, so the filter is
     * applied to remote and local models alike; a model whose capabilities cannot be read is EXCLUDED.
     * Synchronous — for callers already off the EDT (the central AI-models settings panel).
     */
    public List<String> loadEmbeddingModelNamesNow() throws Exception {
        List<String> names = new ArrayList<String>();
        for (OllamaModelInfo info : loadInstalledModelsNow()) {
            if (modelCanEmbed(info.getDisplayName())) {
                names.add(info.getDisplayName());
            }
        }
        return names;
    }

    private boolean modelCanEmbed(String modelName) {
        try {
            return loadModelInfoNow(modelName).getCapabilities().contains("embedding");
        } catch (Exception unknown) {
            return false;
        }
    }

    @Override
    public Task listInstalledModels(final InstalledModelsListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    listener.onInstalledModels(loadInstalledModelsNow());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task listRunningModels(final RunningModelsListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    listener.onRunningModels(loadRunningModelsNow());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    /**
     * Synchronous aggregation for callers already off the EDT (global catalog refresh). Fault
     * isolation: per-source failures are collected; only when EVERY contributing source failed the
     * whole call fails.
     */
    public List<OllamaModelInfo> loadInstalledModelsNow() throws Exception {
        List<OllamaModelInfo> models = new ArrayList<OllamaModelInfo>();
        List<Exception> failures = new ArrayList<Exception>();
        int contributing = 0;
        contributing++;
        try {
            for (OllamaModelInfo info : remote.createClient().getInstalledModels()) {
                models.add(info.withContainer(remote.getContainerId(), remote.getDisplayName(),
                        false));
            }
        } catch (Exception remoteDown) {
            failures.add(remoteDown);
        }
        if (local.hasAnythingToServe()) {
            contributing++;
            try {
                for (OllamaModelInfo info : local.createClient().getInstalledModels()) {
                    models.add(info.withContainer(local.getContainerId(), local.getDisplayName(),
                            true));
                }
            } catch (Exception localDown) {
                failures.add(localDown);
            }
        }
        if (!failures.isEmpty() && failures.size() == contributing) {
            throw combined(failures);
        }
        return models;
    }

    /** Synchronous running-model aggregation with the same fault isolation. */
    public List<OllamaRunningModelInfo> loadRunningModelsNow() throws Exception {
        List<OllamaRunningModelInfo> models = new ArrayList<OllamaRunningModelInfo>();
        List<Exception> failures = new ArrayList<Exception>();
        int contributing = 0;
        contributing++;
        try {
            for (OllamaRunningModelInfo info : remote.createClient().getRunningModels()) {
                models.add(info.withContainer(remote.getContainerId(), remote.getDisplayName(),
                        false));
            }
        } catch (Exception remoteDown) {
            failures.add(remoteDown);
        }
        if (local.hasAnythingToServe()) {
            contributing++;
            try {
                for (OllamaRunningModelInfo info : local.createClient().getRunningModels()) {
                    models.add(info.withContainer(local.getContainerId(), local.getDisplayName(),
                            true));
                }
            } catch (Exception localDown) {
                failures.add(localDown);
            }
        }
        if (!failures.isEmpty() && failures.size() == contributing) {
            throw combined(failures);
        }
        return models;
    }

    /** Synchronous, ROUTED model info (used by the catalog refresh and capability probes). */
    public OllamaModelInfoView loadModelInfoNow(String modelName) throws Exception {
        OllamaContainerSource source = sourceFor(modelName);
        return source.createClient().getModelInfo(modelName)
                .withContainer(source.getContainerId(), source.getDisplayName(), source.isLocal());
    }

    // ------------------------------------------------------------------ server-level calls

    @Override
    public Task getServerVersion(final ServerVersionListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    listener.onServerVersion(remote.createClient().getVersion());
                } catch (Exception remoteDown) {
                    // Fault isolation: a reachable LOCAL runtime still reports a version line.
                    try {
                        if (local.hasAnythingToServe()) {
                            listener.onServerVersion("Ollama unreachable — local runtime "
                                    + local.createClient().getVersion());
                            return;
                        }
                    } catch (Exception ignored) {
                        // fall through to the remote error
                    }
                    listener.onError(remoteDown);
                }
            }
        });
    }

    @Override
    public Task ping(final ActionListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    boolean reachable = remote.createClient().ping();
                    listener.onComplete(reachable ? "Ollama is reachable."
                            : "Ollama did not respond to ping.");
                } catch (Exception remoteDown) {
                    try {
                        if (local.hasAnythingToServe() && local.createClient().ping()) {
                            listener.onComplete(
                                    "Ollama is unreachable; the LOCAL runtime is reachable.");
                            return;
                        }
                    } catch (Exception ignored) {
                    }
                    listener.onError(remoteDown);
                }
            }
        });
    }

    // ------------------------------------------------------------------ routed per-model calls

    @Override
    public Task getModelInfo(final String modelName, final ModelInfoListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    listener.onModelInfo(loadModelInfoNow(modelName));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task deleteModel(final String modelName, final ActionListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    routedClient(modelName).deleteModel(modelName);
                    listener.onComplete("Deleted " + modelName + ".");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task unloadModel(final String modelName, final ActionListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    routedClient(modelName).unloadModel(modelName);
                    listener.onComplete("Unloaded " + modelName + ".");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task pullModel(final String modelName, final PullListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    if (LocalModelNames.isLocalModelName(modelName)) {
                        throw new IllegalArgumentException("Local models are installed through "
                                + "the AskAI Hugging Face pane, not pulled from a registry.");
                    }
                    remote.createClient().pullModel(modelName,
                            new com.aresstack.askai.client.OllamaPullListener() {
                                public void onProgress(OllamaPullProgress progress) {
                                    listener.onProgress(progress);
                                }
                            });
                    listener.onComplete("Pulled " + modelName + ".");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task generate(final String modelName, final String prompt,
                         final ActionListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    // Routed honestly: the LOCAL container answers with its typed capability error
                    // for non-generate models — never silently forwarded to the remote server.
                    listener.onComplete(routedClient(modelName).generate(modelName, prompt));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task embed(final String modelName, final String input, final EmbedListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    List<List<Double>> embeddings = routedClient(modelName)
                            .embed(modelName, Collections.singletonList(input));
                    int dimensions = embeddings.isEmpty() ? 0 : embeddings.get(0).size();
                    listener.onEmbedding(embeddings.size(), dimensions);
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task streamChat(final ChatRequest request, final ChatListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    routedClient(request.getModelName()).streamChat(request.getModelName(),
                            request.getMessages(), request.getKeepAlive(),
                            request.getThinking().toWireValue(),
                            new OllamaChatStreamListener() {
                                public void onThinkingDelta(String delta) {
                                    listener.onThinkingDelta(delta);
                                }

                                public void onContent(String content) {
                                    listener.onContent(content);
                                }

                                public void onToolCalls(java.util.List<com.aresstack.askai
                                        .client.OllamaToolCall> toolCalls) {
                                    listener.onToolCalls(toolCalls);
                                }

                                public void onStatus(String status) {
                                    listener.onStatus(status);
                                }

                                public void onComplete(OllamaChatCompletion completion) {
                                    listener.onComplete(toChatResult(completion));
                                }
                            });
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private OllamaContainerSource sourceFor(String modelName) {
        return local.ownsModel(modelName) ? local : remote;
    }

    private AskAiOllamaClient routedClient(String modelName) throws Exception {
        return sourceFor(modelName).createClient();
    }

    private static Exception combined(List<Exception> failures) {
        StringBuilder message = new StringBuilder("All model containers failed:");
        for (Exception failure : failures) {
            message.append(' ').append(failure.getMessage()).append(';');
        }
        return new Exception(message.toString(), failures.get(0));
    }

    private static ChatResult toChatResult(OllamaChatCompletion completion) {
        if (completion == null) {
            return new ChatResult("", 0L, 0L);
        }
        return new ChatResult(completion.getThinking(), completion.getContent(),
                completion.getToolCalls(), completion.getEvalCount(),
                completion.getEvalDurationNanos());
    }

    private Task submit(Runnable runnable) {
        return new FutureTaskAdapter(executor.submit(runnable));
    }

    private static final class FutureTaskAdapter implements Task {
        private final Future<?> future;

        private FutureTaskAdapter(Future<?> future) {
            this.future = future;
        }

        public void cancel() {
            future.cancel(true);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable,
                    "askai-virtual-ollama-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
