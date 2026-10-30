package com.aresstack.askai.research.runtime.role;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.DefaultPhaseOutputContract;
import com.aresstack.askai.research.runtime.team.FakeModelPort;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfile;
import com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry;
import com.aresstack.askai.research.runtime.team.PortMainModelChat;
import com.aresstack.askai.research.runtime.team.TeamAgentPlaybook;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * The five existing roles on the role interface (ADR 0001, lane D). The prompts must stay byte for byte what
 * they were before the migration: the SHA-256 fingerprints below were taken from the role classes on
 * {@code adr0001/c} (commit 07724a3) before any change, so a prompt edit fails here and must be a deliberate,
 * separately reviewed change.
 */
public class ResearchRolesTest {

    @Test
    public void workerPromptsAreByteIdenticalToBeforeTheMigration() throws Exception {
        assertEquals("c635bf434472a59cc545096806a74bb4b73b3fc6d3e84e5d18786fc9bdea859b",
                sha256(ResearchRoles.PAGE_READINESS_JUDGE.getSystemPrompt()));
        assertEquals("af220d232024761b60bb6f21be6dbac4819072804f3b349b2d3b3c205d09f161",
                sha256(ResearchRoles.SCOPE_PROBE_GENERATOR.getSystemPrompt()));
        assertEquals("9cff2f491ba8657f36f8d089439319da753413cf48a2311caee6734984f9a3b8",
                sha256(ResearchRoles.SCOPE_ADVICE_CHOOSER.getSystemPrompt()));
    }

    @Test
    public void conversationPromptsAreByteIdenticalToBeforeTheMigration() throws Exception {
        assertEquals("0989113034c7a54ef97d392df1de9f6555038e691c30d8c1dfe3848d126b3944",
                sha256(ResearchRoles.CONVERSATION.getSystemPrompt()));
        assertEquals("923bf0c9c627985c1b6f92f5911152b66c066667a21d0c3c0ce9c93a4a61e460",
                sha256(scopingRole(false, false).getSystemPrompt()));
        assertEquals("e530d927cb752ce78b7f8b579f91f4d4fdf2a4b3802c89a1b1f27fb07664b803",
                sha256(scopingRole(false, true).getSystemPrompt()));
        assertEquals("8ea29310898bf035aa70e9bd2f0f9f5e91452c0c9fdc526abfbf80eefaa363e6",
                sha256(scopingRole(true, false).getSystemPrompt()));
        assertEquals("fca2ee637a7624f0204878dad3065cd384f01130772783b00bac6813a7238ee2",
                sha256(scopingRole(true, true).getSystemPrompt()));
    }

    @Test
    public void everyRoleNamesTheSlotItUsedBefore() {
        assertEquals(ResearchModelSlots.CONVERSATION, ResearchRoles.CONVERSATION.getSlot());
        assertEquals(ResearchModelSlots.SCOPE, ResearchRoles.SCOPE_PROBE_GENERATOR.getSlot());
        assertEquals(ResearchModelSlots.SCOPE, ResearchRoles.SCOPE_ADVICE_CHOOSER.getSlot());
        assertEquals(ResearchModelSlots.JUDGE, ResearchRoles.PAGE_READINESS_JUDGE.getSlot());
        assertEquals(ResearchModelSlots.LAYOUT_REPAIR, ResearchRoles.SERP_LAYOUT_REPAIR.getSlot());
        assertEquals(ResearchRole.PromptSource.CALLER, ResearchRoles.SERP_LAYOUT_REPAIR.getPromptSource());
        for (ResearchRole role : ResearchRoles.all(PhaseAssistantProfileRegistry.defaults())) {
            assertEquals("no role hides a context constant: 0 = the slot default (a platform setting)",
                    0, role.getContextTokens());
        }
    }

    @Test
    public void theWorkerRolesUseNoTools() {
        for (ResearchRole role : ResearchRoles.workers()) {
            assertTrue(role.getId(), role.getTools().isEmpty());
        }
    }

