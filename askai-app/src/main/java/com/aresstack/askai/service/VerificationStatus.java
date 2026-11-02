package com.aresstack.askai.service;

/**
 * Outcome of verifying a freshly installed model against Ollama's {@code /api/show} capabilities.
 *
 * <p>The capabilities reported by {@code /api/show} are the single source of truth for what the
 * installed model can actually do — never the modality hints from ollama.com or Hugging Face.</p>
 */
public enum VerificationStatus {

    /** {@code /api/show} succeeded and every required capability (if any) is present. */
    VERIFIED,

    /** {@code /api/show} succeeded but at least one required capability is missing. */
    MISSING_REQUIRED,

    /**
     * {@code /api/show} reported no capabilities field (empty, or an older Ollama that does not send
     * it). This is explicitly <em>not</em> "no capabilities": nothing may be enabled on this basis
     * (e.g. audio must stay off).
     */
    UNKNOWN,

    /** The {@code /api/show} call itself failed after the install completed. */
    FAILED
}
