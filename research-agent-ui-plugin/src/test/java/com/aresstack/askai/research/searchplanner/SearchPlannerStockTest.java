package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.research.host.ResearchRuntimeSettings;
import com.aresstack.askai.research.lead.LeadTask;
import com.aresstack.askai.research.lead.OllamaStockChat;
import com.aresstack.askai.research.lead.SearchStrategy;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchMemoryStore;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SearchRecord;
import com.aresstack.askai.research.plan.SourcePreference;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 3 on stored data: the search planner with gemma4:e2b (thinking off) on three fixed states of the memory
 * built from the stock of 17.11.2026: its mission, the four cards and 20 questions of its concept (fragen-1711.json),
 * the 69 searches the app ran or queued that day (suchen-1711.json, with the passages they brought), quotes from the
 * architect's evidence pairs (belegpaare-1711.jsonl, only on the owner's machine) and the 17 named topics
 * (erwartete-themen-1711.json).
 *
 * <ul>
 *   <li>{@code G1} start: no search yet; round 1, eight tasks with the standard strategy (six questions, two cards
 *       with four questions each).</li>
 *   <li>{@code G2} after the searches of 17.11.: round 1 searched every question once (the question as the search);
 *       the answerable ones found up to two direct pieces of evidence (pairs BELEGT), every question up to one lead
 *       (HINWEIS). The 69 searches of 17.11. stand beside them as searches of round 1: each under the cards whose
 *       name and questions share the most rare word stems with it (the first five letters of a word that at most
 *       half of the cards have; all cards when it shares none) and under the questions of those cards it shares such
 *       a stem with; a finished one with its passages, a queued one as running. The stock does not say how many
 *       sources a search of 17.11. brought, so they show 0 sources unless the test data give a count
 *       ({@code quellen}), and none brought new evidence (the evidence of G2 came with the searches of round 1).
 *       Round 2, eight tasks with the standard strategy.</li>
 *   <li>{@code G3} change and new topics: G2, and round 2 searched the four questions the stock cannot answer
 *       (beantwortbar false, never shown) once more without new evidence; the topic finder found the 17 named topics,
 *       and the lead asked six new questions on four of the eight INNEN topics (written for this test). Round 3: the
 *       four with a changed strategy (two with an instruction, two without) and the four topics.</li>
 * </ul>
 *
 * <p>Proposed criteria (the architect fixes them before a run counts), in every run: P1 at least 90 % of the tasks
 * get a search. P2 (build plan: every search names a question and repeats no earlier search), on what the model
 * wrote before the rules: at least 90 % of its searches name a question of their task, and at most 10 % repeat an
 * earlier search or one of another task of the round (the same words in any order). P3 of the searches taken, at
 * most 10 % share 80 % of their words with an earlier search. P4 (only reported) the strategy shows in at least half
 * of the searches of a changed task: another language (an English word, no German letter), named publishers
 * ({@code site:} or a publisher's name), another kind of source (a kind of document), other words (less than half
 * the words of every earlier search of its question). {@code result.json} holds ids, counts, strategies and classes
 * of reasons; no search, no question text, no quote and no purpose (those stay in {@code projekt/<state>-<run>/} on
 * the machine: the memory and {@code search-planner/log.jsonl} with the raw answers).</p>
 *
 * <pre>
 *   ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*SearchPlannerStockTest*'
 *     -Daskai.stock.plan.korpus=&lt;replay-corpus.json&gt; -Daskai.stock.plan.out=&lt;folder&gt;
 *     -Daskai.stock.plan.suchen=&lt;suchen-1711.json&gt; -Daskai.stock.plan.belegpaare=&lt;belegpaare-1711.jsonl&gt;
 *     -Daskai.stock.plan.chatUrl=http://127.0.0.1:11434 -Daskai.stock.plan.chatModel=gemma4:e2b
 *   optional: lagen (G1,G2,G3), laeufe (2), parallel (4), think (false; true, or omit to leave it out), numCtx,
 *   timeoutSeconds (300), fragen, erwartet (files instead of the copies in the test resources)
 * </pre>
 */
public class SearchPlannerStockTest {

    private static final String P = "askai.stock.plan.";
    private static final String RESOURCES = "/research-lead-stock/";
    private static final long T = 1_000L;
    static final List<String> STATES = Arrays.asList("G1", "G2", "G3");
    private static final Set<String> STOP = new HashSet<String>(Arrays.asList("welche", "welcher", "welches",
            "wie", "was", "wann", "woran", "wo", "gibt", "es", "die", "der", "das", "den", "dem", "des", "und", "oder",
            "von", "fuer", "mit", "auf", "bei", "aus", "etwa", "eine", "einer", "einem", "einen", "ein", "im", "in",
            "ist", "sind", "werden", "wird", "laesst", "sich", "man", "zu", "zur", "zum", "nach", "the", "of", "and",
            "for", "on", "to", "by", "with", "at", "from", "an"));
    private static final Pattern SITE = Pattern.compile("(?i)-?site:\\S+");
    private static final Set<String> ENGLISH = new HashSet<String>(Arrays.asList("the", "of", "and", "for", "on",
            "in", "with", "at", "to", "by", "from", "construction", "worker", "workers", "safety", "cost", "costs",
            "study", "studies", "evaluation", "effectiveness", "evidence", "injury", "injuries", "incident",
            "incidents", "rate", "rates", "accident", "accidents", "adoption", "implementation", "benefit",
            "benefits", "return", "investment", "measurement", "metrics", "performance", "indicators", "training",
            "acceptance", "rollout", "device", "devices", "case", "report", "reports", "review", "cities", "city"));
    private static final List<String> PUBLISHERS = Arrays.asList("bg bau", "berufsgenossenschaft", "unfallkasse",
            "unfallversicherung", "ifa", "fraunhofer", "hochschule", "universitaet", "university", "bundesanstalt",
            "verband", "bauindustrie", "ministerium", "bmas", "niosh", "hse", "zdb", "vdi");
    private static final List<String> DOCUMENT_KINDS = Arrays.asList("studi", "leitf", "norm", "stati", "beric",
            "repor", "veror", "urtei", "pdf", "filet", "merkb", "richt", "handl", "evalu", "metaa", "meta", "uebers",
            "revie", "disse", "white", "falls", "umfra", "erheb", "regel", "vorsc", "posit", "guide", "stand", "study",
            "surve", "daten", "kennz");

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /** A question of the concept; {@code answerable} is the stock's mark and is never shown to the model. */
    static final class Question {
        final String id;
        final String card;
        final String text;
        final boolean answerable;

        Question(String id, String card, String text, boolean answerable) {
            this.id = id;
            this.card = card;
            this.text = text;
            this.answerable = answerable;
        }
    }

    /** A named topic as the topic finder would hand it over, with its expected fence verdict. */
    static final class Named {
        final String id;
        final String name;
        final ResearchTopic.Fence fence;

        Named(String id, String name, ResearchTopic.Fence fence) {
            this.id = id;
            this.name = name;
            this.fence = fence;
        }
    }

    /** One of the architect's question/passage pairs with his verdict (BELEGT, HINWEIS, KEIN). */
    static final class Pair {
        final String question;
        final String kind;
        final String passage;
        final String source;
        final String text;

        Pair(String question, String kind, String passage, String source, String text) {
            this.question = question;
            this.kind = kind;
            this.passage = passage;
            this.source = source;
            this.text = text;
        }
    }

    /** A search of the stock day: its words, who asked for it, finished or queued, the passages it brought. */
    static final class OldSearch {
        final String query;
        final String origin;
        final boolean finished;
        final int passages;
        /** Sources of the search when the test data name them ({@code quellen}); the stock of 17.11. does not. */
        final int sources;

