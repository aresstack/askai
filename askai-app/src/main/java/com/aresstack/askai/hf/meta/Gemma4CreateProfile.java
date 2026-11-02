package com.aresstack.askai.hf.meta;

/** Gemma-4 create profile. Uses the gemma4 renderer/parser (the family Ollama itself ships for Gemma 4). */
public final class Gemma4CreateProfile extends OllamaCreateProfileRegistry.BaseProfile {

    public String family() {
        return "gemma4";
    }

    @Override
    public String renderer() {
        return "gemma4";
    }

    @Override
    public String parser() {
        return "gemma4";
    }
}
