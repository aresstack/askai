package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.topics.TopicMerger.Comparison;
import com.aresstack.askai.research.topics.TopicMerger.Relation;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 15 on disk: one line per doubtful case, appended only, read back as written. */
public class TopicMergeLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aCaseSurvivesTheRoundTrip() throws Exception {
        File project = tmp.newFolder("project");
        TopicMergeLog log = new TopicMergeLog(project);
        assertTrue(log.open().isEmpty());
        Comparison ok = new Comparison(Comparison.Kind.THEMA, "vs-r1-s3-w1-1", "Verkehrsberuhigung", "thema-0001",
                "Tempo 30\nAuch so genannt: Tempo-30-Zonen", true, 0.8132, Comparison.Status.OK,
                Relation.A_TEIL_VON_B, "Tempo 30 ist ein Mittel der Verkehrsberuhigung.", "",
                Arrays.asList("{\"beziehung\":\"a_teil_von_b\"}"), "gemma4:12b", 10L, 1234L);
        Comparison failed = new Comparison(Comparison.Kind.FRAGE, "vs-r1-s3-w1-1-f1", "Wie viele?", "F02",
                "Wie oft?", true, 0.88, Comparison.Status.FEHLER, null, "", "model call failed (TIMEOUT): slow",
                Arrays.asList("", ""), "gemma4:12b", 11L, 30000L);
        Comparison none = new Comparison(Comparison.Kind.KARTE, "vs-r2-s1-w1-2", "Bahnlärm", "",
                "konzept-1: Radwege\nkonzept-2: Parken", true, 0.41, Comparison.Status.OK, Relation.VERSCHIEDEN,
                "Keine Karte.", "", null, "gemma4:12b", 12L, 900L);
        Comparison parent = new Comparison(Comparison.Kind.OBERTHEMA, "vs-r1-s4-w1-1",
                "T1: Fahrradstraßen\nT2: Radschnellwege\nT3: Radwegbreite", "", Comparison.PARENT_MARK, false, 0.7,
                Comparison.Status.OK, Relation.A_TEIL_VON_B, "Alles Wege für den Radverkehr.", "",
                Arrays.asList("{\"oberthema\":\"Radwege\"}"), "gemma4:12b", 13L, 800L,
                TopicMerger.versionOf(Comparison.Kind.OBERTHEMA), "new parent \u201eRadwege\u201c of T1, T2, T3");
        log.appendAll(Arrays.asList(ok, failed, none, parent));

