package com.aresstack.askai.localruntime.generation;

/**
 * Streaming callback for token-by-token generation. AskAI-owned. {@link #onToken} receives the incremental
 * {@code delta} and the full text so far; returning {@code false} requests cancellation (e.g. the HTTP
 * client disconnected). {@link #onComplete} fires once with the terminal result.
 */
public interface LocalGenerationTokenListener {

    /** @return {@code true} to keep generating, {@code false} to cancel as soon as possible. */
    boolean onToken(String delta, String textSoFar);

    /** Called exactly once when generation ends (stop, length, cancel or failure-after-partial). */
    void onComplete(LocalGenerationResult result);
}
