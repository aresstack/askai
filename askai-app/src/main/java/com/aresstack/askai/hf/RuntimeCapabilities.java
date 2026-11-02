package com.aresstack.askai.hf;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The vision/audio modalities that are actually backed by the files being installed — the main GGUF and
 * any encoder (mmproj) companions — proven from GGUF header content, not from the declared plan.
 *
 * <p>AskAI never persists per-model state, so the capability set sent on {@code /api/create} must be the
 * intersection of what Hugging Face declared and what the installed runtime can really do: {@code vision}
 * only when a GGUF here carries a vision encoder, {@code audio} only when one carries an audio encoder.
 * This makes a cancelled/absent projector fall out of the capability set automatically, instead of writing
 * a {@code vision}/{@code audio} capability Ollama could never honour.</p>
 */
public final class RuntimeCapabilities {

    private final boolean hasVision;
    private final boolean hasAudio;

    RuntimeCapabilities(boolean hasVision, boolean hasAudio) {
        this.hasVision = hasVision;
        this.hasAudio = hasAudio;
    }

    /**
     * Inspects the main GGUF and every companion, ORing their vision/audio encoder flags. Files that cannot
     * be read as a GGUF header are ignored (a broken companion simply contributes no modality).
     */
    public static RuntimeCapabilities fromFiles(File mainGguf, List<File> companions) {
        List<File> all = new ArrayList<File>();
        if (mainGguf != null) {
            all.add(mainGguf);
        }
        if (companions != null) {
            all.addAll(companions);
        }
        boolean vision = false;
        boolean audio = false;
        for (File file : all) {
            if (file == null || !file.isFile()) {
                continue;
            }
            try {
                GgufFile.GgufInfo info = GgufFile.inspect(file);
                vision = vision || info.hasVisionEncoder();
                audio = audio || info.hasAudioEncoder();
            } catch (IOException ignored) {
                // Not a readable GGUF header → it contributes no runtime modality.
            }
        }
        return new RuntimeCapabilities(vision, audio);
    }

    public boolean hasVision() {
        return hasVision;
    }

    public boolean hasAudio() {
        return hasAudio;
    }

    /**
     * @return the given capability tags with {@code vision} dropped unless a vision encoder is present and
     *         {@code audio} dropped unless an audio encoder is present; all other tags pass through unchanged.
     */
    public List<String> intersect(List<String> capabilities) {
        List<String> result = new ArrayList<String>();
        if (capabilities == null) {
            return result;
        }
        for (String capability : capabilities) {
            if ("vision".equals(capability) && !hasVision) {
                continue;
            }
            if ("audio".equals(capability) && !hasAudio) {
                continue;
            }
            result.add(capability);
        }
        return result;
    }
}
