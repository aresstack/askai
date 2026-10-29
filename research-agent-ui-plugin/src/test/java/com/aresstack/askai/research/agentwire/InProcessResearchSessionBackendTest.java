package com.aresstack.askai.research.agentwire;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ResearchProjectRequest;
import com.aresstack.askai.research.backend.ResearchPrompt;
import com.aresstack.askai.research.backend.ResearchSessionHandle;
import com.aresstack.askai.research.backend.ResearchSessionListener;
import com.aresstack.askai.research.runtime.loop.ContributionToolInvoker;
import com.aresstack.askai.research.state.ResearchCommandType;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The in-process backend is a pure adapter over {@code ResearchAgentSession}: it opens the agent against the
 * research lane (real readiness check), reports an absent browser honestly, maps the agent's lines into
 * backend events with a monotonic sequence, ends a foreground turn with the typed COMPLETED terminal, never
 * drives the state machine, and stays silent after close.
 */
public class InProcessResearchSessionBackendTest {

    private static final class Rec implements ResearchSessionListener {
        final List<ResearchBackendEvent> events = new CopyOnWriteArrayList<ResearchBackendEvent>();
        final CountDownLatch completed = new CountDownLatch(1);

        public void onEvent(ResearchBackendEvent event) {
            events.add(event);
            if (event.getType() == ResearchBackendEventType.COMPLETED) {
                completed.countDown();
            }
        }

        boolean sawText(String fragment) {
            for (ResearchBackendEvent event : events) {
                if (String.valueOf(event.getText()).contains(fragment)
                        || String.valueOf(event.getTechnicalDetail()).contains(fragment)) {
                    return true;
                }
            }
            return false;
        }

        boolean sawType(ResearchBackendEventType type) {
            for (ResearchBackendEvent event : events) {
                if (event.getType() == type) {
                    return true;
                }
            }
            return false;
        }
    }

    /** A research lane that offers research_status (the agent's readiness check) and counts its calls. */
    private static ContributionToolInvoker researchLane(final AtomicInteger statusCalls, final String status) {
        return new ContributionToolInvoker(Collections.singletonList(
                McpToolContribution.of("research_status", "Current research state.", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        statusCalls.incrementAndGet();
                        return McpToolResult.ok(status);
                    }
                })));
    }

    @Test
    public void startOpensTheAgentAgainstTheResearchLaneAndReportsAMissingBrowserHonestly() {
        AtomicInteger statusCalls = new AtomicInteger();
        InProcessResearchSessionBackend backend = new InProcessResearchSessionBackend(
                new HashMap<String, String>(), researchLane(statusCalls, "scoping/new rev=0"), null, null);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s1", "p1", "t"), rec);
        assertNotNull(handle);
        assertEquals("s1", handle.getSessionId());
        assertTrue("the agent's readiness check called research_status", statusCalls.get() >= 1);
        assertFalse("no error on a clean start", rec.sawType(ResearchBackendEventType.ERROR));
        // Missing browser lane is HONESTLY visible (no silent STATIC_HTTP fallback).
        assertTrue(rec.sawText("BROWSER_NOT_AVAILABLE"));
        // Pure adapter: no functional transitions ever.
        assertFalse(backend.canExecute(handle, ResearchCommandType.START));
        backend.close(handle);
    }

    @Test
    public void aLaneWithoutResearchStatusFailsTheStartAtomically() {
        InProcessResearchSessionBackend backend = new InProcessResearchSessionBackend(
                new HashMap<String, String>(),
                new ContributionToolInvoker(Collections.<McpToolContribution>emptyList()), null, null);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s1", "p1", "t"), rec);
        assertEquals(1, rec.events.size());
        assertEquals(ResearchBackendEventType.ERROR, rec.events.get(0).getType());
        assertTrue(rec.events.get(0).getTechnicalDetail().contains("OPEN"));
        // No half-started agent: a prompt afterwards emits nothing.
        backend.submitPrompt(handle, new ResearchPrompt("go", ""));
        assertEquals(1, rec.events.size());
        backend.close(handle);
    }

    @Test
    public void aForegroundTurnStreamsLinesAndEndsWithTheTypedCompletedTerminal() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        InProcessResearchSessionBackend backend = new InProcessResearchSessionBackend(
                new HashMap<String, String>(), researchLane(statusCalls, "scoping/new rev=0"), null, null);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s1", "p1", "t"), rec);
        backend.submitPrompt(handle, new ResearchPrompt("hello", ""));
        assertTrue("the turn ends", rec.completed.await(30, TimeUnit.SECONDS));
        assertTrue("readiness announced as a technical line", rec.sawText("RESEARCH_MCP_READY"));
        assertTrue("the live research_status is mirrored", rec.sawText("scoping/new rev=0"));
        assertTrue("the turn read the host state", statusCalls.get() >= 2);
        // The terminal MUST be the COMPLETED type (the session clears its turn-in-flight flag on it).
        assertEquals(ResearchBackendEventType.COMPLETED, rec.events.get(rec.events.size() - 1).getType());
        long last = 0;
        for (ResearchBackendEvent e : rec.events) {
            assertTrue("monotonic backend sequence", e.getSequenceNumber() > last);
            last = e.getSequenceNumber();
        }
        backend.close(handle);
    }

    @Test
    public void cancelOnlyCancelsTheTurnAndCloseSilencesLateEvents() throws Exception {
        AtomicInteger statusCalls = new AtomicInteger();
        InProcessResearchSessionBackend backend = new InProcessResearchSessionBackend(
                new HashMap<String, String>(), researchLane(statusCalls, "scoping/new rev=0"), null, null);
        Rec rec = new Rec();
        ResearchSessionHandle handle = backend.createSession(
                new ResearchProjectRequest("s2", "p", "t"), rec);
        backend.cancel(handle); // nothing in flight: a no-op, never an error
        assertFalse(rec.sawType(ResearchBackendEventType.ERROR));
        backend.close(handle);
        int before = rec.events.size();
        backend.submitPrompt(handle, new ResearchPrompt("late", ""));
        Thread.sleep(200);
        assertEquals("no events after close", before, rec.events.size());
    }
}
