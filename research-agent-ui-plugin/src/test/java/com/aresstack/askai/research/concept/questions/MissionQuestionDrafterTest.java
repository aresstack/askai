package com.aresstack.askai.research.concept.questions;

import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Repair plan step 2a, part A: the question draft with the prompt and settings of the question probe. */
public class MissionQuestionDrafterTest {

    static final String MISSION = "Erstelle das Konzept für ein kompaktes, vollständig belegtes deutsches Praxisbuch "
            + "\"Wearables auf Baustellen: Sicherheit, Sensorik und Datenschutz\" für Sicherheitsfachkräfte, Umfang "
            + "20-30 Seiten.";
    static final List<String> CARDS = Arrays.asList("Technologien und Sensortypen",
            "Arbeitsschutz-Gefährdungen und Wirksamkeit", "Recht, Datenschutz und Betriebsrat",
            "Implementierung und Praxis");

    /** Answers from a script, records every call. */
    static final class Chat implements MainModelChat {
        final LinkedList<String> answers = new LinkedList<String>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        final List<String> schemas = new ArrayList<String>();
        double temperature;
        int tokens;

        Chat then(String answer) {
            answers.add(answer);
            return this;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schemaJson);
            this.temperature = temperature;
            this.tokens = maxOutputTokens;
            String answer = answers.isEmpty() ? null : answers.removeFirst();
            return answer == null ? MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "no answer")
                    : MainModelChatResult.ok(answer);
        }

        public String modelName() {
            return "scripted";
        }
    }

    private static String answer(int cards, int perCard) {
        JsonObject json = new JsonObject();
        for (int c = 1; c <= cards; c++) {
            com.google.gson.JsonArray list = new com.google.gson.JsonArray();
            for (int q = 1; q <= perCard; q++) {
                list.add("Frage " + q + " zu Karte " + c + "?");
            }
            json.add("karte" + c, list);
        }
        return json.toString();
    }

    @Test
    public void oneCallWithTheMeasuredPromptAndSettings() {
        Chat chat = new Chat().then(answer(4, 5));

        MissionQuestionDrafter.Result result = new MissionQuestionDrafter(chat).draft(MISSION, CARDS, "de");

        assertTrue(result.getProblem(), result.isOk());
        assertEquals("one call", 1, chat.calls.size());
        assertEquals(0.3, chat.temperature, 1e-9);
        assertEquals(1500, chat.tokens);
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt.startsWith("Buchauftrag:\n" + MISSION));
        assertTrue(prompt.contains("4. Implementierung und Praxis"));
        assertTrue(prompt.contains("Leite für jede Karte vier bis fünf Forschungsfragen ab."));
        assertTrue(prompt.contains("Antworte als JSON mit den Feldern \"karte1\", \"karte2\", \"karte3\" und "
                + "\"karte4\"."));
        assertEquals(MissionQuestionDrafter.SYSTEM_DE, chat.calls.get(0).get(0).getContent());
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        assertEquals(4, schema.getAsJsonArray("required").size());
        assertEquals(4, schema.getAsJsonObject("properties").getAsJsonObject("karte1").get("minItems").getAsInt());
        assertEquals(5, schema.getAsJsonObject("properties").getAsJsonObject("karte4").get("maxItems").getAsInt());

        MissionQuestionList list = result.getList();
        assertEquals(20, list.size());
        assertEquals("F01", list.getQuestions().get(0).getId());
        assertEquals("F20", list.getQuestions().get(19).getId());
        assertEquals(CARDS.get(3), list.getQuestions().get(19).getCard());
        assertEquals(MissionQuestion.Origin.APP, list.getQuestions().get(0).getOrigin());
    }

    @Test
    public void anInvalidAnswerIsAskedOnceMoreThenFails() {
        Chat chat = new Chat().then("{\"karte1\": []}").then(answer(4, 4));
        MissionQuestionDrafter.Result result = new MissionQuestionDrafter(chat).draft(MISSION, CARDS, "de");
        assertTrue(result.isOk());
        assertEquals(2, chat.calls.size());
        assertEquals(16, result.getList().size());

        Chat broken = new Chat().then("kein JSON").then("auch keins");
        MissionQuestionDrafter.Result failed = new MissionQuestionDrafter(broken).draft(MISSION, CARDS, "de");
        assertFalse(failed.isOk());
        assertTrue(failed.getList().isEmpty());
        assertEquals(2, broken.calls.size());
    }

    @Test
    public void withoutMissionCardsOrModelNothingIsDrafted() {
        assertFalse(new MissionQuestionDrafter(null).draft(MISSION, CARDS, "de").isOk());
        Chat chat = new Chat();
        assertFalse(new MissionQuestionDrafter(chat).draft("", CARDS, "de").isOk());
        assertFalse(new MissionQuestionDrafter(chat).draft(MISSION, new ArrayList<String>(), "de").isOk());
        assertTrue("no call without input", chat.calls.isEmpty());
    }

    @Test
    public void repeatedQuestionsAreDroppedAndExtrasCut() {
        String answer = "{\"karte1\":[\"A?\",\"B?\",\"C?\",\"D?\",\"E?\",\"F?\"],\"karte2\":[\"a?\",\"G?\"]}";
        MissionQuestionList list = MissionQuestionDrafter.parse(answer, Arrays.asList("Eins", "Zwei"));
        assertEquals("five for card one, \"a?\" repeats \"A?\"", 6, list.size());
        assertEquals("G?", list.getQuestions().get(5).getText());
        assertNull(MissionQuestionDrafter.parse("[]", Arrays.asList("Eins")));
    }
}
