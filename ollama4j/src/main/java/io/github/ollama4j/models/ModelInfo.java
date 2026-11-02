package io.github.ollama4j.models;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ModelInfo {

    private final String modelFile;
    private final String parameters;
    private final String template;
    private final ModelDetails details;
    private final List<String> capabilities;

    public ModelInfo(String modelFile, String parameters, String template, ModelDetails details) {
        this(modelFile, parameters, template, details, null);
    }

    public ModelInfo(String modelFile, String parameters, String template, ModelDetails details,
                     List<String> capabilities) {
        this.modelFile = modelFile == null ? "" : modelFile;
        this.parameters = parameters == null ? "" : parameters;
        this.template = template == null ? "" : template;
        this.details = details == null ? ModelDetails.empty() : details;
        this.capabilities = capabilities == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(capabilities));
    }

    public String getModelFile() {
        return modelFile;
    }

    public String getParameters() {
        return parameters;
    }

    public String getTemplate() {
        return template;
    }

    public ModelDetails getDetails() {
        return details;
    }

    /**
     * The capability tags reported by {@code /api/show} ("completion", "vision", "audio", "tools",
     * "embedding", "thinking", ...). Empty on Ollama versions that do not report capabilities.
     */
    public List<String> getCapabilities() {
        return capabilities;
    }

    /** @return whether the given capability tag is present (case-insensitive). */
    public boolean hasCapability(String capability) {
        for (int i = 0; i < capabilities.size(); i++) {
            if (capabilities.get(i).equalsIgnoreCase(capability)) {
                return true;
            }
        }
        return false;
    }
}
