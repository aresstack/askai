package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.PromptBlockMeter;
import com.aresstack.askai.research.runtime.team.TeamAgentPlaybook;
import com.aresstack.askai.research.runtime.team.TeamAgentStateView;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRecord;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.sources.SourceStatus;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plan PR 16: the host blocks the phase-gated context reads every turn, and the bounded state block. */
public class PhaseContextToolsTest {

    private final InMemoryResearchSourceRepository sources = InMemoryResearchSourceRepository.empty();
    private String phaseId = ResearchStateIds.RESEARCH;
    private String stateId = ResearchStateIds.RUNNING;
    private String summary;
    private String changeRequest = "";

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
            return sources;
        }

        public String acceptCapture(String captureId) {
            return null;
        }

        @Override
        public String frozenConceptSummary() {
            return summary;
        }

        @Override
        public String latestChangeRequest() {
            return changeRequest;
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

    private String call(String name) {
        return tool(name).getHandler().invoke(new McpToolCall(name, new HashMap<String, Object>())).getText();
    }

    private void addSources(int count) {
        SourceStatus[] statuses = SourceStatus.values();
        for (int i = 0; i < count; i++) {
            StringBuilder title = new StringBuilder("Source number " + i + " about class loaders and modules");
            while (title.length() < 200) {
                title.append(" and more words");
            }
            sources.put(ResearchSourceRecord.builder("source-" + i)
                    .title(title.toString())
                    .url("https://example.test/" + i)
                    .capturedAt(1000L + i)
                    .status(statuses[i % statuses.length])
                    .build());
        }
    }

    @Test
    public void theReadBlocksAreOfferedInEveryPhaseAndState() {
        for (String phase : new String[] {ResearchStateIds.SCOPING, ResearchStateIds.RESEARCH,
                ResearchStateIds.OUTLINE, ResearchStateIds.DRAFT}) {
            for (String state : new String[] {ResearchStateIds.RUNNING, ResearchStateIds.WAITING}) {
                phaseId = phase;
                stateId = state;
                assertTrue(phase + "/" + state, tool("source_overview") != null);
                assertTrue(phase + "/" + state, tool("concept_summary") != null);
                assertTrue(phase + "/" + state, tool("phase_change_request") != null);
            }
        }
    }

    @Test
    public void withTwoHundredSourcesTheStateBlockStaysUnderFourHundredFiftyTokens() {
        addSources(200);
        String overview = call("source_overview");
        assertTrue(overview, overview.startsWith("Sources: 200 total"));
        int titleLines = 0;
        for (String line : overview.split("\n")) {
            if (line.startsWith("- ")) {
                titleLines++;
            }
        }
        assertEquals("only the ten newest titles", 10, titleLines);
        assertTrue("the newest source comes first", overview.contains("Newest 10:\n- source-199: "));
        assertFalse("an old source is not named", overview.contains("source-0:"));

        TeamAgentStateView view = new TeamAgentStateView(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING,
                Collections.<String>emptyList()).withSources(overview);
        PromptBlockMeter meter = new PromptBlockMeter().add(PromptBlockMeter.STATE, ChatMessage.system(
                TeamAgentPlaybook.stateContext(view, "How do Java class loaders isolate plugins?",
                        Collections.singletonList("Module system"), "", Collections.<String>emptyList())));
        assertTrue("state block tokens=" + meter.estimatedTokens(), meter.estimatedTokens() < 450);
    }

    @Test
    public void noSourcesSaysSo() {
        assertEquals("No sources.", call("source_overview"));
    }

    @Test
    public void theConceptSummaryAndTheChangeRequestComeFromTheContext() {
        assertEquals("No approved concept.", call("concept_summary"));
        assertEquals("No change request.", call("phase_change_request"));
        summary = "Approved concept revision 2\nCards:\n- Class loaders\n";
        changeRequest = "Add more sources on JPMS";
        assertEquals(summary, call("concept_summary"));
        assertEquals("Add more sources on JPMS", call("phase_change_request"));
    }

    @Test
    public void theLegacyConceptAddIsNotOfferedInAnyPhase() {
        for (String phase : new String[] {ResearchStateIds.SCOPING, ResearchStateIds.RESEARCH,
                ResearchStateIds.OUTLINE, ResearchStateIds.DRAFT}) {
            phaseId = phase;
            assertNull(phase, tool("concept_add"));
        }
    }
}
