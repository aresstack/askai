package com.aresstack.askai.research.agent;

/** Test access to the package-private gate line of plan PR 14. */
public final class ResearchAgentSessionGateAccess {
    private ResearchAgentSessionGateAccess() {
    }

    public static String gate(java.util.List<String> uncovered) {
        return ResearchAgentSession.gateCoverageText(uncovered);
    }
}
