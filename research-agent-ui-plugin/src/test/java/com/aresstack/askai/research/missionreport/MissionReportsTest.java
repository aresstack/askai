package com.aresstack.askai.research.missionreport;

import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.JsonObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The mission reports on disk: append only, the latest is read back, fail closed. */
public class MissionReportsTest {

    @Rule
    public final TemporaryFolder scratch = new TemporaryFolder();

    @Test
    public void reportsAreAppendedAndTheLatestIsReadBackAcrossOpenings() throws Exception {
        File project = scratch.newFolder("projekt");
        MissionReports reports = new MissionReports(project);
        assertEquals(0, reports.open());
        assertNull(reports.latest());
        MissionReport first = new MissionCheck(MissionCheckTest.memory()).report(MissionCheckTest.TIME);
        MissionReport second = MissionCheckTest.report();
        reports.record(first);
        reports.record(second);

        MissionReports again = new MissionReports(project);
        assertEquals(2, again.open());
        assertEquals(2, again.size());
        JsonObject latest = again.latest();
        assertEquals(second.toJson(), latest);
        assertEquals(40, latest.getAsJsonObject("laenge").get("geschrieben").getAsInt());
        List<String> lines = Files.readAllLines(reports.logFile().toPath(), StandardCharsets.UTF_8);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0), lines.get(0).startsWith("{\"v\":1,\"art\":\"bericht\",\"zeit\":"));
        assertEquals(new File(new File(project, "mission-report"), "reports.jsonl"), reports.logFile());
        latest.addProperty("zeit", 0);
        assertEquals("a copy goes out", second.getTimeMillis(), again.latest().get("zeit").getAsLong());
    }

    @Test
    public void aLineThatCannotBeReadFailsClosedAnUnfinishedLastLineIsCut() throws Exception {
        File project = scratch.newFolder("projekt");
        MissionReports reports = new MissionReports(project);
        reports.open();
        reports.record(MissionCheckTest.report());
        File file = reports.logFile();
        String good = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);

        Files.write(file.toPath(), (good + "{\"v\":1,\"art\":\"bericht\",\"bericht\":{\"ze").getBytes(
                StandardCharsets.UTF_8));
        assertEquals(1, new MissionReports(project).open());
        assertEquals(good, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));

        for (String bad : new String[]{"kaputt\n", "{\"v\":1,\"art\":\"urteil\",\"bericht\":{}}\n",
                "{\"v\":1,\"art\":\"bericht\"}\n", "{\"art\":\"bericht\",\"bericht\":{}}\n"}) {
            Files.write(file.toPath(), (good + bad).getBytes(StandardCharsets.UTF_8));
            try {
                new MissionReports(project).open();
                fail("read: " + bad);
            } catch (CorruptProjectFileException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("line 2"));
            }
        }
    }

    @Test
    public void aNewerVersionOrAnotherGenerationIsNeitherReadNorChangedAndWritingNeedsOpening() throws Exception {
        File project = scratch.newFolder("projekt");
        MissionReports reports = new MissionReports(project);
        try {
            reports.record(MissionCheckTest.report());
            fail("written before opening");
        } catch (IllegalStateException expected) {
            assertFalse(reports.logFile().exists());
        }
        reports.open();
        reports.record(MissionCheckTest.report());
        File file = reports.logFile();
        String newer = "{\"v\":2,\"art\":\"bericht\",\"bericht\":{}}\n";
        Files.write(file.toPath(), newer.getBytes(StandardCharsets.UTF_8));
        try {
            new MissionReports(project).open();
            fail("a newer version was read");
        } catch (MissionReports.NewerVersionException expected) {
            assertEquals(newer, new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        }
        Files.write(new File(file.getParentFile(), "current").toPath(),
                "reports-2.jsonl".getBytes(StandardCharsets.UTF_8));
        try {
            new MissionReports(project).open();
            fail("another generation was read");
        } catch (MissionReports.NewerVersionException expected) {
            assertEquals(file.getParentFile(), expected.getFile().getParentFile());
        }
    }
}
