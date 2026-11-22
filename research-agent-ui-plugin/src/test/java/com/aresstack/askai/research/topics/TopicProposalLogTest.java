package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.topics.TopicFinder.WindowResult;
import com.aresstack.askai.research.topics.TopicProposal.FindingCheck;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 13 on disk: one line per window, appended only, the latest result of a window holds. */
public class TopicProposalLogTest {

    private static final String URL = "https://www.stadt.example/verkehr/bericht-2025";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void aWindowResultSurvivesTheRoundTrip() throws Exception {
        File project = tmp.newFolder("project");
        TopicProposalLog log = new TopicProposalLog(project);
        assertTrue(log.open().isEmpty());
        WindowResult ok = ok("s1", 1, Arrays.asList("s1-p1", "s1-p2"), Arrays.asList(
                proposal("vs-r2-s1-w1-1", "s1-p1", FindingCheck.GEPRUEFT, "Lastenräder in der Innenstadt",
                        Arrays.asList("Wie viele Lieferungen übernehmen Lastenräder?", "Was kosten sie?")),
                proposal("vs-r2-s1-w1-2", "", FindingCheck.NICHT_GEFUNDEN, "Nachtlieferungen",
                        Collections.<String>emptyList())));
        WindowResult failed = new WindowResult("s2", "https://b.example/x", 3, Arrays.asList("s2-p9"),
                WindowResult.Status.FEHLER, "model call failed (TIMEOUT): slow", null, null, Arrays.asList("", ""),
                "gemma4:e2b", 2, 20L, 0L);
        log.append(ok);
        log.append(failed);

        List<WindowResult> read = new TopicProposalLog(project).open();
        assertEquals(2, read.size());
        WindowResult back = read.get(0);
        assertEquals("s1", back.getSourceId());
        assertEquals(URL, back.getSourceUrl());
        assertEquals(1, back.getWindowIndex());
        assertEquals(Arrays.asList("s1-p1", "s1-p2"), back.getPassageIds());
        assertTrue(back.isOk());
        assertEquals("gemma4:e2b", back.getModel());
        assertEquals(2, back.getRound());
        assertEquals(10L, back.getTimeMillis());
        assertEquals(1234L, back.getMillis());
        assertEquals(Collections.singletonList("\"Sechs\": over 5 proposals"), back.getDropped());
        assertEquals(Collections.singletonList("{\"vorschlaege\":[]}"), back.getAnswers());
        assertEquals(2, back.getProposals().size());
        TopicProposal first = back.getProposals().get(0);
        assertEquals("vs-r2-s1-w1-1", first.getId());
        assertEquals("s1", first.getSourceId());
        assertEquals(URL, first.getSourceUrl());
        assertEquals("s1-p1", first.getPassageId());
        assertEquals("übernehmen Lastenräder die letzte Meile", first.getQuote());
        assertEquals(FindingCheck.GEPRUEFT, first.getFindingCheck());
        assertEquals("Lastenräder in der Innenstadt", first.getName());
        assertEquals("Worum es geht.", first.getDescription());
        assertEquals(Arrays.asList("Wie viele Lieferungen übernehmen Lastenräder?", "Was kosten sie?"),
                first.getQuestions());
        assertEquals("gemma4:e2b", first.getModel());
        assertEquals(2, first.getRound());
        assertEquals(11L, first.getTimeMillis());
        assertEquals(FindingCheck.NICHT_GEFUNDEN, back.getProposals().get(1).getFindingCheck());

        WindowResult second = read.get(1);
        assertEquals(WindowResult.Status.FEHLER, second.getStatus());
        assertEquals("model call failed (TIMEOUT): slow", second.getProblem());
        assertEquals(Arrays.asList("", ""), second.getAnswers());
        String line = new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8).split("\n")[0];
        assertTrue(line.startsWith("{\"v\":1,\"art\":\"fenster\",\"quelleId\":\"s1\""));
        assertEquals("topic-proposals", log.logFile().getParentFile().getName());
    }

    @Test
    public void theLatestResultOfAWindowHolds() throws Exception {
        File project = tmp.newFolder("project");
        TopicProposalLog log = new TopicProposalLog(project);
        log.open();
        WindowResult failed = new WindowResult("s1", URL, 1, Arrays.asList("s1-p1"), WindowResult.Status.FEHLER,
                "slow", null, null, null, "m", 1, 1L, 1L);
        WindowResult other = new WindowResult("s2", URL, 1, Arrays.asList("s2-p1"), WindowResult.Status.FEHLER,
                "slow", null, null, null, "m", 1, 2L, 1L);
        WindowResult retried = ok("s1", 1, Arrays.asList("s1-p1"), Collections.singletonList(proposal("vs-r2-s1-w1-1",
                "s1-p1", FindingCheck.GEPRUEFT, "Lastenräder", Collections.<String>emptyList())));
        log.appendAll(Arrays.asList(failed, other, retried));

        List<WindowResult> all = new TopicProposalLog(project).open();
        assertEquals(3, all.size());
        List<WindowResult> latest = TopicProposalLog.latest(all);
        assertEquals(2, latest.size());
        assertTrue(latest.get(0).isOk());
        assertEquals("s2", latest.get(1).getSourceId());
        assertEquals(1, TopicProposalLog.proposals(all).size());
        assertEquals("vs-r2-s1-w1-1", TopicProposalLog.proposals(all).get(0).getId());
        assertEquals(1, TopicProposalLog.failed(all).size());
        assertEquals("s2", TopicProposalLog.failed(all).get(0).getSourceId());
    }

    @Test
    public void writingBeforeOpeningIsRefused() throws Exception {
        TopicProposalLog log = new TopicProposalLog(tmp.newFolder("project"));
        try {
            log.append(ok("s1", 1, Arrays.asList("s1-p1"), null));
            fail("the version check comes first");
        } catch (IllegalStateException expected) {
            assertFalse(log.logFile().exists());
        }
    }

    @Test
    public void anUnfinishedLastLineIsCutAndADamagedLineFailsClosed() throws Exception {
        File project = tmp.newFolder("project");
        TopicProposalLog log = new TopicProposalLog(project);
        log.open();
        log.append(ok("s1", 1, Arrays.asList("s1-p1"), null));
        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"fenster\",\"quelle");
        assertEquals(1, new TopicProposalLog(project).open().size());
        String content = new String(Files.readAllBytes(log.logFile().toPath()), StandardCharsets.UTF_8);
        assertTrue(content.endsWith("\n"));
        assertEquals(1, content.split("\n").length);

        appendRaw(log.logFile(), "{\"v\":1,\"art\":\"fenster\"}\n");
        try {
            new TopicProposalLog(project).open();
            fail("a line without its fields cannot be read");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("record 2"));
        }
        File other = tmp.newFolder("other");
        TopicProposalLog damaged = new TopicProposalLog(other);
        Files.createDirectories(damaged.logFile().getParentFile().toPath());
        appendRaw(damaged.logFile(), "kein json\n");
        try {
            damaged.open();
            fail("damaged");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 1"));
        }
    }

    @Test
    public void aNewerVersionStopsWithoutTouchingTheFile() throws Exception {
        File project = tmp.newFolder("project");
        TopicProposalLog log = new TopicProposalLog(project);
        log.open();
        log.append(ok("s1", 1, Arrays.asList("s1-p1"), null));
        appendRaw(log.logFile(), "{\"v\":2,\"art\":\"fenster\"}\n{\"v\":1,\"art\":\"fen");
        byte[] before = Files.readAllBytes(log.logFile().toPath());
        try {
            new TopicProposalLog(project).open();
            fail("newer");
        } catch (TopicProposalLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2 has version 2"));
        }
        assertArrayEquals("not even the unfinished last line is cut", before,
                Files.readAllBytes(log.logFile().toPath()));

        File moved = tmp.newFolder("moved");
        TopicProposalLog next = new TopicProposalLog(moved);
        File pointer = new File(next.logFile().getParentFile(), "current");
        Files.createDirectories(pointer.getParentFile().toPath());
        Files.write(pointer.toPath(), "log-2.jsonl\n".getBytes(StandardCharsets.UTF_8));
        try {
            next.open();
            fail("a newer generation");
        } catch (TopicProposalLog.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'log-2.jsonl'"));
        }
        Files.write(pointer.toPath(), "log.jsonl\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(next.open().isEmpty());
    }

    private static WindowResult ok(String sourceId, int window, List<String> passages, List<TopicProposal> proposals) {
        return new WindowResult(sourceId, URL, window, passages, WindowResult.Status.OK, "", proposals,
                Collections.singletonList("\"Sechs\": over 5 proposals"),
                Collections.singletonList("{\"vorschlaege\":[]}"), "gemma4:e2b", 2, 10L, 1234L);
    }

    private static TopicProposal proposal(String id, String passageId, FindingCheck check, String name,
                                          List<String> questions) {
        return new TopicProposal(id, "s1", URL, passageId, "übernehmen Lastenräder die letzte Meile", check, name,
                "Worum es geht.", questions, "gemma4:e2b", 2, 11L);
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
