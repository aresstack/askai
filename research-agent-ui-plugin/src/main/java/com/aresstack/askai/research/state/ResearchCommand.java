package com.aresstack.askai.research.state;

/**
 * An immutable command with a stable {@code commandId}, plus where it came from (D2): the actor, the sending
 * surface, a correlation id that a chained command shares with the user action that caused it, the memento
 * revision the surface showed when the user clicked ({@link #NO_REVISION} when the surface shows none) and
 * the user's optional comment ({@code request-changes}). The state machine itself only reads the type; the
 * rest is checked and journalled by the transition engine.
 */
public final class ResearchCommand {

    /** Marker for "the sending surface showed no revision", which skips the outdated-revision check. */
    public static final long NO_REVISION = -1L;

    private final ResearchCommandType type;
    private final String commandId;
    private final CommandActor actor;
    private final CommandSurface surface;
    private final String correlationId;
    private final long seenRevision;
    private final String comment;

    public ResearchCommand(ResearchCommandType type, String commandId) {
        this(type, commandId, CommandActor.USER, CommandSurface.USER_INTERFACE, commandId, NO_REVISION, "");
    }

    public ResearchCommand(ResearchCommandType type, String commandId, CommandActor actor,
                           CommandSurface surface, String correlationId, long seenRevision, String comment) {
        if (type == null) {
            throw new IllegalArgumentException("command type must not be null");
        }
        if (commandId == null || commandId.trim().isEmpty()) {
            throw new IllegalArgumentException("commandId must not be empty");
        }
        if (actor == null || surface == null) {
            throw new IllegalArgumentException("actor and surface must not be null");
        }
        this.type = type;
        this.commandId = commandId;
        this.actor = actor;
        this.surface = surface;
        this.correlationId = correlationId == null || correlationId.trim().isEmpty() ? commandId
                : correlationId.trim();
        this.seenRevision = seenRevision < 0 ? NO_REVISION : seenRevision;
        this.comment = comment == null ? "" : comment.trim();
    }

    /** A user command from the app's own surfaces, without a revision or comment. */
    public static ResearchCommand of(ResearchCommandType type, String commandId) {
        return new ResearchCommand(type, commandId);
    }

    public ResearchCommandType getType() {
        return type;
    }

    public String getCommandId() {
        return commandId;
    }

    public CommandActor getActor() {
        return actor;
    }

    public CommandSurface getSurface() {
        return surface;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    /** The revision the surface showed, or {@link #NO_REVISION}. */
    public long getSeenRevision() {
        return seenRevision;
    }

    public String getComment() {
        return comment;
    }

    @Override
    public String toString() {
        return type + "(" + commandId + ", " + actor + "/" + surface + ")";
    }
}
