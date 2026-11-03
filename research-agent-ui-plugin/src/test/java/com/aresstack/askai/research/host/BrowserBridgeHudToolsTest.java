package com.aresstack.askai.research.host;

import com.aresstack.askai.mcp.api.McpEndpointDefinition;
import com.aresstack.askai.mcp.api.McpEndpointHandle;
import com.aresstack.askai.mcp.api.McpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.capture.CaptureStore;

import org.junit.Test;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Contract test for the PRODUCTIVE browser tool catalog the runtime is allowed to call. The runtime may only
 * invoke tools the bridge publishes, so {@code web_hud_render}/{@code web_hud_poll} MUST be in this catalog —
 * otherwise the call is rejected before Playwright is ever reached ("Unknown tool", surfaced as the live
 * {@code [browser-hud] … cause=ToolFailure: Unknown tool} that made the HUD wholly inert). This guards that the
 * bridge and the sidecar never drift apart again.
 */
public class BrowserBridgeHudToolsTest {

    @Test
    public void catalogContainsBothHudTools() {
        CapturingRegistry registry = new CapturingRegistry();
        RecordingBrowser browser = new RecordingBrowser();
        BrowserBridgeEndpoint bridge = new BrowserBridgeEndpoint(
                registry, browser, new CaptureStore(10), "s1", 1L);

        bridge.open();

        // Sanity: the known-good tools are still there, and the two HUD tools now join them.
        assertTrue("web_open missing", registry.hasTool("web_open"));
        assertTrue("web_challenge_status missing", registry.hasTool("web_challenge_status"));
        assertTrue("web_hud_render must be published to the runtime", registry.hasTool("web_hud_render"));
        assertTrue("web_hud_poll must be published to the runtime", registry.hasTool("web_hud_poll"));
    }

    @Test
    public void invokingHudRenderReachesTheBrowserPort() {
        CapturingRegistry registry = new CapturingRegistry();
        RecordingBrowser browser = new RecordingBrowser();
        BrowserBridgeEndpoint bridge = new BrowserBridgeEndpoint(
                registry, browser, new CaptureStore(10), "s1", 1L);
        bridge.open();

        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("state", "PHASE|status|0|-1|false");
        McpToolResult result = registry.tool("web_hud_render").getHandler()
                .invoke(new McpToolCall("web_hud_render", args));

        assertFalse("render must not error on a live sidecar", result.isError());
        assertEquals("web_hud_render", browser.lastTool);
        assertEquals("PHASE|status|0|-1|false", browser.lastArgs.get("state"));
        assertFalse("render touches the page — it is a DATA call, not control", browser.controlUsed);
    }

    @Test
    public void invokingHudPollReachesTheBrowserPort() {
        CapturingRegistry registry = new CapturingRegistry();
        RecordingBrowser browser = new RecordingBrowser();
        BrowserBridgeEndpoint bridge = new BrowserBridgeEndpoint(
                registry, browser, new CaptureStore(10), "s1", 1L);
        bridge.open();

        McpToolResult result = registry.tool("web_hud_poll").getHandler()
                .invoke(new McpToolCall("web_hud_poll", Collections.<String, Object>emptyMap()));

        assertFalse(result.isError());
        assertEquals("web_hud_poll", browser.lastTool);
        assertTrue("the poll must go through the out-of-band CONTROL lane, never the data owner",
                browser.controlUsed);
    }

    // ------------------------------------------------------------------ fakes

    /** Captures the tool set the bridge publishes so the test can assert + invoke individual tools. */
    private static final class CapturingRegistry implements McpServerRegistry {
        private List<McpToolContribution> tools = Collections.emptyList();

        public McpEndpointHandle registerEndpoint(McpEndpointDefinition definition) {
            return new McpEndpointHandle(definition.getEndpointId(), "tok");
        }

        public void updateTools(McpEndpointHandle handle, Collection<McpToolContribution> published) {
            this.tools = new java.util.ArrayList<McpToolContribution>(published);
        }

        public void unregisterEndpoint(McpEndpointHandle handle) {
        }

        public String endpointUrl(McpEndpointHandle handle) {
            return "inprocess:test";
        }

        public java.util.List<String> toolNames(com.aresstack.askai.mcp.api.McpEndpointHandle handle) {
            return new java.util.ArrayList<String>();
        }

        public java.util.Map<String, String> toolCatalog(
                com.aresstack.askai.mcp.api.McpEndpointHandle handle) {
            return new java.util.LinkedHashMap<String, String>();
        }

        boolean hasTool(String name) {
            return tool(name) != null;
        }

        McpToolContribution tool(String name) {
            for (McpToolContribution t : tools) {
                if (t.getName().equals(name)) {
                    return t;
                }
            }
            return null;
        }
    }

    /** Records the last delegated browser command (and which lane carried it); canned success. */
    private static final class RecordingBrowser implements BrowserRuntimePort {
        String lastTool;
        Map<String, Object> lastArgs;
        boolean controlUsed;

        public String execute(String tool, Map<String, Object> arguments) {
            this.lastTool = tool;
            this.lastArgs = arguments;
            return "ok";
        }

        @Override
        public String executeControl(String tool, Map<String, Object> arguments) {
            this.controlUsed = true;
            this.lastTool = tool;
            this.lastArgs = arguments;
            return "";
        }

        public void setListener(Listener listener) {
        }

        public void ensureStarted() {
        }

        public void restart() {
        }

        public void stop() {
        }

        public boolean isReady() {
            return true;
        }

        public void close() {
        }
    }
}