        OldSearch(String query, String origin, boolean finished, int passages, int sources) {
            this.query = query;
            this.origin = origin;
            this.finished = finished;
            this.passages = passages;
            this.sources = sources;
        }
    }

    /** What the states are built from, with the tasks of every state. */
    static final class Data {
        String mission = "";
        final Map<String, String> cards = new LinkedHashMap<String, String>();
        final List<Question> questions = new ArrayList<Question>();
        final List<Named> topics = new ArrayList<Named>();
        final List<Pair> pairs = new ArrayList<Pair>();
        final List<OldSearch> old = new ArrayList<OldSearch>();
        /** id, topic ({@code @} and the start of its name), text. */
        final List<String[]> leadQuestions = new ArrayList<String[]>();
        /** Per state: target, target id ({@code @} and the start of a topic's name), questions, strategy, Vorgabe. */
        final Map<String, String[][]> tasks = new LinkedHashMap<String, String[][]>();
        boolean realPairs;

        List<Question> unanswerable() {
            List<Question> list = new ArrayList<Question>();
            for (Question question : questions) {
                if (!question.answerable) {
                    list.add(question);
                }
            }
            return list;
        }

        /** Up to {@code max} pairs of a question and verdict from different sources; made up without real ones. */
        List<Pair> pairs(String question, String kind, int max) {
            List<Pair> chosen = new ArrayList<Pair>();
            if (!realPairs) {
                for (int i = 1; i <= max; i++) {
                    chosen.add(new Pair(question, kind, "syn-" + question + "-" + kind + "-" + i, "quelle-syn-"
                            + question + "-" + i, "Synthetische Fundstelle " + i + " (" + kind + ") zu " + question
                            + " für den Bestandstest des Suchplaners."));
                }
                return chosen;
            }
            Set<String> sources = new HashSet<String>();
            Set<String> texts = new HashSet<String>();
            for (Pair pair : pairs) {
                if (chosen.size() < max && pair.question.equals(question) && pair.kind.equals(kind)
                        && sources.add(pair.source) && texts.add(pair.text.trim())) {
                    chosen.add(pair);
                }
            }
            return chosen;
        }

        /** The id of the named topic whose name starts with the words after {@code @}; anything else as it is. */
        String resolve(String reference) {
            if (!reference.startsWith("@")) {
                return reference;
            }
            String start = fold(reference.substring(1));
            for (Named topic : topics) {
                if (fold(topic.name).startsWith(start)) {
                    return topic.id;
                }
            }
            throw new IllegalStateException("no named topic starts with " + reference.substring(1));
        }
    }

    /** Proposed thresholds and how the run works. */
    static final class Settings {
        SearchPlanner.Options options = SearchPlanner.Options.defaults();
        List<String> states = STATES;
        int runs = 2;
        double p1Tasks = 0.9;
        double p2Named = 0.9;
        double p2Repeats = 0.1;
        double p3Near = 0.1;
        double nearOverlap = 0.8;
        double wordingOverlap = 0.5;
    }

    /** One round of one state with what the memory held before it. */
    static final class Outcome {
        final String state;
        final int run;
        final RoundPlan plan;
        final List<SearchRecord> earlier;
        final ResearchMemory memory;
        final int calls;
        final long millis;

        Outcome(String state, int run, RoundPlan plan, List<SearchRecord> earlier, ResearchMemory memory, int calls,
                long millis) {
            this.state = state;
            this.run = run;
            this.plan = plan;
            this.earlier = earlier;
            this.memory = memory;
            this.calls = calls;
            this.millis = millis;
        }
    }

    /** What one task's round shows. */
    static final class Measure {
        int tasks;
        int modelTasks;
        int proposals;
        int taken;
        int wrongQuestion;
        int repeats;
        int near;
        int changed;
        int changedShown;
        final Map<String, Integer> classes = new TreeMap<String, Integer>();

        void add(Measure other) {
            tasks += other.tasks;
            modelTasks += other.modelTasks;
            proposals += other.proposals;
            taken += other.taken;
            wrongQuestion += other.wrongQuestion;
            repeats += other.repeats;
            near += other.near;
            changed += other.changed;
            changedShown += other.changedShown;
            for (Map.Entry<String, Integer> entry : other.classes.entrySet()) {
                classes.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void sampleStock() throws Exception {
        Data data = sampleData();
        Settings settings = new Settings();
        settings.runs = 1;
        SearchPlannerTest.Scripted chat = new SearchPlannerTest.Scripted(SearchPlannerStockTest::goodPlanner);
        File out = tmp.newFolder("out");

        JsonObject result = run(data, chat, settings, out, new JsonObject());

        JsonObject criteria = result.getAsJsonObject("kriterien");
        for (String name : new String[]{"P1", "P2", "P3"}) {
            assertTrue(name + " " + criteria.get(name), criteria.getAsJsonObject(name).get("erfuellt").getAsBoolean());
        }
        assertEquals("2 of 2 changed tasks show their strategy", 1.0, criteria.getAsJsonObject("P4")
                .getAsJsonArray("werte").get(0).getAsDouble(), 1e-9);
        assertTrue(result.get("befund").getAsString(), result.get("bestanden").getAsBoolean());
        assertEquals("one call per task", 9, chat.calls.get());
        JsonObject g2 = runOf(result, "G2");
        assertEquals(2, g2.getAsJsonArray("aufgaben").size());
        JsonObject theme = g2.getAsJsonArray("aufgaben").get(1).getAsJsonObject();
        assertEquals("THEMA", theme.get("ziel").getAsString());
        assertEquals("karte-3", theme.get("zielId").getAsString());
        assertEquals("[\"F05\",\"F06\"]", theme.getAsJsonArray("fragen").toString());
        JsonObject g3 = runOf(result, "G3");
        JsonObject language = g3.getAsJsonArray("aufgaben").get(1).getAsJsonObject();
        assertEquals("andere_sprache", language.get("strategie").getAsString());
        assertFalse(language.get("vorgabe").getAsBoolean());
        assertEquals(2, language.get("strategieSichtbar").getAsInt());
        assertEquals("thema-0002", g3.getAsJsonArray("aufgaben").get(3).getAsJsonObject().get("zielId")
                .getAsString());

        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()), StandardCharsets.UTF_8);
        for (String text : new String[]{"Wie wirkt Tempo 30", "Fallbeispiele", "Studie Kosten", "Findet Zahlen",
                "In der Tempo-30-Zone", "Wie wirken Schulstraßen", "Tempo 30 Unfallzahlen", "Kostenberichte"}) {
            assertFalse("no " + text + " in result.json", written.contains(text));
        }
        List<JsonObject> tasks = new SearchPlannerLog(new File(out, "projekt/G3-1")).open();
        assertEquals(4, tasks.size());
        assertEquals("the raw answer stays on the machine", 1, tasks.get(0).getAsJsonArray("rohantworten").size());
        ResearchMemory stored = new ResearchMemoryStore(new File(out, "projekt/G2-1")).open();
        assertEquals("6 searches of round 1, 4 of the stock day, 4 planned", 14, stored.searches().size());
        SearchRecord tempo = null;
        for (SearchRecord search : stored.searches()) {
            if (search.getQuery().equals("Tempo 30 Unfallzahlen Hauptstraßen")) {
                tempo = search;
            }
        }
        assertEquals("under the card that shares its stems", "[karte-1]", tempo.getTopicIds().toString());
        assertEquals("[F01, F02]", tempo.getQuestionIds().toString());
        assertEquals(40, tempo.getYield().getPassages());
    }

