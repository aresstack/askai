package com.aresstack.askai.research.lead;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.lead.LeadSituation.QuestionView;
import com.aresstack.askai.research.lead.LeadSituation.TopicView;
import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.LeadDecision.Action;
import com.aresstack.askai.research.memory.LeadDecision.Target;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SearchRecord;
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

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Component 8 of the build plan of 21.11.2026, the research lead (Rechercheleitung): after each round it judges the
 * situation and decides the next step. One call of gemma4:12b per round, with thinking, sees the counts of the memory
 * and single quotes, never the whole stock, and decides per question or topic: follow it on, change the strategy
 * (other words, another kind of source, named publishers, another language), take in new questions for a topic, set a
 * question aside; and for the run: go on or end.
 *
 * <p>Rules are only guardrails: the budget of rounds and searches, no search of a question with a strategy that
 * already brought nothing, not even within a topic's task ({@code fruitlessLimit} searches without new evidence force
 * a change, and a strategy that stayed without yield is no change), an end when several rounds brought nothing new,
 * new questions only for topics
 * inside the fence (a card or a found topic with the verdict INNEN), never on AUSSEN or UNKLAR topics, no question set
 * aside before it was searched once, and no end by the model while the last round still brought something, a
 * question was never searched or a question without direct evidence still has a strategy it has not tried (it may set
 * such a question aside first). A free slot goes to a question never searched. Every correction is a note of the plan
 * and part of the recorded reason.</p>
 *
 * <p>"Beenden" never means "everything answered": the memory only shows what was found (component 7), and the plan of
 * an end carries the {@link OpenList} for the author, who releases the sources. A failed call (after one repair)
 * leaves only the guardrails for that round, and that is noted. Every decision goes to the memory
 * ({@link LeadDecision} with strategy and instruction), the questions taken in too (origin LEITUNG).</p>
 *
 * <p>Checked for reuse: {@code ResearchOrchestrator} and {@code RoundDecision} (runtime, ADR 0001) plan work packages
 * and close a run on a measured sufficiency of sources per section; the build plan forbids such a verdict, so neither
 * is used here.</p>
 */
public final class ResearchLead {

    /** Tuning; immutable. */
    public static final class Options {
        private final int questionsShown;
        private final int topicsShown;
        private final int unclearShown;
        private final int recentSearches;
        private final int quoteChars;
        private final int fruitlessLimit;
        private final int stagnationRounds;
        private final int newQuestionsPerTopic;
        private final int newQuestionsPerRound;
        private final int answerTokens;

        private Options(int questionsShown, int topicsShown, int unclearShown, int recentSearches, int quoteChars,
                        int fruitlessLimit, int stagnationRounds, int newQuestionsPerTopic, int newQuestionsPerRound,
                        int answerTokens) {
            if (questionsShown < 1 || topicsShown < 0 || unclearShown < 0 || recentSearches < 0 || quoteChars < 0
                    || fruitlessLimit < 1 || stagnationRounds < 1 || newQuestionsPerTopic < 1
                    || newQuestionsPerRound < 0 || answerTokens < 256) {
                throw new IllegalArgumentException("invalid options of the research lead");
            }
            this.questionsShown = questionsShown;
            this.topicsShown = topicsShown;
            this.unclearShown = unclearShown;
            this.recentSearches = recentSearches;
            this.quoteChars = quoteChars;
            this.fruitlessLimit = fruitlessLimit;
            this.stagnationRounds = stagnationRounds;
            this.newQuestionsPerTopic = newQuestionsPerTopic;
            this.newQuestionsPerRound = newQuestionsPerRound;
            this.answerTokens = answerTokens;
        }

        /**
         * 40 questions, 15 found topics and 10 topics of the author shown, the last 12 searches, quotes of up to 200
         * characters; a change after 2 searches without new evidence, an end after 3 rounds with nothing new; up to 3
         * new questions per topic and 20 per round; 16,384 answer tokens (Ollama counts the thinking in).
         */
        public static Options defaults() {
            return new Options(40, 15, 10, 12, 200, 2, 3, 3, 20, 16384);
        }

        public Options withShown(int questions, int topics, int unclear) {
            return new Options(questions, topics, unclear, recentSearches, quoteChars, fruitlessLimit,
                    stagnationRounds, newQuestionsPerTopic, newQuestionsPerRound, answerTokens);
        }

        public Options withRecentSearches(int count) {
            return new Options(questionsShown, topicsShown, unclearShown, count, quoteChars, fruitlessLimit,
                    stagnationRounds, newQuestionsPerTopic, newQuestionsPerRound, answerTokens);
        }

        public Options withQuoteChars(int chars) {
            return new Options(questionsShown, topicsShown, unclearShown, recentSearches, chars, fruitlessLimit,
                    stagnationRounds, newQuestionsPerTopic, newQuestionsPerRound, answerTokens);
        }

        /** Searches without new evidence after which a strategy must change; rounds with nothing new until the end. */
        public Options withLimits(int fruitless, int stagnation) {
            return new Options(questionsShown, topicsShown, unclearShown, recentSearches, quoteChars, fruitless,
                    stagnation, newQuestionsPerTopic, newQuestionsPerRound, answerTokens);
        }

        public Options withNewQuestions(int perTopic, int perRound) {
            return new Options(questionsShown, topicsShown, unclearShown, recentSearches, quoteChars, fruitlessLimit,
                    stagnationRounds, perTopic, perRound, answerTokens);
        }

        public Options withAnswerTokens(int tokens) {
            return new Options(questionsShown, topicsShown, unclearShown, recentSearches, quoteChars, fruitlessLimit,
                    stagnationRounds, newQuestionsPerTopic, newQuestionsPerRound, tokens);
        }

        public int getQuestionsShown() {
            return questionsShown;
        }

        public int getTopicsShown() {
            return topicsShown;
        }

        public int getUnclearShown() {
            return unclearShown;
        }

        public int getRecentSearches() {
            return recentSearches;
        }

        public int getQuoteChars() {
            return quoteChars;
        }

        public int getFruitlessLimit() {
            return fruitlessLimit;
        }

        public int getStagnationRounds() {
            return stagnationRounds;
        }

        public int getNewQuestionsPerTopic() {
            return newQuestionsPerTopic;
        }

        public int getNewQuestionsPerRound() {
            return newQuestionsPerRound;
        }

        public int getAnswerTokens() {
            return answerTokens;
        }
    }

    /** The budget of a research run: rounds, searches, and how much one round may take. Immutable. */
    public static final class Budget {
        private final int maxRounds;
        private final int maxSearches;
        private final int tasksPerRound;
        private final int searchesPerTask;

        public Budget(int maxRounds, int maxSearches, int tasksPerRound, int searchesPerTask) {
            if (maxRounds < 1 || maxSearches < 1 || tasksPerRound < 1 || searchesPerTask < 1) {
                throw new IllegalArgumentException("a budget needs at least one round, search and task");
            }
            this.maxRounds = maxRounds;
            this.maxSearches = maxSearches;
            this.tasksPerRound = tasksPerRound;
            this.searchesPerTask = searchesPerTask;
        }