        List<Comparison> read = new TopicMergeLog(project).open();
        assertEquals(4, read.size());
        Comparison formed = read.get(3);
        assertEquals(Comparison.Kind.OBERTHEMA, formed.getKind());
        assertEquals("new parent \u201eRadwege\u201c of T1, T2, T3", formed.getNote());
        assertEquals(4, formed.getVersion());
        assertEquals(parent.key(), formed.key());
        assertEquals("the code's note is empty where it read nothing", "", read.get(0).getNote());
        assertEquals("two topics keep the prompt of version 3", 3, read.get(0).getVersion());
        Comparison card = read.get(2);
        assertEquals(Comparison.Kind.KARTE, card.getKind());
        assertEquals("a card case without a card", "", card.getBId());
        assertEquals(Relation.VERSCHIEDEN, card.getRelation());
        assertEquals(none.key(), card.key());
        Comparison back = read.get(0);
        assertEquals(Comparison.Kind.THEMA, back.getKind());
        assertEquals("vs-r1-s3-w1-1", back.getAId());
        assertEquals("Verkehrsberuhigung", back.getAText());
        assertEquals("thema-0001", back.getBId());
        assertEquals("Tempo 30\nAuch so genannt: Tempo-30-Zonen", back.getBText());
        assertTrue(back.isBKnown());
        assertEquals(0.8132, back.getSimilarity(), 1e-12);
        assertEquals(Relation.A_TEIL_VON_B, back.getRelation());
        assertEquals("Tempo 30 ist ein Mittel der Verkehrsberuhigung.", back.getReason());
        assertEquals("gemma4:12b", back.getModel());
        assertEquals(10L, back.getTimeMillis());
        assertEquals(1234L, back.getMillis());
        assertEquals(ok.key(), back.key());
        Comparison second = read.get(1);
        assertFalse(second.isOk());
        assertNull(second.getRelation());
        assertEquals("model call failed (TIMEOUT): slow", second.getProblem());
        assertEquals(Arrays.asList("", ""), second.getAnswers());
        assertEquals("only OK verdicts are reused", 3, TopicMerger.reusable(read).size());
        String line = new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8).split("\n")[0];
        assertTrue(line, line.startsWith("{\"v\":2,\"art\":\"vergleich\",\"typ\":\"THEMA\""));
        assertTrue(line, line.contains("\"fassung\":3,\"vermerk\":\"\""));

        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"vergleich\",\"typ\":\"KARTE\",\"aId\":\"x\","
                + "\"aText\":\"Bahnlärm\",\"bId\":\"\",\"bText\":\"konzept-1: Radwege\",\"bBekannt\":true,"
                + "\"aehnlichkeit\":0.4,"
                + "\"status\":\"OK\",\"beziehung\":\"VERSCHIEDEN\",\"grund\":\"\",\"fehler\":\"\",\"modell\":\"m\","
                + "\"zeit\":1,\"ms\":1,\"fassung\":3,\"rohantworten\":[]}\n");
        List<Comparison> older = new TopicMergeLog(project).open();
        assertEquals("a line of version 1 is read: no note", "", older.get(4).getNote());
        assertEquals(3, older.get(4).getVersion());
        assertEquals("a card verdict under the card prompt of version 3 is not reused", 3,
                TopicMerger.reusable(older).size());
        assertEquals("topic-merge", log.logFile().getParentFile().getName());
    }

    @Test
    public void writingBeforeOpeningIsRefusedAndALaterLineIsCutOnlyWhenUnfinished() throws Exception {
        File project = tmp.newFolder("project");
        TopicMergeLog log = new TopicMergeLog(project);
        Comparison ok = new Comparison(Comparison.Kind.THEMA, "a", "A", "b", "B", false, 0.8, Comparison.Status.OK,
                Relation.GLEICH, "", "", null, "m", 1L, 1L);
        try {
            log.append(ok);
            fail("the version check comes first");
        } catch (IllegalStateException expected) {
            assertFalse(log.logFile().exists());
        }
        log.open();
        log.append(ok);
        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"vergl");
        assertEquals(1, new TopicMergeLog(project).open().size());
        assertTrue(new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8).endsWith("}\n"));

        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"fenster\"}\n");
        try {
            new TopicMergeLog(project).open();
            fail("another kind of record");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("record 2"));
        }
    }

    @Test
    public void aNewerVersionStopsWithoutTouchingTheFile() throws Exception {
        File project = tmp.newFolder("project");
        TopicMergeLog log = new TopicMergeLog(project);
        log.open();
        log.append(new Comparison(Comparison.Kind.FRAGE, "a", "A?", "b", "B?", false, 0.9, Comparison.Status.OK,
                Relation.VERSCHIEDEN, "", "", null, "m", 1L, 1L));
        appendRaw(log.logFile(), "{\"v\":3,\"art\":\"vergleich\"}\n{\"v\":1,\"art\":\"ver");
        byte[] before = Files.readAllBytes(log.logFile().toPath());
        try {
            new TopicMergeLog(project).open();
            fail("newer");
        } catch (TopicMergeLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 has version 3"));
        }
        assertArrayEquals(before, Files.readAllBytes(log.logFile().toPath()));

        File moved = tmp.newFolder("moved");
        TopicMergeLog next = new TopicMergeLog(moved);
        File pointer = new File(next.logFile().getParentFile(), "current");
        Files.createDirectories(pointer.getParentFile().toPath());
        Files.write(pointer.toPath(), "log-2.jsonl\n".getBytes(StandardCharsets.UTF_8));
        try {
            next.open();
            fail("a newer generation");
        } catch (TopicMergeLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'log-2.jsonl'"));
        }
    }

    private static void appendRaw(File file, String text) throws Exception {
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }
}
