package com.aresstack.askai.research.outline.synthesis;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Phase 3c: the deterministic review and its safe fixes, without a model. */
public class OutlineQualityCheckTest {

    private static final OutlineQualityCheck CHECK = new OutlineQualityCheck(OutlineLengthSettings.defaults());

    private static PlannedSection section(String title, String... keys) {
        return new PlannedSection(title, "", Arrays.asList(keys), false);
    }

    private static PlannedChapter chapter(String title, List<String> material, List<String> concepts,
                                          PlannedSection... sections) {
        return new PlannedChapter(title, "Purpose.", material, concepts, Arrays.asList(sections), false);
    }

    private static boolean has(List<PlanFinding> findings, String code, boolean blocking) {
        for (PlanFinding finding : findings) {
            if (finding.getCode().equals(code) && finding.isBlocking() == blocking) {
                return true;
            }
        }
        return false;
    }

    @Test
    public void aGoodPlanHasNoBlockingFinding() {
        SynthesisBrief brief = OutlineSynthesizerTest.fourAreas().brief();
        OutlinePlan plan = FallbackOutlinePlan.plan(brief);
        for (PlanFinding finding : CHECK.review(plan, brief)) {
            assertFalse(finding.toString(), finding.isBlocking());
        }
    }

    @Test
    public void unusedTwiceUsedAndLostMaterialIsBlocking() {
        SynthesisBrief brief = OutlineSynthesizerTest.fourAreas().brief();
        OutlinePlan plan = new OutlinePlan(Arrays.asList(
                chapter("Safety", Arrays.asList("T1", "T2.1"), Collections.singletonList("C1"),
                        section("A", "T1"), section("B", "T2.1")),
                chapter("Data", Arrays.asList("T2"), Collections.<String>emptyList(), section("C", "T2")),
                chapter("Safety", Arrays.asList("T3"), Collections.<String>emptyList(), section("D", "T3.1", "T3.2"))),
                Collections.<String>emptyList(), false);

        List<PlanFinding> findings = CHECK.review(plan, brief);

        assertTrue(has(findings, OutlineQualityCheck.MATERIAL_TWICE, true));
        assertTrue("T4 is in no chapter", has(findings, OutlineQualityCheck.TOPIC_UNUSED, true));
        assertTrue("C5 is neither treated nor a gap", has(findings, OutlineQualityCheck.REQUIREMENT_LOST, true));
        assertTrue(has(findings, OutlineQualityCheck.DUPLICATE_TITLES, true));
        assertTrue("T1 as a whole in one section", has(findings, OutlineQualityCheck.MAJOR_TOPIC_FLAT, false));
        assertTrue("blocking findings come first", findings.get(0).isBlocking());
    }

    @Test
    public void theSafeFixesResolveWhatCodeCanResolve() {
        SynthesisBrief brief = OutlineSynthesizerTest.fourAreas().brief();
        OutlinePlan plan = new OutlinePlan(Arrays.asList(
                chapter("Safety", Arrays.asList("T1", "T2.1"), Arrays.asList("C1", "C5"),
                        section("A", "T1"), section("B", "T2.1")),
                chapter("Data", Arrays.asList("T2"), Collections.<String>emptyList(), section("C", "T2")),
                chapter("Law", Arrays.asList("T3"), Collections.<String>emptyList())),
                Collections.<String>emptyList(), false);

        OutlineQualityCheck.Fixed fixed = CHECK.fix(plan, brief);
        List<PlanFinding> findings = CHECK.review(fixed.getPlan(), brief);

        for (PlanFinding finding : findings) {
            assertFalse(finding.toString(), finding.isBlocking());
        }
        PlannedChapter data = fixed.getPlan().getChapters().get(1);
        assertEquals("T2.1 stays with its first chapter; Data keeps T2.2", Collections.singletonList("T2.2"),
                data.getMaterialKeys());
        assertEquals("T4 became a chapter", 4, fixed.getPlan().getChapters().size());
        assertEquals(Collections.singletonList("C5"), fixed.getPlan().getGapKeys());
        assertFalse(fixed.getPlan().getChapters().get(0).getConceptKeys().contains("C5"));
        assertEquals("T1 split by its sub-topics", 3, fixed.getPlan().getChapters().get(0).getSections().size());
        assertEquals("Law got sections from its material", 2,
                fixed.getPlan().getChapters().get(2).getSections().size());
        assertTrue(fixed.getNotes().size() >= 4);
    }

