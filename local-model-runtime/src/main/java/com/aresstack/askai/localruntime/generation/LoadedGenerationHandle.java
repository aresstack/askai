package com.aresstack.askai.localruntime.generation;

/**
 * A loaded generation model. AskAI-owned; the concrete implementation (once the win-directml generation
 * library is linked) never surfaces a library type through this interface. Thread-safety and the
 * lease/unload discipline are provided by the engine that owns the handle, not by callers.
 */
public interface LoadedGenerationHandle extends AutoCloseable {

    /** Non-streaming generation. */
    LocalGenerationResult generate(LocalGenerationRequest request) throws LocalGenerationException;

    /** Streaming generation; the listener may cancel via its return value. */
    void generate(LocalGenerationRequest request, LocalGenerationTokenListener listener)
            throws LocalGenerationException;

    /** The virtual model id this handle serves. */
    String virtualName();

    @Override
    void close();
}
