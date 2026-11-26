package com.aresstack.askai.research.bookrun;

import com.aresstack.askai.research.domain.scope.ResearchFence;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * The questions of a book from two sides (Phase 4, Angelo 27.11.2026): the questions the mission itself demands
 * (Pflichtfragen), and per group of the clustering of the basket the questions of fact its passages answer
 * (Befundfragen); then every question checked against the mission and its Weidezaun. A demanded question the basket
 * does not answer stays visible, so a part of the mission missing from the basket is not lost. Only mission and fence
 * are the measure; the concept (the mind map) is not asked and not shown.
 * <ul>
 * <li>{@link #draftMission}: one call with the fence alone, the questions the book must answer by its mission.</li>
 * <li>{@link #draft}: one call per group with the fence and the group's passages (heading path and text); a group
 * larger than one context is asked in parts, the questions of the parts are joined. The model names the group and
 * gives as many questions as the passages answer, none of the book's structure and none of opinion.</li>
 * <li>{@link #check}: all questions in bundles that fit the context, each with INNEN, AUSSEN or UNKLAR and a reason.
 * A question the answer misses is asked once more; a question still without a verdict stays in the book's question
 * list as not checked (nothing is dropped by a failure).</li>
 * </ul>
 * Every size is a setting of the context window; there is no fixed number of questions, groups or calls.
 */
public final class StockQuestions {

    /** A passage of a group as the model reads it. */
    public static final class Passage {
        private final String passageId;
        private final String heading;
        private final String text;

        public Passage(String passageId, String heading, String text) {
            this.passageId = passageId;
            this.heading = heading == null ? "" : heading.trim();
            this.text = text == null ? "" : text.trim();
        }

        public String getPassageId() {
            return passageId;
        }
    }

    /** What the drafting of one group gave: its name and its questions. */
    public static final class Drafted {
        private final String groupId;
        private final String title;
        private final List<String> questions;
        private final String problem;

        Drafted(String groupId, String title, List<String> questions, String problem) {
            this.groupId = groupId;
            this.title = title;
            this.questions = Collections.unmodifiableList(new ArrayList<String>(questions));
            this.problem = problem;
        }

        public String getGroupId() {
            return groupId;
        }

        /** The name of the group as the model gave it; {@code ""} when no call succeeded. */
        public String getTitle() {
            return title;
        }

        public List<String> getQuestions() {
            return questions;
        }

        /** Why a part of the group got no questions; {@code ""} when every call succeeded. */
        public String getProblem() {
            return problem;
        }
    }

    /** A question's relation to the mission and its fence. */
    public enum Relation { INNEN, AUSSEN, UNKLAR, UNGEPRUEFT }

    /** The verdict on one question. */
    public static final class Verdict {
        private final Relation relation;
        private final String reason;

        Verdict(Relation relation, String reason) {
            this.relation = relation;
            this.reason = reason;
        }

        public Relation getRelation() {
            return relation;
        }

        public String getReason() {
            return reason;
        }
    }

    /** Sizes from the context window; immutable. Every value is a setting, none a limit of the method. */
    public static final class Options {
        public static final int DEFAULT_PARALLEL = 1;
        public static final int DEFAULT_CONTEXT_TOKENS = 8192;
        public static final double DEFAULT_CHARS_PER_TOKEN = 3.0;
        public static final int DEFAULT_PASSAGE_CHARS = 1500;
        public static final int DEFAULT_DRAFT_ANSWER_TOKENS = 1500;
        public static final int DEFAULT_CHECK_ANSWER_TOKENS_PER_QUESTION = 120;

        private final int parallel;
        private final int contextTokens;
        private final double charsPerToken;
        private final int passageChars;
        private final int draftAnswerTokens;
        private final int checkAnswerTokensPerQuestion;

        public Options(int parallel, int contextTokens, double charsPerToken, int passageChars,
                       int draftAnswerTokens, int checkAnswerTokensPerQuestion) {
            if (parallel < 1 || contextTokens < 1024 || charsPerToken <= 0 || passageChars < 100
                    || draftAnswerTokens < 100 || checkAnswerTokensPerQuestion < 30) {
                throw new IllegalArgumentException("parallel >= 1, contextTokens >= 1024, charsPerToken > 0, "
                        + "passageChars >= 100, draftAnswerTokens >= 100, checkAnswerTokensPerQuestion >= 30 expected");
            }
            this.parallel = parallel;
            this.contextTokens = contextTokens;
            this.charsPerToken = charsPerToken;
            this.passageChars = passageChars;
            this.draftAnswerTokens = draftAnswerTokens;
            this.checkAnswerTokensPerQuestion = checkAnswerTokensPerQuestion;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PARALLEL, DEFAULT_CONTEXT_TOKENS, DEFAULT_CHARS_PER_TOKEN,
                    DEFAULT_PASSAGE_CHARS, DEFAULT_DRAFT_ANSWER_TOKENS, DEFAULT_CHECK_ANSWER_TOKENS_PER_QUESTION);
        }

        public Options withParallel(int value) {
            return new Options(value, contextTokens, charsPerToken, passageChars, draftAnswerTokens,
                    checkAnswerTokensPerQuestion);
        }

        public Options withContextTokens(int value) {
            return new Options(parallel, value, charsPerToken, passageChars, draftAnswerTokens,
                    checkAnswerTokensPerQuestion);
        }

        public int getParallel() {
            return parallel;
        }

        public int getContextTokens() {
            return contextTokens;
        }
    }

    static final double TEMPERATURE = 0.0;
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 2000;
    private static final int REASON_CHARS = 300;
    /** Tokens of the fixed parts of a prompt besides the fence and the material (instructions). */
    private static final int FIXED_PROMPT_TOKENS = 600;

    static final String DRAFT_SYSTEM_DE = "Du erschließt einen Wissensbestand für ein Sachbuch: Du liest eine Gruppe "
            + "von Passagen und sagst, welche Sachfragen des Buchauftrags sie beantworten. Du erfindest nichts. "
            + "Antworte nur mit JSON.";
    static final String DRAFT_SYSTEM_EN = "You open up a stock of knowledge for a non-fiction book: you read a group "
            + "of passages and say which questions of fact of the book's mission they answer. You invent nothing. "
            + "Answer only with JSON.";
    static final String MISSION_SYSTEM_DE = "Du planst ein Sachbuch: Du sagst, welche Sachfragen das Buch nach seinem "
            + "Auftrag beantworten muss. Antworte nur mit JSON.";
    static final String MISSION_SYSTEM_EN = "You plan a non-fiction book: you say which questions of fact the book must "
            + "answer by its mission. Answer only with JSON.";
    static final String CHECK_SYSTEM_DE = "Du prüfst Fragen eines Sachbuchs gegen den Buchauftrag und seinen "
            + "Weidezaun. Antworte nur mit JSON.";
    static final String CHECK_SYSTEM_EN = "You check questions of a non-fiction book against the book's mission and "
            + "its fence. Answer only with JSON.";

    private final MainModelChat chat;
    private final ResearchFence fence;
    private final boolean german;
    private final Options options;
    private final Consumer<String> log;

    public StockQuestions(MainModelChat chat, ResearchFence fence, String language, Options options,
                          Consumer<String> log) {
        if (chat == null) {
            throw new IllegalArgumentException("the questions need a model");
        }
        this.chat = chat;
        this.fence = fence == null ? ResearchFence.of("") : fence;
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log == null ? line -> {
        } : log;
    }

    // ------------------------------------------------------------------------------------------------- mission

    /**
     * The questions the mission demands, from mission and fence alone (one call, one more when the answer is no valid
     * list).
     */
    public Drafted draftMission() {
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result = call(german ? MISSION_SYSTEM_DE : MISSION_SYSTEM_EN, missionPrompt(),
                    options.draftAnswerTokens, draftSchema());
            if (!result.isOk()) {
                problem = "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue;
            }
            JsonObject answer = object(result.getText());
            if (answer == null || !answer.has("fragen") || !answer.get("fragen").isJsonArray()) {
                problem = "the answer is no object with a list \"fragen\"";
                continue;
            }
            Set<String> questions = new LinkedHashSet<String>();
            for (JsonElement question : answer.getAsJsonArray("fragen")) {
                String text = question.isJsonPrimitive() ? clean(question.getAsString()) : "";
                if (!text.isEmpty()) {
                    questions.add(text);
                }
            }
            log.accept("questions of the mission: " + questions.size());
            return new Drafted("", text(answer, "titel"), new ArrayList<String>(questions), "");
        }
        log.accept("questions of the mission failed: " + problem);
        return new Drafted("", "", Collections.<String>emptyList(), problem);
    }

    String missionPrompt() {
        StringBuilder text = new StringBuilder(fence.describe(german, MISSION_CHARS));
        if (german) {
            text.append("Welche Sachfragen muss dieses Buch nach seinem Auftrag beantworten?\n"
                    + "- Jede Frage ist ein vollständiger Fragesatz und fragt nach Tatsachen, die sich mit Quellen "
                    + "belegen lassen.\n"
                    + "- Jede Frage bleibt innerhalb von Rand, Ausschlüssen, räumlichem und zeitlichem Rahmen.\n"
                    + "- Zusammen decken die Fragen alles ab, was der Auftrag verlangt; keine Frage zum Aufbau des "
                    + "Buchs, keine Meinungsfrage, keine Frage zweimal.\n"
                    + "Antworte nur mit JSON: \"titel\" (ein kurzer Arbeitstitel des Buchs) und \"fragen\" (Liste "
                    + "der Fragen).");
        } else {
            text.append("Which questions of fact must this book answer by its mission?\n"
                    + "- Every question is a complete question and asks for facts that sources can support.\n"
                    + "- Every question stays within the edge, the exclusions, place and time.\n"
                    + "- Together the questions cover everything the mission demands; no question about the "
                    + "structure of the book, none of opinion, no question twice.\n"
                    + "Answer only with JSON: \"titel\" (a short working title of the book) and \"fragen\" (the list "
                    + "of questions).");
        }
        return text.toString();
    }

    // ------------------------------------------------------------------------------------------------- draft

    /** Every group, {@link Options#getParallel()} groups at a time; one result per group in the given order. */
    public List<Drafted> draftAll(Map<String, List<Passage>> groups) {
        List<Drafted> out = new ArrayList<Drafted>();
        if (groups.isEmpty()) {
            return out;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, groups.size()));
        List<Future<Drafted>> running = new ArrayList<Future<Drafted>>();
        try {
            for (final Map.Entry<String, List<Passage>> group : groups.entrySet()) {
                running.add(pool.submit(() -> draft(group.getKey(), group.getValue())));
            }
            for (Future<Drafted> result : running) {
                out.add(result.get());
            }
        } catch (ExecutionException crashed) {
            throw new IllegalStateException("question draft crashed", crashed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("question draft interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /** One group: one call per part that fits the context, the questions of all parts joined without repeats. */
    public Drafted draft(String groupId, List<Passage> passages) {
        String title = "";
        Set<String> questions = new LinkedHashSet<String>();
        List<String> problems = new ArrayList<String>();
        for (List<Passage> part : parts(passages)) {
            String problem = "";
            JsonObject answer = null;
            for (int attempt = 1; attempt <= ATTEMPTS && answer == null; attempt++) {
                MainModelChatResult result = call(german ? DRAFT_SYSTEM_DE : DRAFT_SYSTEM_EN, draftPrompt(part),
                        options.draftAnswerTokens, draftSchema());
                if (!result.isOk()) {
                    problem = "model call failed (" + result.getStatus() + "): " + result.getDetail();
                    continue;
                }
                answer = object(result.getText());
                if (answer == null || !answer.has("fragen") || !answer.get("fragen").isJsonArray()) {
                    answer = null;
                    problem = "the answer is no object with a list \"fragen\"";
                }
            }
            if (answer == null) {
                problems.add(problem);
                continue;
            }
            if (title.isEmpty()) {
                title = text(answer, "titel");
            }
            for (JsonElement question : answer.getAsJsonArray("fragen")) {
                String text = question.isJsonPrimitive() ? clean(question.getAsString()) : "";
                if (!text.isEmpty()) {
                    questions.add(text);
                }
            }
        }
        log.accept("questions of group " + groupId + ": " + questions.size()
                + (problems.isEmpty() ? "" : ", " + problems.size() + " parts failed"));
        return new Drafted(groupId, title, new ArrayList<String>(questions), String.join("; ", problems));
    }

    /** The passages in parts whose prompt and answer fit the context; at least one passage each. */
    List<List<Passage>> parts(List<Passage> passages) {
        int budget = (int) ((options.contextTokens - FIXED_PROMPT_TOKENS - options.draftAnswerTokens)
                * options.charsPerToken) - fence.describe(german, MISSION_CHARS).length();
        List<List<Passage>> out = new ArrayList<List<Passage>>();
        List<Passage> current = new ArrayList<Passage>();
        int used = 0;
        for (Passage passage : passages) {
            if (passage.text.isEmpty()) {
                continue;
            }
            int size = block(passage, 0).length();
            if (!current.isEmpty() && used + size > budget) {
                out.add(current);
                current = new ArrayList<Passage>();
                used = 0;
            }
            current.add(passage);
            used += size;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    String draftPrompt(List<Passage> part) {
        StringBuilder text = new StringBuilder(fence.describe(german, MISSION_CHARS));
        text.append(german ? "Passagen einer Gruppe aus dem Bestand:\n\n" : "Passages of one group of the stock:\n\n");
        for (int i = 0; i < part.size(); i++) {
            text.append(block(part.get(i), i + 1));
        }
        if (german) {
            text.append("\nWelche Sachfragen des Buchauftrags beantworten diese Passagen?\n"
                    + "- Jede Frage ist ein vollständiger Fragesatz, den mindestens eine der Passagen beantwortet.\n"
                    + "- Nur Fragen, die zum Gegenstand des Buchauftrags gehören; was die Passagen sonst noch "
                    + "behandeln, fragst du nicht.\n"
                    + "- Keine Fragen zum Aufbau des Buchs, keine Meinungsfragen, keine Frage zweimal.\n"
                    + "- So viele Fragen, wie die Passagen beantworten, und keine Frage, die sie nicht beantworten.\n"
                    + "Antworte nur mit JSON: \"titel\" (ein kurzer Name für das, wovon die Gruppe handelt) und "
                    + "\"fragen\" (Liste der Fragen; leer, wenn die Passagen nichts zum Buchauftrag sagen).");
        } else {
            text.append("\nWhich questions of fact of the book's mission do these passages answer?\n"
                    + "- Every question is a complete question that at least one of the passages answers.\n"
                    + "- Only questions that belong to the subject of the mission; do not ask about what else the "
                    + "passages treat.\n"
                    + "- No questions about the structure of the book, none of opinion, no question twice.\n"
                    + "- As many questions as the passages answer, and none they do not answer.\n"
                    + "Answer only with JSON: \"titel\" (a short name for what the group is about) and \"fragen\" "
                    + "(the list of questions; empty when the passages say nothing about the mission).");
        }
        return text.toString();
    }

    private String block(Passage passage, int number) {
        StringBuilder text = new StringBuilder();
        text.append('[').append(number).append(']');
        if (!passage.heading.isEmpty()) {
            text.append(' ').append(german ? "Überschrift: " : "Heading: ").append(passage.heading);
        }
        String body = passage.text.replaceAll("\\s+", " ");
        boolean cut = body.length() > options.passageChars;
        text.append("\n\"\"\"\n").append(cut ? ResearchFence.cut(body, options.passageChars) : body)
                .append("\n\"\"\"\n");
        return text.toString();
    }

    static String draftSchema() {
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", string.deepCopy());
        JsonObject properties = new JsonObject();
        properties.add("titel", string);
        properties.add("fragen", list);
        return object(properties, "titel", "fragen").toString();
    }

    // ------------------------------------------------------------------------------------------------- check

    /**
     * Every question against mission and fence, in bundles that fit the context.
     *
     * @return the verdict per question, in the given order; a question without a valid verdict is
     * {@link Relation#UNGEPRUEFT} with the cause
     */
    public List<Verdict> check(List<String> questions) {
        Map<Integer, Verdict> done = new LinkedHashMap<Integer, Verdict>();
        for (List<Integer> bundle : bundles(questions)) {
            List<Integer> open = new ArrayList<Integer>(bundle);
            String problem = "";
            for (int attempt = 1; attempt <= ATTEMPTS && !open.isEmpty(); attempt++) {
                MainModelChatResult result = call(german ? CHECK_SYSTEM_DE : CHECK_SYSTEM_EN,
                        checkPrompt(questions, open), options.checkAnswerTokensPerQuestion * open.size(),
                        checkSchema());
                if (!result.isOk()) {
                    problem = "model call failed (" + result.getStatus() + "): " + result.getDetail();
                    continue;
                }
                Map<String, JsonObject> answers = entries(result.getText());
                List<Integer> still = new ArrayList<Integer>();
                for (int i = 0; i < open.size(); i++) {
                    JsonObject answer = answers.get("F" + (i + 1));
                    Relation relation = answer == null ? null : relationOf(text(answer, "urteil"));
                    if (relation == null) {
                        still.add(open.get(i));
                    } else {
                        done.put(open.get(i), new Verdict(relation, cut(text(answer, "grund"), REASON_CHARS)));
                    }
                }
                problem = still.isEmpty() ? "" : "the answer missed " + still.size() + " of " + open.size()
                        + " questions or gave no valid verdict for them";
                open = still;
            }
            for (Integer index : open) {
                done.put(index, new Verdict(Relation.UNGEPRUEFT, problem));
            }
        }
        List<Verdict> out = new ArrayList<Verdict>();
        for (int i = 0; i < questions.size(); i++) {
            out.add(done.get(i));
        }
        return out;
    }

    /** The question indexes in bundles whose prompt and answer fit the context; at least one question each. */
    List<List<Integer>> bundles(List<String> questions) {
        int budget = (int) ((options.contextTokens - FIXED_PROMPT_TOKENS) * options.charsPerToken)
                - fence.describe(german, MISSION_CHARS).length();
        int answerChars = (int) (options.checkAnswerTokensPerQuestion * options.charsPerToken);
        List<List<Integer>> out = new ArrayList<List<Integer>>();
        List<Integer> current = new ArrayList<Integer>();
        int used = 0;
        for (int i = 0; i < questions.size(); i++) {
            int size = questions.get(i).length() + answerChars;
            if (!current.isEmpty() && used + size > budget) {
                out.add(current);
                current = new ArrayList<Integer>();
                used = 0;
            }
            current.add(i);
            used += size;
        }
        if (!current.isEmpty()) {
            out.add(current);
        }
        return out;
    }

    String checkPrompt(List<String> questions, List<Integer> open) {
        StringBuilder text = new StringBuilder(fence.describe(german, MISSION_CHARS));
        text.append(german ? "Fragen:\n" : "Questions:\n");
        for (int i = 0; i < open.size(); i++) {
            text.append("[F").append(i + 1).append("] ").append(questions.get(open.get(i))).append('\n');
        }
        if (german) {
            text.append("\nGehört jede Frage in dieses Buch? Maßstab sind allein der Buchauftrag und sein Weidezaun, "
                    + "wie oben angegeben.\n"
                    + "- \"INNEN\": Die Frage betrifft den Gegenstand des Buchauftrags und liegt innerhalb von Rand, "
                    + "Ausschlüssen, räumlichem und zeitlichem Rahmen.\n"
                    + "- \"AUSSEN\": Die Frage betrifft einen anderen Gegenstand, etwas Ausgeschlossenes oder einen "
                    + "anderen räumlichen oder zeitlichen Rahmen.\n"
                    + "- \"UNKLAR\": Das lässt sich aus Auftrag und Weidezaun nicht entscheiden.\n"
                    + "Antworte nur mit JSON: \"urteile\", für jede Frage ein Eintrag mit \"nr\" (F1, F2, …), "
                    + "\"grund\" (ein Satz) und \"urteil\".");
        } else {
            text.append("\nDoes each question belong in this book? The only measure is the book's mission and its "
                    + "fence as given above.\n"
                    + "- \"INNEN\": the question concerns the subject of the mission and lies within the edge, the "
                    + "exclusions, place and time.\n"
                    + "- \"AUSSEN\": the question concerns another subject, something excluded, or another place or "
                    + "time.\n"
                    + "- \"UNKLAR\": mission and fence do not decide it.\n"
                    + "Answer only with JSON: \"urteile\", one entry per question with \"nr\" (F1, F2, …), \"grund\" "
                    + "(one sentence) and \"urteil\".");
        }
        return text.toString();
    }

    static String checkSchema() {
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");
        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "string");
        JsonArray values = new JsonArray();
        for (Relation relation : new Relation[]{Relation.INNEN, Relation.AUSSEN, Relation.UNKLAR}) {
            values.add(relation.name());
        }
        verdict.add("enum", values);
        JsonObject properties = new JsonObject();
        properties.add("nr", string);
        properties.add("grund", string.deepCopy());
        properties.add("urteil", verdict);
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.add("items", object(properties, "nr", "grund", "urteil"));
        JsonObject top = new JsonObject();
        top.add("urteile", list);
        return object(top, "urteile").toString();
    }

    static Relation relationOf(String value) {
        String v = value.toUpperCase(Locale.ROOT).replace("Ä", "AE").replace("ß", "SS");
        if (v.equals("INNEN")) {
            return Relation.INNEN;
        }
        if (v.equals("AUSSEN")) {
            return Relation.AUSSEN;
        }
        return v.equals("UNKLAR") ? Relation.UNKLAR : null;
    }

    // ------------------------------------------------------------------------------------------------- shared

    private MainModelChatResult call(String system, String prompt, int answerTokens, String schema) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(system));
        messages.add(ChatMessage.user(prompt));
        try {
            MainModelChatResult result = chat.completeJson(messages, TEMPERATURE, answerTokens, schema);
            return result == null ? MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    "model call returned nothing") : result;
        } catch (RuntimeException failed) {
            return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                    failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
        }
    }

    private static JsonObject object(String answer) {
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            return element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** The entries of an answer list by their label ({@code F1}, …; a bare number counts as well). */
    static Map<String, JsonObject> entries(String answer) {
        Map<String, JsonObject> out = new LinkedHashMap<String, JsonObject>();
        JsonObject top = object(answer);
        JsonElement list = top == null ? null : top.get("urteile");
        if (list == null || !list.isJsonArray()) {
            return out;
        }
        for (JsonElement entry : list.getAsJsonArray()) {
            if (entry.isJsonObject()) {
                String label = text(entry.getAsJsonObject(), "nr").toUpperCase(Locale.ROOT).replaceAll("[\\s\\[\\]]",
                        "");
                if (label.matches("\\d+")) {
                    label = "F" + label;
                }
                if (!label.isEmpty() && !out.containsKey(label)) {
                    out.put(label, entry.getAsJsonObject());
                }
            }
        }
        return out;
    }

    private static JsonObject object(JsonObject properties, String... required) {
        JsonArray names = new JsonArray();
        for (String name : required) {
            names.add(name);
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "object");
        o.add("properties", properties);
        o.add("required", names);
        o.addProperty("additionalProperties", false);
        return o;
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? clean(value.getAsString()) : "";
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : ResearchFence.cut(text, max);
    }
}