    @Test
    public void theNumberOfSectionsIsCheckedAgainstTheBookLength() {
        SynthesisFixture corpus = OutlineSynthesizerTest.fourAreas();
        corpus.deliverable = "120 pages";
        SynthesisBrief brief = corpus.brief();
        List<PlanFinding> findings = CHECK.review(FallbackOutlinePlan.plan(brief), brief);
        assertTrue("36000 words over 8 sections", has(findings, OutlineQualityCheck.TOO_FEW_SECTIONS, false));

        corpus.deliverable = "2000 words";
        brief = corpus.brief();
        assertTrue(has(CHECK.review(FallbackOutlinePlan.plan(brief), brief), OutlineQualityCheck.TOO_MANY_SECTIONS,
                false));
    }

    @Test
    public void bookLengthsAreReadInPagesOrWords() {
        OutlineLengthSettings length = OutlineLengthSettings.defaults();
        assertEquals(36000, length.targetWords("120 pages"));
        assertEquals(25000, length.targetWords("20000-30000 words"));
        assertEquals(30000, length.targetWords("ca. 100 Seiten"));
        assertEquals(0, length.targetWords("a practical book"));
        assertEquals(700, length.unboundedTarget(3));
        assertEquals("capped", 1100, length.unboundedTarget(30));
    }

    /** One topic holding the material of three independent concept nodes, one aspect each (live run 4). */
    static SynthesisFixture oneTopicThreeConcepts() {
        SynthesisFixture corpus = new SynthesisFixture();
        int perAspect = 6;
        int base = corpus.diverse("sites", 3, perAspect, 3);
        corpus.concept("c-sensors", "Sensors", base + 1);
        corpus.concept("c-safety", "Safety", base + 1 + (perAspect + 1));
        corpus.concept("c-law", "Law", base + 1 + 2 * (perAspect + 1));
        return corpus;
    }

    /**
     * Live run 4: every chapter named the one large topic with its own requirement; first-come ownership gave the
     * whole topic to the first chapter and dropped the other chapters as repeated material. Each concept part now
     * stays with the chapter treating its requirement, and the prompt tells the model which part is whose.
     */
    @Test
    public void eachConceptPartStaysWithTheChapterOfItsRequirement() {
        SynthesisBrief brief = oneTopicThreeConcepts().brief();
        assertEquals(1, brief.getTopics().size());
        SynthesisBrief.TopicEntry topic = brief.getTopics().get(0);
        assertEquals("", topic.getConceptKey());
        assertEquals(3, topic.getSubtopics().size());
        assertEquals("C1", brief.subtopic("T1.1").getConceptKey());
        assertEquals("C2", brief.subtopic("T1.2").getConceptKey());
        assertEquals("C3", brief.subtopic("T1.3").getConceptKey());
        String prompt = ChapterPlanContract.prompt(brief);
        assertTrue(prompt, prompt.contains("holds the material of concepts C1, C2, C3 (one sub-topic each)"));
        assertTrue(prompt, prompt.contains("distinct; concept C2)"));

        OutlinePlan plan = new OutlinePlan(Arrays.asList(
                chapter("Sensors", Collections.singletonList("T1"), Collections.singletonList("C1"),
                        section("A", "T1")),
                chapter("Safety", Collections.singletonList("T1"), Collections.singletonList("C2"),
                        section("B", "T1")),
                chapter("Law", Collections.singletonList("T1"), Collections.singletonList("C3"),
                        section("C", "T1"))),
                Collections.<String>emptyList(), false);

        OutlinePlan fixed = CHECK.fix(plan, brief).getPlan();

        assertEquals("no chapter is dropped as repeated material", 3, fixed.getChapters().size());
        assertEquals(Collections.singletonList("T1.1"), fixed.getChapters().get(0).getMaterialKeys());
        assertEquals(Collections.singletonList("T1.2"), fixed.getChapters().get(1).getMaterialKeys());
        assertEquals(Collections.singletonList("T1.3"), fixed.getChapters().get(2).getMaterialKeys());
        assertTrue(fixed.getGapKeys().isEmpty());
    }

