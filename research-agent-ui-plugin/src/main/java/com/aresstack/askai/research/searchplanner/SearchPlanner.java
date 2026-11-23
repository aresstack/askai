package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.lead.LeadTask;
import com.aresstack.askai.research.lead.SearchStrategy;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.LeadDecision;
import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.SearchRecord;
import com.aresstack.askai.research.plan.SourcePreference;
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
import java.text.Normalizer;
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
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component 3 of the build plan of 21.11.2026, the search planner (Suchplaner): turns each task of the research lead
 * (component 8) into one to three web searches. Every search names one question of its task, carries the task's
 * strategy and one sentence on what it should find. One call of {@code gemma4:e2b} per task on the slot
 * {@code phase.research.planner} (thinking off, as the concept planner), tasks in parallel, one repair.
 *
 * <p>The model sees the book mission, the strategy with the lead's instruction, the task's questions with what the
 * memory found for them (counts, a few quotes, the domains of the sources), the earlier searches of these questions
 * and, for a targeted strategy, the publishers and kinds of document the mission prefers ({@link SourcePreference}).
 * Rules on the answer: a search must name a question of its task, have the form of a search (3 to 160 characters,
 * at least one word), use {@code site:} only for the strategy {@code gezielte_herausgeber} (nothing appends a
 * {@code site:} mechanically), keep to the strategy where it can be checked, and repeat no earlier search: the same
 * words and the same {@code site:} make the same search, as the search hygiene (component 4) counts it, in any order
 * and any case. Checked strategies: with {@code gezielte_herausgeber} every search has a {@code site:} with a
 * publisher's address; with {@code andere_sprache} it is in the target language (the first other language the
 * instruction names, else English for a German book and German for an English one), which the prompt names; with
 * {@code andere_wortwahl} most of its words are new: fewer than half come from the question or the earlier searches.
 * The repair names the rule of the strategy when a search missed it. When no search of the repaired answer holds and
 * one of them missed only the strategy, one more repair follows. A task still left without a search fails (status
 * FEHLER) and is offered again by the lead; nothing is made up in its place.</p>
 *
 * <p>The searches are written to the memory without a yield (origin {@code PLANER}); the search itself and its yield
 * come later. Checked for reuse: the concept planner of {@code QueryPlanner} (runtime) plans per concept node, not
 * per question and strategy, and its reader is not visible here; its rules for the form of a query are taken over.
 * The entries go into the search plan ({@code SearchPlanEntry}, {@code SearchPlanQueue}) when the component is
 * wired in; the queue checks them again with the search hygiene.</p>
 */
public final class SearchPlanner {

    /** Tuning; immutable. */
    public static final class Options {
        final int parallel;
        final int searchesPerTask;
        final int shownSearches;
        final int quotesPerQuestion;
        final int quoteChars;
        final int answerTokens;

        private Options(int parallel, int searchesPerTask, int shownSearches, int quotesPerQuestion, int quoteChars,
                        int answerTokens) {
            this.parallel = Math.max(1, parallel);
            this.searchesPerTask = Math.max(1, searchesPerTask);
            this.shownSearches = Math.max(0, shownSearches);
            this.quotesPerQuestion = Math.max(0, quotesPerQuestion);
            this.quoteChars = Math.max(40, quoteChars);
            this.answerTokens = Math.max(64, answerTokens);
        }

        /** 4 tasks at a time, up to 3 searches per task, 10 earlier searches and 2 quotes of 200 characters shown. */
        public static Options defaults() {
            return new Options(4, 3, 10, 2, 200, 1024);
        }

        public Options withParallel(int count) {
            return new Options(count, searchesPerTask, shownSearches, quotesPerQuestion, quoteChars, answerTokens);
        }

        public Options withSearchesPerTask(int count) {
            return new Options(parallel, count, shownSearches, quotesPerQuestion, quoteChars, answerTokens);
        }

        public Options withShown(int searches, int quotes, int chars) {
            return new Options(parallel, searchesPerTask, searches, quotes, chars, answerTokens);
        }

        public Options withAnswerTokens(int tokens) {
            return new Options(parallel, searchesPerTask, shownSearches, quotesPerQuestion, quoteChars, tokens);
        }

        public int getParallel() {
            return parallel;
        }

        public int getSearchesPerTask() {
            return searchesPerTask;
        }

        public int getShownSearches() {
            return shownSearches;
        }

        public int getQuotesPerQuestion() {
            return quotesPerQuestion;
        }

        public int getQuoteChars() {
            return quoteChars;
        }

        public int getAnswerTokens() {
            return answerTokens;
        }
    }

    /** The role: the planner slot of the Sources phase, a schema per request (the task's questions), no thinking. */
    public static final ResearchRole ROLE = ResearchRole.builder("search-planner",
                    ResearchModelSlots.PHASE_RESEARCH_PLANNER)
            .purpose("component 3: turns a task of the research lead into one to three web searches, each for one "
                    + "question of the task, with the task's strategy")
            .promptFromCaller()
            .output(RoleOutput.schemaPerRequest("suchen (frage, zweck, anfrage)"))
            .thinking(ThinkingMode.OFF)
            .build();

    /** As the concept planner. */
    static final double TEMPERATURE = 0.3;
    /** Calls per task: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    /**
     * One more repair when no search of the repaired answer holds and one of them missed only the task's strategy.
     * Stock test c3b of 22.11.2026: e2b kept to the form but not always to gezielte_herausgeber or andere_wortwahl
     * after one repair.
     */
    static final int STRATEGY_REPAIRS = 1;
    /** The example addresses of the prompt; a search that names one has no publisher's address. */
    private static final Set<String> EXAMPLE_SITES = new HashSet<String>(java.util.Arrays.asList("herausgeber.de",
            "publisher.org"));
    /** What the memory names as the origin of a search planned here. */
    public static final String ORIGIN = "PLANER";
    private static final int MISSION_CHARS = 1200;
    private static final int TEXT_CHARS = 300;
    private static final int PURPOSE_CHARS = 300;
    private static final int MIN_PURPOSE_CHARS = 10;
    private static final int DOMAINS_SHOWN = 8;

