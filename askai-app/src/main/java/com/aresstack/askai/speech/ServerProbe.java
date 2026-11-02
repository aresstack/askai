package com.aresstack.askai.speech;

/**
 * Port for the cheap server-side checks the readiness preflight needs: the Ollama version (also the
 * reachability signal) and whether the transcription endpoint exists. The adapter probes
 * {@code /v1/audio/transcriptions} with a lightweight request (a 404 means the route is missing; any
 * other status means it is registered) so no real audio is uploaded just to check.
 */
public interface ServerProbe {

    /** @return a stable key for the current server (e.g. its base URL), for cache scoping. */
    String serverKey();

    /** @return the Ollama server version; throws when the server is unreachable. */
    String version() throws Exception;

    /** @return true when {@code POST /v1/audio/transcriptions} is registered (probe is not a 404). */
    boolean endpointAvailable() throws Exception;
}
