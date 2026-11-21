package com.aresstack.askai.research.concept.questions;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Repair plan step 2a, part A: drafts the question list from the mission and the first-level cards in ONE model call.
 * <p>
 * Prompt, schema and call settings are those of arm g12-h of the question probe of 2026-11-21 (gemma4:12b, one
 * call, thinking off, temperature 0.3, 1,500 answer tokens; 120 of 120 questions usable, no duplicate, 10 to 14 s per
 * list). The smaller gemma4:e2b failed that probe, so the role has a slot of its own that ships with gemma4:12b
 * ({@link #SLOT}). The German prompt is the measured one; the English one is its translation and unmeasured.
 * <p>
 * The draft steers nothing: it is shown to the user, who edits and approves it with the concept.
 */
public final class MissionQuestionDrafter {

    /** The slot of the question draft: a sub-slot of Concept that ships with gemma4:12b (question probe). */
    public static final ModelSlot SLOT = ModelSlot.of("phase.scoping.questions");

    public static final ResearchRole ROLE = ResearchRole.builder("mission-questions", SLOT)
            .purpose("repair plan 2a: research questions per first-level card from the mission (a draft the user "
                    + "approves)")
            .promptFromCaller()
            .output(RoleOutput.json("karte1..kartenN, each 4 to 5 questions"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.3;
    static final int ANSWER_TOKENS = 1500;
    static final int MIN_PER_CARD = 4;
    static final int MAX_PER_CARD = 5;

    /** The outcome: the drafted list, or why there is none. */
    public static final class Result {
        private final MissionQuestionList list;
        private final String problem;

        private Result(MissionQuestionList list, String problem) {
            this.list = list;
            this.problem = problem;
        }

        public MissionQuestionList getList() {
            return list;
        }

        /** {@code null} when the draft succeeded. */
        public String getProblem() {
            return problem;
        }

        public boolean isOk() {
            return problem == null;
        }
    }

    private final MainModelChat chat;

    public MissionQuestionDrafter(MainModelChat chat) {
        this.chat = chat;
    }

    /**
     * One call (a second one only when the first answer is no valid list).
     * @param language the working language code ({@code "de"}, {@code "en"})
     */
    public Result draft(String mission, List<String> cards, String language) {
        if (chat == null) {
            return new Result(MissionQuestionList.empty(), "No model is available for the question draft.");
        }
        if (mission == null || mission.trim().isEmpty() || cards == null || cards.isEmpty()) {
            return new Result(MissionQuestionList.empty(),
                    "The question draft needs a mission and at least one first-level card.");
        }
        boolean german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        List<ChatMessage> messages = Arrays.asList(
                new ChatMessage(ChatMessage.Role.SYSTEM, german ? SYSTEM_DE : SYSTEM_EN),
                new ChatMessage(ChatMessage.Role.USER, prompt(mission, cards, german)));
        String schema = schema(cards.size());
        String problem = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            MainModelChatResult result = chat.completeJson(messages, TEMPERATURE, ANSWER_TOKENS, schema);
            if (!result.isOk()) {
                problem = "The model gave no answer: " + result.getDetail();
                continue;
            }
            MissionQuestionList list = parse(result.getText(), cards);
            if (list != null) {
                return new Result(list, null);
            }
            problem = "The model's answer was no valid question list.";
        }
        return new Result(MissionQuestionList.empty(), problem);
    }

    static final String SYSTEM_DE = "Du bist die Rechercheleitung für ein Sachbuch. Du leitest aus dem Buchauftrag "
            + "die Forschungsfragen ab, die die Recherche beantworten muss. Du bleibst im Thema des Auftrags und "
            + "erfindest keine Themen dazu. Antworte nur mit JSON.";

    static final String SYSTEM_EN = "You lead the research for a non-fiction book. From the book mission you derive "
            + "the research questions the research must answer. You stay within the subject of the mission and "
            + "invent no topics. Answer only with JSON.";

    /** The user prompt of the question probe (German verbatim), for any number of cards. */
    static String prompt(String mission, List<String> cards, boolean german) {
        StringBuilder text = new StringBuilder();
        text.append(german ? "Buchauftrag:\n" : "Book mission:\n").append(mission.trim()).append("\n\n");
        text.append(german ? "Karten der Mindmap (Hauptthemen des Buchs):\n"
                : "Cards of the mind map (main themes of the book):\n");
        for (int i = 0; i < cards.size(); i++) {
            text.append(i + 1).append(". ").append(cards.get(i).trim()).append('\n');
        }
        if (german) {
            text.append("\nAufgabe: Leite für jede Karte vier bis fünf Forschungsfragen ab. Eine Forschungsfrage "
                    + "sagt, was die Recherche zu dieser Karte herausfinden muss.\n\n"
                    + "Regeln:\n"
                    + "- Jede Frage liegt im Thema des Auftrags. Was der Auftrag nicht behandelt, fragst du nicht.\n"
                    + "- Jede Frage fragt nach Tatsachen, die sich mit Quellen belegen lassen. Keine Fragen zum "
                    + "Aufbau des Buchs und keine Meinungsfragen.\n"
                    + "- Jede Frage ist ein vollständiger deutscher Fragesatz und so konkret, dass sich an einer "
                    + "Textstelle entscheiden lässt, ob sie die Frage beantwortet.\n"
                    + "- Keine Frage wiederholt eine andere, auch nicht bei einer anderen Karte.\n"
                    + "- Decke alles ab, was der Auftrag zu einer Karte nennt, auch wenn der Kartenname es "
                    + "weglässt.\n\n"
                    + "Antworte als JSON mit den Feldern ").append(fieldList(cards.size(), "und"))
                    .append(". Jedes Feld ist eine Liste mit vier bis fünf Fragen zur Karte mit dieser Nummer.");
        } else {
            text.append("\nTask: Derive four to five research questions for each card. A research question says "
                    + "what the research must find out about this card.\n\n"
                    + "Rules:\n"
                    + "- Every question lies within the subject of the mission. Do not ask about what the mission "
                    + "does not treat.\n"
                    + "- Every question asks for facts that sources can support. No questions about the structure "
                    + "of the book and no questions of opinion.\n"
                    + "- Every question is a complete English question and so concrete that a passage can be "
                    + "judged to answer it or not.\n"
                    + "- No question repeats another, not even under another card.\n"
                    + "- Cover everything the mission names for a card, even when the card's name leaves it out.\n\n"
                    + "Answer as JSON with the fields ").append(fieldList(cards.size(), "and"))
                    .append(". Each field is a list of four to five questions on the card with that number.");
        }
        return text.toString();
    }

    private static String fieldList(int cards, String and) {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= cards; i++) {
            if (i > 1) {
                text.append(i == cards ? " " + and + " " : ", ");
            }
            text.append("\"karte").append(i).append('"');
        }
        return text.toString();
    }

    /** The JSON schema of the probe: {@code karte1..karteN}, each an array of 4 to 5 strings, all required. */
    static String schema(int cards) {
        JsonObject properties = new JsonObject();
        com.google.gson.JsonArray required = new com.google.gson.JsonArray();
        for (int i = 1; i <= cards; i++) {
            JsonObject list = new JsonObject();
            JsonObject items = new JsonObject();
            items.addProperty("type", "string");
            list.add("items", items);
            list.addProperty("maxItems", MAX_PER_CARD);
            list.addProperty("minItems", MIN_PER_CARD);
            list.addProperty("type", "array");
            properties.add("karte" + i, list);
            required.add("karte" + i);
        }
        JsonObject schema = new JsonObject();
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("type", "object");
        return schema.toString();
    }

    /**
     * The list in an answer, {@code null} when it is no JSON object or a card has no question. More than the maximum
     * per card are cut, questions repeated across the list are dropped.
     */
    static MissionQuestionList parse(String answer, List<String> cards) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!element.isJsonObject()) {
                return null;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException invalid) {
            return null;
        }
        Map<String, List<String>> perCard = new LinkedHashMap<String, List<String>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (int i = 0; i < cards.size(); i++) {
            JsonElement field = json.get("karte" + (i + 1));
            if (field == null || !field.isJsonArray()) {
                return null;
            }
            List<String> questions = new ArrayList<String>();
            for (JsonElement entry : field.getAsJsonArray()) {
                if (!entry.isJsonPrimitive()) {
                    continue;
                }
                String question = entry.getAsString().trim();
                if (!question.isEmpty() && questions.size() < MAX_PER_CARD
                        && seen.add(question.toLowerCase(Locale.ROOT))) {
                    questions.add(question);
                }
            }
            if (questions.isEmpty()) {
                return null;
            }
            perCard.put(cards.get(i).trim(), questions);
        }
        return MissionQuestionList.of(perCard, MissionQuestion.Origin.APP);
    }
}
