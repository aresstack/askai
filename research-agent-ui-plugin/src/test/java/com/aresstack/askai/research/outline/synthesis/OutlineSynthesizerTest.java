package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Phase 3b: the synthesis works in bounded, validated steps on {@code phase.outline}; an invalid answer gets one
 * repair and is never used; the revision rounds are limited; without a usable model the plan comes from the analysis.
 * Every model answer is scripted: no live model call.
 */
public class OutlineSynthesizerTest {

    private static final OutlinePlanReview NO_FINDINGS = new OutlinePlanReview() {
        public List<PlanFinding> review(OutlinePlan plan, SynthesisBrief brief) {
            return Collections.emptyList();
        }
    };

    private static final OutlinePlanReview ALWAYS_BLOCKING = new OutlinePlanReview() {
        public List<PlanFinding> review(OutlinePlan plan, SynthesisBrief brief) {
            return Collections.singletonList(PlanFinding.blocking("mixed-groups", "Chapter 1 mixes two areas."));
        }
    };

    /** One book frame with four independent research areas of two aspects each, and one requirement without evidence. */
    static SynthesisFixture fourAreas() {
        SynthesisFixture corpus = new SynthesisFixture();
        String[] names = {"helmets", "sensors", "privacy", "costs"};
        String[] labels = {"Safety", "Data", "Law", "Economics"};
        for (int i = 0; i < names.length; i++) {
            int base = corpus.diverse(names[i], 2, 4, 3);
            corpus.concept("c-" + names[i], labels[i], base);
        }
        corpus.concept("c-training", "Training", corpus.direction());
        return corpus;
    }

