package com.aresstack.askai.localruntime.generation;

/**
 * The default generation port on this development branch: no productive win-directml generation runtime is
 * linked yet, so every load fails cleanly with {@link LocalGenerationErrorCode#RUNTIME_NOT_LINKED}. This
 * lets the /api/chat and /api/generate contracts (validation, capability routing, streaming shape, typed
 * errors) exist and be tested now, while the concrete family adapters arrive later behind the SAME port.
 */
public final class NotLinkedGenerationRuntimePort implements LocalGenerationRuntimePort {

    @Override
    public LoadedGenerationHandle load(LocalGenerationLoadRequest request) throws LocalGenerationException {
        throw new LocalGenerationException(LocalGenerationErrorCode.RUNTIME_NOT_LINKED,
                "no local generation runtime is linked in this build yet for '"
                        + (request == null ? "?" : request.virtualName()) + "'");
    }

    @Override
    public boolean isLinked() {
        return false;
    }
}
