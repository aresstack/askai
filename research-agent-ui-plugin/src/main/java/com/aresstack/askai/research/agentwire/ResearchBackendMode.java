package com.aresstack.askai.research.agentwire;

/**
 * Which {@link com.aresstack.askai.research.backend.ResearchSessionBackend} implementation a session uses.
 * The UI never knows which one is active — both live behind the same port; contract tests run against both
 * where functionally possible. FAKE stays fully functional (demo + deterministic tests); ACP is the
 * productive mode (the name is the persisted settings value; since the Java 21 return the agent runs
 * in-process, nothing external is involved).
 */
public enum ResearchBackendMode {
    FAKE,
    ACP
}
