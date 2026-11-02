package com.aresstack.askai.speech;

import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.client.OllamaModelInfo;
import com.aresstack.askai.stt.AudioCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link AudioModelResolver} backed by Ollama's {@code /api/show}. "Automatic" scans the installed
 * models, keeps only those whose concrete installation reports the exact {@code audio} capability, and
 * prefers the last model that transcribed successfully. An explicitly chosen model is re-verified.
 * {@code UNKNOWN} (no capabilities field) never counts as audio.
 */
public final class OllamaAudioModelResolver implements AudioModelResolver {

    private static final String AUTOMATIC = "Automatic";

    private final Supplier<String> baseUrlSupplier;
    private final Supplier<String> lastAudioModelSupplier;

    public OllamaAudioModelResolver(Supplier<String> baseUrlSupplier, Supplier<String> lastAudioModelSupplier) {
        this.baseUrlSupplier = baseUrlSupplier;
        this.lastAudioModelSupplier = lastAudioModelSupplier;
    }

    public AudioModelResolution resolve(String requestedModel) {
        String requested = requestedModel == null ? "" : requestedModel.trim();
        boolean automatic = requested.isEmpty() || AUTOMATIC.equalsIgnoreCase(requested);
        AskAiOllamaClient client = new AskAiOllamaClient(baseUrlSupplier.get());
        try {
            return automatic ? resolveAutomatic(client) : verifyExplicit(client, requested);
        } catch (Exception ex) {
            // Server/model unreachable: cannot confirm audio → UNKNOWN (never treated as audio-capable).
            return new AudioModelResolution(AudioModelResolution.Status.CAPABILITY_UNKNOWN, requested, "");
        }
    }

    private AudioModelResolution verifyExplicit(AskAiOllamaClient client, String model) throws Exception {
        List<String> capabilities = client.getModelInfo(model).getCapabilities();
        if (capabilities == null || capabilities.isEmpty()) {
            return new AudioModelResolution(AudioModelResolution.Status.CAPABILITY_UNKNOWN, model, "");
        }
        if (AudioCapability.isAudioCapable(capabilities)) {
            return AudioModelResolution.resolved(model);
        }
        return new AudioModelResolution(AudioModelResolution.Status.NOT_AUDIO_CAPABLE, model, join(capabilities));
    }

    private AudioModelResolution resolveAutomatic(AskAiOllamaClient client) throws Exception {
        List<String> audioModels = new ArrayList<String>();
        for (OllamaModelInfo model : client.getInstalledModels()) {
            String name = model.getDisplayName();
            try {
                if (AudioCapability.isAudioCapable(client.getModelInfo(name).getCapabilities())) {
                    audioModels.add(name);
                }
            } catch (Exception ignored) {
                // skip a model we cannot query
            }
        }
        if (audioModels.isEmpty()) {
            return new AudioModelResolution(AudioModelResolution.Status.NO_AUDIO_MODEL, "", "");
        }
        String last = lastAudioModelSupplier.get();
        if (last != null && !last.trim().isEmpty() && audioModels.contains(last.trim())) {
            return AudioModelResolution.resolved(last.trim());
        }
        return AudioModelResolution.resolved(audioModels.get(0));
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
