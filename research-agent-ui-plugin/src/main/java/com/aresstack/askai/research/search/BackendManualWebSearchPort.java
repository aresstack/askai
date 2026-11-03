package com.aresstack.askai.research.search;

import com.aresstack.askai.research.backend.ResearchSessionBackend;
import com.aresstack.askai.research.backend.ResearchSessionHandle;

/**
 * The productive {@link ManualWebSearchPort}: it encodes a user search as a typed {@code #RSC1#} service
 * command and hands it to the session backend's {@link ResearchSessionBackend#submitServiceCommand} — a
 * transport-agnostic seam (the productive backend carries it as a service command; a fake backend ignores it). It is
 * NOT the chat path: it never calls {@code submitPrompt}. Cancellation cancels the in-flight service command
 * through the backend. The port is transport-agnostic on purpose — if the transport later grows a real custom method,
 * only the backend adapter changes, not this port or its callers.
 */
public final class BackendManualWebSearchPort implements ManualWebSearchPort {

    private final ResearchSessionBackend backend;
    private final ResearchSessionHandle handle;

    public BackendManualWebSearchPort(ResearchSessionBackend backend, ResearchSessionHandle handle) {
        this.backend = backend;
        this.handle = handle;
    }

    @Override
    public ManualWebSearchHandle search(ManualWebSearchRequest request) {
        final String requestId = java.util.UUID.randomUUID().toString();
        String envelope = ResearchServiceCommandWire.manualSearch(requestId,
                request == null ? "" : request.getQuery(),
                request == null ? null : request.getLanguage().getCode());
        backend.submitServiceCommand(handle, envelope);
        System.err.println("[manual-search] service command sent requestId=" + requestId);
        return new ManualWebSearchHandle() {
            public String getRequestId() {
                return requestId;
            }

            public void cancel() {
                backend.cancel(handle); // cancels the in-flight service-command turn (sequential model)
            }
        };
    }
}