    private static String chapters(String... topicKeys) {
        StringBuilder json = new StringBuilder("{\"chapters\":[");
        for (int i = 0; i < topicKeys.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"title\":\"Area ").append(i + 1)
                    .append("\",\"purpose\":\"Explains area ").append(i + 1).append(".\",\"topics\":[");
            String[] keys = topicKeys[i].split("\\+");
            for (int k = 0; k < keys.length; k++) {
                json.append(k > 0 ? "," : "").append('"').append(keys[k]).append('"');
            }
            json.append("],\"concepts\":[\"C").append(i + 1).append("\"]}");
        }
        return json.append("],\"gaps\":[\"C5\"]}").toString();
    }

    private static String sections(String... keys) {
        StringBuilder json = new StringBuilder("{\"sections\":[");
        for (int i = 0; i < keys.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"title\":\"Part ").append(i + 1)
                    .append("\",\"purpose\":\"Covers part ").append(i + 1).append(".\",\"material\":[\"")
                    .append(keys[i]).append("\"]}");
        }
        return json.append("]}").toString();
    }

    private static ScriptedChat sectionsForAll(ScriptedChat chat, String... topicKeys) {
        for (String key : topicKeys) {
            chat.then(sections(key + ".1", key + ".2"));
        }
        return chat;
    }

    private static OutlineSynthesizer synthesizer(ScriptedChat chat, OutlinePlanReview review, int rounds) {
        return new OutlineSynthesizer(chat, review, new OutlineSynthesisSettings(rounds, 40, 240, 2, 2048, 1024, 300,
                0.2), null);
    }

    @Test
    public void theModelPlansChaptersThenSectionsFromTheAnalysis() {
        SynthesisBrief brief = fourAreas().brief();
        assertEquals("four research areas", 4, brief.getTopics().size());
        assertEquals("four themes and one requirement without evidence", 5, brief.getRequirements().size());
        ScriptedChat chat = sectionsForAll(new ScriptedChat().then(chapters("T1", "T2", "T3", "T4")),
                "T1", "T2", "T3", "T4");

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 2).synthesize(brief, null);

        OutlinePlan plan = result.getPlan();
        assertFalse(plan.isFromAnalysisOnly());
        assertEquals("four areas are four chapters, not one chapter with four sections", 4,
                plan.getChapters().size());
        for (PlannedChapter chapter : plan.getChapters()) {
            assertEquals("each area's two aspects are its sections", 2, chapter.getSections().size());
            assertFalse(chapter.isUntitled());
        }
        assertEquals(Collections.singletonList("C5"), plan.getGapKeys());
        assertEquals(0, result.getRevisions());
        assertTrue(result.getNotes().isEmpty());
        assertEquals("one plan call and one per chapter", 5, chat.calls.size());

        ScriptedChat.Call first = chat.calls.get(0);
        assertEquals(ChapterPlanContract.SYSTEM, first.messages.get(0).getContent());
        assertTrue("the model sees the gap", first.prompt().contains("C5 Training - gap: no evidence"));
        assertTrue("and the sub-topics", first.prompt().contains("T1.1"));
        assertTrue("keys are constrained by the schema", first.schema.contains("\"enum\""));
        assertEquals(2048, first.maxTokens);
        assertEquals(SectionPlanContract.SYSTEM, chat.calls.get(1).messages.get(0).getContent());
        assertEquals(1024, chat.calls.get(1).maxTokens);
    }

    @Test
    public void anInvalidAnswerGetsOneRepairWithItsErrors() {
        SynthesisBrief brief = fourAreas().brief();
        ScriptedChat chat = sectionsForAll(new ScriptedChat()
                .then(chapters("T1", "T2", "T3", "T9"))
                .then(chapters("T1", "T2", "T3", "T4")), "T1", "T2", "T3", "T4");

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 2).synthesize(brief, null);

        assertEquals(4, result.getPlan().getChapters().size());
        ScriptedChat.Call repair = chat.calls.get(1);
        assertEquals(ChatMessage.Role.ASSISTANT, repair.messages.get(2).getRole());
        assertTrue(repair.last().contains("unknown topic T9"));
    }

    @Test
    public void twoInvalidAnswersAreNeverUsedTheAnalysisPlanIsTaken() {
        SynthesisBrief brief = fourAreas().brief();
        ScriptedChat chat = new ScriptedChat().then("not json").then("{\"chapters\":[]}");

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 2).synthesize(brief, null);

        assertEquals("no section call after a failed plan", 2, chat.calls.size());
        OutlinePlan plan = result.getPlan();
        assertTrue(plan.isFromAnalysisOnly());
        assertEquals("independent groups become chapters", 4, plan.getChapters().size());
        for (PlannedChapter chapter : plan.getChapters()) {
            assertTrue(chapter.isUntitled());
            assertEquals("a major topic is split by its sub-topics", 2, chapter.getSections().size());
        }
        assertEquals(Collections.singletonList("C5"), plan.getGapKeys());
        assertTrue(result.getNotes().get(0).contains("no valid chapter plan"));
    }

    @Test
    public void aFailedCallIsNotRetriedAndTheAnalysisPlanIsTaken() {
        ScriptedChat chat = new ScriptedChat().thenFailure("timeout");
        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 2).synthesize(fourAreas().brief(), null);
        assertEquals(1, chat.calls.size());
        assertTrue(result.getPlan().isFromAnalysisOnly());
    }

    @Test
    public void aChapterWhoseSectionsFailIsSplitByItsMaterial() {
        ScriptedChat chat = new ScriptedChat().then(chapters("T1", "T2", "T3", "T4"))
                .then(sections("T9.1")).then(sections("T1.1", "T1.1"));
        sectionsForAll(chat, "T2", "T3", "T4");

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 2).synthesize(fourAreas().brief(), null);

        PlannedChapter first = result.getPlan().getChapters().get(0);
        assertFalse("the chapter keeps its model title", first.isUntitled());
        assertEquals(2, first.getSections().size());
        assertTrue(first.getSections().get(0).isUntitled());
        assertFalse(result.getPlan().getChapters().get(1).getSections().get(0).isUntitled());
        assertTrue(result.getNotes().get(0).contains("Area 1"));
    }

    @Test
    public void revisionRoundsAreLimitedAndUnchangedChaptersKeepTheirSections() {
        ScriptedChat chat = sectionsForAll(new ScriptedChat().then(chapters("T1", "T2", "T3", "T4")),
                "T1", "T2", "T3", "T4").then(chapters("T1", "T2", "T3", "T4"));

        OutlineSynthesizer.Result result = synthesizer(chat, ALWAYS_BLOCKING, 1).synthesize(fourAreas().brief(),
                null);

        assertEquals(1, result.getRevisions());
        assertEquals("plan, four sections, one revision; the same material needs no new sections", 6,
                chat.calls.size());
        assertTrue(chat.calls.get(5).prompt().contains("Chapter 1 mixes two areas."));
        assertTrue(result.getFindings().get(0).isBlocking());
        assertTrue(result.getNotes().get(result.getNotes().size() - 1).contains("still has findings after 1"));
        assertEquals(2, result.getPlan().getChapters().get(0).getSections().size());
    }

    @Test
    public void aRevisedChapterIsPlannedAgainOnlyWhenItsMaterialChanged() {
        final int[] reviews = {0};
        OutlinePlanReview onceBlocking = new OutlinePlanReview() {
            public List<PlanFinding> review(OutlinePlan plan, SynthesisBrief brief) {
                return reviews[0]++ == 0
                        ? Collections.singletonList(PlanFinding.blocking("thin", "Chapter 4 is thin."))
                        : Collections.<PlanFinding>emptyList();
            }
        };
        ScriptedChat chat = sectionsForAll(new ScriptedChat().then(chapters("T1", "T2", "T3", "T4")),
                "T1", "T2", "T3", "T4")
                .then(chapters("T1", "T2", "T3+T4"))
                .then(sections("T3", "T4"));

        OutlineSynthesizer.Result result = synthesizer(chat, onceBlocking, 2).synthesize(fourAreas().brief(), null);

        assertEquals(1, result.getRevisions());
        assertEquals("only the merged chapter is planned again", 7, chat.calls.size());
        assertEquals(3, result.getPlan().getChapters().size());
        assertTrue(result.getFindings().isEmpty());
    }

    @Test
    public void aCancelStopsBeforeTheNextCall() {
        ScriptedChat chat = new ScriptedChat();
        try {
            synthesizer(chat, NO_FINDINGS, 2).synthesize(fourAreas().brief(), new ClusteringMonitor() {
                public void progress(String stage, long done, long total) {
                }

                public boolean isCancelled() {
                    return true;
                }
            });
            fail("a cancelled synthesis must stop");
        } catch (ClusteringMonitor.ClusteringCancelledException expected) {
            assertTrue(chat.calls.isEmpty());
        }
    }

    @Test
    public void withoutAModelThePlanComesFromTheAnalysis() {
        OutlineSynthesizer.Result result = new OutlineSynthesizer(null, NO_FINDINGS, null, null)
                .synthesize(fourAreas().brief(), null);
        assertTrue(result.getPlan().isFromAnalysisOnly());
        assertEquals(4, result.getPlan().getChapters().size());
        assertTrue(result.getNotes().get(0).contains("No model"));
    }

    @Test
    public void withoutSignificantTopicsThereIsNothingToOutline() {
        SynthesisFixture corpus = new SynthesisFixture();
        corpus.plain("tiny", 2, 1);
        corpus.concept("c-a", "Safety", corpus.direction());
        OutlineSynthesizer.Result result = synthesizer(new ScriptedChat(), NO_FINDINGS, 2)
                .synthesize(corpus.brief(), null);
        assertTrue(result.getPlan().getChapters().isEmpty());
        assertEquals("the requirement stays visible as a gap", Collections.singletonList("C1"),
                result.getPlan().getGapKeys());
    }

    @Test
    public void aSmallContextGetsFewerTopicsAndSaysSo() {
        SynthesisBrief brief = fourAreas().brief();
        final int full = ChapterPlanContract.prompt(brief).length() / OutlineSynthesizer.CHARS_PER_TOKEN;
        final int three = ChapterPlanContract.prompt(brief.limitedTo(3)).length()
                / OutlineSynthesizer.CHARS_PER_TOKEN;
        ScriptedChat chat = sectionsForAll(new ScriptedChat().then(chapters("T1", "T2", "T3")), "T1", "T2", "T3");
        chat.contextTokens = (full + three) / 2 + 2048;
        chat.calls.clear();

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 2).synthesize(brief, null);

        assertEquals(3, result.getBrief().getTopics().size());
        assertEquals(1, result.getBrief().getOmittedTopics().size());
        assertTrue(result.getNotes().get(0).contains("3 of 4 topics"));
        assertFalse("the omitted topic is not offered to the model", chat.calls.get(0).schema.contains("\"T4\""));
    }

    @Test
    public void theRoleRunsOnThePhaseOutlineSlotWithThinkingOff() {
        ResearchRole role = OutlineSynthesizer.ROLE;
        assertEquals("outline-synthesis", role.getId());
        assertEquals(ResearchModelSlots.PHASE_OUTLINE, role.getSlot());
        assertEquals(ResearchRole.PromptSource.CALLER, role.getPromptSource());
        assertEquals(ThinkingMode.OFF, role.getThinking());
        assertNotNull(role.getOutput());
    }

    /**
     * Live run 4 after #210: every chapter named the whole large topic, every section call saw all of it, and three
     * chapters got the same section. Each section call now sees only the material its chapter will own, and a
     * revision keeps a chapter's sections when it owns the same parts again.
     */
    @Test
    public void eachSectionCallSeesOnlyTheMaterialItsChapterOwns() {
        SynthesisBrief brief = OutlineQualityCheckTest.oneTopicThreeConcepts().brief();
        String plan = "{\"chapters\":["
                + "{\"title\":\"Sensors\",\"purpose\":\"Sensors.\",\"topics\":[\"T1\"],\"concepts\":[\"C1\"]},"
                + "{\"title\":\"Safety\",\"purpose\":\"Safety.\",\"topics\":[\"T1\"],\"concepts\":[\"C2\"]},"
                + "{\"title\":\"Law\",\"purpose\":\"Law.\",\"topics\":[\"T1\"],\"concepts\":[\"C3\"]}],"
                + "\"gaps\":[]}";
        final int[] reviews = {0};
        OutlinePlanReview onceBlocking = new OutlinePlanReview() {
            public List<PlanFinding> review(OutlinePlan plan, SynthesisBrief brief) {
                return reviews[0]++ == 0
                        ? Collections.singletonList(PlanFinding.blocking("thin", "Chapter 3 is thin."))
                        : Collections.<PlanFinding>emptyList();
            }
        };
        ScriptedChat chat = new ScriptedChat().then(plan)
                .then(sections("T1.1")).then(sections("T1.2")).then(sections("T1.3"))
                .then(plan);

        OutlineSynthesizer.Result result = synthesizer(chat, onceBlocking, 1).synthesize(brief, null);

        assertEquals(1, result.getRevisions());
        assertEquals("the revision owns the same parts, so every chapter keeps its sections", 5, chat.calls.size());
        for (int i = 1; i <= 3; i++) {
            String schema = chat.calls.get(i).schema;
            for (int j = 1; j <= 3; j++) {
                assertEquals("section call " + i + " and T1." + j, i == j, schema.contains("\"T1." + j + "\""));
            }
        }
        for (int i = 0; i < 3; i++) {
            assertEquals(Collections.singletonList("T1." + (i + 1)),
                    result.getPlan().getChapters().get(i).getSections().get(0).getMaterialKeys());
        }
    }

    /** Live run 4: section 1.2 carried the title of chapter 1. Such an answer is refused and repaired. */
    @Test
    public void aSectionTitleRepeatingTheChapterTitleIsRepaired() {
        String repeating = "{\"sections\":[{\"title\":\"area  1\",\"purpose\":\"Covers it.\","
                + "\"material\":[\"T1.1\",\"T1.2\"]}]}";
        ScriptedChat chat = new ScriptedChat().then(chapters("T1", "T2", "T3", "T4")).then(repeating)
                .then(sections("T1.1", "T1.2"));
        sectionsForAll(chat, "T2", "T3", "T4");

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 0).synthesize(fourAreas().brief(), null);

        assertEquals("plan, a refused and a repaired section answer, three more chapters", 6, chat.calls.size());
        String repair = chat.calls.get(2).messages.get(chat.calls.get(2).messages.size() - 1).getContent();
        assertTrue(repair, repair.contains("repeats the chapter title"));
        assertEquals("Part 1", result.getPlan().getChapters().get(0).getSections().get(0).getTitle());
    }

    private static final String THREE_CHAPTERS_ON_T1 = "{\"chapters\":["
            + "{\"title\":\"Helmets\",\"purpose\":\"Helmets.\",\"topics\":[\"T1\"],\"concepts\":[]},"
            + "{\"title\":\"Location\",\"purpose\":\"Location.\",\"topics\":[\"T1\"],\"concepts\":[]},"
            + "{\"title\":\"Law\",\"purpose\":\"Law.\",\"topics\":[\"T1\"],\"concepts\":[]}],"
            + "\"gaps\":[]}";

    /**
     * Live outline acceptance 2026-11-12 (bge-m3): four areas formed one topic, four chapters named it, and the
     * first chapter kept everything. The model now divides the topic's sub-topics among the chapters naming it.
     */
    @Test
    public void aTopicSeveralChaptersNameIsDividedAmongThemBySubtopic() {
        SynthesisFixture corpus = new SynthesisFixture();
        corpus.diverse("sites", 4, 6, 3);
        SynthesisBrief brief = corpus.brief();
        assertEquals(1, brief.getTopics().size());
        assertEquals(4, brief.getTopics().get(0).getSubtopics().size());
        ScriptedChat chat = new ScriptedChat().then(THREE_CHAPTERS_ON_T1)
                .then("{\"T1.1\":1,\"T1.2\":2,\"T1.3\":3,\"T1.4\":3}")
                .then(sections("T1.1")).then(sections("T1.2")).then(sections("T1.3", "T1.4"));

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 0).synthesize(brief, null);

        assertEquals("plan, one division, three section calls", 5, chat.calls.size());
        ScriptedChat.Call division = chat.calls.get(1);
        assertTrue(division.prompt(), division.prompt().contains("1 \"Helmets\""));
        assertTrue(division.prompt().contains("T1.4 "));
        assertTrue(division.schema, division.schema.contains("\"enum\":[1,2,3]"));
        List<PlannedChapter> chapters = result.getPlan().getChapters();
        assertEquals(3, chapters.size());
        assertEquals(Collections.singletonList("T1.1"), chapters.get(0).getMaterialKeys());
        assertEquals(Collections.singletonList("T1.2"), chapters.get(1).getMaterialKeys());
        assertEquals(java.util.Arrays.asList("T1.3", "T1.4"), chapters.get(2).getMaterialKeys());
        assertTrue(String.join("\n", result.getNotes()).contains("its sub-topics were divided among them"));
    }

    @Test
    public void aDivisionNamingAChapterOutsideTheTopicIsRepairedAndAChapterWithoutAPartIsDropped() {
        SynthesisFixture corpus = new SynthesisFixture();
        corpus.diverse("sites", 4, 6, 3);
        SynthesisBrief brief = corpus.brief();
        ScriptedChat chat = new ScriptedChat().then(THREE_CHAPTERS_ON_T1)
                .then("{\"T1.1\":1,\"T1.2\":7,\"T1.3\":3,\"T1.4\":3}")
                .then("{\"T1.1\":1,\"T1.2\":1,\"T1.3\":3,\"T1.4\":3}")
                .then(sections("T1.1", "T1.2")).then(sections("T1.3", "T1.4"));

        OutlineSynthesizer.Result result = synthesizer(chat, NO_FINDINGS, 0).synthesize(brief, null);

        assertTrue(chat.calls.get(2).last().contains("T1.2 names chapter 7, which does not name T1"));
        assertEquals(2, result.getPlan().getChapters().size());
        assertEquals("Law", result.getPlan().getChapters().get(1).getTitle());
        assertTrue(String.join("\n", result.getNotes()).contains("\"Location\" got no part of T1"));
    }
}
