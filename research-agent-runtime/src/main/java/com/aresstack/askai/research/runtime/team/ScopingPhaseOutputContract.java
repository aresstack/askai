package com.aresstack.askai.research.runtime.team;

/**
 * The SCOPING phase contract: {@link ScopingAssistantOutputParser} producing a
 * {@link ScopingAssistantOutput}. (Historical note: the RA-P6.5 USEFUL-FIRST-TURN rejection —
 * brief-only first turns failing the parse — is long gone; only a non-blank assistantMessage is
 * required, and the first-turn HELP pressure lives in the prompt plus the gate-9b offer nudge.)
 *
 * <p>With the concept tools active the contract additionally publishes a GENERATION-TIME schema
 * (Ollama structured outputs): the K2c gate showed gemma emitting well-intentioned turns (good
 * conceptActions!) inside brace-broken JSON — a grammar stops a small model from losing count
 * where a repair prompt cannot. Without the concept tools the contract stays schema-free, so the
 * behaviour against an older host is unchanged.</p>
 */
public final class ScopingPhaseOutputContract implements PhaseOutputContract {

    private final boolean conceptTools;

    public ScopingPhaseOutputContract() {
        this(false);
    }

    public ScopingPhaseOutputContract(boolean conceptTools) {
        this.conceptTools = conceptTools;
    }

