package com.aresstack.askai.research.mcp;

import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.sources.ResearchSourceRepository;

/**
 * What the research-control tool handlers operate on. Crucially, {@link #currentPhaseId()} and
 * {@link #currentStateId()} are read AT EXECUTION TIME — every handler re-checks authorization against the
 * live state, because a transition may have happened between tools/list and tools/call. Tool visibility
 * (computed by {@link ResearchToolPolicy}) and tool authorization (checked here) are deliberately separate.
 */
public interface ResearchControlContext {

    String currentPhaseId();

    String currentStateId();

    /** Human-readable status line for research_status (phase / run state / revision / pending approval). */
    String statusLine();

    AgentArtifactStore artifactStore();

    ResearchSourceRepository sourceRepository();

    /**
     * Promote a visited capture to an accepted source (the only path that creates a persistent source —
     * productively backed by the Commit-37 {@code SourceAcceptanceService}).
     * @return the compact acceptance result line (status/source_id/title/passage_count/duplicate,
     *         e.g. {@code status=ACCEPTED source_id=source-1 title="t" passage_count=1 duplicate=false}),
     *         or {@code null} when the capture id is unknown.
     */
    String acceptCapture(String captureId);

    /**
     * As {@link #acceptCapture(String)} but records the USER web-search query that found the capture — used by
     * the internal {@code manual_source_accept} endpoint so "what was already searched" survives a restart.
     * The default ignores the query (agent path / test fakes); the productive context overrides it.
     */
    default String acceptCapture(String captureId, String searchQuery) {
        return acceptCapture(captureId);
    }

    /**
     * As {@link #acceptCapture(String, String)} but also records whether the USER marked the page relevant (the
     * HUD ⭐ toggle). Used by {@code manual_source_accept}. The default ignores the flag (agent path / test
     * fakes); the productive context overrides it.
     */
    default String acceptCapture(String captureId, String searchQuery, boolean userRelevant) {
        return acceptCapture(captureId, searchQuery);
    }

    /**
     * As {@link #acceptCapture(String, String, boolean)} but with the search's language snapshot ("en"/"de";
     * empty = none), stamped onto the knowledge-processing job. The default ignores it (agent path / fakes).
     */
    default String acceptCapture(String captureId, String searchQuery, boolean userRelevant,
                                 String languageCode) {
        return acceptCapture(captureId, searchQuery, userRelevant);
    }

    /**
     * As above but with the manual-search REQUEST id that found the capture — persisted so a review
     * can be scoped to exactly one search. The default ignores it (agent path / fakes).
     */
    default String acceptCapture(String captureId, String searchQuery, boolean userRelevant,
                                 String languageCode, String searchRequestId) {
        return acceptCapture(captureId, searchQuery, userRelevant, languageCode);
    }

    /**
     * Park a reranked search candidate as a scored source BEFORE the page is visited (empty full text, status
     * PARKED). Backed by the same {@code SourceAcceptanceService} as acceptance. The default is a no-op
     * (agent path / test fakes); the productive context overrides it.
     * @return a compact park result line ({@code status=PARKED|ALREADY_PRESENT source_id=…}), or {@code null}.
     */
    default String parkCandidate(String url, String title, String excerpt, double rerankScore,
                                 String searchQuery) {
        return null;
    }

    /** As above but with the manual-search REQUEST id. The default delegates (agent path / fakes). */
    default String parkCandidate(String url, String title, String excerpt, double rerankScore,
                                 String searchQuery, String searchRequestId) {
        return parkCandidate(url, title, excerpt, rerankScore, searchQuery);
    }

    /**
     * How many sources one {@code source_review_context} answer may carry. The default is the documented
     * settings fallback; the productive context serves the USER's configured value — a review bound is
     * configuration, never only a code constant.
     */
    default int reviewContextMaximumSources() {
        return com.aresstack.askai.research.host.ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_SOURCES;
    }

    /** How much of each source's text the review context carries (characters). Same contract as above. */
    default int reviewContextMaximumCharactersPerSource() {
        return com.aresstack.askai.research.host.ResearchRuntimeSettings.DEFAULT_REVIEW_MAX_CHARS;
    }

