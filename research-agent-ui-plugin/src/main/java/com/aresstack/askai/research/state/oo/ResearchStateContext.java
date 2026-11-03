package com.aresstack.askai.research.state.oo;

/**
 * Ambient dependencies for a transition: the session id (for future correlation) and the factory used to build
 * target states. Kept small on purpose; it carries no Swing/transport/PF4J type.
 */
public final class ResearchStateContext {

    /** Supplies stable ids for freshly-raised approval gates (injectable for deterministic tests). */
    public interface IdGenerator {
        String newId();
    }

    private final String sessionId;
    private final ResearchStateFactory factory;
    private final IdGenerator idGenerator;

    public ResearchStateContext(String sessionId, ResearchStateFactory factory, IdGenerator idGenerator) {
        this.sessionId = sessionId;
        this.factory = factory;
        this.idGenerator = idGenerator;
    }

    public String getSessionId() {
        return sessionId;
    }

    public ResearchStateFactory getFactory() {
        return factory;
    }

    public String newApprovalId() {
        return idGenerator == null ? null : idGenerator.newId();
    }
}
