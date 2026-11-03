package com.aresstack.askai.research.runtime.service;

/**
 * A typed host→runtime SERVICE COMMAND, parsed from the {@code #RSC1#} control envelope. A service command is
 * carried over the prompt channel (the only host→agent channel) but is NOT a chat turn: it is dispatched
 * before any model / TeamAgent / state logic and reaches a plain application service (e.g. the productive
 * search backend). It never transitions the host state machine and is phase-independent.
 */
public final class ResearchServiceCommand {

    /** A user-triggered web search: run the productive SearchStrategy and stream typed result events back. */
    public static final String TYPE_MANUAL_SEARCH = "manual_search";

    /**
     * A live working-language switch: only updates the runtime's {@link SessionResearchLanguage} — no model
     * call, no history entry, no state-machine command, no workflow event.
     */
    public static final String TYPE_SET_LANGUAGE = "set_language";

    /**
     * The EXPLICIT post-search review (issue #29): the user pressed "Neue Quellen auswerten". The TeamAgent
     * reviews the accepted sources; bracketed by manual_search_review started/finished on this request id.
     */
    public static final String TYPE_REVIEW_SOURCES = "review_sources";

    /**
     * The AUTHORITATIVE scope projection for the next turn: the host owns the research scope, the model
     * reads it as context. Without it the model would reconstruct the scope from the chat history, which is
     * exactly how earlier decisions get lost.
     */
    public static final String TYPE_SET_SCOPE = "set_scope";

    /**
     * Z3b-3: generate scope probes on the central main model — ONE model call, typed result back
     * over the run wire. Carried like every service command (never a chat turn, no state change);
     * the host owns the canonical scope and does ALL geometry — this command only produces material.
     */
    public static final String TYPE_GENERATE_PROBES = "generate_probes";

    /**
     * Z4b: choose at most ONE of the host-prepared advice candidates on the central main model —
     * one model call, typed decision back over the run wire. Like generate_probes this produces
     * MATERIAL only: no scope patch, no workflow action, no state change.
     */
    public static final String TYPE_CHOOSE_ADVICE = "choose_advice";

    /**
     * ADR 0001, lane F: the user starts an orchestrated research run for {@code query} (the orchestrator
     * plans; nothing runs until the user approves the plan).
     */
    public static final String TYPE_ORCHESTRATOR_START = "orchestrator_start";

    /**
     * The user approves: the proposed plan, the pending follow-ups or overrun, or the resumption of an
     * interrupted run. The user's decision, never the orchestrator's.
     */
    public static final String TYPE_ORCHESTRATOR_APPROVE = "orchestrator_approve";

    /** The user rejects the plan or stops the run; open tasks are canceled. */
    public static final String TYPE_ORCHESTRATOR_STOP = "orchestrator_stop";

    /** The user answers the orchestrator's question ({@code query} carries the answer). */
    public static final String TYPE_ORCHESTRATOR_ANSWER = "orchestrator_answer";

    private final String type;
    private final String requestId;
    private final String query;
    private final String language;
    private final String scope;
    /**
     * For a review: the newest capture timestamp the review is about. It bounds WHICH sources this turn
     * reads, so what the model saw and what the host marks reviewed are the same set.
     */
    private final long capturedThrough;
    /** The JSON payload of a {@code generate_probes} command; empty otherwise. */
    private final String request;

    public ResearchServiceCommand(String type, String requestId, String query) {
        this(type, requestId, query, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language) {
        this(type, requestId, query, language, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope) {
        this(type, requestId, query, language, scope, 0L);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope, long capturedThrough) {
        this(type, requestId, query, language, scope, capturedThrough, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope, long capturedThrough, String request) {
        this.capturedThrough = capturedThrough;
        this.request = request == null ? "" : request;
        this.type = type == null ? "" : type;
        this.requestId = requestId == null ? "" : requestId;
        this.query = query == null ? "" : query;
        this.language = language == null ? "" : language;
        this.scope = scope == null ? "" : scope;
    }

    public String getType() {
        return type;
    }

    /** Correlates the command with its typed result/progress/failure events and with a later cancel. */
    public String getRequestId() {
        return requestId;
    }

    public String getQuery() {
        return query;
    }

    /** The language code ("en"/"de") of a {@code set_language} command; empty when absent. */
    public String getLanguage() {
        return language;
    }

    /** The rendered scope projection of a {@code set_scope} command; empty when absent. */
    public long getCapturedThrough() {
        return capturedThrough;
    }

    public String getScope() {
        return scope;
    }

    /** The JSON payload of a {@code generate_probes} command; empty when absent. */
    public String getRequest() {
        return request;
    }
}
