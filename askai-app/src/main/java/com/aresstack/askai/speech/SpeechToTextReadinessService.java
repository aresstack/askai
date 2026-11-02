package com.aresstack.askai.speech;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Speech-to-text preflight. Separates "server reachable + endpoint present" from "an audio model is
 * available", and caches the verdict per (server, version, requested model) so it is cheap to call
 * before each dictation. {@link #invalidate()} drops the cache after a model install or server change.
 */
public final class SpeechToTextReadinessService {

    private final ServerProbe serverProbe;
    private final AudioModelResolver audioModelResolver;
    private final Map<String, ReadinessStatus> cache = new ConcurrentHashMap<String, ReadinessStatus>();

    public SpeechToTextReadinessService(ServerProbe serverProbe, AudioModelResolver audioModelResolver) {
        this.serverProbe = serverProbe;
        this.audioModelResolver = audioModelResolver;
    }

    /**
     * @param requestedModel the user's chosen model, or empty/"Automatic" for automatic selection
     * @return the readiness verdict (cached when the server version is known)
     */
    public ReadinessStatus check(String requestedModel) {
        String version;
        try {
            version = serverProbe.version();
        } catch (Exception ex) {
            return ReadinessStatus.SERVER_UNREACHABLE; // not cached: transient
        }

        String key = serverProbe.serverKey() + "|" + version + "|" + (requestedModel == null ? "" : requestedModel);
        ReadinessStatus cached = cache.get(key);
        if (cached != null) {
            return cached;
        }

        ReadinessStatus status = compute(requestedModel);
        cache.put(key, status);
        return status;
    }

    private ReadinessStatus compute(String requestedModel) {
        boolean endpointAvailable;
        try {
            endpointAvailable = serverProbe.endpointAvailable();
        } catch (Exception ex) {
            return ReadinessStatus.SERVER_UNREACHABLE;
        }
        if (!endpointAvailable) {
            return ReadinessStatus.SERVER_ENDPOINT_UNAVAILABLE;
        }
        AudioModelResolver.AudioModelResolution resolution = audioModelResolver.resolve(requestedModel);
        switch (resolution.getStatus()) {
            case RESOLVED:
                return ReadinessStatus.READY;
            case CAPABILITY_UNKNOWN:
                return ReadinessStatus.MODEL_CAPABILITY_UNKNOWN;
            case NOT_AUDIO_CAPABLE:
                return ReadinessStatus.MODEL_NOT_AUDIO_CAPABLE;
            case NO_AUDIO_MODEL:
            default:
                return ReadinessStatus.NO_AUDIO_MODEL;
        }
    }

    /** Drops all cached verdicts (call after a model install, model-list refresh or server change). */
    public void invalidate() {
        cache.clear();
    }
}