    static final String SYSTEM_DE = "Du bist der Suchplaner einer Recherche für ein Sachbuch. Du bekommst eine Aufgabe "
            + "der Rechercheleitung und schreibst Websuchen dafür. Du suchst nicht selbst. Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You are the search planner of the research for a non-fiction book. You get one "
            + "task of the research lead and write web searches for it. You do not search yourself. Answer only with "
            + "JSON.";

    private static final Pattern SITE = Pattern.compile("(?<![\\p{L}\\p{N}])(-?)site:(\\S+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern NUMBER = Pattern.compile("\\p{N}+");

    /** Short German words no search in another language needs; with the English ones no part of a wording. */
    private static final Set<String> GERMAN_WORDS = new HashSet<String>(java.util.Arrays.asList("der", "die", "das",
            "den", "dem", "des", "und", "oder", "für", "fuer", "über", "ueber", "mit", "von", "vom", "zum", "zur",
            "bei", "beim", "nach", "auf", "aus", "ein", "eine", "einer", "eines", "einem", "einen", "wie", "was", "wer",
            "welche", "welcher", "welches", "nicht", "ist", "sind", "wird", "werden", "im", "am", "zu", "es"));
    private static final Set<String> ENGLISH_WORDS = new HashSet<String>(java.util.Arrays.asList("the", "a", "an",
            "and", "or", "of", "in", "on", "at", "for", "with", "by", "from", "to", "how", "what", "which", "who",
            "is", "are", "does", "do"));
    /** Endings of German words, each with the shortest word it counts for. */
    private static final String[][] GERMAN_ENDINGS = {{"ungen", "7"}, {"ung", "5"}, {"heit", "6"}, {"keit", "6"},
            {"lichen", "8"}, {"licher", "8"}, {"liche", "7"}, {"lich", "6"}, {"ischen", "8"}, {"ische", "7"},
            {"isch", "6"}, {"schaft", "8"}, {"chen", "6"}, {"en", "5"}};
    /**
     * Languages a search can be written in: code, name in German, name in English, then the stems a text names it
     * by. A search of the strategy andere_sprache goes into the first one the instruction names, else into English
     * (German book) or German (English book).
     */
    private static final String[][] LANGUAGES = {
            {"en", "Englisch", "English", "englisch", "english"},
            {"de", "Deutsch", "German", "deutsch", "german"},
            {"fr", "Französisch", "French", "französisch", "franzoesisch", "french"},
            {"es", "Spanisch", "Spanish", "spanisch", "spanish"},
            {"it", "Italienisch", "Italian", "italienisch", "italian"},
            {"nl", "Niederländisch", "Dutch", "niederländisch", "niederlaendisch", "holländisch", "dutch"},
            {"pl", "Polnisch", "Polish", "polnisch", "polish"},
            {"pt", "Portugiesisch", "Portuguese", "portugiesisch", "portuguese"},
            {"sv", "Schwedisch", "Swedish", "schwedisch", "swedish"},
            {"da", "Dänisch", "Danish", "dänisch", "daenisch", "danish"},
            {"cs", "Tschechisch", "Czech", "tschechisch", "czech"}};

    private final MainModelChat chat;
    private final boolean german;
    private final Options options;
    private final SourcePreference preference;
    private final Consumer<String> log;
    private final Clock clock;

    public SearchPlanner(MainModelChat chat, String language, Options options, SourcePreference preference,
                         Consumer<String> log) {
        this(chat, language, options, preference, log, Clock.systemUTC());
    }

    SearchPlanner(MainModelChat chat, String language, Options options, SourcePreference preference,
                  Consumer<String> log, Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the search planner needs its model");
        }
        this.chat = chat;
        this.german = language == null || !language.trim().toLowerCase(Locale.ROOT).startsWith("en");
        this.options = options == null ? Options.defaults() : options;
        this.preference = preference == null ? SourcePreference.NONE : preference;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Plans the searches of the lead's tasks of one round and writes them to the memory (without yield), in task
     * order. The model calls run in parallel; the searches are taken in task order, so a search another task of the
     * round already holds counts as a repeat.
     *
     * @param budget the searches the round may take at most (the lead's search budget)
     */
    public RoundPlan plan(ResearchMemory memory, List<LeadTask> tasks, String mission, int budget)
            throws IOException {
        long start = clock.millis();
        Map<String, String> earlier = new LinkedHashMap<String, String>();
        Set<String> ids = new HashSet<String>();
        for (SearchRecord search : memory.searches()) {
            earlier.putIfAbsent(key(search.getQuery()), search.getQuery());
            ids.add(search.getId());
        }
        List<Job> jobs = new ArrayList<Job>();
        for (LeadTask task : tasks == null ? Collections.<LeadTask>emptyList() : tasks) {
            jobs.add(new Job(task, brief(memory, task, mission)));
        }
        run(jobs, earlier);

        List<TaskPlan> plans = new ArrayList<TaskPlan>();
        int left = Math.max(0, budget);
        int number = 0;
        for (Job job : jobs) {
            List<PlannedSearch> taken = new ArrayList<PlannedSearch>();
            List<Rejection> rejected = new ArrayList<Rejection>(job.rejected);
            String problem = job.problem;
            for (Candidate candidate : job.accepted) {
                String key = key(candidate.query);
                if (earlier.containsKey(key)) {
                    rejected.add(new Rejection(candidate.query, say("wiederholt die Suche „" + earlier.get(key)
                            + "“ einer anderen Aufgabe dieser Runde", "repeats the search \"" + earlier.get(key)
                            + "\" of another task of this round")));
                    continue;
                }
                if (left <= 0) {
                    rejected.add(new Rejection(candidate.query, say("über dem Suchbudget der Runde",
                            "over the round's search budget")));
                    continue;
                }
                String id;
                do {
                    id = "suche-r" + job.task.getRound() + "-" + (++number);
                } while (ids.contains(id));
                ids.add(id);
                List<String> topics = job.task.getTarget() == LeadDecision.Target.THEMA
                        ? Collections.singletonList(job.task.getTargetId()) : Collections.<String>emptyList();
                SearchRecord record = new SearchRecord(id, candidate.query, Collections.singletonList(
                        candidate.question), topics, job.task.getStrategy().wire(), ORIGIN, job.task.getRound(), null,
                        clock.millis());
                memory.record(record);
                earlier.put(key, candidate.query);
                taken.add(new PlannedSearch(record, candidate.purpose));
                left--;
            }
            TaskPlan.Status status = taken.isEmpty() ? TaskPlan.Status.FEHLER : TaskPlan.Status.MODELL;
            if (taken.isEmpty() && problem.isEmpty()) {
                problem = say("keine Suche übrig", "no search left");
            }
            if (status == TaskPlan.Status.FEHLER) {
                log("search planner: task " + job.task.getTargetId() + " has no search: " + problem);
            }
            plans.add(new TaskPlan(job.task, status, taken, rejected, status == TaskPlan.Status.FEHLER ? problem : "",
                    job.answers, job.model, job.millis));
        }
        return new RoundPlan(plans, clock.millis() - start);
    }

    // ------------------------------------------------------------------------------------------------- calls

    /** One task on its way through the model. */
    private static final class Job {
        final LeadTask task;
        final Brief brief;
        final List<Candidate> accepted = new ArrayList<Candidate>();
        final List<Rejection> rejected = new ArrayList<Rejection>();
        final List<String> answers = new ArrayList<String>();
        String problem = "";
        String model = "";
        long millis;

        Job(LeadTask task, Brief brief) {
            this.task = task;
            this.brief = brief;
        }
    }

    /** A search the model wrote that passed the rules of its own task. */
    private static final class Candidate {
        final String question;
        final String query;
        final String purpose;

        Candidate(String question, String query, String purpose) {
            this.question = question;
            this.query = query;
            this.purpose = purpose;
        }
    }

    private void run(List<Job> jobs, Map<String, String> earlier) {
        if (jobs.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.parallel, jobs.size()), runnable -> {
            Thread thread = new Thread(runnable, "search-planner");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<Future<?>>();
            for (Job job : jobs) {
                futures.add(pool.submit(() -> ask(job, earlier)));
            }
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (Exception failed) {
                    Job job = jobs.get(i);
                    job.accepted.clear();
                    job.problem = "planning failed: " + failed;
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private void ask(Job job, Map<String, String> earlier) {
        long began = clock.millis();
        if (job.brief.problem != null) {
            job.problem = job.brief.problem;
            job.millis = clock.millis() - began;
            return;
        }
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(job.brief, german, options)));
        String schema = schema(job.brief.questionIds(), options.searchesPerTask);
        int calls = ATTEMPTS;
        for (int attempt = 1; attempt <= calls; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, options.answerTokens,
                        schema);
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (result == null || !result.isOk()) {
                job.answers.add("");
                job.problem = result == null ? "model call returned nothing"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue;
            }
            String text = result.getText() == null ? "" : result.getText();
            job.answers.add(text);
            List<Rejection> rejected = new ArrayList<Rejection>();
            String invalid = read(text, job.brief, earlier, job.accepted, rejected);
            job.rejected.addAll(rejected);
            if (invalid.isEmpty() && !job.accepted.isEmpty()) {
                job.problem = "";
                job.model = chat.modelName();
                break;
            }
            job.problem = invalid.isEmpty() ? say("keine brauchbare Suche: ", "no usable search: ")
                    + reasons(rejected) : "invalid answer: " + invalid;
            List<Rejection> misses = new ArrayList<Rejection>();
            for (Rejection rejection : rejected) {
                if (rejection.isStrategy()) {
                    misses.add(rejection);
                }
            }
            if (attempt == calls && calls == ATTEMPTS && invalid.isEmpty() && !misses.isEmpty()) {
                calls += STRATEGY_REPAIRS;
            }
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war nicht brauchbar: " : "Your answer was not "
                    + "usable: ") + job.problem + (misses.isEmpty() ? "" : "\n" + strategyHint(job.brief, misses))
                    + (german ? "\nSchreibe neue Suchen, nur das JSON-Objekt."
                    : "\nWrite new searches, the JSON object only.")));
        }
        job.millis = clock.millis() - began;
    }

    /**
     * Reads one answer: the searches that pass go to {@code accepted} (at most the searches per task), the others to
     * {@code rejected} with the reason. Returns why the answer as a whole is unreadable, {@code ""} when it is not.
     */
    String read(String text, Brief brief, Map<String, String> earlier, List<Candidate> accepted,
                List<Rejection> rejected) {
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(text == null ? "" : text.trim());
            if (!element.isJsonObject()) {
                return "the answer is no JSON object";
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            return "the answer is no JSON";
        }
        JsonElement list = json.get("suchen");
        if (list == null || !list.isJsonArray()) {
            return "\"suchen\" must be a list";
        }
        Set<String> own = new HashSet<String>();
        for (Candidate candidate : accepted) {
            own.add(key(candidate.query));
        }
        for (JsonElement element : list.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                return "every search must be an object";
            }
            JsonObject item = element.getAsJsonObject();
            String question = string(item, "frage");
            String query = string(item, "anfrage").replaceAll("\\s+", " ").trim();
            String purpose = cut(string(item, "zweck").replaceAll("\\s+", " ").trim(), PURPOSE_CHARS);
            String reason = check(question, query, purpose, brief, earlier, own);
            boolean strategy = false;
            if (reason == null) {
                reason = strategyRule(question, Parts.of(query), brief);
                strategy = reason != null;
            }
            if (reason == null && accepted.size() >= options.searchesPerTask) {
                reason = say("mehr als " + options.searchesPerTask + " Suchen", "more than "
                        + options.searchesPerTask + " searches");
            }
            if (reason != null) {
                rejected.add(new Rejection(query, reason, question, strategy));
                continue;
            }
            own.add(key(query));
            accepted.add(new Candidate(question, query, purpose));
        }
        return "";
    }

    /** Why a search may not be taken, or {@code null}; the strategy's own rule is {@link #strategyRule}. */
    private String check(String question, String query, String purpose, Brief brief, Map<String, String> earlier,
                         Set<String> own) {
        if (!brief.questionIds().contains(question)) {
            return say("nennt keine Frage der Aufgabe („" + question + "“)", "names no question of the task (\""
                    + question + "\")");
        }
        Parts parts = Parts.of(query);
        if (parts.words.isEmpty()) {
            return say("kein Suchwort", "no search word");
        }
        if (query.length() < ConceptQueryPlan.MIN_QUERY_LENGTH || query.length() > ConceptQueryPlan.MAX_QUERY_LENGTH) {
            return say("Länge außerhalb von " + ConceptQueryPlan.MIN_QUERY_LENGTH + " bis "
                    + ConceptQueryPlan.MAX_QUERY_LENGTH + " Zeichen", "length outside "
                    + ConceptQueryPlan.MIN_QUERY_LENGTH + " to " + ConceptQueryPlan.MAX_QUERY_LENGTH + " characters");
        }
        if (!parts.sites.isEmpty() && brief.strategy != SearchStrategy.GEZIELTE_HERAUSGEBER) {
            return say("site: nur bei der Strategie gezielte_herausgeber", "site: only with the strategy "
                    + "gezielte_herausgeber");
        }
        if (purpose.length() < MIN_PURPOSE_CHARS) {
            return say("ohne Satz, was sie finden soll", "without a sentence on what it should find");
        }
        String key = parts.key();
        if (earlier.containsKey(key)) {
            return say("wiederholt die Suche „" + earlier.get(key) + "“", "repeats the search \"" + earlier.get(key)
                    + "\"");
        }
        if (own.contains(key)) {
            return say("doppelt in dieser Antwort", "twice in this answer");
        }
        return null;
    }

    /**
     * Whether a search keeps to a strategy that asks for something it can be checked on, {@code null} when it does:
     * gezielte_herausgeber needs a {@code site:} with a publisher's address (not the prompt's example), andere_sprache
     * the target language (not the book's: not mostly German words and not mostly the words of the question),
     * andere_wortwahl mostly new words: fewer than half from the question or the earlier searches. The other
     * strategies check nothing here.
     */
    private String strategyRule(String questionId, Parts parts, Brief brief) {
        if (brief.strategy == SearchStrategy.GEZIELTE_HERAUSGEBER) {
            boolean example = false;
            for (String site : parts.sites) {
                if (EXAMPLE_SITES.contains(site)) {
                    example = true;
                } else if (!site.startsWith("-") && site.indexOf('.') > 0) {
                    return null;
                }
            }
            return example ? say("nur die Beispieladresse statt der eines Herausgebers (Strategie "
                    + "gezielte_herausgeber)", "only the example address instead of a publisher's (strategy "
                    + "gezielte_herausgeber)")
                    : say("ohne site: mit der Adresse eines Herausgebers (Strategie gezielte_herausgeber)",
                    "without site: and a publisher's address (strategy gezielte_herausgeber)");
        }
        String questionText = questionText(questionId, brief);
        if (brief.strategy == SearchStrategy.ANDERE_SPRACHE && brief.language != null) {
            List<String> words = new ArrayList<String>();
            for (String word : parts.words) {
                if (!NUMBER.matcher(word).matches()) {
                    words.add(word);
                }
            }
            int germanWords = 0;
            for (String word : words) {
                germanWords += germanLike(word) ? 1 : 0;
            }
            boolean wrong = "de".equals(brief.language[0]) ? germanWords == 0 && !words.isEmpty()
                    : germanWords > 0 && germanWords * 2 >= words.size();
            if (!wrong && !"de".equals(brief.language[0]) && german) {
                wrong = overlap(stems(String.join(" ", parts.words)), stems(questionText)) * 2 > 1;
            }
            if (wrong) {
                return say("nicht auf " + brief.language[1] + " (Strategie andere_sprache)", "not in "
                        + brief.language[2] + " (strategy andere_sprache)");
            }
            return null;
        }
        if (brief.strategy == SearchStrategy.ANDERE_WORTWAHL
                && overlap(stems(String.join(" ", parts.words)), oldStems(questionId, brief)) * 2 >= 1) {
            return say("zu viele Wörter der Frage oder der bisherigen Suchen (Strategie andere_wortwahl)",
                    "too many words of the question or the earlier searches (strategy andere_wortwahl)");
        }
        return null;
    }

    private static String questionText(String questionId, Brief brief) {
        for (QuestionBrief question : brief.questions) {
            if (question.question.getId().equals(questionId)) {
                return question.question.getText();
            }
        }
        return "";
    }

    /** The stems of the question and of the earlier searches: what andere_wortwahl counts as old. */
    private static Set<String> oldStems(String questionId, Brief brief) {
        Set<String> old = stems(questionText(questionId, brief));
        for (SearchRecord search : brief.searches) {
            old.addAll(stems(search.getQuery()));
        }
        return old;
    }

    /**
     * The rule of the task's strategy in the words of a repair, for searches that missed only it: which publishers'
     * addresses the mission names, which words came from the question or the earlier searches, which language.
     */
    String strategyHint(Brief brief, List<Rejection> misses) {
        switch (brief.strategy) {
            case GEZIELTE_HERAUSGEBER: {
                List<String> sites = new ArrayList<String>();
                for (String target : preference.getTargets()) {
                    if (target.startsWith("site:") && target.length() > 5 && sites.size() < 3) {
                        sites.add(target);
                    }
                }
                String named = sites.isEmpty() ? "" : say(", etwa ", ", for example ") + String.join(say(" oder ",
                        " or "), sites);
                return say("Regel der Strategie gezielte_herausgeber: Jede Suche braucht „site:“ mit der "
                        + "Internetadresse eines Herausgebers, der dazu veröffentlicht" + named + "; nennt die Vorgabe "
                        + "einen Herausgeber ohne Adresse, nimm seine bekannte Adresse. Eine Suche ohne site: wird "
                        + "nicht genommen.", "Rule of the strategy gezielte_herausgeber: every search needs \"site:\" "
                        + "with the web address of a publisher that publishes on it" + named + "; when the instruction "
                        + "names a publisher without its address, use its known address. A search without site: is "
                        + "not taken.");
            }
            case ANDERE_WORTWAHL: {
                List<String> taken = new ArrayList<String>();
                for (Rejection miss : misses) {
                    Set<String> old = oldStems(miss.getQuestion(), brief);
                    List<String> words = new ArrayList<String>();
                    for (String word : Parts.of(miss.getQuery()).words) {
                        Set<String> stem = stems(word);
                        if (!stem.isEmpty() && old.contains(stem.iterator().next())) {
                            words.add(word);
                        }
                    }
                    taken.add((german ? "„" : "\"") + cut(miss.getQuery(), 80) + (german ? "“: " : "\": ")
                            + String.join(", ", words));
                }
                return say("Regel der Strategie andere_wortwahl: Die meisten Wörter jeder Suche sind neu, weniger "
                        + "als die Hälfte darf aus der Frage oder den bisherigen Suchen stammen. Übernommen hattest "
                        + "du: " + String.join("; ", taken) + ". Ersetze diese Wörter durch Fachbegriffe, Synonyme, "
                        + "Ober- oder Unterbegriffe oder Begriffe aus den Funden.", "Rule of the strategy "
                        + "andere_wortwahl: most words of every search are new, fewer than half may come from the "
                        + "question or the earlier searches. You took over: " + String.join("; ", taken) + ". Replace "
                        + "these words by technical terms, synonyms, broader or narrower terms or terms from the "
                        + "findings.");
            }
            case ANDERE_SPRACHE: {
                String[] language = brief.language == null ? targetLanguage("") : brief.language;
                return say("Regel der Strategie andere_sprache: Schreibe jede Suche ganz auf " + language[1]
                        + "; übersetze alle Begriffe der Frage, deutsche Wörter nur in Namen und Normen.",
                        "Rule of the strategy andere_sprache: write every search entirely in " + language[2]
                        + "; translate all terms of the question, English words only in names and standards.");
            }
            default:
                return "";
        }
    }

    /** A word with an umlaut or ß, a short German word, or a German ending. */
    static boolean germanLike(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        if (w.matches(".*[äöüß].*") || GERMAN_WORDS.contains(w) || w.contains("tz")) {
            return true;
        }
        for (String[] ending : GERMAN_ENDINGS) {
            if (w.endsWith(ending[0]) && w.length() >= Integer.parseInt(ending[1])) {
                return true;
            }
        }
        return false;
    }

    /** The words of a text that carry meaning, cut to six letters; no numbers and no short German or English words. */
    static Set<String> stems(String text) {
        Set<String> stems = new LinkedHashSet<String>();
        String lower = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (String word : NOT_A_WORD.split(SITE.matcher(lower).replaceAll(" "))) {
            if (word.length() < 3 || NUMBER.matcher(word).matches() || GERMAN_WORDS.contains(word)
                    || ENGLISH_WORDS.contains(word)) {
                continue;
            }
            stems.add(word.length() > 6 ? word.substring(0, 6) : word);
        }
        return stems;
    }

    /** The share of {@code words} that {@code old} holds, 0 for no words. */
    static double overlap(Set<String> words, Set<String> old) {
        if (words.isEmpty()) {
            return 0;
        }
        int shared = 0;
        for (String word : words) {
            shared += old.contains(word) ? 1 : 0;
        }
        return shared / (double) words.size();
    }

    /** The language a search of the strategy andere_sprache goes into: the first other one the text names. */
    String[] targetLanguage(String instruction) {
        String own = german ? "de" : "en";
        String text = instruction == null ? "" : instruction.toLowerCase(Locale.ROOT);
        for (String[] language : LANGUAGES) {
            if (language[0].equals(own)) {
                continue;
            }
            for (int i = 3; i < language.length; i++) {
                if (text.contains(language[i])) {
                    return language;
                }
            }
        }
        return german ? LANGUAGES[0] : LANGUAGES[1];
    }

    private String reasons(List<Rejection> rejected) {
        if (rejected.isEmpty()) {
            return say("die Liste war leer", "the list was empty");
        }
        List<String> parts = new ArrayList<String>();
        for (Rejection rejection : rejected) {
            parts.add("„" + cut(rejection.getQuery(), 80) + "“ " + rejection.getReason());
        }
        return String.join("; ", parts);
    }

    // ------------------------------------------------------------------------------------------------- brief

    /** What the model gets for one task, read from the memory before the round's searches are written. */
    static final class Brief {
        final LeadTask task;
        final SearchStrategy strategy;
        final String mission;
        final String topicName;
        final List<QuestionBrief> questions;
        final List<SearchRecord> searches;
        final List<String> domains;
        final String problem;
        /** For the strategy andere_sprache the target language ({@link #LANGUAGES} row), else {@code null}. */
        String[] language;

        Brief(LeadTask task, String mission, String topicName, List<QuestionBrief> questions,
              List<SearchRecord> searches, List<String> domains, String problem) {
            this.task = task;
            this.strategy = task == null ? SearchStrategy.STANDARD : task.getStrategy();
            this.mission = mission;
            this.topicName = topicName;
            this.questions = questions;
            this.searches = searches;
            this.domains = domains;
            this.problem = problem;
        }

        Set<String> questionIds() {
            Set<String> ids = new LinkedHashSet<String>();
            for (QuestionBrief question : questions) {
                ids.add(question.question.getId());
            }
            return ids;
        }
    }

    /** One question of a task with what the memory holds for it. */
    static final class QuestionBrief {
        final ResearchQuestion question;
        final String topicName;
        final QuestionEvidence evidence;
        final int searches;
        final List<String> quotes;

        QuestionBrief(ResearchQuestion question, String topicName, QuestionEvidence evidence, int searches,
                      List<String> quotes) {
            this.question = question;
            this.topicName = topicName;
            this.evidence = evidence;
            this.searches = searches;
            this.quotes = quotes;
        }
    }

    Brief brief(ResearchMemory memory, LeadTask task, String mission) {
        if (task == null || task.getQuestionIds().isEmpty()) {
            return new Brief(task, mission, "", Collections.<QuestionBrief>emptyList(),
                    Collections.<SearchRecord>emptyList(), Collections.<String>emptyList(), "task without question");
        }
        List<QuestionBrief> questions = new ArrayList<QuestionBrief>();
        Set<String> domains = new LinkedHashSet<String>();
        Set<String> searchIds = new LinkedHashSet<String>();
        List<SearchRecord> searches = new ArrayList<SearchRecord>();
        for (String id : task.getQuestionIds()) {
            ResearchQuestion question = memory.currentQuestion(id);
            if (question == null) {
                return new Brief(task, mission, "", Collections.<QuestionBrief>emptyList(),
                        Collections.<SearchRecord>emptyList(), Collections.<String>emptyList(), "unknown question "
                        + id);
            }
            ResearchTopic topic = question.getTopicId().isEmpty() ? null : memory.currentTopic(question.getTopicId());
            QuestionEvidence evidence = memory.evidenceOf(id);
            List<String> quotes = new ArrayList<String>();
            List<EvidenceVerdict> found = new ArrayList<EvidenceVerdict>(evidence.getDirect());
            found.addAll(evidence.getLeads());
            for (EvidenceVerdict verdict : found) {
                if (!verdict.getQuote().isEmpty() && quotes.size() < options.quotesPerQuestion) {
                    quotes.add(cut(verdict.getQuote().replaceAll("\\s+", " ").trim(), options.quoteChars));
                }
                if (!verdict.getSourceDomain().isEmpty()) {
                    domains.add(verdict.getSourceDomain());
                }
            }
            List<SearchRecord> own = memory.searchesFor(id);
            for (SearchRecord search : own) {
                if (searchIds.add(search.getId())) {
                    searches.add(search);
                }
            }
            questions.add(new QuestionBrief(question, topic == null ? "" : topic.getName(), evidence, own.size(),
                    quotes));
        }
        String topicName = "";
        if (task.getTarget() == LeadDecision.Target.THEMA) {
            ResearchTopic topic = memory.currentTopic(task.getTargetId());
            topicName = topic == null ? task.getTargetId() : topic.getName();
            for (SearchRecord search : memory.searches()) {
                if (search.getTopicIds().contains(task.getTargetId()) && searchIds.add(search.getId())) {
                    searches.add(search);
                }
            }
        }
        Collections.sort(searches, (a, b) -> Integer.compare(b.getRound(), a.getRound()));
        List<SearchRecord> shown = new ArrayList<SearchRecord>(searches.subList(0, Math.min(searches.size(),
                options.shownSearches)));
        List<String> domainList = new ArrayList<String>(domains);
        Brief brief = new Brief(task, mission, topicName, questions, shown, domainList.subList(0, Math.min(
                domainList.size(), DOMAINS_SHOWN)), null);
        if (task.getStrategy() == SearchStrategy.ANDERE_SPRACHE) {
            brief.language = targetLanguage(task.getInstruction());
        }
        return brief;
    }

    // ------------------------------------------------------------------------------------------------- prompt

    String prompt(Brief brief, boolean german, Options options) {
        StringBuilder text = new StringBuilder();
        String m = clean(brief.mission);
        text.append(german ? "Buchauftrag:\n" : "Book mission:\n")
                .append(m.isEmpty() ? (german ? "(keiner angegeben)" : "(none given)") : cut(m, MISSION_CHARS))
                .append("\n\n");
        text.append(german ? "Aufgabe der Rechercheleitung (Runde " : "Task of the research lead (round ")
                .append(brief.task.getRound()).append("):\n");
        text.append(german ? "Strategie: " : "Strategy: ").append(brief.strategy.wire()).append(" (")
                .append(brief.strategy.meaning(german)).append(")\n");
        String instruction = clean(brief.task.getInstruction());
        text.append(german ? "Vorgabe: " : "Instruction: ").append(instruction.isEmpty() ? (german ? "(keine)"
                : "(none)") : cut(instruction, TEXT_CHARS)).append('\n');
        if (brief.language != null) {
            text.append(german ? "Zielsprache: " : "Target language: ").append(brief.language[german ? 1 : 2])
                    .append('\n');
        }
        if (!brief.topicName.isEmpty()) {
            text.append(german ? "Thema: " : "Topic: ").append(brief.topicName).append('\n');
        }
        text.append('\n').append(german ? "Fragen:\n" : "Questions:\n");
        for (QuestionBrief question : brief.questions) {
            text.append("- ").append(question.question.getId());
            if (!question.topicName.isEmpty()) {
                text.append(" [").append(question.topicName).append(']');
            }
            text.append(' ').append(cut(clean(question.question.getText()), TEXT_CHARS)).append("\n  ")
                    .append(question.evidence.summary()).append(german ? "; Suchen bisher: " : "; searches so far: ")
                    .append(question.searches).append('\n');
            for (String quote : question.quotes) {
                text.append(german ? "  Aus einem Fund: „" : "  From a finding: \"").append(quote)
                        .append(german ? "“\n" : "\"\n");
            }
        }
        if (!brief.domains.isEmpty()) {
            text.append(german ? "Quellen der bisherigen Funde: " : "Sources of the findings so far: ")
                    .append(String.join(", ", brief.domains)).append('\n');
        }
        text.append('\n').append(german ? "Bisherige Suchen dazu (nicht wiederholen):\n"
                : "Earlier searches for this (do not repeat):\n");
        if (brief.searches.isEmpty()) {
            text.append(german ? "(noch keine)\n" : "(none yet)\n");
        }
        for (SearchRecord search : brief.searches) {
            text.append("- ").append(german ? "„" : "\"").append(cut(search.getQuery(), 160))
                    .append(german ? "“ (" : "\" (").append(search.getStrategy().isEmpty() ? "standard"
                            : search.getStrategy()).append(german ? ", Runde " : ", round ").append(search.getRound())
                    .append(")");
            SearchRecord.Yield yield = search.getYield();
            if (yield == null) {
                text.append(german ? ": läuft\n" : ": running\n");
            } else {
                text.append(": ").append(yield.getSources()).append(german ? " Quellen, " : " sources, ")
                        .append(yield.getPassages()).append(german ? " Passagen, " : " passages, ")
                        .append(yield.getNewEvidence()).append(german ? " neue Belege\n" : " new pieces of evidence\n");
            }
        }
        if (brief.strategy == SearchStrategy.GEZIELTE_HERAUSGEBER
                || brief.strategy == SearchStrategy.ANDERE_QUELLENART) {
            List<String> preferred = preferred();
            if (!preferred.isEmpty()) {
                text.append('\n').append(german ? "Bevorzugte Quellen laut Auftrag: " : "Sources the mission prefers: ")
                        .append(String.join(", ", preferred)).append('\n');
            }
        }
        text.append('\n');
        int max = options.searchesPerTask;
        if (german) {
            text.append("Schreibe 1 bis ").append(max).append(" Websuchen für diese Aufgabe. Regeln:\n")
                    .append("- Jede Suche gehört zu genau einer Frage von oben; \"frage\" ist ihre Kennung.\n")
                    .append("- Eine Suche ist, was ein Mensch in eine Suchmaschine tippt: 3 bis 10 Wörter, konkrete "
                            + "Begriffe, Namen, Normen, Orte und Jahre; keine ganzen Sätze.\n")
                    .append("- Strategie: ").append(rule(brief, true)).append('\n')
                    .append("- Wiederhole keine bisherige Suche, auch nicht mit denselben Wörtern in anderer "
                            + "Reihenfolge. Die Suchen unterscheiden sich im Blickwinkel, nicht nur in der "
                            + "Wortfolge.\n")
                    .append(brief.strategy == SearchStrategy.GEZIELTE_HERAUSGEBER
                            ? "- Jede Suche braucht \"site:\" mit der Adresse eines Herausgebers, etwa "
                            + "site:herausgeber.de; nennt die Vorgabe einen Herausgeber ohne Adresse, nimm seine "
                            + "bekannte Adresse.\n" : "- Keine Operatoren wie site:, Anführungszeichen oder OR.\n")
                    .append("- \"zweck\" ist ein Satz: was die Suche finden soll.\n\n")
                    .append("Antworte nur mit JSON: {\"suchen\": [{\"frage\": ..., \"zweck\": ..., \"anfrage\": "
                            + "...}]}");
        } else {
            text.append("Write 1 to ").append(max).append(" web searches for this task. Rules:\n")
                    .append("- Every search belongs to exactly one question above; \"frage\" is its id.\n")
                    .append("- A search is what a person types into a search engine: 3 to 10 words, concrete terms, "
                            + "names, standards, places and years; no full sentences.\n")
                    .append("- Strategy: ").append(rule(brief, false)).append('\n')
                    .append("- Repeat no earlier search, not even with the same words in another order. The searches "
                            + "differ in angle, not only in word order.\n")
                    .append(brief.strategy == SearchStrategy.GEZIELTE_HERAUSGEBER
                            ? "- Every search needs \"site:\" with a publisher's address, for example "
                            + "site:publisher.org; when the instruction names a publisher without its address, use "
                            + "its known address.\n" : "- No operators such as site:, quotation marks or OR.\n")
                    .append("- \"zweck\" is one sentence: what the search should find.\n\n")
                    .append("Answer only with JSON: {\"suchen\": [{\"frage\": ..., \"zweck\": ..., \"anfrage\": "
                            + "...}]}");
        }
        return text.toString();
    }

    /** What a strategy asks of the searches. */
    private String rule(Brief brief, boolean german) {
        switch (brief.strategy) {
            case ANDERE_WORTWAHL:
                return german ? "andere Wörter als die bisherigen Suchen: Die meisten Wörter jeder Suche sind neu, "
                        + "weniger als die Hälfte darf aus der Frage oder den bisherigen Suchen stammen. Nimm "
                        + "Fachbegriffe, Synonyme, Ober- und Unterbegriffe und Begriffe aus den Funden, nicht "
                        + "dieselben Wörter umgestellt."
                        : "other words than the earlier searches: most words of every search are new, fewer than half "
                        + "may come from the question or the earlier searches. Use technical terms, synonyms, broader "
                        + "and narrower terms and terms from the findings, not the same words rearranged.";
            case ANDERE_QUELLENART:
                return german ? "nenne eine Art von Dokument, die Belege trägt und bisher fehlt: Studie, Leitfaden, "
                        + "Norm, Statistik, Bericht, Verordnung, Urteil oder PDF."
                        : "name a kind of document that carries evidence and is missing so far: study, guideline, "
                        + "standard, statistics, report, regulation, court ruling or PDF.";
            case GEZIELTE_HERAUSGEBER:
                return german ? "suche bei Herausgebern, die dazu veröffentlichen: Behörden, Unfallversicherungen, "
                        + "Verbände, Normungsgremien, Hochschulen oder Fachverlage, keine Hersteller und Händler."
                        : "search at publishers that publish on it: authorities, accident insurers, associations, "
                        + "standards bodies, universities or specialist publishers, no manufacturers and shops.";
            case ANDERE_SPRACHE:
                String[] language = brief.language == null ? targetLanguage("") : brief.language;
                return german ? "schreibe jede Suche auf " + language[1] + ": übersetze die Begriffe der Frage, "
                        + "deutsche Wörter nur in Namen und Normen."
                        : "write every search in " + language[2] + ": translate the terms of the question, English "
                        + "words only in names and standards.";
            default:
                return german ? "die Frage direkt, mit ihren wichtigsten Begriffen und Begriffen aus den Funden."
                        : "the question directly, with its key terms and terms from the findings.";
        }
    }

    /** The mission's preferred publishers and kinds of document, with their domains where known. */
    private List<String> preferred() {
        List<String> out = new ArrayList<String>();
        for (String term : preference.getTerms()) {
            if (!term.trim().isEmpty() && !out.contains(term.trim())) {
                out.add(term.trim());
            }
        }
        for (String target : preference.getTargets()) {
            String domain = target.startsWith("site:") ? target.substring(5) : "";
            if (!domain.isEmpty() && !out.contains(domain)) {
                out.add(domain);
            }
        }
        return out;
    }

    /** The answer schema of a task: the question ids of the task, the purpose before the query. */
    static String schema(Set<String> questionIds, int max) {
        JsonObject question = type("string");
        JsonArray ids = new JsonArray();
        for (String id : questionIds) {
            ids.add(id);
        }
        question.add("enum", ids);
        JsonObject purpose = type("string");
        purpose.addProperty("minLength", MIN_PURPOSE_CHARS);
        JsonObject query = type("string");
        query.addProperty("minLength", ConceptQueryPlan.MIN_QUERY_LENGTH);
        query.addProperty("maxLength", ConceptQueryPlan.MAX_QUERY_LENGTH);
        JsonObject properties = new JsonObject();
        properties.add("frage", question);
        properties.add("zweck", purpose);
        properties.add("anfrage", query);
        JsonObject item = type("object");
        item.add("properties", properties);
        JsonArray required = new JsonArray();
        required.add("frage");
        required.add("zweck");
        required.add("anfrage");
        item.add("required", required);
        JsonObject list = type("array");
        list.add("items", item);
        list.addProperty("minItems", 1);
        list.addProperty("maxItems", Math.max(1, max));
        JsonObject rootProperties = new JsonObject();
        rootProperties.add("suchen", list);
        JsonObject root = type("object");
        root.add("properties", rootProperties);
        JsonArray rootRequired = new JsonArray();
        rootRequired.add("suchen");
        root.add("required", rootRequired);
        return root.toString();
    }

    // ------------------------------------------------------------------------------------------------- identity

    /**
     * The identity of a search as the search hygiene (component 4) counts it: its words in lower case, sorted, each
     * once, and its {@code site:} restrictions. Two queries with the same key are the same search.
     */
    public static String key(String query) {
        return Parts.of(query == null ? "" : query).key();
    }

    /** A query taken apart: its words and its {@code site:} restrictions ({@code -site:} kept apart). */
    private static final class Parts {
        final TreeSet<String> words = new TreeSet<String>();
        final TreeSet<String> sites = new TreeSet<String>();

        static Parts of(String query) {
            Parts parts = new Parts();
            String text = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            Matcher site = SITE.matcher(text);
            StringBuffer rest = new StringBuffer();
            while (site.find()) {
                String host = host(site.group(2));
                if (!host.isEmpty()) {
                    parts.sites.add(site.group(1) + host);
                }
                site.appendReplacement(rest, " ");
            }
            site.appendTail(rest);
            for (String word : NOT_A_WORD.split(rest.toString())) {
                if (!word.isEmpty()) {
                    parts.words.add(word);
                }
            }
            return parts;
        }

        /** "https://www.BauA.de/x" and "baua.de." both become "baua.de". */
        private static String host(String value) {
            String host = value.trim();
            int scheme = host.indexOf("://");
            if (scheme >= 0) {
                host = host.substring(scheme + 3);
            }
            for (char end : new char[]{'/', '?', '#', '"', '\'', ')'}) {
                int cut = host.indexOf(end);
                if (cut >= 0) {
                    host = host.substring(0, cut);
                }
            }
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        }

        String key() {
            String words = String.join(" ", this.words);
            return sites.isEmpty() ? words : words + " site:" + String.join(",", sites);
        }
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static JsonObject type(String name) {
        JsonObject json = new JsonObject();
        json.addProperty("type", name);
        return json;
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }

    static String cut(String text, int chars) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (flat.length() <= chars) {
            return flat;
        }
        String head = flat.substring(0, chars);
        int blank = head.lastIndexOf(' ');
        return (blank >= chars * 3 / 5 ? head.substring(0, blank) : head).trim() + " …";
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
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
