package com.aresstack.askai.localruntime.generation;

import java.util.Locale;

/**
 * The AskAI-owned backend a local generation model may run on. WARP is the D3D12 software adapter and AUTO
 * the hardware adapter of the same native DirectML path; CPU is the native Java reference; DIRECTML is the
 * Phi-3 sidecar's explicit GPU mode. Which of these a given family actually offers comes from the catalog
 * (never invented here).
 */
public enum LocalGenerationBackend {

    WARP,
    AUTO,
    CPU,
    DIRECTML;

    public String lower() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Parse a lowercase/uppercase token, or {@code null} when unknown. */
    public static LocalGenerationBackend parse(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            return valueOf(token.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
