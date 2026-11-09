package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.ResearchRoles;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Issue #152: the outline label and brief roles were built inline without a prompt source, so the first
 * "Generate outline" failed with "role outline-labels declares no prompt source". The roles now have one home,
 * and every declared phase role (runtime inventory plus the plugin's outline roles) must declare its prompt
 * source and output.
 */
public class OutlineBriefRolesTest {

    @Test
    public void outlineRolesDeclarePromptSourceOutputAndTheirSlots() {
        assertEquals("outline-labels", OutlineBriefWorker.LABELS_ROLE.getId());
        assertEquals(ResearchModelSlots.PHASE_OUTLINE_LABELS, OutlineBriefWorker.LABELS_ROLE.getSlot());
        assertEquals("outline-briefs", OutlineBriefWorker.BRIEFS_ROLE.getId());
        assertEquals(ResearchModelSlots.PHASE_OUTLINE_BRIEFS, OutlineBriefWorker.BRIEFS_ROLE.getSlot());
        for (ResearchRole role : OutlineBriefWorker.roles()) {
            assertEquals(role.getId(), ResearchRole.PromptSource.CALLER, role.getPromptSource());
            assertEquals(role.getId(), ThinkingMode.OFF, role.getThinking());
            assertNotNull(role.getId(), role.getOutput());
        }
    }

    @Test
    public void everyDeclaredPhaseRoleHasAPromptSourceAndAnOutput() {
        List<ResearchRole> roles = new ArrayList<ResearchRole>(
                ResearchRoles.all(PhaseAssistantProfileRegistry.defaults(true, true)));
        roles.addAll(OutlineBriefWorker.roles());
        Set<String> ids = new HashSet<String>();
        for (ResearchRole role : roles) {
            assertTrue("duplicate role id " + role.getId(), ids.add(role.getId()));
            assertNotNull(role.getId() + " declares no prompt source", role.getPromptSource());
            assertNotNull(role.getId() + " declares no output", role.getOutput());
            if (role.getPromptSource() == ResearchRole.PromptSource.ROLE) {
                assertTrue(role.getId() + " has a blank system prompt", !role.getSystemPrompt().trim().isEmpty());
            }
        }
        assertTrue(ids.contains("outline-labels"));
        assertTrue(ids.contains("outline-briefs"));
    }
}
