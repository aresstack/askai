package com.aresstack.askai.research.runtime.host;

import com.aresstack.askai.research.runtime.loop.SolonToolInvoker;

/** The host tool endpoints over MCP (streamable HTTP), for the stand-alone agent process and its tests. */
public final class SolonHostToolEndpoints implements HostToolEndpoints {

    @Override
    public HostToolEndpoint connect(String url, String transport, int requestTimeoutSeconds) {
        return new SolonToolInvoker(url, transport, requestTimeoutSeconds);
    }
}
