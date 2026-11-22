package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.scope.ResearchScopeDraft;
import com.aresstack.askai.research.domain.scope.ScopeFacet;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.memory.ResearchTopic.Fence;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
 * Component 14, the fence check (Themenprüfung am Weidezaun): one call judges one merged topic against the mission,
 * the edge of the subject and the cards of the mind map, and answers INNEN, AUSSEN or UNKLAR, always with a reason.
 *
 * <ul>
 *   <li>INNEN: the topic belongs in the book. It need not match a card: the concept gives the direction, not the
 *       finished content, so a new topic inside the mission's subject is INNEN.</li>
 *   <li>AUSSEN: another subject. It stops only further search on the topic; its sources and passages stay in the
 *       stock.</li>
 *   <li>UNKLAR: at the edge of the subject or not enough to tell. It goes to the author as a question
 *       ({@link #questions}) and blocks nothing.</li>
 * </ul>
 *
 * <p>A topic is judged once, and again when the mission, the edge, the scope or the cards change (the
 * {@link Brief#basis() basis} of a verdict) or the topic's own name and description change. A subtopic is judged on
 * its own and inherits nothing from its parent; the prompt does not name the parent, so a card does not read as a
 * hint for INNEN. The cards themselves (origin KONZEPT) are inside by definition and are not judged. A decision of
 * the author ({@link #byAuthor}) holds until the author changes it, whatever the basis.</p>
 *
 * <p>Rules of the build plan: a slot of its own ({@link ResearchModelSlots#PHASE_RESEARCH_FENCE}), a fixed answer
 * schema with the reason first, thinking off, temperature 0, one repair attempt. A failed call gives FEHLER with its
 * cause, never a verdict; after five failures in a row the rest is not asked, with the last cause. Topics are judged
 * in parallel, the order of the verdicts is the order of the topics.</p>
 */
public final class TopicFenceCheck {

    /** Tuning; immutable. */
    public static final class Options {
        /** Failures in a row after which the rest of a batch is not asked. */
        public static final int STOP_AFTER_FAILURES = 5;

        private final int parallel;
        private final int excerpts;
        private final int excerptChars;

        private Options(int parallel, int excerpts, int excerptChars) {
            if (parallel < 1 || excerpts < 0 || excerptChars < 40) {
                throw new IllegalArgumentException("parallel >= 1, excerpts >= 0 and excerptChars >= 40 expected");
            }
            this.parallel = parallel;
            this.excerpts = excerpts;
            this.excerptChars = excerptChars;
        }

        /** Four at a time; four findings of up to 300 characters per topic. */
        public static Options defaults() {
            return new Options(4, 4, 300);
        }

        public Options withParallel(int newParallel) {
            return new Options(newParallel, excerpts, excerptChars);
        }

        public Options withExcerpts(int count, int chars) {
            return new Options(parallel, count, chars);
        }

        public int getParallel() {
            return parallel;
        }

        /** Findings shown per topic. */
        public int getExcerpts() {
            return excerpts;
        }

        /** Characters per finding shown. */
        public int getExcerptChars() {
            return excerptChars;
        }
    }

    /**
     * What a topic is judged against: the mission, the edge of the subject (where the mission says where its subject
     * ends, decision E12), the lines of the scope (included, excluded, place, time) and the cards of the mind map.
     * Immutable; empty parts are left out of the prompt.
     */
    public static final class Brief {
        private final String mission;
        private final String edge;
        private final List<String> included;
        private final List<String> excluded;
        private final String place;
        private final String time;
        private final List<String> cards;

        public Brief(String mission, String edge, List<String> included, List<String> excluded, String place,
                     String time, List<String> cards) {
            this.mission = clean(mission);
            this.edge = clean(edge);
            this.included = cleanAll(included);
            this.excluded = cleanAll(excluded);
            this.place = clean(place);
            this.time = clean(time);
            this.cards = cleanAll(cards);
        }

        /** Only the mission and the cards. */
        public static Brief of(String mission, List<String> cards) {
            return new Brief(mission, "", null, null, "", "", cards);
        }

        /**
         * From the scope of Phase 1: its mission, the confirmed facets as included (a provisional facet is a
         * hypothesis, not a boundary), the excluded facets and the exclusions in the user's words as excluded, the
         * place and the time. The edge is given separately as long as the scope has no field for it.
         */
        public static Brief of(ResearchScopeDraft scope, String edge, List<String> cards) {
            List<String> included = new ArrayList<String>();
            List<String> excluded = new ArrayList<String>();
            for (ScopeFacet facet : scope.getFacets()) {
                if (facet.getStatus() == ScopeFacet.Status.CONFIRMED) {
                    included.add(facet.getLabel());
                } else if (facet.isExcluded()) {
                    excluded.add(facet.getLabel());
                }
            }
            excluded.addAll(scope.getExclusions());
            return new Brief(scope.getMission(), edge, included, excluded, scope.getGeographicScope(),
                    scope.getTemporalScope(), cards);
        }

        public String getMission() {
            return mission;
        }

        public String getEdge() {
            return edge;
        }

        public List<String> getIncluded() {
            return included;
        }

        public List<String> getExcluded() {
            return excluded;
        }

        public String getPlace() {
            return place;
        }

        public String getTime() {
            return time;
        }

        public List<String> getCards() {
            return cards;
        }

        /** The fingerprint of everything a verdict depends on besides the topic: 16 hex digits of SHA-256. */
        public String basis() {
            StringBuilder text = new StringBuilder();
            text.append("auftrag\n").append(mission).append("\nrand\n").append(edge);
            text.append("\ndazu\n").append(String.join("\n", included));
            text.append("\nnicht\n").append(String.join("\n", excluded));
            text.append("\nraum\n").append(place).append("\nzeit\n").append(time);
            text.append("\nkarten\n").append(String.join("\n", cards));
            return hash(text.toString());
        }
    }

    /** One topic as the check reads it: name, description and a few findings (quotes from the sources). */
    public static final class Subject {
        private final String topicId;
        private final String name;
        private final String description;
        private final List<String> excerpts;

        public Subject(String topicId, String name, String description, List<String> excerpts) {
            this.topicId = clean(topicId);
            this.name = clean(name).replaceAll("\\s+", " ");
            this.description = clean(description).replaceAll("\\s+", " ");
            this.excerpts = cleanAll(excerpts);
            if (this.topicId.isEmpty()) {
                throw new IllegalArgumentException("a subject needs the topic's id");
            }
            if (this.name.isEmpty() && this.description.isEmpty()) {
                throw new IllegalArgumentException("topic " + this.topicId + " has neither name nor description");
            }
        }

        public String getTopicId() {
            return topicId;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public List<String> getExcerpts() {
            return excerpts;
        }

        /** The fingerprint of the topic's own text: a verdict holds while name and description stay. */
        public String key() {
            return hash(TopicMerger.wording(name) + "\n" + TopicMerger.wording(description));
        }
    }

    /** A verdict on one topic, as stored; immutable. */
    public static final class Verdict {
        /** OK carries a fence and a reason; FEHLER carries the cause and never a fence. */
        public enum Status { OK, FEHLER }

        /** Who decided: the model, or the author answering a question. */
        public enum Origin { MODELL, NUTZER }

        private final String topicId;
        private final String topicName;
        private final String topicKey;
        private final String basis;
        private final Status status;
        private final Fence fence;
        private final String reason;
        private final String problem;
        private final List<String> answers;
        private final Origin origin;
        private final String model;
        private final long timeMillis;
        private final long millis;

        public Verdict(String topicId, String topicName, String topicKey, String basis, Status status, Fence fence,
                       String reason, String problem, List<String> answers, Origin origin, String model,
                       long timeMillis, long millis) {
            this.topicId = clean(topicId);
            this.topicName = clean(topicName);
            this.topicKey = clean(topicKey);
            this.basis = clean(basis);
            this.status = status == null ? Status.FEHLER : status;
            this.fence = fence;
            this.reason = clean(reason);
            this.problem = clean(problem);
            this.answers = answers == null ? Collections.<String>emptyList()
                    : Collections.unmodifiableList(new ArrayList<String>(answers));
            this.origin = origin == null ? Origin.MODELL : origin;
            this.model = clean(model);
            this.timeMillis = timeMillis;
            this.millis = millis;
            if (this.topicId.isEmpty()) {
                throw new IllegalArgumentException("a verdict needs the topic's id");
            }
            if (this.status == Status.OK && (fence == null || fence == Fence.UNGEPRUEFT)) {
                throw new IllegalArgumentException("an OK verdict on " + topicId + " needs INNEN, AUSSEN or UNKLAR");
            }
            if (this.status == Status.FEHLER && fence != null) {
                throw new IllegalArgumentException("a failed check of " + topicId + " carries no fence");
            }
        }

        public String getTopicId() {
            return topicId;
        }

        public String getTopicName() {
            return topicName;
        }

        /** {@link Subject#key()} of the topic as judged. */
        public String getTopicKey() {
            return topicKey;
        }

        /** {@link Brief#basis()} of the brief it was judged against. */
        public String getBasis() {
            return basis;
        }

        public Status getStatus() {
            return status;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** INNEN, AUSSEN or UNKLAR; {@code null} for FEHLER. */
        public Fence getFence() {
            return fence;
        }

        public String getReason() {
            return reason;
        }

        public String getProblem() {
            return problem;
        }

        public List<String> getAnswers() {
            return answers;
        }

        public Origin getOrigin() {
            return origin;
        }

        public String getModel() {
            return model;
        }

        public long getTimeMillis() {
            return timeMillis;
        }

        public long getMillis() {
            return millis;
        }

        @Override
        public String toString() {
            return topicId + " " + (isOk() ? fence + " (" + origin + "): " + reason : "FEHLER: " + problem);
        }
    }

    /** An UNKLAR verdict as a question to the author; it blocks nothing while it is open. */
    public static final class AuthorQuestion {
        private final String topicId;
        private final String topicName;
        private final String reason;

        AuthorQuestion(String topicId, String topicName, String reason) {
            this.topicId = topicId;
            this.topicName = topicName;
            this.reason = reason;
        }

        public String getTopicId() {
            return topicId;
        }

        public String getTopicName() {
            return topicName;
        }

        public String getReason() {
            return reason;
        }

        /** The question as the author reads it. */
        public String text(boolean german) {
            String name = topicName.isEmpty() ? topicId : topicName;
            return german ? "Gehört das Thema „" + name + "“ ins Buch? " + reason
                    : "Does the topic \"" + name + "\" belong in the book? " + reason;
        }
    }

    /** The role: its own slot under Sources, a fixed schema with the reason first, thinking off. */
    public static final ResearchRole ROLE = ResearchRole.builder("topic-fence-check",
                    ResearchModelSlots.PHASE_RESEARCH_FENCE)
            .purpose("component 14: judges one merged topic against the mission, the edge of the subject and the "
                    + "cards of the mind map: inside the fence, outside, or unclear for the author")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(), "grund (one or two sentences), urteil (innen, aussen, unklar)"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final double TEMPERATURE = 0.0;
    /** Answer budget: two sentences and one word. */
    static final int ANSWER_TOKENS = 400;
    /** Calls per topic: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 2500;
    private static final int REASON_CHARS = 400;

    static final String SYSTEM_DE = "Du prüfst für ein Sachbuch, ob ein Thema hineingehört. Der Buchauftrag steckt den "
            + "Gegenstand des Buchs ab, den Weidezaun. Du urteilst über ein Thema nach dem anderen. Antworte nur mit "
            + "JSON.";
    static final String SYSTEM_EN = "You check whether a topic belongs in a non-fiction book. The book's mission marks "
            + "out its subject, the fence. You judge one topic at a time. Answer only with JSON.";

    private final MainModelChat chat;
    private final boolean german;
    private final Options options;
    private final Consumer<String> log;
    private final Clock clock;

    public TopicFenceCheck(MainModelChat chat, String language, Options options, Consumer<String> log) {
        this(chat, language, options, log, Clock.systemUTC());
    }

    TopicFenceCheck(MainModelChat chat, String language, Options options, Consumer<String> log, Clock clock) {
        if (chat == null) {
            throw new IllegalArgumentException("the fence check needs a chat model");
        }
        this.chat = chat;
        this.german = language == null || !language.trim().toLowerCase(Locale.ROOT).startsWith("en");
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
        this.clock = clock;
    }

    // ------------------------------------------------------------------------------------------------- which

    /**
     * The topics of the memory that need a verdict on this brief: every standing topic that is not a card, without
     * an OK verdict on the same basis and the same name and description. A decision of the author holds.
     */
    public static List<ResearchTopic> unchecked(ResearchMemory memory, Brief brief, List<Verdict> earlier) {
        Map<String, Verdict> latest = latest(earlier);
        String basis = brief.basis();
        List<ResearchTopic> todo = new ArrayList<ResearchTopic>();
        for (ResearchTopic topic : memory.topics()) {
            if (topic.getOrigin() == ResearchTopic.Origin.KONZEPT) {
                continue;
            }
            Verdict verdict = latest.get(topic.getId());
            boolean holds = verdict != null && verdict.isOk() && (verdict.getOrigin() == Verdict.Origin.NUTZER
                    || (verdict.getBasis().equals(basis) && verdict.getTopicKey().equals(keyOf(topic))));
            if (!holds) {
                todo.add(topic);
            }
        }
        return todo;
    }

    /** The latest OK verdict per topic, else the latest at all; an author's decision is not replaced by the model. */
    static Map<String, Verdict> latest(List<Verdict> verdicts) {
        Map<String, Verdict> latest = new HashMap<String, Verdict>();
        if (verdicts == null) {
            return latest;
        }
        for (Verdict verdict : verdicts) {
            Verdict before = latest.get(verdict.getTopicId());
            boolean keepAuthor = before != null && before.isOk() && before.getOrigin() == Verdict.Origin.NUTZER
                    && verdict.getOrigin() != Verdict.Origin.NUTZER;
            boolean keepOk = before != null && before.isOk() && !verdict.isOk();
            if (!keepAuthor && !keepOk) {
                latest.put(verdict.getTopicId(), verdict);
            }
        }
        return latest;
    }

    /** A topic of the memory as the check reads it: its name, description and the first distinct findings. */
    public static Subject subjectOf(ResearchMemory memory, ResearchTopic topic, Options options) {
        Options o = options == null ? Options.defaults() : options;
        List<String> excerpts = new ArrayList<String>();
        Set<String> seen = new LinkedHashSet<String>();
        for (ResearchTopic.Finding finding : memory.findingsOfTopic(topic.getId())) {
            if (excerpts.size() >= o.getExcerpts()) {
                break;
            }
            String quote = clean(finding.getQuote()).replaceAll("\\s+", " ");
            if (!quote.isEmpty() && seen.add(TopicMerger.wording(quote))) {
                excerpts.add(cut(quote, o.getExcerptChars()));
            }
        }
        return new Subject(topic.getId(), topic.getName(), topic.getDescription(), excerpts);
    }

    private static String keyOf(ResearchTopic topic) {
        return new Subject(topic.getId(), topic.getName(), topic.getDescription(), null).key();
    }

    // ------------------------------------------------------------------------------------------------- check

    /** One verdict per topic, in the order of the topics. */
    public List<Verdict> check(List<Subject> subjects, Brief brief) {
        List<Verdict> verdicts = new ArrayList<Verdict>();
        if (subjects == null || subjects.isEmpty()) {
            return verdicts;
        }
        Set<String> ids = new LinkedHashSet<String>();
        for (Subject subject : subjects) {
            if (!ids.add(subject.getTopicId())) {
                throw new IllegalArgumentException("topic " + subject.getTopicId() + " is given twice");
            }
        }
        final String basis = brief.basis();
        final AtomicInteger failuresInRow = new AtomicInteger();
        final AtomicReference<String> lastFailure = new AtomicReference<String>("");
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(options.getParallel(), subjects.size()));
        List<Future<Verdict>> running = new ArrayList<Future<Verdict>>();
        try {
            for (final Subject subject : subjects) {
                running.add(workers.submit(() -> {
                    if (failuresInRow.get() >= Options.STOP_AFTER_FAILURES) {
                        return new Verdict(subject.getTopicId(), subject.getName(), subject.key(), basis,
                                Verdict.Status.FEHLER, null, "", "not asked: the model failed "
                                + Options.STOP_AFTER_FAILURES + " topics in a row; last: " + lastFailure.get(), null,
                                Verdict.Origin.MODELL, chat.modelName(), clock.millis(), 0L);
                    }
                    Verdict verdict = ask(subject, brief, basis);
                    if (verdict.isOk()) {
                        failuresInRow.set(0);
                    } else {
                        lastFailure.set(verdict.getProblem());
                        failuresInRow.incrementAndGet();
                    }
                    return verdict;
                }));
            }
            for (Future<Verdict> verdict : running) {
                verdicts.add(verdict.get());
            }
        } catch (ExecutionException crashed) {
            throw new IllegalStateException("fence check crashed", crashed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fence check interrupted", interrupted);
        } finally {
            workers.shutdownNow();
        }
        int[] counts = new int[4];
        for (Verdict verdict : verdicts) {
            counts[verdict.isOk() ? verdict.getFence().ordinal() : 3]++;
        }
        log("fence check: " + verdicts.size() + " topics, " + counts[Fence.INNEN.ordinal()] + " INNEN, "
                + counts[Fence.AUSSEN.ordinal()] + " AUSSEN, " + counts[Fence.UNKLAR.ordinal()] + " UNKLAR, "
                + counts[3] + " FEHLER");
        return verdicts;
    }

    /** One topic: at most {@link #ATTEMPTS} calls. */
    Verdict ask(Subject subject, Brief brief, String basis) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(prompt(brief, subject, german, options)));
        List<String> answers = new ArrayList<String>();
        String problem = "";
        long start = clock.millis();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, ANSWER_TOKENS, schema());
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
                return new Verdict(subject.getTopicId(), subject.getName(), subject.key(), basis, Verdict.Status.OK,
                        answer.fence, answer.reason, "", answers, Verdict.Origin.MODELL, chat.modelName(),
                        clock.millis(), clock.millis() - start);
            }
            problem = "invalid answer: " + answer.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + answer.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("fence check failed for " + subject.getTopicId() + ": " + problem);
        return new Verdict(subject.getTopicId(), subject.getName(), subject.key(), basis, Verdict.Status.FEHLER, null,
                "", problem, answers, Verdict.Origin.MODELL, chat.modelName(), clock.millis(), clock.millis() - start);
    }

    /** The author's decision on a topic, for instance the answer to an UNKLAR question. */
    public static Verdict byAuthor(Subject subject, Brief brief, Fence fence, String reason, long timeMillis) {
        return new Verdict(subject.getTopicId(), subject.getName(), subject.key(), brief.basis(), Verdict.Status.OK,
                fence, reason, "", null, Verdict.Origin.NUTZER, "", timeMillis, 0L);
    }

    // ------------------------------------------------------------------------------------------------- results

    /**
     * The OK verdicts written to the topics of the memory (fence and reason), each only while its topic still stands
     * and only when it is the latest that holds for it; a FEHLER changes nothing, the topic is offered again.
     */
    public static int apply(ResearchMemory memory, List<Verdict> verdicts) throws IOException {
        int written = 0;
        for (Verdict verdict : latest(verdicts).values()) {
            if (!verdict.isOk()) {
                continue;
            }
            ResearchTopic topic = memory.topic(verdict.getTopicId());
            ResearchTopic standing = memory.currentTopic(verdict.getTopicId());
            if (topic == null || standing == null || !standing.getId().equals(topic.getId())) {
                continue;
            }
            if (topic.getFence() == verdict.getFence() && topic.getFenceReason().equals(verdict.getReason())) {
                continue;
            }
            String by = verdict.getOrigin() == Verdict.Origin.NUTZER ? "nutzer" : verdict.getModel();
            memory.updateTopic(topic.withFence(verdict.getFence(), verdict.getReason(), by, verdict.getTimeMillis()));
            written++;
        }
        return written;
    }

    /** The UNKLAR verdicts of the model as questions to the author, in the order of the verdicts. */
    public static List<AuthorQuestion> questions(List<Verdict> verdicts) {
        List<AuthorQuestion> questions = new ArrayList<AuthorQuestion>();
        for (Verdict verdict : verdicts) {
            if (verdict.isOk() && verdict.getFence() == Fence.UNKLAR && verdict.getOrigin() == Verdict.Origin.MODELL) {
                questions.add(new AuthorQuestion(verdict.getTopicId(), verdict.getTopicName(), verdict.getReason()));
            }
        }
        return questions;
    }

    // ------------------------------------------------------------------------------------------------- prompt

    static String prompt(Brief brief, Subject subject, boolean german, Options options) {
        String mission = brief.getMission().isEmpty() ? (german ? "(keiner angegeben)" : "(none given)")
                : cut(brief.getMission(), MISSION_CHARS);
        StringBuilder text = new StringBuilder();
        text.append(german ? "Buchauftrag:\n" : "Book mission:\n").append(mission).append("\n\n");
        if (!brief.getEdge().isEmpty()) {
            text.append(german ? "Rand des Themas:\n" : "Edge of the subject:\n").append(brief.getEdge())
                    .append("\n\n");
        }
        list(text, german ? "Dazu gehört ausdrücklich:" : "Explicitly included:", brief.getIncluded());
        list(text, german ? "Ausgeschlossen:" : "Excluded:", brief.getExcluded());
        if (!brief.getPlace().isEmpty()) {
            text.append(german ? "Räumlicher Rahmen: " : "Place: ").append(brief.getPlace()).append('\n');
        }
        if (!brief.getTime().isEmpty()) {
            text.append(german ? "Zeitlicher Rahmen: " : "Time: ").append(brief.getTime()).append('\n');
        }
        if (!brief.getPlace().isEmpty() || !brief.getTime().isEmpty()) {
            text.append('\n');
        }
        list(text, german ? "Karten der Mindmap (sie geben die Richtung vor, nicht den fertigen Inhalt):"
                : "Cards of the mind map (they give the direction, not the finished content):", brief.getCards());
        text.append(german ? "Zu prüfendes Thema:\n" : "Topic to check:\n")
                .append(subject.getName().isEmpty() ? (german ? "(ohne Namen)" : "(no name)") : subject.getName())
                .append('\n');
        if (!subject.getDescription().isEmpty()) {
            text.append(german ? "Worum es geht: " : "What it is about: ").append(subject.getDescription())
                    .append('\n');
        }
        List<String> excerpts = subject.getExcerpts();
        int shown = Math.min(excerpts.size(), options.getExcerpts());
        if (shown > 0) {
            text.append(german ? "Aus den Quellen:\n" : "From the sources:\n");
            for (int i = 0; i < shown; i++) {
                String excerpt = cut(excerpts.get(i).replaceAll("\\s+", " "), options.getExcerptChars());
                text.append("- ").append(german ? "„" : "\"").append(excerpt).append(german ? "“" : "\"")
                        .append('\n');
            }
        }
        text.append('\n');
        if (german) {
            text.append("Liegt das Thema innerhalb des Weidezauns? Wähle ein Urteil:\n")
                    .append("- \"innen\": Das Thema gehört in dieses Buch. Es muss keiner Karte entsprechen: Ein "
                            + "neues Thema gehört dazu, wenn es zum Gegenstand des Auftrags beiträgt.\n")
                    .append("- \"aussen\": Das Thema behandelt einen anderen Gegenstand und trägt zum Auftrag nichts "
                            + "bei, auch nicht als übertragbare Erkenntnis. Dass es einer Karte ähnelt oder dieselben "
                            + "Wörter benutzt, macht es noch nicht zu einem Thema des Buchs.\n")
                    .append("- \"unklar\": Das Thema liegt am Rand des Auftrags, oder die Angaben reichen für ein "
                            + "Urteil nicht. Dann entscheidet der Autor.\n\n")
                    .append("Antworte nur mit JSON mit den Feldern \"grund\" (ein oder zwei Sätze, zuerst) und "
                            + "\"urteil\" (\"innen\", \"aussen\" oder \"unklar\").");
        } else {
            text.append("Does the topic lie inside the fence? Choose one verdict:\n")
                    .append("- \"innen\": The topic belongs in this book. It need not match a card: a new topic "
                            + "belongs if it adds to the subject of the mission.\n")
                    .append("- \"aussen\": The topic deals with another subject and adds nothing to the mission, not "
                            + "even as a transferable finding. Resembling a card or using the same words does not make "
                            + "it a topic of the book.\n")
                    .append("- \"unklar\": The topic lies at the edge of the mission, or there is not enough to "
                            + "judge. Then the author decides.\n\n")
                    .append("Answer only with JSON with the fields \"grund\" (one or two sentences, first) and "
                            + "\"urteil\" (\"innen\", \"aussen\" or \"unklar\").");
        }
        return text.toString();
    }

    private static void list(StringBuilder text, String title, List<String> lines) {
        if (lines.isEmpty()) {
            return;
        }
        text.append(title).append('\n');
        for (String line : lines) {
            text.append("- ").append(line).append('\n');
        }
        text.append('\n');
    }

    /** {@code grund} first, then {@code urteil} from the three words; nothing else. */
    static String schema() {
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        JsonArray words = new JsonArray();
        words.add("innen");
        words.add("aussen");
        words.add("unklar");
        JsonObject verdict = new JsonObject();
        verdict.addProperty("type", "string");
        verdict.add("enum", words);
        JsonObject properties = new JsonObject();
        properties.add("grund", reason);
        properties.add("urteil", verdict);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("urteil");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /** A verdict as read from an answer, or why the answer holds none. */
    static final class Answer {
        Fence fence;
        String reason = "";
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
        JsonElement word = json.get("urteil");
        Fence fence = word != null && word.isJsonPrimitive() ? fenceOf(word.getAsString()) : null;
        if (fence == null) {
            answer.problem = "\"urteil\" must be innen, aussen or unklar";
            return answer;
        }
        JsonElement reason = json.get("grund");
        String why = reason != null && reason.isJsonPrimitive()
                ? reason.getAsString().replaceAll("\\s+", " ").trim() : "";
        if (why.isEmpty()) {
            answer.problem = "\"grund\" must give the reason";
            return answer;
        }
        answer.fence = fence;
        answer.reason = cut(why, REASON_CHARS);
        return answer;
    }

    /** innen, aussen (also außen) or unklar, case ignored; {@code null} for anything else. */
    static Fence fenceOf(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replace("ß", "ss");
        if (w.equals("innen")) {
            return Fence.INNEN;
        }
        if (w.equals("aussen")) {
            return Fence.AUSSEN;
        }
        if (w.equals("unklar")) {
            return Fence.UNKLAR;
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    static String hash(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format(Locale.ROOT, "%02x", digest[i] & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("SHA-256 missing", missing);
        }
    }

    /** At most {@code chars} characters, cut at the last blank when it is not too far back, then " …". */
    static String cut(String text, int chars) {
        if (text.length() <= chars) {
            return text;
        }
        String head = text.substring(0, chars);
        int blank = head.lastIndexOf(' ');
        return (blank >= chars * 3 / 5 ? head.substring(0, blank) : head).trim() + " …";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> cleanAll(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String value : values) {
                String v = clean(value);
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
