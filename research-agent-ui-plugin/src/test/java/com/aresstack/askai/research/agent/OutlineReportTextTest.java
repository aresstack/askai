package com.aresstack.askai.research.agent;

import com.aresstack.askai.research.outline.OutlineReport;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

/** The Outline tab shows the gaps and findings of the last generation in plain lines. */
public class OutlineReportTextTest {

    @Test
    public void gapsComeFirstThenProblemsWarningsAndNotes() {
        String text = OutlineEditorView.reportText(new OutlineReport("analysis-1", false,
                Collections.singletonList(new OutlineReport.Gap("Training", "Book > Training", "GAP",
                        "no evidence in the research")),
                Arrays.asList(new OutlineReport.Finding(true, "areas-merged", "Chapter 1 combines two areas."),
                        new OutlineReport.Finding(false, "thin-chapter", "Chapter 2 is thin.")),
                Collections.singletonList("Topic \"Drones\" joined \"Sensors\"."),
                Collections.singletonList("Fitness trackers")));

        assertTrue(text.startsWith("Built on topic analysis analysis-1."));
        assertTrue(text.contains("- Book > Training: no evidence in the research"));
        assertTrue(text.indexOf("Training") < text.indexOf("Problems"));
        assertTrue(text.contains("Problems the revisions did not solve:\n- Chapter 1 combines two areas."));
        assertTrue(text.contains("Warnings:\n- Chapter 2 is thin."));
        assertTrue(text.contains("- Fitness trackers"));
        assertTrue(text.contains("How the outline came about:"));
    }

    @Test
    public void anOutlineThatWasNeverGeneratedSaysSo() {
        assertTrue(OutlineEditorView.reportText(OutlineReport.EMPTY).contains("not been generated"));
    }
}
