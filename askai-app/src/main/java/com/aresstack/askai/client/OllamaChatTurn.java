package com.aresstack.askai.client;

import java.util.Collections;
import java.util.List;

/**
 * One message in an Ollama chat conversation, in AskAI domain terms.
 *
 * <p>Roles are the Ollama wire roles ({@code system}, {@code user}, {@code assistant}, {@code tool}).
 * Beyond the content, an assistant turn also carries its reasoning ({@code thinking}) and any
 * {@code toolCalls} it requested, and a tool turn carries the {@code toolName}, so a full turn can be
 * fed back into the conversation unchanged.</p>
 */
public final class OllamaChatTurn {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    private final String role;
    private final String content;
    private final String thinking;
    private final String toolName;
    private final List<OllamaToolCall> toolCalls;
    private final List<String> images;

    public OllamaChatTurn(String role, String content) {
        this(role, content, "", "", Collections.<OllamaToolCall>emptyList());
    }

    public OllamaChatTurn(String role, String content, String thinking, String toolName,
                          List<OllamaToolCall> toolCalls) {
        this(role, content, thinking, toolName, toolCalls, Collections.<String>emptyList());
    }

    public OllamaChatTurn(String role, String content, String thinking, String toolName,
                          List<OllamaToolCall> toolCalls, List<String> images) {
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.toolName = toolName == null ? "" : toolName;
        this.toolCalls = toolCalls == null
                ? Collections.<OllamaToolCall>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<OllamaToolCall>(toolCalls));
        this.images = images == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(images));
    }

    public static OllamaChatTurn system(String content) {
        return new OllamaChatTurn(ROLE_SYSTEM, content);
    }

    public static OllamaChatTurn user(String content) {
        return new OllamaChatTurn(ROLE_USER, content);
    }

    /** A user turn carrying one or more base64-encoded images (no data-URI prefix), for vision models. */
    public static OllamaChatTurn user(String content, List<String> images) {
        return new OllamaChatTurn(ROLE_USER, content, "", "", Collections.<OllamaToolCall>emptyList(), images);
    }

    public static OllamaChatTurn assistant(String content) {
        return new OllamaChatTurn(ROLE_ASSISTANT, content);
    }

    /** A full assistant turn: reasoning, answer and the tool calls it requested. */
    public static OllamaChatTurn assistant(String thinking, String content, List<OllamaToolCall> toolCalls) {
        return new OllamaChatTurn(ROLE_ASSISTANT, content, thinking, "", toolCalls);
    }

    /** A tool result to feed back into the conversation. */
    public static OllamaChatTurn tool(String toolName, String content) {
        return new OllamaChatTurn(ROLE_TOOL, content, "", toolName, Collections.<OllamaToolCall>emptyList());
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public String getThinking() {
        return thinking;
    }

    public String getToolName() {
        return toolName;
    }

    public List<OllamaToolCall> getToolCalls() {
        return toolCalls;
    }

    /** Base64-encoded images attached to this turn (empty for text-only turns). */
    public List<String> getImages() {
        return images;
    }

    public boolean isUser() {
        return ROLE_USER.equalsIgnoreCase(role);
    }

    public boolean isAssistant() {
        return ROLE_ASSISTANT.equalsIgnoreCase(role);
    }

    public boolean isSystem() {
        return ROLE_SYSTEM.equalsIgnoreCase(role);
    }

    public boolean isTool() {
        return ROLE_TOOL.equalsIgnoreCase(role);
    }
}
