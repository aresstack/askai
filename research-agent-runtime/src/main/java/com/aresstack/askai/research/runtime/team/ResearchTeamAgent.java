package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-session model-backed research conversation engine. It owns ONE session's conversation state — the
 * system prompt, the running user/assistant history and the scope so far — and turns each user message into a
 * validated {@link TeamAgentTurn} by calling the configured main model ({@link MainModelChat}).
 *
 * <p>It is deliberately transport-agnostic and transport-free so the whole conversation can be driven in a unit
 * test with a scripted fake model. The host's two nested state patterns stay the authority in three ways:</p>
 * <ul>
 *   <li><b>Commands.</b> The engine only PROPOSES a command and pre-validates that proposal against the live
 *       allowed set ({@link TeamAgentStateView}). An out-of-set command triggers ONE bounded repair; if the
 *       model insists, the turn is a {@link TeamAgentResult.Status#COMMAND_REJECTED} whose misleading message
 *       is withheld — never run, never invented, never falsely claimed.</li>
 *   <li><b>Scope.</b> The model may only PROPOSE a scope ({@link #getProposedQuestion()} /
 *       {@link #getProposedAspects()}). Only the host promotes a proposal to confirmed via
 *       {@link #applyConfirmedScope(String, List)}; the model is never allowed to mark its own scope as
 *       confirmed.</li>
 *   <li><b>Retries.</b> A user message is held as a single pending turn and only committed to history once the
 *       model answers usably, so {@link #retryPendingTurn(TeamAgentStateView)} after a failure never
 *       duplicates the user's message.</li>
 * </ul>
 *
 * <p>Model or transport failures never fabricate a turn; they surface as
 * {@link TeamAgentResult.Status#MODEL_UNAVAILABLE} or {@link TeamAgentResult.Status#UNUSABLE_ANSWER}.</p>
 *
 * <p>Not thread-safe: one instance per research session, driven from that session's single turn at a time.</p>
 */
public final class ResearchTeamAgent {

    /** A calm, deterministic temperature for a consultative planner (varied queries come from the prompt). */
    private static final double TEMPERATURE = 0.4;
    /**
     * The answer budgets per call, taken from the ROLE of the call (plan 4.1 rule 6, table 4.2): a Concept chat
     * turn including its tool rounds gets 4,096; a chat turn of every later phase 1,536; the post-search review
     * 4,096 in Concept (summary and suggestions) and 3,072 in Sources (summary only), both provisional until the
     * measurement PR. Roles on one slot never share one budget. A JSON answer cut off at its budget fails loud.
     */
    public static final int CONCEPT_TURN_BUDGET = 4096;
    public static final int PHASE_TURN_BUDGET = 1536;
    public static final int CONCEPT_REVIEW_BUDGET = 4096;
    public static final int SOURCES_REVIEW_BUDGET = 3072;

    /** The answer budget of a chat turn (and its tool rounds) in a phase. */
    public static int turnBudget(String phaseId) {
        return isConcept(phaseId) ? CONCEPT_TURN_BUDGET : PHASE_TURN_BUDGET;
    }

    /** The answer budget of the post-search review in a phase. */
    public static int reviewBudget(String phaseId) {
        return isConcept(phaseId) ? CONCEPT_REVIEW_BUDGET : SOURCES_REVIEW_BUDGET;
    }

    private static boolean isConcept(String phaseId) {
        return phaseId == null || phaseId.isEmpty()
                || PhaseAssistantProfileRegistry.SCOPING_PHASE_ID.equalsIgnoreCase(phaseId);
    }

    private final MainModelChat model;
    private final PhaseAssistantProfileRegistry profiles;
    private final PhaseContextAssembler contextAssembler;
    private final List<ChatMessage> history = new ArrayList<ChatMessage>();

    /** Scope the HOST has confirmed (never set by the model). */
    private String confirmedQuestion = "";
    private final List<String> confirmedAspects = new ArrayList<String>();

    /** Scope the MODEL last proposed but that nobody has confirmed yet. */
    private String proposedQuestion = "";
    private final List<String> proposedAspects = new ArrayList<String>();

    private boolean greeted;
    private TeamAgentResult greetingResult;

    /** The user's message for the current turn, held until the model answers usably (retry-safe). */
    private ChatMessage pendingUserTurn;

    public ResearchTeamAgent(MainModelChat model) {
        this(model, PhaseAssistantProfileRegistry.defaults(), new PhaseContextAssembler());
    }

    /** Inject a custom profile registry/assembler — mainly to prove per-phase context selection in tests. */
    public ResearchTeamAgent(MainModelChat model, PhaseAssistantProfileRegistry profiles,
                             PhaseContextAssembler contextAssembler) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (profiles == null || contextAssembler == null) {
            throw new IllegalArgumentException("profiles and contextAssembler must not be null");
        }
        this.model = model;
        this.profiles = profiles;
        this.contextAssembler = contextAssembler;
    }

    public String modelName() {
        return model.modelName();
    }

    public String getProposedQuestion() {
        return proposedQuestion;
    }

    public List<String> getProposedAspects() {
        return new ArrayList<String>(proposedAspects);
    }

    public String getConfirmedQuestion() {
        return confirmedQuestion;
    }

    public List<String> getConfirmedAspects() {
        return new ArrayList<String>(confirmedAspects);
    }

    /**
     * Promote a scope to confirmed. Only the HOST calls this — after the user (via the host state pattern) has
     * approved it. The model is then told this scope is settled, but it can never set it itself.
     */
    public void applyConfirmedScope(String question, List<String> aspects) {
        confirmedQuestion = question == null ? "" : question.trim();
        confirmedAspects.clear();
        if (aspects != null) {
            for (String aspect : aspects) {
                if (aspect != null && !aspect.trim().isEmpty()) {
                    confirmedAspects.add(aspect.trim());
                }
            }
        }
    }

    public boolean hasGreeted() {
        return greeted;
    }

    public boolean hasPendingTurn() {
        return pendingUserTurn != null;
    }

    /**
     * Produce the opening greeting + first scoping question from the model. Truly idempotent within a session:
     * once a greeting has succeeded it is cached and returned as-is, so a second call neither re-asks the model
     * nor writes a second assistant turn. A greeting that FAILED (MODEL_UNAVAILABLE / UNUSABLE_ANSWER) leaves
     * {@link #hasGreeted()} false, so it can be retried.
     */
    public TeamAgentResult greet(TeamAgentStateView state) {
        if (greeted) {
            return greetingResult;
        }
        // The greeting is a phase-agnostic bootstrap: there is no topic yet, so no scoping brief can exist.
        // It therefore uses the neutral fallback profile (generic prompt + generic contract), never the
        // scoping business contract that would demand a research brief.
        PhaseAssistantProfile greeting = profiles.fallback();
        PromptBlockMeter meter = new PromptBlockMeter();
        List<ChatMessage> messages = contextAssembler.assemble(greeting, state, confirmedQuestion,
                confirmedAspects, proposedQuestion, proposedAspects, history, meter);
        append(messages, meter, PromptBlockMeter.GREETING_INSTRUCTION, ChatMessage.user(
                TeamAgentPlaybook.greetingInstruction(contextAssembler.workingLanguageDisplayName())));
        TeamAgentResult result = runTurn(messages, meter, state, greeting.getOutputContract(), null,
                turnBudget(state.getPhaseId()));
        if (result.isOk()) {
            greeted = true;
            greetingResult = result;
            recordAssistant(result.getOutput());
            foldProposal(result.getOutput());
        }
        return result;
    }

    /**
     * Advance the conversation with the user's message. The message becomes the single pending turn and is only
     * committed to history once the model answers usably; a successful answer also folds any scope update into
     * the PROPOSED scope (never the confirmed one).
     */
    public TeamAgentResult respond(String userText, TeamAgentStateView state) {
        pendingUserTurn = ChatMessage.user(userText == null ? "" : userText.trim());
        return runUserTurn(state);
    }

    /**
     * A turn the user triggered by ACTING, not by writing — pressing "review the new sources". It runs the
     * same model and the same output contract, and it reads the real conversation as its context, but the
     * instruction behind it is machinery: it is not appended to the history as something the user said.
     * <p>
     * It used to go through {@link #respond}, which turns its argument into a user message. On success the
     * internal wording ended up in the conversation as if the user had typed it, and every later turn was
     * assembled on top of that fiction.
     */
    public TeamAgentResult internalTurn(String instruction, TeamAgentStateView state) {
        return internalTurn(instruction, state, null);
    }

    /**
     * An internal turn that must answer with ONE action type (a nudge or repair asking for exactly that
     * action): the generation-time schema admits only that variant. {@code null} = an ordinary internal turn.
     */
    public TeamAgentResult internalTurn(String instruction, TeamAgentStateView state, String requiredActionType) {
        return internalTurn(instruction, state, requiredActionType, turnBudget(state.getPhaseId()));
    }

    /** The post-search review: an internal turn with the review's own answer budget (plan 4.2). */
    public TeamAgentResult reviewTurn(String instruction, TeamAgentStateView state) {
        return internalTurn(instruction, state, null, reviewBudget(state.getPhaseId()));
    }

    private TeamAgentResult internalTurn(String instruction, TeamAgentStateView state, String requiredActionType,
                                         int budget) {
        PhaseAssistantProfile profile = profiles.forPhase(state.getPhaseId());
        PromptBlockMeter meter = new PromptBlockMeter();
        List<ChatMessage> messages = baseMessages(state, meter);
        append(messages, meter, PromptBlockMeter.TOOL_FEEDBACK,
                ChatMessage.user(instruction == null ? "" : instruction.trim()));
        TeamAgentResult result = runTurn(messages, meter, state, profile.getOutputContract(), requiredActionType,
                budget);
        if (result.isOk()) {
            // The ANSWER is real conversation — the user sees it, and the next turn must know it exists.
            // Only the instruction stays out.
            recordAssistant(result.getOutput());
            foldProposal(result.getOutput());
        }
        return result;
    }

    /**
     * Re-run the pending user turn after a failure (MODEL_UNAVAILABLE / UNUSABLE_ANSWER / COMMAND_REJECTED)
     * without re-appending the user's message. Requires a pending turn — an OK turn clears it.
     */
    public TeamAgentResult retryPendingTurn(TeamAgentStateView state) {
        if (pendingUserTurn == null) {
            throw new IllegalStateException("no pending user turn to retry");
        }
        return runUserTurn(state);
    }

    // ------------------------------------------------------------------ internals

    private TeamAgentResult runUserTurn(TeamAgentStateView state) {
        PhaseAssistantProfile profile = profiles.forPhase(state.getPhaseId());
        PromptBlockMeter meter = new PromptBlockMeter();
        List<ChatMessage> messages = baseMessages(state, meter);
        append(messages, meter, PromptBlockMeter.USER_MESSAGE, pendingUserTurn);
        TeamAgentResult result = runTurn(messages, meter, state, profile.getOutputContract(), null,
                turnBudget(state.getPhaseId()));
        if (result.isOk()) {
            history.add(pendingUserTurn);
            pendingUserTurn = null;
            recordAssistant(result.getOutput());
            foldProposal(result.getOutput());
        }
        return result;
    }

    /**
     * The per-turn model context, assembled from the ACTIVE phase's assistant profile: the phase decides the
     * system prompt (and, later, its readable/writable artifacts and tools). The active phase is the host's,
     * read from {@code state.getPhaseId()} — the model never chooses it.
     */
    private List<ChatMessage> baseMessages(TeamAgentStateView state, PromptBlockMeter meter) {
        PhaseAssistantProfile profile = profiles.forPhase(state.getPhaseId());
        return contextAssembler.assemble(profile, state, confirmedQuestion, confirmedAspects,
                proposedQuestion, proposedAspects, history, meter);
    }

    private static void append(List<ChatMessage> messages, PromptBlockMeter meter, String block,
                               ChatMessage message) {
        messages.add(message);
        meter.add(block, message);
    }

    /**
     * The full turn pipeline for the ACTIVE phase's contract: get a parseable, validated output (with one
     * bounded parse repair). A non-OK model call is MODEL_UNAVAILABLE; an answer that stays unparseable — or a
     * repaired answer that leaks meta-talk — after one repair is UNUSABLE_ANSWER. None of these fabricate or
     * surface a misleading turn.
     */
    private TeamAgentResult runTurn(List<ChatMessage> messages, PromptBlockMeter meter, TeamAgentStateView state,
                                    PhaseOutputContract contract, String requiredActionType, int budget) {
        Parsed parsed = callParseWithRepair(messages, meter, state.getPhaseId(), contract, requiredActionType,
                budget);
        if (parsed.failure != null) {
            return parsed.failure;
        }
        // The model is an ASSISTANT, not a process controller. A legacy proposedCommand only exists on the
        // generic turn, and is honored only when the host happens to allow it — otherwise silently ignored
        // (never a COMMAND_REJECTED, never a nagging repair). A phase-specific output carries no command.
        PhaseAssistantOutput output = parsed.output;
        String validatedCommand = null;
        if (output instanceof TeamAgentTurn) {
            TeamAgentTurn turn = (TeamAgentTurn) output;
            if (turn.hasProposedCommand() && state.allows(turn.getProposedCommand())) {
                validatedCommand = turn.getProposedCommand();
            }
        }
        return TeamAgentResult.ok(output, validatedCommand);
    }

    /**
     * ONE model call, schema-constrained when the contract provides a generation-time schema
     * (Ollama structured outputs) — grammar stops a small model from losing count of its braces;
     * without a schema the long-standing plain completion stays byte-identical.
     */
    private MainModelChatResult callModel(List<ChatMessage> messages, PromptBlockMeter meter, String phase,
                                          PhaseOutputContract contract, String requiredActionType, int budget) {
        String schema = requiredActionType == null ? contract.outputSchemaJson()
                : contract.outputSchemaJson(requiredActionType);
        MainModelChatResult result = schema == null
                ? model.complete(messages, TEMPERATURE, budget)
                : model.completeJson(messages, TEMPERATURE, budget, schema);
        ModelCallLog.log(phase, meter, result);
        return result;
    }

    /** One model call + phase-contract parse, with EXACTLY ONE bounded parse-repair on failure. */
    private Parsed callParseWithRepair(List<ChatMessage> messages, PromptBlockMeter meter, String phase,
                                       PhaseOutputContract contract, String requiredActionType, int budget) {
        MainModelChatResult call = callModel(messages, meter, phase, contract, requiredActionType, budget);
        if (!call.isOk()) {
            return Parsed.fail(TeamAgentResult.modelUnavailable(call.getDetail()));
        }
        PhaseParseResult parsed = contract.parse(call.getText());
        if (parsed.isOk()) {
            return Parsed.ok(parsed.getOutput(), call.getText());
        }
        traceParseFailure("first", parsed.getError(), call.getText());
        List<ChatMessage> repair = new ArrayList<ChatMessage>(messages);
        PromptBlockMeter repairMeter = meter.copy();
        append(repair, repairMeter, PromptBlockMeter.REPAIR_NOTE, ChatMessage.assistant(call.getText()));
        append(repair, repairMeter, PromptBlockMeter.REPAIR_NOTE, ChatMessage.user(TeamAgentPlaybook.repairNudge()));
        return callParseOnce(repair, repairMeter, phase, contract, requiredActionType, budget);
    }

    /**
     * Make an UNUSABLE_ANSWER DIAGNOSABLE. The user only ever sees one honest line, which says nothing about
     * WHY the turn failed — so a contract that keeps rejecting simple inputs can only be fixed by guessing,
     * and guessing means weakening the contract until something passes. This records the concrete parse error
     * together with the beginning of the model's actual answer, on STDERR like every other runtime log.
     */
    private static void traceParseFailure(String attempt, String error, String raw) {
        System.err.println("[team-agent] parse failed (" + attempt + "): " + error
                + " | answer=" + excerptOf(raw));
    }

    /** Enough of the answer to recognise its shape (fences, prose, truncation), never the whole turn. */
    private static final int PARSE_FAILURE_EXCERPT_CHARS = 600;

    static String excerptOf(String raw) {
        if (raw == null) {
            return "<none>";
        }
        String flat = raw.replace('\n', '⏎');
        if (flat.length() <= PARSE_FAILURE_EXCERPT_CHARS) {
            return flat;
        }
        // Head AND tail: an output-budget truncation is only visible at the END of the answer (the head
        // of a cut-off JSON looks perfectly healthy) — a head-only excerpt made that failure mode
        // undiagnosable from the trace.
        int half = PARSE_FAILURE_EXCERPT_CHARS / 2;
        return flat.substring(0, half) + " …[" + flat.length() + " chars]… "
                + flat.substring(flat.length() - half);
    }

    /**
     * One model call + parse, with NO further repair (used for the second, bounded repair attempt). A repair
     * is pure infrastructure: if the model uses this turn to apologize or talk about formatting/JSON, that
     * message must never reach the user. So a repaired turn whose visible message leaks codec meta-talk is
     * failed as UNUSABLE_ANSWER — the user then sees the fixed, meta-free typed line, and the pending user
     * turn stays intact for a clean retry.
     */
    private Parsed callParseOnce(List<ChatMessage> messages, PromptBlockMeter meter, String phase,
                                 PhaseOutputContract contract, String requiredActionType, int budget) {
        MainModelChatResult call = callModel(messages, meter, phase, contract, requiredActionType, budget);
        if (!call.isOk()) {
            return Parsed.fail(TeamAgentResult.modelUnavailable(call.getDetail()));
        }
        PhaseParseResult parsed = contract.parse(call.getText());
        if (!parsed.isOk()) {
            traceParseFailure("repair", parsed.getError(), call.getText());
            return Parsed.fail(TeamAgentResult.unusableAnswer(parsed.getError()));
        }
        if (!VisibleAssistantMessageValidator.isCleanBusinessMessage(parsed.getOutput().getAssistantMessage())) {
            traceParseFailure("repair-meta-talk", "visible message is not a business message",
                    parsed.getOutput().getAssistantMessage());
            return Parsed.fail(TeamAgentResult.unusableAnswer("repair produced a non-business message"));
        }
        return Parsed.ok(parsed.getOutput(), call.getText());
    }

    /**
     * Record the assistant turn CANONICALLY so CONTEXT ACCUMULATES across short replies without inventing a
     * third representation: history holds exactly the structured turn the model emitted, serialized by
     * {@link TeamAgentTurnCodec} and re-readable by {@link TeamAgentTurnParser}. The user still only ever sees
     * {@code assistantMessage} (that is what the wire carries); the model gets its own understood/suggested/
     * open facts back verbatim, so a following one-word reply has something to build on.
     */
    private void recordAssistant(PhaseAssistantOutput output) {
        history.add(ChatMessage.assistant(output.canonicalJson()));
    }

    /**
     * Fold a scope update from the model into the PROPOSED scope (last statement wins for the question). Only
     * the generic turn carries a proposed question/aspects; a phase-specific output (e.g. the scoping brief)
     * has no such fields, so nothing is folded for it.
     */
    private void foldProposal(PhaseAssistantOutput output) {
        if (!(output instanceof TeamAgentTurn)) {
            return;
        }
        TeamAgentTurn turn = (TeamAgentTurn) output;
        if (turn.getQuestion() != null) {
            proposedQuestion = turn.getQuestion();
        }
        if (!turn.getAspects().isEmpty()) {
            proposedAspects.clear();
            proposedAspects.addAll(turn.getAspects());
        }
    }

    /** A parsed phase output (with its raw text, for repair context) OR a typed failure — never both. */
    private static final class Parsed {
        final TeamAgentResult failure;
        final PhaseAssistantOutput output;
        final String raw;

        private Parsed(TeamAgentResult failure, PhaseAssistantOutput output, String raw) {
            this.failure = failure;
            this.output = output;
            this.raw = raw;
        }

        static Parsed fail(TeamAgentResult failure) {
            return new Parsed(failure, null, null);
        }

        static Parsed ok(PhaseAssistantOutput output, String raw) {
            return new Parsed(null, output, raw);
        }
    }
}
