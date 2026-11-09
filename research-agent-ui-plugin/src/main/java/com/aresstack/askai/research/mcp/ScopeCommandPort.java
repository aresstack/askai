package com.aresstack.askai.research.mcp;

/**
 * The session-side implementation of the ONE-command exclusion facade (live-gate 4 decision).
 * The session owns the scope coordinator, the concept service and the conflict registry; the
 * MCP tool handlers reach it through this port (wired like the bot gateway:
 * {@code resources.setScopeCommandPort(...)}), never through a second scope instance.
 */
public interface ScopeCommandPort {

    /**
     * @return the structured JSON reply (EXCLUDED + optional conceptConflict), a plain-text
     *         teaching error, or {@code null} when this session has no scope system at all
     */
    String excludeTopic(String topic);

    /** @return the structured JSON reply (REMOVED / KEPT_SUPPRESSED) or a plain-text error */
    String resolveConceptConflict(String conflictId, String decision);

    /**
     * Offer 3-5 AI-authored orientation searches as the user's yellow exploration tags (gate 8b:
     * the optional in-band field starved under the generation grammar — as a command the model
     * actually offers). {@code suggestionsJson} = {@code [{"query":..,"purpose":..},..]}.
     * @return the structured JSON reply (OFFERED + count) or a plain-text error
     */
    String offerSearches(String suggestionsJson);

    /** The current blacklist terms (excluded labels/ids + exclusions), for the concept tools. */
    java.util.List<String> blacklistedTerms();

    /** One technical-log line from a concept tool handler. Default no-op (fakes). */
    default void conceptToolLog(String line) {
    }

    /**
     * scope_probe (AP3): the READ-ONLY semantic sensor — where do the given terms sit relative
     * to the negotiated fence? Decides nothing, mutates nothing. Returns the receipt text, or
     * {@code null} when this session has no scope system.
     */
    default String scopeProbe(java.util.List<String> terms) {
        return null;
    }

    /** SC1: pin one immutable search-scope snapshot (ACTIVE/INACTIVE/UNAVAILABLE reply). */
    default String searchScopeBegin() {
        return null;
    }

    /** SC1: judge one candidate batch against a pinned snapshot. */
    default String searchScopeEvaluate(String handle, String lane, String itemsJson) {
        return null;
    }

    /** SC1: release a pinned snapshot (best effort). */
    default String searchScopeEnd(String handle) {
        return null;
    }

    /** The read-only frozen concept summary (plan PR 16), or {@code null} when there is none. */
    default String frozenConceptSummary() {
        return null;
    }

    /**
     * Plan PR 15: the Sources assistant's {@code request_research} adds one plan entry for a concept node (id or
     * label; blank = a free entry). Returns {@code null} when unsupported. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String requestResearch(String query, String node) {
        return null;
    }

    /**
     * Plan PR 15: the Sources assistant's {@code blacklist_add} on the add-only path of PR 07 (an entry after
     * {@code SUBMIT_SCOPE}; a term also becomes an excluded facet for the semantic gate, like
     * {@code exclude_topic}). Returns {@code null} when unsupported. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String blacklistAdd(String entry) {
        return null;
    }

    /**
     * Plan PR 29: the Document assistant's {@code section_rewrite} reruns the section writer for one section (id or
     * title) with the user's comment. Returns {@code null} when unsupported. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String sectionRewrite(String section, String comment) {
        return null;
    }

    /**
     * Plan PR 15: the Sources block (search plan, coverage per node), {@code null} without a plan.
     * DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String searchPlanOverview() {
        return null;
    }
}
