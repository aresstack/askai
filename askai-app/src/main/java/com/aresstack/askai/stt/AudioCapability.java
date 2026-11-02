package com.aresstack.askai.stt;

import java.util.List;
import java.util.Locale;

/**
 * The single source of truth for "may this model be used for speech-to-text?". A model is
 * audio-capable only when Ollama's {@code /api/show} capabilities contain the exact capability
 * {@code audio}. Vision, a bundled {@code mmproj}, or a general "multimodal" nature are explicitly
 * NOT audio — a vision/coding model like {@code devstral-small-2} must never be offered for STT.
 */
public final class AudioCapability {

    public static final String CAPABILITY = "audio";

    private AudioCapability() {
    }

    /** @return true only when the capability list contains the exact "audio" capability. */
    public static boolean isAudioCapable(List<String> capabilities) {
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
