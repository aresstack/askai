package com.aresstack.askai.hf.meta;

/**
 * One message for the optional {@code messages} array of {@code /api/create}. Immutable. Only ever built
 * from an explicit, supported model definition — never from README examples.
 */
public final class OllamaCreateMessage {

    private final String role;
    private final String content;

    public OllamaCreateMessage(String role, String content) {
        this.role = role == null ? "" : role;
        this.content = content == null ? "" : content;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }
}