    @Test
    public void aPlannerThatRepeatsAndNamesForeignQuestionsFailsWhileTheRulesHold() throws Exception {
        Data data = sampleData();
        Settings settings = new Settings();
        settings.runs = 1;
        SearchPlannerTest.Scripted chat = new SearchPlannerTest.Scripted(SearchPlannerStockTest::repeatingPlanner);
        File out = tmp.newFolder("schlecht");

        JsonObject result = run(data, chat, settings, out, new JsonObject());

        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertFalse("half of the searches name a foreign question", criteria.getAsJsonObject("P2").get("erfuellt")
                .getAsBoolean());
        assertFalse(result.get("bestanden").getAsBoolean());
        JsonObject g2 = runOf(result, "G2");
        JsonObject first = g2.getAsJsonArray("aufgaben").get(0).getAsJsonObject();
        assertEquals("FEHLER", first.get("status").getAsString());
        assertEquals("KEINE_BRAUCHBARE", first.get("fehler").getAsString());
        assertEquals("two answers, each a repeat and a foreign question", 2, first.getAsJsonObject("verworfen")
                .get("ALT").getAsInt());
        assertEquals(2, first.getAsJsonObject("verworfen").get("FRAGE").getAsInt());
        assertEquals("the rules took no repeat", 0, g2.getAsJsonObject("summe").get("genommen").getAsInt());
    }

