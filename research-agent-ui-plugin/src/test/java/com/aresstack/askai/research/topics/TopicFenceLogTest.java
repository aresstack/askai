package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.memory.ResearchTopic.Fence;
import com.aresstack.askai.research.topics.TopicFenceCheck.Brief;
import com.aresstack.askai.research.topics.TopicFenceCheck.Verdict;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 14 on disk: the briefs once per basis and one line per verdict, appended only, read back as written. */
public class TopicFenceLogTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void briefsAndVerdictsSurviveTheRoundTrip() throws Exception {
        File project = tmp.newFolder("project");
        TopicFenceLog log = new TopicFenceLog(project);
        assertTrue(log.open().isEmpty());
        Brief brief = new Brief(TopicFenceCheckTest.MISSION, "Dörfer zählen, wenn übertragbar.",
                Collections.singletonList("Lieferverkehr"), Arrays.asList("Flugverkehr", "Autobahnen"), "Deutschland",
                "ab 2015", TopicFenceCheckTest.CARDS);
        log.appendBrief(brief);
        log.appendBrief(brief);
        Verdict ok = new Verdict("thema-0001", "Lieferverkehr", "k1", brief.basis(), Verdict.Status.OK, Fence.UNKLAR,
                "Am Rand.", "", Collections.singletonList("{\"grund\":\"Am Rand.\",\"urteil\":\"unklar\"}"),
                Verdict.Origin.MODELL, "gemma4:12b", 10L, 2345L);
        Verdict failed = new Verdict("thema-0002", "Bahnlärm", "k2", brief.basis(), Verdict.Status.FEHLER, null, "",
                "model call failed (TIMEOUT): slow", Arrays.asList("", ""), Verdict.Origin.MODELL, "gemma4:12b", 11L,
                30000L);
        Verdict author = new Verdict("thema-0001", "Lieferverkehr", "k1", brief.basis(), Verdict.Status.OK,
                Fence.INNEN, "Gehört dazu.", "", null, Verdict.Origin.NUTZER, "", 12L, 0L);
        Verdict menu = new Verdict("thema-0003", "Startseite Kontakt Suche", "k3", brief.basis(), Verdict.Status.OK,
                Fence.AUSSEN, TopicFenceCheck.NO_TOPIC_DE + "Das ist die Navigation der Seite.", "",
                Collections.singletonList("{\"grund\":\"Das ist die Navigation der Seite.\","
                        + "\"urteil\":\"kein_thema\"}"),
                Verdict.Origin.MODELL, "gemma4:12b", 13L, 900L, true, TopicFenceCheck.PROMPT_VERSION);
        log.appendAll(Arrays.asList(ok, failed, author, menu));

        TopicFenceLog again = new TopicFenceLog(project);
        List<Verdict> read = again.open();
        assertEquals(4, read.size());
        assertTrue("KEIN_THEMA survives as AUSSEN with its mark", read.get(3).isNoTopic());
        assertEquals(Fence.AUSSEN, read.get(3).getFence());
        assertEquals("Kein Sachthema: Das ist die Navigation der Seite.", read.get(3).getReason());
        assertFalse(read.get(0).isNoTopic() || read.get(1).isNoTopic() || read.get(2).isNoTopic());
        assertEquals(2, read.get(0).getVersion());
        Verdict back = read.get(0);
        assertEquals("thema-0001", back.getTopicId());
        assertEquals("Lieferverkehr", back.getTopicName());
        assertEquals("k1", back.getTopicKey());
        assertEquals(brief.basis(), back.getBasis());
        assertEquals(Fence.UNKLAR, back.getFence());
        assertEquals("Am Rand.", back.getReason());
        assertEquals(Verdict.Origin.MODELL, back.getOrigin());
        assertEquals("gemma4:12b", back.getModel());
        assertEquals(10L, back.getTimeMillis());
        assertEquals(2345L, back.getMillis());
        assertEquals(ok.getAnswers(), back.getAnswers());
        assertFalse(read.get(1).isOk());
        assertNull(read.get(1).getFence());
        assertEquals("model call failed (TIMEOUT): slow", read.get(1).getProblem());
        assertEquals(Verdict.Origin.NUTZER, read.get(2).getOrigin());
        assertEquals(1, again.briefs().size());
        Brief stored = again.briefs().get(brief.basis());
        assertEquals(brief.getEdge(), stored.getEdge());
        assertEquals(brief.getExcluded(), stored.getExcluded());
        assertEquals(brief.getCards(), stored.getCards());

