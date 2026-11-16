package com.aresstack.askai.research.runtime.team;

/**
 * The outcome of one TeamAgent turn (greeting or reply). Exactly one of three honest states:
 * <ul>
 *   <li>{@link Status#OK} — the model answered and the answer parsed; {@link #getTurn()} is the structured
 *       intent and {@link #getValidatedCommand()} is its proposed command IFF that command is currently
 *       allowed by the host state machine (else {@code null});</li>
 *   <li>{@link Status#MODEL_UNAVAILABLE} — the main model could not be reached (transport/timeout/HTTP);
 *       the caller shows an honest status and offers a retry, never a fabricated outline;</li>
 *   <li>{@link Status#UNUSABLE_ANSWER} — the model answered but, even after one bounded repair, produced no
 *       parseable turn; again an honest error, never invented content.</li>
 *   <li>{@link Status#COMMAND_REJECTED} — the model insisted on a command the host does not allow in the
 *       current state (even after a bounded repair). The illegal command is dropped AND its potentially
 *       misleading assistant message is withheld, so the model can never claim a step happened that did not.</li>
 * </ul>
 */
public final class TeamAgentResult {

    public enum Status {
        OK,
        MODEL_UNAVAILABLE,
        UNUSABLE_ANSWER,
        COMMAND_REJECTED
    }

    private final Status status;
    private final PhaseAssistantOutput output;
    private final String validatedCommand;
    private final String detail;
    private final int truncatedAtTokens;

    private TeamAgentResult(Status status, PhaseAssistantOutput output, String validatedCommand,
                            String detail) {
        this(status, output, validatedCommand, detail, 0);
    }

    private TeamAgentResult(Status status, PhaseAssistantOutput output, String validatedCommand,
                            String detail, int truncatedAtTokens) {
        this.status = status;
        this.output = output;
        this.validatedCommand = validatedCommand;
        this.detail = detail == null ? "" : detail;
        this.truncatedAtTokens = Math.max(0, truncatedAtTokens);
    }

    public static TeamAgentResult ok(PhaseAssistantOutput output, String validatedCommand) {
        return new TeamAgentResult(Status.OK, output, validatedCommand, "");
    }

    public static TeamAgentResult modelUnavailable(String detail) {
        return new TeamAgentResult(Status.MODEL_UNAVAILABLE, null, null, detail);
    }

    public static TeamAgentResult unusableAnswer(String detail) {
        return new TeamAgentResult(Status.UNUSABLE_ANSWER, null, null, detail);
    }

    /**
     * An {@link Status#UNUSABLE_ANSWER} with a KNOWN cause: the provider cut the answer at the output token
     * limit (finish reason {@code length}), so it could not be read. The user is told exactly that and which
     * setting to raise, instead of the generic "no clear answer" line that invites a pointless retry.
     *
     * @param budgetTokens the output budget the turn was granted (the "Answer budget (tokens)" setting)
     */
    public static TeamAgentResult truncatedAnswer(String detail, int budgetTokens) {
        return new TeamAgentResult(Status.UNUSABLE_ANSWER, null, null, detail, Math.max(1, budgetTokens));
    }

    /** True when the answer was unusable because it ended at the output token limit. */
    public boolean isTruncated() {
        return truncatedAtTokens > 0;
    }

    /** The output budget the truncated turn was granted; 0 when the turn was not truncated. */
    public int getTruncatedAtTokens() {
        return truncatedAtTokens;
    }

    /**
     * The model kept proposing a command the host does not allow here. The {@code detail} is that rejected
     * command name; the turn (and its misleading message) is deliberately withheld.
     */
    public static TeamAgentResult commandRejected(String rejectedCommand) {
        return new TeamAgentResult(Status.COMMAND_REJECTED, null, null, rejectedCommand);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /** The structured output for this turn (a {@link TeamAgentTurn} or a {@link ScopingAssistantOutput}). */
    public PhaseAssistantOutput getOutput() {
        return output;
    }

    /** Back-compat: the generic turn IFF this phase produced one; {@code null} for a phase-specific output. */
    public TeamAgentTurn getTurn() {
        return output instanceof TeamAgentTurn ? (TeamAgentTurn) output : null;
    }

    /** The proposed command, but only when the host currently allows it; {@code null} otherwise. */
    public String getValidatedCommand() {
        return validatedCommand;
    }

    public String getDetail() {
        return detail;
    }
}
