package com.aresstack.askai.service;

import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.client.OllamaChatCompletion;
import com.aresstack.askai.client.OllamaChatStreamListener;
import com.aresstack.askai.client.OllamaPullProgress;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service implementation backed by the {@code ollama4j} adapter
 * ({@link AskAiOllamaClient}). All regular Ollama REST calls run through the
 * adapter, which maps library DTOs to AskAI domain models. The service itself
 * never parses JSON and never touches the Swing thread.
 */
public final class DefaultOllamaService implements OllamaService {

    private final AskAiModel model;
    private final ExecutorService executor;

    public DefaultOllamaService(AskAiModel model) {
        this.model = model;
        this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory());
    }

    @Override
    public Task listModelNames(final ModelNamesListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onModelNames(client().getModelNames());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task listChatModelNames(final ModelNamesListener listener) {
        // A plain remote Ollama world has no local models to filter.
        return listModelNames(listener);
    }

    @Override
    public Task listInstalledModels(final InstalledModelsListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onInstalledModels(client().getInstalledModels());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task listRunningModels(final RunningModelsListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onRunningModels(client().getRunningModels());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task getServerVersion(final ServerVersionListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onServerVersion(client().getVersion());
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task ping(final ActionListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean reachable = client().ping();
                    listener.onComplete(reachable ? "Ollama is reachable." : "Ollama did not respond to ping.");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task getModelInfo(final String modelName, final ModelInfoListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onModelInfo(client().getModelInfo(modelName));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task deleteModel(final String modelName, final ActionListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    client().deleteModel(modelName);
                    listener.onComplete("Deleted " + modelName + ".");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task loadModel(final String modelName, final String keepAlive, final ActionListener listener) {
        return submit(new Runnable() {
            public void run() {
                try {
                    client().loadModel(modelName, keepAlive);
                    listener.onComplete("Loaded " + modelName + ".");
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task unloadModel(final String modelName, final ActionListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    client().unloadModel(modelName);
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
            @Override
            public void run() {
                try {
                    client().pullModel(modelName, new com.aresstack.askai.client.OllamaPullListener() {
                        @Override
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
    public Task generate(final String modelName, final String prompt, final ActionListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    listener.onComplete(client().generate(modelName, prompt));
                } catch (Exception ex) {
                    listener.onError(ex);
                }
            }
        });
    }

    @Override
    public Task embed(final String modelName, final String input, final EmbedListener listener) {
        return submit(new Runnable() {
            @Override
            public void run() {
                try {
                    List<List<Double>> embeddings = client().embed(modelName, Collections.singletonList(input));
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
            @Override
            public void run() {
                try {
                    client().streamChat(request.getModelName(), request.getMessages(), request.getKeepAlive(),
                            request.getThinking().toWireValue(),
                            new OllamaChatStreamListener() {
                                @Override
                                public void onThinkingDelta(String delta) {
                                    listener.onThinkingDelta(delta);
                                }

                                @Override
                                public void onContent(String content) {
                                    listener.onContent(content);
                                }

                                @Override
                                public void onToolCalls(java.util.List<com.aresstack.askai.client.OllamaToolCall> toolCalls) {
                                    listener.onToolCalls(toolCalls);
                                }

                                @Override
                                public void onStatus(String status) {
                                    listener.onStatus(status);
                                }

                                @Override
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

    private AskAiOllamaClient client() {
        return new AskAiOllamaClient(model.getOllamaBaseUrl());
    }

    private Task submit(Runnable runnable) {
        return new FutureTaskAdapter(executor.submit(runnable));
    }

    private static ChatResult toChatResult(OllamaChatCompletion completion) {
        if (completion == null) {
            return new ChatResult("", 0L, 0L);
        }
        return new ChatResult(completion.getThinking(), completion.getContent(), completion.getToolCalls(),
                completion.getEvalCount(), completion.getEvalDurationNanos());
    }

    private static final class FutureTaskAdapter implements Task {
        private final Future<?> future;

        private FutureTaskAdapter(Future<?> future) {
            this.future = future;
        }

        @Override
        public void cancel() {
            future.cancel(true);
        }
    }

    private static final class DaemonThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-ollama-service-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
