package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.InProcessMcpServerRegistry;
import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.agent.ResearchArtifactStore;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** Session-scoped research-control endpoint: state-gated visibility, live re-check, tokens, generations. */
public class ResearchControlEndpointTest {

    /** Mutable fake context: tests flip phase/state to simulate transitions between list and call. */
    private static final class Ctx implements ResearchControlContext {
        String phaseId = ResearchStateIds.SCOPING;
        String stateId = ResearchStateIds.RUNNING;
        final AgentArtifactStore store = new ResearchArtifactStore();
        final InMemoryResearchSourceRepository sources = new InMemoryResearchSourceRepository();

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
            return store;
        }

        public ResearchSourceRepository sourceRepository() {
            return sources;
        }

        public String acceptCapture(String captureId) {
            if (!"cap1".equals(captureId)) {
                return null;
            }
            try {
                // Simulate promotion: a new source record appears in the shared repository.
                return "src-accepted";
            } finally {
                // (InMemory repo has fixed seeds; the id return is what the tool reports.)
            }
        }
    }

    private static McpToolResult call(InProcessMcpServerRegistry reg, ResearchControlEndpoint ep,
                                      String tool, String... kv) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            args.put(kv[i], kv[i + 1]);
        }
        return reg.invoke(ep.getEndpointId(), ep.getHandle().getToken(), new McpToolCall(tool, args));
    }

    private static List<String> tools(InProcessMcpServerRegistry reg, ResearchControlEndpoint ep) {
        return reg.listToolNames(ep.getEndpointId(), ep.getHandle().getToken());
    }

    @Test
    public void visibilityFollowsPhaseAndRunState() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ResearchControlEndpoint ep = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ep.open();

        // SCOPING/running: only the always-tools — issue #32: NO concept_save (the ResearchBrief is the
        // canonical scoping artifact) and no legacy per-stage tools anywhere.
        List<String> t = tools(reg, ep);
        assertTrue(t.containsAll(java.util.Arrays.asList("research_status", "artifact_read", "source_list",
                "source_review_context")));
        assertFalse(t.contains("concept_save"));
        assertFalse(t.contains("outline_save"));
        assertFalse(t.contains("draft_save"));
        // + the PR 16 context reads: source_overview, concept_summary, phase_change_request, and PR 15 search_plan_overview
        assertEquals("scoping offers exactly the 8 read tools", 8, t.size());

        // OUTLINE/running → outline_save only (legacy operability for old persisted sessions).
        ctx.phaseId = ResearchStateIds.OUTLINE;
        ep.refreshTools();
        t = tools(reg, ep);
        assertTrue(t.contains("outline_save"));
        assertFalse(t.contains("concept_save"));

        // RESEARCH/running → source tools only; the legacy findings/notes writers are gone.
        ctx.phaseId = ResearchStateIds.RESEARCH;
        ep.refreshTools();
        t = tools(reg, ep);
        assertTrue(t.containsAll(java.util.Arrays.asList("source_accept", "source_park")));
        assertFalse(t.contains("finding_add"));
        assertFalse(t.contains("notes_append"));

        // #43: DOCUMENT (id "draft") is the ONE writing phase for the canonical document.
        ctx.phaseId = ResearchStateIds.DRAFT;
        ep.refreshTools();
        t = tools(reg, ep);
        assertTrue("document offers document_read", t.contains("document_read"));
        assertTrue("document offers document_save", t.contains("document_save"));
        assertFalse(t.contains("draft_read"));
        assertFalse(t.contains("draft_save"));
        assertFalse(t.contains("final_read"));
        assertFalse(t.contains("final_save"));

        // Any non-running run state removes ALL write tools (approval gate, paused, blocked, failed, terminal).
        for (String s : new String[]{ResearchStateIds.WAITING_APPROVAL, ResearchStateIds.PAUSED,
                ResearchStateIds.BLOCKED, ResearchStateIds.FAILED, ResearchStateIds.CANCELLED}) {
            ctx.stateId = s;
            ep.refreshTools();
            t = tools(reg, ep);
            assertEquals("only the 8 read tools in " + s, 8, t.size());
        }
        ep.close();
    }

    @Test
    public void executionRechecksLiveStateEvenWhenToolWasListed() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ctx.phaseId = ResearchStateIds.DRAFT;
        ResearchControlEndpoint ep = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ep.open();
        assertTrue(tools(reg, ep).contains("document_save"));

        // Transition happens AFTER tools/list but BEFORE tools/call: the handler must reject server-side.
        ctx.stateId = ResearchStateIds.WAITING_APPROVAL;
        McpToolResult denied = call(reg, ep, "document_save", "markdown", "late write",
                "expected_revision", "0");
        assertTrue(denied.isError());
        assertTrue(denied.getText().contains("Not allowed in the current state"));
        ep.close();
    }

    @Test
    public void writesWorkWithOptimisticLockingAndAppends() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ctx.phaseId = ResearchStateIds.OUTLINE;
        ResearchControlEndpoint ep = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ep.open();

        // Save with the correct revision succeeds; a stale revision is a conflict, not an overwrite.
        long rev = ctx.store.read("outline").getRevision();
        assertFalse(call(reg, ep, "outline_save", "markdown", "# New", "expected_revision",
                String.valueOf(rev)).isError());
        McpToolResult stale = call(reg, ep, "outline_save", "markdown", "# Old copy",
                "expected_revision", String.valueOf(rev));
        assertTrue(stale.isError());
        assertEquals("# New", ctx.store.read("outline").getMarkdown());

        // #43: drafting AND finalizing happen inside the ONE Document phase on the ONE
        // canonical document (issue #32) — a later save sees the earlier one.
        ctx.phaseId = ResearchStateIds.DRAFT;
        ep.refreshTools();
        long docRev = ctx.store.read("document").getRevision();
        assertFalse(call(reg, ep, "document_save", "markdown", "# Working document",
                "expected_revision", String.valueOf(docRev)).isError());
        McpToolResult finalized = call(reg, ep, "document_read");
        assertFalse(finalized.isError());
        assertTrue("the finalizing pass reads the drafted document", finalized.getText()
                .contains("# Working document"));
        assertFalse(call(reg, ep, "document_save", "markdown", "# Working document\n\nDone.",
                "expected_revision", String.valueOf(ctx.store.read("document").getRevision())).isError());
        assertEquals("# Working document\n\nDone.", ctx.store.read("document").getMarkdown());

        // source_accept: unknown capture rejected; known capture reports the new source id.
        ctx.phaseId = ResearchStateIds.RESEARCH;
        ep.refreshTools();
        assertTrue(call(reg, ep, "source_accept", "capture_id", "nope").isError());
        McpToolResult accepted = call(reg, ep, "source_accept", "capture_id", "cap1");
        assertFalse(accepted.isError());
        assertTrue(accepted.getText().contains("src-accepted"));
        ep.close();
    }

    @Test
    public void endpointIdentityBindsSessionAndGenerationAndTokens() {
        InProcessMcpServerRegistry reg = new InProcessMcpServerRegistry();
        Ctx ctx = new Ctx();
        ResearchControlEndpoint g1 = new ResearchControlEndpoint(reg, "s1", 1L, ctx);
        ResearchControlEndpoint g2 = new ResearchControlEndpoint(reg, "s1", 2L, ctx);
        ResearchControlEndpoint other = new ResearchControlEndpoint(reg, "s2", 1L, ctx);
        g1.open();
        g2.open();
        other.open();

        assertNotEquals(g1.getEndpointId(), g2.getEndpointId()); // generation in the identity
        assertNotEquals(g1.getEndpointId(), other.getEndpointId()); // session in the identity
        assertNotEquals(g1.getHandle().getToken(), g2.getHandle().getToken());

        // Wrong token → rejected; g1's token cannot call g2's endpoint.
        assertTrue(reg.invoke(g2.getEndpointId(), g1.getHandle().getToken(),
                new McpToolCall("research_status", null)).isError());

        // Retiring the old generation invalidates exactly it; the new one keeps working.
        String g1Id = g1.getEndpointId();
        String g1Token = g1.getHandle().getToken();
        g1.close();
        g1.close(); // idempotent
        assertTrue(reg.invoke(g1Id, g1Token, new McpToolCall("research_status", null)).isError());
        assertFalse(call(reg, g2, "research_status").isError());
        g2.close();
        other.close();
    }
}
