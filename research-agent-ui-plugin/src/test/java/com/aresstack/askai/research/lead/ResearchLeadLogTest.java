package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static com.aresstack.askai.research.lead.ResearchLeadTest.MISSION;
import static com.aresstack.askai.research.lead.ResearchLeadTest.answerAsking;
import static com.aresstack.askai.research.lead.ResearchLeadTest.lead;
import static com.aresstack.askai.research.lead.ResearchLeadTest.memory;
import static com.aresstack.askai.research.lead.ResearchLeadTest.questionsFor;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@code research-lead/log.jsonl}: one line per round, the version rule, and a damaged file fails closed. */
public class ResearchLeadLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void eachRoundIsOneLineWithTasksNotesAndTheOpenList() throws Exception {
        File project = tmp.newFolder("projekt");
        ResearchLeadLog log = new ResearchLeadLog(project);
        assertTrue(log.open().isEmpty());
        ResearchMemory memory = memory();
        ResearchLeadTest.Scripted chat = new ResearchLeadTest.Scripted(messages -> MainModelChatResult.ok(
                answerAsking("weiter", "los", new String[]{questionsFor("thema-0001", "Erfahrungen",
                        "Wie viele Städte haben Schulstraßen eingerichtet?")})));
        LeadPlan first = lead(chat).plan(memory, MISSION, "");
        log.append(first, 100L);
        LeadPlan end = new ResearchLead(chat, "de", ResearchLead.Options.defaults(),
                new ResearchLead.Budget(1, 100, 4, 3), null, ResearchLeadTest.CLOCK).plan(memory, MISSION, "");
        log.append(end, 200L);

        List<JsonObject> rounds = new ResearchLeadLog(project).open();
        assertEquals(2, rounds.size());
        JsonObject one = rounds.get(0);
        assertEquals(1, one.get("runde").getAsInt());
        assertEquals("MODELL", one.get("status").getAsString());
        assertFalse(one.get("ende").getAsBoolean());
        assertEquals("test-model", one.get("modell").getAsString());
        assertEquals("Lage: zwei Fragen ohne Beleg.", one.get("lage").getAsString());
        assertEquals(4, one.getAsJsonArray("aufgaben").size());
        JsonObject task = one.getAsJsonArray("aufgaben").get(0).getAsJsonObject();
        assertEquals("THEMA", task.get("ziel").getAsString());
        assertEquals("[\"leitung-r1-1\"]", task.getAsJsonArray("fragen").toString());
        assertEquals("standard", task.get("strategie").getAsString());
        assertEquals("MODELL", task.get("herkunft").getAsString());
        assertEquals("[\"leitung-r1-1\"]", one.getAsJsonArray("neueFragen").toString());
        assertEquals(12, one.get("suchbudget").getAsInt());
        assertEquals(1, one.getAsJsonArray("rohantworten").size());
        assertEquals(100L, one.get("zeit").getAsLong());
        JsonObject two = rounds.get(1);
        assertEquals("REGEL", two.get("status").getAsString());
        assertTrue(two.get("ende").getAsBoolean());
        assertEquals(5, two.getAsJsonObject("offeneListe").get("offen").getAsInt());
        assertEquals(1, two.getAsJsonObject("offeneListe").get("autor").getAsInt());
        assertEquals("Budget verbraucht: alle 1 Runden sind gelaufen",
                two.getAsJsonObject("offeneListe").get("grund").getAsString());

        try {
            new ResearchLeadLog(tmp.newFolder("geschlossen")).append(first, 1L);
            fail("writing needs the log opened first");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("open"));
        }
    }

    @Test
    public void aNewerVersionStopsAndADamagedLineFailsClosed() throws Exception {
        File project = tmp.newFolder("neuer");
        ResearchLeadLog log = new ResearchLeadLog(project);
        write(log.logFile(), "{\"v\":1,\"art\":\"runde\",\"runde\":1}\n{\"v\":2,\"art\":\"runde\",\"runde\":2}\n");
        try {
            log.open();
            fail("a newer version must stop the opening");
        } catch (ResearchLeadLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 has version 2"));
        }

        File pointed = tmp.newFolder("zeiger");
        write(new File(new File(pointed, "research-lead"), "current"), "g2/log.jsonl");
        try {
            new ResearchLeadLog(pointed).open();
            fail("a pointer to another generation must stop the opening");
        } catch (ResearchLeadLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'g2/log.jsonl'"));
        }

        File damaged = tmp.newFolder("kaputt");
        ResearchLeadLog broken = new ResearchLeadLog(damaged);
        write(broken.logFile(), "{\"v\":1,\"art\":\"runde\",\"runde\":1}\nkein json\n");
        try {
            broken.open();
            fail("a damaged line must fail closed");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2"));
        }

        File cut = tmp.newFolder("abgebrochen");
        ResearchLeadLog unfinished = new ResearchLeadLog(cut);
        write(unfinished.logFile(), "{\"v\":1,\"art\":\"runde\",\"runde\":1}\n{\"v\":1,\"art\":\"ru");
        assertEquals(1, unfinished.open().size());
        assertEquals("{\"v\":1,\"art\":\"runde\",\"runde\":1}\n", new String(Files.readAllBytes(
                unfinished.logFile().toPath()), StandardCharsets.UTF_8));
    }

    private static void write(File file, String content) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
