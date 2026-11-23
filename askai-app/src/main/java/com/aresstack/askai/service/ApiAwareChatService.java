package com.aresstack.askai.service;

import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.client.OllamaModelInfoView;
import com.aresstack.askai.client.OllamaRunningModelInfo;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.ModelProviderSettingsRepository;
import com.aresstack.askai.modelport.provider.AbortSignal;
import com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider;
import com.aresstack.askai.modelport.provider.ProviderCall;
import com.aresstack.askai.modelport.provider.ProviderCompletion;
import com.aresstack.askai.modelport.provider.ProviderStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;

/**
 * The chat window's model service: Ollama (and AskAI's local runtime) as before, or, while the OpenAI-compatible API
 * is the chosen provider, the API for everything a chat tab needs: its model list (the API's chat models, the
 * {@code chat} role first), the model details (text in, text out; images only for the {@code vision} role) and the
 * streamed answer over {@code /chat/completions} with the same provider the research uses. Loading and unloading do
 * nothing on the API. Everything else (model management, Ollama status) stays with the delegate.
 */
public final class ApiAwareChatService implements OllamaService {

    /** Parts of model ids an API offers that cannot chat (embedding, reranking, speech, vision encoders, OCR). */
    static final List<String> NOT_CHAT = Collections.unmodifiableList(Arrays.asList(
            "embed", "rerank", "tts", "vit-", "docling", "mineru", "bge-", "e5-", "whisper", "orpheus"));

    private final OllamaService delegate;
    private final ModelProviderSettingsRepository providers;
    private final OpenAiCompatibleModelProvider api;
    private final Executor background;

    public ApiAwareChatService(OllamaService delegate, ModelProviderSettingsRepository providers,
                               OpenAiCompatibleModelProvider api) {
        this(delegate, providers, api, task -> {
            Thread thread = new Thread(task, "api-chat");
            thread.setDaemon(true);
            thread.start();
        });
    }

    ApiAwareChatService(OllamaService delegate, ModelProviderSettingsRepository providers,
                        OpenAiCompatibleModelProvider api, Executor background) {
        this.delegate = delegate;
        this.providers = providers;
        this.api = api;
        this.background = background;
    }

    private ModelProviderSettings apiSettings() {
        ModelProviderSettings settings = providers.load();
        return settings.usesApi() ? settings : null;
    }

    /** The API's models a chat can use, the {@code chat} role's model first. */
    static List<String> chatModels(List<String> offered, String chatModel) {
        List<String> models = new ArrayList<String>();
        for (String id : offered) {
            String lower = id.toLowerCase(Locale.ROOT);
            boolean chat = true;
            for (String part : NOT_CHAT) {
                if (lower.contains(part)) {
                    chat = false;
                    break;
                }
            }
            if (chat) {
                models.add(id);
            }
        }
        if (chatModel != null && !chatModel.isEmpty()) {
            models.remove(chatModel);
            models.add(0, chatModel);
        }
        return models;
    }

    /**
     * The API's chat models, blocking (the global catalog refresh runs off the EDT), or {@code null} while Ollama is
     * the chosen provider, so the caller loads Ollama's list as before.
     */
    public List<String> apiChatModelNamesNow() throws Exception {
        ModelProviderSettings settings = apiSettings();
        if (settings == null) {
            return null;
        }
        return chatModels(api.modelNames(settings.getApiBaseUrl()),
                settings.apiRoleModel(ModelProviderSettings.ROLE_CHAT));
    }

    @Override
    public Task listModelNames(ModelNamesListener listener) {
        return apiSettings() == null ? delegate.listModelNames(listener) : listApiModels(listener);
    }

    @Override
    public Task listChatModelNames(ModelNamesListener listener) {
        return apiSettings() == null ? delegate.listChatModelNames(listener) : listApiModels(listener);
    }

    private Task listApiModels(final ModelNamesListener listener) {
        final Flag cancelled = new Flag();
        background.execute(() -> {
            ModelProviderSettings settings = apiSettings();
            if (settings == null) {
                return;
            }
            try {
                List<String> names = chatModels(api.modelNames(settings.getApiBaseUrl()),
                        settings.apiRoleModel(ModelProviderSettings.ROLE_CHAT));
                if (!cancelled.set) {
                    listener.onModelNames(names);
                }
            } catch (Exception failed) {
                if (!cancelled.set) {
                    listener.onError(failed);
                }
            }
        });
        return cancelled;
    }

    @Override
    public Task getModelInfo(String modelName, ModelInfoListener listener) {
        ModelProviderSettings settings = apiSettings();
        if (settings == null) {
            return delegate.getModelInfo(modelName, listener);
        }
        List<String> capabilities = new ArrayList<String>(Collections.singletonList("completion"));
        if (modelName != null && modelName.equals(settings.apiRoleModel("vision"))) {
            capabilities.add("vision");
        }
        listener.onModelInfo(new OllamaModelInfoView(null, "", "", "", "", capabilities,
                "openai-compatible", "OpenAI-compatible API", false));
        return new Flag();
    }