        /** 30 short rounds, 120 searches, 4 tasks per round of up to 3 searches each. */
        public static Budget defaults() {
            return new Budget(30, 120, 4, 3);
        }

        public int getMaxRounds() {
            return maxRounds;
        }

        public int getMaxSearches() {
            return maxSearches;
        }

        public int getTasksPerRound() {
            return tasksPerRound;
        }

        public int getSearchesPerTask() {
            return searchesPerTask;
        }
    }

    /** The role: its own slot under Sources, a schema per request (the targets of the round), thinking on. */
    public static final ResearchRole ROLE = ResearchRole.builder("research-lead",
                    ResearchModelSlots.PHASE_RESEARCH_LEAD)
            .purpose("component 8: judges the situation after each round of the Sources phase and decides the next "
                    + "step per question or topic, and whether the research goes on")
            .promptFromCaller()
            .output(RoleOutput.schemaPerRequest("lage, entscheidungen (grund, ziel, handlung, strategie, vorgabe, "
                    + "fragen), lauf (grund, handlung)"))
            .thinking(ThinkingMode.ON)
            .build();

    static final double TEMPERATURE = 0.0;
    /** Calls per round: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    static final String WEITER = "weiter_verfolgen";
    static final String WECHSELN = "strategie_wechseln";
    static final String NEUE_FRAGEN = "neue_fragen";
    static final String ZURUECKSTELLEN = "zurueckstellen";
    private static final List<String> ACTIONS = Collections.unmodifiableList(java.util.Arrays.asList(WEITER, WECHSELN,
            NEUE_FRAGEN, ZURUECKSTELLEN));
    private static final int MISSION_CHARS = 2500;
    private static final int TEXT_CHARS = 300;
    private static final int REASON_CHARS = 500;
    private static final int SITUATION_CHARS = 1500;
    private static final int MIN_QUESTION_CHARS = 10;
    private static final int QUERY_CHARS = 120;

    static final String SYSTEM_DE = "Du leitest die Recherche für ein Sachbuch. Nach jeder Runde beurteilst du die "
            + "Lage und entscheidest den nächsten Schritt. Du suchst nicht selbst und schreibst keine Suchanfragen: "
            + "Ein Suchplaner macht aus deinen Vorgaben die Suchen. Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You lead the research for a non-fiction book. After each round you judge the "
            + "situation and decide the next step. You do not search yourself and write no search queries: a search "
            + "planner turns your instructions into searches. Answer only with JSON.";

    private final MainModelChat chat;
    private final boolean german;
    private final Options options;
    private final Budget budget;
    private final Consumer<String> log;
    private final Clock clock;

    public ResearchLead(MainModelChat chat, String language, Options options, Budget budget, Consumer<String> log) {
        this(chat, language, options, budget, log, Clock.systemUTC());
    }

    ResearchLead(MainModelChat chat, String language, Options options, Budget budget, Consumer<String> log,
                 Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the research lead needs its model");
        }
        this.chat = chat;
        this.german = language == null || !language.trim().toLowerCase(Locale.ROOT).startsWith("en");
        this.options = options == null ? Options.defaults() : options;
        this.budget = budget == null ? Budget.defaults() : budget;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Plans the next round on the memory as it stands: first the rules that end without a model (budget, standstill,
     * nothing left), then one model call, then the guardrails. Every decision and every question taken in is written
     * to the memory before the plan is returned.
     *
     * @param mission the book mission
     * @param edge    the edge of the subject (decision E12), {@code ""} when there is none
     */
    public LeadPlan plan(ResearchMemory memory, String mission, String edge) throws IOException {
        long start = clock.millis();
        LeadSituation situation = LeadSituation.of(memory, options.recentSearches, options.quoteChars);
        String end = endRule(situation);
        if (end != null) {
            Round round = new Round(memory, situation, "");
            round.end(end, "");
            return round.plan(LeadPlan.Status.REGEL, "", "", Collections.<String>emptyList(), start);
        }
        Shown shown = shown(situation);
        String prompt = prompt(situation, shown, mission, edge, german, options, budget, slots(situation));
        Call call = ask(prompt, schema(shown.ids(), options.newQuestionsPerTopic, slots(situation)), shown.ids());
        if (!call.ok()) {
            log("research lead: the call failed in round " + situation.round + ": " + call.problem);
            Round round = new Round(memory, situation, "");
            round.notes.add(say("Modellaufruf gescheitert (" + call.problem + "); diese Runde planen nur die "
                    + "Leitplanken", "The model call failed (" + call.problem + "); only the guardrails plan this "
                    + "round"));
            round.fill(true);
            if (round.tasks.isEmpty()) {
                round.end(say("Keine Aufgabe mehr möglich, und die Leitung war nicht erreichbar",
                        "No task left, and the lead could not be reached"), "");
            } else {
                round.go(say("Leitplanke: Modellaufruf gescheitert, nur Leitplanken in dieser Runde",
                        "Guardrail: the model call failed, only guardrails this round"), "");
            }
            return round.plan(LeadPlan.Status.FEHLER, "", call.problem, call.answers, start);
        }
        Round round = new Round(memory, situation, chat.modelName());
        round.take(call.answer, shown);
        return round.plan(LeadPlan.Status.MODELL, call.answer.situation, "", call.answers, start);
    }

    // ------------------------------------------------------------------------------------------------- rules

    /** Why the research ends before any model call, or {@code null}. */
    String endRule(LeadSituation situation) {
        if (situation.round > budget.maxRounds) {
            return say("Budget verbraucht: alle " + budget.maxRounds + " Runden sind gelaufen",
                    "Budget used up: all " + budget.maxRounds + " rounds have run");
        }
        if (situation.searchesDone >= budget.maxSearches) {
            return say("Budget verbraucht: " + situation.searchesDone + " von " + budget.maxSearches + " Suchen",
                    "Budget used up: " + situation.searchesDone + " of " + budget.maxSearches + " searches");
        }
        if (situation.quietRounds >= options.stagnationRounds) {
            return say("Stillstand: die letzten " + situation.quietRounds + " Runden mit Suchen brachten keinen neuen "
                    + "Beleg und kein neues Thema", "Standstill: the last " + situation.quietRounds + " rounds with "
                    + "searches brought no new evidence and no new topic");
        }
        if (situation.questions.isEmpty() && freshTopics(situation) == 0) {
            return say("Keine offene Frage und kein Thema ohne Fragen mehr; den Rest entscheidet der Autor",
                    "No open question and no topic without questions left; the author decides the rest");
        }
        return null;
    }

