package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.ResearchPhaseState;
import com.aresstack.askai.research.state.oo.ResearchStateFactory;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The State visualization renders a domain-derived snapshot (no transition table of its own). */
public class ResearchStateViewTest {

    private final ResearchStateFactory factory = ResearchStateFactory.getInstance();

    private ResearchStateSnapshot snapshot(String phaseId, String stateId, String continuation,
                                           String approvalId, long rev, String problem) {
        ResearchPhaseState phase = factory.phase(phaseId,
                factory.state(phaseId, stateId, continuation, approvalId));
        return ResearchStateSnapshot.of(phase, rev, problem);
    }

    @Test
    public void showsCurrentPhaseAndSubstate() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, ""));
        assertTrue("the product name, never the compat id", text.contains("SOURCES"));
        assertTrue(text.contains("active"));
        assertTrue(text.contains("RUNNING"));
        assertTrue(text.contains("revision: 5"));
    }

    @Test
    public void showsPausedWithContinuation() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.PAUSED, ResearchStateIds.RUNNING, null, 6L, ""));
        assertTrue(text.contains("PAUSED"));
        assertTrue(text.contains("continuation: RUNNING"));
    }

    @Test
    public void showsBlockedWithReason() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.BLOCKED, ResearchStateIds.RUNNING, null, 6L,
                        "waiting for credentials"));
        assertTrue(text.contains("BLOCKED"));
        assertTrue(text.contains("reason: waiting for credentials"));
    }

    @Test
    public void showsFailedWithReason() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.FAILED, ResearchStateIds.RUNNING, null, 6L,
                        "provider down"));
        assertTrue(text.contains("FAILED"));
        assertTrue(text.contains("reason: provider down"));
    }

    @Test
    public void showsApprovalWithId() {
        String text = ResearchStateView.render(
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "appr-1", 7L, ""));
        assertTrue(text.contains("WAITING_APPROVAL"));
        assertTrue(text.contains("approval: appr-1"));
    }

    @Test
    public void showsCompletedAndCancelled() {
        String completed = ResearchStateView.render(
                snapshot(ResearchStateIds.DRAFT, ResearchStateIds.COMPLETED, null, null, 20L, ""));
        assertTrue("the product name, never the compat id", completed.contains("DOCUMENT"));
        assertTrue(completed.contains("COMPLETED"));

        String cancelled = ResearchStateView.render(
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.CANCELLED, null, null, 3L, ""));
        assertTrue(cancelled.contains("CANCELLED"));
    }

    @Test
    public void earlierPhasesAreMarkedCompleted() {
        // #43: Outline comes AFTER Sources — with Sources running only Concept is done.
        ResearchStateSnapshot s = snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        assertTrue(s.getCompletedPhaseIds().contains(ResearchStateIds.SCOPING));
        assertFalse(s.getCompletedPhaseIds().contains(ResearchStateIds.OUTLINE));
        assertFalse(s.getCompletedPhaseIds().contains(ResearchStateIds.DRAFT));
    }

    @Test
    public void allowedCommandsComeFromTheDomain() {
        ResearchStateSnapshot s = snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        assertTrue(s.getAllowedCommands().contains(ResearchCommandType.REQUEST_EVIDENCE_REVIEW));
        assertTrue(s.getAllowedCommands().contains(ResearchCommandType.PAUSE));
        assertFalse(s.getAllowedCommands().contains(ResearchCommandType.APPROVE_OUTLINE));
    }

    @Test
    public void viewRendersSnapshotIntoText() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.OUTLINE, ResearchStateIds.WAITING_APPROVAL, null, "a9", 8L, "");
        final ResearchStateView[] viewHolder = new ResearchStateView[1];
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                viewHolder[0] = new ResearchStateView();
                viewHolder[0].setSnapshot(s);
            }
        });
        assertTrue(viewHolder[0].renderedText().contains("approval: a9"));
    }

    // ------------------------------------------------------------------ clickable phases

    @Test
    public void theNextPhaseIsClickableWithTheDomainAdvanceCommand() throws Exception {
        // SCOPING/running: SUBMIT_SCOPE leads into RESEARCH → the RESEARCH plate is the control.
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING, null, null, 2L, "");
        assertTrue(s.advanceCommandFor(ResearchStateIds.RESEARCH) == ResearchCommandType.SUBMIT_SCOPE);
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                assertTrue("the research plate carries the submit-scope advance",
                        view.clickablePhasesForTest().get(ResearchStateIds.RESEARCH)
                                == ResearchCommandType.SUBMIT_SCOPE);
            }
        });
    }

    @Test
    public void approvalGatesOfferForwardAndBackwardPhaseClicks() throws Exception {
        // Sources approval: OUTLINE = approve (forward), SOURCES itself = request revision.
        final ResearchStateSnapshot s = snapshot(ResearchStateIds.RESEARCH,
                ResearchStateIds.WAITING_APPROVAL, null, "a1", 9L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                java.util.Map<String, ResearchCommandType> clickable = view.clickablePhasesForTest();
                assertTrue(clickable.get(ResearchStateIds.OUTLINE)
                        == ResearchCommandType.APPROVE_EVIDENCE);
                assertTrue("PR 05: the current phase's plate never dispatches",
                        clickable.get(ResearchStateIds.RESEARCH) == null);
            }
        });
    }

    @Test
    public void agentInternalAdvancesAreNotClickable() throws Exception {
        // RESEARCH/running advances via REQUEST_EVIDENCE_REVIEW — an AGENT decision, not user
        // vocabulary → no plate becomes a control (the user pauses/cancels, the agent advances).
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                java.util.Map<String, ResearchCommandType> clickable = view.clickablePhasesForTest();
                assertTrue("the agent-internal advance stays no control",
                        !clickable.containsKey(ResearchStateIds.RESEARCH));
                assertTrue("only the way BACK is a control (always possible)",
                        clickable.size() == 1
                                && clickable.get(ResearchStateIds.SCOPING) == ResearchCommandType.REOPEN_CONCEPT);
            }
        });
    }

    @Test
    public void phaseClickDispatchesAndRejectionFeedbackIsShown() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING, null, null, 2L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                final java.util.List<ResearchCommandType> clicked =
                        new java.util.ArrayList<ResearchCommandType>();
                ResearchStateView view = new ResearchStateView();
                view.setCommandListener(new ResearchStateView.CommandListener() {
                    public com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                            commandClicked(ResearchCommandType command) {
                        clicked.add(command);
                        return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.of(
                                com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                                        .Status.DISPATCH_FAILED, "The brief still needs a question.");
                    }
                });
                view.setSnapshot(s);
                view.clickPhaseForTest(ResearchStateIds.RESEARCH);
                assertTrue(clicked.contains(ResearchCommandType.SUBMIT_SCOPE));
                assertTrue("the honest domain reason is shown",
                        view.feedbackTextForTest().contains("The brief still needs a question."));

                view.setSnapshot(s); // the next state change clears stale feedback
                assertFalse(view.feedbackTextForTest().contains("needs a question"));
            }
        });
    }

    // ------------------------------------------------------------------ no command furniture

    @Test
    public void thereIsNoExtraCommandButtonBarAnywhereInTheTab() throws Exception {
        // The interruption machinery (pause/resume/retry/cancel, block/fail) is DOMAIN state, not
        // user furniture: besides the clickable plates the tab renders labels only — no ComicButton.
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.RESEARCH, ResearchStateIds.RUNNING, null, null, 5L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = acceptingView();
                view.setSnapshot(s);
                assertFalse("no button controls in the state tab",
                        containsButton(view));
            }
        });
    }

    private static boolean containsButton(java.awt.Container container) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof javax.swing.JScrollBar) {
                continue; // the comic scrollbar's zero-size arrow stubs are plumbing, not controls
            }
            if (child instanceof javax.swing.AbstractButton) {
                return true;
            }
            if (child instanceof java.awt.Container && containsButton((java.awt.Container) child)) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void withoutAListenerTheTabStaysReadOnly() throws Exception {
        final ResearchStateSnapshot s =
                snapshot(ResearchStateIds.SCOPING, ResearchStateIds.RUNNING, null, null, 2L, "");
        javax.swing.SwingUtilities.invokeAndWait(new Runnable() {
            public void run() {
                ResearchStateView view = new ResearchStateView();
                view.setSnapshot(s);
                assertTrue("no command port, no clickable plates",
                        view.clickablePhasesForTest().isEmpty());
            }
        });
    }

    private static ResearchStateView acceptingView() {
        ResearchStateView view = new ResearchStateView();
        view.setCommandListener(new ResearchStateView.CommandListener() {
            public com.aresstack.askai.research.backend.ResearchCommandDispatchResult
                    commandClicked(ResearchCommandType command) {
                return com.aresstack.askai.research.backend.ResearchCommandDispatchResult.accepted();
            }
        });
        return view;
    }
}
