package com.aresstack.askai.hf.meta;

/**
 * How much AskAI trusts a metadata value. Only {@link #HIGH} values (and, per-field, explicitly allowed
 * {@link #MEDIUM} ones) may be written into a functional Ollama {@code /api/create} field — an uncertain
 * value must never overwrite Ollama's own GGUF-derived detection.
 */
public enum Confidence {

    /** Exact, unambiguous match (e.g. an exact quantization token, a registry-mapped family). */
    HIGH,

    /** Plausible but not certain; sent only where a field explicitly opts MEDIUM in. */
    MEDIUM,

    /** A guess; never sent to Ollama, kept only for provenance/audit. */
    LOW
}
