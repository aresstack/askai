package com.aresstack.askai.vision;

import java.util.List;
import java.util.Locale;

/**
 * The single source of truth for "may this model receive image input?". A model is vision-capable only
 * when Ollama's {@code /api/show} capabilities contain the exact capability {@code vision}. A model name,
 * a Hugging-Face tag, a bundled {@code mmproj}, or a general "multimodal" nature are explicitly NOT
 * evidence of vision — mirroring {@link com.aresstack.askai.stt.AudioCapability} for audio.
 */
public final class VisionCapability {

    public static final String CAPABILITY = "vision";

    private VisionCapability() {
    }

    /** @return true only when the capability list contains the exact "vision" capability. */
    public static boolean isVisionCapable(List<String> capabilities) {
        if (capabilities == null) {
            return false;
        }
        for (int i = 0; i < capabilities.size(); i++) {
            String capability = capabilities.get(i);
            if (capability != null && CAPABILITY.equals(capability.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