        String[] lines = new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8).split("\n");
        assertEquals("the brief once", 5, lines.length);
        assertTrue(lines[0], lines[0].startsWith("{\"v\":2,\"art\":\"grundlage\",\"grundlage\":\"" + brief.basis()));
        assertTrue(lines[1], lines[1].startsWith("{\"v\":2,\"art\":\"zaun\",\"themaId\":\"thema-0001\""));
        assertTrue(lines[1], lines[1].endsWith(",\"keinThema\":false,\"fassung\":2}"));
        assertTrue(lines[4], lines[4].endsWith(",\"keinThema\":true,\"fassung\":2}"));
        assertEquals("topic-fence", log.logFile().getParentFile().getName());
        again.appendBrief(brief);
        assertEquals(5, new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8)
                .split("\n").length);

        appendRaw(log.logFile(), lines[1].replace("{\"v\":2,", "{\"v\":1,")
                .replace(",\"keinThema\":false,\"fassung\":2}", "}") + "\n");
        List<Verdict> older = new TopicFenceLog(project).open();
        assertEquals("a line of version 1 is read", 5, older.size());
        assertFalse(older.get(4).isNoTopic());
        assertEquals("under the first prompt", 1, older.get(4).getVersion());
        assertEquals(Fence.UNKLAR, older.get(4).getFence());
    }

    @Test
    public void writingBeforeOpeningIsRefusedAndOnlyAnUnfinishedLastLineIsCut() throws Exception {
        File project = tmp.newFolder("project");
        TopicFenceLog log = new TopicFenceLog(project);
        Verdict ok = new Verdict("t", "T", "k", "b", Verdict.Status.OK, Fence.INNEN, "Ja.", "", null,
                Verdict.Origin.MODELL, "m", 1L, 1L);
        try {
            log.append(ok);
            fail("the version check comes first");
        } catch (IllegalStateException expected) {
            assertFalse(log.logFile().exists());
        }
        log.open();
        log.append(ok);
        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"za");
        assertEquals(1, new TopicFenceLog(project).open().size());
        assertTrue(new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8).endsWith("}\n"));

        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"grundlage\",\"grundlage\":\"falsch\",\"auftrag\":\"x\"}\n");
        try {
            new TopicFenceLog(project).open();
            fail("a brief that does not match its basis");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("record 2"));
        }
        try {
            new Verdict("t", "T", "k", "b", Verdict.Status.OK, Fence.UNGEPRUEFT, "", "", null, null, "m", 1L, 1L);
            fail("UNGEPRUEFT is no verdict");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("INNEN, AUSSEN or UNKLAR"));
        }
        try {
            new Verdict("t", "T", "k", "b", Verdict.Status.OK, Fence.UNKLAR, "Menü.", "", null, null, "m", 1L, 1L,
                    true, TopicFenceCheck.PROMPT_VERSION);
            fail("KEIN_THEMA is AUSSEN");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("is an OK verdict AUSSEN"));
        }
    }

    @Test
    public void aNewerVersionStopsWithoutTouchingTheFile() throws Exception {
        File project = tmp.newFolder("project");
        TopicFenceLog log = new TopicFenceLog(project);
        log.open();
        log.append(new Verdict("t", "T", "k", "b", Verdict.Status.OK, Fence.AUSSEN, "Nein.", "", null,
                Verdict.Origin.MODELL, "m", 1L, 1L));
        appendRaw(log.logFile(), "{\"v\":3,\"art\":\"zaun\"}\n{\"v\":1,\"art\":\"za");
        byte[] before = Files.readAllBytes(log.logFile().toPath());
        try {
            new TopicFenceLog(project).open();
            fail("newer");
        } catch (TopicFenceLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 has version 3"));
        }
        assertArrayEquals(before, Files.readAllBytes(log.logFile().toPath()));

        File moved = tmp.newFolder("moved");
        TopicFenceLog next = new TopicFenceLog(moved);
        File pointer = new File(next.logFile().getParentFile(), "current");
        Files.createDirectories(pointer.getParentFile().toPath());
        Files.write(pointer.toPath(), "log-2.jsonl\n".getBytes(StandardCharsets.UTF_8));
        try {
            next.open();
            fail("a newer generation");
        } catch (TopicFenceLog.NewerVersionException expected) {
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
