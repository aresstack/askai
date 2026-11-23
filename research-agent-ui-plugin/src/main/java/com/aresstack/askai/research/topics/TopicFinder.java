package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.memory.PassageNoise;
import com.aresstack.askai.research.memory.PassageText;
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

import java.time.Clock;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Component 13 of the build plan (rev 13, 21.11.2026): the topic finder. A call reads the passages of one loaded source
 * and proposes topics the book does not know yet, also subtopics that narrow a known topic: a short name, one sentence,
 * the passage and a verbatim quote that show the source treats it, and at most two questions. It decides nothing about
 * the Weidezaun (that is the topic check, 14) and merges nothing (that is the merge, 15). A source longer than
 * {@link Options#getWindowChars()} is read in windows of consecutive passages, one call each.
 *
 * <p>The program looks every quote up ({@link PassageText#holdsQuote}): in the passage the model named, else in the
 * other passages of the call (the passage is then corrected), else the proposal is kept with
 * {@link TopicProposal.FindingCheck#NICHT_GEFUNDEN}. Nothing is dropped silently: a proposal without a name and those
 * over {@value #MAX_PROPOSALS} per call are listed with the reason in the window's result.</p>
 *
 * <p>Rules of the build plan: a slot of its own ({@link ResearchModelSlots#PHASE_RESEARCH_TOPICS}), a fixed answer
 * schema, thinking off, temperature 0. A failed call or an unreadable answer gets one repair attempt, then the window
 * is FEHLER with its reason and is offered again later; after {@link Options#STOP_AFTER_FAILURES} failed windows in a
 * row the rest of a batch is not asked (FEHLER too, with the last cause). The finder keeps no state between calls; it
 * is as thread-safe as the injected chat.</p>
 */
public final class TopicFinder {

    /** At most this many proposals per call. */
    public static final int MAX_PROPOSALS = 5;

    /** One passage of a source; immutable. */
    public static final class SourcePassage {
        private final String passageId;
        private final String text;

        public SourcePassage(String passageId, String text) {
            this.passageId = passageId == null ? "" : passageId.trim();
            this.text = text == null ? "" : text;
            if (this.passageId.isEmpty()) {
                throw new IllegalArgumentException("a passage needs an id");
            }
        }

        public String getPassageId() {
            return passageId;
        }

        public String getText() {
            return text;
        }
    }

    /** A loaded source with its passages in reading order; immutable. */
    public static final class Source {
        private final String sourceId;
        private final String address;
        private final String title;
        private final String domain;
        private final List<SourcePassage> passages;

        /**
         * @param domain the host as shown to the model; empty to take it from the address
         */
        public Source(String sourceId, String address, String title, String domain, List<SourcePassage> passages) {
            this.sourceId = sourceId == null ? "" : sourceId.trim();
            this.address = address == null ? "" : address.trim();
            this.title = title == null ? "" : title.trim();
            String given = domain == null ? "" : domain.trim();
            this.domain = given.isEmpty() ? hostOf(this.address) : given;
            this.passages = Collections.unmodifiableList(new ArrayList<SourcePassage>(
                    passages == null ? Collections.<SourcePassage>emptyList() : passages));
            if (this.sourceId.isEmpty()) {
                throw new IllegalArgumentException("a source needs an id");
            }
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getAddress() {
            return address;
        }

        public String getTitle() {
            return title;
        }

        public String getDomain() {
            return domain;
        }

        public List<SourcePassage> getPassages() {
            return passages;
        }
    }

    /** The passages of a source one call reads; immutable. */
    public static final class Window {
        private final Source source;
        private final int index;
        private final int count;
        private final List<SourcePassage> passages;

        Window(Source source, int index, int count, List<SourcePassage> passages) {
            this.source = source;
            this.index = index;
            this.count = count;
            this.passages = Collections.unmodifiableList(new ArrayList<SourcePassage>(passages));
        }

        public Source getSource() {
            return source;
        }

        /** 1 for the first window of the source. */
        public int getIndex() {
            return index;
        }

        /** How many windows the source has. */
        public int getCount() {
            return count;
        }

        public List<SourcePassage> getPassages() {
            return passages;
        }

        /** Source and passages, as {@link WindowResult#windowKey()} gives it for the result of this window. */
        public String key() {
            StringBuilder key = new StringBuilder(source.getSourceId());
            for (SourcePassage passage : passages) {
                key.append('\n').append(passage.getPassageId());
            }
            return key.toString();
        }
    }

    /** What one call gave: the proposals, or why there are none; immutable. */
    public static final class WindowResult {
        /** OK also when the source holds nothing new (no proposals). */
        public enum Status { OK, FEHLER }

        private final String sourceId;
        private final String sourceUrl;
        private final int windowIndex;
        private final List<String> passageIds;
        private final Status status;
        private final String problem;
        private final List<TopicProposal> proposals;
        private final List<String> dropped;
        private final List<String> answers;
        private final String model;
        private final int round;
        private final long timeMillis;
        private final long millis;

        public WindowResult(String sourceId, String sourceUrl, int windowIndex, List<String> passageIds,
                            Status status, String problem, List<TopicProposal> proposals, List<String> dropped,
                            List<String> answers, String model, int round, long timeMillis, long millis) {
            this.sourceId = sourceId == null ? "" : sourceId.trim();
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl.trim();
            this.windowIndex = windowIndex;
            this.passageIds = copy(passageIds);
            this.status = status == null ? Status.FEHLER : status;
            this.problem = problem == null ? "" : problem.trim();
            this.proposals = Collections.unmodifiableList(new ArrayList<TopicProposal>(
                    proposals == null ? Collections.<TopicProposal>emptyList() : proposals));
            this.dropped = copy(dropped);
            this.answers = copy(answers);
            this.model = model == null ? "" : model.trim();
            this.round = Math.max(1, round);
            this.timeMillis = timeMillis;
            this.millis = millis;
            if (this.status == Status.FEHLER && !this.proposals.isEmpty()) {
                throw new IllegalArgumentException("a failed window holds no proposals");
            }
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public int getWindowIndex() {
            return windowIndex;
        }

        /** The passages the call read, in order. */
        public List<String> getPassageIds() {
            return passageIds;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** For FEHLER, what went wrong. */
        public String getProblem() {
            return problem;
        }

        public List<TopicProposal> getProposals() {
            return proposals;
        }

        /** Proposals of the answer that were not taken, each with the reason. */
        public List<String> getDropped() {
            return dropped;
        }

        /** The raw answer of every call (a failed call adds ""). */
        public List<String> getAnswers() {
            return answers;
        }

        public String getModel() {
            return model;
        }

        public int getRound() {
            return round;
        }

        public long getTimeMillis() {
            return timeMillis;
        }

        /** The time of the calls; 0 when the window was not asked. */
        public long getMillis() {
            return millis;
        }

        /** Source and passages: the same key means the same text was read. */
        public String windowKey() {
            return sourceId + "\n" + String.join("\n", passageIds);
        }

        private static List<String> copy(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<String>(
                    values == null ? Collections.<String>emptyList() : values));
        }
    }

    /** How the finder works; immutable. */
    public static final class Options {
        /** Default calls at the same time (to be measured per role). */
        public static final int DEFAULT_PARALLEL = 4;
        /** Default characters of passage text per call; a longer source is read in several windows. */
        public static final int DEFAULT_WINDOW_CHARS = 6000;
        public static final int MIN_WINDOW_CHARS = 2000;
        /** Default characters of one passage in the prompt; longer passages are cut. */
        public static final int DEFAULT_PASSAGE_CHARS = 1500;
        public static final int MIN_PASSAGE_CHARS = 300;
        /** After this many failed windows in a row the rest of a batch is not asked. */
        public static final int STOP_AFTER_FAILURES = 5;

        private final int parallel;
        private final int windowChars;
        private final int passageChars;

        public Options(int parallel, int windowChars, int passageChars) {
            if (parallel < 1 || windowChars < MIN_WINDOW_CHARS || passageChars < MIN_PASSAGE_CHARS
                    || passageChars > windowChars) {
                throw new IllegalArgumentException("at least one call at a time, " + MIN_WINDOW_CHARS
                        + " characters per window and " + MIN_PASSAGE_CHARS + " per passage, a passage within a "
                        + "window");
            }
            this.parallel = parallel;
            this.windowChars = windowChars;
            this.passageChars = passageChars;
        }

        public static Options defaults() {
            return new Options(DEFAULT_PARALLEL, DEFAULT_WINDOW_CHARS, DEFAULT_PASSAGE_CHARS);
        }

        public int getParallel() {
            return parallel;
        }

        public int getWindowChars() {
            return windowChars;
        }

        public int getPassageChars() {
            return passageChars;
        }
    }

    /** The role: its own slot under Sources, a fixed schema, thinking off. */
    public static final ResearchRole ROLE = ResearchRole.builder("topic-finder",
                    ResearchModelSlots.PHASE_RESEARCH_TOPICS)
            .purpose("component 13: reads a loaded source and proposes topics the book does not know yet, each with "
                    + "passage, verbatim quote and at most two questions; decides nothing about the Weidezaun")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(), "vorschlaege: passage, zitat, name, beschreibung, fragen"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.0;
    /** Answer budget: five proposals with quote, name, sentence and two questions. */
    static final int ANSWER_TOKENS = 1200;
    /** Calls per window: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 2000;
    private static final int KNOWN_TOPICS = 80;
    private static final int NAME_CHARS = 120;
    private static final int DESCRIPTION_CHARS = 400;
    private static final int QUOTE_CHARS = 600;
    private static final int QUESTION_CHARS = 300;

    private final MainModelChat chat;
    private final String mission;
    private final boolean german;
    private final Options options;
    private final Clock clock;
    private final Consumer<String> log;
    private PassageNoise noise = PassageNoise.NONE;

    /**
     * @param chat     the transport of the slot ({@link #ROLE})
     * @param mission  the book's mission as the user wrote it
     * @param language the working language ({@code "en"} for English prompts; anything else reads German)
     * @param options  the defaults when {@code null}
     * @param log      progress and errors; may be {@code null}
     */
    public TopicFinder(MainModelChat chat, String mission, String language, Options options, Consumer<String> log) {
        this(chat, mission, language, options, log, Clock.systemUTC());
    }

    TopicFinder(MainModelChat chat, String mission, String language, Options options, Consumer<String> log,
                Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the topic finder needs a chat");
        }
        this.chat = chat;
        this.mission = mission == null ? "" : mission.trim();
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
        this.clock = clock;
    }

    /**
     * Which passages are page leftovers (the marks of the page-noise check before this finder); {@code null} is
     * {@link PassageNoise#NONE}. Leftovers are left out of the windows from now on.
     */
    public synchronized TopicFinder useNoise(PassageNoise noise) {
        this.noise = noise == null ? PassageNoise.NONE : noise;
        return this;
    }

    /**
     * The windows of a source: consecutive passages, each cut to {@link Options#getPassageChars()}, until the next one
     * would pass {@link Options#getWindowChars()}. Passages without text are left out; a source without text has no
     * window.
     */
    public static List<Window> windowsOf(Source source, Options options) {
        return windowsOf(source, options, PassageNoise.NONE);
    }

    /** {@link #windowsOf(Source, Options)} without the passages {@code noise} marks as page leftovers. */
    public static List<Window> windowsOf(Source source, Options options, PassageNoise noise) {
        List<List<SourcePassage>> groups = new ArrayList<List<SourcePassage>>();
        List<SourcePassage> current = new ArrayList<SourcePassage>();
        int chars = 0;
        for (SourcePassage passage : source.getPassages()) {
            String text = passage.getText().trim();
            if (text.isEmpty() || noise.isNoise(PassageText.key(passage.getText()))) {
                continue;
            }
            int length = Math.min(text.length(), options.getPassageChars());
            if (!current.isEmpty() && chars + length > options.getWindowChars()) {
                groups.add(current);
                current = new ArrayList<SourcePassage>();
                chars = 0;
            }
            current.add(passage);
            chars += length;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        List<Window> windows = new ArrayList<Window>();
        for (int i = 0; i < groups.size(); i++) {
            windows.add(new Window(source, i + 1, groups.size(), groups.get(i)));
        }
        return windows;
    }

    /**
     * The windows still to read: every window of the given sources that has no OK result among {@code done} (results
     * of earlier calls, for instance from {@link TopicProposalLog}). That is a new source, a window that failed and a
     * window a breaker left unasked. A window counts as read when a result has the same source and passages; after a
     * change of {@link Options} the windows of a source can differ, and the source is read again (the merge, 15, takes
     * repeated proposals together).
     */
    public List<Window> unread(List<Source> sources, List<WindowResult> done) {
        Set<String> read = new LinkedHashSet<String>();
        if (done != null) {
            for (WindowResult result : done) {
                if (result.isOk()) {
                    read.add(result.windowKey());
                }
            }
        }
        List<Window> open = new ArrayList<Window>();
        for (Source source : sources) {
            for (Window window : windowsOf(source, options, currentNoise())) {
                if (!read.contains(window.key())) {
                    open.add(window);
                }
            }
        }
        return open;
    }

    private synchronized PassageNoise currentNoise() {
        return noise;
    }

    /** One window: at most {@link #ATTEMPTS} calls. */
    public WindowResult read(Window window, List<String> knownTopics, int round) {
        Source source = window.getSource();
        List<String> known = known(knownTopics);
        List<String> texts = new ArrayList<String>();
        for (SourcePassage passage : window.getPassages()) {
            texts.add(cut(passage.getText().trim(), options.getPassageChars()));
        }
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(mission, known, source.getTitle(), source.getDomain(), window.getIndex(),
                window.getCount(), texts, german)));
        List<String> answers = new ArrayList<String>();
        String problem = "";
        long start = clock.millis();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, ANSWER_TOKENS,
                        schema());
            } catch (RuntimeException failed) {
                result = MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE,
                        failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage());
            }
            if (result == null || !result.isOk()) {
                answers.add("");
                problem = result == null ? "model call returned nothing"
                        : "model call failed (" + result.getStatus() + "): " + result.getDetail();
                continue; // the same request once more
            }
            String text = result.getText() == null ? "" : result.getText();
            answers.add(text);
            Parsed parsed = parse(text);
            if (parsed.problem.isEmpty()) {
                List<String> dropped = new ArrayList<String>(parsed.dropped);
                List<TopicProposal> proposals = proposals(window, parsed, dropped, round);
                return new WindowResult(source.getSourceId(), source.getAddress(), window.getIndex(),
                        passageIds(window), WindowResult.Status.OK, "", proposals, dropped, answers,
                        chat.modelName(), round, clock.millis(), clock.millis() - start);
            }
            problem = "invalid answer: " + parsed.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + parsed.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("topic finder failed for " + source.getSourceId() + " window " + window.getIndex() + ": " + problem);
        return failed(window, problem, answers, round, clock.millis() - start);
    }

    /**
     * Every window of every source, {@link Options#getParallel()} calls at a time. After
     * {@link Options#STOP_AFTER_FAILURES} failed windows in a row the remaining windows are not asked.
     *
     * @return one result per window, sources in the given order, windows in reading order
     */
    public List<WindowResult> readAll(List<Source> sources, final List<String> knownTopics, final int round) {
        List<Window> windows = new ArrayList<Window>();
        for (Source source : sources) {
            windows.addAll(windowsOf(source, options));
        }
        return readWindows(windows, knownTopics, round);
    }

    /** The given windows, {@link Options#getParallel()} at a time, in the given order (for a retry). */
    public List<WindowResult> readWindows(List<Window> windows, final List<String> knownTopics, final int round) {
        List<WindowResult> results = new ArrayList<WindowResult>(windows.size());
        if (windows.isEmpty()) {
            return results;
        }
        final AtomicInteger failuresInRow = new AtomicInteger();
        final AtomicReference<String> lastFailure = new AtomicReference<String>("");
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(options.getParallel(), windows.size()));
        List<Future<WindowResult>> running = new ArrayList<Future<WindowResult>>();
        try {
            for (final Window window : windows) {
                running.add(pool.submit(() -> {
                    if (failuresInRow.get() >= Options.STOP_AFTER_FAILURES) {
                        return failed(window, "not asked: the model failed " + Options.STOP_AFTER_FAILURES
                                + " windows in a row; last: " + lastFailure.get(), Collections.<String>emptyList(),
                                round, 0L);
                    }
                    WindowResult result = read(window, knownTopics, round);
                    if (result.isOk()) {
                        failuresInRow.set(0);
                    } else {
                        lastFailure.set(result.getProblem());
                        failuresInRow.incrementAndGet();
                    }
                    return result;
                }));
            }
            for (Future<WindowResult> future : running) {
                results.add(future.get());
            }
        } catch (ExecutionException crashed) {
            throw new IllegalStateException("topic finder crashed", crashed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("topic finder interrupted", interrupted);
        } finally {
            pool.shutdownNow();
        }
        int proposals = 0;
        int failed = 0;
        for (WindowResult result : results) {
            proposals += result.getProposals().size();
            failed += result.isOk() ? 0 : 1;
        }
        log("topic finder: " + windows.size() + " windows, " + proposals + " proposals, " + failed + " failed");
        return results;
    }

    private WindowResult failed(Window window, String problem, List<String> answers, int round, long millis) {
        return new WindowResult(window.getSource().getSourceId(), window.getSource().getAddress(), window.getIndex(),
                passageIds(window), WindowResult.Status.FEHLER, problem, null, null, answers, chat.modelName(), round,
                clock.millis(), millis);
    }

    /** The proposals of a readable answer, each quote looked up in the passages the call read. */
    private List<TopicProposal> proposals(Window window, Parsed parsed, List<String> dropped, int round) {
        List<TopicProposal> out = new ArrayList<TopicProposal>();
        List<SourcePassage> passages = window.getPassages();
        Source source = window.getSource();
        for (int i = 0; i < parsed.items.size(); i++) {
            Item item = parsed.items.get(i);
            String passageId = "";
            TopicProposal.FindingCheck check = TopicProposal.FindingCheck.NICHT_GEFUNDEN;
            if (item.passage >= 1 && item.passage <= passages.size()) {
                passageId = passages.get(item.passage - 1).getPassageId();
                if (PassageText.holdsQuote(passages.get(item.passage - 1).getText(), item.quote)) {
                    check = TopicProposal.FindingCheck.GEPRUEFT;
                }
            }
            if (check == TopicProposal.FindingCheck.NICHT_GEFUNDEN) {
                for (SourcePassage passage : passages) {
                    if (PassageText.holdsQuote(passage.getText(), item.quote)) {
                        passageId = passage.getPassageId();
                        check = TopicProposal.FindingCheck.KORRIGIERT;
                        break;
                    }
                }
            }
            List<String> questions = item.questions;
            if (questions.size() > TopicProposal.MAX_QUESTIONS) {
                dropped.add("\"" + item.name + "\": " + (questions.size() - TopicProposal.MAX_QUESTIONS)
                        + " question(s) over " + TopicProposal.MAX_QUESTIONS);
                questions = questions.subList(0, TopicProposal.MAX_QUESTIONS);
            }
            out.add(new TopicProposal(proposalId(round, source.getSourceId(), window.getIndex(), i + 1),
                    source.getSourceId(), source.getAddress(), passageId, item.quote, check, item.name,
                    item.description, questions, chat.modelName(), round, clock.millis()));
        }
        return out;
    }

    /** Round, source, window and place in the answer: unique within a project. */
    static String proposalId(int round, String sourceId, int window, int number) {
        return "vs-r" + Math.max(1, round) + "-" + sourceId + "-w" + window + "-" + number;
    }

    private static List<String> passageIds(Window window) {
        List<String> ids = new ArrayList<String>();
        for (SourcePassage passage : window.getPassages()) {
            ids.add(passage.getPassageId());
        }
        return ids;
    }

    /** Known topic names, each once, at most {@value #KNOWN_TOPICS}. */
    static List<String> known(List<String> names) {
        Set<String> kept = new LinkedHashSet<String>();
        if (names != null) {
            for (String name : names) {
                String text = name == null ? "" : cut(name.replaceAll("\\s+", " ").trim(), NAME_CHARS);
                if (!text.isEmpty() && kept.size() < KNOWN_TOPICS) {
                    kept.add(text);
                }
            }
        }
        return new ArrayList<String>(kept);
    }

    private void log(String line) {
        if (log != null) {
            log.accept(line);
        }
    }

    static final String SYSTEM_DE = "Du liest Quellen für ein Sachbuch und schlägst Themen vor, die das Buch noch "
            + "nicht kennt. Du arbeitest nur mit dem vorgelegten Text und erfindest nichts. Antworte nur mit JSON.";

    static final String SYSTEM_EN = "You read sources for a non-fiction book and propose topics the book does not know "
            + "yet. You work only with the text given and invent nothing. Answer only with JSON.";

    static String prompt(String mission, List<String> known, String title, String domain, int window, int windows,
                         List<String> passages, boolean german) {
        String task = cut(mission.replaceAll("\\s+", " ").trim(), MISSION_CHARS);
        StringBuilder list = new StringBuilder();
        for (String name : known) {
            list.append("- ").append(name).append('\n');
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < passages.size(); i++) {
            text.append('[').append(i + 1).append("] ").append(passages.get(i)).append("\n\n");
        }
        String label = (title.isEmpty() ? "" : title + " ") + "(" + (domain.isEmpty() ? (german ? "unbekannt"
                : "unknown") : domain) + ")";
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Bekannte Themen des Buchs:\n" + (known.isEmpty() ? "(noch keine)\n" : list.toString()) + "\n"
                    + "Quelle: " + label + "\n"
                    + "Passagen der Quelle" + (windows > 1 ? " (Teil " + window + " von " + windows + ")" : "")
                    + ":\n" + text
                    + "Aufgabe: Welche Themen behandelt diese Quelle, die unter den bekannten Themen fehlen? Gemeint "
                    + "sind auch Unterthemen, die ein bekanntes Thema genauer fassen. Schlage höchstens "
                    + MAX_PROPOSALS + " Themen vor, das wichtigste zuerst. Für jedes Thema:\n"
                    + "1. passage: die Nummer der Passage, in der das Thema am deutlichsten behandelt wird.\n"
                    + "2. zitat: ein wörtliches Zitat aus dieser Passage (höchstens 25 Wörter), das zeigt, dass die "
                    + "Quelle das Thema behandelt. Übernimm die Wörter genau so, wie sie dastehen.\n"
                    + "3. name: ein kurzer Name für das Thema (höchstens 8 Wörter), wie eine Abschnittsüberschrift.\n"
                    + "4. beschreibung: ein Satz, worum es bei dem Thema geht.\n"
                    + "5. fragen: höchstens zwei Fragen, die das Buch zu diesem Thema beantworten könnte.\n\n"
                    + "Ob ein Thema ins Buch gehört, entscheidet später eine eigene Prüfung. Lass ein Thema deshalb "
                    + "nicht weg, nur weil es am Rand oder außerhalb des Auftrags zu liegen scheint. Schlage kein "
                    + "bekanntes Thema noch einmal vor, auch nicht unter anderem Namen. Seitenreste wie Navigation, "
                    + "Impressum, Cookie-Hinweise, Werbung oder Literaturlisten sind kein Thema. Behandelt die Quelle "
                    + "nichts, was fehlt, gib eine leere Liste zurück.\n\n"
                    + "Antworte nur mit JSON mit dem Feld \"vorschlaege\": einer Liste von Objekten mit den Feldern "
                    + "\"passage\", \"zitat\", \"name\", \"beschreibung\" und \"fragen\".";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "Known topics of the book:\n" + (known.isEmpty() ? "(none yet)\n" : list.toString()) + "\n"
                + "Source: " + label + "\n"
                + "Passages of the source" + (windows > 1 ? " (part " + window + " of " + windows + ")" : "")
                + ":\n" + text
                + "Task: Which topics does this source treat that are missing from the known topics? This includes "
                + "subtopics that narrow a known topic. Propose at most " + MAX_PROPOSALS + " topics, the most "
                + "important first. For each topic:\n"
                + "1. passage: the number of the passage that treats the topic most clearly.\n"
                + "2. zitat: a verbatim quote from that passage (at most 25 words) that shows the source treats the "
                + "topic. Copy the words exactly as they stand.\n"
                + "3. name: a short name for the topic (at most 8 words), like a section heading.\n"
                + "4. beschreibung: one sentence on what the topic is about.\n"
                + "5. fragen: at most two questions the book could answer on this topic.\n\n"
                + "Whether a topic belongs in the book is decided later by a check of its own. So do not leave a "
                + "topic out only because it seems to lie at the edge of the mission or outside it. Do not propose a "
                + "known topic again, not under another name either. Page leftovers such as navigation, imprint, "
                + "cookie notices, advertising or reference lists are no topic. If the source treats nothing that is "
                + "missing, return an empty list.\n\n"
                + "Answer only with JSON with the field \"vorschlaege\": a list of objects with the fields "
                + "\"passage\", \"zitat\", \"name\", \"beschreibung\" and \"fragen\".";
    }

    /** At most {@value #MAX_PROPOSALS} proposals, each with all five fields; nothing else allowed. */
    static String schema() {
        JsonObject string = new JsonObject();
        string.addProperty("type", "string");
        JsonObject integer = new JsonObject();
        integer.addProperty("type", "integer");
        JsonObject questions = new JsonObject();
        questions.addProperty("type", "array");
        questions.addProperty("maxItems", TopicProposal.MAX_QUESTIONS);
        questions.add("items", string.deepCopy());
        JsonObject properties = new JsonObject();
        properties.add("passage", integer);
        properties.add("zitat", string.deepCopy());
        properties.add("name", string.deepCopy());
        properties.add("beschreibung", string.deepCopy());
        properties.add("fragen", questions);
        JsonArray required = new JsonArray();
        for (String field : new String[]{"passage", "zitat", "name", "beschreibung", "fragen"}) {
            required.add(field);
        }
        JsonObject item = new JsonObject();
        item.addProperty("type", "object");
        item.add("properties", properties);
        item.add("required", required);
        item.addProperty("additionalProperties", false);
        JsonObject list = new JsonObject();
        list.addProperty("type", "array");
        list.addProperty("maxItems", MAX_PROPOSALS);
        list.add("items", item);
        JsonObject rootProperties = new JsonObject();
        rootProperties.add("vorschlaege", list);
        JsonArray rootRequired = new JsonArray();
        rootRequired.add("vorschlaege");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", rootProperties);
        schema.add("required", rootRequired);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /** One proposal as the model wrote it. */
    static final class Item {
        int passage;
        String quote = "";
        String name = "";
        String description = "";
        List<String> questions = new ArrayList<String>();
    }

    /** The proposals read from an answer, those not taken with the reason, or why the answer holds none. */
    static final class Parsed {
        final List<Item> items = new ArrayList<Item>();
        final List<String> dropped = new ArrayList<String>();
        String problem = "";
    }

    static Parsed parse(String answer) {
        Parsed parsed = new Parsed();
        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!element.isJsonObject()) {
                parsed.problem = "the answer is no JSON object";
                return parsed;
            }
            json = element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            parsed.problem = "the answer is no JSON";
            return parsed;
        }
        JsonElement list = json.get("vorschlaege");
        if (list == null || !list.isJsonArray()) {
            parsed.problem = "\"vorschlaege\" missing or not a list";
            return parsed;
        }
        int position = 0;
        for (JsonElement element : list.getAsJsonArray()) {
            position++;
            if (!element.isJsonObject()) {
                parsed.dropped.add("proposal " + position + ": not an object");
                continue;
            }
            JsonObject o = element.getAsJsonObject();
            Item item = new Item();
            item.name = cut(text(o, "name"), NAME_CHARS);
            if (item.name.isEmpty()) {
                parsed.dropped.add("proposal " + position + ": no name");
                continue;
            }
            if (parsed.items.size() >= MAX_PROPOSALS) {
                parsed.dropped.add("\"" + item.name + "\": over " + MAX_PROPOSALS + " proposals");
                continue;
            }
            item.passage = number(o.get("passage"));
            item.quote = cut(text(o, "zitat"), QUOTE_CHARS);
            item.description = cut(text(o, "beschreibung"), DESCRIPTION_CHARS);
            JsonElement questions = o.get("fragen");
            if (questions != null && questions.isJsonArray()) {
                Set<String> seen = new LinkedHashSet<String>();
                for (JsonElement question : questions.getAsJsonArray()) {
                    String q = question.isJsonPrimitive()
                            ? cut(question.getAsString().replaceAll("\\s+", " ").trim(), QUESTION_CHARS) : "";
                    if (!q.isEmpty()) {
                        seen.add(q);
                    }
                }
                item.questions.addAll(seen);
            }
            parsed.items.add(item);
        }
        return parsed;
    }

    /** A passage number as the model wrote it ({@code 3}, {@code "3"}, {@code "[3]"}); 0 when there is none. */
    static int number(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) {
            return 0;
        }
        String digits = value.getAsString().replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 6) {
            return 0;
        }
        return Integer.parseInt(digits);
    }

    /** The host of an address, lower case, without {@code www.}. */
    static String hostOf(String address) {
        String rest = address == null ? "" : address.trim().replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        int end = rest.length();
        for (char stop : new char[]{'/', '?', '#', ' '}) {
            int at = rest.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String host = rest.substring(0, end);
        host = host.substring(host.lastIndexOf('@') + 1);
        int port = host.indexOf(':');
        if (port >= 0) {
            host = host.substring(0, port);
        }
        return host.toLowerCase(Locale.ROOT).replaceFirst("^www\\d*\\.", "");
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value != null && value.isJsonPrimitive() ? value.getAsString().replaceAll("\\s+", " ").trim() : "";
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max).trim() + " …";
    }

    /** Proposals of the results by source, in order (for reports). */
    public static Map<String, List<TopicProposal>> bySource(List<WindowResult> results) {
        Map<String, List<TopicProposal>> out = new LinkedHashMap<String, List<TopicProposal>>();
        for (WindowResult result : results) {
            List<TopicProposal> list = out.get(result.getSourceId());
            if (list == null) {
                list = new ArrayList<TopicProposal>();
                out.put(result.getSourceId(), list);
            }
            list.addAll(result.getProposals());
        }
        return out;
    }
}
