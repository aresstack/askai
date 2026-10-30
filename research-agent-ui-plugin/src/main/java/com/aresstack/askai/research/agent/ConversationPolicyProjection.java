package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.scope.ScopeCheckReport;

/**
 * The RATIFIED conversation policy (Phase-1 Bauplan): an EPHEMERAL, stateless projection —
 * signals in, ONE stage + host-guidance hint out, recomputed per user turn, never persisted,
 * never a second state machine. Stages derive from the INTERPLAY of growth/novelty, OPEN
 * boundary material and the current scope check — never from anchor counts, never from a
 * magic card number (counts only mark "still sparse").
 *
 * <p>Ratified guards baked in: {@code READY} means a TRUSTWORTHY sweep, never "scope
 * complete"; {@code NOTHING_TO_ASK} never means "done" — CLOSURE_CHECK only says "no strong
 * boundary question is known right now" and leaves the decision with the user.
 * {@code CALIBRATION_WEAK} is not a weaker READY: it never supports closure and only adds a
 * conservative note. A stale report (its scope revision no longer current) is IGNORED.
 * Novelty counts NEW positive concept-node IDs per user turn — renames and moves mint no ids
 * and are correctly no novelty.</p>
 */
public final class ConversationPolicyProjection {

    public enum Stage { OPEN_EXPLORATION, GUIDED_EXPLORATION, BOUNDARY_PROBING, CLOSURE_CHECK }

    /** The typed signal snapshot one turn is judged on — assembled by the session, pure here. */
    public static final class Inputs {
        final boolean missionPresent;
        final int positiveNodeCount;
        final int turnsWithoutNewCards;
        /**
         * OPEN boundary material: open/candidate conflicts, drift guards, or a currently
         * pending boundary question (ASKED). Deliberately NOT settled exclusions alone —
         * those are finished decisions; treating them as pending work would trap the
         * conversation in BOUNDARY_PROBING forever and make CLOSURE_CHECK unreachable.
         */
        final boolean openBoundaryWork;
        final boolean openConflict;
        /** Whether the latest report's scope revision still matches the current draft. */
        final boolean scopeCheckCurrent;
        final ScopeCheckReport.Kind scopeCheckKind;
        final boolean calibrationWeak;
        final int driftGuardCount;
        final int sparseCardThreshold;
        final int noveltySaturationTurns;

        public Inputs(boolean missionPresent, int positiveNodeCount, int turnsWithoutNewCards,
                      boolean openBoundaryWork, boolean openConflict, boolean scopeCheckCurrent,
                      ScopeCheckReport.Kind scopeCheckKind, boolean calibrationWeak,
                      int driftGuardCount, int sparseCardThreshold, int noveltySaturationTurns) {
            this.missionPresent = missionPresent;
            this.positiveNodeCount = positiveNodeCount;
            this.turnsWithoutNewCards = turnsWithoutNewCards;
            this.openBoundaryWork = openBoundaryWork;
            this.openConflict = openConflict;
            this.scopeCheckCurrent = scopeCheckCurrent;
            this.scopeCheckKind = scopeCheckKind;
            this.calibrationWeak = calibrationWeak;
            this.driftGuardCount = driftGuardCount;
            this.sparseCardThreshold = sparseCardThreshold;
            this.noveltySaturationTurns = noveltySaturationTurns;
        }
    }

    private ConversationPolicyProjection() {
    }

    /** The ratified priority rules — a pure function, recomputed every turn. */
    public static Stage stageOf(Inputs in) {
        boolean sparse = in.positiveNodeCount < in.sparseCardThreshold || !in.missionPresent;
        boolean saturated = in.turnsWithoutNewCards >= in.noveltySaturationTurns;
        if (sparse) {
            // An exclusion in the very first sentence must NOT narrow the dialogue — the
            // sparse field is explored first, boundary talk comes when substance exists.
            return Stage.OPEN_EXPLORATION;
        }
        if (!saturated) {
            return Stage.GUIDED_EXPLORATION;
        }
        if (in.openBoundaryWork) {
            return Stage.BOUNDARY_PROBING;
        }
        if (in.scopeCheckCurrent && in.scopeCheckKind == ScopeCheckReport.Kind.NOTHING_TO_ASK
                && !in.openConflict) {
            return Stage.CLOSURE_CHECK;
        }
        return Stage.GUIDED_EXPLORATION;
    }

