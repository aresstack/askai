package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.scope.ScopeCheckReport;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The RATIFIED conversation-policy pins: priority order (sparse first — an early exclusion
 * never narrows), node-ID novelty saturation, CLOSURE_CHECK only on a CURRENT
 * NOTHING_TO_ASK without open conflict, CALIBRATION_WEAK never supporting closure beyond a
 * conservative line, and the delimited host-guidance header with GPT's verbatim hard lines.
 */
public class ConversationPolicyProjectionTest {

    private static final int SPARSE = 4;
    private static final int SATURATION = 3;

    private ConversationPolicyProjection.Inputs inputs(
            boolean mission, int cards, int turnsWithoutNew, boolean boundaryWork,
            boolean openConflict, boolean checkCurrent, ScopeCheckReport.Kind kind,
            boolean calibrationWeak, int driftGuards) {
        return new ConversationPolicyProjection.Inputs(mission, cards, turnsWithoutNew,
                boundaryWork, openConflict, checkCurrent, kind, calibrationWeak,
                driftGuards, SPARSE, SATURATION);
    }

    @Test
    public void sparseFieldStaysOpenEvenWithBoundaryMaterialInTheFirstSentence() {
        // An exclusion in the very first sentence: boundary work exists, but the concept
        // is sparse — the ratified priority keeps the dialogue in OPEN_EXPLORATION.
        assertEquals(ConversationPolicyProjection.Stage.OPEN_EXPLORATION,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 2, 0, true, false, false, null, false, 0)));
        // Missing mission alone also marks sparse, regardless of card count.
        assertEquals(ConversationPolicyProjection.Stage.OPEN_EXPLORATION,
                ConversationPolicyProjection.stageOf(inputs(
                        false, 12, 5, false, false, false, null, false, 0)));
    }

    @Test
    public void freshNoveltyMeansGuidedExploration() {
        assertEquals(ConversationPolicyProjection.Stage.GUIDED_EXPLORATION,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 1, true, false, true,
                        ScopeCheckReport.Kind.NOTHING_TO_ASK, false, 2)));
    }

    @Test
    public void saturatedWithOpenBoundaryWorkProbesTheBoundary() {
        assertEquals(ConversationPolicyProjection.Stage.BOUNDARY_PROBING,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 3, true, false, true,
                        ScopeCheckReport.Kind.NOTHING_TO_ASK, false, 1)));
    }

    @Test
    public void closureNeedsCurrentNothingToAskAndNoOpenConflict() {
        assertEquals(ConversationPolicyProjection.Stage.CLOSURE_CHECK,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 3, false, false, true,
                        ScopeCheckReport.Kind.NOTHING_TO_ASK, false, 0)));
        // A STALE report is ignored: no closure, guided instead.
        assertEquals(ConversationPolicyProjection.Stage.GUIDED_EXPLORATION,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 3, false, false, false,
                        ScopeCheckReport.Kind.NOTHING_TO_ASK, false, 0)));
        // An open conflict blocks closure even when the sweep found nothing to ask.
        assertFalse(ConversationPolicyProjection.Stage.CLOSURE_CHECK
                == ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 3, false, true, true,
                        ScopeCheckReport.Kind.NOTHING_TO_ASK, false, 0)));
        // ASKED (a pending question) is boundary work, never closure.
        assertEquals(ConversationPolicyProjection.Stage.BOUNDARY_PROBING,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 3, true, false, true,
                        ScopeCheckReport.Kind.ASKED, false, 0)));
    }

    @Test
    public void calibrationWeakOnlyAddsOneConservativeLineNeverClosureSupport() {
        // CALIBRATION_WEAK is not a weaker READY: kind stays SWEEP_NOT_READY → no closure.
        assertEquals(ConversationPolicyProjection.Stage.GUIDED_EXPLORATION,
                ConversationPolicyProjection.stageOf(inputs(
                        true, 8, 3, false, false, true,
                        ScopeCheckReport.Kind.SWEEP_NOT_READY, true, 0)));
        String block = ConversationPolicyProjection.hintBlock(
                ConversationPolicyProjection.Stage.GUIDED_EXPLORATION, true);
        assertTrue(block.contains("could not calibrate reliably"));
        String without = ConversationPolicyProjection.hintBlock(
                ConversationPolicyProjection.Stage.GUIDED_EXPLORATION, false);
        assertFalse(without.contains("could not calibrate reliably"));
    }

    @Test
    public void hintBlockCarriesTheRatifiedDelimiterAndVerbatimHardLines() {
        String open = ConversationPolicyProjection.hintBlock(
                ConversationPolicyProjection.Stage.OPEN_EXPLORATION, false);
        assertTrue(open.contains("CONVERSATION POLICY"));
        assertTrue(open.contains(
                "This is host guidance for HOW to conduct the next turn."));
        assertTrue(open.contains(
                "It is NOT part of the user's concept, mission or blacklist."));
        assertTrue(open.contains("Do not prematurely narrow the user's topic."));

        assertTrue(ConversationPolicyProjection.hintBlock(
                        ConversationPolicyProjection.Stage.GUIDED_EXPLORATION, false)
                .contains("Read CURRENT_CONCEPT before proposing or adding cards."));
        assertTrue(ConversationPolicyProjection.hintBlock(
                        ConversationPolicyProjection.Stage.BOUNDARY_PROBING, false)
                .contains("Ask at most ONE boundary question in this turn."));
        String closure = ConversationPolicyProjection.hintBlock(
                ConversationPolicyProjection.Stage.CLOSURE_CHECK, false);
        assertTrue(closure.contains("Do not claim that the scope is complete."));
        assertTrue(closure.contains(
                "The user alone decides whether to continue or leave Concept."));
    }

    @Test
    public void logLineProjectsTheDecidingInputsWithoutRawDumps() {
        ConversationPolicyProjection.Inputs in = inputs(
                true, 8, 3, true, false, true, ScopeCheckReport.Kind.ASKED, true, 2);
        String line = ConversationPolicyProjection.logLine(
                ConversationPolicyProjection.Stage.BOUNDARY_PROBING, in);
        assertEquals("conversation policy -> BOUNDARY_PROBING novelty=SATURATED cards=8"
                + " boundary=true scopeCheck=ASKED driftGuards=2 calibration=WEAK", line);
        String stale = ConversationPolicyProjection.logLine(
                ConversationPolicyProjection.Stage.GUIDED_EXPLORATION,
                inputs(true, 5, 1, false, false, false,
                        ScopeCheckReport.Kind.NOTHING_TO_ASK, false, 0));
        assertTrue(stale.contains("scopeCheck=STALE_OR_NONE"));
        assertTrue(stale.contains("novelty=1/3"));
    }

    @Test
    public void probeRefusalOutsideTheClosureCheckIsARejectionThatNamesTheStage() {
        for (ConversationPolicyProjection.Stage stage : ConversationPolicyProjection.Stage.values()) {
            String refusal = ConversationPolicyProjection.probeRefusal(stage);
            // The scope_probe tool maps every reply that does not start with PROBED to an
            // error — the loop then books a REJECTED round and hands the text to the model.
            assertTrue(refusal.startsWith("PROBE_REFUSED"));
            assertTrue(refusal.contains("CLOSURE_CHECK"));
            assertTrue(refusal.contains("the stage is " + stage.name()));
            assertTrue(refusal.contains("Do not probe now"));
        }
    }
}
