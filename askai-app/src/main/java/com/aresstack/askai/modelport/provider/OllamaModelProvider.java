package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.TokenUsage;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.client.OllamaChatCompletion;
import com.aresstack.askai.client.OllamaChatOptions;
import com.aresstack.askai.client.OllamaChatStreamListener;
import com.aresstack.askai.client.OllamaChatTurn;
import com.aresstack.askai.client.OllamaRequestException;
import com.aresstack.askai.client.OllamaResidentModel;
import com.aresstack.askai.client.OllamaToolCall;
import com.aresstack.askai.modelport.ModelPortSettings;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.ollama4j.utils.Utils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * The Ollama wire of the model port (ADR 0001, 3.2) on {@link AskAiOllamaClient}, the JVM's only Ollama client. It
 * serves the {@code ollama} endpoint and AskAI's local runtime, which speaks Ollama's API. Everything Ollama-specific
 * the port used to do itself lives here: the context per call ({@code num_ctx}), the answer budget
 * ({@code num_predict}), {@code keep_alive}, the {@code think} field resolved from {@code /api/show}, loading and
 * unloading a model ({@code /api/generate}), the resident models ({@code /api/ps}) and the installed models
 * ({@code /api/tags}).
 *
 * <p>One machine, one GPU: {@link #sharesMemory()} is true, so the port groups work by model and treats a change of
 * model as a deliberate step. The concurrent calls per model and the context per model are the port's own settings
 * ({@code model-port.properties}).</p>
 */
public final class OllamaModelProvider implements ModelProvider {

    private final Function<String, AskAiOllamaClient> clientFactory;
    private final Map<String, AskAiOllamaClient> clients = new ConcurrentHashMap<String, AskAiOllamaClient>();
    private final Map<String, Capabilities> capabilityCache = new ConcurrentHashMap<String, Capabilities>();

    /** @param clientFactory creates the client of a base URL (a seam for tests); null = {@link AskAiOllamaClient} */
    public OllamaModelProvider(Function<String, AskAiOllamaClient> clientFactory) {
        this.clientFactory = clientFactory == null ? AskAiOllamaClient::new : clientFactory;
    }

    @Override
    public boolean sharesMemory() {
        return true;
    }

    @Override
    public int defaultCapacity() {
        return 0; // the port's capacity.default (mirrors OLLAMA_NUM_PARALLEL)
    }

    @Override
    public int contextTokens(String model) {
        return 0; // the port's context per model (num_ctx, sent with every call)
    }

    @Override
    public ProviderCompletion chat(String baseUrl, final ProviderCall call, final ProviderStream stream)
            throws ProviderException {
        Map<String, Object> options = new LinkedHashMap<String, Object>();
        options.put("num_ctx", call.getContextTokens()); // the model's context, on every call
        if (call.getTemperature() != null) {
            options.put("temperature", call.getTemperature());
        }
        Object think = resolveThinking(call.getRequestedThinking(), call.effectiveThinking(), baseUrl,
                call.getModel());
        int budget = call.getAnswerTokens();
        if (budget > 0 && call.getRequestedThinking() == ThinkingMode.OFF && "low".equals(think)) {
            // A contract role asked for no reasoning on a model that cannot switch it off: its answer
            // budget gets room for the reasoning (1,000 tokens until the measurement PR, plan 4.1 rule 3).
            budget += ProviderCall.LEVEL_ONLY_REASONING_TOKENS;
        }
        if (budget > 0) {
            options.put("num_predict", budget);
        }
        List<OllamaChatOptions.Tool> tools = new ArrayList<OllamaChatOptions.Tool>();
        for (ToolDefinition tool : call.getTools()) {
            tools.add(new OllamaChatOptions.Tool(tool.getName(), tool.getDescription(),
                    tool.getParametersSchemaJson()));
        }
        OutputFormat format = call.getOutputFormat();
        OllamaChatOptions extras = new OllamaChatOptions(tools,
                format.getKind() == OutputFormat.Kind.JSON_SCHEMA ? format.getSchemaJson() : null,
                options, format.getKind() == OutputFormat.Kind.JSON, () -> call.getAbort().isAborted(),
                call.getTruncate());
        OllamaChatCompletion completion;
        try {
            completion = client(baseUrl).streamChat(call.getModel(), turns(call.getMessages()), call.getKeepAlive(),
                    think, extras, new OllamaChatStreamListener() {
                        public void onThinkingDelta(String delta) {
                            stream.onThinkingDelta(delta);
                        }

                        public void onContent(String content) {
                            stream.onContentDelta(content);
                        }

                        public void onStatus(String status) {
                        }

                        public void onComplete(OllamaChatCompletion completion) {
                        }
                    });
        } catch (OllamaRequestException failure) {
            throw new ProviderException(failure.getMessage(), isConnectFailure(failure), failure);
        }
        List<ToolCall> toolCalls = new ArrayList<ToolCall>();
        for (OllamaToolCall toolCall : completion.getToolCalls()) {
            if (toolCall.getName() != null && !toolCall.getName().trim().isEmpty()) {
                toolCalls.add(new ToolCall(toolCall.getId(), toolCall.getName(),
                        argumentsJson(toolCall.getArguments())));
            }
        }
        return new ProviderCompletion(completion.getContent(), completion.getThinking(), toolCalls,
                finishReason(completion.getDoneReason(), !toolCalls.isEmpty()),
                new TokenUsage((int) Math.min(Integer.MAX_VALUE, completion.getPromptEvalCount()),
                        completion.getEvalCount() > 0 ? (int) Math.min(Integer.MAX_VALUE,
                                completion.getEvalCount()) : -1),
                Duration.ofNanos(completion.getLoadDurationNanos()),
                Duration.ofNanos(completion.getPromptEvalDurationNanos()));
    }

    @Override
    public void load(String baseUrl, String model, int contextTokens, String keepAlive) throws ProviderException {
        try {
            client(baseUrl).loadModel(model, keepAlive, contextTokens);
        } catch (OllamaRequestException failed) {
            throw new ProviderException(failed.getMessage(), isConnectFailure(failed), failed);
        }
    }

    @Override
    public void unload(String baseUrl, String model) throws ProviderException {
        try {
            client(baseUrl).unloadModel(model);
        } catch (OllamaRequestException failed) {
            throw new ProviderException(failed.getMessage(), isConnectFailure(failed), failed);
        }
    }

    @Override
    public List<OllamaResidentModel> residentModels(String baseUrl) throws ProviderException {
        try {
            return client(baseUrl).getResidentModels();
        } catch (OllamaRequestException failed) {
            throw new ProviderException(failed.getMessage(), isConnectFailure(failed), failed);
        }
    }

    @Override
    public List<String> modelNames(String baseUrl) throws ProviderException {
        try {
            return client(baseUrl).getModelNames();
        } catch (OllamaRequestException failed) {
            throw new ProviderException(failed.getMessage(), isConnectFailure(failed), failed);
        }
    }

    /** {@code llama3.1:8b} matches {@code llama3.1:8b}; a bare name matches its {@code :latest} tag. */
    @Override
    public boolean offers(List<String> names, String model) {
        return isInstalled(names, model);
    }

    @Override
    public String missingModelMessage(String model, String slot) {
        return ModelPortSettings.notInstalledMessage(model, slot);
    }

    /** {@code llama3.1:8b} matches {@code llama3.1:8b}; a bare name matches its {@code :latest} tag. */
    public static boolean isInstalled(List<String> installed, String model) {
        String wanted = model.trim();
        String withTag = wanted.indexOf(':') < 0 ? wanted + ":latest" : wanted;
        for (String name : installed) {
            if (name != null && (name.trim().equals(wanted) || name.trim().equals(withTag))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The wire value of {@code think} for one call (plan 4.1, rule 3): the request's mode, else the slot's;
     * Auto resolves per model. A model without the thinking capability never gets the field; a level-only
     * model ({@code gpt-oss}) gets {@code low} for Off and Auto.
     */
    Object resolveThinking(ThinkingMode requested, ThinkingMode effective, String baseUrl, String model) {
        ThinkingMode mode = effective == null ? ThinkingMode.DEFAULT : effective;
        Capabilities capabilities = capabilities(baseUrl, model);
        if (!capabilities.thinking) {
            return null;
        }
        if (mode == ThinkingMode.DEFAULT || mode == ThinkingMode.OFF) {
            return capabilities.levelOnly ? "low" : Boolean.FALSE;
        }
        return thinkValue(mode);
    }

    /** What {@code /api/show} says about a model's thinking, cached per endpoint and model. */
    private Capabilities capabilities(String baseUrl, String model) {
        String key = baseUrl + "\n" + model;
        Capabilities known = capabilityCache.get(key);
        if (known != null) {
            return known;
        }
        Capabilities resolved;
        try {
            com.aresstack.askai.client.OllamaModelInfoView info = client(baseUrl).getModelInfo(model);
            boolean thinking = info.getCapabilities().contains("thinking");
            resolved = new Capabilities(thinking,
                    ModelPortSettings.isLevelOnlyModel(model, info.getDetails().getFamily()));
            capabilityCache.put(key, resolved);
        } catch (OllamaRequestException | RuntimeException unknown) {
            // Not cached: the next call asks again. Without knowledge no think field is sent, because a
            // non-thinking model rejects one.
            System.err.println("[model-port] capabilities of " + model + " unknown, sending no think field: "
                    + unknown.getMessage());
            resolved = new Capabilities(false, false);
        }
        return resolved;
    }

    private static final class Capabilities {
        final boolean thinking;
        final boolean levelOnly;

        Capabilities(boolean thinking, boolean levelOnly) {
            this.thinking = thinking;
            this.levelOnly = levelOnly;
        }
    }

    private AskAiOllamaClient client(String baseUrl) {
        AskAiOllamaClient client = clients.get(baseUrl);
        if (client == null) {
            client = clientFactory.apply(baseUrl);
            AskAiOllamaClient raced = clients.putIfAbsent(baseUrl, client);
            if (raced != null) {
                client = raced;
            }
        }
        return client;
    }

    /** The wire value of {@code think}: null = omit. */
    static Object thinkValue(ThinkingMode mode) {
        switch (mode) {
            case OFF:
                return Boolean.FALSE;
            case ON:
                return Boolean.TRUE;
            case LOW:
                return "low";
            case MEDIUM:
                return "medium";
            case HIGH:
                return "high";
            default:
                return null;
        }
    }

    static List<OllamaChatTurn> turns(List<ModelMessage> messages) {
        List<OllamaChatTurn> turns = new ArrayList<OllamaChatTurn>();
        for (ModelMessage message : messages) {
            switch (message.getRole()) {
                case SYSTEM:
                    turns.add(OllamaChatTurn.system(message.getContent()));
                    break;
                case ASSISTANT:
                    List<OllamaToolCall> calls = new ArrayList<OllamaToolCall>();
                    for (ToolCall call : message.getToolCalls()) {
                        calls.add(new OllamaToolCall(call.getId(), call.getName(), arguments(call)));
                    }
                    turns.add(OllamaChatTurn.assistant(message.getThinking(), message.getContent(), calls));
                    break;
                case TOOL:
                    turns.add(OllamaChatTurn.tool(message.getToolName(), message.getContent()));
                    break;
                default:
                    turns.add(OllamaChatTurn.user(message.getContent()));
            }
        }
        return turns;
    }

    /** A replayed call's arguments; invalid JSON from a small model is replayed as an empty object. */
    private static Map<String, Object> arguments(ToolCall call) {
        try {
            Map<String, Object> parsed = Utils.getObjectMapper().readValue(call.getArgumentsJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (IOException invalid) {
            return Collections.emptyMap();
        }
    }

    static String argumentsJson(Map<String, Object> arguments) {
        try {
            return Utils.getObjectMapper().writeValueAsString(
                    arguments == null ? Collections.<String, Object>emptyMap() : arguments);
        } catch (IOException unexpected) {
            return "{}";
        }
    }

    static ChatResult.FinishReason finishReason(String doneReason, boolean toolCalls) {
        if (toolCalls) {
            return ChatResult.FinishReason.TOOL_CALLS;
        }
        if ("stop".equalsIgnoreCase(doneReason)) {
            return ChatResult.FinishReason.STOP;
        }
        if ("length".equalsIgnoreCase(doneReason)) {
            return ChatResult.FinishReason.LENGTH;
        }
        return doneReason == null || doneReason.isEmpty() ? ChatResult.FinishReason.STOP
                : ChatResult.FinishReason.OTHER;
    }

    /** True when the failure (or a cause) is a refused or timed-out connection: the endpoint is down. */
    public static boolean isConnectFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
