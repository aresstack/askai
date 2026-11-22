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
 * the edge of the subject and the cards of the mind map, and answers INNEN, AUSSEN, UNKLAR or KEIN_THEMA, always with a
 * reason.
 *
 * <ul>
 *   <li>INNEN: the topic itself concerns the subject of the mission or adds a finding to it. It need not match a
 *       card: the concept gives the direction, not the finished content, so a new topic inside the mission's subject
 *       is INNEN. Falling within the area of a card is no reason: the prompt shows the cards after the topic, as
 *       orientation only, and asks for a reason that names the subject of the mission. Angelo's verdicts on the
 *       disputed topics of 22 Nov are in the prompt as general rules: rules and duties that apply when the subject is
 *       used count even where the source does not name it, so do the technology it consists of or works with, with
 *       its limits and risks, and related applications and markets whose findings carry over.</li>
 *   <li>AUSSEN: another subject; also a rule that does not apply when the subject is used, although it comes from the
 *       same field (Angelo on 22 Nov: the test duty of DGUV V3 does not concern battery devices). It stops only
 *       further search on the topic; its sources and passages stay in the stock.</li>
 *   <li>UNKLAR: at the edge of the subject, open whether a rule applies, or not enough to tell. It goes to the author
 *       as a question ({@link #questions}) and blocks nothing.</li>
 *   <li>KEIN_THEMA: no subject matter but a remnant of the page or document (navigation, contact data, a literature
 *       list). The stock test of 22 Nov let such topics through as INNEN with every edge text. In the memory it is
 *       AUSSEN with the reason marked ({@link Verdict#isNoTopic()}), so the outline and the report need nothing
 *       new.</li>
 * </ul>
 *
 * <p>A topic is judged once, and again when the mission, the edge, the scope or the cards change (the
 * {@link Brief#basis() basis} of a verdict), the topic's own name and description change, or the prompt has a new
 * {@link #PROMPT_VERSION version} since the model's verdict. A subtopic is judged on
 * its own and inherits nothing from its parent; the prompt does not name the parent, so a card does not read as a
 * hint for INNEN. A parent shows its subtopics' names, and when it has fewer findings of its own than the prompt
 * holds, findings of its subtopics fill up, one per subtopic in turn: a parent that the topic merge formed has none
 * of its own. The cards themselves (origin KONZEPT) are inside by definition and are not judged. A decision of the
 * author ({@link #byAuthor}) holds until the author changes it, whatever the basis.</p>
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
        private final boolean cards;

        private Options(int parallel, int excerpts, int excerptChars, boolean cards) {
            if (parallel < 1 || excerpts < 0 || excerptChars < 40) {
                throw new IllegalArgumentException("parallel >= 1, excerpts >= 0 and excerptChars >= 40 expected");
            }
            this.parallel = parallel;
            this.excerpts = excerpts;
            this.excerptChars = excerptChars;
            this.cards = cards;
        }

        /** Four at a time; four findings of up to 300 characters per topic; the cards are shown. */
        public static Options defaults() {
            return new Options(4, 4, 300, true);
        }

        public Options withParallel(int newParallel) {
            return new Options(newParallel, excerpts, excerptChars, cards);
        }

        public Options withExcerpts(int count, int chars) {
            return new Options(parallel, count, chars, cards);
        }

        /**
         * Whether the prompt shows the cards of the mind map (after the topic, as orientation only). Off is for
         * measuring the check without them; the cards stay part of the {@linkplain Brief#basis() basis} either way.
         */
        public Options withCards(boolean shown) {
            return new Options(parallel, excerpts, excerptChars, shown);
        }

        public int getParallel() {
            return parallel;
        }

        public boolean isCards() {
            return cards;
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

    /**
     * One topic as the check reads it: name, description, a few findings (quotes from the sources) and the names of
     * its subtopics.
     */
    public static final class Subject {
        private final String topicId;
        private final String name;
        private final String description;
        private final List<String> excerpts;
        private final List<String> parts;

        public Subject(String topicId, String name, String description, List<String> excerpts) {
            this(topicId, name, description, excerpts, null);
        }

        public Subject(String topicId, String name, String description, List<String> excerpts, List<String> parts) {
            this.topicId = clean(topicId);
            this.name = clean(name).replaceAll("\\s+", " ");
            this.description = clean(description).replaceAll("\\s+", " ");
            this.excerpts = cleanAll(excerpts);
            List<String> named = new ArrayList<String>();
            for (String part : cleanAll(parts)) {
                named.add(part.replaceAll("\\s+", " "));
            }
            this.parts = Collections.unmodifiableList(named);
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

        /** The names of its subtopics as they stand, in the order of the memory; empty for a topic without any. */
        public List<String> getParts() {
            return parts;
        }

        /**
         * The fingerprint of the topic's own text: a verdict holds while name and description stay, also when
         * subtopics or findings come and go.
         */
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
        private final boolean noTopic;
        private final int version;

        /** A verdict under the prompt of this build. */
        public Verdict(String topicId, String topicName, String topicKey, String basis, Status status, Fence fence,
                       String reason, String problem, List<String> answers, Origin origin, String model,
                       long timeMillis, long millis) {
            this(topicId, topicName, topicKey, basis, status, fence, reason, problem, answers, origin, model,
                    timeMillis, millis, false, PROMPT_VERSION);
        }

        /**
         * {@code noTopic}: the model said KEIN_THEMA, only with OK and AUSSEN; {@code version}: the
         * {@link #PROMPT_VERSION} it was asked under.
         */
        public Verdict(String topicId, String topicName, String topicKey, String basis, Status status, Fence fence,
                       String reason, String problem, List<String> answers, Origin origin, String model,
                       long timeMillis, long millis, boolean noTopic, int version) {
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
            this.noTopic = noTopic;
            this.version = version;
            if (this.topicId.isEmpty()) {
                throw new IllegalArgumentException("a verdict needs the topic's id");
            }
            if (noTopic && (this.status != Status.OK || fence != Fence.AUSSEN)) {
                throw new IllegalArgumentException("\"no subject\" on " + topicId + " is an OK verdict AUSSEN");
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

        /**
         * The model said KEIN_THEMA: no subject matter but a remnant of the page or document. The fence is AUSSEN and
         * the reason starts with {@link #NO_TOPIC_DE} or {@link #NO_TOPIC_EN}.
         */
        public boolean isNoTopic() {
            return noTopic;
        }

        /** The {@link #PROMPT_VERSION} the verdict was asked under; a model's verdict of another one is asked again. */
        public int getVersion() {
            return version;
        }

        @Override
        public String toString() {
            return topicId + " " + (isOk() ? fence + (noTopic ? ", kein Sachthema" : "") + " (" + origin + "): "
                    + reason : "FEHLER: " + problem);
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
            .output(RoleOutput.schema(schema(), "grund (one or two sentences), urteil (innen, aussen, unklar, "
                    + "kein_thema)"))
            .thinking(ThinkingMode.OFF)
            .build();

    /**
     * The prompt's version; a model's verdict under another one does not hold. 1 (21.11.2026): innen, aussen, unklar.
     * 2 (22.11.2026): kein_thema, a rule must apply when the subject is used, Angelo's rules for what counts inside,
     * the subtopics of a parent.
     */
    public static final int PROMPT_VERSION = 2;

    static final double TEMPERATURE = 0.0;
    /** Answer budget: two sentences and one word. */
    static final int ANSWER_TOKENS = 400;
    /** Calls per topic: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    private static final int MISSION_CHARS = 2500;
    private static final int REASON_CHARS = 400;
    /** Subtopic names a prompt shows; the rest is counted. */
    static final int PARTS_SHOWN = 5;
    /** The start of the reason of a KEIN_THEMA verdict, in the memory and the log. */
    public static final String NO_TOPIC_DE = "Kein Sachthema: ";
    public static final String NO_TOPIC_EN = "Not a subject: ";

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
     * an OK verdict on the same basis, the same name and description and the prompt of this build. A decision of the
     * author holds.
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
                    || (verdict.getBasis().equals(basis) && verdict.getTopicKey().equals(keyOf(topic))
                    && verdict.getVersion() == PROMPT_VERSION));
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

    /** A topic of the memory as the check reads it; see {@link #subjects}. */
    public static Subject subjectOf(ResearchMemory memory, ResearchTopic topic, Options options) {
        return subjects(memory, Collections.singletonList(topic), options).get(0);
    }

    /**
     * Topics of the memory as the check reads them, in the order given: name, description, the first distinct
     * findings, and the names of the subtopics that stand. When a topic has fewer findings of its own than the prompt
     * shows, its subtopics fill up, one finding of each in turn; a parent formed by the topic merge has none of its
     * own. The subtopics are looked up once for all topics.
     */
    public static List<Subject> subjects(ResearchMemory memory, List<ResearchTopic> topics, Options options) {
        Options o = options == null ? Options.defaults() : options;
        Map<String, List<ResearchTopic>> children = childrenOf(memory);
        List<Subject> subjects = new ArrayList<Subject>();
        for (ResearchTopic topic : topics) {
            List<ResearchTopic> parts = children.containsKey(topic.getId()) ? children.get(topic.getId())
                    : Collections.<ResearchTopic>emptyList();
            List<String> excerpts = new ArrayList<String>();
            Set<String> seen = new LinkedHashSet<String>();
            for (ResearchTopic.Finding finding : memory.findingsOfTopic(topic.getId())) {
                if (excerpts.size() >= o.getExcerpts()) {
                    break;
                }
                addExcerpt(finding, excerpts, seen, o);
            }
            List<List<ResearchTopic.Finding>> ofParts = new ArrayList<List<ResearchTopic.Finding>>();
            List<String> names = new ArrayList<String>();
            for (ResearchTopic part : parts) {
                ofParts.add(memory.findingsOfTopic(part.getId()));
                names.add(part.getName().trim().isEmpty() ? part.getDescription() : part.getName());
            }
            for (int round = 0; excerpts.size() < o.getExcerpts(); round++) {
                boolean more = false;
                for (List<ResearchTopic.Finding> findings : ofParts) {
                    if (round < findings.size() && excerpts.size() < o.getExcerpts()) {
                        more = true;
                        addExcerpt(findings.get(round), excerpts, seen, o);
                    }
                }
                if (!more) {
                    break;
                }
            }
            subjects.add(new Subject(topic.getId(), topic.getName(), topic.getDescription(), excerpts, names));
        }
        return subjects;
    }

    /** The standing subtopics of each topic id, as {@link ResearchMemory#parentOf} resolves their parent. */
    static Map<String, List<ResearchTopic>> childrenOf(ResearchMemory memory) {
        Map<String, List<ResearchTopic>> children = new HashMap<String, List<ResearchTopic>>();
        for (ResearchTopic topic : memory.topics()) {
            if (topic.getParentId().isEmpty()) {
                continue;
            }
            ResearchTopic parent = memory.parentOf(topic.getId());
            if (parent != null) {
                children.computeIfAbsent(parent.getId(), id -> new ArrayList<ResearchTopic>()).add(topic);
            }
        }
        return children;
    }

    private static void addExcerpt(ResearchTopic.Finding finding, List<String> excerpts, Set<String> seen,
                                   Options options) {
        String quote = clean(finding.getQuote()).replaceAll("\\s+", " ");
        if (!quote.isEmpty() && seen.add(TopicMerger.wording(quote))) {
            excerpts.add(cut(quote, options.getExcerptChars()));
        }
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
        int noTopic = 0;
        for (Verdict verdict : verdicts) {
            counts[verdict.isOk() ? verdict.getFence().ordinal() : 3]++;
            noTopic += verdict.isNoTopic() ? 1 : 0;
        }
        log("fence check: " + verdicts.size() + " topics, " + counts[Fence.INNEN.ordinal()] + " INNEN, "
                + counts[Fence.AUSSEN.ordinal()] + " AUSSEN (" + noTopic + " of them no subject), "
                + counts[Fence.UNKLAR.ordinal()] + " UNKLAR, " + counts[3] + " FEHLER");
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
                String reason = answer.noTopic ? (german ? NO_TOPIC_DE : NO_TOPIC_EN) + answer.reason : answer.reason;
                return new Verdict(subject.getTopicId(), subject.getName(), subject.key(), basis, Verdict.Status.OK,
                        answer.fence, reason, "", answers, Verdict.Origin.MODELL, chat.modelName(),
                        clock.millis(), clock.millis() - start, answer.noTopic, PROMPT_VERSION);
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
        text.append(german ? "Zu prüfendes Thema:\n" : "Topic to check:\n")
                .append(subject.getName().isEmpty() ? (german ? "(ohne Namen)" : "(no name)") : subject.getName())
                .append('\n');
        if (!subject.getDescription().isEmpty()) {
            text.append(german ? "Worum es geht: " : "What it is about: ").append(subject.getDescription())
                    .append('\n');
        }
        List<String> parts = subject.getParts();
        if (!parts.isEmpty()) {
            List<String> shownParts = new ArrayList<String>();
            for (String part : parts.subList(0, Math.min(parts.size(), PARTS_SHOWN))) {
                shownParts.add(cut(part, 120));
            }
            int more = parts.size() - shownParts.size();
            text.append(german ? "Teilthemen: " : "Subtopics: ").append(String.join("; ", shownParts))
                    .append(more == 0 ? "" : german ? " und " + more + " weitere" : " and " + more + " more")
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
        if (options.isCards() && !brief.getCards().isEmpty()) {
            cards(text, brief.getCards(), german);
        }
        if (german) {
            text.append("Gehört das Thema in dieses Buch? Maßstab ist der Buchauftrag mit seinem Rand, nicht die "
                            + "Karten. Wähle ein Urteil:\n")
                    .append("- \"innen\": Das Thema selbst handelt vom Gegenstand des Auftrags oder liefert eine "
                            + "Erkenntnis, die erkennbar zu diesem Gegenstand beiträgt. Dazu zählen auch Regeln und "
                            + "Pflichten, die beim Einsatz des Gegenstands gelten, auch wenn die Quelle ihn nicht "
                            + "nennt, die Technik, aus der er besteht oder mit der er arbeitet, mit ihren Grenzen und "
                            + "Risiken, und verwandte Anwendungen und Märkte, deren Erkenntnisse sich auf ihn "
                            + "übertragen lassen. Es muss keiner Karte entsprechen: Auch ein Thema, das keine Karte "
                            + "vorsieht, gehört dann dazu. Dass es in den Bereich einer Karte fällt, reicht dafür "
                            + "nicht.\n")
                    .append("- \"aussen\": Das Thema behandelt einen anderen Gegenstand und trägt zum Auftrag nichts "
                            + "bei, auch nicht als übertragbare Erkenntnis. Eine Regel, Norm oder Pflicht, die beim "
                            + "Einsatz des Gegenstands nicht gilt, weil sie andere Anlagen, Geräte oder Tätigkeiten "
                            + "betrifft, ist \"aussen\", auch wenn sie aus demselben Rechts- oder Fachgebiet stammt. "
                            + "Dass ein Thema einer Karte ähnelt oder dieselben Wörter benutzt, macht es noch nicht zu "
                            + "einem Thema des Buchs.\n")
                    .append("- \"unklar\": Das Thema liegt am Rand des Auftrags, es ist offen, ob eine Regel beim "
                            + "Einsatz des Gegenstands gilt, oder die Angaben reichen für ein Urteil nicht. Dann "
                            + "entscheidet der Autor.\n")
                    .append("- \"kein_thema\": Das ist kein Sachthema, sondern ein Rest der Webseite oder des "
                            + "Dokuments, etwa Navigation oder Menü, Kontaktdaten oder Impressum, eine Literatur- oder "
                            + "Linkliste, ein Hinweis zu Cookies oder zur Anmeldung.\n\n")
                    .append("Antworte nur mit JSON mit den Feldern \"grund\" (ein oder zwei Sätze, zuerst: was das "
                            + "Thema mit dem Gegenstand des Auftrags zu tun hat oder warum nichts; eine Karte ist kein "
                            + "Grund) und \"urteil\" (\"innen\", \"aussen\", \"unklar\" oder \"kein_thema\").");
        } else {
            text.append("Does the topic belong in this book? The measure is the book mission with its edge, not the "
                            + "cards. Choose one verdict:\n")
                    .append("- \"innen\": The topic itself deals with the subject of the mission or yields a finding "
                            + "that recognisably adds to that subject. This includes rules and duties that apply when "
                            + "the subject is used, even if the source does not name it, the technology it consists of "
                            + "or works with, with its limits and risks, and related applications and markets whose "
                            + "findings carry over to it. It need not match a card: a topic that no card foresees "
                            + "belongs then as well. Falling within the area of a card is not enough.\n")
                    .append("- \"aussen\": The topic deals with another subject and adds nothing to the mission, not "
                            + "even as a transferable finding. A rule, standard or duty that does not apply when the "
                            + "subject is used, because it concerns other installations, devices or activities, is "
                            + "\"aussen\", even if it comes from the same field of law or expertise. Resembling a card "
                            + "or using the same words does not make it a topic of the book.\n")
                    .append("- \"unklar\": The topic lies at the edge of the mission, it is open whether a rule "
                            + "applies when the subject is used, or there is not enough to judge. Then the author "
                            + "decides.\n")
                    .append("- \"kein_thema\": This is no subject matter but a remnant of the web page or document, "
                            + "such as navigation or a menu, contact details or an imprint, a list of literature or "
                            + "links, a notice about cookies or signing in.\n\n")
                    .append("Answer only with JSON with the fields \"grund\" (one or two sentences, first: what the "
                            + "topic has to do with the subject of the mission, or why nothing; a card is no reason) "
                            + "and \"urteil\" (\"innen\", \"aussen\", \"unklar\" or \"kein_thema\").");
        }
        return text.toString();
    }

    /**
     * The cards come after the topic and are marked as orientation: the stock test of 22 Nov showed reasons that
     * leaned on a card ("falls within the area of ...") although the prompt said a card is no measure.
     */
    private static void cards(StringBuilder text, List<String> cards, boolean german) {
        text.append(german ? "Karten der Mindmap, nur zur Orientierung:\n" : "Cards of the mind map, for orientation "
                + "only:\n");
        for (String card : cards) {
            text.append("- ").append(card).append('\n');
        }
        text.append(german ? "Die Karten geben die Richtung vor, nicht den Inhalt des Buchs. Jede Karte meint ihren "
                + "Bereich nur, soweit er den Gegenstand des Auftrags betrifft. Dass ein Thema in den Bereich einer "
                + "Karte fällt, ist kein Grund für \"innen\".\n\n"
                : "The cards give the direction, not the content of the book. Each card means its area only as far "
                + "as it concerns the subject of the mission. That a topic falls within the area of a card is no "
                + "reason for \"innen\".\n\n");
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

    /** {@code grund} first, then {@code urteil} from the four words; nothing else. */
    static String schema() {
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        JsonArray words = new JsonArray();
        words.add("innen");
        words.add("aussen");
        words.add("unklar");
        words.add(NO_TOPIC_WORD);
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

    /** The fourth word of the answer: no subject matter, a remnant of the page or document. */
    static final String NO_TOPIC_WORD = "kein_thema";

    /** A verdict as read from an answer, or why the answer holds none. KEIN_THEMA is AUSSEN with {@code noTopic}. */
    static final class Answer {
        Fence fence;
        boolean noTopic;
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
        String said = word != null && word.isJsonPrimitive() ? word.getAsString() : "";
        boolean noTopic = isNoTopicWord(said);
        Fence fence = noTopic ? Fence.AUSSEN : fenceOf(said);
        if (fence == null) {
            answer.problem = "\"urteil\" must be innen, aussen, unklar or kein_thema";
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
        answer.noTopic = noTopic;
        answer.reason = cut(why, REASON_CHARS);
        return answer;
    }

    /** kein_thema, also with a blank or hyphen or run together, case ignored. */
    static boolean isNoTopicWord(String word) {
        String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        return w.equals("keinthema");
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
