package com.aresstack.askai.service;

public final class ChatRequest {

    private final String modelName;
    private final String text;

    public ChatRequest(String modelName, String text) {
        this.modelName = modelName == null ? "" : modelName.trim();
        this.text = text == null ? "" : text;
    }

    public String getModelName() {
        return modelName;
    }

    public String getText() {
        return text;
    }

    public boolean isValid() {
        return modelName.length() > 0 && text.trim().length() > 0;
    }
}
