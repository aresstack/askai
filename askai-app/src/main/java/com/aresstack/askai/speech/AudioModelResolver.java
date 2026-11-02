package com.aresstack.askai.speech;

/**
 * Port that resolves which installed model to use for transcription. "Automatic" picks a model whose
 * concrete installation was confirmed audio-capable via {@code /api/show} (preferring the last one that
 * worked); an explicitly chosen model is still re-verified. {@code UNKNOWN} never counts as audio.
 */
public interface AudioModelResolver {

    /**
     * @param requestedModel the user's explicit choice, or empty/"Automatic" for automatic selection
     * @return the resolution: a concrete model name when audio-capable, or a reason why not
     */
    AudioModelResolution resolve(String requestedModel);

    /** Outcome of resolving an audio model. */
    final class AudioModelResolution {

        /** Why an audio model could (not) be chosen. */
        public enum Status {
            RESOLVED, NO_AUDIO_MODEL, CAPABILITY_UNKNOWN, NOT_AUDIO_CAPABLE
        }

        private final Status status;
        private final String modelName;
        private final String capabilityStatus;

        public AudioModelResolution(Status status, String modelName, String capabilityStatus) {
            this.status = status;
            this.modelName = modelName == null ? "" : modelName;
            this.capabilityStatus = capabilityStatus == null ? "" : capabilityStatus;
        }

        public static AudioModelResolution resolved(String modelName) {
            return new AudioModelResolution(Status.RESOLVED, modelName, "audio");
        }

        public Status getStatus() {
            return status;
        }

        public String getModelName() {
            return modelName;
        }

        /** @return a short capability description for diagnostics (e.g. "audio", "completion, vision"). */
        public String getCapabilityStatus() {
            return capabilityStatus;
        }

        public boolean isResolved() {
            return status == Status.RESOLVED;
        }
    }
}