    /** Why the model may not end the research now, or {@code null} when it may. */
    String endVeto(LeadSituation situation, int questionsTaken) {
        if (situation.lastNewEvidence > 0 || situation.lastNewTopics > 0) {
            return say("die letzte Runde brachte noch " + situation.lastNewEvidence + " neue Belege und "
                    + situation.lastNewTopics + " neue Themen", "the last round still brought "
                    + situation.lastNewEvidence + " new pieces of evidence and " + situation.lastNewTopics
                    + " new topics");
        }
        if (situation.neverSearched > 0) {
            return say(situation.neverSearched + " offene Fragen wurden noch nie gesucht",
                    situation.neverSearched + " open questions were never searched");
        }
        List<String> untried = new ArrayList<String>();
        for (QuestionView view : situation.questions) {
            if (view.evidence.getDirectCount() == 0 && untried(view) != null) {
                untried.add(view.id());
            }
        }
        if (!untried.isEmpty()) {
            String ids = String.join(", ", untried.subList(0, Math.min(5, untried.size())))
                    + (untried.size() > 5 ? " …" : "");
            return say(untried.size() + " offene Fragen ohne direkten Beleg haben noch eine Suchstrategie, die nicht "
                    + "versucht ist (" + ids + ")", untried.size() + " open questions without direct evidence still "
                    + "have a search strategy not tried (" + ids + ")");
        }
        if (questionsTaken > 0) {
            return say("die Leitung hat in dieser Runde neue Fragen aufgenommen",
                    "the lead took in new questions this round");
        }
        int fresh = freshTopics(situation);
        if (fresh > 0) {
            return say(fresh + " Themen innerhalb des Weidezauns haben noch keine Fragen",
                    fresh + " topics inside the fence have no questions yet");
        }
        return null;
    }

    /** The tasks this round can take: the tasks per round, and never more than searches are left. */
    int slots(LeadSituation situation) {
        return Math.max(1, Math.min(budget.tasksPerRound, budget.maxSearches - situation.searchesDone));
    }

    /**
     * The strategy a question gets: the one asked for when it is a real change, else the first one not tried since
     * the last new evidence; {@code null} as strategy means nothing is left and the question is set aside.
     */
    static Choice choose(QuestionView question, boolean change, SearchStrategy asked, int fruitlessLimit,
                         boolean german) {
        if (!change) {
            if (question.fruitlessWithCurrent < fruitlessLimit) {
                return new Choice(question.current, "");
            }
            SearchStrategy next = untried(question);
            return new Choice(next, question.fruitlessWithCurrent + (german ? " Suchen ohne neuen Beleg mit "
                    : " searches without new evidence with ") + question.current.wire() + rest(next, german));
        }
        if (asked != null && asked != SearchStrategy.STANDARD && asked != question.current
                && !question.tried.contains(asked)) {
            return new Choice(asked, "");
        }
        SearchStrategy next = untried(question);
        String why = asked == null || asked == SearchStrategy.STANDARD
                ? (german ? "kein Wechsel genannt" : "no change named")
                : asked == question.current ? asked.wire() + (german ? " ist die bisherige Strategie"
                : " is the strategy so far") : asked.wire() + (german ? " blieb schon ohne neuen Beleg"
                : " already stayed without new evidence");
        return new Choice(next, why + rest(next, german));
    }

    /**
     * Whether a question may be searched with a strategy: with its current one while that brought nothing fewer than
     * {@code fruitlessLimit} times, with any other one it has not tried without yield since its last new evidence.
     */
    static boolean allowed(QuestionView question, SearchStrategy strategy, int fruitlessLimit) {
        return strategy == question.current ? question.fruitlessWithCurrent < fruitlessLimit
                : !question.tried.contains(strategy);
    }

    private static String rest(SearchStrategy next, boolean german) {
        if (next == null) {
            return german ? ", alle Strategien versucht, die Frage wird zurückgestellt"
                    : ", every strategy tried, the question is set aside";
        }
        return (german ? ", Wechsel zu " : ", change to ") + next.wire();
    }

    private static SearchStrategy untried(QuestionView question) {
        for (SearchStrategy strategy : SearchStrategy.CHANGE_ORDER) {
            if (strategy != question.current && !question.tried.contains(strategy)) {
                return strategy;
            }
        }
        return null;
    }

    /** A strategy for a question with the guardrail's note ({@code ""} when the model's choice stands). */
    static final class Choice {
        final SearchStrategy strategy;
        final String note;

        Choice(SearchStrategy strategy, String note) {
            this.strategy = strategy;
            this.note = note;
        }
    }

    private static int freshTopics(LeadSituation situation) {
        int fresh = 0;
        for (TopicView topic : situation.found) {
            if (topic.fresh) {
                fresh++;
            }
        }
        return fresh;
    }

    // ------------------------------------------------------------------------------------------------- a round

    /** The decisions of one round as they are applied to the memory. */
    private final class Round {
        final ResearchMemory memory;
        final LeadSituation situation;
        final String model;
        final long now;
        final List<LeadTask> tasks = new ArrayList<LeadTask>();
        final List<LeadDecision> decisions = new ArrayList<LeadDecision>();
        final List<ResearchQuestion> taken = new ArrayList<ResearchQuestion>();
        final List<String> notes = new ArrayList<String>();
        final Set<String> decided = new HashSet<String>();
        final Set<String> wordings = new HashSet<String>();
        final int slots;
        OpenList openList;
        boolean end;
        int nextNumber = 1;

        Round(ResearchMemory memory, LeadSituation situation, String model) {
            this.memory = memory;
            this.situation = situation;
            this.model = model;
            this.now = clock.millis();
            this.slots = slots(situation);
            for (ResearchQuestion question : memory.questions()) {
                wordings.add(wording(question.getText()));
            }
        }

        /** The model's answer through the guardrails, in its order. */
        void take(Answer answer, Shown shown) throws IOException {
            Map<String, QuestionView> questions = new LinkedHashMap<String, QuestionView>();
            for (QuestionView view : situation.questions) {
                questions.put(view.id(), view);
            }
            Map<String, TopicView> topics = new LinkedHashMap<String, TopicView>();
            for (TopicView view : situation.cards) {
                topics.put(view.id(), view);
            }
            for (TopicView view : situation.found) {
                topics.put(view.id(), view);
            }
            int newLeft = options.newQuestionsPerRound;
            for (Decision decision : answer.decisions) {
                QuestionView question = shown.ids().contains(decision.target) ? questions.get(decision.target) : null;
                TopicView topic = shown.ids().contains(decision.target) ? topics.get(decision.target) : null;
                if (question == null && topic == null) {
                    notes.add(say("Ziel „" + decision.target + "“ steht nicht im Lagebild; Entscheidung verworfen",
                            "Target \"" + decision.target + "\" is not in the situation; decision dropped"));
                    continue;
                }
                if (decided.contains(decision.target)) {
                    notes.add(say("Ziel " + decision.target + " war in dieser Runde schon entschieden; zweite "
                            + "Entscheidung verworfen", "Target " + decision.target + " was decided this round "
                            + "already; second decision dropped"));
                    continue;
                }
                if (ZURUECKSTELLEN.equals(decision.action)) {
                    setAside(decision, question);
                } else if (NEUE_FRAGEN.equals(decision.action)) {
                    newLeft -= takeIn(decision, question, topic, newLeft);
                } else {
                    search(decision, question, topic, WECHSELN.equals(decision.action));
                }
            }
            if (answer.end) {
                String veto = endVeto(situation, taken.size());
                if (veto == null) {
                    if (!tasks.isEmpty()) {
                        notes.add(say("Die Leitung beendet die Recherche; die Aufgaben dieser Runde entfallen",
                                "The lead ends the research; the tasks of this round are dropped"));
                        tasks.clear();
                    }
                    end(answer.endReason, model);
                    return;
                }
                notes.add(say("Leitplanke: kein Ende, " + veto + "; die Recherche läuft weiter",
                        "Guardrail: no end, " + veto + "; the research goes on"));
            }
            fill(tasks.isEmpty() && taken.isEmpty());
            if (tasks.isEmpty() && taken.isEmpty()) {
                end(say("Keine Aufgabe mehr möglich: jede offene Frage ist gesucht und ohne Strategie übrig",
                        "No task left: every open question was searched and has no strategy left"), "");
                return;
            }
            go(answer.end ? answer.endReason + " [" + say("Leitplanke: die Recherche läuft weiter",
                    "Guardrail: the research goes on") + "]" : answer.endReason, model);
        }

