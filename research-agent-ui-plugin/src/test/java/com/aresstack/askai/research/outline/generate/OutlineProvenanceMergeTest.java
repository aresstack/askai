package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineCandidate;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineReport;
import com.aresstack.askai.research.outline.OutlineSection;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** A regeneration matches stored entries by passage overlap and never overwrites what the user owns. */
public class OutlineProvenanceMergeTest {

    private static OutlineProvenance material(String id, String... passages) {
        return new OutlineProvenance("", Collections.singletonList(id), Arrays.asList(passages));
    }

    private static OutlineSection section(String id, String title, OutlineProvenance provenance, boolean edited) {
        return new OutlineSection(id, "", 0, title, new OutlineBrief(title + " brief", null, 400), provenance,
                edited, "");
    }

    private static OutlineChapter chapter(String id, String title, boolean edited, OutlineSection... sections) {
        List<String> passages = new java.util.ArrayList<String>();
        for (OutlineSection section : sections) {
            passages.addAll(section.getProvenance().getPassageIds());
        }
        return new OutlineChapter(id, 0, title, new OutlineBrief(title + " brief", null, 0),
                new OutlineProvenance("", Collections.singletonList("t-" + title), passages), edited, "",
                Arrays.asList(sections));
    }

    private static Outline stored() {
        return new Outline(Arrays.asList(
                chapter("ch-a", "Helmets", false,
                        section("sec-a1", "Fit", material("s1", "p1", "p2", "p3"), false),
                        section("sec-a2", "Sensors", material("s2", "p4", "p5", "p6"), false)),
                chapter("ch-b", "My privacy chapter", true,
                        section("sec-b1", "Consent", material("s3", "p7", "p8", "p9"), true)),
                chapter("ch-c", "Old costs", false,
                        section("sec-c1", "Prices", material("s4", "q1", "q2"), false),
                        section("sec-c2", "My note", OutlineProvenance.NONE, true)),
                chapter("ch-u", "Field notes", false, section("sec-u1", "Interviews", OutlineProvenance.NONE, false))));
    }

    @Test
    public void entriesMatchByPassageOverlapAndOwnedEntriesSurvive() {
        Outline proposal = new Outline(Arrays.asList(
                // the same helmets material under a new title, sections reordered
                chapter("", "Head protection", false,
                        section("", "Sensor helmets", material("x2", "p4", "p5", "p6"), false),
                        section("", "Helmet fit", material("x1", "p1", "p2", "p3"), false)),
                // the privacy material grew
                chapter("", "Privacy", false, section("", "Consent forms", material("x3", "p7", "p8", "p9",
                        "p10"), false)),
                chapter("", "Exoskeletons", false, section("", "Lifting", material("x4", "r1", "r2", "r3"), false))),
                Collections.singletonList(new OutlineCandidate("t-out", "Fitness", null, null, 2)),
                new OutlineReport("analysis-2", false, null, null, null, null));

        Outline merged = OutlineProvenanceMerge.merge(stored(), proposal, 0.5);

        OutlineChapter helmets = merged.getChapters().get(0);
        assertEquals("ch-a", helmets.getId());
        assertEquals("a generated chapter takes the new title", "Head protection", helmets.getTitle());
        assertEquals("sec-a2", helmets.getSections().get(0).getId());
        assertEquals("sec-a1", helmets.getSections().get(1).getId());
        assertEquals("", helmets.getStaleReason());

        OutlineChapter privacy = merged.getChapters().get(1);
        assertEquals("ch-b", privacy.getId());
        assertEquals("an edited chapter keeps its title", "My privacy chapter", privacy.getTitle());
        assertEquals("and is marked because its research changed", OutlineProvenanceMerge.RESEARCH_CHANGED,
                privacy.getStaleReason());
        assertEquals("Consent", privacy.getSections().get(0).getTitle());
        assertEquals(OutlineProvenanceMerge.RESEARCH_CHANGED, privacy.getSections().get(0).getStaleReason());

        OutlineChapter exoskeletons = merged.getChapters().get(2);
        assertEquals("a new chapter gets its id from the store", "", exoskeletons.getId());

        assertEquals("the vanished generated chapter keeps only the user's section", "ch-c",
                merged.getChapters().get(3).getId());
        assertEquals(1, merged.getChapters().get(3).getSections().size());
        assertEquals("My note", merged.getChapters().get(3).getSections().get(0).getTitle());
        assertEquals("a chapter the user added is kept", "ch-u", merged.getChapters().get(4).getId());
        assertEquals(5, merged.getChapters().size());

        assertEquals("analysis-2", merged.getReport().getAnalysisSnapshotId());
        assertEquals(1, merged.getCandidates().size());
    }

    @Test
    public void tooLittleOverlapIsANewEntry() {
        Outline proposal = new Outline(Collections.singletonList(chapter("", "Helmets", false,
                section("", "Fit", material("x1", "p1", "z1", "z2", "z3"), false))));
        Outline merged = OutlineProvenanceMerge.merge(stored(), proposal, 0.5);
        assertEquals("", merged.getChapters().get(0).getId());
        assertFalse("the unedited helmets chapter is gone", merged.allIds().contains("ch-a"));
        assertTrue(merged.allIds().contains("ch-b"));
    }
}
