package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.composer.ComposerAccessory;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.research.backend.ScopingAssistantUpdate;
import com.aresstack.askai.research.state.oo.ResearchStateIds;

import javax.swing.JComponent;
import java.util.function.Consumer;

/**
 * The composer accessory: the UNIFORM tag surface above the composer. Yellow suggestion tags (scoping only)
 * plus the RED action tags of the CURRENT state — one derivation, one click path, no chat action cards and no
 * special-case buttons. A yellow click runs the search; a red click runs its command through the session's
 * ONE structured command processor ({@link ResearchAgentSession#executeCommand}) — exactly what {@code /do}
 * and the MCP {@code run_command} run. Kept in sync via the session's state listener; disposed by the host on
 * session/agent/tab change.
 */
final class ScopingComposerAccessory implements ComposerAccessory {

    private final ResearchAgentSession research;
    private final ScopingSupportView view;
    private final Runnable refresh;

    private volatile Consumer<String> placeholderSink;

    ScopingComposerAccessory(final ResearchAgentSession research, final UiExecutor uiExecutor) {
        this.research = research;
        this.view = new ScopingSupportView();
        this.view.setSearchAction(new Consumer<String>() {
            public void accept(String query) {
                // The clicked YELLOW tag announces itself aloud (settings-gated, assertive tone) —
                // THIS is the "gelbe Suchtags vorlesen" surface, not the transcript bubbles.
                research.readSearchTagAloud(query);
                // A yellow suggestion click IS a /search: run the SAME phase-independent manual web search the
                // typed `/search` command runs. It must NOT go through ChatSubmissionTarget.submitText — that
                // would disguise a phase-independent service as an agent prompt and couple it to the phase/turn
                // availability. The visible, persisted "Websuche: <query>" breadcrumb is emitted uniformly from
                // the search's 'started' event (applyManualSearch), so both entry points look identical.
                research.requestManualWebSearch(query);
            }
        });
        this.view.setActionHandler(new Consumer<ResearchActionTag>() {
            public void accept(ResearchActionTag action) {
                // ONE path for every red tag: the structured command processor. Rejections surface through
                // the session (problems/diagnostics); the tags re-derive on the resulting state change.
                String arguments = "";
                if ("request-changes".equals(action.getCommand())) {
                    // D1: an optional one-line comment for the change request (Cancel = no request).
                    Object answer = javax.swing.JOptionPane.showInputDialog(view,
                            "What should change? (optional)", "Request changes",
                            javax.swing.JOptionPane.PLAIN_MESSAGE, null, null, "");
                    if (answer == null) {
                        return;
                    }
                    arguments = answer.toString().trim();
                }
                research.executeUserCommand(action.getCommand(), arguments, action.getRevision());
            }
        });
        this.refresh = new Runnable() {
            public void run() {
                final boolean scoping = ResearchStateIds.SCOPING.equals(
                        research.currentResearchSnapshot().getCurrentPhaseId());
                // Drop suggestions whose query a user search already covered, so a searched (clicked) tag
                // disappears after the search and the same/covered query is never re-offered; the tags then
                // re-arrange in the flow layout. Suggestions are scoping-only; ACTION tags exist in EVERY
                // phase (approval gates, resume/retry, review offers) — the surface follows the state.
                final ScopingAssistantUpdate projection = scoping
                        ? withoutSearched(research.latestScopingProjection()) : null;
                final java.util.List<ResearchActionTag> actions = research.availableActionTags();
                uiExecutor.execute(new Runnable() {
                    public void run() {
                        // Back to demand-driven visibility: the free-search field moved to the
                        // host's TITLE BAR (ResearchWebSearchContribution), so this
                        // surface only appears while it has tags to show.
                        view.setVisible(scoping || !actions.isEmpty());
                        view.apply(projection, actions);
                        pushPlaceholder(scoping, projection);
                    }
                });
            }
        };
        research.addStateListener(refresh);
        refresh.run(); // initial paint
    }

    public JComponent getComponent() {
        return view;
    }

    @Override
    public void bindPlaceholderSink(Consumer<String> sink) {
        this.placeholderSink = sink;
        refresh.run(); // push the current query into the freshly bound composer placeholder
    }

    /** The agent's best query lives ONLY in the composer placeholder — nowhere else in the UI. */
    private void pushPlaceholder(boolean scoping, ScopingAssistantUpdate projection) {
        Consumer<String> sink = placeholderSink;
        if (sink == null) {
            return;
        }
        String query = null;
        if (scoping && projection != null && !projection.getSearchSuggestions().isEmpty()) {
            query = projection.getSearchSuggestions().get(0).getQuery();
        }
        sink.accept(query == null || query.trim().isEmpty() ? null : query.trim());
    }

    /** A copy of the projection with already-searched queries removed (null-safe; identity when none drop). */
    private ScopingAssistantUpdate withoutSearched(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return null;
        }
        java.util.List<ScopingAssistantUpdate.Suggestion> kept =
                new java.util.ArrayList<ScopingAssistantUpdate.Suggestion>();
        for (ScopingAssistantUpdate.Suggestion suggestion : projection.getSearchSuggestions()) {
            if (!research.wasManuallySearched(suggestion.getQuery())) {
                kept.add(suggestion);
            }
        }
        if (kept.size() == projection.getSearchSuggestions().size()) {
            return projection; // nothing searched yet → unchanged
        }
        return new ScopingAssistantUpdate(projection.getPhaseId(), kept,
                projection.getAdviceRecommendation(), projection.getAdviceReason());
    }

    public void dispose() {
        research.removeStateListener(refresh);
        view.dispose();
    }
}