    /** An unused concept part joins the chapter of its requirement, not the chapter with most group material. */
    @Test
    public void anUnusedConceptPartJoinsTheChapterOfItsRequirement() {
        SynthesisBrief brief = oneTopicThreeConcepts().brief();
        OutlinePlan plan = new OutlinePlan(Arrays.asList(
                chapter("Sensors", Arrays.asList("T1.1", "T1.2"), Collections.singletonList("C1"),
                        section("A", "T1.1", "T1.2")),
                chapter("Law", Collections.singletonList("T1.2"), Arrays.asList("C2", "C3"),
                        section("C", "T1.2"))),
                Collections.<String>emptyList(), false);

        OutlinePlan fixed = CHECK.fix(plan, brief).getPlan();

        assertEquals(2, fixed.getChapters().size());
        assertEquals(Collections.singletonList("T1.1"), fixed.getChapters().get(0).getMaterialKeys());
        assertEquals(Arrays.asList("T1.2", "T1.3"), fixed.getChapters().get(1).getMaterialKeys());
    }

    /**
     * The rest of a topic (passages assigned to no concept node) is used once, even when its concept parts end up in
     * different chapters.
     */
    @Test
    public void theRestOfATopicSplitOverChaptersIsUsedOnce() {
        SynthesisFixture corpus = new SynthesisFixture();
        int perAspect = 6;
        int base = corpus.diverse("sites", 2, perAspect, 3);
        corpus.rest("sites", base, 4, 3);
        corpus.concept("c-sensors", "Sensors", base + 1);
        corpus.concept("c-safety", "Safety", base + 1 + (perAspect + 1));
        SynthesisBrief brief = corpus.brief();
        assertEquals(2, brief.getTopics().get(0).getSubtopics().size());
        assertEquals(4, brief.restOf("T1").size());
        OutlinePlan plan = CHECK.fix(new OutlinePlan(Arrays.asList(
                chapter("Sensors", Collections.singletonList("T1"), Collections.singletonList("C1"),
                        section("A", "T1")),
                chapter("Safety", Collections.singletonList("T1"), Collections.singletonList("C2"),
                        section("B", "T1"))),
                Collections.<String>emptyList(), false), brief).getPlan();

        com.aresstack.askai.research.outline.Outline outline = new OutlineAssembler(OutlineLengthSettings.defaults())
                .assemble(plan, brief, "snap", Collections.<PlanFinding>emptyList(),
                        Collections.<String>emptyList());

        assertEquals(2, outline.getChapters().size());
        List<String> used = new java.util.ArrayList<String>();
        for (com.aresstack.askai.research.outline.OutlineChapter chapter : outline.getChapters()) {
            for (com.aresstack.askai.research.outline.OutlineSection section : chapter.getSections()) {
                used.addAll(section.getProvenance().getPassageIds());
            }
        }
        assertEquals("every passage of the topic once", brief.passagesOf("T1").size(), used.size());
        assertEquals(brief.passagesOf("T1").size(), new java.util.HashSet<String>(used).size());
    }

