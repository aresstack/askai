package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.research.lead.SearchStrategy;
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
import java.util.Arrays;
import java.util.List;

import static com.aresstack.askai.research.searchplanner.SearchPlannerTest.MISSION;
import static com.aresstack.askai.research.searchplanner.SearchPlannerTest.answer;
import static com.aresstack.askai.research.searchplanner.SearchPlannerTest.memory;
import static com.aresstack.askai.research.searchplanner.SearchPlannerTest.planner;
import static com.aresstack.askai.research.searchplanner.SearchPlannerTest.search;
import static com.aresstack.askai.research.searchplanner.SearchPlannerTest.task;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** {@code search-planner/log.jsonl}: one line per task, the version rule, and a damaged file fails closed. */
public class SearchPlannerLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void eachTaskIsOneLineWithItsSearchesAndWhatWasNotTaken() throws Exception {
        File project = tmp.newFolder("projekt");
        SearchPlannerLog log = new SearchPlannerLog(project);
        assertTrue(log.open().isEmpty());
        ResearchMemory memory = memory();
        SearchPlannerTest.Scripted chat = new SearchPlannerTest.Scripted(messages -> {
            if (messages.get(1).getContent().contains("- F02 ")) {
                return MainModelChatResult.ok("kein JSON");
            }
            return MainModelChatResult.ok(answer(search("F01", "dieselbe Suche noch einmal", "Tempo 30 Unfälle"),
                    search("F01", "Unfallzahlen nach Einführung von Tempo 30", "Tempo 30 Unfallstatistik 2023")));
        });
        RoundPlan round = planner(chat).plan(memory, Arrays.asList(task("F01", SearchStrategy.ANDERE_WORTWAHL,
                "Versicherer"), task("F02", SearchStrategy.STANDARD, "")), MISSION, 12);
        log.append(round, 100L);

        List<JsonObject> tasks = new SearchPlannerLog(project).open();
        assertEquals(2, tasks.size());
        JsonObject one = tasks.get(0);
        assertEquals("aufgabe", one.get("art").getAsString());
        assertEquals(2, one.get("runde").getAsInt());
        assertEquals("FRAGE", one.get("ziel").getAsString());
        assertEquals("F01", one.get("zielId").getAsString());
        assertEquals("[\"F01\"]", one.getAsJsonArray("fragen").toString());
        assertEquals("andere_wortwahl", one.get("strategie").getAsString());
        assertEquals("MODELL", one.get("status").getAsString());
        assertEquals("test-model", one.get("modell").getAsString());
        assertEquals(1, one.getAsJsonArray("suchen").size());
        JsonObject taken = one.getAsJsonArray("suchen").get(0).getAsJsonObject();
        assertEquals("suche-r2-1", taken.get("id").getAsString());
        assertEquals("F01", taken.get("frage").getAsString());
        assertEquals("the query itself is logged too", "Tempo 30 Unfallstatistik 2023",
                taken.get("anfrage").getAsString());
        assertEquals("Unfallzahlen nach Einführung von Tempo 30", taken.get("zweck").getAsString());
        JsonObject refused = one.getAsJsonArray("verworfen").get(0).getAsJsonObject();
        assertEquals("Tempo 30 Unfälle", refused.get("anfrage").getAsString());
        assertEquals("wiederholt die Suche „Tempo 30 Unfälle“", refused.get("grund").getAsString());
        assertEquals("", one.get("fehler").getAsString());
        assertEquals(1, one.getAsJsonArray("rohantworten").size());
        assertEquals(100L, one.get("zeit").getAsLong());
        JsonObject two = tasks.get(1);
        assertEquals("FEHLER", two.get("status").getAsString());
        assertEquals("invalid answer: the answer is no JSON", two.get("fehler").getAsString());
        assertEquals(2, two.getAsJsonArray("rohantworten").size());
        assertEquals(0, two.getAsJsonArray("suchen").size());

        try {
            new SearchPlannerLog(tmp.newFolder("geschlossen")).append(round, 1L);
            fail("writing needs the log opened first");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("open"));
        }
    }

    @Test
    public void aNewerVersionStopsAndADamagedLineFailsClosed() throws Exception {
        File project = tmp.newFolder("neuer");
        SearchPlannerLog log = new SearchPlannerLog(project);
        write(log.logFile(), "{\"v\":1,\"art\":\"aufgabe\",\"runde\":1}\n{\"v\":2,\"art\":\"aufgabe\",\"runde\":2}\n");
        try {
            log.open();
            fail("a newer version must stop the opening");
        } catch (SearchPlannerLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 has version 2"));
        }

        File pointed = tmp.newFolder("zeiger");
        write(new File(new File(pointed, "search-planner"), "current"), "g2/log.jsonl");
        try {
            new SearchPlannerLog(pointed).open();
            fail("a pointer to another generation must stop the opening");
        } catch (SearchPlannerLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'g2/log.jsonl'"));
        }

        File damaged = tmp.newFolder("kaputt");
        SearchPlannerLog broken = new SearchPlannerLog(damaged);
        write(broken.logFile(), "{\"v\":1,\"art\":\"aufgabe\",\"runde\":1}\n{\"v\":1,\"art\":\"runde\"}\n");
        try {
            broken.open();
            fail("an unknown record kind must fail closed");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2"));
        }

        File cut = tmp.newFolder("abgebrochen");
        SearchPlannerLog unfinished = new SearchPlannerLog(cut);
        write(unfinished.logFile(), "{\"v\":1,\"art\":\"aufgabe\",\"runde\":1}\n{\"v\":1,\"art\":\"auf");
        assertEquals(1, unfinished.open().size());
        assertEquals("{\"v\":1,\"art\":\"aufgabe\",\"runde\":1}\n", new String(Files.readAllBytes(
                unfinished.logFile().toPath()), StandardCharsets.UTF_8));
    }

    private static void write(File file, String content) throws Exception {
        Files.createDirectories(file.getParentFile().toPath());
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
