package com.aresstack.askai.research.agentwire;

import com.aresstack.askai.research.backend.ResearchBackendEvent;
import com.aresstack.askai.research.backend.ResearchBackendEventType;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** A scoping projection wire line decodes into a typed SCOPING_PROJECTION event (suggestions + advice). */
public class ResearchAgentEventMapperScopingTest {

    private static ResearchBackendEvent map(String wireLine) {
        return ResearchAgentEventMapper.mapMessage("p1", wireLine).build();
    }

    @Test
    public void aScopeassistLineBecomesATypedProjection() {
        ResearchBackendEvent event = map("#RSX1# scopeassist phase=scoping advice=CONTINUE "
                + "advicereason=precise sugg=wearables|tech|1,glasses|priv|2");

        assertEquals(ResearchBackendEventType.SCOPING_PROJECTION, event.getType());
        ScopingAssistantUpdate projection = event.getScopingProjection();
        assertEquals("scoping", projection.getPhaseId());
        assertEquals("CONTINUE", projection.getAdviceRecommendation());
        assertEquals(2, projection.getSearchSuggestions().size());
        assertEquals("wearables", projection.getSearchSuggestions().get(0).getQuery());
        assertEquals("tech", projection.getSearchSuggestions().get(0).getPurpose());
        assertEquals(1, projection.getSearchSuggestions().get(0).getPriority());
        assertEquals("glasses", projection.getSearchSuggestions().get(1).getQuery());
    }

    @Test
    public void anEmptyProjectionClearsTheWorkspacePanels() {
        ResearchBackendEvent event = map("#RSX1# scopeassist phase=scoping advice=NEUTRAL");

        assertEquals(ResearchBackendEventType.SCOPING_PROJECTION, event.getType());
        assertTrue(event.getScopingProjection().getSearchSuggestions().isEmpty());
    }

    @Test
    public void aBriefLineBecomesAResearchBriefEventCarryingTheMarkdown() throws Exception {
        String brief = "# Research Brief\n\n## Fragestellung\n\nWearables mit Audio & Video?";
        String content = java.net.URLEncoder.encode(brief, "UTF-8");
        ResearchBackendEvent event = map("#RSX1# brief phase=scoping content=" + content);

        assertEquals(ResearchBackendEventType.RESEARCH_BRIEF, event.getType());
        assertEquals(brief, event.getText());
        assertEquals("scoping", event.getTitle());
    }
}
