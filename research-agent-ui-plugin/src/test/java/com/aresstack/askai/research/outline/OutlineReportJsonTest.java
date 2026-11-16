package com.aresstack.askai.research.outline;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The report of the last generation is stored with the outline and read back unchanged. */
public class OutlineReportJsonTest {

    @Test
    public void theReportSurvivesTheFileFormat() {
        OutlineReport report = new OutlineReport("analysis-0123456789abcdef", true,
                Collections.singletonList(new OutlineReport.Gap("Training", "Book > Training", "GAP",
                        "no evidence in the research")),
                Arrays.asList(new OutlineReport.Finding(true, "areas-merged", "Chapter 1 combines two areas."),
                        new OutlineReport.Finding(false, "thin-chapter", "Chapter 2 rests on 2 passages.")),
                Collections.singletonList("No model is available."), Collections.singletonList("Fitness"));
        Outline outline = new Outline(Collections.singletonList(OutlineChapter.draft("Safety", null, null, null)),
                null, report);

        String json = OutlineJson.write(outline);
        Outline read = OutlineJson.read(json);

        assertTrue(json.contains("\"analysisSnapshot\": \"analysis-0123456789abcdef\""));
        assertEquals(report, read.getReport());
        assertEquals(outline, read);
    }

    @Test
    public void anOutlineWithoutReportStaysWithoutOne() {
        String json = OutlineJson.write(new Outline(Collections.<OutlineChapter>emptyList()));
        assertFalse(json.contains("report"));
        assertTrue(OutlineJson.read(json).getReport().isEmpty());
    }
}
