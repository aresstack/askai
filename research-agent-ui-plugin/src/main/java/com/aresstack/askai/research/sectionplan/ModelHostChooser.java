package com.aresstack.askai.research.sectionplan;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The model behind {@link HostChooser}: one call per topic without a section of its own, on the slot
 * {@code phase.outline.plan}, thinking off, temperature 0, one repair. The model sees the topic's name and description
 * and the candidates as short labels ({@code A1}, {@code A2}, ...) with their names and chapters; the schema allows
 * only these labels and {@code KEINER}, the reason comes first. It sees no evidence, no weights and no mission.
 */
public final class ModelHostChooser implements HostChooser {

    static final int ATTEMPTS = 2;
    static final double TEMPERATURE = 0.0;
    static final int ANSWER_TOKENS = 256;
    static final int CONTEXT_TOKENS = 8192;
    /** The answer for "fits in none of them". */
    static final String NONE = "KEINER";
    /** Descriptions are cut to this many characters. */
    static final int DESCRIPTION_CHARS = 300;

    static final String SYSTEM_DE = "Du ordnest Themen eines Sachbuchs den Abschnitten seiner Gliederung zu. "
            + "Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You place topics of a non-fiction book into the sections of its outline. "
            + "Answer only with JSON.";

    public static final ResearchRole ROLE = ResearchRole.builder("section-host", ResearchModelSlots.PHASE_OUTLINE_PLAN)
            .purpose("component 9: names the topic of the outline in whose section a topic without a section of its "
                    + "own fits best, or none")
            .promptFromCaller()
            .output(RoleOutput.schemaPerRequest("grund, wahl (a label of the list or KEINER)"))
            .thinking(ThinkingMode.OFF)
            .contextTokens(CONTEXT_TOKENS)
            .build();

    private final MainModelChat chat;
    private final boolean german;

    public ModelHostChooser(MainModelChat chat, boolean german) {
        if (chat == null) {
            throw new IllegalArgumentException("no model");
        }
        this.chat = chat;
        this.german = german;
    }

    @Override
    public String modelName() {
        return chat.modelName();
    }

    @Override
    public Choice choose(Option topic, List<Option> candidates) {
        List<String> answers = new ArrayList<String>();
        if (candidates == null || candidates.isEmpty()) {
            return Choice.none(german ? "keine Abschnitte zur Wahl" : "no sections to choose from", answers);
        }
        Map<String, Option> byLabel = new LinkedHashMap<String, Option>();
        for (Option candidate : candidates) {
            byLabel.put("A" + (byLabel.size() + 1), candidate);
        }
        String schema = schema(byLabel);
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(topic, byLabel)));
        String problem = "no answer";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, ANSWER_TOKENS, schema);
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (result == null || !result.isOk()) {
                answers.add("");
                problem = result == null ? "model call returned nothing"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue;
            }
            String text = result.getText() == null ? "" : result.getText();
            answers.add(text);
            String[] parsed = parse(text, byLabel);
            if (parsed[2].isEmpty()) {
                return parsed[0].isEmpty() ? Choice.none(parsed[1], answers)
                        : Choice.of(byLabel.get(parsed[0]).getTopicId(), parsed[1], answers);
            }
            problem = "invalid answer: " + parsed[2];
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + parsed[2] + (german ? "\nAntworte nur mit dem JSON-Objekt." : "\nAnswer with the JSON object "
                    + "only.")));
        }
        return Choice.failed(problem, answers);
    }

    /** The question: the topic, the candidates by label, the answer's form. */
    String prompt(Option topic, Map<String, Option> byLabel) {
        StringBuilder text = new StringBuilder();
        String description = cut(topic.getDescription());
        if (german) {
            text.append("Ein Thema aus der Recherche bekommt keinen eigenen Abschnitt im Buch. Wähle das Thema der "
                    + "Gliederung, in dessen Abschnitt es inhaltlich am besten passt. Passt es zu keinem, wähle \"")
                    .append(NONE).append("\".\n\nThema: „").append(topic.getName()).append("“");
            if (!description.isEmpty()) {
                text.append("\nBeschreibung: ").append(description);
            }
            text.append("\n\nThemen der Gliederung:");
        } else {
            text.append("A topic from the research gets no section of its own in the book. Choose the topic of the "
                    + "outline in whose section it fits best by content. If it fits none, choose \"")
                    .append(NONE).append("\".\n\nTopic: \"").append(topic.getName()).append("\"");
            if (!description.isEmpty()) {
                text.append("\nDescription: ").append(description);
            }
            text.append("\n\nTopics of the outline:");
        }
        for (Map.Entry<String, Option> entry : byLabel.entrySet()) {
            Option candidate = entry.getValue();
            text.append("\n- \"").append(entry.getKey()).append("\": ").append(candidate.getName());
            if (!candidate.getChapterTitle().isEmpty() && !candidate.getChapterTitle().equals(candidate.getName())) {
                text.append(german ? " (Kapitel „" : " (chapter \"").append(candidate.getChapterTitle())
                        .append(german ? "“)" : "\")");
            }
        }
        text.append(german ? "\n\nAntworte nur mit JSON: {\"grund\": ein kurzer Satz, warum, \"wahl\": eine Kennung "
                + "aus der Liste oder \"" + NONE + "\"}" : "\n\nAnswer only with JSON: {\"grund\": one short sentence "
                + "why, \"wahl\": a label of the list or \"" + NONE + "\"}");
        return text.toString();
    }

    static String schema(Map<String, Option> byLabel) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        reason.addProperty("minLength", 5);
        properties.add("grund", reason);
        JsonObject choice = new JsonObject();
        choice.addProperty("type", "string");
        JsonArray values = new JsonArray();
        for (String label : byLabel.keySet()) {
            values.add(label);
        }
        values.add(NONE);
        choice.add("enum", values);
        properties.add("wahl", choice);
        schema.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("wahl");
        schema.add("required", required);
        return schema.toString();
    }

    /** {label or "" for none, reason, problem ("" when the answer holds)}. */
    static String[] parse(String text, Map<String, Option> byLabel) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text == null ? "" : text.trim());
            if (!element.isJsonObject()) {
                return new String[]{"", "", "not a JSON object"};
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException unreadable) {
            return new String[]{"", "", "not JSON"};
        }
        String reason = json.has("grund") && json.get("grund").isJsonPrimitive()
                ? json.get("grund").getAsString().trim() : "";
        String choice = json.has("wahl") && json.get("wahl").isJsonPrimitive() ? json.get("wahl").getAsString().trim()
                : "";
        if (reason.length() < 5) {
            return new String[]{"", "", "\"grund\" missing"};
        }
        if (NONE.equalsIgnoreCase(choice)) {
            return new String[]{"", reason, ""};
        }
        String label = choice.toUpperCase(java.util.Locale.ROOT);
        if (!byLabel.containsKey(label)) {
            return new String[]{"", "", "\"wahl\" must be a label of the list or " + NONE};
        }
        return new String[]{label, reason, ""};
    }

    private static String cut(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() <= DESCRIPTION_CHARS ? trimmed : trimmed.substring(0, DESCRIPTION_CHARS) + " …";
    }
}
