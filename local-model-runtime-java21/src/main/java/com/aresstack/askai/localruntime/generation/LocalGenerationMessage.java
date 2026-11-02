package com.aresstack.askai.localruntime.generation;

/**
 * One chat message (AskAI-owned). {@code role} is one of {@code system}, {@code user}, {@code assistant};
 * the port/handle applies the model's own chat template when rendering these to a prompt.
 */
public record LocalGenerationMessage(String role, String content) {

    public LocalGenerationMessage {
        role = role == null ? "" : role.trim();
        content = content == null ? "" : content;
    }
}
