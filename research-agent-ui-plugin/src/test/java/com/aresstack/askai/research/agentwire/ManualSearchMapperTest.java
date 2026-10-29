package com.aresstack.askai.research.agentwire;

import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;

import org.junit.Test;

import java.net.URLEncoder;

import static org.junit.Assert.assertEquals;

/** The host decodes {@code #RSX1# manual_search_*} wire lines into typed MANUAL_SEARCH events. */
public class ManualSearchMapperTest {


    @Test
    public void startedCarriesTheDecodedQueryAndRequestId() throws Exception {
        String line = "#RSX1# manual_search_started request_id=R1 query="
                + URLEncoder.encode("wearables audio", "UTF-8");
        ResearchBackendEvent e = ResearchAgentEventMapper.mapMessage("p1", line).build();
        assertEquals(ResearchBackendEventType.MANUAL_SEARCH, e.getType());
        assertEquals("started", e.getTitle());
        assertEquals("R1", e.getTechnicalDetail());
        assertEquals("Websuche: wearables audio", e.getText());
        assertEquals("manual-search-R1", e.getActivityId());
    }

    @Test
    public void completedReportsTheResultCount() {
        String line = "#RSX1# manual_search_completed request_id=R1 results=3 status=RESULTS";
        ResearchBackendEvent e = ResearchAgentEventMapper.mapMessage("p1", line).build();
        assertEquals(ResearchBackendEventType.MANUAL_SEARCH, e.getType());
        assertEquals("completed", e.getTitle());
        assertEquals("R1", e.getTechnicalDetail());
        assertEquals("3 Treffer", e.getText());
    }

    @Test
    public void anUnavailableFailureIsUserReadable() {
        String line = "#RSX1# manual_search_failed request_id=R1 reason=SEARCH_UNAVAILABLE";
        ResearchBackendEvent e = ResearchAgentEventMapper.mapMessage("p1", line).build();
        assertEquals("failed", e.getTitle());
        assertEquals("Websuche nicht verfügbar.", e.getText());
    }

    /**
     * A TECHNICAL search failure (browser/SERP/reranker) now arrives as a {@code failed} event — NOT a
     * {@code completed} 0-hit search — so the host runs its terminal path (composer released, problem shown)
     * and the chat turn never wedges busy. Every technical reason token is user-readable + retryable.
     */
    @Test
    public void aTechnicalFailureIsAUserReadableFailedEvent() {
        for (String reason : new String[] {"SEARCH_TECHNICAL_PROBLEM", "MCP_UNAVAILABLE",
                "RERANKER_UNAVAILABLE", "RERANKER_TIMEOUT", "RERANKER_INVALID_RESPONSE",
                "RERANKER_CONFIGURATION_ERROR"}) {
            String line = "#RSX1# manual_search_failed request_id=R1 reason=" + reason;
            ResearchBackendEvent e = ResearchAgentEventMapper.mapMessage("p1", line).build();
            assertEquals("failed", e.getTitle());
            assertEquals("R1", e.getTechnicalDetail());
            assertEquals("Websuche technisch fehlgeschlagen. Bitte erneut versuchen.", e.getText());
        }
    }
}
