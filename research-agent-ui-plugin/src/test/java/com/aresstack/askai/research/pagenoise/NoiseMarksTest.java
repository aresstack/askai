package com.aresstack.askai.research.pagenoise;

import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The page-noise check's marks on disk: append only, the latest per text holds, fail closed. */
public class NoiseMarksTest {

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    static NoiseMark noise(String key) {
        return NoiseMark.of(key, "p-" + key, "s1", NoiseMark.Verdict.RAUSCHEN, NoiseMark.Kind.WERBUNG,
                NoiseMark.MODEL, "Ein Rabattangebot.", "gemma4:e2b", PageNoiseCheck.VERSION, 10L, 5L);
    }

    static NoiseMark content(String key) {
        return NoiseMark.of(key, "p-" + key, "s1", NoiseMark.Verdict.INHALT, NoiseMark.Kind.WERBUNG,
                NoiseMark.MODEL, "Eine Regel.", "gemma4:e2b", PageNoiseCheck.VERSION, 20L, 5L);
    }

    @Test
    public void theLatestMarkOfATextHoldsAcrossOpenings() throws Exception {
        File project = scratch.newFolder("projekt");
        NoiseMarks marks = new NoiseMarks(project);
        assertEquals(0, marks.open());
        marks.record(noise("tempo dreissig"), Arrays.asList("{\"urteil\":\"RAUSCHEN\"}"));
        marks.record(noise("parkscheibe"));
        marks.record(content("tempo dreissig"));

        NoiseMarks again = new NoiseMarks(project);
        assertEquals(3, again.open());
        assertEquals(2, again.size());
        assertFalse("the later INHALT holds", again.isNoise("tempo dreissig"));
        assertTrue(again.isNoise("parkscheibe"));
        assertFalse(again.isNoise("unbekannt"));
        assertEquals(NoiseMark.Kind.KEINE, again.mark("tempo dreissig").getKind());
        assertEquals("gemma4:e2b", again.mark("parkscheibe").getModel());
        assertEquals(5L, again.mark("parkscheibe").getMillis());
        List<String> lines = Files.readAllLines(marks.logFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(3, lines.size());
        JsonObject first = JsonParser.parseString(lines.get(0)).getAsJsonObject();
        assertEquals("marke", first.get("art").getAsString());
        assertEquals(1, first.get("v").getAsInt());
        assertEquals("{\"urteil\":\"RAUSCHEN\"}", first.getAsJsonArray("rohantworten").get(0).getAsString());
        assertEquals(new File(new File(project, "page-noise"), "marks.jsonl"), marks.logFile());
    }

    @Test
    public void onlyAMarkThatHoldsAndSaysRauschenIsNoise() {
        NoiseMarks marks = new NoiseMarks();
        NoiseMark mixed = NoiseMark.of("misch", "p1", "s1", NoiseMark.Verdict.MISCH, NoiseMark.Kind.KEINE,
                NoiseMark.MODEL, "Teils Werbung.", "m", 1, 1L, 0);
        NoiseMark failed = NoiseMark.failed("fehler", "p2", "s1", "model call failed", "m", 1, 1L, 0);
        try {
            marks.record(mixed);
            marks.record(failed);
            marks.record(noise("rauschen"));
        } catch (java.io.IOException impossible) {
            throw new AssertionError(impossible);
        }
        assertFalse(marks.isNoise("misch"));
        assertEquals("MISCH without a kind is SONSTIGES", NoiseMark.Kind.SONSTIGES, mixed.getKind());
        assertFalse(marks.isNoise("fehler"));
        assertNull(failed.getVerdict());
        assertEquals(NoiseMark.Kind.KEINE, failed.getKind());
        assertTrue(marks.isNoise("rauschen"));
        assertNull("RAM only", marks.logFile());
    }

    @Test
    public void aLineThatCannotBeReadFailsClosedAnUnfinishedLastLineIsCut() throws Exception {
        File project = scratch.newFolder("projekt");
        NoiseMarks marks = new NoiseMarks(project);
        marks.open();
        marks.record(noise("parkscheibe"));
        File file = marks.logFile();
        String good = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        Files.write(file.toPath(), (good + "{\"v\":1,\"art\":\"marke\",\"text\":\"halb")
                .getBytes(StandardCharsets.UTF_8));
        NoiseMarks cut = new NoiseMarks(project);
        assertEquals(1, cut.open());
        assertEquals(good, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));

        for (String bad : new String[]{"kaputt\n", "{\"v\":1,\"art\":\"paar\"}\n",
                "{\"v\":1,\"art\":\"marke\",\"text\":\"x\",\"status\":\"OK\",\"urteil\":\"VIELLEICHT\",\"stufe\":2,"
                        + "\"fassung\":1,\"zeit\":1}\n",
                "{\"v\":1,\"art\":\"marke\",\"text\":\"x\",\"status\":\"OK\",\"urteil\":\"RAUSCHEN\",\"stufe\":3,"
                        + "\"fassung\":1,\"zeit\":1}\n"}) {
            Files.write(file.toPath(), (good + bad).getBytes(StandardCharsets.UTF_8));
            try {
                new NoiseMarks(project).open();
                fail("read: " + bad);
            } catch (CorruptProjectFileException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("line 2"));
            }
        }
    }

    @Test
    public void aNewerVersionOrAnotherGenerationIsNeitherReadNorChanged() throws Exception {
        File project = scratch.newFolder("projekt");
        NoiseMarks marks = new NoiseMarks(project);
        marks.open();
        marks.record(noise("parkscheibe"));
        File file = marks.logFile();
        String newer = "{\"v\":2,\"art\":\"marke\"}\n";
        Files.write(file.toPath(), newer.getBytes(StandardCharsets.UTF_8));
        try {
            new NoiseMarks(project).open();
            fail("a newer version was read");
        } catch (NoiseMarks.NewerVersionException expected) {
            assertEquals(newer, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        Files.write(new File(file.getParentFile(), "current").toPath(),
                "marks-2.jsonl".getBytes(StandardCharsets.UTF_8));
        try {
            new NoiseMarks(project).open();
            fail("another generation was read");
        } catch (NoiseMarks.NewerVersionException expected) {
            assertEquals(file.getParentFile(), expected.getFile().getParentFile());
        }
    }

    @Test
    public void marksAreWrittenOnlyAfterOpening() throws Exception {
        NoiseMarks marks = new NoiseMarks(scratch.newFolder("projekt"));
        try {
            marks.record(noise("parkscheibe"));
            fail("written before opening");
        } catch (IllegalStateException expected) {
            assertFalse(marks.logFile().exists());
        }
    }

    @Test
    public void aMarkNeedsAKeyAStageAndAVerdictWhenItHolds() {
        for (Runnable bad : new Runnable[]{
                () -> NoiseMark.of("", "p", "s", NoiseMark.Verdict.RAUSCHEN, null, 1, "", "", 1, 1L, 0),
                () -> NoiseMark.of("k", "p", "s", NoiseMark.Verdict.RAUSCHEN, null, 3, "", "", 1, 1L, 0),
                () -> NoiseMark.of("k", "p", "s", null, null, 2, "", "", 1, 1L, 0)}) {
            try {
                bad.run();
                fail("accepted");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().startsWith("a mark"));
            }
        }
    }
}
