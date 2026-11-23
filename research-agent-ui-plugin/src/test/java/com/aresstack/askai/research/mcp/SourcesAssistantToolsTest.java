package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plan PR 15: the Sources assistant's add-only actions and the Sources block. */
public class SourcesAssistantToolsTest {

    private String phaseId = ResearchStateIds.RESEARCH;
    private String stateId = ResearchStateIds.RUNNING;
    private String overview;
    private final List<String> calls = new ArrayList<String>();

    private final ResearchControlContext ctx = new ResearchControlContext() {
        public String currentPhaseId() {
            return phaseId;
        }

        public String currentStateId() {
            return stateId;
        }

        public String statusLine() {
            return phaseId + "/" + stateId;
        }

        public AgentArtifactStore artifactStore() {
            return null;
        }

        public ResearchSourceRepository sourceRepository() {
            return InMemoryResearchSourceRepository.empty();
        }

        public String acceptCapture(String captureId) {
            return null;
        }

        @Override
        public String requestResearch(String query, String node) {
            calls.add("request " + query + " @" + node);
            return "ADDED plan entry e1 \"" + query + "\"";
        }

        @Override
        public String blacklistAdd(String entry) {
            calls.add("blacklist " + entry);
            return "OSGi".equals(entry) ? "Tool failed: \"OSGi\" is a card of the approved concept."
                    : "ADDED blacklist entry \"" + entry + "\"";
        }

        @Override
        public String searchPlanOverview() {
            return overview;
        }
    };

    private McpToolContribution tool(String name) {
        for (McpToolContribution tool : ResearchToolPolicy.toolsFor(phaseId, stateId, ctx)) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    private McpToolResult call(String name, String... keyValues) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            args.put(keyValues[i], keyValues[i + 1]);
        }
        return tool(name).getHandler().invoke(new McpToolCall(name, args));
    }

    @Test
    public void theAddOnlyActionsAreOfferedOnlyInResearchRunning() {
        assertTrue(tool("request_research") != null);
        assertTrue(tool("blacklist_add") != null);
        for (String[] other : new String[][] {{ResearchStateIds.RESEARCH, ResearchStateIds.WAITING_APPROVAL},
                {ResearchStateIds.SCOPING, ResearchStateIds.RUNNING}, {ResearchStateIds.OUTLINE,
                        ResearchStateIds.RUNNING}, {ResearchStateIds.DRAFT, ResearchStateIds.RUNNING}}) {
            phaseId = other[0];
            stateId = other[1];
            assertNull(other[0] + "/" + other[1], tool("request_research"));
            assertNull(other[0] + "/" + other[1], tool("blacklist_add"));
        }
    }

    @Test
    public void requestResearchAddsAPlanEntryThroughTheContext() {
        McpToolResult result = call("request_research", "query", "JPMS layers", "node", "Module system");
        assertFalse(result.getText(), result.isError());
        assertEquals("request JPMS layers @Module system", calls.get(0));
        assertTrue(call("request_research").isError());
    }

    @Test
    public void aWithdrawnStateIsRefusedServerSide() {
        McpToolContribution request = tool("request_research");
        stateId = ResearchStateIds.WAITING_APPROVAL; // the gate opened after the offer
        McpToolResult result = request.getHandler().invoke(new McpToolCall("request_research",
                java.util.Collections.<String, Object>singletonMap("query", "x")));
        assertTrue(result.isError());
        assertTrue(result.getText(), result.getText().startsWith("Not allowed in the current state"));
        assertTrue(calls.isEmpty());
    }

    @Test
    public void blacklistAddPassesTheRefusalOfTheAddOnlyPath() {
        assertFalse(call("blacklist_add", "entry", "Spring").isError());
        McpToolResult refused = call("blacklist_add", "entry", "OSGi");
        assertTrue(refused.isError());
        assertTrue(refused.getText(), refused.getText().contains("approved concept"));
    }

    @Test
    public void theSourcesBlockIsReadableAndSaysWhenThereIsNoPlan() {
        assertEquals("No search plan.", call("search_plan_overview").getText());
        overview = "Search plan coverage per concept node:\n- Module system: open";
        assertEquals(overview, call("search_plan_overview").getText());
        phaseId = ResearchStateIds.OUTLINE;
        assertTrue("readable in every phase", tool("search_plan_overview") != null);
    }
}
