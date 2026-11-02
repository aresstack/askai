package com.aresstack.askai.client;

/**
 * Details object returned by Ollama for installed and running models.
 */
public final class OllamaModelDetails {

    private final String format;
    private final String family;
    private final String parameterSize;
    private final String quantizationLevel;

    public OllamaModelDetails(String format, String family, String parameterSize, String quantizationLevel) {
        this.format = safe(format);
        this.family = safe(family);
        this.parameterSize = safe(parameterSize);
        this.quantizationLevel = safe(quantizationLevel);
    }

    public static OllamaModelDetails empty() {
        return new OllamaModelDetails("", "", "", "");
    }

    public String getFormat() {
        return format;
    }

    public String getFamily() {
        return family;
    }

    public String getParameterSize() {
        return parameterSize;
    }

    public String getQuantizationLevel() {
        return quantizationLevel;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