    /**
     * The host's refusal of a {@code scope_probe} outside the closure check — machinery, not
     * prompt text: the PR #19 live acceptance saw gemma4:e2b probe in the FIRST turn of both
     * topics although the prompt restricts the probe to CLOSURE_CHECK, and the sensor lock then
     * turned the whole opening answer into a probe summary. The text starts with
     * {@code PROBE_REFUSED} so the tool reports it as a rejection (the loop books a REJECTED
     * round and hands the text back to the model), and it names the stage so the model learns
     * WHY and answers the user's request instead of retrying.
     */
    public static String probeRefusal(Stage stage) {
        return "PROBE_REFUSED: scope_probe is the closure-check sensor and is available only while "
                + "the CONVERSATION POLICY stage is CLOSURE_CHECK — the stage is " + stage.name()
                + ". Do not probe now and do not retry it in this turn: answer the user's request "
                + "directly (cards, offer, exclusion or a plain reply).";
    }

    /** The delimited host-guidance block the fence carries (ratified header + hard lines). */
    public static String hintBlock(Stage stage, boolean calibrationWeak) {
        StringBuilder block = new StringBuilder("\nCONVERSATION POLICY\n"
                + "This is host guidance for HOW to conduct the next turn.\n"
                + "It is NOT part of the user's concept, mission or blacklist.\n\n"
                + "STAGE: ").append(stage.name()).append('\n');
        switch (stage) {
            case OPEN_EXPLORATION:
                block.append("- Do not prematurely narrow the user's topic.\n")
                        .append("- Prefer understanding, broad themes and capturing clearly "
                                + "expressed interests.\n")
                        .append("- Do not revisit questions the user has already answered.\n");
                break;
            case GUIDED_EXPLORATION:
                block.append("- Read CURRENT_CONCEPT before proposing or adding cards.\n")
                        .append("- Deepen existing branches before inventing parallel "
                                + "duplicates.\n")
                        .append("- Ask about meaningful dimensions, not about arbitrary "
                                + "target counts.\n");
                break;
            case BOUNDARY_PROBING:
                block.append("- Ask at most ONE boundary question in this turn.\n")
                        .append("- Prefer an unresolved edge over creating more concept "
                                + "cards.\n")
                        .append("- Do not reopen settled exclusions or settled choices.\n");
                break;
            default: // CLOSURE_CHECK
                block.append("- Do not claim that the scope is complete.\n")
                        .append("- Summarize what is established and name any remaining "
                                + "uncertainty.\n")
                        .append("- The user alone decides whether to continue or leave "
                                + "Concept.\n");
        }
        if (calibrationWeak) {
            block.append("- The last scope check could not calibrate reliably — be "
                    + "conservative about boundary claims.\n");
        }
        return block.toString();
    }

    /** The compact observability line (ratified D2): stage plus the deciding inputs. */
    public static String logLine(Stage stage, Inputs in) {
        return "conversation policy -> " + stage.name()
                + " novelty=" + (in.turnsWithoutNewCards >= in.noveltySaturationTurns
                        ? "SATURATED" : in.turnsWithoutNewCards + "/"
                                + in.noveltySaturationTurns)
                + " cards=" + in.positiveNodeCount
                + " boundary=" + in.openBoundaryWork
                + " scopeCheck=" + (!in.scopeCheckCurrent ? "STALE_OR_NONE"
                        : String.valueOf(in.scopeCheckKind))
                + " driftGuards=" + in.driftGuardCount
                + (in.calibrationWeak ? " calibration=WEAK" : "");
    }
}
