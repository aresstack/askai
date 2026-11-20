package com.aresstack.askai.research.runtime.writer;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The section plan: parts that know their place and the text before them, and passages assigned by key point. */
public class SectionPlanTest {

    /** "Helm" points one way, "Gas" another, everything else a third. */
    private static final SentenceVectors FAKE = new SentenceVectors() {
        public List<float[]> embed(List<String> sentences) {
            List<float[]> out = new ArrayList<float[]>();
            for (String s : sentences) {
                out.add(s.contains("Helm") ? new float[]{1, 0, 0} : s.contains("Gas") ? new float[]{0, 1, 0}
                        : new float[]{0, 0, 1});
            }
            return out;
        }
    };

    private static EvidencePassage passage(int n, String text) {
        return new EvidencePassage(n, "src-" + n, "sha-" + n, "p-" + n, text);
    }

    private static List<EvidencePassage> pack() {
        return Arrays.asList(
                passage(1, "Ein Gaswarner meldet giftige Gase in der Baugrube und warnt die Beschäftigten rechtzeitig."),
                passage(2, "Der smarte Helm misst Stöße und meldet einen Sturz sofort an die Bauleitung weiter."),
                passage(3, "Gassensoren prüfen die Luftqualität fortlaufend und lösen bei Gefahr einen Alarm aus."),
                passage(4, "Im Helm sitzen Sensoren für Temperatur und Lage, die fortlaufend ihre Werte senden."));
    }

    private static SectionAssignment.Builder section() {
        return SectionAssignment.builder("s1").title("Wearables").brief("Geräte auf der Baustelle")
                .keyPoints(Arrays.asList("Helme", "Gaswarner", "Schulung")).passages(pack()).bookLanguage("German")
                .sectionWords(300).wordsPerCall(100).answerTokens(600);
    }

    private static String user(List<ChatMessage> request) {
        return request.get(1).getContent();
    }

