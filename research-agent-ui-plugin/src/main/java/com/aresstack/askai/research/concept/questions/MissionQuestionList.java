package com.aresstack.askai.research.concept.questions;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Repair plan step 2a, part A: the question list of the book, grouped by first-level card. It is a draft the user
 * edits in Phase 1 and approves with the concept; the Phase-1 record then holds a frozen copy. The user edits it as
 * plain text, one card heading per card and one question per line:
 * <pre>
 * ## Technologien und Sensortypen
 * - Welche Sensortypen ...?
 * </pre>
 * Ids ({@code F01}, ...) follow the list order. A question whose text equals a drafted one stays "app", every other
 * one is the user's. Immutable.
 */
public final class MissionQuestionList {

    private static final MissionQuestionList EMPTY = new MissionQuestionList(Collections.<MissionQuestion>emptyList());

    private final List<MissionQuestion> questions;

    private MissionQuestionList(List<MissionQuestion> questions) {
        this.questions = Collections.unmodifiableList(new ArrayList<MissionQuestion>(questions));
    }

    public static MissionQuestionList empty() {
        return EMPTY;
    }

    /** The questions per card in the order given, numbered anew. */
    public static MissionQuestionList of(Map<String, List<String>> perCard, MissionQuestion.Origin origin) {
        List<MissionQuestion> list = new ArrayList<MissionQuestion>();
        for (Map.Entry<String, List<String>> card : perCard.entrySet()) {
            for (String text : card.getValue()) {
                if (text != null && !text.trim().isEmpty()) {
                    list.add(new MissionQuestion(id(list.size() + 1), card.getKey(), text, origin));
                }
            }
        }
        return new MissionQuestionList(list);
    }

    public List<MissionQuestion> getQuestions() {
        return questions;
    }

    public boolean isEmpty() {
        return questions.isEmpty();
    }

    public int size() {
        return questions.size();
    }

    /** The questions of each card, in list order. */
    public Map<String, List<MissionQuestion>> byCard() {
        Map<String, List<MissionQuestion>> cards = new LinkedHashMap<String, List<MissionQuestion>>();
        for (MissionQuestion question : questions) {
            List<MissionQuestion> list = cards.get(question.getCard());
            if (list == null) {
                list = new ArrayList<MissionQuestion>();
                cards.put(question.getCard(), list);
            }
            list.add(question);
        }
        return cards;
    }

    /**
     * The same list with each question bound to the anchor id of the first-level card its heading names (exact name,
     * case and surrounding blanks ignored). A heading that names no card leaves its questions unbound.
     */
    public MissionQuestionList withCardAnchors(List<String> firstLevelCards) {
        Map<String, String> anchorOf = new LinkedHashMap<String, String>();
        if (firstLevelCards != null) {
            for (String card : firstLevelCards) {
                String key = key(card);
                if (!key.isEmpty() && !anchorOf.containsKey(key)) {
                    anchorOf.put(key, com.aresstack.askai.research.scope.ConceptAnchorProjection.anchorIdOf(
                            java.util.Collections.singletonList(card.trim())));
                }
            }
        }
        List<MissionQuestion> bound = new ArrayList<MissionQuestion>();
        for (MissionQuestion question : questions) {
            String anchor = anchorOf.get(key(question.getCard()));
            bound.add(new MissionQuestion(question.getId(), question.getCard(), question.getText(),
                    question.getOrigin(), anchor == null ? "" : anchor));
        }
        return new MissionQuestionList(bound);
    }

    /** The headings of this list that name none of {@code firstLevelCards}, in list order. */
    public List<String> cardsWithoutCard(List<String> firstLevelCards) {
        Set<String> known = new LinkedHashSet<String>();
        if (firstLevelCards != null) {
            for (String card : firstLevelCards) {
                known.add(key(card));
            }
        }
        List<String> missing = new ArrayList<String>();
        for (String card : byCard().keySet()) {
            if (!known.contains(key(card))) {
                missing.add(card);
            }
        }
        return missing;
    }

    private static String key(String card) {
        return card == null ? "" : card.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /** The editable text form (see the class comment); {@code ""} for an empty list. */
    public String toText() {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, List<MissionQuestion>> card : byCard().entrySet()) {
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append("## ").append(card.getKey()).append('\n');
            for (MissionQuestion question : card.getValue()) {
                text.append("- ").append(question.getText()).append('\n');
            }
        }
        return text.toString();
    }

    /**
     * The list a user's text describes. A line starting with {@code #} names the card of the lines below it; any
     * other non-empty line is one question (a leading {@code -}, {@code *} or number is dropped). Questions before
     * the first heading belong to the card "". A question whose text {@code previous} holds keeps its origin.
     */
    public static MissionQuestionList parse(String text, MissionQuestionList previous) {
        Map<String, MissionQuestion.Origin> known = new LinkedHashMap<String, MissionQuestion.Origin>();
        if (previous != null) {
            for (MissionQuestion question : previous.questions) {
                known.put(question.getText(), question.getOrigin());
            }
        }
        List<MissionQuestion> list = new ArrayList<MissionQuestion>();
        Set<String> seen = new LinkedHashSet<String>();
        String card = "";
        for (String raw : (text == null ? "" : text).split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                card = line.replaceFirst("^#+", "").trim();
                continue;
            }
            String question = line.replaceFirst("^(?:[-*\\u2022]|\\d+[.)])\\s*", "").trim();
            if (question.isEmpty() || !seen.add(card + "\n" + question)) {
                continue;
            }
            MissionQuestion.Origin origin = known.containsKey(question) ? known.get(question)
                    : MissionQuestion.Origin.USER;
            list.add(new MissionQuestion(id(list.size() + 1), card, question, origin));
        }
        return new MissionQuestionList(list);
    }

    public JsonArray toJson() {
        JsonArray array = new JsonArray();
        for (MissionQuestion question : questions) {
            JsonObject entry = new JsonObject();
            entry.addProperty("id", question.getId());
            entry.addProperty("card", question.getCard());
            entry.addProperty("text", question.getText());
            entry.addProperty("origin", question.getOrigin().name().toLowerCase(java.util.Locale.ROOT));
            if (!question.getCardAnchorId().isEmpty()) {
                entry.addProperty("cardAnchor", question.getCardAnchorId());
            }
            array.add(entry);
        }
        return array;
    }

    /** The list of a JSON array as {@link #toJson} writes it; {@code null} or a non-array is the empty list. */
    public static MissionQuestionList fromJson(JsonElement json) {
        if (json == null || !json.isJsonArray()) {
            return EMPTY;
        }
        List<MissionQuestion> list = new ArrayList<MissionQuestion>();
        for (JsonElement element : json.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String origin = string(entry, "origin");
            list.add(new MissionQuestion(string(entry, "id"), string(entry, "card"), string(entry, "text"),
                    "app".equalsIgnoreCase(origin) ? MissionQuestion.Origin.APP : MissionQuestion.Origin.USER,
                    string(entry, "cardAnchor")));
        }
        return new MissionQuestionList(list);
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    static String id(int number) {
        return String.format(java.util.Locale.ROOT, "F%02d", number);
    }
}
