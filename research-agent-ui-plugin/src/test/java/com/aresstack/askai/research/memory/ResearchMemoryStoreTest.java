package com.aresstack.askai.research.memory;

import com.aresstack.askai.research.memory.EvidenceVerdict.Kind;
import com.aresstack.askai.research.memory.LeadDecision.Action;
import com.aresstack.askai.research.memory.LeadDecision.Target;
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

import static com.aresstack.askai.research.memory.ResearchMemoryTest.Q;
import static com.aresstack.askai.research.memory.ResearchMemoryTest.TEXT_A;
import static com.aresstack.askai.research.memory.ResearchMemoryTest.TEXT_B;
import static com.aresstack.askai.research.memory.ResearchMemoryTest.TEXT_C;
import static com.aresstack.askai.research.memory.ResearchMemoryTest.direct;
import static com.aresstack.askai.research.memory.ResearchMemoryTest.lead;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Component 7: the memory on disk, appended only, replayed in order, damaged lines fail closed. */
public class ResearchMemoryStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void savingAndLoadingGivesTheSameMemory() throws Exception {
        File project = tmp.newFolder("project");
        ResearchMemory memory = new ResearchMemoryStore(project).open();
        ResearchTopic card = ResearchTopic.ofCard("T-recht", "Recht", "anchor-concept-recht", 0.4, 1L);
        memory.addTopic(card);
        ResearchTopic found = ResearchTopic.found("T-gew", "Gewerkschaftliche Praxis", "Wie Gewerkschaften KI "
                + "begleiten", Collections.singletonList(new ResearchTopic.Finding("s7", "p7", "Die IG Metall")),
                "gemma4:e2b", 2L);
        memory.addTopic(found);
        memory.updateTopic(found.withFence(ResearchTopic.Fence.UNKLAR, "Rand des Themas offen", "gemma4:12b", 3L));
        memory.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat bei KI-Systemen?", "T-recht",
                ResearchQuestion.Origin.NUTZER, 1L));
        memory.addQuestion(ResearchQuestion.open("N01", "Wie begleiten Gewerkschaften KI-Einführungen?", "T-gew",
                ResearchQuestion.Origin.FINDER, 2L));
        memory.record(direct("p1", "s1", "https://a.example/x", TEXT_A));
        memory.record(direct("p2", "s2", "https://b.example/y", TEXT_B));
        memory.record(lead("p3", "s3", TEXT_C));
        memory.record(EvidenceVerdict.judged(Q, "p2", "s2", "https://b.example/y", TEXT_B, Kind.HINWEIS, "",
                "zweite Lesung", "m", 2, 5L));
        memory.record(EvidenceVerdict.failed("N01", "p9", "s9", "", TEXT_C, "timeout", "gemma4:e2b", 2, 6L));
        SearchRecord search = new SearchRecord("S1", "Betriebsrat KI Mitbestimmung", Arrays.asList(Q),
                Arrays.asList("T-recht"), "Wortwahl", "PLANER", 1, null, 7L);
        memory.record(search);
        memory.record(search.withYield(new SearchRecord.Yield(4, 30, 2, 1), 8L));
        memory.record(new LeadDecision(2, Target.FRAGE, Q, Action.STRATEGIE_WECHSELN, "zwei Suchen ohne neuen Beleg",
                memory.evidenceOf(Q).counts(), "gemma4:12b", 9L));
        memory.record(new LeadDecision(2, Target.LAUF, "", Action.BEENDEN, "Budget verbraucht", null, "", 10L));

        ResearchMemory loaded = new ResearchMemoryStore(project).open();

        assertEquals(memory.report(), loaded.report());
        assertEquals(memory.evidenceOf(Q).summary(), loaded.evidenceOf(Q).summary());
        assertEquals("Belegt: 1 (1 Quelle) · Weiterführender Hinweis: 2", loaded.evidenceOf(Q).summary());
        assertEquals(memory.evidenceOf(Q).counts(), loaded.evidenceOf(Q).counts());
        assertEquals(1, loaded.toRetry().size());
        ResearchTopic topic = loaded.topic("T-gew");
        assertEquals(ResearchTopic.Fence.UNKLAR, topic.getFence());
        assertEquals("Rand des Themas offen", topic.getFenceReason());
        assertEquals("Die IG Metall", topic.getFindings().get(0).getQuote());
        assertEquals(0.4, loaded.topic("T-recht").getWeight(), 1e-9);
        assertEquals("anchor-concept-recht", loaded.topic("T-recht").getCardAnchorId());
        assertEquals(ResearchQuestion.Origin.NUTZER, loaded.question(Q).getOrigin());
        EvidenceVerdict p1 = loaded.verdict(Q, "p1");
        assertEquals("https://a.example/x", p1.getSourceUrl());
        assertEquals("gemma4:12b", p1.getModel());
        assertEquals(ResearchMemoryTest.quoteOf(TEXT_A), p1.getQuote());
        SearchRecord.Yield yield = loaded.searches().get(0).getYield();
        assertEquals(2, yield.getNewEvidence());
        assertEquals(Arrays.asList("T-recht"), loaded.searches().get(0).getTopicIds());
        List<LeadDecision> decisions = loaded.decisions();
        assertEquals(2, decisions.size());
        assertEquals(Action.STRATEGIE_WECHSELN, decisions.get(0).getAction());
        assertEquals(memory.evidenceOf(Q).counts().get("direkt"), decisions.get(0).getCounts().get("direkt"));
        assertEquals(Target.LAUF, decisions.get(1).getTarget());

        List<String> lines = Files.readAllLines(new ResearchMemoryStore(project).logFile().toPath(),
                StandardCharsets.UTF_8);
        assertEquals("every record is one appended line", 14, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("\"art\":\"thema\"") && lines.get(0).contains("\"themaId\""));
        assertTrue(lines.get(5), lines.get(5).contains("\"frageId\":\"F01\"")
                && lines.get(5).contains("\"urteilModell\""));
    }

    @Test
    public void writingGoesOnAfterReopening() throws Exception {
        File project = tmp.newFolder("project");
        ResearchMemory first = new ResearchMemoryStore(project).open();
        first.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat?", "",
                ResearchQuestion.Origin.KONZEPT, 1L));
        ResearchMemory second = new ResearchMemoryStore(project).open();
        second.record(direct("p1", "s1", "", TEXT_A));

        assertEquals(1, new ResearchMemoryStore(project).open().evidenceOf(Q).getDirectCount());
    }

    @Test
    public void anUnfinishedLastLineIsDroppedAndADamagedLineFailsClosed() throws Exception {
        File project = tmp.newFolder("project");
        ResearchMemoryStore store = new ResearchMemoryStore(project);
        ResearchMemory memory = store.open();
        memory.addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat?", "",
                ResearchQuestion.Origin.KONZEPT, 1L));
        appendRaw(store.logFile(), "{\"v\":1,\"art\":\"belegurteil\",\"frageId\":\"F01\",\"pas");

        ResearchMemory reopened = store.open();
        assertEquals(1, reopened.questions().size());
        assertTrue(new String(Files.readAllBytes(store.logFile().toPath()), StandardCharsets.UTF_8).endsWith("}\n"));

        appendRaw(store.logFile(), "not json\n");
        try {
            store.open();
            fail("a damaged line must not be skipped");
        } catch (CorruptProjectFileException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("line 2"));
        }
    }

    /** Build plan, point R: a line of a newer version stops the opening, and the file stays as it is. */
    @Test
    public void aNewerVersionStopsWithoutTouchingTheFile() throws Exception {
        File project = tmp.newFolder("project");
        ResearchMemoryStore store = new ResearchMemoryStore(project);
        store.open().addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat?", "",
                ResearchQuestion.Origin.KONZEPT, 1L));
        appendRaw(store.logFile(), "{\"v\":2,\"art\":\"quelle\",\"quelleId\":\"s1\"}\n{\"v\":2,\"art\":\"fr");
        byte[] before = Files.readAllBytes(store.logFile().toPath());
        try {
            store.open();
            fail("a newer version must not be read");
        } catch (ResearchMemoryStore.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("newer AskAI")
                    && expected.getMessage().contains("line 2 has version 2"));
        }
        assertArrayEquals("not even the unfinished last line is cut", before,
                Files.readAllBytes(store.logFile().toPath()));
    }

    /** Build plan, point R: a newer build moves the memory to a new generation; this build stays out of it. */
    @Test
    public void aPointerToAnotherGenerationStops() throws Exception {
        File project = tmp.newFolder("project");
        ResearchMemoryStore store = new ResearchMemoryStore(project);
        store.open().addQuestion(ResearchQuestion.open(Q, "Welche Rechte hat der Betriebsrat?", "",
                ResearchQuestion.Origin.KONZEPT, 1L));
        File pointer = new File(store.logFile().getParentFile(), "current");
        Files.write(pointer.toPath(), "log.jsonl\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("a pointer to this build's generation is fine", 1, store.open().questions().size());
        Files.write(pointer.toPath(), "g2/log.jsonl\n".getBytes(StandardCharsets.UTF_8));
        try {
            store.open();
            fail("a newer generation must not be read");
        } catch (ResearchMemoryStore.NewerVersionException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("'g2/log.jsonl'"));
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