    @Test
    public void roleIdsAreUnique() {
        List<ResearchRole> roles = ResearchRoles.all(PhaseAssistantProfileRegistry.defaults(true, true));
        Set<String> ids = new HashSet<String>();
        for (ResearchRole role : roles) {
            assertTrue("duplicate role id " + role.getId(), ids.add(role.getId()));
        }
        assertTrue(ids.containsAll(Arrays.asList("conversation", "conversation.scoping", "conversation.research",
                "conversation.draft", "conversation.finalization", "scope-probe-generator",
                "scope-advice-chooser", "page-readiness-judge", "serp-layout-repair")));
    }

    @Test
    public void researchDraftAndFinalizationHaveTheirOwnProfiles() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        PhaseAssistantProfile research = registry.forPhase("research");
        PhaseAssistantProfile draft = registry.forPhase("draft");
        PhaseAssistantProfile finalization = registry.forPhase("FINALIZATION");

        assertEquals(TeamAgentPlaybook.researchSystemPrompt(), research.getSystemPrompt());
        assertEquals(TeamAgentPlaybook.draftSystemPrompt(), draft.getSystemPrompt());
        assertEquals("both document phases work on the one canonical document",
                draft.getSystemPrompt(), finalization.getSystemPrompt());
        assertNotEquals(registry.fallback().getSystemPrompt(), research.getSystemPrompt());
        assertNotEquals(research.getSystemPrompt(), draft.getSystemPrompt());
        for (PhaseAssistantProfile profile : Arrays.asList(research, draft, finalization)) {
            assertTrue("the generic turn parser still reads the answer",
                    profile.getOutputContract() instanceof DefaultPhaseOutputContract);
            assertTrue(profile.getSystemPrompt().contains("\"assistantMessage\": string"));
            assertEquals(ResearchModelSlots.CONVERSATION, profile.getRole().getSlot());
            assertEquals("", profile.getWritableArtifactId());
            assertEquals(new HashSet<String>(profile.getRole().getTools().getToolNames()),
                    profile.getAllowedCapabilities());
            for (String tool : profile.getAllowedCapabilities()) {
                assertFalse("read-only: " + tool, tool.endsWith("_save") || tool.endsWith("_accept")
                        || tool.endsWith("_park") || tool.startsWith("concept_add"));
            }
        }
        assertTrue(research.getAllowedCapabilities().contains("source_review_context"));
        assertTrue(draft.getAllowedCapabilities().contains("document_read"));
        assertEquals("outline keeps the neutral fallback", registry.fallback().getSystemPrompt(),
                registry.forPhase("outline").getSystemPrompt());
    }

    @Test
    public void theNewPhasePromptsNeverHandThePhaseToTheModel() {
        for (String prompt : Arrays.asList(TeamAgentPlaybook.researchSystemPrompt(),
                TeamAgentPlaybook.draftSystemPrompt())) {
            assertTrue(prompt.contains("Never talk about internal machinery"));
            assertTrue(prompt.contains("gatekeeper"));
            assertFalse(prompt.contains("proposedCommand"));
        }
    }

    @Test
    public void aRoleChatSendsTheRoleSlotAndContextWithEveryCall() throws Exception {
        FakeModelPort port = new FakeModelPort();
        try {
            port.enqueueRaw("READABLE");
            PortMainModelChat chat = PortMainModelChat.forRole(port,
                    ResearchRoles.PAGE_READINESS_JUDGE.withContextTokens(4096), null);

            assertTrue(chat.complete(Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u")), 0.0, 24)
                    .isOk());

            ChatRequest request = port.requests().get(0);
            assertEquals(ResearchModelSlots.JUDGE, request.getSlot());
            assertEquals(4096, request.getContextTokens());
        } finally {
            port.close();
        }
    }

    @Test
    public void theDefaultRoleChatLeavesTheContextToTheSlot() throws Exception {
        FakeModelPort port = new FakeModelPort();
        try {
            port.enqueueRaw("{}");
            PortMainModelChat.forRole(port, ResearchRoles.SCOPE_PROBE_GENERATOR, null)
                    .completeJson(Arrays.asList(ChatMessage.user("u")), 0.2, 100, null);
            assertEquals(0, port.requests().get(0).getContextTokens());
        } finally {
            port.close();
        }
    }

    private static ResearchRole scopingRole(boolean alwaysSuggest, boolean conceptTools) {
        return PhaseAssistantProfileRegistry.defaults(alwaysSuggest, conceptTools).forPhase("scoping").getRole();
    }

    private static String sha256(String text) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