        private void setAside(Decision decision, QuestionView question) throws IOException {
            if (question == null) {
                notes.add(say("Nur Fragen werden zurückgestellt; Thema " + decision.target + " bleibt",
                        "Only questions are set aside; topic " + decision.target + " stays"));
                return;
            }
            if (question.searches == 0) {
                notes.add(say("Leitplanke: " + question.id() + " wurde noch nie gesucht und wird nicht "
                        + "zurückgestellt", "Guardrail: " + question.id() + " was never searched and is not set "
                        + "aside"));
                return;
            }
            decided.add(question.id());
            memory.updateQuestion(question.question.withState(ResearchQuestion.State.ZURUECKGESTELLT, now));
            record(new LeadDecision(situation.round, Target.FRAGE, question.id(), Action.ZURUECKSTELLEN,
                    decision.reason, question.counts(), model, now));
        }

        /** New questions for a topic inside the fence; returns how many were taken in. */
        private int takeIn(Decision decision, QuestionView question, TopicView topic, int left)
                throws IOException {
            TopicView target = topic;
            if (target == null && question.topic != null) {
                for (TopicView view : situation.cards) {
                    if (view.id().equals(question.topic.getId())) {
                        target = view;
                    }
                }
                for (TopicView view : situation.found) {
                    if (view.id().equals(question.topic.getId())) {
                        target = view;
                    }
                }
            }
            if (target == null) {
                notes.add(say("Neue Fragen nur zu einem Thema innerhalb des Weidezauns (Karte oder Thema mit dem "
                        + "Urteil innen); " + decision.target + " gehört zu keinem solchen Thema", "New questions only "
                        + "for a topic inside the fence (a card or a topic with the verdict INNEN); "
                        + decision.target + " belongs to no such topic"));
                return 0;
            }
            if (decided.contains(target.id())) {
                notes.add(say("Thema " + target.id() + " war in dieser Runde schon entschieden; neue Fragen verworfen",
                        "Topic " + target.id() + " was decided this round already; new questions dropped"));
                return 0;
            }
            List<String> ids = new ArrayList<String>();
            int dropped = 0;
            for (String text : decision.questions) {
                String clean = text.replaceAll("\\s+", " ").trim();
                String key = wording(clean);
                if (clean.length() < MIN_QUESTION_CHARS || clean.length() > TEXT_CHARS || wordings.contains(key)
                        || ids.size() >= options.newQuestionsPerTopic || ids.size() >= left) {
                    dropped++;
                    continue;
                }
                ResearchQuestion stored = memory.addQuestion(ResearchQuestion.open(nextId(), clean, target.id(),
                        ResearchQuestion.Origin.LEITUNG, now));
                wordings.add(key);
                taken.add(stored);
                ids.add(stored.getId());
            }
            if (dropped > 0) {
                notes.add(say("Leitplanke: " + dropped + " neue Fragen zu " + target.id() + " verworfen (gleicher "
                        + "Wortlaut wie eine bekannte Frage, zu kurz, zu lang oder über der Grenze)", "Guardrail: "
                        + dropped + " new questions for " + target.id() + " dropped (same wording as a known "
                        + "question, too short, too long or over the limit)"));
            }
            if (ids.isEmpty()) {
                return 0;
            }
            decided.add(target.id());
            record(new LeadDecision(situation.round, Target.THEMA, target.id(), Action.NEUE_FRAGEN, decision.reason,
                    target.counts(), model, now, "", decision.instruction, ids));
            if (tasks.size() < slots) {
                tasks.add(new LeadTask(situation.round, Target.THEMA, target.id(), ids, SearchStrategy.STANDARD,
                        decision.instruction, decision.reason, LeadTask.Origin.MODELL));
            } else {
                notes.add(say("Fragen zu " + target.id() + " aufgenommen; gesucht wird in einer späteren Runde "
                        + "(kein Platz mehr)", "Questions for " + target.id() + " taken in; they are searched in a "
                        + "later round (no slot left)"));
            }
            return ids.size();
        }

