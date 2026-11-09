package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class OutlineBriefWorkerTest {

    static final BriefContext CONTEXT = new BriefContext("Wärmepumpen", "Ein Ratgeber", "German",
            Arrays.asList("Kernkraft"));

    /** A chat that answers from a queue (or with a function of the prompt) and records each call's budget. */
    static final class ScriptedChat implements MainModelChat {
        final String name;
        final Deque<String> answers = new ArrayDeque<String>();
        final List<Integer> budgets = new ArrayList<Integer>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        java.util.function.Function<String, String> byPrompt;

        ScriptedChat(String name, String... answers) {
            this.name = name;
            this.answers.addAll(Arrays.asList(answers));
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            budgets.add(maxOutputTokens);
            calls.add(new ArrayList<ChatMessage>(messages));
            if (byPrompt != null) {
                return MainModelChatResult.ok(byPrompt.apply(messages.get(1).getContent()));
            }
            return MainModelChatResult.ok(answers.isEmpty() ? "not json" : answers.poll());
        }

        @Override
        public String modelName() {
            return name;
        }
    }

    static String briefAnswer(int sections) {
        StringBuilder json = new StringBuilder("{\"sections\":[");
        for (int i = 1; i <= sections; i++) {
            json.append(i > 1 ? "," : "").append("{\"index\":").append(i).append(",\"purpose\":\"Zweck ")
                    .append(i).append("\",\"keyPoints\":[\"a\",\"b\",\"c\"]}");
        }
        return json.append("]}").toString();
    }


    static Outline.IdSource sequence() {
        final int[] next = {0};
        return new Outline.IdSource() {
            @Override
            public String next(String prefix) {
                return prefix + (++next[0]);
            }
        };
    }

    static OutlineSection section(String title, String node, String cluster, int words, String... passages) {
        return OutlineSection.draft(title, new OutlineBrief("default " + title, Arrays.asList("p"), words),
                new OutlineProvenance(node, cluster == null ? Collections.<String>emptyList()
                        : Collections.singletonList(cluster), Arrays.asList(passages)));
    }

    static Outline stored() {
        OutlineChapter concept = OutlineChapter.draft("Grundlagen", OutlineBrief.EMPTY,
                new OutlineProvenance("n1", null, null), Arrays.asList(
                        section("Physik", "n1a", null, 500, "p01"), section("Geschichte", "n1b", null, 500, "p02")));
        OutlineChapter clustered = OutlineChapter.draft("Praxis", OutlineBrief.EMPTY,
                new OutlineProvenance("n2", null, null), Arrays.asList(
                        section("Praxis, part 1", "n2", "c1", 600, "p10", "p11", "p12", "p13"),
                        section("Praxis, part 2", "n2", "c2", 600, "p14", "p15", "p16", "p17")));
        OutlineChapter last = OutlineChapter.draft("Ausblick", OutlineBrief.EMPTY,
                new OutlineProvenance("n3", null, null), Arrays.asList(section("Zukunft", "n3a", null, 400, "p20")));
        return new Outline(Arrays.asList(concept, clustered, last)).placedAgainst(Outline.EMPTY, sequence());
    }

    static final class Passages implements OutlinePassages {
        final Map<String, float[]> vectors = new LinkedHashMap<String, float[]>();

        Passages() {
            // four directions, two passages each, inside chapter "Praxis"
            float[][] axes = {{1, 0, 0, 0}, {0, 1, 0, 0}, {0, 0, 1, 0}, {0, 0, 0, 1}};
            for (int i = 0; i < 8; i++) {
                float[] v = axes[i / 2].clone();
                v[(i / 2 + 1) % 4] = 0.05f * (i % 2);
                vectors.put("p1" + i, v);
            }
        }

        @Override
        public Map<String, float[]> vectors(List<String> passageIds) {
            Map<String, float[]> found = new HashMap<String, float[]>();
            for (String id : passageIds) {
                if (vectors.containsKey(id)) {
                    found.put(id, vectors.get(id));
                }
            }
            return found;
        }

        @Override
        public String text(String passageId) {
            return "Text von " + passageId;
        }
    }

    @Test
    public void invalidJsonTwiceKeepsTheDefaultBriefAndTheOutlineStaysApprovable() {
        ScriptedChat briefs = new ScriptedChat("phase.outline.briefs", "{oops", "[]");
        OutlineBriefWorker worker = new OutlineBriefWorker(new ScriptedChat("phase.outline.labels"), briefs, null);
        OutlineChapter chapter = stored().getChapters().get(0);

        OutlineBriefWorker.ChapterBriefs result = worker.brief(CONTEXT, chapter, null);

        assertFalse(result.isGenerated());
        assertTrue(result.getFailure().startsWith("Brief not generated"));
        assertEquals(chapter, result.getChapter());
        assertEquals(2, briefs.calls.size());
        assertTrue(briefs.calls.get(1).get(3).getContent().contains("Your answer was rejected"));
        assertTrue(new Outline(Collections.singletonList(result.getChapter())).hasChapterWithSection());
    }

    @Test
    public void aValidBriefKeepsTheWordTargetsAndStaysWithinItsBudget() {
        ScriptedChat briefs = new ScriptedChat("phase.outline.briefs", briefAnswer(2));
        List<BriefCallTelemetry> telemetry = new ArrayList<BriefCallTelemetry>();
        OutlineBriefWorker worker = new OutlineBriefWorker(new ScriptedChat("labels"), briefs, telemetry::add);

        OutlineBriefWorker.ChapterBriefs result = worker.brief(CONTEXT, stored().getChapters().get(0), null);

        assertTrue(result.isGenerated());
        OutlineSection first = result.getChapter().getSections().get(0);
        assertEquals("Zweck 1", first.getBrief().getPurpose());
        assertEquals(Arrays.asList("a", "b", "c"), first.getBrief().getKeyPoints());
        assertEquals(500, first.getBrief().getWordTarget());
        assertEquals(Collections.singletonList(400), briefs.budgets);
        assertEquals(1, telemetry.size());
        assertTrue(telemetry.get(0).isAccepted());
        assertTrue(briefs.calls.get(0).get(1).getContent().contains("Kernkraft"));
    }

    @Test
    public void aChapterWithMoreThanSixSectionsIsBriefedInTwoCalls() {
        List<OutlineSection> sections = new ArrayList<OutlineSection>();
        for (int i = 0; i < 8; i++) {
            sections.add(section("S" + i, "n" + i, null, 100, "p" + i));
        }
        OutlineChapter chapter = OutlineChapter.draft("Groß", OutlineBrief.EMPTY, null, sections);
        ScriptedChat briefs = new ScriptedChat("briefs", briefAnswer(4), briefAnswer(4));
        OutlineBriefWorker worker = new OutlineBriefWorker(new ScriptedChat("labels"), briefs, null);

        OutlineBriefWorker.ChapterBriefs result = worker.brief(CONTEXT, chapter, null);

        assertTrue(result.isGenerated());
        assertEquals(Arrays.asList(700, 700), briefs.budgets);
        assertEquals("Zweck 4", result.getChapter().getSections().get(7).getBrief().getPurpose());
    }

    @Test
    public void theReaderRefusesDuplicateAndMissingIndexes() {
        BriefAnswerException refused = assertThrows(BriefAnswerException.class, () -> BriefContract.read(
                "{\"sections\":[{\"index\":1,\"purpose\":\"x\",\"keyPoints\":[\"a\",\"b\",\"c\"]},"
                        + "{\"index\":1,\"purpose\":\"y\",\"keyPoints\":[\"a\",\"b\",\"c\"]}]}", 2));
        assertTrue(refused.errors().contains("index 1 occurs more than once"));
        assertTrue(refused.errors().contains("section 2 is missing"));
    }

    @Test
    public void labelsAreShortNounPhrasesAndARefusedLabelIsRepairedOnce() {
        ScriptedChat labels = new ScriptedChat("labels",
                "{\"title\":\"Dies ist ein viel zu langer Titel für einen Abschnitt im Buch.\",\"summary\":\"s\"}",
                "{\"title\":\"Wärmepumpen im Altbau\",\"summary\":\"Wie Altbauten umgerüstet werden.\"}");
        OutlineBriefWorker worker = new OutlineBriefWorker(labels, new ScriptedChat("briefs"), null);

        OutlineLabel label = worker.label(CONTEXT, "c9", "Praxis", Arrays.asList("Text"), null);

        assertEquals("Wärmepumpen im Altbau", label.getTitle());
        assertEquals(2, labels.calls.size());
        assertEquals(Collections.nCopies(2, LabelContract.BUDGET), labels.budgets);
        assertNull(new OutlineBriefWorker(new ScriptedChat("labels", "x", "y"), new ScriptedChat("b"), null)
                .label(CONTEXT, "c9", "", Arrays.asList("Text"), null));
    }

    @Test
    public void regeneratingASubClusterChapterWithFourSectionsChangesOnlyThatChapter() {
        Outline stored = stored();
        ScriptedChat labels = new ScriptedChat("labels");
        labels.byPrompt = prompt -> "{\"title\":\"Thema " + (prompt.contains("p10") ? "A" : prompt.contains("p12")
                ? "B" : prompt.contains("p14") ? "C" : "D") + "\",\"summary\":\"Kurz.\"}";
        ScriptedChat briefs = new ScriptedChat("briefs", briefAnswer(4));
        ChapterRegenerator regenerator = new ChapterRegenerator(new OutlineBriefWorker(labels, briefs, null),
                new Passages());
        String chapterId = stored.getChapters().get(1).getId();

        ChapterRegenerator.Result result = regenerator.regenerate(stored, CONTEXT, chapterId, 4, "mehr Praxis");
        Outline outline = result.getOutline().placedAgainst(stored, sequence());

        assertNull(result.getBriefFailure());
        OutlineChapter regenerated = outline.getChapters().get(1);
        assertEquals(chapterId, regenerated.getId());
        assertEquals(4, regenerated.getSections().size());
        assertEquals(Arrays.asList("Thema A", "Thema B", "Thema C", "Thema D"), Arrays.asList(
                regenerated.getSections().get(0).getTitle(), regenerated.getSections().get(1).getTitle(),
                regenerated.getSections().get(2).getTitle(), regenerated.getSections().get(3).getTitle()));
        assertEquals(Arrays.asList("p10", "p11"), regenerated.getSections().get(0).getProvenance().getPassageIds());
        assertEquals(300, regenerated.getSections().get(0).getBrief().getWordTarget());
        for (OutlineSection section : regenerated.getSections()) {
            assertFalse(stored.allIds().contains(section.getId()));
            assertEquals(chapterId, section.getParentId());
        }
        assertEquals(stored.getChapters().get(0), outline.getChapters().get(0));
        assertEquals(stored.getChapters().get(2), outline.getChapters().get(2));
        assertTrue(labels.calls.get(0).get(1).getContent().contains("mehr Praxis"));
        assertTrue(briefs.calls.get(0).get(1).getContent().contains("mehr Praxis"));
    }

    @Test
    public void aConceptChapterCannotBeResplitButCanBeBriefedAgain() {
        Outline stored = stored();
        ChapterRegenerator regenerator = new ChapterRegenerator(new OutlineBriefWorker(new ScriptedChat("l"),
                new ScriptedChat("b", briefAnswer(2)), null), new Passages());
        String chapterId = stored.getChapters().get(0).getId();

        assertThrows(IllegalArgumentException.class, () -> regenerator.regenerate(stored, CONTEXT, chapterId, 3, ""));
        Outline briefed = regenerator.regenerate(stored, CONTEXT, chapterId, 0, "").getOutline();

        assertEquals(stored.getChapters().get(0).getSections().get(0).getId(),
                briefed.getChapters().get(0).getSections().get(0).getId());
        assertEquals("Zweck 1", briefed.getChapters().get(0).getSections().get(0).getBrief().getPurpose());
        assertThrows(IllegalArgumentException.class, () -> regenerator.regenerate(stored, CONTEXT,
                stored.getChapters().get(1).getId(), 7, ""));
    }

    @Test
    public void anAdoptedCandidateBecomesAChapterWithFreshIds() {
        Outline stored = stored();
        ChapterRegenerator regenerator = new ChapterRegenerator(new OutlineBriefWorker(
                new ScriptedChat("l", "{\"title\":\"Förderprogramme\",\"summary\":\"Zuschüsse.\"}"),
                new ScriptedChat("b", briefAnswer(1)), null), new Passages());

        Outline adopted = regenerator.adopt(stored, CONTEXT, "c7", "Förderung", Arrays.asList("p30", "p31"),
                Arrays.asList("p30"), 500).getOutline().placedAgainst(stored, sequence());

        assertEquals(4, adopted.getChapters().size());
        OutlineChapter chapter = adopted.getChapters().get(3);
        assertEquals("Förderprogramme", chapter.getTitle());
        assertFalse(stored.allIds().contains(chapter.getId()));
        assertNotEquals("", chapter.getSections().get(0).getId());
        assertEquals(Arrays.asList("p30", "p31"), chapter.getSections().get(0).getProvenance().getPassageIds());
        assertEquals(stored.getChapters(), adopted.getChapters().subList(0, 3));
    }

    @Test
    public void reclusteringIsDeterministic() {
        Passages passages = new Passages();
        assertEquals(SectionReclusterer.split(passages.vectors, 3), SectionReclusterer.split(passages.vectors, 3));
        assertThrows(IllegalArgumentException.class, () -> SectionReclusterer.split(passages.vectors, 9));
    }
}
