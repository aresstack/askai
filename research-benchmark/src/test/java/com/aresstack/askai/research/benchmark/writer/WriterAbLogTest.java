package com.aresstack.askai.research.benchmark.writer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.aresstack.askai.research.host.ResearchRuntimeSettings;
import com.aresstack.askai.research.runtime.rerank.SourcePreferenceRanking;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/** The run records of the writer A/B after the stage 0 review: writer log, judge context, source preference. */
public class WriterAbLogTest {

    @Test
    public void countsTheWritersLinesPerSampleAndWritesThemToTheFile() throws Exception {
        File file = File.createTempFile("writer-ab", ".log");
        WriterAbLog log = new WriterAbLog(file);
        log.begin("run 1, gemma, chapter");
        log.accept("section s1: top-up 1 for 120 missing words (200 of 320), 3 uncited passages");
        log.accept("section s1: topped up to 290 words");
        log.accept("section s1: top-up 1 for 60 missing words (260 of 320), 0 uncited passages, by key points");
        log.accept("section s1: top-up dropped (250 words without an error, ...)");
        log.accept("section s2: removed 0 label lines and 2 repeated sentences, moved 0 uncited questions");
        log.accept("section s2: one repair for 3 citation errors");
        Map<String, Integer> counts = log.counts();
        assertEquals(Integer.valueOf(2), counts.get("top-ups"));
        assertEquals(Integer.valueOf(90), counts.get("top-up words"));
        assertEquals(Integer.valueOf(1), counts.get("top-ups without new passages"));
        assertEquals(Integer.valueOf(1), counts.get("top-ups dropped"));
        assertEquals(Integer.valueOf(2), counts.get("repeats removed"));
        assertEquals(Integer.valueOf(1), counts.get("repairs"));
        log.check();

        log.begin("run 1, gemma, section 1");
        assertEquals(Integer.valueOf(0), log.counts().get("top-ups"));
        String written = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        assertTrue(written, written.contains("## run 1, gemma, chapter"));
        assertTrue(written, written.contains("topped up to 290 words"));
    }

    @Test
    public void aWriterThatLostItsSentenceVectorsStopsTheRun() {
        WriterAbLog log = new WriterAbLog(null);
        log.begin("run 2");
        log.accept("sentence embedding unavailable, checks stay lexical: connection refused");
        try {
            log.check();
            fail("the run must stop");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("lexical checks only"));
        }
    }

    @Test
    public void theJudgeReadsTheSentencesBeforeOfTheSameParagraphAndThinksLowWithGptOss() {
        List<WriterQualityMetrics.Sentence> sentences = WriterQualityMetrics.sentences(
                "Erster Absatz endet hier [1].\n\nSensoren messen den Puls [1]. Daten gehen an die Leitstelle [2]. "
                        + "Diese Systeme warnen früh [2, 3].");
        assertEquals("", CitationSupportJudge.context(sentences, 1));
        assertEquals("Sensoren messen den Puls. Daten gehen an die Leitstelle.",
                CitationSupportJudge.context(sentences, 3));
        assertEquals("low", CitationSupportJudge.think("gpt-oss:20b").getAsString());
        assertFalse(CitationSupportJudge.think("gemma4:12b").getAsBoolean());
    }

    @Test
    public void theCallsAreRecordedByKindWithTheirAnswerText() throws Exception {
        File file = File.createTempFile("writer-ab", ".jsonl");
        Files.write(file.toPath(), new byte[0]);
        WriterAbCalls calls = new WriterAbCalls(file);
        calls.begin("run 1, gemma4:12b @parts, chapter");
        String system = "You write one section of a non-fiction book from numbered source passages";
        calls.accept("{\"model\":\"gemma4:12b\",\"messages\":[{\"content\":\"" + system + "\"}]}",
                "{\"message\":{\"content\":\"Erster \"},\"done\":false}\n"
                        + "{\"message\":{\"content\":\"Satz [1].\"},\"done\":true}\n");
        calls.accept("{\"model\":\"gemma4:12b\",\"messages\":[{\"content\":\"" + system + "\"},{\"content\":"
                + "\"Your answer above is already written and stays as it is; it has about 200 words\"}]}",
                "{\"message\":{\"content\":\"Nachtrag [2].\"},\"done\":true}");
        calls.accept("{\"model\":\"gpt-oss:20b\",\"messages\":[{\"content\":\"judge this\"}]}",
                "{\"message\":{\"content\":\"{}\"},\"done\":true}");
        List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).contains("\"kind\":\"draft\"") && lines.get(0).contains("Erster Satz [1]."));
        assertTrue(lines.get(1), lines.get(1).contains("\"kind\":\"top-up\"") && lines.get(1).contains("@parts"));
        assertEquals(WriterAbCalls.REPAIR, WriterAbCalls.kind(system
                + " A previous answer to this request broke the writing rules:"));
    }

    @Test
    public void anArmSpecFromABatchFileUsesHashInsteadOfBar() {
        List<WriterAbArm> arms = WriterAbArm.parse("gemma4:e2b##,gpt-oss:20b##low,llama3.1:8b|1100|");
        assertEquals("gpt-oss:20b", arms.get(1).getModel());
        assertEquals("low", arms.get(1).getThinking());
        assertEquals(0, arms.get(1).getWordsPerCall());
        assertEquals("", arms.get(0).getThinking());
        assertEquals(1100, arms.get(2).getWordsPerCall());
    }

    @Test
    public void theExportRanksLikeTheAppWhenTheMissionPrefersSources() throws Exception {
        File project = Files.createTempDirectory("writer-ab-project").toFile();
        Files.write(new File(project, "book.json").toPath(), ("{\"pins\":[{\"mission\":\"alt\"},"
                + "{\"mission\":\"Ein Buch über Wearables. Bevorzuge Quellen von DGUV und BAuA.\"},{\"other\":1}]}")
                .getBytes(StandardCharsets.UTF_8));
        WriterAbExport export = new WriterAbExport(project, "http://127.0.0.1:11434");
        String mission = export.mission();
        assertTrue(mission, mission.contains("Bevorzuge"));
        SourcePreferenceRanking on = WriterAbExport.ranking(mission, ResearchRuntimeSettings.loadSourcePreference(
                new WriterAbVariant.MapStore(Collections.<String, String>emptyMap())));
        SourcePreferenceRanking off = WriterAbExport.ranking(mission, ResearchRuntimeSettings.loadSourcePreference(
                new WriterAbVariant.MapStore(Collections.singletonMap(
                        ResearchRuntimeSettings.KEY_SOURCE_PREFERENCE_ENABLED, "false"))));
        assertFalse("on by default", on.isNone());
        assertTrue("an authority host moves up", on.shiftOf("https://www.baua.de/DE/Themen/x.html") != 0
                || on.shiftOf("https://www.bmas.bund.de/a.html") != 0);
        assertTrue("off is no ranking", off.isNone());
    }
}