        private void search(Decision decision, QuestionView question, TopicView topic, boolean change)
                throws IOException {
            if (tasks.size() >= slots) {
                notes.add(say("Kein Platz mehr in dieser Runde: " + decision.target + " folgt später",
                        "No slot left this round: " + decision.target + " comes later"));
                return;
            }
            SearchStrategy asked = SearchStrategy.of(decision.strategy);
            if (topic != null) {
                SearchStrategy strategy = !change ? SearchStrategy.STANDARD
                        : asked == null || asked == SearchStrategy.STANDARD ? SearchStrategy.ANDERE_WORTWAHL : asked;
                List<String> skipped = new ArrayList<String>();
                List<String> ids = topicQuestions(topic, strategy, skipped);
                if (!skipped.isEmpty()) {
                    notes.add(say("Leitplanke bei Thema " + topic.id() + ": " + String.join(", ", skipped)
                            + " nicht mit " + strategy.wire() + " (blieb damit ohne neuen Beleg)", "Guardrail on "
                            + "topic " + topic.id() + ": " + String.join(", ", skipped) + " not with "
                            + strategy.wire() + " (it stayed without new evidence with it)"));
                }
                if (ids.isEmpty()) {
                    notes.add(say("Thema " + topic.id() + " hat keine offene Frage, die mit " + strategy.wire()
                            + " gesucht werden darf; dafür wären neue Fragen oder ein Wechsel nötig", "Topic "
                            + topic.id() + " has no open question that may be searched with " + strategy.wire()
                            + "; it would need new questions or a change"));
                    return;
                }
                decided.add(topic.id());
                record(new LeadDecision(situation.round, Target.THEMA, topic.id(),
                        change ? Action.STRATEGIE_WECHSELN : Action.WEITER_VERFOLGEN, decision.reason,
                        topic.counts(), model, now, strategy.wire(), decision.instruction, null));
                tasks.add(new LeadTask(situation.round, Target.THEMA, topic.id(), ids, strategy,
                        decision.instruction, decision.reason, LeadTask.Origin.MODELL));
                return;
            }
            Choice choice = choose(question, change, asked, options.fruitlessLimit, german);
            String reason = choice.note.isEmpty() ? decision.reason
                    : decision.reason + " [" + say("Leitplanke: ", "Guardrail: ") + choice.note + "]";
            if (!choice.note.isEmpty()) {
                notes.add(say("Leitplanke bei ", "Guardrail on ") + question.id() + ": " + choice.note);
            }
            decided.add(question.id());
            if (choice.strategy == null) {
                memory.updateQuestion(question.question.withState(ResearchQuestion.State.ZURUECKGESTELLT, now));
                record(new LeadDecision(situation.round, Target.FRAGE, question.id(), Action.ZURUECKSTELLEN, reason,
                        question.counts(), model, now));
                return;
            }
            boolean changed = choice.strategy != question.current || change;
            record(new LeadDecision(situation.round, Target.FRAGE, question.id(),
                    changed ? Action.STRATEGIE_WECHSELN : Action.WEITER_VERFOLGEN, reason, question.counts(), model,
                    now, choice.strategy.wire(), decision.instruction, null));
            tasks.add(new LeadTask(situation.round, Target.FRAGE, question.id(),
                    Collections.singletonList(question.id()), choice.strategy, decision.instruction, reason,
                    LeadTask.Origin.MODELL));
        }

        /**
         * Up to three open questions of a topic that are not decided yet and may be searched with the strategy, the
         * least searched first; the ones passed over because the strategy already stayed without yield for them go
         * to {@code skipped}.
         */
        private List<String> topicQuestions(TopicView topic, SearchStrategy strategy, List<String> skipped) {
            List<QuestionView> open = new ArrayList<QuestionView>();
            for (QuestionView view : situation.questions) {
                if (topic.openQuestionIds.contains(view.id()) && !decided.contains(view.id())) {
                    open.add(view);
                }
            }
            Collections.sort(open, (a, b) -> Integer.compare(a.searches, b.searches));
            List<String> ids = new ArrayList<String>();
            for (QuestionView view : open) {
                if (ids.size() >= 3) {
                    break;
                }
                if (!allowed(view, strategy, options.fruitlessLimit)) {
                    skipped.add(view.id());
                    continue;
                }
                ids.add(view.id());
                decided.add(view.id());
            }
            return ids;
        }

        /**
         * Free slots by rule: questions never searched first; with {@code wider} also questions without direct
         * evidence that still have a strategy, then any open question that still has one.
         */
        void fill(boolean wider) throws IOException {
            for (QuestionView question : situation.questions) {
                if (tasks.size() >= slots) {
                    return;
                }
                if (question.searches == 0 && !decided.contains(question.id())) {
                    ruleTask(question, SearchStrategy.STANDARD, say("Leitplanke: freier Platz; die Frage wurde noch "
                            + "nie gesucht", "Guardrail: free slot; the question was never searched"));
                }
            }
            if (!wider) {
                return;
            }
            for (int pass = 0; pass < 2; pass++) {
                for (QuestionView question : situation.questions) {
                    if (tasks.size() >= slots) {
                        return;
                    }
                    if (decided.contains(question.id()) || (pass == 0 && question.evidence.getDirectCount() > 0)) {
                        continue;
                    }
                    Choice choice = choose(question, false, null, options.fruitlessLimit, german);
                    if (choice.strategy != null) {
                        ruleTask(question, choice.strategy, say("Leitplanke: keine andere Aufgabe; Strategie ",
                                "Guardrail: no other task; strategy ") + choice.strategy.wire());
                    }
                }
            }
        }

        private void ruleTask(QuestionView question, SearchStrategy strategy, String reason) throws IOException {
            decided.add(question.id());
            record(new LeadDecision(situation.round, Target.FRAGE, question.id(),
                    strategy == question.current ? Action.WEITER_VERFOLGEN : Action.STRATEGIE_WECHSELN, reason,
                    question.counts(), "", now, strategy.wire(), "", null));
            tasks.add(new LeadTask(situation.round, Target.FRAGE, question.id(),
                    Collections.singletonList(question.id()), strategy, "", reason, LeadTask.Origin.REGEL));
        }

        void end(String reason, String byModel) throws IOException {
            String why = reason == null || reason.trim().isEmpty() ? say("Ende der Recherche", "End of the research")
                    : reason;
            record(new LeadDecision(situation.round, Target.LAUF, "", Action.BEENDEN, why, runCounts(), byModel,
                    now));
            end = true;
            tasks.clear();
            openList = OpenList.of(memory, situation.round, why);
        }

        void go(String reason, String byModel) throws IOException {
            String why = reason == null || reason.trim().isEmpty() ? say("Die Recherche läuft weiter",
                    "The research goes on") : reason;
            record(new LeadDecision(situation.round, Target.LAUF, "", Action.WEITER_VERFOLGEN, why, runCounts(),
                    byModel, now));
        }

        private Map<String, Integer> runCounts() {
            Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
            counts.put("runde", situation.round);
            counts.put("suchen", situation.searchesDone);
            counts.put("offeneFragen", situation.questions.size());
            counts.put("nieGesucht", situation.neverSearched);
            counts.put("neueBelegeLetzteRunde", situation.lastNewEvidence);
            counts.put("neueThemenLetzteRunde", situation.lastNewTopics);
            counts.put("rundenOhneNeues", situation.quietRounds);
            counts.put("neueFragen", taken.size());
            return counts;
        }

        private void record(LeadDecision decision) throws IOException {
            memory.record(decision);
            decisions.add(decision);
        }

        private String nextId() {
            String id;
            do {
                id = "leitung-r" + situation.round + "-" + nextNumber++;
            } while (memory.question(id) != null);
            return id;
        }

        LeadPlan plan(LeadPlan.Status status, String text, String problem, List<String> answers, long start) {
            int searchBudget = end ? 0 : Math.min(tasks.size() * budget.searchesPerTask,
                    budget.maxSearches - situation.searchesDone);
            return new LeadPlan(situation.round, status, end, tasks, decisions, taken, notes, text, openList,
                    searchBudget, problem, answers, status == LeadPlan.Status.MODELL ? model : "",
                    clock.millis() - start);
        }
    }

    // ------------------------------------------------------------------------------------------------- the call

    /** The questions and topics the prompt shows; only they can be targets. */
    static final class Shown {
        final List<QuestionView> questions;
        final List<TopicView> found;
        final List<TopicView> cards;
        final List<ResearchTopic> unclear;
        final int questionsHidden;
        final int foundHidden;
        private final Set<String> ids = new LinkedHashSet<String>();

