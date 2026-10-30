package com.aresstack.askai.research.mcp;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.plugin.api.agent.artifact.AgentArtifactStore;
import com.aresstack.askai.research.concept.ConceptBranchService;
import com.aresstack.askai.research.sources.InMemoryResearchSourceRepository;
import com.aresstack.askai.research.sources.ResearchSourceRepository;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.store.FileConceptStore;

import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The SMALL-MODEL facade over the concept (K2c): three tiny tools addressed by name paths —
 * no handles, no revisions to echo, examples in the descriptions, required arguments validated
 * before dispatch, and the same server-side phase re-check as every write tool. All change
 * semantics stay in ConceptBranchService.
 */
public class ConceptToolsPolicyTest {

    private ConceptBranchService service;
    private String phaseId = ResearchStateIds.SCOPING;
    private String stateId = ResearchStateIds.RUNNING;
    private int changeNotifications;
    private java.util.List<String> blacklist = java.util.Collections.emptyList();
    private final java.util.List<String> toolLog = new java.util.ArrayList<String>();

    private final ResearchControlContext ctx = new ResearchControlContext() {
        public String currentPhaseId() {
            return phaseId;
        }

        public String currentStateId() {
            return stateId;
        }

        public String statusLine() {
            return phaseId + "/" + stateId;
        }

        public AgentArtifactStore artifactStore() {
            return null;
        }

        public ResearchSourceRepository sourceRepository() {
            return InMemoryResearchSourceRepository.empty();
        }

        public String acceptCapture(String captureId) {
            return null;
        }

        @Override
        public ConceptBranchService conceptBranchService() {
            return service;
        }

        @Override
        public void onConceptChanged(long newWorkingRevision) {
            changeNotifications++;
        }

        @Override
        public java.util.List<String> blacklistedTerms() {
            return blacklist;
        }

        @Override
        public void conceptToolLog(String line) {
            toolLog.add(line);
        }

    };

    @Before
    public void freshService() throws Exception {
        File dir = Files.createTempDirectory("askai-concept-tools").toFile();
        service = new ConceptBranchService(new FileConceptStore(new File(dir, "concept")));
        changeNotifications = 0;
    }

    private McpToolContribution tool(String name) {
        for (McpToolContribution tool : ResearchToolPolicy.toolsFor(phaseId, stateId, ctx)) {
            if (name.equals(tool.getName())) {
                return tool;
            }
        }
        return null;
    }