    @Override
    public Task listRunningModels(RunningModelsListener listener) {
        if (apiSettings() == null) {
            return delegate.listRunningModels(listener);
        }
        listener.onRunningModels(Collections.<OllamaRunningModelInfo>emptyList());
        return new Flag();
    }

    @Override
    public Task loadModel(String modelName, String keepAlive, ActionListener listener) {
        if (apiSettings() == null) {
            return delegate.loadModel(modelName, keepAlive, listener);
        }
        listener.onComplete("The API keeps its models ready; nothing to load.");
        return new Flag();
    }

    @Override
    public Task unloadModel(String modelName, ActionListener listener) {
        if (apiSettings() == null) {
            return delegate.unloadModel(modelName, listener);
        }
        listener.onComplete("Nothing to unload on the API.");
        return new Flag();
    }

    @Override
    public Task streamChat(final ChatRequest request, final ChatListener listener) {
        final ModelProviderSettings settings = apiSettings();
        if (settings == null) {
            return delegate.streamChat(request, listener);
        }
        final Abort abort = new Abort();
        background.execute(() -> {
            StringBuilder content = new StringBuilder();
            StringBuilder thinking = new StringBuilder();
            try {
                listener.onStatus("Asking " + request.getModelName() + " on the API …");
                ProviderCall call = new ProviderCall("chat", request.getModelName(), messages(request),
                        Collections.<ToolDefinition>emptyList(), OutputFormat.text(), ThinkingMode.DEFAULT,
                        ThinkingMode.DEFAULT, null, 0, settings.apiContextOf(request.getModelName()), null, "",
                        abort);
                ProviderCompletion done = api.chat(settings.getApiBaseUrl(), call, new ProviderStream() {
                    public void onThinkingDelta(String delta) {
                        if (abort.isAborted()) {
                            return; // a chunk that arrives after Stop belongs to no answer any more
                        }
                        thinking.append(delta);
                        listener.onThinkingDelta(delta);
                    }

                    public void onContentDelta(String delta) {
                        if (abort.isAborted()) {
                            return;
                        }
                        content.append(delta);
                        listener.onContent(delta);
                    }
                });
                if (abort.isAborted()) {
                    return;
                }
                String answer = done.getContent() == null ? "" : done.getContent();
                if (content.length() == 0 && !answer.isEmpty()) {
                    listener.onContent(answer); // a service that does not stream answers at once
                }
                long tokens = done.getUsage() == null ? 0L : done.getUsage().getOutputTokens();
                listener.onComplete(new ChatResult(thinking.length() > 0 ? thinking.toString()
                        : done.getThinking(), "", null, tokens, 0L));
            } catch (Exception failed) {
                if (!abort.isAborted()) {
                    listener.onError(failed);
                }
            }
        });
        return abort;
    }

    /** The chat turns as port messages: system, user (with its images), assistant; tool turns do not go to the API. */
    static List<ModelMessage> messages(ChatRequest request) {
        List<ModelMessage> messages = new ArrayList<ModelMessage>();
        for (OllamaChatTurn turn : request.getMessages()) {
            String role = turn.getRole();
            if (OllamaChatTurn.ROLE_SYSTEM.equals(role)) {
                messages.add(ModelMessage.system(turn.getContent()));
            } else if (OllamaChatTurn.ROLE_USER.equals(role)) {
                messages.add(turn.getImages().isEmpty() ? ModelMessage.user(turn.getContent())
                        : ModelMessage.user(turn.getContent(), turn.getImages()));
            } else if (OllamaChatTurn.ROLE_ASSISTANT.equals(role)) {
                messages.add(ModelMessage.assistant(turn.getContent()));
            }
        }
        return messages;
    }

    // ------------------------------------------------------------------ the rest stays with Ollama

    @Override
    public Task listInstalledModels(InstalledModelsListener listener) {
        return delegate.listInstalledModels(listener);
    }

    @Override
    public Task getServerVersion(ServerVersionListener listener) {
        return delegate.getServerVersion(listener);
    }

    @Override
    public Task ping(ActionListener listener) {
        return delegate.ping(listener);
    }

    @Override
    public Task deleteModel(String modelName, ActionListener listener) {
        return delegate.deleteModel(modelName, listener);
    }

    @Override
    public Task pullModel(String modelName, PullListener listener) {
        return delegate.pullModel(modelName, listener);
    }

    @Override
    public Task generate(String modelName, String prompt, ActionListener listener) {
        return delegate.generate(modelName, prompt, listener);
    }

    @Override
    public Task embed(String modelName, String input, EmbedListener listener) {
        return delegate.embed(modelName, input, listener);
    }

    private static class Flag implements Task {
        volatile boolean set;

        @Override
        public void cancel() {
            set = true;
        }
    }

    /** Cancelling a chat aborts the API call at once (the provider closes its connection). */
    private static final class Abort extends Flag implements AbortSignal {
        private final List<Runnable> actions = new CopyOnWriteArrayList<Runnable>();

        @Override
        public void cancel() {
            set = true;
            for (Runnable action : actions) {
                action.run();
            }
        }

        @Override
        public boolean isAborted() {
            return set;
        }

        @Override
        public void onAbort(Runnable action) {
            actions.add(action);
            if (set) {
                action.run();
            }
        }
    }
}
