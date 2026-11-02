package com.aresstack.askai.video;

/**
 * A recording BACKEND descriptor (ported principle from WD4J/corenth): identity, display name, runtime
 * AVAILABILITY and a factory. Availability is answered by the provider itself (e.g. a native-library
 * locator), never by UI heuristics, so the UI only offers backends whose runtime is actually present —
 * and never silently falls back to another one.
 */
public interface MediaRecorderProvider {

    /** Stable id, e.g. {@code "jcodec"}, {@code "vlc"}, {@code "ffmpeg"}. */
    String getId();

    /** Human-readable name for the backend dropdown. */
    String getDisplayName();

    /** @return whether this backend's runtime is available on this machine right now. */
    boolean isAvailable();

    /** Create a fresh recorder. Only call when {@link #isAvailable()} is true. */
    MediaRecorder createRecorder();
}
