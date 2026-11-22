package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.lead.LeadSituation.QuestionView;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchMemoryStore;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SearchRecord;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Component 8 on stored data: the research lead with gemma4:12b (thinking on) on five fixed situations built from the
 * stock of 17.11.2026: its mission, the four cards and 20 questions of its concept (fragen-1711.json), quotes from the
 * architect's evidence pairs (belegpaare-1711.jsonl, only on the owner's machine) and the 17 named topics
 * (erwartete-themen-1711.json) as topics the topic finder found, with sample passages of their sheets as findings.
 *
 * <ul>
 *   <li>{@code S1} start: no search yet.</li>
 *   <li>{@code S2} fruitless: round 1 searched every question once with the standard strategy; the 16 questions the
 *       stock can answer found up to two direct pieces of evidence (pairs BELEGT), every question up to one lead
 *       (HINWEIS); round 2 searched the four the stock cannot answer (beantwortbar false: F07, F16, F17, F20) once
 *       more with the standard strategy, again without new evidence. The model is not told which ones those are.</li>
 *   <li>{@code S3} new topics: round 1 as in S2, and the topic finder found the 17 named topics (8 INNEN without
 *       questions, 4 UNKLAR, 5 AUSSEN).</li>
 *   <li>{@code S4} budget used up: S3 with the first of the four set aside and a budget of as many searches as
 *       ran.</li>
 *   <li>{@code S5} standstill: S2 and two more rounds in which the four were searched with other words and another
 *       kind of source, without new evidence.</li>
 * </ul>
 *
 * <p>Proposed criteria, fixed by the architect before the run counts (S1 to S3 in every run): L1 the model plans the
 * start itself: status MODELL, it does not want to end, at least 3 of the round's tasks come from it, no decision is
 * dropped for a target outside the situation. L2 a question without yield leads to a change, not to a repetition: no
 * task searches one of the four with a strategy that already stayed without yield; the model decides on at least half
 * of the four, none of its decisions on them repeats a fruitless strategy, and at least half of its decisions on them
 * change to a strategy not tried (setting aside counts as no repetition). L3 new topics lead to new questions: new
 * questions for at least 3 of the 8 INNEN topics, none for an AUSSEN or UNKLAR topic. L4 a used-up budget ends without
 * a model call with the open list: every open question, the one set aside, the 4 UNKLAR topics for the author, the
 * text says the research is not complete and never "beantwortet". L5 a standstill ends without a model call with
 * the open list. {@code result.json} holds ids, counts, actions and strategies; no quote, no passage text, no question
 * text and no reason of the model (those stay in {@code projekt/<state>-<run>/} on the machine: the memory and
 * {@code research-lead/log.jsonl} with the raw answers).</p>
 *
 * <pre>
 *   ./gradlew :research-agent-ui-plugin:componentStockTest --tests '*ResearchLeadStockTest*'
 *     -Daskai.stock.lead.korpus=&lt;replay-corpus.json&gt; -Daskai.stock.lead.out=&lt;folder&gt;
 *     -Daskai.stock.lead.belegpaare=&lt;belegpaare-1711.jsonl&gt;
 *     -Daskai.stock.lead.chatUrl=http://127.0.0.1:11434 -Daskai.stock.lead.chatModel=gemma4:12b
 *   optional: rand (text file with the edge of the subject; the out folder gets -rand), lagen (S1,S2,S3,S4,S5),
 *   laeufe (2, runs of S1 to S3), think (true; false, or omit to leave it out), numCtx (16384, the context of the
 *   lead's role; omit to leave it to the server), timeoutSeconds (600), antwortTokens (10240), fragen, erwartet,
 *   themen, stichworte (files instead of the copies in the test resources)
 * </pre>
 */
public class ResearchLeadStockTest {

    private static final String P = "askai.stock.lead.";
    private static final String RESOURCES = "/research-lead-stock/";
    private static final long T = 1_000L;
    static final List<String> STATES = Arrays.asList("S1", "S2", "S3", "S4", "S5");
    private static final List<String> MODEL_STATES = Arrays.asList("S1", "S2", "S3");
    private static final Set<String> STOP = new HashSet<String>(Arrays.asList("welche", "welcher", "welches",
            "wie", "was", "wann", "woran", "wo", "gibt", "es", "die", "der", "das", "den", "dem", "des", "und", "oder",
            "von", "für", "mit", "auf", "bei", "aus", "etwa", "eine", "einer", "einem", "einen", "ein", "im", "in",
            "ist", "sind", "werden", "wird", "lässt", "sich", "man", "zu", "zur", "zum", "nach"));

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
        final List<String> keywords;
        final List<ResearchTopic.Finding> findings;

        Named(String id, String name, ResearchTopic.Fence fence, List<String> keywords,
              List<ResearchTopic.Finding> findings) {
            this.id = id;
            this.name = name;
            this.fence = fence;
            this.keywords = keywords;
            this.findings = findings;
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

    /** What the situations are built from. */
    static final class Data {
        String mission = "";
        String edge = "";
        final Map<String, String> cards = new LinkedHashMap<String, String>();
        final List<Question> questions = new ArrayList<Question>();
        final List<Named> topics = new ArrayList<Named>();
        final List<Pair> pairs = new ArrayList<Pair>();
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
                            + " für den Bestandstest der Rechercheleitung."));
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
    }

    /** Proposed thresholds and how the run works. */
    static final class Settings {
        ResearchLead.Options options = ResearchLead.Options.defaults();
        ResearchLead.Budget budget = ResearchLead.Budget.defaults();
        List<String> states = STATES;
        int runs = 2;
        int l1ModelTasks = 3;
        int l3InsideTopics = 3;
    }

    /** One plan of one state with what the lead saw before it. */
    static final class Outcome {
        final String state;
        final int run;
        final LeadSituation before;
        final LeadPlan plan;
        final ResearchLead.Answer answer;
        final int calls;
        final long millis;
        final ResearchMemory memory;

        Outcome(String state, int run, LeadSituation before, LeadPlan plan, int calls, long millis,
                ResearchMemory memory) {
            this.state = state;
            this.run = run;
            this.before = before;
            this.plan = plan;
            this.calls = calls;
            this.millis = millis;
            this.memory = memory;
            List<String> answers = plan.getAnswers();
            this.answer = plan.getStatus() == LeadPlan.Status.MODELL && !answers.isEmpty()
                    ? ResearchLead.parse(answers.get(answers.size() - 1)) : null;
        }
    }

    // ------------------------------------------------------------------------------------------------- tests

    @Test
    public void sampleStock() throws Exception {
        Data data = sampleData();
        assertEquals(2, data.unanswerable().size());
        assertEquals("the passages that hold a key word of the topic", 1, data.topics.get(0).findings.size());
        assertEquals(0, data.topics.get(1).findings.size());
        Settings settings = new Settings();
        settings.runs = 1;
        ResearchLeadTest.Scripted chat = new ResearchLeadTest.Scripted(ResearchLeadStockTest::goodLead);
        File out = tmp.newFolder("out");

        JsonObject result = run(data, chat, settings, out, new JsonObject());

        JsonObject criteria = result.getAsJsonObject("kriterien");
        for (String name : new String[]{"L1", "L2", "L3", "L4", "L5"}) {
            assertTrue(name + " " + criteria.get(name), criteria.getAsJsonObject(name).get("erfuellt").getAsBoolean());
        }
        assertTrue(result.get("befund").getAsString(), result.get("bestanden").getAsBoolean());
        assertEquals("only S1 to S3 ask the model", 3, chat.calls.get());
        JsonObject s2 = result.getAsJsonObject("lagen").getAsJsonObject("S2").getAsJsonArray("laeufe").get(0)
                .getAsJsonObject();
        assertEquals("[\"F04\",\"F06\"]", s2.getAsJsonArray("ohneErtrag").toString());
        assertEquals(2, s2.get("wechsel").getAsInt());
        assertEquals(0, s2.get("wiederholungInAufgaben").getAsInt());
        JsonObject s3 = result.getAsJsonObject("lagen").getAsJsonObject("S3").getAsJsonArray("laeufe").get(0)
                .getAsJsonObject();
        assertEquals(2, s3.get("innenMitNeuenFragen").getAsInt());
        assertEquals(3, s3.get("neueFragen").getAsInt());
        JsonObject s4 = result.getAsJsonObject("lagen").getAsJsonObject("S4").getAsJsonArray("laeufe").get(0)
                .getAsJsonObject();
        assertEquals(5, s4.getAsJsonObject("offeneListe").get("offen").getAsInt());
        assertEquals(1, s4.getAsJsonObject("offeneListe").get("zurueckgestellt").getAsInt());
        assertEquals(1, s4.getAsJsonObject("offeneListe").get("autor").getAsInt());

        String written = new String(Files.readAllBytes(new File(out, "result.json").toPath()), StandardCharsets.UTF_8);
        for (String text : new String[]{"Synthetische Fundstelle", "Tempo 30 auf die Zahl", "morgens für Autos",
                "Grund der Leitung", "Wie viele Schulstraßen"}) {
            assertFalse("no " + text + " in result.json", written.contains(text));
        }
        List<JsonObject> rounds = new ResearchLeadLog(new File(out, "projekt/S3-1")).open();
        assertEquals(1, rounds.size());
        assertEquals("the raw answer stays on the machine", 1, rounds.get(0).getAsJsonArray("rohantworten").size());
        ResearchMemory stored = new ResearchMemoryStore(new File(out, "projekt/S3-1")).open();
        assertEquals("6 questions of the concept and 3 taken in", 9, stored.questions().size());
    }

    @Test
    public void aLeadThatRepeatsAndEndsFailsTheCriteriaWhileTheGuardrailsHold() throws Exception {
        Data data = sampleData();
        Settings settings = new Settings();
        settings.runs = 1;
        settings.states = Arrays.asList("S1", "S2", "S3");
        ResearchLeadTest.Scripted chat = new ResearchLeadTest.Scripted(messages -> MainModelChatResult.ok(
                ResearchLeadTest.answer("beenden", "Es ist genug.",
                        ResearchLeadTest.decision("F04", "weiter_verfolgen", "standard", "noch einmal"),
                        ResearchLeadTest.decision("F06", "strategie_wechseln", "standard", "noch einmal"),
                        ResearchLeadTest.decision("F09", "weiter_verfolgen", "standard", "gibt es nicht"))));
        File out = tmp.newFolder("schlecht");

        JsonObject result = run(data, chat, settings, out, new JsonObject());

        JsonObject criteria = result.getAsJsonObject("kriterien");
        assertFalse("the model wants to end at the start", criteria.getAsJsonObject("L1").get("erfuellt")
                .getAsBoolean());
        assertFalse("two repetitions", criteria.getAsJsonObject("L2").get("erfuellt").getAsBoolean());
        assertFalse("no new questions", criteria.getAsJsonObject("L3").get("erfuellt").getAsBoolean());
        JsonObject s2 = result.getAsJsonObject("lagen").getAsJsonObject("S2").getAsJsonArray("laeufe").get(0)
                .getAsJsonObject();
        assertEquals(2, s2.get("wiederholung").getAsInt());
        assertEquals("the guardrails changed the strategy anyway", 0, s2.get("wiederholungInAufgaben").getAsInt());
        assertTrue(s2.get("modellWollteEnden").getAsBoolean());
        assertFalse("the guardrail keeps the research going", s2.get("ende").getAsBoolean());
        assertEquals(1, s2.get("verworfen").getAsInt());
        assertFalse(result.get("bestanden").getAsBoolean());
    }

    @Test
    public void storedStock() throws Exception {
        String corpusPath = System.getProperty(P + "korpus", "");
        Assume.assumeTrue("research lead stock test skipped (no -D" + P + "korpus)", !corpusPath.isEmpty());
        String chatUrl = System.getProperty(P + "chatUrl", "http://127.0.0.1:11434");
        String chatModel = System.getProperty(P + "chatModel", "gemma4:12b");
        String numCtxSetting = System.getProperty(P + "numCtx", String.valueOf(ResearchLead.CONTEXT_TOKENS));
        String numCtx = "omit".equalsIgnoreCase(numCtxSetting) ? "" : numCtxSetting.trim();
        String thinkSetting = System.getProperty(P + "think", "true");
        Boolean think = "omit".equalsIgnoreCase(thinkSetting) ? null : Boolean.valueOf(!"false".equalsIgnoreCase(
                thinkSetting));
        int timeout = 1000 * Integer.getInteger(P + "timeoutSeconds", 600);
        String edgePath = System.getProperty(P + "rand", "");
        String pairsPath = System.getProperty(P + "belegpaare", "");
        File out = new File(System.getProperty(P + "out", "build/component-stock/research-lead"),
                chatModel.replaceAll("[^A-Za-z0-9._-]", "_") + (edgePath.isEmpty() ? "" : "-rand"));
        Files.createDirectories(out.toPath());

        Settings settings = new Settings();
        settings.options = ResearchLead.Options.defaults().withAnswerTokens(Integer.getInteger(P + "antwortTokens",
                ResearchLead.Options.defaults().getAnswerTokens()));
        settings.runs = Integer.getInteger(P + "laeufe", 2);
        settings.states = Arrays.asList(System.getProperty(P + "lagen", String.join(",", STATES)).split("\\s*,\\s*"));

        Data data = new Data();
        Map<String, String[]> passages;
        try (Reader in = new InputStreamReader(new FileInputStream(corpusPath), StandardCharsets.UTF_8)) {
            passages = readCorpus(in, data);
        }
        data.edge = edgePath.isEmpty() ? "" : new String(Files.readAllBytes(new File(edgePath).toPath()),
                StandardCharsets.UTF_8).trim();
        readQuestions(text("fragen", "fragen-1711.json"), data);
        readTopics(text("erwartet", "erwartete-themen-1711.json"), text("stichworte", "stichworte-1711.json"),
                text("themen", "themen-1711.json"), passages, data);
        if (!pairsPath.isEmpty()) {
            try (Reader in = new InputStreamReader(new FileInputStream(pairsPath), StandardCharsets.UTF_8)) {
                readPairs(in, data);
            }
        } else {
            System.out.println("[research-lead] no -D" + P + "belegpaare: the quotes of the evidence are made up");
        }

        JsonObject sources = new JsonObject();
        sources.addProperty("korpus", corpusPath);
        sources.addProperty("auftragZeichen", data.mission.length());
        sources.addProperty("rand", edgePath.isEmpty() ? "keiner" : edgePath + " (" + data.edge.length()
                + " Zeichen)");
        sources.addProperty("belegpaare", pairsPath.isEmpty() ? "keine (Belege synthetisch)" : pairsPath + " ("
                + data.pairs.size() + " Paare)");
        sources.addProperty("chat", chatModel + " at " + chatUrl + (numCtx.isEmpty() ? "" : ", num_ctx " + numCtx)
                + (think == null ? ", think weggelassen" : ", think:" + think) + ", Zeitlimit " + timeout / 1000
                + " s");
        System.out.println("[research-lead] states " + settings.states + ", " + settings.runs + " runs of S1 to S3, "
                + "chat " + chatModel + (data.edge.isEmpty() ? "" : ", with the edge"));

        OllamaStockChat chat = new OllamaStockChat(chatUrl, chatModel, numCtx.isEmpty() ? null
                : Integer.valueOf(numCtx), think, timeout);
        JsonObject result = run(data, chat, settings, out, sources);
        JsonObject usage = new JsonObject();
        usage.addProperty("aufrufe", chat.calls.get());
        usage.addProperty("fehlgeschlagen", chat.failures.get());
        usage.addProperty("tokens", chat.evalTokens.get());
        usage.addProperty("denkZeichen", chat.thinkingChars.get());
        result.add("modell", usage);
        write(new File(out, "result.json"), result);
        System.out.println("[research-lead] result " + new File(out, "result.json").getAbsolutePath());
        assertTrue(result.get("befund").getAsString(), result.get("bestanden").getAsBoolean());
    }

    // ------------------------------------------------------------------------------------------------- run

    /** Every state (S1 to S3 {@code runs} times) on a memory of its own, the log, {@code result.json}. */
    static JsonObject run(Data data, MainModelChat model, Settings settings, File out, JsonObject sources)
            throws Exception {
        File projects = new File(out, "projekt");
        deleteTree(projects);
        Counting chat = new Counting(model);
        JsonObject json = new JsonObject();
        json.addProperty("geschrieben", LocalDateTime.now().withNano(0).toString());
        json.add("quellen", sources);
        json.add("einstellungen", settingsJson(settings, data));
        JsonObject states = new JsonObject();
        JsonObject criteria = new JsonObject();
        List<String> missed = new ArrayList<String>();
        long start = System.currentTimeMillis();
        for (String state : settings.states) {
            if (!STATES.contains(state)) {
                throw new IllegalArgumentException("unknown state " + state + " (known: " + STATES + ")");
            }
            int runs = MODEL_STATES.contains(state) ? Math.max(1, settings.runs) : 1;
            List<Outcome> outcomes = new ArrayList<Outcome>();
            for (int run = 1; run <= runs; run++) {
                File project = new File(projects, state + "-" + run);
                ResearchMemory memory = new ResearchMemoryStore(project).open();
                build(state, memory, data);
                ResearchLeadLog log = new ResearchLeadLog(project);
                log.open();
                LeadSituation before = LeadSituation.of(memory, settings.options.getRecentSearches(),
                        settings.options.getQuoteChars());
                ResearchLead lead = new ResearchLead(chat, "de", settings.options, budget(state, settings, data),
                        line -> System.out.println("[research-lead] " + line));
                int calls = chat.calls.get();
                long began = System.currentTimeMillis();
                LeadPlan plan = lead.plan(memory, data.mission, data.edge);
                long millis = System.currentTimeMillis() - began;
                log.append(plan, System.currentTimeMillis());
                Outcome outcome = new Outcome(state, run, before, plan, chat.calls.get() - calls, millis, memory);
                outcomes.add(outcome);
                System.out.println("[research-lead] " + state + " run " + run + ": " + plan.getStatus()
                        + (plan.isEnd() ? ", end" : ", " + plan.getTasks().size() + " tasks, "
                        + plan.getNewQuestions().size() + " new questions") + ", " + millis + " ms");
            }
            states.add(state, stateJson(outcomes, data, settings));
            criterion(state, outcomes, data, settings, criteria, missed);
        }
        json.add("lagen", states);
        JsonObject time = new JsonObject();
        time.addProperty("ms", System.currentTimeMillis() - start);
        time.addProperty("aufrufe", chat.calls.get());
        json.add("zeit", time);
        json.add("kriterien", criteria);
        json.addProperty("bestanden", missed.isEmpty());
        json.addProperty("befund", missed.isEmpty() ? "alle Kriterien der gelaufenen Lagen erfüllt"
                : "nicht erfüllt: " + String.join(", ", missed));
        write(new File(out, "result.json"), json);
        return json;
    }

    private static ResearchLead.Budget budget(String state, Settings settings, Data data) {
        ResearchLead.Budget budget = settings.budget;
        if (state.equals("S4")) {
            return new ResearchLead.Budget(budget.getMaxRounds(), data.questions.size(), budget.getTasksPerRound(),
                    budget.getSearchesPerTask());
        }
        return budget;
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
        if (state.equals("S1")) {
            return;
        }
        boolean topics = state.equals("S3") || state.equals("S4");
        roundOne(memory, data, topics);
        if (topics) {
            if (state.equals("S4") && !data.unanswerable().isEmpty()) {
                Question first = data.unanswerable().get(0);
                memory.updateQuestion(memory.currentQuestion(first.id).withState(
                        ResearchQuestion.State.ZURUECKGESTELLT, T + 30));
                memory.record(new LeadDecision(1, LeadDecision.Target.FRAGE, first.id,
                        LeadDecision.Action.ZURUECKSTELLEN, "zurückgestellt (Testlage)", null, "", T + 30));
            }
            return;
        }
        for (Question question : data.unanswerable()) {
            search(memory, "r2-" + question.id, question, SearchStrategy.STANDARD, 2, 0, 0);
        }
        if (state.equals("S5")) {
            for (Question question : data.unanswerable()) {
                search(memory, "r3-" + question.id, question, SearchStrategy.ANDERE_WORTWAHL, 3, 0, 0);
                search(memory, "r4-" + question.id, question, SearchStrategy.ANDERE_QUELLENART, 4, 0, 0);
            }
        }
    }

    /**
     * Round 1: every question searched once with the standard strategy; the answerable ones found up to two direct
     * pieces of evidence, every question up to one lead; with {@code topics} the named topics were found too.
     */
    private static void roundOne(ResearchMemory memory, Data data, boolean topics) throws IOException {
        int topicsLeft = topics ? data.topics.size() : 0;
        for (Question question : data.questions) {
            if (question.answerable) {
                for (Pair pair : data.pairs(question.id, "BELEGT", 2)) {
                    verdict(memory, pair, EvidenceVerdict.Kind.BELEGT);
                }
            }
            for (Pair pair : data.pairs(question.id, "HINWEIS", 1)) {
                verdict(memory, pair, EvidenceVerdict.Kind.HINWEIS);
            }
            int newTopics = topicsLeft > 0 ? 1 : 0;
            topicsLeft -= newTopics;
            search(memory, "r1-" + question.id, question, SearchStrategy.STANDARD, 1,
                    memory.evidenceOf(question.id).getDirectCount(), newTopics);
        }
        if (topics) {
            for (Named topic : data.topics) {
                memory.addTopic(ResearchTopic.found(topic.id, topic.name, "", topic.findings, "themenfinder", T + 20)
                        .withFence(topic.fence, "erwartetes Urteil (Testlage)", "zaunpruefung", T + 21));
            }
        }
    }

    private static void verdict(ResearchMemory memory, Pair pair, EvidenceVerdict.Kind kind) throws IOException {
        memory.record(EvidenceVerdict.judged(pair.question, pair.passage, pair.source, "", pair.text, kind,
                quote(pair.text), "Urteil des Architekten (Testlage)", "referenz", 1, T + 1));
    }

    private static void search(ResearchMemory memory, String id, Question question, SearchStrategy strategy,
                               int round, int newEvidence, int newTopics) throws IOException {
        SearchRecord search = new SearchRecord(id, query(question, strategy, round), Collections.singletonList(
                question.id), null, strategy.wire(), "PLANER", round, null, T + round * 10L);
        memory.record(search);
        memory.record(search.withYield(new SearchRecord.Yield(4, 12, newEvidence, newTopics), T + round * 10L + 5));
    }

    /** A query a search planner could have written: the question first, then its content words with a twist. */
    static String query(Question question, SearchStrategy strategy, int round) {
        String words = String.join(" ", contentWords(question.text));
        if (strategy == SearchStrategy.STANDARD) {
            return round == 1 ? LeadSituation.cut(question.text.replace("?", "").trim(), 100) : words;
        }
        if (strategy == SearchStrategy.ANDERE_WORTWAHL) {
            return words + " Erfahrungsbericht";
        }
        if (strategy == SearchStrategy.ANDERE_QUELLENART) {
            return words + " Leitfaden filetype:pdf";
        }
        return words;
    }

    private static List<String> contentWords(String text) {
        List<String> words = new ArrayList<String>();
        for (String word : text.replaceAll("[^\\p{L}\\p{N}-]+", " ").trim().split(" ")) {
            if (word.length() >= 4 && !STOP.contains(word.toLowerCase(Locale.ROOT)) && words.size() < 6) {
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
        String cut = LeadSituation.cut(flat, 200);
        return cut.endsWith(" …") ? cut.substring(0, cut.length() - 2) : cut;
    }

    // ------------------------------------------------------------------------------------------------- reading

    /** The mission into {@code data}; every passage as id → {text, source}. */
    static Map<String, String[]> readCorpus(Reader in, Data data) {
        JsonObject corpus = JsonParser.parseReader(in).getAsJsonObject();
        JsonElement mission = corpus.get("mission");
        if (mission == null || !mission.isJsonPrimitive() || mission.getAsString().trim().isEmpty()) {
            throw new IllegalArgumentException("the corpus holds no mission");
        }
        data.mission = mission.getAsString().trim();
        Map<String, String[]> passages = new HashMap<String, String[]>();
        JsonArray list = corpus.getAsJsonArray("passages");
        for (JsonElement element : list == null ? new JsonArray() : list) {
            JsonObject passage = element.getAsJsonObject();
            passages.put(string(passage, "id"), new String[]{string(passage, "text"), string(passage, "sourceId")});
        }
        return passages;
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

    /**
     * The named topics in their order as {@code thema-0001} ...; the findings are the sample passages of their sheets
     * that hold one of their key words, at most three.
     */
    static void readTopics(String expected, String keywords, String sheets, Map<String, String[]> passages,
                           Data data) {
        JsonObject words = JsonParser.parseString(keywords).getAsJsonObject().getAsJsonObject("themen");
        Map<String, List<String>> samples = new HashMap<String, List<String>>();
        for (JsonElement element : JsonParser.parseString(sheets).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject sheet = element.getAsJsonObject();
            List<String> ids = new ArrayList<String>();
            for (JsonElement id : sheet.getAsJsonArray("stichprobe")) {
                ids.add(id.getAsString());
            }
            samples.put(string(sheet, "blatt"), ids);
        }
        int number = 0;
        for (JsonElement element : JsonParser.parseString(expected).getAsJsonObject().getAsJsonArray("themen")) {
            JsonObject topic = element.getAsJsonObject();
            String name = string(topic, "name");
            List<String> keys = new ArrayList<String>();
            JsonArray list = words.has(name) ? words.getAsJsonArray(name) : new JsonArray();
            for (JsonElement key : list) {
                keys.add(key.getAsString());
            }
            List<ResearchTopic.Finding> findings = new ArrayList<ResearchTopic.Finding>();
            for (JsonElement sheet : topic.getAsJsonArray("blaetter")) {
                for (String id : samples.getOrDefault(sheet.getAsString(), Collections.<String>emptyList())) {
                    String[] passage = passages.get(id);
                    if (passage != null && findings.size() < 3 && hitsAny(passage[0], keys)) {
                        findings.add(new ResearchTopic.Finding(passage[1], id, passage[0]));
                    }
                }
            }
            number++;
            data.topics.add(new Named(String.format("thema-%04d", number), name,
                    ResearchTopic.Fence.valueOf(string(topic, "erwartet")), keys, findings));
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

    // ------------------------------------------------------------------------------------------------- measuring

    private static JsonObject stateJson(List<Outcome> outcomes, Data data, Settings settings) {
        JsonObject json = new JsonObject();
        JsonArray runs = new JsonArray();
        for (Outcome outcome : outcomes) {
            runs.add(runJson(outcome, data, settings));
        }
        json.add("laeufe", runs);
        return json;
    }

    private static JsonObject runJson(Outcome outcome, Data data, Settings settings) {
        LeadPlan plan = outcome.plan;
        JsonObject json = new JsonObject();
        json.addProperty("lauf", outcome.run);
        json.addProperty("runde", plan.getRound());
        json.addProperty("status", plan.getStatus().name());
        json.addProperty("ende", plan.isEnd());
        json.addProperty("modellWollteEnden", outcome.answer != null && outcome.answer.end);
        json.addProperty("aufrufe", outcome.calls);
        json.addProperty("ms", outcome.millis);
        JsonArray tasks = new JsonArray();
        int fromModel = 0;
        for (LeadTask task : plan.getTasks()) {
            JsonObject item = new JsonObject();
            item.addProperty("ziel", task.getTarget().name());
            item.addProperty("zielId", task.getTargetId());
            item.add("fragen", strings(task.getQuestionIds()));
            item.addProperty("strategie", task.getStrategy().wire());
            item.addProperty("herkunft", task.getOrigin().name());
            tasks.add(item);
            fromModel += task.getOrigin() == LeadTask.Origin.MODELL ? 1 : 0;
        }
        json.add("aufgaben", tasks);
        json.addProperty("aufgabenVomModell", fromModel);
        JsonArray decisions = new JsonArray();
        if (outcome.answer != null) {
            for (ResearchLead.Decision decision : outcome.answer.decisions) {
                JsonObject item = new JsonObject();
                item.addProperty("ziel", decision.target);
                item.addProperty("handlung", decision.action);
                item.addProperty("strategie", decision.strategy);
                decisions.add(item);
            }
        }
        json.add("entscheidungenDesModells", decisions);
        JsonObject asked = new JsonObject();
        if (outcome.answer != null) {
            for (Map.Entry<String, ResearchLead.Asked> entry : outcome.answer.asked.entrySet()) {
                asked.addProperty(entry.getKey(), entry.getValue().questions.size());
            }
        }
        json.add("neueFragenDesModells", asked);
        int wrongTopic = 0;
        for (String note : plan.getNotes()) {
            wrongTopic += note.startsWith("Neue Fragen nur zu einem Thema innerhalb des Weidezauns") ? 1 : 0;
        }
        json.addProperty("neueFragenFalschesThema", wrongTopic);
        json.addProperty("leitplanken", plan.getNotes().size());
        json.addProperty("verworfen", dropped(plan));
        json.addProperty("wiederholungInAufgaben", repeatsInTasks(outcome, settings));
        Map<String, Integer> taken = new TreeMap<String, Integer>();
        for (ResearchQuestion question : plan.getNewQuestions()) {
            taken.merge(question.getTopicId(), 1, Integer::sum);
        }
        JsonObject newQuestions = new JsonObject();
        for (Map.Entry<String, Integer> entry : taken.entrySet()) {
            newQuestions.addProperty(entry.getKey(), entry.getValue());
        }
        json.add("neueFragenJeThema", newQuestions);
        json.addProperty("neueFragen", plan.getNewQuestions().size());
        if (outcome.state.equals("S2") || outcome.state.equals("S5")) {
            List<String> ids = new ArrayList<String>();
            for (Question question : data.unanswerable()) {
                ids.add(question.id);
            }
            json.add("ohneErtrag", strings(ids));
        }
        if (outcome.state.equals("S2")) {
            int[] classes = fruitless(outcome, data);
            json.addProperty("entschieden", classes[0]);
            json.addProperty("wechsel", classes[1]);
            json.addProperty("zurueckgestellt", classes[2]);
            json.addProperty("wiederholung", classes[3]);
        }
        if (outcome.state.equals("S3")) {
            int[] topics = newTopicQuestions(outcome, data);
            json.addProperty("innenMitNeuenFragen", topics[0]);
            json.addProperty("innenThemen", topics[1]);
            json.addProperty("neueFragenAussenOderUnklar", topics[2]);
            json.addProperty("neueFragenZuKarten", topics[3]);
            json.addProperty("neueFragenMitStichwort", topics[4]);
        }
        if (plan.getOpenList() != null) {
            OpenList open = plan.getOpenList();
            JsonObject list = new JsonObject();
            list.addProperty("offen", open.getOpen().size());
            list.addProperty("zurueckgestellt", open.getSetAside().size());
            list.addProperty("autor", open.getForTheAuthor().size());
            list.addProperty("nichtVollstaendig", open.text(true).contains("nicht vollständig"));
            list.addProperty("ohneBeantwortet", !open.text(true).toLowerCase(Locale.ROOT).contains("beantwortet"));
            list.addProperty("grundIstStillstand", open.getReason().startsWith("Stillstand"));
            list.addProperty("grundIstBudget", open.getReason().startsWith("Budget verbraucht"));
            json.add("offeneListe", list);
        }
        return json;
    }

    /** Decisions dropped because their target is not in the situation. */
    private static int dropped(LeadPlan plan) {
        int dropped = 0;
        for (String note : plan.getNotes()) {
            dropped += note.contains("steht nicht im Lagebild") ? 1 : 0;
        }
        return dropped;
    }

    /** Questions in the round's tasks that get a strategy which already stayed without yield for them. */
    static int repeatsInTasks(Outcome outcome, Settings settings) {
        Map<String, QuestionView> views = new HashMap<String, QuestionView>();
        for (QuestionView view : outcome.before.questions) {
            views.put(view.id(), view);
        }
        int repeats = 0;
        for (LeadTask task : outcome.plan.getTasks()) {
            for (String id : task.getQuestionIds()) {
                QuestionView view = views.get(id);
                if (view != null && !ResearchLead.allowed(view, task.getStrategy(),
                        settings.options.getFruitlessLimit())) {
                    repeats++;
                }
            }
        }
        return repeats;
    }

    /**
     * The model's own decisions on the questions without yield: {decided, changed to a strategy not tried, set
     * aside, repeated (on with the strategy so far, or a "change" to standard or a strategy already without yield)}.
     */
    static int[] fruitless(Outcome outcome, Data data) {
        int[] classes = new int[4];
        if (outcome.answer == null) {
            return classes;
        }
        Map<String, QuestionView> views = new HashMap<String, QuestionView>();
        for (QuestionView view : outcome.before.questions) {
            views.put(view.id(), view);
        }
        Set<String> ids = new HashSet<String>();
        for (Question question : data.unanswerable()) {
            ids.add(question.id);
        }
        Set<String> seen = new HashSet<String>();
        for (ResearchLead.Decision decision : outcome.answer.decisions) {
            QuestionView view = views.get(decision.target);
            if (!ids.contains(decision.target) || view == null || !seen.add(decision.target)) {
                continue;
            }
            classes[0]++;
            SearchStrategy asked = SearchStrategy.of(decision.strategy);
            if (ResearchLead.ZURUECKSTELLEN.equals(decision.action)) {
                classes[2]++;
            } else if (ResearchLead.WECHSELN.equals(decision.action) && asked != null
                    && asked != SearchStrategy.STANDARD && asked != view.current && !view.tried.contains(asked)) {
                classes[1]++;
            } else if (ResearchLead.WECHSELN.equals(decision.action) || ResearchLead.WEITER.equals(decision.action)) {
                classes[3]++;
            }
        }
        return classes;
    }

    /**
     * New questions of S3: {INNEN topics with new questions, INNEN topics, new questions for AUSSEN or UNKLAR topics,
     * new questions for cards, new questions holding a key word of their topic}.
     */
    static int[] newTopicQuestions(Outcome outcome, Data data) {
        Map<String, Named> topics = new HashMap<String, Named>();
        int inside = 0;
        for (Named topic : data.topics) {
            topics.put(topic.id, topic);
            inside += topic.fence == ResearchTopic.Fence.INNEN ? 1 : 0;
        }
        Set<String> insideWithNew = new HashSet<String>();
        int outsideOrUnclear = 0;
        int cards = 0;
        int keyword = 0;
        for (ResearchQuestion question : outcome.plan.getNewQuestions()) {
            Named topic = topics.get(question.getTopicId());
            if (topic == null) {
                cards += data.cards.containsKey(question.getTopicId()) ? 1 : 0;
                continue;
            }
            if (topic.fence == ResearchTopic.Fence.INNEN) {
                insideWithNew.add(topic.id);
            } else {
                outsideOrUnclear++;
            }
            keyword += hitsAny(question.getText(), topic.keywords) ? 1 : 0;
        }
        return new int[]{insideWithNew.size(), inside, outsideOrUnclear, cards, keyword};
    }

    private static void criterion(String state, List<Outcome> outcomes, Data data, Settings settings,
                                  JsonObject criteria, List<String> missed) {
        boolean ok = true;
        List<String> parts = new ArrayList<String>();
        String name;
        String text;
        switch (state) {
            case "S1":
                name = "L1";
                text = "Start: Status MODELL, kein Ende gewollt, mindestens " + settings.l1ModelTasks
                        + " Aufgaben vom Modell, nichts verworfen";
                for (Outcome outcome : outcomes) {
                    int fromModel = 0;
                    for (LeadTask task : outcome.plan.getTasks()) {
                        fromModel += task.getOrigin() == LeadTask.Origin.MODELL ? 1 : 0;
                    }
                    boolean run = outcome.answer != null && !outcome.answer.end && !outcome.plan.isEnd()
                            && fromModel >= Math.min(settings.l1ModelTasks, settings.budget.getTasksPerRound())
                            && dropped(outcome.plan) == 0;
                    ok &= run;
                    parts.add("Lauf " + outcome.run + ": " + outcome.plan.getStatus() + ", " + fromModel + " von "
                            + outcome.plan.getTasks().size() + " Aufgaben vom Modell" + (outcome.answer != null
                            && outcome.answer.end ? ", wollte enden" : "") + ", verworfen " + dropped(outcome.plan));
                }
                break;
            case "S2":
                name = "L2";
                int four = data.unanswerable().size();
                text = "Strategiewechsel: keine Aufgabe wiederholt eine Strategie ohne Ertrag; das Modell entscheidet "
                        + "über mindestens die Hälfte der " + four + " Fragen ohne Ertrag, wiederholt keine und "
                        + "wechselt bei mindestens der Hälfte seiner Entscheidungen zu einer nicht versuchten";
                for (Outcome outcome : outcomes) {
                    int[] classes = fruitless(outcome, data);
                    int repeats = repeatsInTasks(outcome, settings);
                    boolean run = outcome.answer != null && repeats == 0 && classes[0] * 2 >= four
                            && classes[3] == 0 && classes[1] * 2 >= classes[0];
                    ok &= run;
                    parts.add("Lauf " + outcome.run + ": " + outcome.plan.getStatus() + ", entschieden " + classes[0]
                            + ", Wechsel " + classes[1] + ", zurückgestellt " + classes[2] + ", Wiederholung "
                            + classes[3] + ", Wiederholung in Aufgaben " + repeats + (outcome.answer != null
                            && outcome.answer.end ? ", wollte enden" : ""));
                }
                break;
            case "S3":
                name = "L3";
                text = "Neue Themen: neue Fragen zu mindestens " + settings.l3InsideTopics + " INNEN-Themen, keine zu "
                        + "AUSSEN oder UNKLAR";
                for (Outcome outcome : outcomes) {
                    int[] topics = newTopicQuestions(outcome, data);
                    boolean run = outcome.answer != null && topics[0] >= Math.min(settings.l3InsideTopics, topics[1])
                            && topics[2] == 0;
                    ok &= run;
                    parts.add("Lauf " + outcome.run + ": " + outcome.plan.getStatus() + ", INNEN mit neuen Fragen "
                            + topics[0] + " von " + topics[1] + ", zu AUSSEN/UNKLAR " + topics[2] + ", zu Karten "
                            + topics[3]);
                }
                break;
            case "S4":
                name = "L4";
                text = "Budget: Ende ohne Modellaufruf mit offener Liste (alle offenen Fragen, die zurückgestellte, "
                        + "die UNKLAR-Themen), nicht vollständig, nie beantwortet";
                for (Outcome outcome : outcomes) {
                    OpenList open = outcome.plan.getOpenList();
                    int unclear = 0;
                    for (Named topic : data.topics) {
                        unclear += topic.fence == ResearchTopic.Fence.UNKLAR ? 1 : 0;
                    }
                    boolean aside = !data.unanswerable().isEmpty();
                    boolean run = outcome.plan.getStatus() == LeadPlan.Status.REGEL && outcome.plan.isEnd()
                            && outcome.calls == 0 && open != null && open.getReason().startsWith("Budget verbraucht")
                            && open.getOpen().size() == data.questions.size() - (aside ? 1 : 0)
                            && open.getSetAside().size() == (aside ? 1 : 0) && open.getForTheAuthor().size() == unclear
                            && open.text(true).contains("nicht vollständig")
                            && !open.text(true).toLowerCase(Locale.ROOT).contains("beantwortet");
                    ok &= run;
                    parts.add(outcome.plan.getStatus() + (outcome.plan.isEnd() ? ", Ende" : ", kein Ende")
                            + ", Aufrufe " + outcome.calls + (open == null ? ", keine Liste" : ", offen "
                            + open.getOpen().size() + ", zurückgestellt " + open.getSetAside().size() + ", Autor "
                            + open.getForTheAuthor().size()));
                }
                break;
            default:
                name = "L5";
                text = "Stillstand: Ende ohne Modellaufruf mit offener Liste";
                for (Outcome outcome : outcomes) {
                    OpenList open = outcome.plan.getOpenList();
                    boolean run = outcome.plan.getStatus() == LeadPlan.Status.REGEL && outcome.plan.isEnd()
                            && outcome.calls == 0 && open != null && open.getReason().startsWith("Stillstand")
                            && open.getOpen().size() == data.questions.size();
                    ok &= run;
                    parts.add(outcome.plan.getStatus() + (outcome.plan.isEnd() ? ", Ende" : ", kein Ende")
                            + ", Aufrufe " + outcome.calls + (open == null ? ", keine Liste" : ", offen "
                            + open.getOpen().size()));
                }
        }
        JsonObject item = new JsonObject();
        item.addProperty("kriterium", text);
        item.addProperty("wert", String.join("; ", parts));
        item.addProperty("erfuellt", ok);
        criteria.add(name, item);
        if (!ok) {
            missed.add(name);
        }
    }

    private static JsonObject settingsJson(Settings settings, Data data) {
        JsonObject json = new JsonObject();
        json.addProperty("lagen", String.join(",", settings.states));
        json.addProperty("laeufe", settings.runs);
        json.addProperty("fragenGezeigt", settings.options.getQuestionsShown());
        json.addProperty("themenGezeigt", settings.options.getTopicsShown());
        json.addProperty("zitatZeichen", settings.options.getQuoteChars());
        json.addProperty("ohneErtragGrenze", settings.options.getFruitlessLimit());
        json.addProperty("stillstandRunden", settings.options.getStagnationRounds());
        json.addProperty("neueFragenJeThema", settings.options.getNewQuestionsPerTopic());
        json.addProperty("antwortTokens", settings.options.getAnswerTokens());
        json.addProperty("budget", settings.budget.getMaxRounds() + " Runden, " + settings.budget.getMaxSearches()
                + " Suchen, " + settings.budget.getTasksPerRound() + " Aufgaben je Runde");
        json.addProperty("fragen", data.questions.size());
        json.addProperty("karten", data.cards.size());
        json.addProperty("themen", data.topics.size());
        json.addProperty("belege", data.realPairs ? "belegpaare" : "synthetisch");
        json.addProperty("rand", !data.edge.isEmpty());
        return json;
    }

    // ------------------------------------------------------------------------------------------------- sample

    /** Road traffic, written for this test: 3 cards, 6 questions (2 the stock cannot answer), 4 named topics. */
    static Data sampleData() throws IOException {
        Data data = new Data();
        Map<String, String[]> passages = readCorpus(new StringReader("{\"mission\":\"Ein Praxisbuch über Verkehr in "
                + "Städten: Tempo, Radwege und Parken.\",\"passages\":["
                + "{\"id\":\"s1#p1\",\"sourceId\":\"s1\",\"text\":\"Die Schulstraße vor der Grundschule ist morgens "
                + "für Autos gesperrt.\"},"
                + "{\"id\":\"s1#p2\",\"sourceId\":\"s1\",\"text\":\"Eltern bringen ihre Kinder mit dem Rad.\"},"
                + "{\"id\":\"s2#p1\",\"sourceId\":\"s2\",\"text\":\"Seilbahnen schweben über dem Fluss.\"}]}"), data);
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
                        + "{\"name\":\"Seilbahnen im Nahverkehr\",\"blaetter\":[\"T02\"],\"erwartet\":\"AUSSEN\"}]}",
                "{\"themen\":{\"Schulstraßen\":[\"schulstrasse\"],\"Lieferzonen\":[\"lieferzone\",\"ladezone\"],"
                        + "\"Carsharing\":[\"carsharing\"],\"Seilbahnen im Nahverkehr\":[\"seilbahn\"]}}",
                "{\"themen\":[{\"blatt\":\"T01\",\"stichprobe\":[\"s1#p1\",\"s1#p2\",\"fehlt#p9\"]},"
                        + "{\"blatt\":\"T02\",\"stichprobe\":[\"s2#p1\"]}]}", passages, data);
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
        return data;
    }

    /** A lead that does what the criteria ask: it starts, changes the strategy and asks about new topics. */
    static MainModelChatResult goodLead(List<ChatMessage> messages) {
        String prompt = messages.get(1).getContent();
        List<String> decisions = new ArrayList<String>();
        String[] asked = new String[0];
        if (prompt.contains("(noch ohne Fragen)")) {
            asked = new String[]{ResearchLeadTest.questionsFor("thema-0001", "Erfahrungen der Städte",
                    "Wie viele Schulstraßen gibt es in deutschen Städten?", "Wer ordnet eine Schulstraße an?"),
                    ResearchLeadTest.questionsFor("thema-0002", "Regeln der Kommunen",
                            "Wie lange dürfen Lieferwagen in einer Lieferzone halten?")};
        } else if (prompt.contains("in Folge ohne neuen Beleg")) {
            decisions.add(ResearchLeadTest.decision("F04", "strategie_wechseln", "andere_quellenart",
                    "Haushaltspläne der Städte"));
            decisions.add(ResearchLeadTest.decision("F06", "strategie_wechseln", "gezielte_herausgeber",
                    "Handelsverband und IHK"));
        } else {
            for (String id : new String[]{"F01", "F02", "F03"}) {
                decisions.add(ResearchLeadTest.decision(id, "weiter_verfolgen", "standard", "Studien und Ämter"));
            }
        }
        return MainModelChatResult.ok(ResearchLeadTest.answerAsking("weiter", "Grund der Leitung: es gibt noch zu "
                + "tun.", asked, decisions.toArray(new String[0])));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    /** Counts the model calls of the run. */
    static final class Counting implements MainModelChat {
        final MainModelChat chat;
        final AtomicInteger calls = new AtomicInteger();

        Counting(MainModelChat chat) {
            this.chat = chat;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            calls.incrementAndGet();
            return chat.complete(messages, temperature, maxOutputTokens);
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            calls.incrementAndGet();
            return chat.completeJson(messages, temperature, maxOutputTokens, schemaJson);
        }

        @Override
        public String modelName() {
            return chat.modelName();
        }
    }

    /** Lower case, umlauts as two letters, accents off, every other sign a blank, blanks single. */
    static String fold(String text) {
        String lower = (text == null ? "" : text).toLowerCase(Locale.ROOT).replace("ä", "ae").replace("ö", "oe")
                .replace("ü", "ue").replace("ß", "ss");
        String plain = Normalizer.normalize(lower, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** {@code =word} whole word, {@code ^word} at a word start, anything else anywhere (as the topic finder's test). */
    static boolean hitsAny(String text, List<String> keywords) {
        String folded = " " + fold(text) + " ";
        for (String keyword : keywords) {
            boolean hit = keyword.startsWith("=") ? folded.contains(" " + fold(keyword.substring(1)) + " ")
                    : keyword.startsWith("^") ? folded.contains(" " + fold(keyword.substring(1)))
                    : folded.contains(fold(keyword));
            if (hit) {
                return true;
            }
        }
        return false;
    }

    private static String text(String property, String resource) throws IOException {
        String path = System.getProperty(P + property, "");
        if (!path.isEmpty()) {
            return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
        }
        try (InputStream in = ResearchLeadStockTest.class.getResourceAsStream(RESOURCES + resource)) {
            if (in == null) {
                throw new IOException("missing test resource " + RESOURCES + resource);
            }
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
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