    @Test
    public void storedStock() throws Exception {
        String corpusPath = System.getProperty(P + "korpus", "");
        Assume.assumeTrue("search planner stock test skipped (no -D" + P + "korpus)", !corpusPath.isEmpty());
        String chatUrl = System.getProperty(P + "chatUrl", "http://127.0.0.1:11434");
        String chatModel = System.getProperty(P + "chatModel", "gemma4:e2b");
        String numCtx = System.getProperty(P + "numCtx", "");
        String thinkSetting = System.getProperty(P + "think", "false");
        Boolean think = "omit".equalsIgnoreCase(thinkSetting) ? null : Boolean.valueOf("true".equalsIgnoreCase(
                thinkSetting));
        int timeout = 1000 * Integer.getInteger(P + "timeoutSeconds", 300);
        String searchesPath = System.getProperty(P + "suchen", "");
        String pairsPath = System.getProperty(P + "belegpaare", "");
        File out = new File(System.getProperty(P + "out", "build/component-stock/search-planner"),
                chatModel.replaceAll("[^A-Za-z0-9._-]", "_"));
        Files.createDirectories(out.toPath());

        Settings settings = new Settings();
        settings.options = SearchPlanner.Options.defaults().withParallel(Integer.getInteger(P + "parallel", 4));
        settings.runs = Integer.getInteger(P + "laeufe", 2);
        settings.states = Arrays.asList(System.getProperty(P + "lagen", String.join(",", STATES)).split("\\s*,\\s*"));

        Data data = new Data();
        try (Reader in = new InputStreamReader(new FileInputStream(corpusPath), StandardCharsets.UTF_8)) {
            data.mission = readMission(in);
        }
        readQuestions(text("fragen", "fragen-1711.json"), data);
        readTopics(text("erwartet", "erwartete-themen-1711.json"), data);
        if (!searchesPath.isEmpty()) {
            readSearches(new String(Files.readAllBytes(new File(searchesPath).toPath()), StandardCharsets.UTF_8),
                    data);
        } else {
            System.out.println("[search-planner] no -D" + P + "suchen: G2 and G3 without the searches of 17.11.");
        }
        if (!pairsPath.isEmpty()) {
            try (Reader in = new InputStreamReader(new FileInputStream(pairsPath), StandardCharsets.UTF_8)) {
                readPairs(in, data);
            }
        } else {
            System.out.println("[search-planner] no -D" + P + "belegpaare: the quotes of the evidence are made up");
        }
        data.leadQuestions.addAll(Arrays.asList(LEAD_QUESTIONS_1711));
        data.tasks.putAll(TASKS_1711);

        JsonObject sources = new JsonObject();
        sources.addProperty("korpus", corpusPath);
        sources.addProperty("auftragZeichen", data.mission.length());
        sources.addProperty("suchen", searchesPath.isEmpty() ? "keine" : searchesPath + " (" + data.old.size()
                + " Suchen)");
        sources.addProperty("belegpaare", pairsPath.isEmpty() ? "keine (Belege synthetisch)" : pairsPath + " ("
                + data.pairs.size() + " Paare)");
        sources.addProperty("chat", chatModel + " at " + chatUrl + (numCtx.isEmpty() ? "" : ", num_ctx " + numCtx)
                + (think == null ? ", think weggelassen" : ", think:" + think) + ", Zeitlimit " + timeout / 1000
                + " s, parallel " + settings.options.getParallel());
        System.out.println("[search-planner] states " + settings.states + ", " + settings.runs + " runs, chat "
                + chatModel);

        OllamaStockChat chat = new OllamaStockChat(chatUrl, chatModel, numCtx.isEmpty() ? null
                : Integer.valueOf(numCtx), think, timeout);
        JsonObject result = run(data, chat, settings, out, sources);
        JsonObject usage = new JsonObject();
        usage.addProperty("aufrufe", chat.calls.get());
        usage.addProperty("fehlgeschlagen", chat.failures.get());
        usage.addProperty("tokens", chat.evalTokens.get());
        result.add("modell", usage);
        write(new File(out, "result.json"), result);
        System.out.println("[search-planner] result " + new File(out, "result.json").getAbsolutePath());
        assertTrue(result.get("befund").getAsString(), result.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- the stock

    /** The tasks of the three states on the concept of 17.11. */
    static final Map<String, String[][]> TASKS_1711 = new LinkedHashMap<String, String[][]>();

    static {
        TASKS_1711.put("G1", new String[][]{
                {"FRAGE", "F01", "F01", "standard", ""},
                {"FRAGE", "F06", "F06", "standard", ""},
                {"FRAGE", "F11", "F11", "standard", ""},
                {"FRAGE", "F13", "F13", "standard", ""},
                {"FRAGE", "F16", "F16", "standard", ""},
                {"FRAGE", "F18", "F18", "standard", ""},
                {"THEMA", "karte-1", "F02,F03,F04,F05", "standard", ""},
                {"THEMA", "karte-2", "F07,F08,F09,F10", "standard", ""}});
        TASKS_1711.put("G2", new String[][]{
                {"FRAGE", "F02", "F02", "standard", ""},
                {"FRAGE", "F05", "F05", "standard", ""},
                {"FRAGE", "F12", "F12", "standard", ""},
                {"FRAGE", "F14", "F14", "standard", ""},
                {"FRAGE", "F15", "F15", "standard", ""},
                {"FRAGE", "F19", "F19", "standard", ""},
                {"THEMA", "karte-3", "F11,F13", "standard", ""},
                {"THEMA", "karte-4", "F16,F17,F18,F20", "standard", ""}});
        TASKS_1711.put("G3", new String[][]{
                {"FRAGE", "F07", "F07", "andere_quellenart", "Bisher nur Hersteller- und Fachartikel. Gesucht sind "
                        + "Studien, Evaluationen oder Unfallauswertungen mit Zahlen zur Wirkung."},
                {"FRAGE", "F16", "F16", "gezielte_herausgeber", "Kosten und Nutzen beziffern am ehesten "
                        + "Berufsgenossenschaften, Unfallversicherer und Verbände der Bauwirtschaft."},
                {"FRAGE", "F17", "F17", "andere_wortwahl", ""},
                {"FRAGE", "F20", "F20", "andere_sprache", ""},
                {"THEMA", "@Alleinarbeit", "L01,L02", "standard", ""},
                {"THEMA", "@Funk", "L03", "standard", ""},
                {"THEMA", "@IT-Sicherheit", "L04,L05", "standard", ""},
                {"THEMA", "@Einordnung", "L06", "standard", ""}});
    }

    /** The lead's new questions on four INNEN topics of 17.11., written for this test. */
    static final String[][] LEAD_QUESTIONS_1711 = {
            {"L01", "@Alleinarbeit", "Welche Anforderungen gelten für Personen-Notsignal-Anlagen bei Alleinarbeit auf "
                    + "Baustellen?"},
            {"L02", "@Alleinarbeit", "Wie schnell erreicht ein Notsignal aus einem Wearable die Rettungskette auf der "
                    + "Baustelle?"},
            {"L03", "@Funk", "Welche Funknetze tragen die Daten von Wearables auf Baustellen, und wo stoßen sie an "
                    + "Grenzen?"},
            {"L04", "@IT-Sicherheit", "Wie werden vernetzte Wearables gegen Angriffe und Datenabfluss geschützt?"},
            {"L05", "@IT-Sicherheit", "Welche Vorgaben zur IT-Sicherheit gelten für vernetzte Geräte im Betrieb?"},
            {"L06", "@Einordnung", "Wann gilt ein Wearable als persönliche Schutzausrüstung und wann als "
                    + "Medizinprodukt?"}};

    // ------------------------------------------------------------------------------------------------- run

    /** Every state {@code runs} times on a memory of its own, the planner's log, {@code result.json}. */
    static JsonObject run(Data data, MainModelChat model, Settings settings, File out, JsonObject sources)
            throws Exception {
        File projects = new File(out, "projekt");
        deleteTree(projects);
        Counting chat = new Counting(model);
        SourcePreference preference = SourcePreference.of(data.mission,
                ResearchRuntimeSettings.loadSourcePreference(null));
        JsonObject json = new JsonObject();
        json.addProperty("geschrieben", LocalDateTime.now().withNano(0).toString());
        json.add("quellen", sources);
        json.add("einstellungen", settingsJson(settings, data, preference));
        JsonObject states = new JsonObject();
        Map<Integer, Measure> perRun = new TreeMap<Integer, Measure>();
        long start = System.currentTimeMillis();
        for (String state : settings.states) {
            if (!STATES.contains(state) || !data.tasks.containsKey(state)) {
                throw new IllegalArgumentException("unknown state " + state + " (known: " + data.tasks.keySet() + ")");
            }
            JsonArray runs = new JsonArray();
            for (int run = 1; run <= Math.max(1, settings.runs); run++) {
                File project = new File(projects, state + "-" + run);
                ResearchMemory memory = new ResearchMemoryStore(project).open();
                build(state, memory, data);
                List<LeadTask> tasks = tasks(state, memory, data);
                SearchPlannerLog log = new SearchPlannerLog(project);
                log.open();
                List<SearchRecord> earlier = new ArrayList<SearchRecord>(memory.searches());
                SearchPlanner planner = new SearchPlanner(chat, "de", settings.options, preference,
                        line -> System.out.println("[search-planner] " + line));
                int calls = chat.calls.get();
                long began = System.currentTimeMillis();
                RoundPlan plan = planner.plan(memory, tasks, data.mission, tasks.size()
                        * settings.options.getSearchesPerTask());
                long millis = System.currentTimeMillis() - began;
                log.append(plan, System.currentTimeMillis());
                Outcome outcome = new Outcome(state, run, plan, earlier, memory, chat.calls.get() - calls, millis);
                Measure sum = new Measure();
                runs.add(runJson(outcome, settings, sum));
                perRun.computeIfAbsent(run, r -> new Measure()).add(sum);
                System.out.println("[search-planner] " + state + " run " + run + ": " + sum.modelTasks + " of "
                        + sum.tasks + " tasks with searches, " + sum.taken + " of " + sum.proposals
                        + " searches taken, " + millis + " ms");
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("aufgaben", data.tasks.get(state).length);
            entry.add("laeufe", runs);
            states.add(state, entry);
        }
        json.add("lagen", states);
        JsonObject time = new JsonObject();
        time.addProperty("ms", System.currentTimeMillis() - start);
        time.addProperty("aufrufe", chat.calls.get());
        json.add("zeit", time);
        List<String> missed = new ArrayList<String>();
        json.add("kriterien", criteria(perRun, settings, missed));
        json.addProperty("bestanden", missed.isEmpty());
        json.addProperty("befund", missed.isEmpty() ? "P1 bis P3 in jedem Lauf erfüllt"
                : "nicht erfüllt: " + String.join(", ", missed));
        write(new File(out, "result.json"), json);
        return json;
    }

    // ------------------------------------------------------------------------------------------------- states

    /** Writes the state into the (empty) memory. */
    static void build(String state, ResearchMemory memory, Data data) throws IOException {
        for (Map.Entry<String, String> card : data.cards.entrySet()) {
            memory.addTopic(ResearchTopic.ofCard(card.getKey(), card.getValue(), "anchor-" + card.getKey(), 0, T));
        }
        for (Question question : data.questions) {
            memory.addQuestion(ResearchQuestion.open(question.id, question.text, question.card,
                    ResearchQuestion.Origin.KONZEPT, T));
        }
        if (state.equals("G1")) {
            return;
        }
        for (Question question : data.questions) {
            if (question.answerable) {
                for (Pair pair : data.pairs(question.id, "BELEGT", 2)) {
                    verdict(memory, pair, EvidenceVerdict.Kind.BELEGT);
                }
            }
            for (Pair pair : data.pairs(question.id, "HINWEIS", 1)) {
                verdict(memory, pair, EvidenceVerdict.Kind.HINWEIS);
            }
            search(memory, "r1-" + question.id, cut(question.text.replace("?", "").trim(), 100), question, 1,
                    memory.evidenceOf(question.id).getDirectCount());
        }
        Map<String, Set<String>> profiles = profiles(data);
        int number = 0;
        for (OldSearch old : data.old) {
            number++;
            List<String> cards = cardsOf(old.query, profiles);
            List<String> questions = new ArrayList<String>();
            Set<String> rare = rareStems(old.query, profiles);
            for (Question question : data.questions) {
                if (cards.contains(question.card) && !Collections.disjoint(rare, stems(question.text))) {
                    questions.add(question.id);
                }
            }
            SearchRecord search = new SearchRecord("alt-" + number, old.query, questions, cards, "standard",
                    "ALT-" + old.origin.toUpperCase(Locale.ROOT), 1, null, T + 15);
            memory.record(search);
            if (old.finished) {
                memory.record(search.withYield(new SearchRecord.Yield(old.sources, old.passages, 0, 0), T + 16));
            }
        }
        if (state.equals("G2")) {
            return;
        }
        for (Question question : data.unanswerable()) {
            search(memory, "r2-" + question.id, String.join(" ", contentWords(question.text)), question, 2, 0);
        }
        for (Named topic : data.topics) {
            List<ResearchTopic.Finding> none = Collections.emptyList();
            memory.addTopic(ResearchTopic.found(topic.id, topic.name, "", none, "themenfinder", T + 30)
                    .withFence(topic.fence, "erwartetes Urteil (Testlage)", "zaunpruefung", T + 31));
        }
        for (String[] question : data.leadQuestions) {
            memory.addQuestion(ResearchQuestion.open(question[0], question[2], data.resolve(question[1]),
                    ResearchQuestion.Origin.LEITUNG, T + 32));
        }
    }

    /** The lead's tasks of the state, checked against the memory. */
    static List<LeadTask> tasks(String state, ResearchMemory memory, Data data) {
        List<LeadTask> tasks = new ArrayList<LeadTask>();
        int round = state.equals("G1") ? 1 : state.equals("G2") ? 2 : 3;
        for (String[] spec : data.tasks.get(state)) {
            LeadDecision.Target target = LeadDecision.Target.valueOf(spec[0]);
            String targetId = data.resolve(spec[1]);
            List<String> questions = Arrays.asList(spec[2].split(","));
            for (String id : questions) {
                if (memory.currentQuestion(id) == null) {
                    throw new IllegalStateException(state + ": the task on " + spec[1] + " names the unknown "
                            + "question " + id);
                }
            }
            SearchStrategy strategy = SearchStrategy.of(spec[3]);
            if (strategy == null) {
                throw new IllegalStateException(state + ": unknown strategy " + spec[3]);
            }
            tasks.add(new LeadTask(round, target, targetId, questions, strategy, spec[4], "Testlage",
                    LeadTask.Origin.MODELL));
        }
        return tasks;
    }

    private static void verdict(ResearchMemory memory, Pair pair, EvidenceVerdict.Kind kind) throws IOException {
        memory.record(EvidenceVerdict.judged(pair.question, pair.passage, pair.source, "", pair.text, kind,
                quote(pair.text), "Urteil des Architekten (Testlage)", "referenz", 1, T + 1));
    }

    private static void search(ResearchMemory memory, String id, String query, Question question, int round,
                               int newEvidence) throws IOException {
        SearchRecord search = new SearchRecord(id, query, Collections.singletonList(question.id), null, "standard",
                SearchPlanner.ORIGIN, round, null, T + round * 10L);
        memory.record(search);
        memory.record(search.withYield(new SearchRecord.Yield(4, 12, newEvidence, 0), T + round * 10L + 5));
    }

    /** Every card: the stems of its name and of its questions. */
    static Map<String, Set<String>> profiles(Data data) {
        Map<String, Set<String>> profiles = new LinkedHashMap<String, Set<String>>();
        for (Map.Entry<String, String> card : data.cards.entrySet()) {
            profiles.put(card.getKey(), new HashSet<String>(stems(card.getValue())));
        }
        for (Question question : data.questions) {
            profiles.computeIfAbsent(question.card, c -> new HashSet<String>()).addAll(stems(question.text));
        }
        return profiles;
    }

    /** The stems of a search that some card has, but at most half of the cards (rare stems). */
    static Set<String> rareStems(String query, Map<String, Set<String>> profiles) {
        Set<String> rare = new LinkedHashSet<String>();
        for (String stem : stems(query)) {
            int cards = cardsWith(stem, profiles);
            if (cards > 0 && 2 * cards <= profiles.size()) {
                rare.add(stem);
            }
        }
        return rare;
    }

    /** The cards with the most weight of shared stems (a stem counts 1 / the number of cards that have it). */
    static List<String> cardsOf(String query, Map<String, Set<String>> profiles) {
        Set<String> rare = rareStems(query, profiles);
        double best = 0;
        List<String> top = new ArrayList<String>();
        for (Map.Entry<String, Set<String>> card : profiles.entrySet()) {
            double score = 0;
            for (String stem : rare) {
                if (card.getValue().contains(stem)) {
                    score += 1.0 / cardsWith(stem, profiles);
                }
            }
            if (score > best + 1e-9) {
                best = score;
                top.clear();
                top.add(card.getKey());
            } else if (score > 0 && Math.abs(score - best) <= 1e-9) {
                top.add(card.getKey());
            }
        }
        return top.isEmpty() ? new ArrayList<String>(profiles.keySet()) : top;
    }

    private static int cardsWith(String stem, Map<String, Set<String>> profiles) {
        int count = 0;
        for (Set<String> profile : profiles.values()) {
            if (profile.contains(stem)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------------------------------------- measuring

    private static JsonObject runJson(Outcome outcome, Settings settings, Measure sum) {
        JsonObject json = new JsonObject();
        json.addProperty("lauf", outcome.run);
        json.addProperty("ms", outcome.millis);
        json.addProperty("aufrufe", outcome.calls);
        json.addProperty("bisherigeSuchen", outcome.earlier.size());
        List<Set<String>> earlierWords = new ArrayList<Set<String>>();
        for (SearchRecord search : outcome.earlier) {
            earlierWords.add(words(search.getQuery()));
        }
        JsonArray tasks = new JsonArray();
        for (TaskPlan task : outcome.plan.getTasks()) {
            Measure measure = new Measure();
            measure.tasks = 1;
            measure.modelTasks = task.getStatus() == TaskPlan.Status.MODELL ? 1 : 0;
            measure.taken = task.getSearches().size();
            measure.proposals = measure.taken + task.getRejected().size();
            for (Rejection rejection : task.getRejected()) {
                measure.classes.merge(reasonClass(rejection.getReason()), 1, Integer::sum);
            }
            measure.wrongQuestion = measure.classes.getOrDefault("FRAGE", 0);
            measure.repeats = measure.classes.getOrDefault("ALT", 0) + measure.classes.getOrDefault("RUNDE", 0);
            SearchStrategy strategy = task.getTask().getStrategy();
            int shown = 0;
            for (PlannedSearch search : task.getSearches()) {
                Set<String> own = words(search.getQuery());
                double overlap = 0;
                for (Set<String> other : earlierWords) {
                    overlap = Math.max(overlap, jaccard(own, other));
                }
                if (overlap >= settings.nearOverlap) {
                    measure.near++;
                }
                if (strategy != SearchStrategy.STANDARD && shows(strategy, search, outcome, settings)) {
                    shown++;
                }
            }
            if (strategy != SearchStrategy.STANDARD) {
                measure.changed = 1;
                measure.changedShown = measure.taken > 0 && 2 * shown >= measure.taken ? 1 : 0;
            }
            JsonObject item = new JsonObject();
            item.addProperty("ziel", task.getTask().getTarget().name());
            item.addProperty("zielId", task.getTask().getTargetId());
            item.add("fragen", strings(task.getTask().getQuestionIds()));
            item.addProperty("strategie", strategy.wire());
            item.addProperty("vorgabe", !task.getTask().getInstruction().isEmpty());
            item.addProperty("status", task.getStatus().name());
            if (task.getStatus() == TaskPlan.Status.FEHLER) {
                item.addProperty("fehler", problemClass(task.getProblem()));
            }
            item.addProperty("antworten", task.getAnswers().size());
            item.addProperty("vorschlaege", measure.proposals);
            item.addProperty("genommen", measure.taken);
            JsonObject classes = new JsonObject();
            for (Map.Entry<String, Integer> entry : measure.classes.entrySet()) {
                classes.addProperty(entry.getKey(), entry.getValue());
            }
            item.add("verworfen", classes);
            Map<String, Integer> perQuestion = new TreeMap<String, Integer>();
            for (PlannedSearch search : task.getSearches()) {
                perQuestion.merge(search.getQuestionId(), 1, Integer::sum);
            }
            JsonObject questions = new JsonObject();
            for (Map.Entry<String, Integer> entry : perQuestion.entrySet()) {
                questions.addProperty(entry.getKey(), entry.getValue());
            }
            item.add("suchenJeFrage", questions);
            item.addProperty("nahWiederholungen", measure.near);
            if (strategy != SearchStrategy.STANDARD) {
                item.addProperty("strategieSichtbar", shown);
            }
            item.addProperty("ms", task.getMillis());
            tasks.add(item);
            sum.add(measure);
        }
        json.add("aufgaben", tasks);
        json.add("summe", measureJson(sum));
        return json;
    }

    private static JsonObject measureJson(Measure measure) {
        JsonObject json = new JsonObject();
        json.addProperty("aufgaben", measure.tasks);
        json.addProperty("mitSuche", measure.modelTasks);
        json.addProperty("vorschlaege", measure.proposals);
        json.addProperty("genommen", measure.taken);
        json.addProperty("fremdeFrage", measure.wrongQuestion);
        json.addProperty("wiederholungen", measure.repeats);
        json.addProperty("nahWiederholungen", measure.near);
        json.addProperty("geaenderteAufgaben", measure.changed);
        json.addProperty("strategieSichtbar", measure.changedShown);
        JsonObject classes = new JsonObject();
        for (Map.Entry<String, Integer> entry : measure.classes.entrySet()) {
            classes.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("verworfen", classes);
        return json;
    }

    /** P1 to P3 in every run (they count), P4 reported. */
    private static JsonObject criteria(Map<Integer, Measure> perRun, Settings settings, List<String> missed) {
        JsonObject json = new JsonObject();
        JsonObject p1 = criterion("mindestens " + percent(settings.p1Tasks) + " der Aufgaben bekommen eine Suche");
        JsonObject p2 = criterion("mindestens " + percent(settings.p2Named) + " der Vorschläge nennen eine Frage "
                + "ihrer Aufgabe, höchstens " + percent(settings.p2Repeats) + " wiederholen eine Suche");
        JsonObject p3 = criterion("höchstens " + percent(settings.p3Near) + " der genommenen Suchen teilen "
                + percent(settings.nearOverlap) + " ihrer Wörter mit einer früheren");
        JsonObject p4 = criterion("nur berichtet: Anteil der geänderten Aufgaben, bei denen mindestens die Hälfte der "
                + "Suchen die Strategie zeigt");
        boolean ok1 = true;
        boolean ok2 = true;
        boolean ok3 = true;
        for (Measure measure : perRun.values()) {
            double tasks = share(measure.modelTasks, measure.tasks);
            double named = share(measure.proposals - measure.wrongQuestion, measure.proposals);
            double repeats = measure.proposals == 0 ? 1 : share(measure.repeats, measure.proposals);
            double near = measure.taken == 0 ? 1 : share(measure.near, measure.taken);
            p1.getAsJsonArray("werte").add(round(tasks));
            p2.getAsJsonArray("werte").add(round(named));
            p2.getAsJsonArray("wiederholungen").add(round(repeats));
            p3.getAsJsonArray("werte").add(round(near));
            p4.getAsJsonArray("werte").add(round(share(measure.changedShown, measure.changed)));
            ok1 &= tasks >= settings.p1Tasks;
            ok2 &= named >= settings.p2Named && repeats <= settings.p2Repeats;
            ok3 &= near <= settings.p3Near;
        }
        verdict(p1, ok1, "P1", missed);
        verdict(p2, ok2, "P2", missed);
        verdict(p3, ok3, "P3", missed);
        p4.addProperty("erfuellt", true);
        json.add("P1", p1);
        json.add("P2", p2);
        json.add("P3", p3);
        json.add("P4", p4);
        return json;
    }

    private static JsonObject criterion(String rule) {
        JsonObject json = new JsonObject();
        json.addProperty("regel", rule);
        json.add("werte", new JsonArray());
        if (rule.contains("wiederholen")) {
            json.add("wiederholungen", new JsonArray());
        }
        return json;
    }

    private static void verdict(JsonObject criterion, boolean ok, String name, List<String> missed) {
        criterion.addProperty("erfuellt", ok);
        if (!ok) {
            missed.add(name);
        }
    }

    /** Whether a search shows the strategy of its task (only reported). */
    static boolean shows(SearchStrategy strategy, PlannedSearch search, Outcome outcome, Settings settings) {
        String query = search.getQuery();
        String folded = " " + fold(query) + " ";
        switch (strategy) {
            case ANDERE_SPRACHE:
                if (query.matches(".*[äöüÄÖÜß].*")) {
                    return false;
                }
                for (String word : folded.trim().split(" ")) {
                    if (ENGLISH.contains(word)) {
                        return true;
                    }
                }
                return false;
            case GEZIELTE_HERAUSGEBER:
                if (query.toLowerCase(Locale.ROOT).contains("site:")) {
                    return true;
                }
                List<String> names = new ArrayList<String>(PUBLISHERS);
                for (String publisher : ResearchRuntimeSettings.items(
                        ResearchRuntimeSettings.DEFAULT_SOURCE_PREFERENCE_PUBLISHERS)) {
                    int eq = publisher.indexOf('=');
                    names.add(fold(eq > 0 ? publisher.substring(0, eq) : publisher));
                }
                for (String name : names) {
                    if (!name.isEmpty() && folded.contains(" " + fold(name) + " ")) {
                        return true;
                    }
                }
                return false;
            case ANDERE_QUELLENART:
                for (String word : folded.trim().split(" ")) {
                    for (String kind : DOCUMENT_KINDS) {
                        if (word.startsWith(kind)) {
                            return true;
                        }
                    }
                }
                return false;
            case ANDERE_WORTWAHL:
                Set<String> own = words(query);
                for (SearchRecord earlier : outcome.earlier) {
                    if (earlier.getQuestionIds().contains(search.getQuestionId())
                            && jaccard(own, words(earlier.getQuery())) >= settings.wordingOverlap) {
                        return false;
                    }
                }
                return true;
            default:
                return true;
        }
    }

    /** The class of a reason the planner gave for not taking a search. */
    static String reasonClass(String reason) {
        if (reason.startsWith("nennt keine Frage")) {
            return "FRAGE";
        }
        if (reason.contains("einer anderen Aufgabe dieser Runde")) {
            return "RUNDE";
        }
        if (reason.startsWith("wiederholt die Suche")) {
            return "ALT";
        }
        if (reason.startsWith("doppelt")) {
            return "DOPPELT";
        }
        if (reason.startsWith("site:")) {
            return "SITE";
        }
        if (reason.endsWith("(Strategie gezielte_herausgeber)") || reason.endsWith("(Strategie andere_sprache)")
                || reason.endsWith("(Strategie andere_wortwahl)")) {
            return "STRATEGIE";
        }
        if (reason.startsWith("ohne Satz")) {
            return "ZWECK";
        }
        if (reason.startsWith("Länge") || reason.startsWith("kein Suchwort")) {
            return "FORM";
        }
        if (reason.startsWith("mehr als")) {
            return "ZU_VIELE";
        }
        if (reason.startsWith("über dem Suchbudget")) {
            return "BUDGET";
        }
        return "ANDERE";
    }

    /** Why a task got no search, without any text of the answers. */
    static String problemClass(String problem) {
        if (problem.startsWith("invalid answer")) {
            return "UNLESBAR";
        }
        if (problem.startsWith("keine brauchbare Suche")) {
            return "KEINE_BRAUCHBARE";
        }
        if (problem.startsWith("model call")) {
            return "MODELLFEHLER";
        }
        if (problem.startsWith("keine Suche übrig")) {
            return "NICHTS_UEBRIG";
        }
        return "ANDERE";
    }

    private static JsonObject settingsJson(Settings settings, Data data, SourcePreference preference) {
        JsonObject json = new JsonObject();
        json.add("lagen", strings(settings.states));
        json.addProperty("laeufe", settings.runs);
        json.addProperty("suchenJeAufgabe", settings.options.getSearchesPerTask());
        json.addProperty("gezeigteSuchen", settings.options.getShownSearches());
        json.addProperty("zitateJeFrage", settings.options.getQuotesPerQuestion());
        json.addProperty("parallel", settings.options.getParallel());
        json.addProperty("nahUeberlappung", settings.nearOverlap);
        json.addProperty("andereWortwahlUnter", settings.wordingOverlap);
        json.addProperty("karten", data.cards.size());
        json.addProperty("fragen", data.questions.size());
        json.addProperty("leitungsfragen", data.leadQuestions.size());
        json.addProperty("benannteThemen", data.topics.size());
        json.addProperty("suchenDesBestandstags", data.old.size());
        json.addProperty("belegeEcht", data.realPairs);
        json.addProperty("bevorzugteQuellen", preference.getTargets().size());
        return json;
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** The mission of the corpus. */
    static String readMission(Reader in) {
        JsonObject corpus = JsonParser.parseReader(in).getAsJsonObject();
        JsonElement mission = corpus.get("mission");
        if (mission == null || !mission.isJsonPrimitive() || mission.getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException("the corpus holds no mission");
        }
        return mission.getAsString().trim();
    }

    /** The cards ("themen") and the questions; the architect's "stand" is never read. */
    static void readQuestions(String json, Data data) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        for (Map.Entry<String, JsonElement> card : root.getAsJsonObject("themen").entrySet()) {
            data.cards.put("karte-" + card.getKey(), card.getValue().getAsString());
        }
        for (JsonElement element : root.getAsJsonArray("fragen")) {
            JsonObject question = element.getAsJsonObject();
            JsonElement answerable = question.get("beantwortbar");
            data.questions.add(new Question(string(question, "id"), "karte-" + string(question, "thema"),
                    string(question, "text"), answerable == null || answerable.getAsBoolean()));
        }
    }

    /** The named topics in their order as {@code thema-0001} ... with their expected fence verdict. */
    static void readTopics(String expected, Data data) {
        int number = 0;
        for (JsonElement element : JsonParser.parseString(expected).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject topic = element.getAsJsonObject();
            number++;
            data.topics.add(new Named(String.format("thema-%04d", number), string(topic, "name"),
                    ResearchTopic.Fence.valueOf(string(topic, "erwartet"))));
        }
    }

    /** The searches of the stock day ({@code suche}, {@code herkunft}, {@code zustand}, {@code passagen}). */
    static void readSearches(String json, Data data) {
        for (JsonElement element : JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("suchen")) {
            JsonObject search = element.getAsJsonObject();
            data.old.add(new OldSearch(string(search, "suche"), string(search, "herkunft"),
                    "completed".equals(string(search, "zustand")), number(search, "passagen"),
                    number(search, "quellen")));
        }
    }

    /** The architect's pairs, one JSON object per line ({@code frage}, {@code urteil}, {@code passage} ...). */
    static void readPairs(Reader in, Data data) throws IOException {
        BufferedReader lines = new BufferedReader(in);
        String line;
        while ((line = lines.readLine()) != null) {
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject pair = JsonParser.parseString(line).getAsJsonObject();
            data.pairs.add(new Pair(string(pair, "frage"), string(pair, "urteil"), string(pair, "passage"),
                    string(pair, "quelle"), string(pair, "text")));
        }
        data.realPairs = true;
    }

    // ------------------------------------------------------------------------------------------------- sample

    /** A small stock on city traffic, written for this test, with tasks of its own. */
    static Data sampleData() throws IOException {
        Data data = new Data();
        data.mission = readMission(new StringReader("{\"mission\":\"Ein Praxisbuch über Verkehr in Städten: Tempo, "
                + "Radwege und Parken. Bevorzugt werden Quellen von BASt und Difu.\"}"));
        readQuestions("{\"themen\":{\"1\":\"Tempo und Verkehrsberuhigung\",\"2\":\"Radverkehr\",\"3\":\"Parken\"},"
                + "\"fragen\":["
                + "{\"id\":\"F01\",\"thema\":\"1\",\"text\":\"Wie wirkt Tempo 30 auf die Zahl der Unfälle?\","
                + "\"stand\":\"beantwortet\",\"beantwortbar\":true},"
                + "{\"id\":\"F02\",\"thema\":\"1\",\"text\":\"Wer entscheidet über Tempo 30 auf Hauptstraßen?\","
                + "\"beantwortbar\":true},"
                + "{\"id\":\"F03\",\"thema\":\"2\",\"text\":\"Welche Radwege trennen Rad- und Autoverkehr sicher?\","
                + "\"beantwortbar\":true},"
                + "{\"id\":\"F04\",\"thema\":\"2\",\"text\":\"Was kostet ein Kilometer geschützter Radweg?\","
                + "\"beantwortbar\":false},"
                + "{\"id\":\"F05\",\"thema\":\"3\",\"text\":\"Wie viel kostet Anwohnerparken in deutschen Städten?\","
                + "\"beantwortbar\":true},"
                + "{\"id\":\"F06\",\"thema\":\"3\",\"text\":\"Wie wirkt der Wegfall von Parkplätzen auf den "
                + "Handel?\",\"beantwortbar\":false}]}", data);
        readTopics("{\"themen\":["
                + "{\"name\":\"Schulstraßen\",\"blaetter\":[\"T01\"],\"erwartet\":\"INNEN\"},"
                + "{\"name\":\"Lieferzonen\",\"blaetter\":[],\"erwartet\":\"INNEN\"},"
                + "{\"name\":\"Carsharing\",\"blaetter\":[],\"erwartet\":\"UNKLAR\"},"
                + "{\"name\":\"Seilbahnen im Nahverkehr\",\"blaetter\":[\"T02\"],\"erwartet\":\"AUSSEN\"}]}", data);
        readSearches("{\"suchen\":["
                + "{\"suche\":\"Tempo 30 Unfallzahlen Hauptstraßen\",\"herkunft\":\"base\",\"zustand\":\"completed\","
                + "\"passagen\":40,\"passagenZumThema\":12},"
                + "{\"suche\":\"Radwege Sicherheit Studie site:bast.de\",\"herkunft\":\"variant\","
                + "\"zustand\":\"completed\",\"passagen\":30,\"passagenZumThema\":5},"
                + "{\"suche\":\"Anwohnerparken Gebühren Vergleich\",\"herkunft\":\"worker\",\"zustand\":\"submitted\","
                + "\"passagen\":0,\"passagenZumThema\":0},"
                + "{\"suche\":\"Seilbahn Stadtverkehr\",\"herkunft\":\"worker\",\"zustand\":\"completed\","
                + "\"passagen\":12,\"passagenZumThema\":0}]}", data);
        StringBuilder pairs = new StringBuilder();
        String[][] rows = {
                {"F01", "BELEGT", "q1#p1", "q1", "In der Tempo-30-Zone sank die Zahl der schweren Unfälle um ein "
                        + "Viertel."},
                {"F01", "BELEGT", "q2#p1", "q2", "Nach der Einführung von Tempo 30 gab es weniger Verletzte im "
                        + "Stadtgebiet."},
                {"F02", "BELEGT", "q3#p1", "q3", "Über Tempo 30 auf Hauptstraßen entscheidet die "
                        + "Straßenverkehrsbehörde nach der StVO."},
                {"F03", "BELEGT", "q4#p1", "q4", "Hochbordradwege mit Bordstein trennen den Radverkehr sicher vom "
                        + "Autoverkehr."},
                {"F04", "HINWEIS", "q5#p1", "q5", "Geschützte Radwege brauchen Poller und Bordsteine, die Geld "
                        + "kosten."},
                {"F05", "BELEGT", "q6#p1", "q6", "Anwohnerparken kostet in vielen deutschen Städten heute bis zu "
                        + "120 Euro im Jahr."},
                {"F05", "KEIN", "q7#p1", "q7", "Parkhäuser sind am Wochenende oft leer und kosten wenig."}};
        for (String[] row : rows) {
            JsonObject pair = new JsonObject();
            pair.addProperty("frage", row[0]);
            pair.addProperty("urteil", row[1]);
            pair.addProperty("passage", row[2]);
            pair.addProperty("quelle", row[3]);
            pair.addProperty("text", row[4]);
            pairs.append(pair).append('\n');
        }
        readPairs(new StringReader(pairs.toString()), data);
        data.leadQuestions.add(new String[]{"L01", "@Schulstra", "Wie wirken Schulstraßen auf den Schulweg der "
                + "Kinder?"});
        data.leadQuestions.add(new String[]{"L02", "@Lieferzonen", "Wie viele Lieferzonen braucht eine Innenstadt?"});
        data.tasks.put("G1", new String[][]{
                {"FRAGE", "F01", "F01", "standard", ""},
                {"FRAGE", "F05", "F05", "standard", ""},
                {"THEMA", "karte-2", "F03,F04", "standard", ""}});
        data.tasks.put("G2", new String[][]{
                {"FRAGE", "F02", "F02", "standard", ""},
                {"THEMA", "karte-3", "F05,F06", "standard", ""}});
        data.tasks.put("G3", new String[][]{
                {"FRAGE", "F04", "F04", "andere_quellenart", "Gesucht sind Kostenberichte von Kommunen."},
                {"FRAGE", "F06", "F06", "andere_sprache", ""},
                {"THEMA", "@Schulstra", "L01", "standard", ""},
                {"THEMA", "@Lieferzonen", "L02", "standard", ""}});
        return data;
    }

    private static final Pattern QUESTION_LINE = Pattern.compile("(?m)^- ([A-Z]\\d+)\\b");
    private static final Pattern STRATEGY_LINE = Pattern.compile("(?m)^Strategie: ([a-z_]+)");
    private static final Pattern EARLIER_LINE = Pattern.compile("(?m)^- „(.+?)“ \\(");

    /** A planner that does what the criteria ask: two new searches for the first question, in its strategy. */
    static MainModelChatResult goodPlanner(List<ChatMessage> messages) {
        String prompt = messages.get(1).getContent();
        Matcher question = QUESTION_LINE.matcher(prompt);
        String id = question.find() ? question.group(1) : "F00";
        Matcher strategy = STRATEGY_LINE.matcher(prompt);
        String wire = strategy.find() ? strategy.group(1) : "standard";
        String first;
        String second;
        if (wire.equals("andere_quellenart")) {
            first = "Studie Kosten " + id + " Kommunen";
            second = "Leitfaden Kosten " + id + " Radverkehr";
        } else if (wire.equals("andere_sprache")) {
            first = "study of costs " + id + " in cities";
            second = "evaluation of retail effects " + id + " in cities";
        } else if (wire.equals("gezielte_herausgeber")) {
            first = "site:bast.de " + id + " Bericht";
            second = "Difu " + id + " Kommunen";
        } else if (wire.equals("andere_wortwahl")) {
            first = "Synonyme " + id + " Fachbegriffe";
            second = "Alltagssprache " + id + " Begriffe";
        } else {
            first = "Fallbeispiele " + id + " Stadtverkehr";
            second = "Zahlen " + id + " Kommunen Übersicht";
        }
        return MainModelChatResult.ok(SearchPlannerTest.answer(
                SearchPlannerTest.search(id, "Findet Zahlen zu " + id + " aus belastbaren Quellen.", first),
                SearchPlannerTest.search(id, "Findet Beispiele zu " + id + " aus Städten.", second)));
    }

    /** A planner that repeats the first earlier search it is shown and names a question of no task. */
    static MainModelChatResult repeatingPlanner(List<ChatMessage> messages) {
        String prompt = messages.get(1).getContent();
        Matcher question = QUESTION_LINE.matcher(prompt);
        String id = question.find() ? question.group(1) : "F00";
        Matcher earlier = EARLIER_LINE.matcher(prompt);
        String repeat = earlier.find() ? earlier.group(1) : "Erste Suche " + id + " Stadtverkehr";
        return MainModelChatResult.ok(SearchPlannerTest.answer(
                SearchPlannerTest.search(id, "Noch einmal dieselbe Suche.", repeat),
                SearchPlannerTest.search("F99", "Eine Suche für eine fremde Frage.", "fremde Frage " + id + " Suche")));
    }

    /** Counts the model calls. */
    static final class Counting implements MainModelChat {
        final MainModelChat model;
        final AtomicInteger calls = new AtomicInteger();

        Counting(MainModelChat model) {
            this.model = model;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.incrementAndGet();
            return model.complete(messages, temperature, maxOutputTokens);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            return model.completeJson(messages, temperature, maxOutputTokens, schemaJson);
        }

        @Override
        public String modelName() {
            return model.modelName();
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static JsonObject runOf(JsonObject result, String state) {
        return result.getAsJsonObject("lagen").getAsJsonObject(state).getAsJsonArray("laeufe").get(0)
                .getAsJsonObject();
    }

    /** Lower case, umlauts as two letters, accents off, every other sign a blank, blanks single. */
    static String fold(String text) {
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue").replace("ß", "ss");
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** The words of a search (folded, without {@code site:}, at least three letters, no stop word). */
    static Set<String> words(String query) {
        Set<String> words = new LinkedHashSet<String>();
        for (String word : fold(SITE.matcher(query == null ? "" : query).replaceAll(" ")).split(" ")) {
            if (word.length() >= 3 && !STOP.contains(word)) {
                words.add(word);
            }
        }
        return words;
    }

    /** The first five letters of every word of at least four letters that is no stop word and no number. */
    static Set<String> stems(String text) {
        Set<String> stems = new LinkedHashSet<String>();
        for (String word : fold(SITE.matcher(text == null ? "" : text).replaceAll(" ")).split(" ")) {
            if (word.length() >= 4 && !STOP.contains(word) && !word.matches("\\d+")) {
                stems.add(word.substring(0, Math.min(5, word.length())));
            }
        }
        return stems;
    }

    static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1;
        }
        Set<String> union = new HashSet<String>(a);
        union.addAll(b);
        Set<String> common = new HashSet<String>(a);
        common.retainAll(b);
        return (double) common.size() / union.size();
    }

    private static List<String> contentWords(String text) {
        List<String> words = new ArrayList<String>();
        for (String word : text.replaceAll("[^\\p{L}\\p{N}-]+", " ").trim().split(" ")) {
            if (word.length() >= 4 && !STOP.contains(fold(word)) && words.size() < 6) {
                words.add(word);
            }
        }
        return words;
    }

    /** The first sentence of 40 to 240 characters, else the first 200 characters cut at a blank. */
    static String quote(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        for (int i = 40; i < Math.min(flat.length(), 240); i++) {
            char c = flat.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == flat.length() || flat.charAt(i + 1) == ' ')) {
                return flat.substring(0, i + 1);
            }
        }
        return cut(flat, 200);
    }

    static String cut(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        int blank = text.lastIndexOf(' ', max);
        return text.substring(0, blank > max / 2 ? blank : max).trim();
    }

    private static double share(int part, int whole) {
        return whole == 0 ? 0 : (double) part / whole;
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    private static String percent(double value) {
        return Math.round(value * 100) + " %";
    }

    private static String text(String property, String resource) throws IOException {
        String path = System.getProperty(P + property, "");
        if (!path.isEmpty()) {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        }
        try (InputStream in = SearchPlannerStockTest.class.getResourceAsStream(RESOURCES + resource)) {
            if (in == null) {
                throw new IOException("missing test resource " + RESOURCES + resource);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, n);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static int number(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() ? 0 : value.getAsInt();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static void write(File file, JsonObject json) throws IOException {
        Files.write(file.toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void deleteTree(File folder) throws IOException {
        if (!folder.exists()) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder.toPath())) {
            List<Path> all = new ArrayList<Path>();
            paths.forEach(all::add);
            all.sort(Comparator.reverseOrder());
            for (Path path : all) {
                Files.delete(path);
            }
        }
    }
}
