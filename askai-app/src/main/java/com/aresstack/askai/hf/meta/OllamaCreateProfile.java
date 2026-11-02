package com.aresstack.askai.hf.meta;

/**
 * A static, per-model-family configuration for the extra top-level {@code /api/create} fields
 * ({@code renderer}, {@code parser}, {@code requires}). This is <em>request configuration only</em> — it
 * is not model management and never becomes installed-model state.
 *
 * <p>Only verified values are returned; an empty string means "leave the field out and let Ollama detect
 * it". {@code template}/{@code system}/{@code messages} are intentionally not provided here: an embedded
 * GGUF chat template takes precedence, and HF Jinja templates would need a separate tested converter.</p>
 */
public interface OllamaCreateProfile {

    /** The Ollama model family this profile applies to (e.g. "gemma4"). */
    String family();

    /** The {@code renderer} value, or "" to omit it (Ollama auto-detects). */
    String renderer();

    /** The {@code parser} value, or "" to omit it. */
    String parser();

    /** The minimum Ollama version for {@code requires}, or "" to omit it. */
    String requires();
}
