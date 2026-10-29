package com.aresstack.askai.java8.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.ollama4j.ModelPullListener;
import io.github.ollama4j.Ollama;
import io.github.ollama4j.exceptions.OllamaException;
import io.github.ollama4j.models.chat.OllamaChatMessage;
import io.github.ollama4j.models.chat.OllamaChatMessageRole;
import io.github.ollama4j.models.chat.OllamaChatRequest;
import io.github.ollama4j.models.chat.OllamaChatResponseModel;
import io.github.ollama4j.models.chat.OllamaChatResult;
import io.github.ollama4j.models.chat.OllamaChatTokenHandler;
import io.github.ollama4j.models.chat.OllamaChatToolCalls;
import io.github.ollama4j.models.embed.OllamaEmbedRequest;
import io.github.ollama4j.models.embed.OllamaEmbedResult;
import io.github.ollama4j.models.generate.OllamaGenerateRequest;
import io.github.ollama4j.models.ps.ModelProcessesResult;
import io.github.ollama4j.models.response.Model;
import io.github.ollama4j.models.response.ModelDetail;
import io.github.ollama4j.models.response.ModelMeta;
import io.github.ollama4j.models.response.ModelPullResponse;
import io.github.ollama4j.models.response.OllamaResult;
import io.github.ollama4j.tools.OllamaToolCallsFunction;
import io.github.ollama4j.utils.Utils;

