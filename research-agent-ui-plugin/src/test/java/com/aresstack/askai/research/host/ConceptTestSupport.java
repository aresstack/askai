package com.aresstack.askai.research.host;

import com.aresstack.askai.research.agent.ResearchAgentSession;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import java.util.Collections;

/** PR 07: brings a productive session's concept to the structural minimum of submit-scope. */
final class ConceptTestSupport {

    private ConceptTestSupport() {
    }

    /** Start Concept when it is new, then add one first-level card and a mission when they are missing. */
    static void makeSubmittable(ResearchAgentSession session) {
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
}