    public PhaseParseResult parse(String rawModelText) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(rawModelText);
        return result.isOk()
                ? PhaseParseResult.ok(result.getOutput())
                : PhaseParseResult.fail(result.getError());
    }

    /**
     * The scoping answer's shape as a grammar. Deliberate choices: {@code assistantMessage} and
     * {@code conceptAction} are REQUIRED (the action decision is always explicit — type "none"
     * says "I change nothing"); the concept action types are an enum; list sizes carry maxItems
     * (the Weidezaun lesson: a model that loses count is stopped by the grammar, not by prose);
     * scope operations pin {@code kind} to the known enum AND require a {@code facetId} in the
     * TECHNICAL-ID shape (live-gate 2: missing ids starved the Weidezaun; live-gate 3: minLength
     * alone let a 100-char prompt placeholder become a canonical facet — the pattern blocks
     * commas, colons, braces and sentences at generation time, and the runtime re-checks it for
     * engines that ignore {@code pattern}). The grammar is FLAT on purpose (the K2c lesson: no
     * oneOf for small models), so {@code facetId} is demanded on EVERY operation — on non-facet
     * kinds the codec simply ignores the extra field, which is far cheaper than a branched
     * grammar. Advisory suggestions require NON-EMPTY label+query — the first live gate saw
     * {@code "label":""} slip past a presence-only schema. Per-kind REQUIRED fields (addExclusion
     * needs value, setMission needs mission, …) cannot be expressed flatly — that stays the
     * runtime validator's job, with the field reference travelling in the repair feedback.
     */
    @Override
    public String outputSchemaJson() {
        if (!conceptTools) {
            return null;
        }
        return "{\"type\":\"object\",\"properties\":{"
                // Scoping regression (Meerschweinchen live gate): Ollama's grammar emits the
                // REQUIRED properties first, in schema order. With assistantMessage first the
                // small model narrated its action in the visible text ("add_cards: …") and then
                // answered conceptAction "none". The action now comes FIRST: the model decides
                // the step in the structured channel, then writes the sentence for the user.
                // Safety slice after the slice-2 gate: remove/rewrite left the grammar — a
                // small model must not be able to EMIT a destructive concept edit (the parser
                // stays tolerant for legacy transcripts; the loop refuses execution).
                // add_cards slice: the single add left the ACTIVE contract (the live gate lost
                // a user-named card to the four-round budget of one-by-one adds) — the model
                // captures ALL named areas as ONE typed list; the parser stays tolerant of
                // legacy "add" transcripts, the loop refuses their execution.
                + "\"conceptAction\":{\"type\":\"object\",\"properties\":{"
                // The dedicated move generator owns the model side of moves EXCLUSIVELY
                // (gate ruling): the universal enum advertises no move, so no starving
                // invalid rounds compete with the pre-executed dedicated path.
                + "\"type\":{\"type\":\"string\",\"enum\":[\"none\",\"read\",\"add_cards\","
                + "\"exclude\",\"resolve\",\"offer\",\"rename\",\"probe\"]},"
                + "\"path\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"string\"}},"
                + "\"parent\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"string\"}},"
                + "\"name\":{\"type\":\"string\"},"
                + "\"names\":{\"type\":\"array\",\"maxItems\":16,\"items\":"
                + "{\"type\":\"string\",\"minLength\":1}},"
                // scope_probe (AP3): ONE list of terms — no ids, no thresholds, no cosines;
                // the host owns the complexity, the grammar bounds the list.
                + "\"terms\":{\"type\":\"array\",\"maxItems\":12,\"items\":"
                + "{\"type\":\"string\",\"minLength\":1}},"

                + "\"topic\":{\"type\":\"string\"},"
                + "\"conflictId\":{\"type\":\"string\"},"
                + "\"decision\":{\"type\":\"string\",\"enum\":[\"REMOVE\","
                + "\"KEEP_SUPPRESSED\"]},"
                + "\"suggestions\":{\"type\":\"array\",\"maxItems\":5,\"items\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "\"query\":{\"type\":\"string\",\"minLength\":1},"
                + "\"purpose\":{\"type\":\"string\"}},"
                + "\"required\":[\"query\"]}}},"
                + "\"required\":[\"type\"]},"
                + "\"assistantMessage\":{\"type\":\"string\"},"
                // K4: the concept is the ONE scoping artifact — the legacy brief field left the
                // grammar entirely (this schema only exists WITH the concept tools).
                + "\"searchSuggestions\":{\"type\":\"array\",\"maxItems\":5,\"items\":"
                + "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\","
                + "\"minLength\":1},"
                + "\"purpose\":{\"type\":\"string\"},\"priority\":{\"type\":\"integer\"}},"
                + "\"required\":[\"query\"]}},"
                + "\"advice\":{\"type\":\"object\",\"properties\":{"
                + "\"recommendation\":{\"type\":\"string\",\"enum\":[\"STAY\",\"CONTINUE\","
                + "\"NEUTRAL\"]},\"reason\":{\"type\":\"string\"}},"
                + "\"required\":[\"recommendation\"]},"
                + "\"scopePatch\":{\"type\":\"object\",\"properties\":{"
                + "\"operations\":{\"type\":\"array\",\"maxItems\":8,\"items\":"
                // Zielbild slice 1: the concept (mindmap) IS the positive working space —
                // addFacet/confirmFacet left the model contract (no more facet duplication of
                // card names), and excludeFacet has its own one-command action. The runtime
                // validator still accepts them for host paths and old transcripts.
                // Enum AUDIT (add_cards gate): the flat grammar can only carry kind+facetId+
                // label, so every kind whose REQUIRED fields exceed that (the {value} family,
                // setCrossCuttingEmphasis{dimension}, the issue ops) was an advertised trap —
                // the model kept emitting them, the runtime validator kept rejecting them
                // ("addExclusion/addConstraint without 'value'" noise). Only the two
                // flat-expressible kinds remain emittable; the validator still ACCEPTS the
                // full set for host paths and legacy transcripts.
                + "{\"type\":\"object\",\"properties\":{\"kind\":{\"type\":\"string\","
                + "\"enum\":[\"setFacetEmphasis\",\"setDeliverable\"]},"
                + "\"facetId\":{\"type\":\"string\",\"minLength\":1,"
                + "\"pattern\":\"^[a-z0-9][a-z0-9_-]{0,63}$\"},"
                + "\"label\":{\"type\":\"string\",\"minLength\":1}},"
                + "\"required\":[\"kind\",\"facetId\"]}}}},"
                + "\"unresolvedIssues\":{\"type\":\"array\",\"maxItems\":6,\"items\":"
                + "{\"type\":\"object\"}},"
                + "\"orientationSuggestions\":{\"type\":\"array\",\"maxItems\":3,\"items\":"
                + "{\"type\":\"object\",\"properties\":{"
                + "\"label\":{\"type\":\"string\",\"minLength\":1},"
                + "\"query\":{\"type\":\"string\",\"minLength\":1},"
                + "\"rationale\":{\"type\":\"string\"}},"
                + "\"required\":[\"label\",\"query\"]}}"
                + "},\"required\":[\"conceptAction\",\"assistantMessage\"]}";
    }
}
