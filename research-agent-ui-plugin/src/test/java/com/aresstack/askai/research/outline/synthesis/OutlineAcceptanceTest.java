package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeFenceEvaluator;
import com.aresstack.askai.research.knowledge.topic.ScopeFence;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineReport;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.outline.OutlineWorkspace;
import com.aresstack.askai.research.outline.generate.OutlineProvenanceMerge;
import com.aresstack.askai.research.store.FileOutlineStore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Phase 3 acceptance tests A to F of the order: the whole path from the topic analysis to the stored outline (brief,
 * synthesis with scripted model answers, quality check, safe fixes, assembly, provenance merge). No live model call;
 * the live run is {@code OutlineLiveAcceptance}.
 */
public class OutlineAcceptanceTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    // ------------------------------------------------------------------ scripted answers

    /** A chapter of the plan: title, material keys, concept keys. */
    private static String chapter(String title, List<String> topics, List<String> concepts) {
        return "{\"title\":\"" + title + "\",\"purpose\":\"Explains " + title.toLowerCase() + ".\",\"topics\":"
                + array(topics) + ",\"concepts\":" + array(concepts) + "}";
    }

    private static String plan(List<String> gaps, String... chapters) {
        return "{\"chapters\":[" + String.join(",", chapters) + "],\"gaps\":" + array(gaps) + "}";
    }

    private static String sections(String... keys) {
        StringBuilder json = new StringBuilder("{\"sections\":[");
        for (int i = 0; i < keys.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"title\":\"Part ").append(i + 1).append(' ').append(keys[i])
                    .append("\",\"purpose\":\"Covers ").append(keys[i]).append(".\",\"material\":")
                    .append(array(Arrays.asList(keys[i].split("\\+")))).append('}');
        }
        return json.append("]}").toString();
    }

    private static String array(List<String> values) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            json.append(i > 0 ? "," : "").append('"').append(values.get(i)).append('"');
        }
        return json.append(']').toString();
    }

    private static List<String> keys(String... keys) {
        return Arrays.asList(keys);
    }

    private static final List<String> NONE = Collections.emptyList();

    private static Outline propose(SynthesisFixture corpus, ScriptedChat chat) {
        return new OutlineProposer(chat, OutlineSynthesisSettings.defaults(), OutlineLengthSettings.defaults(), null)
                .propose(corpus.brief(), "analysis-test", null, null);
    }

    private static Set<String> passagesOf(OutlineChapter chapter) {
        return new HashSet<String>(chapter.getProvenance().getPassageIds());
    }

    // ------------------------------------------------------------------ A

    /** One concept root (the book frame) and four independent, well-evidenced research areas. */
    private static SynthesisFixture oneRootFourAreas() {
        SynthesisFixture corpus = new SynthesisFixture();
        for (String area : new String[]{"helmets", "sensors", "privacy", "costs"}) {
            corpus.diverse(area, 2, 4, 3);
        }
        return corpus;
    }

    @Test
    public void testA_oneConceptRootWithFourResearchAreasIsNotOneChapterWithFourSections() {
        SynthesisFixture corpus = oneRootFourAreas();
        ScriptedChat chat = new ScriptedChat()
                // The model first copies the single concept root: one chapter over everything ...
                .then(plan(NONE, chapter("Wearables", keys("T1", "T2", "T3", "T4"), NONE)))
                .then(sections("T1", "T2", "T3", "T4"))
                // ... the review sends it back; the revision separates the areas.
                .then(plan(NONE, chapter("Head protection", keys("T1"), NONE),
                        chapter("Sensor data", keys("T2"), NONE), chapter("Privacy", keys("T3"), NONE),
                        chapter("Costs", keys("T4"), NONE)))
                .then(sections("T1.1", "T1.2")).then(sections("T2.1", "T2.2"))
                .then(sections("T3.1", "T3.2")).then(sections("T4.1", "T4.2"));

        Outline outline = propose(corpus, chat);

        assertTrue("the review named the merged areas",
                chat.calls.get(2).prompt().contains("combines independent major research areas"));
        assertEquals("four areas, four chapters", 4, outline.getChapters().size());
        Set<String> seen = new HashSet<String>();
        for (OutlineChapter chapter : outline.getChapters()) {
            assertEquals(2, chapter.getSections().size());
            for (String passage : chapter.getProvenance().getPassageIds()) {
                assertTrue("every passage in one chapter", seen.add(passage));
            }
        }
        assertEquals("the whole research is placed", corpus.passages.size(), seen.size());
        assertEquals("analysis-test", outline.getReport().getAnalysisSnapshotId());
        assertFalse(outline.getReport().isFromAnalysisOnly());
        for (OutlineReport.Finding finding : outline.getReport().getFindings()) {
            assertFalse(finding.getMessage(), finding.isBlocking());
        }
    }

    @Test
    public void testA_withoutAModelTheAnalysisAloneStillSeparatesTheAreas() {
        Outline outline = propose(oneRootFourAreas(), null);
        assertEquals(4, outline.getChapters().size());
        assertTrue(outline.getReport().isFromAnalysisOnly());
        assertTrue(outline.getReport().getNotes().get(0).contains("No model"));
    }

    // ------------------------------------------------------------------ B

    @Test
    public void testB_oneLargeTopicWithSubTopicsGetsAChapterWithSeveralSections() {
        SynthesisFixture corpus = new SynthesisFixture();
        int site = corpus.diverse("site safety", 4, 4, 4);
        corpus.plain("history", 4, 2);
        corpus.concept("c-safety", "Safety", site);
        ScriptedChat chat = new ScriptedChat()
                .then(plan(NONE, chapter("Site safety", keys("T1"), keys("C1")),
                        chapter("Background", keys("T2"), NONE)))
                // The model squeezes the large topic into a single section ...
                .then(sections("T1"))
                .then(sections("T2"));

        Outline outline = propose(corpus, chat);

        OutlineChapter safety = outline.getChapters().get(0);
        assertEquals("... the check splits it by its four sub-topics", 4, safety.getSections().size());
        for (OutlineSection section : safety.getSections()) {
            assertTrue("code-made sections are labelled later", section.isUntitled());
            assertFalse(section.getProvenance().getPassageIds().isEmpty());
        }
        assertEquals(1, outline.getChapters().get(1).getSections().size());
        assertTrue(String.join("\n", outline.getReport().getNotes()).contains("got a section per sub-topic"));
        assertEquals("the concept node is the chapter's provenance", "c-safety",
                safety.getProvenance().getConceptNodeId());
    }

    // ------------------------------------------------------------------ C

    @Test
    public void testC_redundantSourcesDoNotInflateTheOutline() {
        SynthesisFixture corpus = new SynthesisFixture();
        // 30 passages from 10 sources, but only 3 distinct statements ...
        corpus.repeated("battery life", 3, 10, 10);
        // ... next to 8 distinct passages in two aspects.
        corpus.diverse("fall detection", 2, 4, 3);
        SynthesisBrief brief = corpus.brief();
        SynthesisBrief.TopicEntry repeated = null;
        for (SynthesisBrief.TopicEntry topic : brief.getTopics()) {
            if (topic.getProfile().getTitle().contains("battery")) {
                repeated = topic;
            }
        }
        assertEquals(30, repeated.getProfile().getPassageCount());
        assertEquals(3, repeated.getProfile().getDistinctPassageCount());
        assertTrue("repetition makes no sub-topics", repeated.getSubtopics().isEmpty());

        Outline outline = propose(corpus, null);

        OutlineSection battery = null;
        int batterySections = 0;
        for (OutlineChapter chapter : outline.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                if (section.getProvenance().getPassageIds().containsAll(repeated.getProfile().getPassageIds())) {
                    battery = section;
                }
                if (section.getTitle().contains("battery")) {
                    batterySections++;
                }
            }
        }
        assertEquals("the repeated topic is one section", 1, batterySections);
        OutlineLengthSettings length = OutlineLengthSettings.defaults();
        assertEquals("its budget follows the 3 distinct passages, not the 30 copies", length.unboundedTarget(3),
                battery.getBrief().getWordTarget());
        assertTrue(battery.getBrief().getWordTarget() < length.unboundedTarget(30));
    }

    @Test
    public void testC_theBookLengthIsSpreadByDistinctMaterial() {
        SynthesisFixture corpus = new SynthesisFixture();
        corpus.repeated("battery life", 3, 10, 10);
        corpus.plain("fall detection", 9, 3);
        corpus.deliverable = "about 12000 words";

        Outline outline = propose(corpus, null);

        int battery = 0;
        int falls = 0;
        int total = 0;
        for (OutlineChapter chapter : outline.getChapters()) {
            total += chapter.getBrief().getWordTarget();
            for (OutlineSection section : chapter.getSections()) {
                if (section.getTitle().contains("battery")) {
                    battery = section.getBrief().getWordTarget();
                } else {
                    falls = section.getBrief().getWordTarget();
                }
            }
        }
        assertEquals("the length is spread over the sections", 12000, total);
        assertEquals("3 distinct passages get a third of what 9 get", falls / 3.0, battery, 50);
    }

    // ------------------------------------------------------------------ D

    @Test
    public void testD_newResearchTopicsBecomeChaptersAndOutOfScopeOnesStayOut() {
        SynthesisFixture corpus = new SynthesisFixture();
        int helmets = corpus.diverse("helmets", 2, 4, 3);
        corpus.diverse("exoskeletons", 2, 4, 3);
        int fitness = corpus.plain("fitness trackers", 6, 3);
        corpus.concept("c-safety", "Safety", helmets);
        corpus.fence = new ScopeFence(Arrays.asList(
                new ScopeFenceEvaluator.AnchorVector("in-1", ScopeAnchor.Membership.IN, axis(helmets)),
                new ScopeFenceEvaluator.AnchorVector("out-1", ScopeAnchor.Membership.OUT, axis(fitness))),
                Collections.singletonMap("out-1", "Consumer fitness"), new ScopeFenceEvaluator.Thresholds(0.7, 0.05),
                1L, "fp");
        SynthesisBrief brief = corpus.brief();
        assertEquals("helmets and the new exoskeletons topic", 2, brief.getTopics().size());
        String novel = brief.getTopics().get(0).getConceptKey().isEmpty() ? "T1" : "T2";
        String known = novel.equals("T1") ? "T2" : "T1";
        ScriptedChat chat = new ScriptedChat()
                .then(plan(NONE, chapter("Protective gear", keys(known), keys("C1")),
                        chapter("Exoskeletons", keys(novel), NONE)))
                .then(sections(known + ".1", known + ".2"))
                .then(sections(novel + ".1", novel + ".2"));

        Outline outline = propose(corpus, chat);

        assertEquals(2, outline.getChapters().size());
        OutlineChapter exoskeletons = outline.getChapters().get(1);
        assertEquals("not in the concept", "", exoskeletons.getProvenance().getConceptNodeId());
        assertEquals(8, exoskeletons.getProvenance().getPassageIds().size());
        assertEquals("the out-of-scope topic is only a candidate", 1, outline.getCandidates().size());
        assertTrue(outline.getCandidates().get(0).getSuggestedTitle().contains("fitness"));
        assertTrue(outline.getReport().getOutOfScopeTopics().get(0).contains("fitness"));
        for (OutlineChapter chapter : outline.getChapters()) {
            assertFalse(chapter.getTitle().contains("fitness"));
        }
    }

    // ------------------------------------------------------------------ E

    @Test
    public void testE_aRequirementWithoutEvidenceStaysVisibleAsAGapAndGetsNoInventedChapter() {
        SynthesisFixture corpus = new SynthesisFixture();
        int helmets = corpus.diverse("helmets", 2, 4, 3);
        corpus.concept("c-safety", "Safety", helmets);
        corpus.concept("c-training", "Training", corpus.direction());
        ScriptedChat chat = new ScriptedChat()
                // The model claims the unsupported requirement in a chapter and lists no gap.
                .then(plan(NONE, chapter("Safety and training", keys("T1"), keys("C1", "C2"))))
                .then(sections("T1.1", "T1.2"))
                // The review blocks; the revision still claims it (the fix moves it).
                .then(plan(NONE, chapter("Safety and training", keys("T1"), keys("C1", "C2"))))
                .then(plan(NONE, chapter("Safety and training", keys("T1"), keys("C1", "C2"))));

        Outline outline = propose(corpus, chat);

        assertEquals(1, outline.getChapters().size());
        assertEquals(1, outline.getReport().getGaps().size());
        OutlineReport.Gap gap = outline.getReport().getGaps().get(0);
        assertEquals("Training", gap.getRequirement());
        assertEquals("GAP", gap.getStatus());
        assertEquals("no evidence in the research", gap.getReason());
        String notes = String.join("\n", outline.getReport().getNotes());
        assertTrue(notes.contains("moved from"));
        // Live run 4: "still has findings" stood beside an empty findings list; the report says how it ended.
        assertTrue(notes, notes.contains("still has findings after 2 revision rounds"));
        assertTrue(notes, notes.contains("After the safe fixes the review has no blocking findings left."));
        assertFalse("the chapter no longer claims it", outline.getChapters().get(0).getBrief().getKeyPoints()
                .contains("Training"));
    }

    @Test
    public void testE_aThinRequirementIsReportedWithItsReason() {
        SynthesisFixture corpus = new SynthesisFixture();
        int helmets = corpus.diverse("helmets", 2, 4, 3);
        int noise = corpus.plain("noise", 4, 1);
        corpus.concept("c-safety", "Safety", helmets);
        corpus.concept("c-noise", "Noise", noise);

        Outline outline = propose(corpus, null);

        boolean reported = false;
        for (OutlineReport.Gap gap : outline.getReport().getGaps()) {
            if (gap.getRequirement().equals("Noise")) {
                reported = true;
                assertEquals("THIN", gap.getStatus());
                assertEquals("too few independent sources", gap.getReason());
            }
        }
        assertTrue("one source is no confirmation", reported);
    }

    // ------------------------------------------------------------------ F

    @Test
    public void testF_userEditsSurviveARegenerationAndAreMarkedWhenTheirResearchChanged() {
        SynthesisFixture corpus = oneRootFourAreas();
        OutlineWorkspace workspace = new OutlineWorkspace(new FileOutlineStore(folder.getRoot()));
        long first = workspace.save(OutlineProvenanceMerge.merge(workspace.current(), propose(corpus, null)), 0L,
                1L).revision;
        Outline generated = workspace.current();
        assertEquals(4, generated.getChapters().size());

        // The user renames the first chapter and adds a chapter of their own.
        OutlineChapter renamed = generated.getChapters().get(0).withTitle("My helmets").withUserEdited(true);
        List<OutlineChapter> edited = new ArrayList<OutlineChapter>(generated.getChapters());
        edited.set(0, renamed);
        edited.add(OutlineChapter.draft("Field notes", null, null,
                Collections.singletonList(OutlineSection.draft("Interviews", null, null))).withUserEdited(true));
        long second = workspace.save(generated.withChapters(edited), first, 2L).revision;

        // The research grows by a new area; the outline is generated again.
        corpus.diverse("exoskeletons", 2, 4, 3);
        Outline merged = OutlineProvenanceMerge.merge(workspace.current(), propose(corpus, null));
        long third = workspace.save(merged, second, 3L).revision;
        Outline regenerated = workspace.current();

        OutlineChapter mine = regenerated.chapter(renamed.getId());
        assertEquals("the edited chapter keeps its id and title", "My helmets", mine.getTitle());
        assertTrue(mine.isUserEdited());
        assertEquals("its research did not change, so it is not marked", "", mine.getStaleReason());
        assertEquals(passagesOf(renamed), passagesOf(mine));
        assertEquals("four areas, the new one and the user's chapter", 6, regenerated.getChapters().size());
        boolean notesKept = false;
        for (OutlineChapter chapter : regenerated.getChapters()) {
            notesKept |= chapter.getTitle().equals("Field notes");
        }
        assertTrue("the user's own chapter is kept", notesKept);
        for (OutlineChapter chapter : generated.getChapters().subList(1, 4)) {
            assertTrue("unedited chapters keep their ids", regenerated.chapter(chapter.getId()) != null);
        }
        assertNotEquals(second, third);
        workspace.restore(first, third, 4L);
        assertEquals("the first revision can be restored", generated.getChapters().get(0).getTitle(),
                workspace.current().getChapters().get(0).getTitle());
    }

    private static float[] axis(int direction) {
        float[] values = new float[SynthesisFixture.DIMENSION];
        values[direction] = 1f;
        return values;
    }
}
