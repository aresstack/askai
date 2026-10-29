package com.aresstack.askai.research.backend;

import com.aresstack.askai.research.state.ResearchPhase;
import com.aresstack.askai.research.state.ResearchRunState;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;

/**
 * Immutable backend event with a stable envelope (eventId, sessionId, projectId, revision, timestamp, a
 * per-session monotonic sequenceNumber, and the originating commandId when applicable) plus type-specific
 * payload. The sequenceNumber lets the UI ignore late/duplicate deliveries and prepares for later transport
 * reordering/replay without a UI rewrite. Only public-safe text is carried; raw tool args/results are not.
 */
public final class ResearchBackendEvent {

    private final String eventId;
    private final String sessionId;
    private final String projectId;
    private final long revision;
    private final long timestamp;
    private final long sequenceNumber;
    private final String commandId;
    private final ResearchBackendEventType type;

    private final ResearchPhase phase;
    private final ResearchRunState runState;
    private final ResearchStateMemento stateMemento;
    private final String activityId;
    private final ResearchActivityKind activityKind;
    private final String title;
    private final String text;
    private final String approvalId;
    private final String publicMessage;
    private final String technicalDetail;
    private final ResearchRunProgressInfo runProgress;
    private final ResearchRunOutcomeInfo runOutcome;
    private final ScopingAssistantUpdate scopingProjection;

    private ResearchBackendEvent(Builder b) {
        this.eventId = b.eventId;
        this.sessionId = b.sessionId;
        this.projectId = b.projectId;
        this.revision = b.revision;
        this.timestamp = b.timestamp;
        this.sequenceNumber = b.sequenceNumber;
        this.commandId = b.commandId;
        this.type = b.type;
        this.stateMemento = b.stateMemento;
        this.phase = b.stateMemento != null ? ResearchStateIds.phase(b.stateMemento.getPhaseId()) : b.phase;
        this.runState = b.stateMemento != null
                ? ResearchStateIds.runState(b.stateMemento.getStateId()) : b.runState;
        this.activityId = b.activityId;
        this.activityKind = b.activityKind;
        this.title = b.title;
        this.text = b.text;
        this.approvalId = b.approvalId;
        this.publicMessage = b.publicMessage;
        this.technicalDetail = b.technicalDetail;
        this.runProgress = b.runProgress;
        this.runOutcome = b.runOutcome;
        this.scopingProjection = b.scopingProjection;
    }

    public String getEventId() {
        return eventId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getProjectId() {
        return projectId;
    }

    public long getRevision() {
        return revision;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getCommandId() {
        return commandId;
    }

    public ResearchBackendEventType getType() {
        return type;
    }

    public ResearchPhase getPhase() {
        return phase;
    }

    public ResearchRunState getRunState() {
        return runState;
    }

    /** @return the exact state snapshot for a state-change event (the live truth), or {@code null}. */
    public ResearchStateMemento getStateMemento() {
        return stateMemento;
    }

    public String getActivityId() {
        return activityId;
    }

    public ResearchActivityKind getActivityKind() {
        return activityKind;
    }

    public String getTitle() {
        return title;
    }

    public String getText() {
        return text;
    }

    public String getApprovalId() {
        return approvalId;
    }

    public String getPublicMessage() {
        return publicMessage;
    }

    public String getTechnicalDetail() {
        return technicalDetail;
    }

    /** @return the structured run progress for a {@code RUN_PROGRESS} event, else {@code null}. */
    public ResearchRunProgressInfo getRunProgress() {
        return runProgress;
    }

    /** @return the structured run outcome for a {@code RUN_OUTCOME} event, else {@code null}. */
    public ResearchRunOutcomeInfo getRunOutcome() {
        return runOutcome;
    }

    /** @return the scoping projection for a {@code SCOPING_PROJECTION} event, else {@code null}. */
    public ScopingAssistantUpdate getScopingProjection() {
        return scopingProjection;
    }

    public static Builder builder(ResearchBackendEventType type) {
        return new Builder(type);
    }

    public static final class Builder {
        private String eventId = "";
        private String sessionId = "";
        private String projectId = "";
        private long revision;
        private long timestamp;
        private long sequenceNumber;
        private String commandId;
        private final ResearchBackendEventType type;
        private ResearchPhase phase;
        private ResearchRunState runState;
        private ResearchStateMemento stateMemento;
        private String activityId;
        private ResearchActivityKind activityKind;
        private String title = "";
        private String text = "";
        private String approvalId;
        private String publicMessage = "";
        private String technicalDetail = "";
        private ResearchRunProgressInfo runProgress;
        private ResearchRunOutcomeInfo runOutcome;
        private ScopingAssistantUpdate scopingProjection;

        private Builder(ResearchBackendEventType type) {
            this.type = type;
        }

        public Builder envelope(String eventId, String sessionId, String projectId, long revision,
                                long timestamp, long sequenceNumber, String commandId) {
            this.eventId = eventId;
            this.sessionId = sessionId;
            this.projectId = projectId;
            this.revision = revision;
            this.timestamp = timestamp;
            this.sequenceNumber = sequenceNumber;
            this.commandId = commandId;
            return this;
        }

        public Builder state(ResearchPhase phase, ResearchRunState runState) {
            this.phase = phase;
            this.runState = runState;
            return this;
        }

        /** The exact state snapshot (the live truth); legacy phase/runState are derived from it. */
        public Builder stateMemento(ResearchStateMemento stateMemento) {
            this.stateMemento = stateMemento;
            return this;
        }

        public Builder activity(String activityId, ResearchActivityKind kind, String title, String text) {
            this.activityId = activityId;
            this.activityKind = kind;
            this.title = title;
            this.text = text;
            return this;
        }

        public Builder approval(String approvalId, String prompt) {
            this.approvalId = approvalId;
            this.text = prompt;
            return this;
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder text(String text) {
            this.text = text;
            return this;
        }

        public Builder runProgress(ResearchRunProgressInfo runProgress) {
            this.runProgress = runProgress;
            return this;
        }

        public Builder runOutcome(ResearchRunOutcomeInfo runOutcome) {
            this.runOutcome = runOutcome;
            return this;
        }

        public Builder scopingProjection(ScopingAssistantUpdate scopingProjection) {
            this.scopingProjection = scopingProjection;
            return this;
        }

        public Builder messages(String publicMessage, String technicalDetail) {
            this.publicMessage = publicMessage;
            this.technicalDetail = technicalDetail;
            return this;
        }

        public ResearchBackendEvent build() {
            return new ResearchBackendEvent(this);
        }
    }
}
