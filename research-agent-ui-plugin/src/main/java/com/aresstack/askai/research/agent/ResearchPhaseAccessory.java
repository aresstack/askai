package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import java.util.List;
import java.util.function.Consumer;

/**
 * The generic phase-bound surface: the {@link ResearchOutOfScopeSky} is a see-through layer over the
 * TOP of the transcript ({@code TRANSCRIPT_OVERLAY} placement). PR 12: the blacklist filters in every
 * phase, so the sky is shown in every phase (Q2). Visibility and data follow the session's state
 * listener exactly like before; add/remove intents run through the session's ONE scope path.
 */
final class ResearchPhaseAccessory implements ComposerAccessory {

    private final ResearchAgentSession research;
    private final ResearchOutOfScopeSky view;
    private final Runnable refresh;

    ResearchPhaseAccessory(final ResearchAgentSession research, final UiExecutor uiExecutor) {
        this.research = research;
        this.view = new ResearchOutOfScopeSky();
        // Model voice for read-aloud: host-configured (chat settings → Audio & Dictation); the
        // sky falls back to the Windows voice whenever the port is absent or inactive. The
        // SESSION's live language decides which language's voice speaks — German text gets the
        // German voice, English text the English one (Windows fallback included).
        com.aresstack.askai.agent.model.speech.SpeechSynthesisPort speechPort =
                research.getHostService(
                        com.aresstack.askai.agent.model.speech.SpeechSynthesisPort.class);
        this.view.setModelVoice(speechPort);
        this.view.setReadAloudLanguage(new java.util.function.Supplier<String>() {
            public String get() {
                return research.getSessionLanguage().currentLanguage().getCode();
            }
        });
        if (speechPort != null && speechPort.isReadAloudActiveByDefault()) {
            this.view.enableReadAloudAutoStart(); // central preference: Play is on from the start
        }
        // The session mirrors the LIVE Play/Pause wish — the "tags only while read-aloud is
        // active" gate reads exactly this state.
        this.view.setReadAloudStateListener(new Consumer<Boolean>() {
            public void accept(Boolean active) {
                research.setReadAloudActive(Boolean.TRUE.equals(active));
            }
        });
        this.view.setAddAction(new Consumer<String>() {
            public void accept(String text) {
                reportRejection(research.addScopeExclusion(text));
            }
        });
        this.view.setRemoveAction(new Consumer<String>() {
            public void accept(String text) {
                reportRejection(research.removeScopeExclusion(text));
            }
        });
        this.refresh = new Runnable() {
            public void run() {
                final boolean scoping = ResearchStateIds.SCOPING.equals(
                        research.currentResearchSnapshot().getCurrentPhaseId());
                // ONE projection over the ONE draft: the user-worded exclusion strings PLUS the
                // labels of EXCLUDED facets — before this, a model-recorded excludeFacet was
                // persisted but invisible ("Noch keine Ausschlüsse" over a stored exclusion).
                // PR 12: the blacklist filters in every phase, so the sky is shown in every phase
                final List<String> exclusions = exclusionsProjection(research.currentScopeDraft());
                // Read-aloud source: the host's persisted last assistant answer — while the
                // bar's Play is active, each NEW answer is spoken automatically on this refresh.
                final com.aresstack.askai.plugin.api.service.ChatMessageSnapshot lastAnswer =
                        scoping ? research.lastAssistantMessage() : null;
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        view.setVisible(true);
                        view.setExclusions(exclusions);
                        view.setLatestAnswer(lastAnswer == null ? null : lastAnswer.getMessageId(),
                                lastAnswer == null ? null : lastAnswer.getText());
                    }
                });
            }
        };
        research.addStateListener(refresh);
        refresh.run(); // initial paint
    }

    /** A refused add/remove must stay visible — the sky would otherwise silently lie. */
    private void reportRejection(String reason) {
        if (reason != null) {
            JOptionPane.showMessageDialog(view, reason, "Blacklist entry not changed",
                    JOptionPane.WARNING_MESSAGE);
        }
    }

    public JComponent getComponent() {
        return view;
    }

    @Override
    public Placement getPlacement() {
        return Placement.TRANSCRIPT_OVERLAY;
    }

    public void dispose() {
        research.removeStateListener(refresh);
        view.shutdownReadAloud(); // the voice never outlives its chat tab
    }

    /** Exclusion strings + EXCLUDED facet labels, deduplicated, draft order. */
    private static java.util.List<String> exclusionsProjection(
            com.aresstack.askai.research.domain.scope.ResearchScopeDraft draft) {
        java.util.LinkedHashSet<String> union =
                new java.util.LinkedHashSet<String>(draft.getExclusions());
        for (com.aresstack.askai.research.domain.scope.ScopeFacet facet : draft.excludedFacets()) {
            if (!facet.getLabel().trim().isEmpty()) {
                union.add(facet.getLabel().trim());
            }
        }
        return new java.util.ArrayList<String>(union);
    }
}
