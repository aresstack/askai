package com.aresstack.askai.research.plugin;

import com.aresstack.askai.plugin.api.agent.AgentArtifact;
import com.aresstack.askai.plugin.api.agent.AgentSettingsContribution;
import com.aresstack.askai.plugin.api.agent.artifact.ArtifactViewContribution;
import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import com.aresstack.askai.research.agent.ResearchArtifacts;
import com.aresstack.askai.research.agent.ResearchChatCommands;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Settings are plugin settings of the selected agent in the HOST's gear menu — session-based, and NOT
 * artifacts, NOT slash commands, NOT chat cards.
 */
public class ResearchSettingsContributionTest {

    private final ResearchAgentPluginExtension extension = new ResearchAgentPluginExtension();

    @Test
    public void theExtensionContributesExactlyOneSettingsPage() {
        assertEquals(1, extension.getSettingsContributions().size());
        assertEquals("Research Agent",
                extension.getSettingsContributions().get(0).getDisplayName());
    }

    @Test
    public void aForeignOrAbsentSessionGetsNoSettingsComponent() {
        AgentSettingsContribution contribution = extension.getSettingsContributions().get(0);
        // No session / another agent's session → null → the host omits the category entirely
        // (normal chat shows no research settings; deselecting the agent removes them again).
        assertNull(contribution.createSettingsComponent(null));
    }

    @Test
    public void pluginSettingsOpenWithoutAnySession() {
        final java.util.Map<String, String> values = new java.util.HashMap<String, String>();
        com.aresstack.askai.plugin.api.service.WorkspaceStateStore store =
                new com.aresstack.askai.plugin.api.service.WorkspaceStateStore() {
                    public String get(String key, String defaultValue) {
                        return values.containsKey(key) ? values.get(key) : defaultValue;
                    }
                    public boolean getBoolean(String key, boolean defaultValue) {
                        return Boolean.parseBoolean(get(key, String.valueOf(defaultValue)));
                    }
                    public int getInt(String key, int defaultValue) {
                        return Integer.parseInt(get(key, String.valueOf(defaultValue)));
                    }
                    public void put(String key, String value) {
                        values.put(key, value);
                    }
                    public void putBoolean(String key, boolean value) {
                        put(key, String.valueOf(value));
                    }
                    public void putInt(String key, int value) {
                        put(key, String.valueOf(value));
                    }
                };
        org.junit.Assume.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        AgentSettingsContribution contribution = extension.getSettingsContributions().get(0);
        // Settings → Plugins → "Settings…": no chat, no session, no model — the page still exists.
        assertTrue(contribution.createPluginSettingsComponent(store) != null);
    }

    @Test
    public void runtimeAndSearchSettingsAreNoArtifactsAnymore() {
        for (AgentArtifact artifact : ResearchArtifacts.all()) {
            assertFalse("settings are not work products: " + artifact.getId(),
                    "runtime".equals(artifact.getId()) || "search-settings".equals(artifact.getId()));
        }
        for (ArtifactViewContribution view : extension.getArtifactViews()) {
            assertFalse(ResearchArtifacts.TYPE_RUNTIME.equals(view.getArtifactTypeId()));
            assertFalse(ResearchArtifacts.TYPE_SEARCH_SETTINGS.equals(view.getArtifactTypeId()));
        }
    }

    @Test
    public void thereIsNoSettingsSlashCommand() {
        boolean found = false;
        for (ChatCommandContribution command : ResearchChatCommands.all()) {
            found |= "settings".equals(command.getDescriptor().getName());
        }
        assertFalse("settings are configured in the gear menu, never via /settings", found);
    }

    @Test
    public void workProductsStayInTheArtifactArea() {
        // The artifact area keeps exactly the work products + structured research views (issue #32,
        // minus the former visualization tab — the mindmap is a toolbar-button/'/map' overlay now:
        // brief, sources, document [outline in its drawer], state — no legacy per-stage tabs).
        assertTrue(ResearchArtifacts.all().size() == 4);
    }
}
