package com.aresstack.askai.research.host;

import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.util.Collections;

/** PR 07: brings a productive session's concept to the structural minimum of submit-scope. */
final class ConceptTestSupport {

    private ConceptTestSupport() {
    }

    /**
     * Start Concept when it is new, then add one first-level card and a mission when they are missing, and
     * give the session a ready fake embedding model (PR 12: submit-scope requires one).
     */
    static void makeSubmittable(ResearchAgentSession session) {
        readyEmbedding(session);
        if (ResearchStateIds.NEW.equals(session.currentResearchSnapshot().getCurrentStateId())) {
            session.dispatch(ResearchCommandType.START, null);
        }
        session.conceptBranchService().addCards(Collections.<String>emptyList(),
                Collections.singletonList("Isolation"));
        if (session.currentMission().trim().isEmpty()) {
            String refused = session.editMission("Plugin isolation");
            if (refused != null) {
                throw new AssertionError(refused);
            }
        }
    }

    /** PR 12: a ready (fake) embedding model; nothing is called on it unless the scope fence has anchors. */
    static void readyEmbedding(ResearchAgentSession session) {
        ProductiveResearchSessionResources resources = resourcesOf(session);
        if (resources != null && resources.getEmbeddingDescriptor() == null) {
            resources.setEmbeddingDescriptor(fakeEmbedding());
        }
    }

    static com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor fakeEmbedding() {
        return new com.aresstack.askai.agent.model.embedding.EmbeddingEndpointDescriptor("fake/embedding:test",
                "http://127.0.0.1:9", "/api/embed", 8, "none", "rev-1", 1000L);
    }

    static ProductiveResearchSessionResources resourcesOf(ResearchAgentSession session) {
        try {
            java.lang.reflect.Field field = ResearchAgentSession.class.getDeclaredField("productiveResources");
            field.setAccessible(true);
            return (ProductiveResearchSessionResources) field.get(session);
        } catch (ReflectiveOperationException missing) {
            throw new AssertionError(missing);
        }
    }
}
