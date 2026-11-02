package com.aresstack.askai.research.runtime.host;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import java.util.List;
import java.util.Map;

/**
 * One host tool endpoint the research runtime talks to (research control, research service, research
 * browser). {@link ToolInvoker#call} classifies failures; {@link #callTool} hands back the raw result.
 */
public interface HostToolEndpoint extends ToolInvoker, AutoCloseable {

    /** The tool names the host currently offers on this endpoint. */
    List<String> toolNames();

    /**
     * Calls a tool and returns its result, an error result included.
     *
     * @throws RuntimeException the endpoint is not reachable
     */
    HostToolResult callTool(String toolName, Map<String, Object> args);

    @Override
    void close();
}