        Shown(List<QuestionView> questions, List<TopicView> found, List<TopicView> cards, List<ResearchTopic> unclear,
              int questionsHidden, int foundHidden) {
            this.questions = questions;
            this.found = found;
            this.cards = cards;
            this.unclear = unclear;
            this.questionsHidden = questionsHidden;
            this.foundHidden = foundHidden;
            for (QuestionView view : questions) {
                ids.add(view.id());
            }
            for (TopicView view : found) {
                ids.add(view.id());
            }
            for (TopicView view : cards) {
                ids.add(view.id());
            }
        }

        Set<String> ids() {
            return ids;
        }
    }

    Shown shown(LeadSituation situation) {
        List<QuestionView> questions = new ArrayList<QuestionView>(situation.questions.subList(0,
                Math.min(situation.questions.size(), options.questionsShown)));
        List<TopicView> found = new ArrayList<TopicView>(situation.found.subList(0,
                Math.min(situation.found.size(), options.topicsShown)));
        List<ResearchTopic> unclear = new ArrayList<ResearchTopic>(situation.unclear.subList(0,
                Math.min(situation.unclear.size(), options.unclearShown)));
        return new Shown(questions, found, new ArrayList<TopicView>(situation.cards), unclear,
                situation.questions.size() - questions.size(), situation.found.size() - found.size());
    }

    /** The outcome of the model call: the answer, or why there is none, and the raw answers. */
    private static final class Call {
        final Answer answer;
        final String problem;
        final List<String> answers;

        Call(Answer answer, String problem, List<String> answers) {
            this.answer = answer;
            this.problem = problem;
            this.answers = answers;
        }

        boolean ok() {
            return answer != null;
        }
    }

