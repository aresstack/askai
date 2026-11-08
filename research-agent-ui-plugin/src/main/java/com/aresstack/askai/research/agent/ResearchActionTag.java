package com.aresstack.askai.research.agent;

/**
 * ONE currently available action as a red tag (issue #34-style UI unification): every tag represents exactly
 * one command of the synchronized command surface (state-machine commands like {@code approve-evidence} or
 * service commands like {@code review-sources}) and is derived from the LIVE state — a command that is not
 * allowed right now yields no tag (except the explicitly disabled-with-reason case, e.g. submit-scope while
 * the brief is still empty). Immutable.
 */
public final class ResearchActionTag {

    private final String command;
    private final String label;
    private final String tooltip;
    private final boolean enabled;
    /** The workflow revision the tag was derived for (PR 06, D3), or -1 when not stamped. */
    private final long revision;

    public ResearchActionTag(String command, String label, String tooltip, boolean enabled) {
        this(command, label, tooltip, enabled, -1L);
    }

    private ResearchActionTag(String command, String label, String tooltip, boolean enabled, long revision) {
        this.command = command == null ? "" : command;
        this.label = label == null || label.isEmpty() ? this.command : label;
        this.tooltip = tooltip == null ? "" : tooltip;
        this.enabled = enabled;
        this.revision = revision;
    }

    /** This tag stamped with the revision the user sees, so a click on a stale tag is refused. */
    public ResearchActionTag withRevision(long seenRevision) {
        return new ResearchActionTag(command, label, tooltip, enabled, seenRevision);
    }

    /** The revision the tag was derived for, or -1. */
    public long getRevision() {
        return revision;
    }

    /** The command (kebab-case) this tag runs — the SAME name /do and the MCP run_command accept. */
    public String getCommand() {
        return command;
    }

    public String getLabel() {
        return label;
    }

    public String getTooltip() {
        return tooltip;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
