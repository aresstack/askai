package io.github.ollama4j.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One chat message. Immutable. Beyond {@code role}/{@code content} it also carries the reasoning
 * ({@code thinking}), any {@code toolCalls} the assistant requested, and — for a {@code tool} result —
 * the {@code toolName}, so a full turn (thinking + content + tool_calls, then tool results) can be sent
 * back to Ollama unchanged.
 */
public final class ChatMessage {

    private final String role;
    private final String content;
    private final String thinking;
    private final String toolName;
    private final List<ToolCall> toolCalls;
    private final List<String> images;

    public ChatMessage(String role, String content) {
        this(role, content, "", "", Collections.<ToolCall>emptyList());
    }

    public ChatMessage(String role, String content, String thinking, String toolName, List<ToolCall> toolCalls) {
        this(role, content, thinking, toolName, toolCalls, Collections.<String>emptyList());
    }

    public ChatMessage(String role, String content, String thinking, String toolName,
                       List<ToolCall> toolCalls, List<String> images) {
        this.role = role == null ? "user" : role;
        this.content = content == null ? "" : content;
        this.thinking = thinking == null ? "" : thinking;
        this.toolName = toolName == null ? "" : toolName;
        this.toolCalls = toolCalls == null
                ? Collections.<ToolCall>emptyList()
                : Collections.unmodifiableList(new ArrayList<ToolCall>(toolCalls));
        this.images = images == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(images));
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }

    /** A user turn carrying one or more base64-encoded images (no data-URI prefix), for vision models. */
    public static ChatMessage user(String content, List<String> images) {
        return new ChatMessage("user", content, "", "", Collections.<ToolCall>emptyList(), images);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content);
    }

    /** A full assistant turn: reasoning, answer and the tool calls it requested. */
    public static ChatMessage assistant(String thinking, String content, List<ToolCall> toolCalls) {
        return new ChatMessage("assistant", content, thinking, "", toolCalls);
    }

    /** A tool result to feed back into the conversation. */
    public static ChatMessage tool(String toolName, String content) {
        return new ChatMessage("tool", content, "", toolName, Collections.<ToolCall>emptyList());
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

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    /** Base64-encoded images attached to this message (empty for text-only turns). */
    public List<String> getImages() {
        return images;
    }
}