import java.net.http.HttpRequest;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * The sole adapter between AskAI and the official {@code io.github.ollama4j:ollama4j} library (1.1.7, the
 * client the original AskAI used before the Java-8 detour). It maps library DTOs into the AskAI domain
 * models so the UI and services never depend on the library directly.
 *
 * <p>Two wire details of AskAI's chat contract are not expressible in the library's typed request and are
 * written by {@link RawChatRequest} directly into the JSON body: the {@code think} field as AskAI's
 * {@code ThinkingOption} emits it (a boolean or one of the levels {@code low}/{@code medium}/{@code high}/
 * {@code max}; the library's enum stops at {@code high}) and {@code tool_name} on tool-result turns (the
 * library's message has no such field). Tool calls are executed by AskAI's own agent loop, never by the
 * library: its automatic re-send on tool calls is switched off.</p>
 */
public final class AskAiOllamaClient {

    private static final long REQUEST_TIMEOUT_SECONDS = 6L * 60L * 60L;
    private static final String DEFAULT_BASE_URL = "http://localhost:11434";

    static {
        // ollama4j parses every response with ONE shared ObjectMapper, and some of its DTOs are strict
        // (no ignoreUnknown on /api/version, /api/tags and the /api/ps details): a newer Ollama field —
        // or the "features" block AskAI's own local model runtime adds to /api/version — would fail the
        // whole call. Clients ignore what they do not know; that is the documented Ollama contract.
        Utils.getObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private final Ollama ollama;

    public AskAiOllamaClient(String baseUrl) {
        this.ollama = new Ollama(normalizeBaseUrl(baseUrl));
        // The library's default is 10 s for the whole request: far too short for a model that first has
        // to load. AskAI's model-level timeouts are enforced by its own services, not here.
        this.ollama.setRequestTimeoutSeconds(REQUEST_TIMEOUT_SECONDS);
        // AskAI's agent loop executes tool calls itself. Without this the library would re-send the
        // conversation up to three times whenever the model requests a tool, and fail on the unknown tool.
        this.ollama.setMaxChatToolCallRetries(0);
    }

    public boolean ping() throws OllamaRequestException {
        try {
            return ollama.ping();
        } catch (OllamaException ex) {
            throw wrap("ping", ex);
        }
    }

    public String getVersion() throws OllamaRequestException {
        try {
            return ollama.getVersion();
        } catch (OllamaException ex) {
            throw wrap("version", ex);
        }
    }

    public List<OllamaModelInfo> getInstalledModels() throws OllamaRequestException {
        try {
            List<Model> models = ollama.listModels();
            List<OllamaModelInfo> mapped = new ArrayList<OllamaModelInfo>();
            if (models != null) {
                for (Model model : models) {
                    mapped.add(toModelInfo(model));
                }
            }
            return mapped;
        } catch (OllamaException ex) {
            throw wrap("list models", ex);
        }
    }

    public List<String> getModelNames() throws OllamaRequestException {
        List<String> names = new ArrayList<String>();
        for (OllamaModelInfo info : getInstalledModels()) {
            names.add(info.getDisplayName());
        }
        return names;
    }

    public List<OllamaRunningModelInfo> getRunningModels() throws OllamaRequestException {
        try {
            ModelProcessesResult result = ollama.ps();
            List<OllamaRunningModelInfo> mapped = new ArrayList<OllamaRunningModelInfo>();
            if (result != null && result.getModels() != null) {
                for (ModelProcessesResult.ModelProcess process : result.getModels()) {
                    mapped.add(new OllamaRunningModelInfo(
                            process.getName(),
                            process.getModel(),
                            process.getExpiresAt(),
                            process.getSize(),
                            process.getSizeVram(),
                            toDetails(process.getDetails())));
                }
            }
            return mapped;
        } catch (OllamaException ex) {
            throw wrap("list running models", ex);
        }
    }

    public OllamaModelInfoView getModelInfo(String modelName) throws OllamaRequestException {
        try {
            ModelDetail info = ollama.getModelDetails(modelName);
            return new OllamaModelInfoView(
                    toDetails(info.getDetails()),
                    info.getTemplate(),
                    info.getSystem(),
                    info.getParameters(),
                    info.getModelFile(),
                    capabilities(info.getCapabilities()));
        } catch (OllamaException ex) {
            throw wrap("model info for " + modelName, ex);
        }
    }

    public void deleteModel(String modelName) throws OllamaRequestException {
        try {
            ollama.deleteModel(modelName, false);
        } catch (OllamaException ex) {
            throw wrap("delete " + modelName, ex);
        }
    }

    public void unloadModel(String modelName) throws OllamaRequestException {
        try {
            ollama.unloadModel(modelName);
        } catch (OllamaException ex) {
            throw wrap("unload " + modelName, ex);
        }
    }

    public void pullModel(String modelName, final OllamaPullListener listener) throws OllamaRequestException {
        try {
            ollama.pullModel(modelName, new ModelPullListener() {
                public void onStatusUpdate(String pulledModel, ModelPullResponse response) {
                    if (listener != null && response != null) {
                        listener.onProgress(new OllamaPullProgress(
                                response.getStatus(), response.getCompleted(), response.getTotal()));
                    }
                }
            });
        } catch (OllamaException ex) {
            throw wrap("pull " + modelName, ex);
        }
    }

    public String generate(String modelName, String prompt) throws OllamaRequestException {
        try {
            OllamaGenerateRequest request = OllamaGenerateRequest.builder()
                    .withModel(modelName)
                    .withPrompt(prompt)
                    .build();
            OllamaResult result = ollama.generate(request, null);
            return result == null || result.getResponse() == null ? "" : result.getResponse();
        } catch (OllamaException ex) {
            throw wrap("generate with " + modelName, ex);
        }
    }

    public List<List<Double>> embed(String modelName, List<String> inputs) throws OllamaRequestException {
        try {
            OllamaEmbedResult result = ollama.embed(new OllamaEmbedRequest(modelName, inputs));
            return result == null || result.getEmbeddings() == null
                    ? Collections.<List<Double>>emptyList()
                    : result.getEmbeddings();
        } catch (OllamaException ex) {
            throw wrap("embed with " + modelName, ex);
        }
    }

    public OllamaChatCompletion streamChat(String modelName, List<OllamaChatTurn> conversation, String keepAlive,
                                           final OllamaChatStreamListener listener) throws OllamaRequestException {
        return streamChat(modelName, conversation, keepAlive, null, listener);
    }

    /**
     * @param think the wire value for the {@code think} field: {@code null} (omit), a {@link Boolean}, or
     *              a level string ({@code "low"}/{@code "medium"}/{@code "high"}/{@code "max"}).
     */
    public OllamaChatCompletion streamChat(String modelName, List<OllamaChatTurn> conversation, String keepAlive,
                                           Object think, final OllamaChatStreamListener listener)
            throws OllamaRequestException {
        try {
            // Mutable on purpose: the library appends the answer to the request's message list.
            List<OllamaChatMessage> messages = new ArrayList<OllamaChatMessage>();
            List<String> toolNames = new ArrayList<String>();
            for (OllamaChatTurn turn : conversation) {
                messages.add(toChatMessage(turn));
                toolNames.add(turn.isTool() ? turn.getToolName() : "");
            }
            RawChatRequest request = new RawChatRequest(think, toolNames);
            request.setModel(modelName);
            request.setMessages(messages);
            request.setKeepAlive(keepAlive);
            request.setStream(true);
            request.setUseTools(false);

            final StringBuilder thinking = new StringBuilder();
            final StringBuilder content = new StringBuilder();
            final List<OllamaToolCall> toolCalls = new ArrayList<OllamaToolCall>();
            OllamaChatResult result = ollama.chat(request, new OllamaChatTokenHandler() {
                public void accept(OllamaChatResponseModel chunk) {
                    OllamaChatMessage message = chunk == null ? null : chunk.getMessage();
                    if (message == null) {
                        return;
                    }
                    String thinkingDelta = message.getThinking();
                    if (thinkingDelta != null && !thinkingDelta.isEmpty()) {
                        thinking.append(thinkingDelta);
                        if (listener != null) {
                            listener.onThinkingDelta(thinkingDelta);
                        }
                    }
                    String contentDelta = message.getResponse();
                    if (contentDelta != null && !contentDelta.isEmpty()) {
                        content.append(contentDelta);
                        if (listener != null) {
                            listener.onContent(contentDelta);
                        }
                    }
                    List<OllamaToolCall> calls = toOllamaToolCalls(message.getToolCalls());
                    if (!calls.isEmpty()) {
                        toolCalls.addAll(calls);
                        if (listener != null) {
                            listener.onToolCalls(calls);
                        }
                    }
                }
            });
            OllamaChatResponseModel last = result == null ? null : result.getResponseModel();
            long evalCount = last == null || last.getEvalCount() == null ? 0L : last.getEvalCount();
            long evalDuration = last == null || last.getEvalDuration() == null ? 0L : last.getEvalDuration();
            OllamaChatCompletion completion = new OllamaChatCompletion(
                    thinking.toString(), content.toString(), toolCalls, evalCount, evalDuration);
            if (listener != null) {
                listener.onComplete(completion);
            }
            return completion;
        } catch (OllamaException ex) {
            throw wrap("chat with " + modelName, ex);
        }
    }

    // ------------------------------------------------------------------ mapping

    private static List<OllamaToolCall> toOllamaToolCalls(List<OllamaChatToolCalls> toolCalls) {
        List<OllamaToolCall> result = new ArrayList<OllamaToolCall>();
        if (toolCalls != null) {
            for (OllamaChatToolCalls call : toolCalls) {
                OllamaToolCallsFunction function = call == null ? null : call.getFunction();
                if (function != null) {
                    result.add(new OllamaToolCall(function.getName(), function.getArguments()));
                }
            }
        }
        return result;
    }

    private static OllamaChatMessage toChatMessage(OllamaChatTurn turn) {
        if (turn.isSystem()) {
            return new OllamaChatMessage(OllamaChatMessageRole.SYSTEM, turn.getContent());
        }
        if (turn.isTool()) {
            // tool_name travels through RawChatRequest; the library's message has no field for it.
            return new OllamaChatMessage(OllamaChatMessageRole.TOOL, turn.getContent());
        }
        if (turn.isAssistant()) {
            List<OllamaChatToolCalls> calls = null;
            if (!turn.getToolCalls().isEmpty()) {
                calls = new ArrayList<OllamaChatToolCalls>();
                for (OllamaToolCall call : turn.getToolCalls()) {
                    calls.add(new OllamaChatToolCalls(null, new OllamaToolCallsFunction(
                            call.getName(), new LinkedHashMap<String, Object>(call.getArguments()))));
                }
            }
            String thinking = turn.getThinking().isEmpty() ? null : turn.getThinking();
            return new OllamaChatMessage(OllamaChatMessageRole.ASSISTANT, turn.getContent(), thinking, calls, null);
        }
        if (!turn.getImages().isEmpty()) {
            List<byte[]> images = new ArrayList<byte[]>();
            for (String image : turn.getImages()) {
                images.add(Base64.getDecoder().decode(image));
            }
            return new OllamaChatMessage(OllamaChatMessageRole.USER, turn.getContent(), null, null, images);
        }
        return new OllamaChatMessage(OllamaChatMessageRole.USER, turn.getContent());
    }

    private static OllamaModelInfo toModelInfo(Model model) {
        String name = model.getName() != null && !model.getName().isEmpty() ? model.getName() : model.getModel();
        String modelField = model.getModel() != null && !model.getModel().isEmpty() ? model.getModel() : name;
        return new OllamaModelInfo(
                name,
                modelField,
                timestamp(model.getModifiedAt()),
                model.getSize(),
                model.getDigest(),
                toDetails(model.getModelMeta()));
    }

    private static OllamaModelDetails toDetails(ModelMeta details) {
        if (details == null) {
            return OllamaModelDetails.empty();
        }
        return new OllamaModelDetails(
                details.getFormat(),
                details.getFamily(),
                details.getParameterSize(),
                details.getQuantizationLevel());
    }

    private static OllamaModelDetails toDetails(ModelProcessesResult.ModelDetails details) {
        if (details == null) {
            return OllamaModelDetails.empty();
        }
        return new OllamaModelDetails(
                details.getFormat(),
                details.getFamily(),
                details.getParameterSize(),
                details.getQuantizationLevel());
    }

    private static List<String> capabilities(String[] capabilities) {
        return capabilities == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(Arrays.asList(capabilities));
    }

    /** ISO-8601 with offset and always with seconds (the model cards cut the text after the seconds). */
    private static String timestamp(OffsetDateTime time) {
        return time == null ? "" : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(time);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.trim();
        if (url.isEmpty()) {
            return DEFAULT_BASE_URL;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    private static OllamaRequestException wrap(String operation, OllamaException ex) {
        return new OllamaRequestException("Ollama request failed (" + operation + "): " + ex.getMessage(), ex);
    }

    /**
     * The chat request with the two wire details the library's typed model cannot express. The body is the
     * library's own serialization, patched on the JSON tree: {@code think} written verbatim (omitted when
     * null), {@code tool_name} added to tool-result messages, and the library-internal {@code useTools} flag
     * plus an empty {@code tools} list dropped so Ollama sees exactly the fields AskAI means.
     */
    static final class RawChatRequest extends OllamaChatRequest {

        private final Object think;
        private final List<String> toolNames;

        RawChatRequest(Object think, List<String> toolNames) {
            this.think = think;
            this.toolNames = toolNames == null ? Collections.<String>emptyList() : toolNames;
        }

        @Override
        public HttpRequest.BodyPublisher getBodyPublisher() {
            return HttpRequest.BodyPublishers.ofString(toJson());
        }

        /** The exact JSON body sent to {@code /api/chat}. */
        String toJson() {
            ObjectMapper mapper = Utils.getObjectMapper();
            ObjectNode body = mapper.valueToTree(this);
            body.remove("think");
            if (think != null) {
                body.putPOJO("think", think);
            }
            body.remove("useTools");
            JsonNode tools = body.get("tools");
            if (tools == null || tools.isNull() || (tools.isArray() && tools.size() == 0)) {
                body.remove("tools");
            }
            JsonNode messages = body.get("messages");
            if (messages instanceof ArrayNode) {
                for (int i = 0; i < messages.size() && i < toolNames.size(); i++) {
                    String toolName = toolNames.get(i);
                    if (toolName != null && !toolName.isEmpty() && messages.get(i) instanceof ObjectNode) {
                        ((ObjectNode) messages.get(i)).put("tool_name", toolName);
                    }
                }
            }
            try {
                return mapper.writeValueAsString(body);
            } catch (JsonProcessingException ex) {
                throw new IllegalArgumentException("Request not Body convertible.", ex);
            }
        }
    }
}
