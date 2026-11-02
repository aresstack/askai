package com.aresstack.askai.hf.meta;

/**
 * Mistral create profile. Renderer/parser are left to Ollama's own detection until confirmed against a
 * real server; the profile exists so a verified value is a one-line change.
 */
public final class MistralCreateProfile extends OllamaCreateProfileRegistry.BaseProfile {

    public String family() {
        return "mistral";
    }
}
