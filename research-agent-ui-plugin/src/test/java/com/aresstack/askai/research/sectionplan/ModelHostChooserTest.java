package com.aresstack.askai.research.sectionplan;

import com.aresstack.askai.agent.model.port.ThinkingMode;
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
import static org.junit.Assert.assertTrue;

public class ModelHostChooserTest {

    /** Answers from a list, records the prompts and schemas. */
    static final class Scripted implements MainModelChat {
        final LinkedList<MainModelChatResult> answers = new LinkedList<MainModelChatResult>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        final List<String> schemas = new ArrayList<String>();
        double temperature = -1;
        int tokens;

        Scripted then(String text) {
            answers.add(MainModelChatResult.ok(text));
            return this;
        }

        Scripted thenFailure() {
            answers.add(MainModelChatResult.failure(MainModelChatResult.Status.TIMEOUT, "zu langsam"));
            return this;
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return completeJson(messages, temperature, maxOutputTokens, null);
        }

        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schema) {
            calls.add(new ArrayList<ChatMessage>(messages));
            schemas.add(schema);
            this.temperature = temperature;
            this.tokens = maxOutputTokens;
            return answers.removeFirst();
        }

        public String modelName() {
            return "gemma-test";
        }
    }

    static final HostChooser.Option TOPIC = new HostChooser.Option("topic-77aa", "Helme für Kinder",
            "Ob Kinder auf dem Rad einen Helm tragen müssen.", "");
    static final List<HostChooser.Option> CANDIDATES = Arrays.asList(
            new HostChooser.Option("karte-rad", "Radverkehr", "", "Radverkehr"),
            new HostChooser.Option("topic-19bc", "Schulwege", "Wege zur Schule zu Fuß", "Fußverkehr"));

    @Test
    public void theModelPicksALabelAndTheChoiceNamesTheTopic() {
        Scripted chat = new Scripted().then("{\"grund\": \"Helme betreffen Radfahrer.\", \"wahl\": \"A1\"}");

        HostChooser.Choice choice = new ModelHostChooser(chat, true).choose(TOPIC, CANDIDATES);

        assertFalse(choice.isFailed());
        assertEquals("karte-rad", choice.getTopicId());
        assertEquals("Helme betreffen Radfahrer.", choice.getReason());
        assertEquals(0.0, chat.temperature, 0);
        assertEquals(256, chat.tokens);
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt, prompt.startsWith("Ein Thema aus der Recherche bekommt keinen eigenen Abschnitt im Buch. "
                + "Wähle das Thema der Gliederung, in dessen Abschnitt es inhaltlich am besten passt. Passt es zu "
                + "keinem, wähle \"KEINER\".\n\nThema: „Helme für Kinder“\nBeschreibung: Ob Kinder auf dem Rad einen "
                + "Helm tragen müssen.\n\nThemen der Gliederung:\n- \"A1\": Radverkehr\n- \"A2\": Schulwege (Kapitel "
                + "„Fußverkehr“)\n\nAntworte nur mit JSON:"));
        assertFalse("no ids in the prompt", prompt.contains("topic-") || prompt.contains("karte-rad"));
        JsonObject schema = JsonParser.parseString(chat.schemas.get(0)).getAsJsonObject();
        assertEquals("[\"A1\",\"A2\",\"KEINER\"]",
                schema.getAsJsonObject("properties").getAsJsonObject("wahl").getAsJsonArray("enum").toString());
        assertEquals("[\"grund\",\"wahl\"]", schema.getAsJsonArray("required").toString());
        assertEquals("grund", schema.getAsJsonObject("properties").keySet().iterator().next());
    }

    @Test
    public void keinerIsAnAnswerAndNotAFailure() {
        Scripted chat = new Scripted().then("{\"grund\": \"Passt zu keinem Thema.\", \"wahl\": \"KEINER\"}");

        HostChooser.Choice choice = new ModelHostChooser(chat, true).choose(TOPIC, CANDIDATES);

        assertFalse(choice.isFailed());
        assertEquals("", choice.getTopicId());
    }

    @Test
    public void anInvalidAnswerIsRepairedOnceThenItFails() {
        Scripted repaired = new Scripted().then("{\"grund\": \"Radfahrer.\", \"wahl\": \"A7\"}")
                .then("{\"grund\": \"Radfahrer tragen Helme.\", \"wahl\": \"a2\"}");

        HostChooser.Choice choice = new ModelHostChooser(repaired, true).choose(TOPIC, CANDIDATES);

        assertEquals("topic-19bc", choice.getTopicId());
        assertEquals(2, repaired.calls.size());
        List<ChatMessage> second = repaired.calls.get(1);
        assertTrue(second.get(second.size() - 1).getContent().startsWith("Deine Antwort war ungültig: \"wahl\" "
                + "must be a label of the list or KEINER"));

        Scripted broken = new Scripted().then("kein JSON").thenFailure();
        HostChooser.Choice failed = new ModelHostChooser(broken, true).choose(TOPIC, CANDIDATES);
        assertTrue(failed.isFailed());
        assertTrue(failed.getReason(), failed.getReason().startsWith("model call failed (TIMEOUT)"));
        assertEquals(Arrays.asList("kein JSON", ""), failed.getAnswers());
    }

    @Test
    public void theRoleAndTheEnglishPrompt() {
        assertEquals("phase.outline.plan", ModelHostChooser.ROLE.getSlot().getName());
        assertEquals(ThinkingMode.OFF, ModelHostChooser.ROLE.getThinking());
        assertTrue(ModelHostChooser.ROLE.getOutput().isSchemaPerRequest());
        assertEquals(8192, ModelHostChooser.ROLE.getContextTokens());

        Scripted chat = new Scripted().then("{\"grund\": \"Fits nowhere at all.\", \"wahl\": \"KEINER\"}");
        new ModelHostChooser(chat, false).choose(TOPIC, CANDIDATES);
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt, prompt.startsWith("A topic from the research gets no section of its own in the book."));
        assertTrue(prompt, prompt.contains("- \"A2\": Schulwege (chapter \"Fußverkehr\")"));
        assertEquals(ModelHostChooser.SYSTEM_EN, chat.calls.get(0).get(0).getContent());
    }
}
