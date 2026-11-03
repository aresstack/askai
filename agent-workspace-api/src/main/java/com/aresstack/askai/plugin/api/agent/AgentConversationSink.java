package com.aresstack.askai.plugin.api.agent;

/**
 * Host port through which an {@link AgentSession} pushes conversation activity into the <em>shared</em> AskAI
 * chat transcript. There is exactly one conversation surface in the product — the normal chat — so an agent
 * never creates its own; it adapts its backend events onto this sink and the host renders them in the existing
 * user/assistant/thinking/tool/approval bubbles.
 *
 * <p>Everything is addressed by stable ids so updates target the right bubble. Raw tool arguments/results must
 * not be pushed unfiltered. All methods are called on the UI thread; after the session is closed the host may
 * ignore further calls.</p>
 */
public interface AgentConversationSink {

    void appendUserMessage(String messageId, String markdown);

    void appendAssistantMessage(String messageId, String markdown);

    void startThinking(String activityId, String title);

    /** Shows {@code text} as the bubble's FULL current body (replaces, never appends). */
    void updateThinking(String activityId, String text);

    void finishThinking(String activityId, String summary);

    void startToolActivity(String activityId, String title, String explanation);

    /** Invoked when the user CLICKS a tool-activity bubble (e.g. to have its tag read aloud). */
    interface ToolActivityClickListener {
        void toolActivityClicked(String title, String explanation);
    }

    /**
     * Optional: register a click listener for this conversation's tool-activity bubbles. Hosts
     * without clickable activities (and test fakes) simply keep the no-op default.
     */
    default void setToolActivityClickListener(ToolActivityClickListener listener) {
    }

    void updateToolActivity(String activityId, String title, String explanation);

    void completeToolActivity(String activityId, String summary);

    void failToolActivity(String activityId, String summary);

    /** Render an interactive approval request in the chat; the id is the approval the user later acts on. */
    void requestApproval(String approvalId, String prompt);

    /** Render a blocked/failed condition as a readable status bubble (public message only). */
    void showProblem(String problemId, String publicMessage);

    /**
     * A muted, centered ITALIC info/system line in the shared chat (e.g. {@code "Websuche: <query>"}) — NOT a
     * user or assistant bubble. It is a persisted breadcrumb: it survives a restart (as content), unlike a
     * transient activity card. A host without an info line falls back to an assistant message so the text is
     * never lost.
     */
    default void appendInfoMessage(String messageId, String markdown) {
        appendAssistantMessage(messageId, markdown);
    }

    /**
     * Mark a previously appended info line with its TERMINAL outcome: success renders a green check
     * behind the line, failure a red circling-arrow retry control — clicking it runs {@code retry}.
     * Hosts without decorated info lines may drop this.
     */
    default void markInfoStatus(String messageId, boolean success, Runnable retry) {
    }

    /**
     * One technical diagnostic line for the host's collapsible "Technical details" area — NEVER rendered
     * in the visible chat or inside a card. Hosts without such an area may drop the line.
     */
    default void appendTechnicalLog(String line) {
    }

    /**
     * The session's turn activity started or ended WITHOUT producing conversation content — the host must
     * re-read Send/Stop availability.
     * <p>
     * Every other method here happens to refresh the composer as a side effect of writing a bubble, which
     * made "the agent is done" depend on "the agent also said something". A turn that ended right after its
     * last message left the Stop button red forever. Releasing the composer is its own statement.
     */
    default void turnActivityChanged() {
    }

    /**
     * How an action relates to the card: a NAVIGATION action only shows something (open the sources tab,
     * open the configuration) and must never consume the card — its decision buttons stay usable. A
     * DECISION action consumes the card once it is ACCEPTED.
     */
    enum ActionKind {
        NAVIGATION,
        DECISION
    }

    /** The typed outcome of a pressed card action — drives whether the card's buttons stay active. */
    enum ActionExecutionResult {
        /** The decision took effect; the card is consumed (its buttons are disabled). */
        ACCEPTED,
        /** The action was rejected by the current state; the card stays active. */
        REJECTED,
        /** Nothing changed (typical for NAVIGATION); the card stays active. */
        NO_STATE_CHANGE,
        /** The action failed; the card stays active and the failure is reported visibly. */
        FAILED
    }

    /** One typed action offered on an interactive card (stable id + localized label + kind). */
    final class ActionOption {
        private final String id;
        private final String label;
        private final ActionKind kind;

        public ActionOption(String id, String label) {
            this(id, label, ActionKind.DECISION);
        }

        public ActionOption(String id, String label, ActionKind kind) {
            this.id = id == null ? "" : id;
            this.label = label == null ? "" : label;
            this.kind = kind == null ? ActionKind.DECISION : kind;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public ActionKind getKind() {
            return kind;
        }
    }

    /** Receives the id of the action the user pressed on a card (called on the UI thread). */
    interface ActionHandler {
        ActionExecutionResult onAction(String actionId);
    }

    /**
     * Render ONE interactive result/decision card: readable markdown plus real buttons that dispatch typed
     * actions back to the session — never synthetic chat messages. A host without card support falls back
     * to the plain message (the text alone must remain understandable).
     */
    default void showActionCard(String cardId, String markdown, java.util.List<ActionOption> actions,
                                ActionHandler handler) {
        appendAssistantMessage(cardId, markdown);
    }

    /**
     * Like {@link #showActionCard} but for a card RE-DERIVED from the live state on restore (e.g. a pending
     * approval). Its underlying content is already in the persisted transcript, so the host renders the
     * interactive buttons WITHOUT persisting the card again — otherwise a re-shown card would duplicate and
     * accumulate across restarts. Defaults to {@link #showActionCard}.
     */
    default void showLiveActionCard(String cardId, String markdown, java.util.List<ActionOption> actions,
                                    ActionHandler handler) {
        showActionCard(cardId, markdown, actions, handler);
    }
}