    private Call ask(String prompt, String schema, Set<String> ids) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt));
        List<String> answers = new ArrayList<String>();
        String problem = "";
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, options.answerTokens,
                        schema);
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
            Answer answer = parse(text);
            if (answer.problem.isEmpty()) {
                return new Call(answer, "", answers);
            }
            problem = "invalid answer: " + answer.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + answer.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        return new Call(null, problem, answers);
    }

    /** One decision as the model gave it. */
    static final class Decision {
        String reason = "";
        String target = "";
        String action = "";
        String strategy = "";
        String instruction = "";
        List<String> questions = new ArrayList<String>();
    }

    /** The model's answer as read, or why it holds none. */
    static final class Answer {
        String situation = "";
        List<Decision> decisions = new ArrayList<Decision>();
        boolean end;
        String endReason = "";
        String problem = "";
    }

    static Answer parse(String text) {
        Answer answer = new Answer();
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text == null ? "" : text.trim());
            if (!element.isJsonObject()) {
                answer.problem = "the answer is no JSON object";
                return answer;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            answer.problem = "the answer is no JSON";
            return answer;
        }
        answer.situation = cut(string(json, "lage"), SITUATION_CHARS);
        JsonElement run = json.get("lauf");
        if (run == null || !run.isJsonObject()) {
            answer.problem = "\"lauf\" must be an object with \"grund\" and \"handlung\"";
            return answer;
        }
        String runAction = word(string(run.getAsJsonObject(), "handlung"));
        if (!runAction.equals("weiter") && !runAction.equals("beenden")) {
            answer.problem = "\"lauf.handlung\" must be weiter or beenden";
            return answer;
        }
        answer.end = runAction.equals("beenden");
        answer.endReason = cut(string(run.getAsJsonObject(), "grund"), REASON_CHARS);
        JsonElement list = json.get("entscheidungen");
        if (list == null || !list.isJsonArray()) {
            answer.problem = "\"entscheidungen\" must be a list";
            return answer;
        }
        int n = 0;
        for (JsonElement element : list.getAsJsonArray()) {
            n++;
            if (!element.isJsonObject()) {
                answer.problem = "decision " + n + " is no object";
                return answer;
            }
            JsonObject item = element.getAsJsonObject();
            Decision decision = new Decision();
            decision.action = word(string(item, "handlung"));
            if (!ACTIONS.contains(decision.action)) {
                answer.problem = "decision " + n + ": \"handlung\" must be one of " + ACTIONS;
                return answer;
            }
            decision.reason = cut(string(item, "grund"), REASON_CHARS);
            if (decision.reason.isEmpty()) {
                answer.problem = "decision " + n + ": \"grund\" must give the reason";
                return answer;
            }
            decision.target = string(item, "ziel");
            decision.strategy = string(item, "strategie");
            decision.instruction = cut(string(item, "vorgabe"), TEXT_CHARS);
            JsonElement asked = item.get("fragen");
            if (asked != null && asked.isJsonArray()) {
                for (JsonElement question : asked.getAsJsonArray()) {
                    if (question.isJsonPrimitive()) {
                        decision.questions.add(question.getAsString());
                    }
                }
            }
            answer.decisions.add(decision);
        }
        return answer;
    }

    // ------------------------------------------------------------------------------------------------- prompt

    static String prompt(LeadSituation situation, Shown shown, String mission, String edge, boolean german,
                         Options options, Budget budget, int slots) {
        StringBuilder text = new StringBuilder();
        String m = clean(mission);
        text.append(german ? "Buchauftrag:\n" : "Book mission:\n")
                .append(m.isEmpty() ? (german ? "(keiner angegeben)" : "(none given)") : cut(m, MISSION_CHARS))
                .append("\n\n");
        if (!clean(edge).isEmpty()) {
            text.append(german ? "Rand des Themas:\n" : "Edge of the subject:\n").append(clean(edge)).append("\n\n");
        }
        text.append(german ? "Stand vor Runde " : "Before round ").append(situation.round).append(": ")
                .append(situation.round - 1).append(german ? " von höchstens " : " of at most ")
                .append(budget.maxRounds).append(german ? " Runden gelaufen, " : " rounds run, ")
                .append(situation.searchesDone).append(german ? " von höchstens " : " of at most ")
                .append(budget.maxSearches).append(german ? " Suchen verbraucht. In dieser Runde sind bis zu "
                        : " searches used. This round can take up to ")
                .append(slots).append(german ? " Suchaufgaben möglich.\n" : " search tasks.\n");
        if (situation.lastRound == 0) {
            text.append(german ? "Noch keine Suche ist abgeschlossen.\n" : "No search has finished yet.\n");
        } else {
            text.append(german ? "Letzte Runde mit Suchen (Runde " : "Last round with searches (round ")
                    .append(situation.lastRound).append("): ").append(situation.lastNewEvidence)
                    .append(german ? " neue direkte Belege, " : " new pieces of direct evidence, ")
                    .append(situation.lastNewTopics).append(german ? " neue Themen. Runden ohne Neues in Folge: "
                            : " new topics. Rounds with nothing new in a row: ")
                    .append(situation.quietRounds).append(".\n");
        }
        text.append('\n');

        text.append(german ? "Offene Fragen (Zählwerte aus dem Gedächtnis; „Belegt“ heißt nur, dass Belege gefunden "
                + "wurden, nicht, dass die Frage erschöpft ist):\n" : "Open questions (counts from the memory; "
                + "\"Belegt\" only says that evidence was found, not that the question is exhausted):\n");
        for (QuestionView view : shown.questions) {
            text.append("- ").append(view.id());
            if (view.topic != null) {
                text.append(" [").append(view.topic.getName()).append(']');
            }
            text.append(' ').append(cut(view.question.getText(), TEXT_CHARS)).append("\n  ")
                    .append(view.evidence.summary()).append(german ? "; Passagen geprüft: " : "; passages judged: ")
                    .append(view.evidence.getJudgedPassages());
            if (view.searches == 0) {
                text.append(german ? "; noch nie gesucht" : "; never searched");
            } else {
                text.append(german ? "; Suchen: " : "; searches: ").append(view.searches)
                        .append(german ? ", zuletzt " : ", last ").append(view.current.wire());
                if (view.fruitlessWithCurrent > 0) {
                    text.append(german ? ", davon " : ", of them ").append(view.fruitlessWithCurrent)
                            .append(german ? " in Folge ohne neuen Beleg" : " in a row without new evidence");
                }
                if (!view.tried.isEmpty()) {
                    text.append(german ? "; seit dem letzten neuen Beleg ohne Ertrag versucht: "
                            : "; tried without yield since the last new evidence: ").append(wires(view.tried));
                }
            }
            text.append('\n');
            if (!view.directQuote.isEmpty()) {
                text.append(german ? "  Beleg: „" : "  Evidence: \"").append(view.directQuote)
                        .append(german ? "“\n" : "\"\n");
            }
            if (!view.leadQuote.isEmpty()) {
                text.append(german ? "  Hinweis: „" : "  Lead: \"").append(view.leadQuote)
                        .append(german ? "“\n" : "\"\n");
            }
        }
        if (shown.questions.isEmpty()) {
            text.append(german ? "(keine)\n" : "(none)\n");
        }
        if (shown.questionsHidden > 0) {
            text.append(german ? "(" + shown.questionsHidden + " weitere offene Fragen sind nicht gezeigt.)\n"
                    : "(" + shown.questionsHidden + " more open questions are not shown.)\n");
        }
        text.append('\n');

        if (!shown.found.isEmpty()) {
            text.append(german ? "Themen innerhalb des Weidezauns, die das Konzept nicht vorsah (gefunden):\n"
                    : "Topics inside the fence the concept did not foresee (found):\n");
            for (TopicView view : shown.found) {
                text.append("- ").append(view.id()).append(' ').append(view.topic.getName());
                if (!view.topic.getDescription().isEmpty()) {
                    text.append(": ").append(cut(view.topic.getDescription(), TEXT_CHARS));
                }
                text.append(german ? "\n  Fundstellen: " : "\n  findings: ").append(view.findings)
                        .append(german ? "; offene Fragen: " : "; open questions: ")
                        .append(view.openQuestionIds.size());
                if (view.fresh) {
                    text.append(german ? " (noch ohne Fragen)" : " (no questions yet)");
                }
                text.append('\n');
                if (!view.quote.isEmpty()) {
                    text.append(german ? "  Aus den Quellen: „" : "  From the sources: \"").append(view.quote)
                            .append(german ? "“\n" : "\"\n");
                }
            }
            if (shown.foundHidden > 0) {
                text.append(german ? "(" + shown.foundHidden + " weitere Themen sind nicht gezeigt.)\n"
                        : "(" + shown.foundHidden + " more topics are not shown.)\n");
            }
            text.append('\n');
        }
        if (!shown.cards.isEmpty()) {
            text.append(german ? "Karten des Konzepts:\n" : "Cards of the concept:\n");
            for (TopicView view : shown.cards) {
                text.append("- ").append(view.id()).append(' ').append(view.topic.getName())
                        .append(german ? "; offene Fragen: " : "; open questions: ")
                        .append(view.openQuestionIds.size()).append('\n');
            }
            text.append('\n');
        }
        if (!shown.unclear.isEmpty()) {
            text.append(german ? "Themen, über die der Autor noch entscheidet (keine neuen Fragen dazu):\n"
                    : "Topics the author still decides (no new questions for them):\n");
            for (ResearchTopic topic : shown.unclear) {
                text.append("- ").append(topic.getName()).append('\n');
            }
            text.append('\n');
        }
        if (!situation.recent.isEmpty()) {
            text.append(german ? "Letzte Suchen:\n" : "Last searches:\n");
            for (SearchRecord search : situation.recent) {
                List<String> about = new ArrayList<String>(search.getQuestionIds());
                about.addAll(search.getTopicIds());
                text.append(german ? "- Runde " : "- round ").append(search.getRound()).append(", ")
                        .append(String.join(" ", about)).append(", ")
                        .append(LeadSituation.strategyOf(search).wire()).append(": ")
                        .append(german ? "„" : "\"").append(cut(search.getQuery(), QUERY_CHARS))
                        .append(german ? "“" : "\"");
                SearchRecord.Yield yield = search.getYield();
                if (yield == null) {
                    text.append(german ? ", läuft\n" : ", running\n");
                } else {
                    text.append(german ? ", Ertrag: " : ", yield: ").append(yield.getSources())
                            .append(german ? " Quellen, " : " sources, ").append(yield.getPassages())
                            .append(german ? " Passagen, " : " passages, ").append(yield.getNewEvidence())
                            .append(german ? " neue Belege, " : " new pieces of evidence, ")
                            .append(yield.getNewTopics()).append(german ? " neue Themen\n" : " new topics\n");
                }
            }
            text.append('\n');
        }
        instructions(text, german, options, slots);
        return text.toString();
    }

    private static void instructions(StringBuilder text, boolean german, Options options, int slots) {
        if (german) {
            text.append("Entscheide die nächste Runde. Handlungen:\n")
                    .append("- \"weiter_verfolgen\": eine Frage, oder die offenen Fragen eines Themas, mit der "
                            + "bisherigen Strategie weiter suchen.\n")
                    .append("- \"strategie_wechseln\": anders suchen, mit einer dieser Strategien:\n");
        } else {
            text.append("Decide the next round. Actions:\n")
                    .append("- \"weiter_verfolgen\": search a question, or the open questions of a topic, on with "
                            + "the strategy so far.\n")
                    .append("- \"strategie_wechseln\": search differently, with one of these strategies:\n");
        }
        for (SearchStrategy strategy : SearchStrategy.CHANGE_ORDER) {
            text.append("  \"").append(strategy.wire()).append("\": ").append(strategy.meaning(german)).append('\n');
        }
        if (german) {
            text.append("  Nach ").append(options.fruitlessLimit).append(" Suchen ohne neuen Beleg mit derselben "
                            + "Strategie ist ein Wechsel Pflicht; eine Strategie, die schon ohne Ertrag blieb, ist "
                            + "kein Wechsel.\n")
                    .append("- \"neue_fragen\": zu einem Thema bis zu ").append(options.newQuestionsPerTopic)
                    .append(" neue Fragen aufnehmen (\"fragen\"), die die offenen Fragen noch nicht stellen. Themen, "
                            + "die das Konzept nicht vorsah, verdienen eigene Fragen: Das Konzept gibt die Richtung "
                            + "vor, nicht den fertigen Inhalt des Buchs.\n")
                    .append("- \"zurueckstellen\": eine Frage vorerst nicht weiter suchen, etwa wenn mehrere "
                            + "Strategien nichts brachten.\n")
                    .append("Jede Entscheidung mit \"weiter_verfolgen\", \"strategie_wechseln\" oder \"neue_fragen\" "
                            + "wird eine Suchaufgabe; mehr als ").append(slots).append(" passen nicht in diese Runde "
                            + "(neue Fragen bleiben dann für später). Jede Entscheidung braucht einen Grund aus den "
                            + "Zählwerten oder Belegen. \"ziel\" ist die Kennung einer Frage, eines Themas oder einer "
                            + "Karte von oben. \"vorgabe\" ist ein Satz für den Suchplaner: was gefunden werden soll "
                            + "und wo. \"strategie\" ist \"standard\", wenn keine besondere Strategie gemeint ist.\n")
                    .append("Zum Lauf (\"lauf\"): \"weiter\" oder \"beenden\". Beenden heißt nie, dass alles "
                            + "beantwortet ist. Beende nur, wenn weitere Suchen nichts Neues erwarten lassen. Solange "
                            + "eine Frage ohne direkten Beleg noch eine Strategie hat, die sie nicht versucht hat, "
                            + "läuft die Recherche weiter; gibst du eine solche Frage auf, stelle sie zurück.\n\n")
                    .append("Antworte nur mit JSON mit den Feldern \"lage\" (zwei bis vier Sätze, zuerst), "
                            + "\"entscheidungen\" (Liste mit \"grund\", \"ziel\", \"handlung\", \"strategie\", "
                            + "\"vorgabe\", \"fragen\") und \"lauf\" (\"grund\", \"handlung\").");
        } else {
            text.append("  After ").append(options.fruitlessLimit).append(" searches without new evidence with the "
                            + "same strategy a change is a must; a strategy that already stayed without yield is no "
                            + "change.\n")
                    .append("- \"neue_fragen\": take in up to ").append(options.newQuestionsPerTopic)
                    .append(" new questions for a topic (\"fragen\") that the open questions do not ask yet. Topics "
                            + "the concept did not foresee deserve questions of their own: the concept gives the "
                            + "direction, not the finished content of the book.\n")
                    .append("- \"zurueckstellen\": stop searching a question for now, for instance when several "
                            + "strategies brought nothing.\n")
                    .append("Every decision with \"weiter_verfolgen\", \"strategie_wechseln\" or \"neue_fragen\" "
                            + "becomes a search task; more than ").append(slots).append(" do not fit into this round "
                            + "(new questions then wait). Every decision needs a reason from the counts or the "
                            + "evidence. \"ziel\" is the id of a question, topic or card from above. \"vorgabe\" is "
                            + "one sentence for the search planner: what to find and where. \"strategie\" is "
                            + "\"standard\" when no particular strategy is meant.\n")
                    .append("For the run (\"lauf\"): \"weiter\" or \"beenden\". Ending never means that everything "
                            + "is answered. End only when further searches promise nothing new. While a question "
                            + "without direct evidence still has a strategy it has not tried, the research goes on; "
                            + "if you give such a question up, set it aside.\n\n")
                    .append("Answer only with JSON with the fields \"lage\" (two to four sentences, first), "
                            + "\"entscheidungen\" (list with \"grund\", \"ziel\", \"handlung\", \"strategie\", "
                            + "\"vorgabe\", \"fragen\") and \"lauf\" (\"grund\", \"handlung\").");
        }
    }

    /** The answer schema of a round: targets only from the ids shown, reasons first. */
    static String schema(Set<String> ids, int questionsPerTopic, int slots) {
        JsonObject decision = new JsonObject();
        decision.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        properties.add("grund", type("string"));
        JsonObject target = type("string");
        target.add("enum", strings(new ArrayList<String>(ids)));
        properties.add("ziel", target);
        JsonObject action = type("string");
        action.add("enum", strings(ACTIONS));
        properties.add("handlung", action);
        JsonObject strategy = type("string");
        List<String> wires = new ArrayList<String>();
        for (SearchStrategy s : SearchStrategy.values()) {
            wires.add(s.wire());
        }
        strategy.add("enum", strings(wires));
        properties.add("strategie", strategy);
        properties.add("vorgabe", type("string"));
        JsonObject asked = type("array");
        asked.add("items", type("string"));
        asked.addProperty("maxItems", questionsPerTopic);
        properties.add("fragen", asked);
        decision.add("properties", properties);
        decision.add("required", strings(java.util.Arrays.asList("grund", "ziel", "handlung", "strategie", "vorgabe",
                "fragen")));

        JsonObject decisions = type("array");
        decisions.add("items", decision);
        decisions.addProperty("maxItems", slots + 12);

        JsonObject run = type("object");
        JsonObject runProperties = new JsonObject();
        runProperties.add("grund", type("string"));
        JsonObject runAction = type("string");
        runAction.add("enum", strings(java.util.Arrays.asList("weiter", "beenden")));
        runProperties.add("handlung", runAction);
        run.add("properties", runProperties);
        run.add("required", strings(java.util.Arrays.asList("grund", "handlung")));

        JsonObject root = type("object");
        JsonObject rootProperties = new JsonObject();
        rootProperties.add("lage", type("string"));
        rootProperties.add("entscheidungen", decisions);
        rootProperties.add("lauf", run);
        root.add("properties", rootProperties);
        root.add("required", strings(java.util.Arrays.asList("lage", "entscheidungen", "lauf")));
        return root.toString();
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static JsonObject type(String name) {
        JsonObject json = new JsonObject();
        json.addProperty("type", name);
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String wires(Set<SearchStrategy> strategies) {
        List<String> names = new ArrayList<String>();
        for (SearchStrategy strategy : strategies) {
            names.add(strategy.wire());
        }
        return String.join(", ", names);
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().replaceAll("\\s+", " ").trim() : "";
    }

    /** Lower case, umlauts and blanks folded: "Strategie wechseln" and "strategie_wechseln" are one word. */
    static String word(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT).replace("ü", "ue").replace("ä", "ae")
                .replace("ö", "oe").replace(' ', '_').replace('-', '_');
    }

    /** Letters and digits, case ignored: how the memory tells two wordings apart. */
    static String wording(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    static String cut(String text, int chars) {
        return LeadSituation.cut(text, chars);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String say(String inGerman, String inEnglish) {
        return german ? inGerman : inEnglish;
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
