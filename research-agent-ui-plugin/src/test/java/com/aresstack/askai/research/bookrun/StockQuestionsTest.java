package com.aresstack.askai.research.bookrun;

import com.aresstack.askai.research.domain.scope.ResearchFence;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** The questions from mission and groups and their check against mission and fence. Made-up texts (city traffic). */
public class StockQuestionsTest {

    private static final ResearchFence FENCE = new ResearchFence("Ein Sachbuch für Stadtplaner über Verkehrssicherheit "
            + "in Innenstädten.", "Nur der Verkehr in der Stadt.", Collections.singletonList("Tempo 30"),
            Collections.singletonList("Autobahnen"), "Deutschland", "");

    @Test
    public void theDraftOfAGroupShowsFenceAndPassagesAndNoConcept() {
        Answers chat = new Answers("{\"titel\":\"Tempo 30\",\"fragen\":[\"Wie wirkt Tempo 30 auf Unfälle?\","
                + "\" Wie wirkt Tempo 30 auf Unfälle? \",\"\"]}");
        StockQuestions.Drafted drafted = new StockQuestions(chat, FENCE, "de", null, null).draft("gruppe-1",
                Arrays.asList(new StockQuestions.Passage("p1", "Bericht > Unfälle", "Nach Tempo 30 sank die Zahl "
                        + "schwerer Unfälle."), new StockQuestions.Passage("p2", "", "")));

        assertEquals("Tempo 30", drafted.getTitle());
        assertEquals("the same question once, none empty", Collections.singletonList("Wie wirkt Tempo 30 auf "
                + "Unfälle?"), drafted.getQuestions());
        String prompt = chat.prompts.get(0);
        assertTrue(prompt, prompt.startsWith("Buchauftrag:\nEin Sachbuch für Stadtplaner"));
        assertTrue(prompt, prompt.contains("Ausgeschlossen:\n- Autobahnen"));
        assertTrue(prompt, prompt.contains("[1] Überschrift: Bericht > Unfälle\n\"\"\"\nNach Tempo 30 sank"));
        assertFalse("an empty passage is not shown", prompt.contains("[2]"));
        assertFalse("no concept", prompt.contains("Karte") || prompt.contains("Mindmap"));
    }

    @Test
    public void aGroupLargerThanTheContextIsAskedInParts() {
        StockQuestions questions = new StockQuestions(new Answers(), FENCE, "de",
                StockQuestions.Options.defaults().withContextTokens(3000), null);
        List<StockQuestions.Passage> passages = new ArrayList<StockQuestions.Passage>();
        for (int i = 0; i < 12; i++) {
            passages.add(new StockQuestions.Passage("p" + i, "", repeat("Ein langer Satz über den Verkehr. ", 40)));
        }
        List<List<StockQuestions.Passage>> parts = questions.parts(passages);
        assertTrue(parts.size() > 1);
        int total = 0;
        for (List<StockQuestions.Passage> part : parts) {
            assertFalse(part.isEmpty());
            total += part.size();
        }
        assertEquals("every passage in a part", 12, total);
    }

    @Test
    public void theMissionQuestionsComeFromTheFenceAlone() {
        Answers chat = new Answers("{\"titel\":\"Sichere Innenstädte\",\"fragen\":[\"Wie wirkt Tempo 30?\"]}");
        StockQuestions.Drafted demanded = new StockQuestions(chat, FENCE, "de", null, null).draftMission();

        assertEquals(Collections.singletonList("Wie wirkt Tempo 30?"), demanded.getQuestions());
        assertTrue(chat.systems.get(0).startsWith("Du planst ein Sachbuch"));
        assertTrue(chat.prompts.get(0).contains("Rand des Themas:\nNur der Verkehr in der Stadt."));
        assertTrue(chat.prompts.get(0).contains("Zusammen decken die Fragen alles ab, was der Auftrag verlangt"));
    }

    @Test
    public void theCheckAsksAMissedQuestionOnceMoreAndNeverDropsOne() {
        Answers chat = new Answers(
                "{\"urteile\":[{\"nr\":\"F1\",\"grund\":\"Gehört dazu.\",\"urteil\":\"INNEN\"},"
                        + "{\"nr\":\"2\",\"grund\":\"Autobahnen sind ausgeschlossen.\",\"urteil\":\"AUSSEN\"}]}",
                "kein JSON");
        List<StockQuestions.Verdict> verdicts = new StockQuestions(chat, FENCE, "de", null, null).check(Arrays.asList(
                "Wie wirkt Tempo 30?", "Wie sicher sind Autobahnen?", "Was kostet ein Zebrastreifen?"));

        assertEquals(3, verdicts.size());
        assertEquals(StockQuestions.Relation.INNEN, verdicts.get(0).getRelation());
        assertEquals(StockQuestions.Relation.AUSSEN, verdicts.get(1).getRelation());
        assertEquals("missed twice: kept, not checked", StockQuestions.Relation.UNGEPRUEFT,
                verdicts.get(2).getRelation());
        assertEquals(2, chat.prompts.size());
        assertTrue(chat.prompts.get(1), chat.prompts.get(1).contains("[F1] Was kostet ein Zebrastreifen?")
                && !chat.prompts.get(1).contains("Autobahnen?"));
    }

    private static String repeat(String text, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(text);
        }
        return out.toString();
    }

    /** Answers in turn; records system and user prompt of every call. */
    static final class Answers implements MainModelChat {
        private final LinkedList<String> answers;
        final List<String> systems = new ArrayList<String>();
        final List<String> prompts = new ArrayList<String>();

        Answers(String... answers) {
            this.answers = new LinkedList<String>(Arrays.asList(answers));
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            systems.add(messages.get(0).getContent());
            prompts.add(messages.get(messages.size() - 1).getContent());
            return answers.isEmpty() ? MainModelChatResult.ok("{\"titel\":\"\",\"fragen\":[]}")
                    : MainModelChatResult.ok(answers.removeFirst());
        }

        @Override
        public String modelName() {
            return "answers";
        }
    }
}
