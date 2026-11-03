package com.aresstack.askai.plugin.api.agent.setup;

/**
 * A part of AskAI's first-start setup wizard that an agent plugin contributes: what the plugin needs before the
 * user can start right away (for the research agent: the search browser download and the search provider). The
 * host shows it in the wizard's research step; the plugin decides what it contains.
 *
 * <p>Unlike {@link com.aresstack.askai.plugin.api.agent.AgentSettingsContribution} this is not session-based: the
 * wizard runs before any chat tab exists, so the page works on host-wide values (the host state store, the
 * plugin's own files).</p>
 */
public interface AgentSetupContribution {

    /** The section heading in the wizard (e.g. "Web search"). */
    String getDisplayName();

    /** Builds the page once per wizard run; {@code null} when there is nothing to set up. */
    AgentSetupPage createSetupPage(AgentSetupContext context);
}