    private static McpToolResult invoke(McpToolContribution tool, String... keyValues) {
        Map<String, Object> args = new HashMap<String, Object>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            args.put(keyValues[i], keyValues[i + 1]);
        }
        return tool.getHandler().invoke(new McpToolCall(tool.getName(), args));
    }

    // ------------------------------------------------------------------ visibility & contract

    @Test
    public void writingIsOfferedOnlyInScopingRunningButReadingEverywhere() {
        assertTrue(tool("concept_read") != null);
        assertTrue(tool("concept_add_cards") != null);
        assertTrue("concept_add stays for OLD runtimes only", tool("concept_add") != null);
        assertTrue(tool("concept_rename") != null);
        phaseId = ResearchStateIds.RESEARCH; // later phase: the concept is frozen but readable
        assertTrue(tool("concept_read") != null);
        assertNull(tool("concept_add_cards"));
        assertNull(tool("concept_add"));
        assertNull(tool("concept_rename"));
    }

    // ------------------------------------------------------------------ add_cards (atomic list)

    @Test
    public void addCardsAppliesAtomicallyAndTheReceiptNamesEveryInputsFate() {
        invoke(tool("concept_add_cards"), "names_json", "[\"Architektur\"]");
        blacklist = java.util.Collections.singletonList("platformio");
        McpToolResult result = invoke(tool("concept_add_cards"),
                "names_json", "[\"Grundlagen\",\"Entwicklungsumgebung\",\"Architektur\","
                        + "\"PlatformIO\"]");
        assertFalse(result.isError());
        String receipt = result.getText();
        assertTrue(receipt, receipt.startsWith("APPLIED revision=2"));
        assertTrue(receipt.contains("\nADDED: Grundlagen"));
        assertTrue(receipt.contains("\nADDED: Entwicklungsumgebung"));
        assertTrue(receipt.contains("\nALREADY_PRESENT: Architektur"));
        assertTrue("the blacklist stays authoritative — no visible card",
                receipt.contains("\nSUPPRESSED_BY_SCOPE: PlatformIO"));
        assertFalse(invoke(tool("concept_read")).getText().contains("PlatformIO"));
        assertEquals("one revision for the whole list", 2, changeNotifications);
    }

    /** Gate corrections: CREATED_PARENT + NO_CHANGE receipts, all lines in the technical log. */
    @Test
    public void addCardsReceiptsAreHonestAndFullyLogged() {
        McpToolResult created = invoke(tool("concept_add_cards"),
                "parent_path", "FreeRTOS", "names_json", "[\"Grundlagen\",\"Tasks\"]");
        assertFalse(created.isError());
        assertTrue(created.getText().startsWith("APPLIED revision=1"));
        assertTrue("the plausible parent is created WITH its cards, atomically",
                created.getText().contains("\nCREATED_PARENT: FreeRTOS"));
        assertTrue(toolLog.contains("concept_add_cards -> APPLIED revision=1"));
        assertTrue(toolLog.contains("concept_add_cards -> CREATED_PARENT: FreeRTOS"));
        assertTrue(toolLog.contains("concept_add_cards -> ADDED: Grundlagen"));
        assertTrue(toolLog.contains("concept_add_cards -> ADDED: Tasks"));

        toolLog.clear();
        McpToolResult repeat = invoke(tool("concept_add_cards"),
                "parent_path", "FreeRTOS", "names_json", "[\"Grundlagen\"]");
        assertFalse(repeat.isError());
        assertTrue("a batch without any new card is honest NO_CHANGE, never APPLIED",
                repeat.getText().startsWith("NO_CHANGE revision=1"));
        assertTrue(repeat.getText().contains("ALREADY_PRESENT: Grundlagen"));
        assertTrue(toolLog.contains("concept_add_cards -> NO_CHANGE revision=1"));
        assertTrue(toolLog.contains("concept_add_cards -> ALREADY_PRESENT: Grundlagen"));
        assertEquals("no change notification for a no-op", 1, changeNotifications);
    }

    /** move_leaf: receipts and refusals are fully mirrored into the technical log. */
    @Test
    public void moveLeafReceiptsAndRefusalsAreLoggedAndAtomic() {
        invoke(tool("concept_add_cards"), "parent_path", "Betriebssysteme",
                "names_json", "[\"Linux\",\"FreeRTOS\"]");
        invoke(tool("concept_add_cards"), "parent_path", "Linux",
                "names_json", "[\"Scheduling\"]");
        toolLog.clear();

        McpToolResult moved = invoke(tool("concept_move_leaf"),
                "source_json", "[\"Scheduling\"]", "parent_path_json", "[\"FreeRTOS\"]");
        assertFalse(moved.isError());
        assertTrue(moved.getText().startsWith("APPLIED revision=3"));
        assertTrue(moved.getText().contains("\nMOVED: Scheduling"));
        assertTrue(moved.getText().contains(
                "\nFROM: [Betriebssysteme, Linux, Scheduling]"));
        assertTrue(moved.getText().contains(
                "\nTO: [Betriebssysteme, FreeRTOS, Scheduling]"));
        assertTrue(moved.getText().contains("\nID: "));
        assertTrue(toolLog.contains("concept_move_leaf -> APPLIED revision=3"));
        assertTrue(toolLog.contains("concept_move_leaf -> MOVED: Scheduling"));
        assertEquals(3, changeNotifications);

        toolLog.clear();
        McpToolResult repeat = invoke(tool("concept_move_leaf"),
                "source_json", "[\"Scheduling\"]", "parent_path_json", "[\"FreeRTOS\"]");
        assertFalse(repeat.isError());
        assertTrue(repeat.getText().startsWith("NO_CHANGE revision=3"));
        assertTrue(repeat.getText().contains("\nALREADY_AT_TARGET: Scheduling"));
        assertTrue(toolLog.contains("concept_move_leaf -> NO_CHANGE revision=3"));
        assertEquals("a no-op move notifies nothing", 3, changeNotifications);

        // "Linux" became a leaf when Scheduling moved out — the BRANCH here is the root card.
        McpToolResult branch = invoke(tool("concept_move_leaf"),
                "source_json", "[\"Betriebssysteme\"]", "parent_path_json", "[]");
        assertTrue(branch.isError());
        assertTrue(branch.getText().contains("SOURCE_NOT_LEAF"));
        assertTrue(toolLog.toString().contains("concept_move_leaf -> REFUSED"));
        assertEquals("a refusal creates no revision", 3, changeNotifications);
    }

    @Test
    public void addCardsToleratesTechnicalListsButNeverSplitsBareSpaces() {
        // Newlines, bullets, semicolons and quotes are tolerated for technical lists …
        McpToolResult result = invoke(tool("concept_add_cards"),
                "names_json", "- Grundlagen\n* \"Computer Science\"\nTasks; Debugging");
        assertFalse(result.isError());
        assertTrue(result.getText().contains("ADDED: Computer Science"));
        assertTrue(result.getText().contains("ADDED: Tasks"));
        assertTrue(result.getText().contains("ADDED: Debugging"));
        // … and a multi-word name is ONE card, never split on spaces.
        String read = invoke(tool("concept_read")).getText();
        assertTrue(read, read.contains("\"Computer Science\""));
        assertFalse("never split on bare spaces", read.contains("\"Computer\""));
        McpToolResult missing = invoke(tool("concept_add_cards"));
        assertTrue(missing.isError());
        assertTrue(missing.getText().contains("multi-word names stay ONE name"));
    }

    /**
     * The safety slice's core pin: NO destructive model tool exists in ANY phase — removal is
     * the host-owned conflict flow only, rewrites wait for the plan workflow.
     */
    @Test
    public void destructiveToolsAreNeverOfferedAnywhere() {
        assertNull(tool("concept_remove"));
        assertNull(tool("concept_rewrite"));
        phaseId = ResearchStateIds.RESEARCH;
        assertNull(tool("concept_remove"));
        assertNull(tool("concept_rewrite"));
    }

    @Test
    public void withoutAServiceNoConceptToolExistsAtAll() {
        service = null;
        assertNull(tool("concept_read"));
        assertNull(tool("concept_add"));
        assertNull(tool("concept_rename"));
    }

    @Test
    public void theDescriptionsCarryConcreteExamplesTheMainframeMateWay() {
        assertTrue(tool("concept_add").getDescription().contains("name=\"<subtopic>\""));
        assertTrue(tool("concept_rename").getDescription().contains("name=\"<new name>\""));
        assertTrue(tool("concept_read").getDescription().contains("path=\"<card>/<child card>\""));
    }

    /**
     * Scoping regression (hasenhaltung yielded ESP32/FreeRTOS cards): a model-facing example shows the
     * SHAPE with placeholders, never a domain topic a small model could take for the user's.
     */
    @Test
    public void noModelFacingExampleCarriesADomainTopic() {
        String[] foreign = {"FreeRTOS", "ESP32", "ESP-IDF", "Arduino", "Priority Inversion", "Einstieg sichten"};
        for (String name : new String[] {"concept_read", "concept_add", "concept_add_cards", "concept_rename",
                "concept_move_leaf", "exclude_topic", "offer_searches", "scope_probe"}) {
            McpToolContribution tool = tool(name);
            for (String word : foreign) {
                assertFalse(name + " still shows " + word, tool != null && tool.getDescription().contains(word));
            }
        }
        String missingName = invoke(tool("concept_add"), "parent_path", "Setup").getText();
        for (String word : foreign) {
            assertFalse("the missing-argument example shows " + word, missingName.contains(word));
        }
    }

    // ------------------------------------------------------------------ the atomic flow

    @Test
    public void addReadRenameWorkByNamePathsWithoutAnyCeremony() {
        McpToolResult first = invoke(tool("concept_add"), "name", "FreeRTOS");
        assertFalse(first.isError());
        assertEquals("added \"FreeRTOS\" revision=1", first.getText());
        assertEquals(1, changeNotifications);

        McpToolResult sub = invoke(tool("concept_add"),
                "parent_path", "FreeRTOS", "name", "Kommunikation");
        assertFalse(sub.isError());
        McpToolResult subsub = invoke(tool("concept_add"),
                "parent_path", "FreeRTOS/Kommunikation", "name", "Task Notifications");
        assertFalse(subsub.isError());
        assertEquals("added \"Task Notifications\" revision=3", subsub.getText());

        McpToolResult read = invoke(tool("concept_read"), "path", "FreeRTOS/Kommunikation");
        assertFalse(read.isError());
        assertTrue(read.getText().contains("{\"Kommunikation\":[{\"Task Notifications\":[]}]}"));
        assertTrue("no handle line anywhere", !read.getText().contains("handle"));

        McpToolResult renamed = invoke(tool("concept_rename"),
                "path", "FreeRTOS/Kommunikation", "name", "IPC");
        assertFalse(renamed.isError());
        assertTrue(renamed.getText().startsWith("renamed to \"IPC\" revision=4"));
        assertEquals(4, changeNotifications);
    }

    @Test
    public void segmentArraysAreTheUnambiguousFormAndSlashStaysACharacterInNames() {
        // The gate's failure mode: a slash-joined path must never collapse into a literal root
        // name. With *_json segments, '/' in a NAME (TCP/IP) is just a character.
        assertFalse(invoke(tool("concept_add"), "name", "Netzwerk").isError());
        McpToolResult tcp = invoke(tool("concept_add"),
                "parent_path_json", "[\"Netzwerk\"]", "name", "TCP/IP");
        assertFalse(tcp.isError());
        McpToolResult read = invoke(tool("concept_read"), "path_json", "[\"Netzwerk\"]");
        assertTrue(read.getText().contains("{\"Netzwerk\":[{\"TCP/IP\":[]}]}"));
        // …and the segments form wins over the slash convenience when both are present.
        McpToolResult renamed = invoke(tool("concept_rename"),
                "path", "falsch/weg", "path_json", "[\"Netzwerk\",\"TCP/IP\"]",
                "name", "Netzwerkstack");
        assertFalse(renamed.isError());
        assertTrue(invoke(tool("concept_read"), "path_json", "[\"Netzwerk\"]")
                .getText().contains("{\"Netzwerk\":[{\"Netzwerkstack\":[]}]}"));
    }

    @Test
    public void requiredArgumentsAreValidatedBeforeDispatchWithAnExample() {
        McpToolResult noName = invoke(tool("concept_add"), "parent_path", "FreeRTOS");
        assertTrue(noName.isError());
        assertTrue(noName.getText().contains("Missing argument: name"));
        assertTrue("the error teaches by example", noName.getText().contains("name=\"<subtopic>\""));
        McpToolResult renameNoName = invoke(tool("concept_rename"), "path", "FreeRTOS");
        assertTrue(renameNoName.isError());
        assertTrue(renameNoName.getText().contains("Missing argument: name"));
        assertEquals(0, changeNotifications);
    }

    /** Slice-2 gate observability finding: the SUPPRESSED truth must reach the technical log. */
    @Test
    public void aReadOfABranchWithBlacklistedCardsLogsTheSuppressedLine() {
        invoke(tool("concept_add"), "name", "Setup");
        invoke(tool("concept_add"), "parent_path", "Setup", "name", "ESP-IDF");
        invoke(tool("concept_add"), "parent_path", "Setup", "name", "Toolchain");
        blacklist = java.util.Collections.singletonList("esp-idf");
        McpToolResult read = invoke(tool("concept_read"), "path", "Setup");
        assertTrue(read.getText().contains("SUPPRESSED IN THIS BRANCH"));
        assertTrue(read.getText().contains("ESP-IDF"));
        assertEquals("concept_read -> SUPPRESSED IN THIS BRANCH: ESP-IDF", toolLog.get(0));
        toolLog.clear();
        blacklist = java.util.Collections.emptyList();
        invoke(tool("concept_read"), "path", "Setup");
        assertTrue("no suppression, no log noise", toolLog.isEmpty());
    }

    @Test
    public void duplicatesAndUnknownParentsComeBackAsTeachingDiagnostics() {
        invoke(tool("concept_add"), "name", "FreeRTOS");
        McpToolResult duplicate = invoke(tool("concept_add"), "name", "FreeRTOS");
        assertTrue(duplicate.isError());
        assertTrue(duplicate.getText().contains("already exists"));

        McpToolResult orphan = invoke(tool("concept_add"),
                "parent_path", "Gibtsnicht", "name", "X");
        assertTrue(orphan.isError());
        assertTrue(orphan.getText().startsWith("TARGET_NODE_NOT_FOUND"));
        assertEquals("failures never notify", 1, changeNotifications);
    }

    // ------------------------------------------------------------------ authorization re-check

    @Test
    public void aPhaseTransitionBetweenListAndCallIsCaughtAtExecutionTime() {
        McpToolContribution add = tool("concept_add"); // offered while SCOPING/running…
        phaseId = ResearchStateIds.RESEARCH; // …but the phase moved on before the call arrived
        McpToolResult result = invoke(add, "name", "FreeRTOS");
        assertTrue(result.isError());
        assertTrue(result.getText().contains("Not allowed in the current state"));
        assertEquals(0, changeNotifications);
    }
}
