package com.aresstack.askai.research.agent;

import com.aresstack.askai.plugin.api.agent.command.ChatCommandContribution;
import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertTrue;

/**
 * The slash surface mirrors the semantic command vocabulary: every red action tag has a typed
 * {@code /command} twin (issue #36 follow-up — "Fragestellung freigeben" was tag-only before).
 */
public class ResearchChatCommandsTest {

    @Test
    public void exactlyTheRedDecisionTagsHaveASlashTwin() {
        Set<String> names = new HashSet<String>();
        for (ChatCommandContribution contribution : ResearchChatCommands.all()) {
            names.add(contribution.getDescriptor().getName());
        }
        // The text adapters…
        assertTrue(names.contains("search"));
        assertTrue(names.contains("open"));
        assertTrue("the mindmap overlay's typed twin", names.contains("map"));
        // …and the red DECISION tags' twins — "Fragestellung freigeben & weiter" (submit-scope)
        // and "Neue Quellen auswerten" (review-sources) were tag-only once, never again.
        for (String command : ResearchChatCommands.processorCommandNames()) {
            assertTrue("missing slash twin for red decision tag: " + command,
                    names.contains(command));
        }
        // The way back is a user control on every surface: a bot-driven or typed session can reopen
        // an earlier phase without a click on the phase pill (live run 3, 2026-11-10).
        for (String reopen : new String[]{"reopen-concept", "reopen-sources", "reopen-outline"}) {
            assertTrue("missing slash twin for the reopen click: " + reopen, names.contains(reopen));
            assertTrue(ResearchSemanticCommands.isSemantic(reopen));
        }
        // The interruption machinery is DOMAIN state, not user furniture: no slash surface for it.
        for (String machinery : new String[]{"pause", "resume", "retry", "cancel"}) {
            assertTrue("interrupt machinery must not be a slash command: " + machinery,
                    !names.contains(machinery));
        }
    }

    @Test
    public void theSemanticTableRoundTrips() {
        // The reverse lookup is what makes phase plates clickable — it must stay consistent with
        // the forward candidates (one table, no drift).
        for (String name : ResearchSemanticCommands.names()) {
            for (com.aresstack.askai.research.state.ResearchCommandType type
                    : ResearchSemanticCommands.candidates(name)) {
                assertTrue(name.equals(ResearchSemanticCommands.semanticNameFor(type)));
            }
        }
    }
}