    @Test
    public void offKeepsTheSectionPromptOfEveryPart() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Der smarte Helm misst Stöße und meldet einen Sturz [2].")
                .answer("Ein Gaswarner meldet giftige Gase in der Baugrube [1].")
                .answer("Die Sensoren im Helm senden fortlaufend ihre Werte [4].");
        SectionDraft draft = new SectionWriter(chat, null).write(section().build());
        assertTrue(draft.isOk());
        assertEquals(3, chat.requests.size());
        for (List<ChatMessage> request : chat.requests) {
            assertTrue(request.get(0).getContent().startsWith(SectionWriterPrompt.WHOLE_SECTION));
            assertFalse(user(request).contains("SECTION PLAN"));
            assertFalse(user(request).contains("SECTION TEXT SO FAR"));
        }
    }

    @Test
    public void continuityTellsEveryPartItsPlaceAndTheTextBeforeIt() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Der smarte Helm misst Stöße und meldet einen Sturz [2].")
                .answer("Ein Gaswarner meldet giftige Gase in der Baugrube [1].")
                .answer("Die Sensoren im Helm senden fortlaufend ihre Werte [4].");
        SectionDraft draft = new SectionWriter(chat, null).write(section()
                .plan(SectionPlanCriteria.of(SectionPlanCriteria.Mode.CONTINUITY)).build());
        assertTrue(draft.isOk());
        assertEquals(3, chat.requests.size());
        String first = user(chat.requests.get(0));
        assertTrue(first, first.contains("SECTION PLAN (the section is written in 3 parts, this is part 1):"));
        assertTrue(first, first.contains("- Helme - this part\n- Gaswarner - a later part\n- Schulung - a later part"));
        assertFalse(first.contains("SECTION TEXT SO FAR"));
        assertTrue(first, first.contains("Write part 1 of 3 now"));
        assertTrue(first, first.contains("Do not conclude or summarise"));
        String second = user(chat.requests.get(1));
        assertTrue(chat.requests.get(1).get(0).getContent().startsWith(SectionWriterPrompt.ONE_PART));
        assertTrue(second, second.contains("- Helme - already written\n- Gaswarner - this part\n- Schulung - a later part"));
        assertTrue(second, second.contains("SECTION TEXT SO FAR"));
        assertTrue(second, second.contains("\"\"\"Der smarte Helm misst Stöße und meldet einen Sturz.\"\"\""));
        assertFalse("the last sentences are no longer sent as the previous section",
                second.contains("PREVIOUS SECTION"));
        String third = user(chat.requests.get(2));
        assertTrue(third, third.contains("Ein Gaswarner meldet giftige Gase in der Baugrube.\"\"\""));
        assertTrue(third, third.contains("End with the last point and do not summarise the section."));
        assertEquals(3, draft.getText().split("\n\n").length);
    }

    @Test
    public void semanticPlanAssignsEveryPassageToItsKeyPointAndFindsTheOpenPoint() {
        List<String> log = new ArrayList<String>();
        SectionAssignment a = section().sectionWords(120).wordsPerCall(60)
                .plan(SectionPlanCriteria.of(SectionPlanCriteria.Mode.SEMANTIC).withWordsPerEvidenceWord(0)).build();
        SectionPlanner.Plan plan = SectionPlanner.plan(a, new SentenceSimilarity(FAKE, null), log::add);
        assertNotNull(plan);
        assertEquals(Arrays.asList("Schulung"), plan.uncovered());
        assertEquals(2, plan.parts.size());
        assertEquals(Arrays.asList("Helme"), plan.parts.get(0).getKeyPoints());
        assertEquals(Arrays.asList(2, 4), numbers(plan.parts.get(0).getPassages()));
        assertEquals(Arrays.asList("Gaswarner"), plan.parts.get(1).getKeyPoints());
        assertEquals(Arrays.asList(1, 3), numbers(plan.parts.get(1).getPassages()));
        assertEquals(120, plan.words);
        assertTrue(log.toString(), log.get(0).contains("plan of 2 parts"));
    }

    @Test
    public void semanticPlanCapsTheWordsAtWhatTheEvidenceCarries() {
        SectionAssignment a = section().sectionWords(1100).wordsPerCall(450)
                .plan(SectionPlanCriteria.of(SectionPlanCriteria.Mode.SEMANTIC)).build();
        SectionPlanner.Plan plan = SectionPlanner.plan(a, new SentenceSimilarity(FAKE, null), null);
        int evidence = 0;
        for (EvidencePassage passage : pack()) {
            evidence += SectionPlanner.words(passage.getText());
        }
        assertEquals(1, plan.parts.size());
        assertTrue(plan.words + " of " + evidence, Math.abs(plan.words - evidence / 2) <= 2);
    }

    @Test
    public void semanticWriterListsPassagesUnderTheirPointAndKeepsThePackNumbers() {
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Der smarte Helm misst Stöße und meldet einen Sturz [1]. Seine Sensoren senden Temperatur "
                        + "und Lage [2].")
                .answer("Ein Gaswarner meldet giftige Gase in der Baugrube [1]. Gassensoren prüfen die Luftqualität "
                        + "[2].");
        SectionDraft draft = new SectionWriter(chat, null).withSentenceVectors(FAKE).write(section()
                .sectionWords(120).wordsPerCall(60)
                .plan(SectionPlanCriteria.of(SectionPlanCriteria.Mode.SEMANTIC).withWordsPerEvidenceWord(0)).build());
        assertTrue(draft.isOk());
        assertEquals(2, chat.requests.size());
        String first = user(chat.requests.get(0));
        assertTrue(first, first.contains("- Schulung - no source passages, leave it out"));
        assertTrue(first, first.contains("For the point \"Helme\":\n[1] \"\"\"Der smarte Helm"));
        assertFalse(first, first.contains("Gaswarner meldet"));
        String second = user(chat.requests.get(1));
        assertTrue(second, second.contains("For the point \"Gaswarner\":\n[1] \"\"\"Ein Gaswarner"));
        // the part's [1], [2] are the pack's [2], [4] and [1], [3]
        assertTrue(draft.getText(), draft.getText().contains("Sturz [2]. Seine Sensoren senden Temperatur und Lage [4]."));
        assertTrue(draft.getText(), draft.getText().contains("Baugrube [1]. Gassensoren prüfen die Luftqualität [3]."));
        assertEquals(Arrays.asList(1, 2, 3, 4), new ArrayList<Integer>(draft.getCitations().keySet()));
        assertEquals(Arrays.asList("Schulung"), draft.getGaps());
    }

    @Test
    public void withoutASentenceModelTheSemanticPlanFallsBackToContinuity() {
        SectionAssignment a = section().plan(SectionPlanCriteria.of(SectionPlanCriteria.Mode.SEMANTIC)).build();
        assertNull(SectionPlanner.plan(a, SentenceSimilarity.none(), null));
        SectionWriterTest.ScriptedChat chat = new SectionWriterTest.ScriptedChat()
                .answer("Der smarte Helm misst Stöße und meldet einen Sturz [2].")
                .answer("Ein Gaswarner meldet giftige Gase in der Baugrube [1].")
                .answer("Die Sensoren im Helm senden fortlaufend ihre Werte [4].");
        assertTrue(new SectionWriter(chat, null).write(a).isOk());
        assertTrue(user(chat.requests.get(1)).contains("SECTION TEXT SO FAR"));
    }

    @Test
    public void soFarKeepsTheEndOfALongText() {
        String text = "Erster Absatz mit einem Satz [1].\n\nZweiter Absatz, der bleibt [2]. Und sein zweiter Satz.";
        assertEquals("Erster Absatz mit einem Satz.\n\nZweiter Absatz, der bleibt. Und sein zweiter Satz.",
                SectionWriter.soFar(text, 0));
        String cut = SectionWriter.soFar(text, 60);
        assertTrue(cut, cut.startsWith("[...]"));
        assertTrue(cut, cut.endsWith("Und sein zweiter Satz."));
        assertFalse(cut, cut.contains("Erster Absatz"));
    }

    private static List<Integer> numbers(List<EvidencePassage> passages) {
        List<Integer> out = new ArrayList<Integer>();
        for (EvidencePassage passage : passages) {
            out.add(passage.getNumber());
        }
        return out;
    }
}