    /**
     * Live run 4 after #204: one aspect per concept node gave every chapter one section, also the chapter of 809
     * passages. A node's material that the sub-topic cut separates gives several aspects of that node; a used
     * concept part treats its requirement even when no chapter names it.
     */
    @Test
    public void aConceptNodesMaterialKeepsItsSubtopicsAndAUsedPartTreatsItsRequirement() {
        SynthesisFixture corpus = new SynthesisFixture();
        int perAspect = 6;
        int base = corpus.diverse("sites", 4, perAspect, 3);
        int step = perAspect + 1;
        corpus.conceptAlong("c-sensors", "Sensors", base + 1, base + 1 + step);
        corpus.conceptAlong("c-safety", "Safety", base + 1 + 2 * step, base + 1 + 3 * step);
        SynthesisBrief brief = corpus.brief();
        List<SynthesisBrief.SubtopicEntry> parts = brief.getTopics().get(0).getSubtopics();
        assertEquals("two aspects per concept node", 4, parts.size());
        assertEquals("C1", parts.get(0).getConceptKey());
        assertEquals("C1", parts.get(1).getConceptKey());
        assertEquals("C2", parts.get(2).getConceptKey());
        assertEquals("C2", parts.get(3).getConceptKey());

        OutlinePlan plan = new OutlinePlan(Collections.singletonList(
                chapter("Sensors", Collections.singletonList("T1"), Collections.singletonList("C1"),
                        section("A", "T1.1", "T1.2"), section("B", "T1.3", "T1.4"))),
                Collections.<String>emptyList(), false);

        OutlinePlan fixed = CHECK.fix(plan, brief).getPlan();

        assertTrue("the safety parts are used, so safety is treated: " + fixed.getGapKeys(),
                fixed.getGapKeys().isEmpty());
        for (PlanFinding finding : CHECK.review(fixed, brief)) {
            assertFalse(finding.toString(), finding.getCode().equals(OutlineQualityCheck.REQUIREMENT_LOST));
        }
    }

    /**
     * Live run 4 after #206: the model treated C2 in a chapter and listed it as a gap as well; the outline reported
     * "evidence exists, but no chapter treats it". A treated requirement with evidence leaves the gaps.
     */
    @Test
    public void aTreatedRequirementWithEvidenceIsNoGap() {
        SynthesisBrief brief = oneTopicThreeConcepts().brief();
        OutlinePlan plan = new OutlinePlan(Arrays.asList(
                chapter("Sensors", Collections.singletonList("T1.1"), Collections.singletonList("C1"),
                        section("A", "T1.1")),
                chapter("Safety", Collections.singletonList("T1.2"), Collections.singletonList("C2"),
                        section("B", "T1.2")),
                chapter("Law", Collections.singletonList("T1.3"), Collections.singletonList("C3"),
                        section("C", "T1.3"))),
                Collections.singletonList("C2"), false);
        assertTrue(has(CHECK.review(plan, brief), OutlineQualityCheck.REQUIREMENT_LOST, false));

        OutlineQualityCheck.Fixed fixed = CHECK.fix(plan, brief);

        assertTrue(fixed.getPlan().getGapKeys().toString(), fixed.getPlan().getGapKeys().isEmpty());
        assertFalse(has(CHECK.review(fixed.getPlan(), brief), OutlineQualityCheck.REQUIREMENT_LOST, false));
    }

    /**
     * Live run 4 after #207: six of eight section answers were refused for leaving out a small advertising topic, and
     * code split the chapters by material. Material the answer leaves out becomes untitled sections; the model's
     * sections stay.
     */
    @Test
    public void materialASectionAnswerLeavesOutBecomesSectionsOfItsOwn() throws Exception {
        SynthesisBrief brief = OutlineSynthesizerTest.fourAreas().brief();
        PlannedChapter chapter = new PlannedChapter("Safety", "Purpose.", Arrays.asList("T1", "T4"),
                Collections.<String>emptyList(), null, false);

        List<PlannedSection> sections = SectionPlanContract.read("{\"sections\":[{\"title\":\"Helmets\","
                + "\"purpose\":\"Why helmets.\",\"material\":[\"T1\"]}]}", brief, chapter);

        assertEquals("Helmets", sections.get(0).getTitle());
        assertFalse(sections.get(0).isUntitled());
        List<String> rest = new java.util.ArrayList<String>();
        for (PlannedSection section : sections.subList(1, sections.size())) {
            assertTrue(section.isUntitled());
            rest.addAll(section.getMaterialKeys());
        }
        assertEquals(OutlineQualityCheck.units(brief, "T4").size() == 1 ? Collections.singletonList("T4")
                : OutlineQualityCheck.units(brief, "T4"), rest);
    }
}
