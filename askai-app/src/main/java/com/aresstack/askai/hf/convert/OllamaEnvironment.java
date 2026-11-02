package com.aresstack.askai.hf.convert;

/**
 * The local Ollama runtime facts a strategy may consult — currently just the server version, which
 * is version-dependent for what a safetensors import can convert. Null/blank version means Ollama
 * was not reachable when the analysis ran, which strategies surface honestly rather than guessing.
 */
public final class OllamaEnvironment {

    private final String version;

    public OllamaEnvironment(String version) {
        this.version = version == null ? "" : version.trim();
    }

    /** @return the Ollama server version, or "" when it could not be determined. */
    public String getVersion() {
        return version;
    }

    public boolean isVersionKnown() {
        return version.length() > 0;
    }

    public static OllamaEnvironment unknown() {
        return new OllamaEnvironment("");
    }
}
