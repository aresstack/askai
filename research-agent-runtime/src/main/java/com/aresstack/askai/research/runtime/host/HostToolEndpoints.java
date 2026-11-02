package com.aresstack.askai.research.runtime.host;

/** Opens the host tool endpoints the host named in the launch configuration (by URL). */
public interface HostToolEndpoints {

    /**
     * @param url                   the endpoint URL from the launch configuration
     * @param transport             the transport named next to it (ignored in-process)
     * @param requestTimeoutSeconds the per-call budget (ignored in-process: calls run on the caller's thread)
     */
    HostToolEndpoint connect(String url, String transport, int requestTimeoutSeconds);
}
