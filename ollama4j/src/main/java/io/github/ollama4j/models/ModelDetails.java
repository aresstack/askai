package io.github.ollama4j.models;

public final class ModelDetails {

    private final String format;
    private final String family;
    private final String families;
    private final String parameterSize;
    private final String quantizationLevel;

    public ModelDetails(String format, String family, String families, String parameterSize, String quantizationLevel) {
        this.format = format == null ? "" : format;
        this.family = family == null ? "" : family;
        this.families = families == null ? "" : families;
        this.parameterSize = parameterSize == null ? "" : parameterSize;
        this.quantizationLevel = quantizationLevel == null ? "" : quantizationLevel;
    }

    public static ModelDetails empty() {
        return new ModelDetails("", "", "", "", "");
    }

    public String getFormat() {
        return format;
    }

    public String getFamily() {
        return family;
    }

    public String getFamilies() {
        return families;
    }

    public String getParameterSize() {
        return parameterSize;
    }

    public String getQuantizationLevel() {
        return quantizationLevel;
    }
}