    /**
     * The session's Konzeptpapier branch-edit service, or {@code null} when the capability is
     * honestly unavailable (clickdummy / old fakes) — then no concept tool is offered at all.
     * One instance per session: MCP tools and (later) UI must share the same handle registry.
     */
    default com.aresstack.askai.research.concept.ConceptBranchService conceptBranchService() {
        return null;
    }

    /**
     * Called after a concept edit COMMITTED (new working revision) so the host can refresh views.
     * The default is a no-op (fakes); the productive context notifies the session.
     */
    default void onConceptChanged(long newWorkingRevision) {
    }

    /**
     * The ONE-command exclusion facade (live-gate 4 decision): record a user exclusion by the
     * user's OWN words. The host derives the id, creates the EXCLUDED facet, republishes the
     * fence and checks the concept for an EXACT name conflict — the structured JSON reply may
     * carry a {@code conceptConflict} + {@code requiredResponse=INFORM_AND_ASK_REMOVE}. Returns
     * {@code null} when this session has no scope system (fakes/clickdummy).
     * DELEGATING WRAPPERS MUST FORWARD THIS (the documented default-method trap).
     */
    default String excludeTopic(String topic) {
        return null;
    }

    /**
     * Resolve a previously reported concept conflict: {@code REMOVE} deletes the conflicting
     * concept node (the host knows the path by the opaque conflictId — the model never
     * reconstructs it), {@code KEEP_SUPPRESSED} keeps the concept entry while research stays
     * suppressed. Returns {@code null} when unsupported.
     */
    default String resolveConceptConflict(String conflictId, String decision) {
        return null;
    }

    /**
     * The search-offer command: the model hands over 3-5 AI-authored orientation searches, the
     * host renders them as the user's yellow exploration tags. Returns {@code null} when this
     * session has no scoping surface. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String offerSearches(String suggestionsJson) {
        return null;
    }

    /**
     * The current blacklist terms (excluded facet labels/ids + plain exclusions) — the concept
     * tools use them for the Zielbild auto-cleanup: a branch READ names suppressed cards, a
     * REWRITE that still contains one is rejected. Empty without a scope system.
     * DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default java.util.List<String> blacklistedTerms() {
        return java.util.Collections.emptyList();
    }

    /**
     * One technical-log line from a concept tool handler (e.g. the read's SUPPRESSED hint) — the
     * gate demands the suppression truth in the OBSERVABLE log, not only in the model-facing
     * tool result. The default is a no-op (fakes). DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default void conceptToolLog(String line) {
    }

    /**
     * scope_probe (AP3): read-only semantic measurement of terms against the negotiated fence.
     * Returns {@code null} when this session has no scope system.
     * DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String scopeProbe(java.util.List<String> terms) {
        return null;
    }

    /**
     * SC1 search-scope control (internal service lane, never an agent tool).
     * DELEGATING WRAPPERS MUST FORWARD THESE.
     */
    default String searchScopeBegin() {
        return null;
    }

    default String searchScopeEvaluate(String handle, String lane, String itemsJson) {
        return null;
    }

    default String searchScopeEnd(String handle) {
        return null;
    }

    /**
     * The read-only summary of the effective frozen concept (plan PR 16), or {@code null} before the first
     * committed {@code SUBMIT_SCOPE} / without a book record. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String frozenConceptSummary() {
        return null;
    }

    /**
     * The latest change request of the CURRENT phase until its next Finish (plan PR 16), {@code ""} when none.
     * DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String latestChangeRequest() {
        return "";
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
     * Plan PR 15: the Sources block (search plan, coverage per node), {@code null} without a plan.
     * DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String searchPlanOverview() {
        return null;
    }

    /**
     * The Outline block (plan PR 21): ids, titles and coverage per chapter of the stored working outline, or
     * {@code null} when this session has no outline store. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String outlineRead() {
        return null;
    }

    /**
     * Plan PR 24: regenerate one chapter of the working outline ({@code sections} 0 = brief again, 2 to 6 =
     * re-split a sub-cluster chapter; the comment steers labels and briefs). Returns the outcome line, or
     * {@code null} when this session cannot regenerate chapters. DELEGATING WRAPPERS MUST FORWARD THIS.
     */
    default String outlineRegenerateChapter(String chapterId, int sections, String comment) {
        return null;
    }
}
