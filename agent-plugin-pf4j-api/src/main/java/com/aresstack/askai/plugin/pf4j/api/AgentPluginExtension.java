package com.aresstack.askai.plugin.pf4j.api;

import com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor;
import com.aresstack.askai.plugin.api.agent.AgentSessionFactory;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;

import org.pf4j.ExtensionPoint;

import java.util.List;

/**
 * The PF4J extension point a <em>chat agent</em> plugin implements. An agent extends the shared AskAI chat
 * rather than replacing it: it describes itself, creates isolated sessions, and contributes slash commands and
 * artifact views. It must be stateless — host services are supplied later at
 * {@link AgentSessionFactory#create}. This lives alongside {@link WorkspacePluginExtension}; a single plugin
 * may expose both during the migration.
 */
public interface AgentPluginExtension extends ExtensionPoint {

    AgentPluginDescriptor getAgentDescriptor();

    AgentSessionFactory getSessionFactory();

    /** Slash commands this agent contributes to the shared composer; never {@code null} (may be empty). */
    List<ChatCommandContribution> getChatCommands();

    /** Views for the agent's structured (non-Markdown) artifacts; never {@code null} (may be empty). */
    List<ArtifactViewContribution> getArtifactViews();

    /**
     * Settings pages for the host's gear menu at the chat composer — rendered ONLY while this agent is the
     * selected one of the current chat tab, with that tab's live session. Session-based by contract:
     * the component works on the session's values, never on agent-global state. Default: none.
     */
    default List<com.aresstack.askai.plugin.api.agent.AgentSettingsContribution> getSettingsContributions() {
        return java.util.Collections.emptyList();
    }

    /**
     * Sections for AskAI's first-start setup wizard: what this agent needs so the user can start right away
     * (downloads, keys). Not session-based – the wizard runs before any chat tab. Default: none.
     */
    default List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> getSetupContributions() {
        return java.util.Collections.emptyList();
    }

    /**
     * Persistent components shown ABOVE the chat composer while this agent is the active one of the current
     * tab (e.g. active-phase controls). Generic and session-based; the host builds and disposes them on
     * session/agent/tab change. Default: none.
     */
    default List<com.aresstack.askai.plugin.api.agent.composer.ComposerAccessoryContribution>
            getComposerAccessories() {
        return java.util.Collections.emptyList();
    }

    /**
     * Small session-scoped controls for the workspace top bar, shown left of the gear while this agent is
     * the active one of the current tab (e.g. a language switch). Generic and session-based like the
     * composer accessories; the host builds and discards them on session/agent/tab change. Default: none.
     */
    default List<com.aresstack.askai.plugin.api.agent.toolbar.AgentToolbarContribution>
            getToolbarContributions() {
        return java.util.Collections.emptyList();
    }

    /**
     * The search this agent offers in the host's TITLE-BAR search field while it is the active agent
     * (e.g. the research web search). The host owns the field; the contribution defines placeholder,
     * tooltip and what a search does. Default: {@code null} = no search (the field stays hidden).
     */
    default com.aresstack.askai.plugin.api.agent.search.AgentSearchContribution getSearchContribution() {
        return null;
    }

    /**
     * An optional BRAND glyph replacing the workspace's hamburger icon while this agent is active
     * (e.g. the research flask). The button's FUNCTION is untouched — it still unfolds the tab
     * menu — and the host shows the ordinary hamburger again on HOVER, so the user always learns
     * what the click does. Paint with the owning component's foreground (the button swaps it per
     * state); monochrome Java2D, no emoji. Default: {@code null} = keep the hamburger.
     */
    default javax.swing.Icon getMenuIcon() {
        return null;
    }
}
