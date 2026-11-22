package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.TopicClusterer;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
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
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Component 15 of the build plan (rev 13, 21.11.2026): the topic merge. It makes clear topics out of the many proposals
 * of the topic finder (13). Proposals of one topic from many sources become one topic with a fixed id, all origins and
 * all findings, and with its relation to the topics the book knows: the same as a known topic, a subtopic of a topic,
 * or new. Questions of the same meaning within a topic become one question that keeps all origins.
 *
 * <p>Step 1 without a chat model: one vector per proposal (name and description, {@link EmbeddingPort} in the document
 * role, bge-m3 in the app), grouped with the average-linkage clusterer of the topic analysis ({@link TopicClusterer})
 * at the similarity {@link Options#getSame()}; the same name joins too. Every group is then compared with its nearest
 * known topics (and the topics merged into them) and with its nearest larger groups, up to
 * {@link Options#getCandidates()} of each. At or above {@code same} the two join without a model, and a group with
 * such a certain candidate gets no doubtful case; below {@link Options#getDoubt()} nothing happens. Step 2 with
 * gemma4:12b only for the doubtful cases in between: the same, a subtopic of, or different. Only "the same" merges;
 * "part of" places a topic under an established one (a known topic, or a new one with proposals of
 * {@link Options#getParentSources()} sources) at most {@link Options#getDepth()} levels below the top of its family.
 * Known topics, and known questions, never merge with each other. Then the card step: cards of the mind map have
 * broad names that a similarity does not reach, so they get no doubtful pairwise case; instead every head of a family
 * (no known topic, under no topic) is put to the model once with all cards and their questions: the same as a card,
 * a part the card already foresees (it answers or deepens one of the card's questions), or none, and none is a valid
 * answer. The same and part of must name the question of that card, or the code counts the answer as none: the
 * card's subject alone draws nothing in; a card without questions is left out. Then the parent step: heads that stand
 * apart but lie close together (average linkage at {@link Options#getParentSimilarity()}) go to the model in groups
 * of 3 to 16, and it may name a common parent topic, or one of the heads as the parent, over at least three of them;
 * a new parent topic holds no proposal of its own and goes through the card step like a head. Whether a topic belongs
 * in the book is not decided here (that is the fence check, component 14). The questions of a topic go through the
 * same two steps with their own band, and a question merges only with questions of its own topic.</p>
 *
 * <p>Nothing is lost: the proposals stay in {@code topic-proposals/}, and {@link Result#applyTo} makes each one a topic
 * of its own in the research memory, merged into its topic (zusammengeführtAus), so a merge can be taken back. A
 * failed call gets one repair attempt, then the comparison is FEHLER: it merges nothing, the asking group is held back
 * and offered again by the next run; after {@link Options#STOP_AFTER_FAILURES} failures in a row the rest is not asked
 * (FEHLER too). Verdicts that worked are reused when the same two texts come again ({@link TopicMergeLog}), so a
 * rerun asks the model only what is new; with a {@link Recorder} every answer is written the moment it is there, so a
 * run that breaks off loses no answer. Every text of a run (proposals, their questions, the known topics and
 * questions) is embedded before the first model call, so the embedding model is not loaded again between the chat
 * calls; a failed embedding request is tried {@value #EMBED_ATTEMPTS} times. The merger keeps only an embedding cache
 * between runs; it is as thread-safe as the injected chat.</p>
 */
public final class TopicMerger {

    /** How two topics relate, as the model judges a doubtful case. For two questions only GLEICH or VERSCHIEDEN. */
    public enum Relation {
        GLEICH("gleich"), A_TEIL_VON_B("a_teil_von_b"), B_TEIL_VON_A("b_teil_von_a"), VERSCHIEDEN("verschieden");

        private final String word;

        Relation(String word) {
            this.word = word;
        }

        /** The word of the answer schema. */
        public String word() {
            return word;
        }

        /** The relation for a word of the schema; {@code null} for any other. */
        public static Relation of(String word) {
            String w = word == null ? "" : word.trim().toLowerCase(Locale.ROOT);
            for (Relation relation : values()) {
                if (relation.word.equals(w)) {
                    return relation;
                }
            }
            return null;
        }

        /** The same relation with A and B swapped. */
        public Relation flipped() {
            return this == A_TEIL_VON_B ? B_TEIL_VON_A : this == B_TEIL_VON_A ? A_TEIL_VON_B : this;
        }
    }

    /** What a merged topic is to the topics the book knew (Zuordnung). */
    public enum Mapping {
        /** A topic the book did not know, and a subtopic of none. */
        NEU,
        /** The proposals are a known topic: they were merged into it. */
        GLEICH,
        /** A topic of its own and a subtopic of another (Unterthema von). */
        UNTERTHEMA
    }

    /** The thresholds; immutable. */
    public static final class Options {
        /** After this many failed calls in a row the rest of a batch is not asked. */
        public static final int STOP_AFTER_FAILURES = 5;

        private final double same;
        private final double doubt;
        private final double questionSame;
        private final double questionDoubt;
        private final int parallel;
        private final boolean namesOnly;
        private final int embedBatch;
        private final int candidates;
        private final boolean cardStep;
        private final int parentSources;
        private final int depth;
        private final boolean parentStep;
        private final double parentSimilarity;

        private Options(double same, double doubt, double questionSame, double questionDoubt, int parallel,
                        boolean namesOnly, int embedBatch, int candidates, boolean cardStep, int parentSources,
                        int depth, boolean parentStep, double parentSimilarity) {
            if (!(doubt > 0 && doubt <= same && same <= 1) || !(questionDoubt > 0 && questionDoubt <= questionSame
                    && questionSame <= 1)) {
                throw new IllegalArgumentException("a band needs 0 < doubt <= same <= 1");
            }
            if (!(parentSimilarity > 0 && parentSimilarity <= 1)) {
                throw new IllegalArgumentException("the parent step needs 0 < similarity <= 1");
            }
            this.same = same;
            this.doubt = doubt;
            this.questionSame = questionSame;
            this.questionDoubt = questionDoubt;
            this.parallel = Math.max(1, parallel);
            this.namesOnly = namesOnly;
            this.embedBatch = Math.max(1, embedBatch);
            this.candidates = Math.max(1, candidates);
            this.cardStep = cardStep;
            this.parentSources = Math.max(1, parentSources);
            this.depth = Math.max(0, depth);
            this.parentStep = parentStep;
            this.parentSimilarity = parentSimilarity;
        }

        /**
         * Proposals join without a model at a cosine of 0.85, the model decides from 0.65 up; questions at 0.92 and
         * from 0.85; four calls at a time; 32 texts per embedding request (point R: Ollama refuses large batches).
         * Proposals by name and description. Each group is compared with up to three candidates of each kind, and
         * the cards of the mind map are decided by the card step. The topic band follows the architect's measurement
         * of 22.11.2026 on the proposals of the 17 Nov stock (bge-m3): of the pairs with the same silver label only
         * 5.6 % reach 0.72 but 21 % reach 0.65, while pairs with different labels stay at 0.5 % there; 0.85 is
         * reached almost only by nearly the same text. A new topic takes subtopics only with proposals of two
         * sources, and a subtopic hangs at most two levels below its top (the architect's second run of 22.11.:
         * the model calls almost every doubtful pair "part of", which made chains ten deep). The parent step looks
         * for a common parent among heads at the lower end of the band (0.65): the architect's fourth run of 22.11.
         * left 29 proposals of one subject in 22 topics at the top, siblings the model rightly called different.
         */
        public static Options defaults() {
            return new Options(0.85, 0.65, 0.92, 0.85, 4, false, 32, 3, true, 2, 2, true, 0.65);
        }

        public Options withTopicBand(double newSame, double newDoubt) {
            return new Options(newSame, newDoubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch,
                    candidates, cardStep, parentSources, depth, parentStep, parentSimilarity);
        }

        public Options withQuestionBand(double newSame, double newDoubt) {
            return new Options(same, doubt, newSame, newDoubt, parallel, namesOnly, embedBatch, candidates, cardStep,
                    parentSources, depth, parentStep, parentSimilarity);
        }

        public Options withParallel(int calls) {
            return new Options(same, doubt, questionSame, questionDoubt, calls, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity);
        }

        /** Compare proposals by their names alone, without the description. */
        public Options withNamesOnly(boolean names) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, names, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity);
        }

        /**
         * How many candidates a group is compared with: up to this many known topics and as many earlier groups, the
         * most similar ones at or above the lower end of the band.
         */
        public Options withCandidates(int perKind) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, perKind,
                    cardStep, parentSources, depth, parentStep, parentSimilarity);
        }

        /**
         * With the card step (the default) the cards of the mind map get no doubtful pairwise case: every head of a
         * family is put to the model once with all cards. Without it, cards are compared like any known topic.
         */
        public Options withCardStep(boolean on) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    on, parentSources, depth, parentStep, parentSimilarity);
        }

        /**
         * Where a "part of" verdict may hang a topic: under a known topic, or under a new topic with proposals of at
         * least {@code sources} sources (an established topic, not a single mention); and at most {@code levels}
         * levels below the top of its family (a card, a known topic or a new topic under none). A verdict that breaks
         * either rule leaves the topic where it is.
         */
        public Options withSubtopics(int sources, int levels) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, sources, levels, parentStep, parentSimilarity);
        }

        /**
         * With the parent step (the default) heads of families that stand apart but lie at or above {@code similarity}
         * of each other (average linkage over their vectors) are put to the model in groups: it may name a common
         * parent topic, and the heads that fall under it become its subtopics. Without it, no topic is formed that no
         * proposal named.
         */
        public Options withParentStep(boolean on, double similarity) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, on, similarity);
        }

        public double getSame() {
            return same;
        }

        public double getDoubt() {
            return doubt;
        }

        public double getQuestionSame() {
            return questionSame;
        }

        public double getQuestionDoubt() {
            return questionDoubt;
        }

        public int getParallel() {
            return parallel;
        }

        public boolean isNamesOnly() {
            return namesOnly;
        }

        public int getEmbedBatch() {
            return embedBatch;
        }

        public int getCandidates() {
            return candidates;
        }

        public boolean isCardStep() {
            return cardStep;
        }

        /** The sources a new topic needs before it takes subtopics. */
        public int getParentSources() {
            return parentSources;
        }

        /** The levels a subtopic may hang below the top of its family. */
        public int getDepth() {
            return depth;
        }

        public boolean isParentStep() {
            return parentStep;
        }

        /** How similar heads must be to be put to the parent step together (average linkage). */
        public double getParentSimilarity() {
            return parentSimilarity;
        }
    }

    /**
     * One case put to the model, with its verdict or why there is none; immutable. For the card step (kind
     * {@link Kind#KARTE}) B is the list of cards as the model read it, the verdict is {@link Relation#GLEICH} (the
     * topic is a card), {@link Relation#A_TEIL_VON_B} (part of a card) or {@link Relation#VERSCHIEDEN} (no card),
     * and {@link #getBId()} names the card, {@code ""} for none. For the parent step (kind {@link Kind#OBERTHEMA}) A
     * is the list of heads as the model read it and B the fixed word {@value #PARENT_MARK}; the verdict is
     * {@link Relation#A_TEIL_VON_B} (a parent was named: heads of the list are part of it) or
     * {@link Relation#VERSCHIEDEN} (none), {@link #getBId()} names the head that became the parent ({@code ""} for a
     * new parent topic), and the parent with its parts is read again from the last raw answer. The note
     * ({@link #getNote()}) says where the code changed or read the answer: a card named without a question of that
     * card counts as none, a parent named over fewer than three heads as none.
     */
    public static final class Comparison {
        /** Two topics, two questions of one topic, a topic and the cards of the mind map, or heads and a parent. */
        public enum Kind { THEMA, FRAGE, KARTE, OBERTHEMA }

        /** B of a case of the parent step. */
        public static final String PARENT_MARK = "oberthema";

        public enum Status { OK, FEHLER }

        private final Kind kind;
        private final String aId;
        private final String aText;
        private final String bId;
        private final String bText;
        private final boolean bKnown;
        private final double similarity;
        private final Status status;
        private final Relation relation;
        private final String reason;
        private final String problem;
        private final List<String> answers;
        private final String model;
        private final long timeMillis;
        private final long millis;
        private final int version;
        private final String note;

        /**
         * A case under the prompts of this build ({@link #versionOf} its kind).
         *
         * @param aId      the asking group: its first proposal or question
         * @param aText    group A as the model read it
         * @param bKnown   whether B is a known topic or question
         * @param relation the verdict for OK, {@code null} for FEHLER
         */
        public Comparison(Kind kind, String aId, String aText, String bId, String bText, boolean bKnown,
                          double similarity, Status status, Relation relation, String reason, String problem,
                          List<String> answers, String model, long timeMillis, long millis) {
            this(kind, aId, aText, bId, bText, bKnown, similarity, status, relation, reason, problem, answers, model,
                    timeMillis, millis, versionOf(kind), "");
        }

        /** A case asked under the prompts of {@code version}: only a verdict of this build's version is reused. */
        public Comparison(Kind kind, String aId, String aText, String bId, String bText, boolean bKnown,
                          double similarity, Status status, Relation relation, String reason, String problem,
                          List<String> answers, String model, long timeMillis, long millis, int version) {
            this(kind, aId, aText, bId, bText, bKnown, similarity, status, relation, reason, problem, answers, model,
                    timeMillis, millis, version, "");
        }

        /**
         * A case with the code's note on the answer ({@code ""} for none).
         *
         * @param note where the code changed or read the answer, see {@link #getNote()}
         */
        public Comparison(Kind kind, String aId, String aText, String bId, String bText, boolean bKnown,
                          double similarity, Status status, Relation relation, String reason, String problem,
                          List<String> answers, String model, long timeMillis, long millis, int version,
                          String note) {
            this.note = clean(note);
            this.version = Math.max(1, version);
            this.kind = kind == null ? Kind.THEMA : kind;
            this.aId = clean(aId);
            this.aText = clean(aText);
            this.bId = clean(bId);
            this.bText = clean(bText);
            this.bKnown = bKnown;
            this.similarity = similarity;
            this.status = status == null ? Status.FEHLER : status;
            this.relation = this.status == Status.OK ? relation : null;
            this.reason = clean(reason);
            this.problem = clean(problem);
            this.answers = Collections.unmodifiableList(new ArrayList<String>(
                    answers == null ? Collections.<String>emptyList() : answers));
            this.model = clean(model);
            this.timeMillis = timeMillis;
            this.millis = millis;
            if (this.status == Status.OK && relation == null) {
                throw new IllegalArgumentException("an OK comparison needs a relation");
            }
            if (this.aText.isEmpty() || this.bText.isEmpty()) {
                throw new IllegalArgumentException("a comparison needs both texts");
            }
        }

        /** The two texts as the model read them: the same key, the same question. */
        public String key() {
            return key(kind, aText, bText);
        }

        static String key(Kind kind, String a, String b) {
            return kind.name() + "\n" + a + "\n\u0000" + b;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        public Kind getKind() {
            return kind;
        }

        public String getAId() {
            return aId;
        }

        public String getAText() {
            return aText;
        }

        public String getBId() {
            return bId;
        }

        public String getBText() {
            return bText;
        }

        public boolean isBKnown() {
            return bKnown;
        }

        public double getSimilarity() {
            return similarity;
        }

        public Status getStatus() {
            return status;
        }

        public Relation getRelation() {
            return relation;
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

        public String getModel() {
            return model;
        }

        public long getTimeMillis() {
            return timeMillis;
        }

        public long getMillis() {
            return millis;
        }

        /** The version of the prompts the case was asked under (Fassung). */
        public int getVersion() {
            return version;
        }

        /**
         * The code's note on the answer (Vermerk), {@code ""} for none: a card named without a question of that card
         * (it counts as none), the parent and its parts as read, or why a named parent counts as none.
         */
        public String getNote() {
            return note;
        }

        @Override
        public String toString() {
            return kind + " " + aId + " / " + bId + " " + String.format(Locale.ROOT, "%.3f", similarity) + ": "
                    + (isOk() ? relation : "FEHLER " + problem);
        }
    }

    /** A topic as the merge leaves it; immutable. */
    public static final class MergedTopic {
        private final String id;
        private final String name;
        private final String description;
        private final Mapping mapping;
        private final String parentId;
        private final String knownId;
        private final List<String> proposalIds;
        private final List<ResearchTopic.Finding> findings;
        private final List<String> sourceIds;
        private final List<String> questionIds;
        private final boolean formed;

        MergedTopic(String id, String name, String description, Mapping mapping, String parentId, String knownId,
                    List<String> proposalIds, List<ResearchTopic.Finding> findings, List<String> sourceIds,
                    List<String> questionIds) {
            this(id, name, description, mapping, parentId, knownId, proposalIds, findings, sourceIds, questionIds,
                    false);
        }

        MergedTopic(String id, String name, String description, Mapping mapping, String parentId, String knownId,
                    List<String> proposalIds, List<ResearchTopic.Finding> findings, List<String> sourceIds,
                    List<String> questionIds, boolean formed) {
            this.formed = formed;
            this.id = id;
            this.name = name;
            this.description = description;
            this.mapping = mapping;
            this.parentId = parentId;
            this.knownId = knownId;
            this.proposalIds = Collections.unmodifiableList(new ArrayList<String>(proposalIds));
            this.findings = Collections.unmodifiableList(new ArrayList<ResearchTopic.Finding>(findings));
            this.sourceIds = Collections.unmodifiableList(new ArrayList<String>(sourceIds));
            this.questionIds = Collections.unmodifiableList(new ArrayList<String>(questionIds));
        }

        /** The fixed id (themaId): a new {@code thema-NNNN}, or the known topic's id for {@link Mapping#GLEICH}. */
        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public String getDescription() {
            return description;
        }

        public Mapping getMapping() {
            return mapping;
        }

        /** The topic this one is a subtopic of: a known topic or another merged topic; {@code ""} for none. */
        public String getParentId() {
            return parentId;
        }

        /** The known topic this one is, or lies under through its parents; {@code ""} when it reaches none. */
        public String getKnownId() {
            return knownId;
        }

        /** The proposals this run brings into the topic (Herkunft), in the order they came. */
        public List<String> getProposalIds() {
            return proposalIds;
        }

        /** The findings of these proposals, each place once. */
        public List<ResearchTopic.Finding> getFindings() {
            return findings;
        }

        /** The sources of these proposals, each once. */
        public List<String> getSourceIds() {
            return sourceIds;
        }

        /** The questions of the topic that stand on their own after the merge (known ones first). */
        public List<String> getQuestionIds() {
            return questionIds;
        }

        /**
         * A parent topic the parent step formed (gebildet): the model named it over heads that stand apart, it holds
         * no proposal, no finding and no question of its own, only its subtopics.
         */
        public boolean isFormed() {
            return formed;
        }

        @Override
        public String toString() {
            return id + " [" + mapping + (parentId.isEmpty() ? "" : " von " + parentId) + (formed ? ", gebildet" : "")
                    + "] " + name + " (" + proposalIds.size() + " Vorschläge, " + sourceIds.size() + " Quellen)";
        }
    }

    /** A question of a proposal and where the merge put it; immutable. */
    public static final class MergedQuestion {
        /** Why a question was merged: the same wording, a similarity at or above the band, or the model. */
        public enum How { WORTLAUT, AEHNLICHKEIT, MODELL }

        private final String id;
        private final String text;
        private final String proposalId;
        private final String topicId;
        private final String into;
        private final How how;

        MergedQuestion(String id, String text, String proposalId, String topicId, String into, How how) {
            this.id = id;
            this.text = text;
            this.proposalId = proposalId;
            this.topicId = topicId;
            this.into = into;
            this.how = how;
        }

        /** {@code <proposal id>-f<n>}. */
        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public String getProposalId() {
            return proposalId;
        }

        /** The merged topic the question belongs to. */
        public String getTopicId() {
            return topicId;
        }

        /** The question it was merged into, {@code ""} when it stands on its own. */
        public String getInto() {
            return into;
        }

        /** {@code null} when it stands on its own. */
        public How getHow() {
            return how;
        }
    }

    /** What one run decided; {@link #applyTo} writes it into the research memory. Immutable. */
    public static final class Result {
        private final List<TopicProposal> placed;
        private final List<MergedTopic> topics;
        private final List<MergedQuestion> questions;
        private final List<Comparison> comparisons;
        private final List<Comparison> asked;
        private final Map<String, String> heldBack;
        private final int groups;
        private final String model;
        private final long timeMillis;

        Result(List<TopicProposal> placed, List<MergedTopic> topics, List<MergedQuestion> questions,
               List<Comparison> comparisons, List<Comparison> asked, Map<String, String> heldBack, int groups,
               String model, long timeMillis) {
            this.placed = Collections.unmodifiableList(new ArrayList<TopicProposal>(placed));
            this.topics = Collections.unmodifiableList(new ArrayList<MergedTopic>(topics));
            this.questions = Collections.unmodifiableList(new ArrayList<MergedQuestion>(questions));
            this.comparisons = Collections.unmodifiableList(new ArrayList<Comparison>(comparisons));
            this.asked = Collections.unmodifiableList(new ArrayList<Comparison>(asked));
            this.heldBack = Collections.unmodifiableMap(new LinkedHashMap<String, String>(heldBack));
            this.groups = groups;
            this.model = model;
            this.timeMillis = timeMillis;
        }

        /** The proposals this run places, in the order they came. */
        public List<TopicProposal> getPlaced() {
            return placed;
        }

        /** The topics, the known ones that took proposals in first, then the new ones in the order they came. */
        public List<MergedTopic> getTopics() {
            return topics;
        }

        /** Every question of the placed proposals. */
        public List<MergedQuestion> getQuestions() {
            return questions;
        }

        /** Every doubtful case of this run, asked now or reused, topics first. */
        public List<Comparison> getComparisons() {
            return comparisons;
        }

        /** The cases asked now: what {@link TopicMergeLog} appends. */
        public List<Comparison> getAsked() {
            return asked;
        }

        /** The cases without a verdict. */
        public List<Comparison> getFailed() {
            List<Comparison> failed = new ArrayList<Comparison>();
            for (Comparison comparison : comparisons) {
                if (!comparison.isOk()) {
                    failed.add(comparison);
                }
            }
            return failed;
        }

        /** Proposals not placed, with the reason: a doubtful case of their group failed; the next run offers them. */
        public Map<String, String> getHeldBack() {
            return heldBack;
        }

        /** The groups of step 1 (proposals at or above the band, or with the same name). */
        public int getGroups() {
            return groups;
        }

        public MergedTopic topic(String id) {
            for (MergedTopic topic : topics) {
                if (topic.getId().equals(id)) {
                    return topic;
                }
            }
            return null;
        }

        /**
         * Write the run into the memory: every placed proposal becomes a topic of its own (with its finding) and its
         * questions open questions of the finder; then each merged topic takes its proposals in (a new topic with its
         * parent, or the known topic it is), and each question of the same meaning is merged into the one that stands.
         * A question the memory already merged by its wording stays where the memory put it.
         */
        public void applyTo(ResearchMemory memory) throws IOException {
            for (TopicProposal proposal : placed) {
                memory.addTopic(ResearchTopic.found(proposal.getId(), proposal.getName(), proposal.getDescription(),
                        proposal.hasFinding() ? Collections.singletonList(proposal.toFinding())
                                : Collections.<ResearchTopic.Finding>emptyList(), proposal.getModel(),
                        proposal.getTimeMillis()));
            }
            for (MergedTopic topic : parentsFirst(topics)) {
                if (topic.getMapping() == Mapping.GLEICH) {
                    ResearchTopic known = memory.topic(topic.getId());
                    List<String> ids = new ArrayList<String>(known.getMergedFrom());
                    ids.addAll(topic.getProposalIds());
                    memory.updateTopic(new ResearchTopic(known.getId(), known.getName(), known.getDescription(),
                            known.getOrigin(), known.getCardAnchorId(), ids, known.getFindings(), known.getFence(),
                            known.getFenceReason(), known.getWeight(), model, timeMillis, known.getParentId()));
                } else {
                    memory.addTopic(new ResearchTopic(topic.getId(), topic.getName(), topic.getDescription(),
                            ResearchTopic.Origin.GEFUNDEN, "", topic.getProposalIds(),
                            Collections.<ResearchTopic.Finding>emptyList(), ResearchTopic.Fence.UNGEPRUEFT, "", 0,
                            model, timeMillis, topic.getParentId()));
                }
            }
            for (MergedQuestion question : questions) {
                memory.addQuestion(ResearchQuestion.open(question.getId(), question.getText(),
                        question.getProposalId(), ResearchQuestion.Origin.FINDER, timeMillis));
            }
            for (MergedQuestion question : questions) {
                if (question.getInto().isEmpty()) {
                    continue;
                }
                ResearchQuestion held = memory.currentQuestion(question.getInto());
                ResearchQuestion own = memory.question(question.getId());
                if (held == null || own == null || !memory.currentQuestion(own.getId()).getId().equals(own.getId())
                        || held.getId().equals(own.getId())) {
                    continue; // merged already, by its wording
                }
                memory.updateQuestion(held.withMerged(Collections.singletonList(own), timeMillis));
            }
        }

        /** New topics after the topics they are subtopics of; the order stays otherwise. */
        private static List<MergedTopic> parentsFirst(List<MergedTopic> topics) {
            Map<String, MergedTopic> byId = new LinkedHashMap<String, MergedTopic>();
            for (MergedTopic topic : topics) {
                byId.put(topic.getId(), topic);
            }
            List<MergedTopic> ordered = new ArrayList<MergedTopic>();
            Set<String> done = new HashSet<String>();
            for (MergedTopic topic : topics) {
                addAfterParent(topic, byId, done, ordered);
            }
            return ordered;
        }

        private static void addAfterParent(MergedTopic topic, Map<String, MergedTopic> byId, Set<String> done,
                                           List<MergedTopic> ordered) {
            if (!done.add(topic.getId())) {
                return;
            }
            MergedTopic parent = byId.get(topic.getParentId());
            if (parent != null) {
                addAfterParent(parent, byId, done, ordered);
            }
            ordered.add(topic);
        }
    }

    /** The role: its own slot under Sources, a fixed schema, thinking off. */
    public static final ResearchRole ROLE = ResearchRole.builder("topic-merger",
                    ResearchModelSlots.PHASE_RESEARCH_MERGE)
            .purpose("component 15: decides the doubtful cases of the topic merge: two topics the same, one a "
                    + "subtopic of the other, or different; two questions of a topic the same or different")
            .promptFromCaller()
            .output(RoleOutput.schema(schema(true), "grund, beziehung (gleich, a_teil_von_b, b_teil_von_a, "
                    + "verschieden)"))
            .thinking(ThinkingMode.OFF)
            .build();

    /**
     * The version of the prompts and schemas (Fassung) of this build; each kind of case keeps the version of its own
     * prompt ({@link #versionOf}), and a verdict is reused only under that version. 1: the prompts of 21.11.2026. 2
     * (22.11.): the reason first, a narrower "part of" and "different" when in doubt, and in the card step the cards
     * with their questions and a question on the book's subject. 3 (22.11.): that question removed again, the card
     * step does not decide what belongs in the book (the architect, after Angelo's fence judgments). 4 (22.11.): the
     * card step names the question of the card the topic answers or deepens, and the parent step; the prompts for two
     * topics and two questions stay those of version 3.
     */
    public static final int PROMPT_VERSION = 4;

    /** The version of the prompt of a kind of case: 3 for two topics or two questions, 4 for the cards and parents. */
    public static int versionOf(Comparison.Kind kind) {
        return kind == Comparison.Kind.KARTE || kind == Comparison.Kind.OBERTHEMA ? 4 : 3;
    }
    static final double TEMPERATURE = 0.0;
    /** Answer budget: one word and one sentence. */
    static final int ANSWER_TOKENS = 300;
    /** Calls per case: the first and one repair attempt (rules of the build plan). */
    static final int ATTEMPTS = 2;
    static final String ID_PREFIX = "thema-";
    private static final int MISSION_CHARS = 1500;
    private static final int OTHER_NAMES = 3;
    private static final int REASON_CHARS = 300;
    /** The questions of a card the card step shows, each cut to {@link #QUESTION_CHARS} characters. */
    static final int CARD_QUESTIONS = 8;
    private static final int QUESTION_CHARS = 200;
    /** A case of the parent step needs at least this many heads, and a parent at least this many parts. */
    static final int PARENT_MIN = 3;
    /** At most this many heads in one case of the parent step. */
    static final int PARENT_MAX = 16;
    /** The rounds of the parent step: the heads a parent left out of its case are asked again. */
    static final int PARENT_ROUNDS = 3;
    /** How much the similarity rises when a group of heads is too large for one case. */
    static final double PARENT_RAISE = 0.05;
    /** Answer budget of a parent case: a sentence, a name, a sentence and the labels of the parts. */
    static final int PARENT_ANSWER_TOKENS = 500;
    /** The id a new parent topic is logged under in the card step, before it has its number. */
    static final String PARENT_ID_PREFIX = "oberthema/";
    private static final int PARENT_NAME_CHARS = 120;
    private static final int PARENT_DESCRIPTION_CHARS = 300;
    private static final int TOPIC_LINE_CHARS = 200;
    private static final int PARTS_SHOWN = 5;
    /** Tries of one embedding request: a timeout while the model loads beside a large chat model is tried again. */
    static final int EMBED_ATTEMPTS = 3;
    /** Pause before an embedding request is tried again. */
    static final long EMBED_PAUSE_MILLIS = 5000L;

    /**
     * Takes every answer of the model the moment it is there ({@link TopicMergeLog#append} in the app), so a run that
     * breaks off keeps what was asked. Called from the worker threads, one answer at a time per call.
     */
    public interface Recorder {
        void record(Comparison comparison) throws IOException;
    }

    private final MainModelChat chat;
    private final EmbeddingPort embeddings;
    private final String mission;
    private final boolean german;
    private final Options options;
    private final Consumer<String> log;
    private final Clock clock;
    private final Map<String, float[]> vectorCache = new HashMap<String, float[]>();
    private String cachedFingerprint = "";
    /** The pause before an embedding request is tried again; tests set 0. */
    volatile long embedPauseMillis = EMBED_PAUSE_MILLIS;

    /**
     * @param chat       the transport of the slot ({@link #ROLE})
     * @param embeddings the vectors of step 1 (bge-m3 in the app)
     * @param mission    the book's mission as the user wrote it
     * @param language   the working language ({@code "en"} for English prompts; anything else reads German)
     * @param options    the defaults when {@code null}
     * @param log        progress and errors; may be {@code null}
     */
    public TopicMerger(MainModelChat chat, EmbeddingPort embeddings, String mission, String language, Options options,
                       Consumer<String> log) {
        this(chat, embeddings, mission, language, options, log, Clock.systemUTC());
    }

    TopicMerger(MainModelChat chat, EmbeddingPort embeddings, String mission, String language, Options options,
                Consumer<String> log, Clock clock) {
        if (chat == null || embeddings == null) {
            throw new IllegalArgumentException("the topic merge needs a chat and an embedding model");
        }
        this.chat = chat;
        this.embeddings = embeddings;
        this.mission = mission == null ? "" : mission.trim();
        this.german = !"en".equalsIgnoreCase(language == null ? "" : language.trim());
        this.options = options == null ? Options.defaults() : options;
        this.log = log;
        this.clock = clock;
    }

    /** The proposals the memory does not hold as topics yet, in order: new ones and those held back before. */
    public static List<TopicProposal> unplaced(List<TopicProposal> proposals, ResearchMemory memory) {
        List<TopicProposal> open = new ArrayList<TopicProposal>();
        for (TopicProposal proposal : proposals) {
            if (memory.topic(proposal.getId()) == null) {
                open.add(proposal);
            }
        }
        return open;
    }

    // ----------------------------------------------------------------------------------------------- the run

    /**
     * Merge the proposals the memory does not hold yet ({@link #unplaced}) into the topics it knows and into new ones.
     * Nothing is written; {@link Result#applyTo} writes into the memory, {@link TopicMergeLog} keeps the cases asked.
     *
     * @param earlier the comparisons of earlier runs: an OK verdict on the same two texts is reused
     * @throws IllegalArgumentException when two proposals share an id
     * @throws IllegalStateException    when the embedding model fails (nothing is decided then; run again)
     */
    public Result merge(List<TopicProposal> proposals, ResearchMemory memory, List<Comparison> earlier) {
        return merge(proposals, memory, earlier, null);
    }

    /**
     * {@link #merge(List, ResearchMemory, List)}, and every answer of the model goes to {@code recorder} the moment it
     * is there (the cases of {@link Result#getAsked()}, in the order they finish).
     *
     * @param recorder {@code null} records nothing; a recorder that fails stops the merge
     */
    public Result merge(List<TopicProposal> proposals, ResearchMemory memory, List<Comparison> earlier,
                        Recorder recorder) {
        long now = clock.millis();
        List<TopicProposal> todo = unplaced(proposals, memory);
        Set<String> ids = new HashSet<String>();
        for (TopicProposal proposal : todo) {
            if (!ids.add(proposal.getId())) {
                throw new IllegalArgumentException("two proposals share the id " + proposal.getId());
            }
        }
        if (todo.isEmpty()) {
            return new Result(todo, Collections.<MergedTopic>emptyList(), Collections.<MergedQuestion>emptyList(),
                    Collections.<Comparison>emptyList(), Collections.<Comparison>emptyList(),
                    Collections.<String, String>emptyMap(), 0, chat.modelName(), now);
        }
        Map<String, Comparison> verdicts = reusable(earlier);
        List<ResearchTopic> known = memory.topics();

        List<Node> items = new ArrayList<Node>();
        for (TopicProposal proposal : todo) {
            items.add(Node.item(proposal.getId(), proposal.getName(), proposal.getDescription(),
                    embeddingText(proposal.getName(), proposal.getDescription()), proposal.getSourceId()));
        }
        List<Node> anchors = new ArrayList<Node>();
        Map<Node, List<String>> anchorTexts = new HashMap<Node, List<String>>();
        for (ResearchTopic topic : known) {
            List<String> memberNames = new ArrayList<String>();
            List<String> texts = new ArrayList<String>();
            texts.add(embeddingText(topic.getName(), topic.getDescription()));
            for (String id : topic.getMergedFrom()) {
                ResearchTopic member = memory.topic(id);
                if (member != null) {
                    texts.add(embeddingText(member.getName(), member.getDescription()));
                    memberNames.add(member.getName());
                }
            }
            boolean card = topic.getOrigin() == ResearchTopic.Origin.KONZEPT;
            List<String> cardQuestions = new ArrayList<String>();
            if (card) {
                for (ResearchQuestion question : memory.questions()) {
                    ResearchTopic of = memory.currentTopic(question.getTopicId());
                    if (of != null && of.getId().equals(topic.getId())) {
                        cardQuestions.add(question.getText());
                    }
                }
            }
            Node anchor = Node.anchor(topic.getId(), topic.getName(), topic.getDescription(), texts.get(0),
                    memberNames, card, levelsAbove(topic, memory), cardQuestions);
            anchors.add(anchor);
            anchorTexts.put(anchor, texts);
        }
        List<String> needed = new ArrayList<String>();
        for (Node item : items) {
            needed.add(item.embedText);
        }
        for (List<String> texts : anchorTexts.values()) {
            needed.addAll(texts);
        }
        // The questions too, before the first call: the embedding model is not loaded again between the chat calls.
        for (TopicProposal proposal : todo) {
            needed.addAll(proposal.getQuestions());
        }
        for (ResearchQuestion question : memory.questions()) {
            needed.add(question.getText());
        }
        embedAll(needed);
        for (Node item : items) {
            item.vectors.add(vectorCache.get(item.embedText));
        }
        for (Node anchor : anchors) {
            for (String text : anchorTexts.get(anchor)) {
                anchor.vectors.add(vectorCache.get(text));
            }
        }

        // Step 1 and the cases of step 2 for the topics.
        Pool topicPool = plan(items, anchors, options.getSame(), options.getDoubt(), Comparison.Kind.THEMA, "",
                options.isCardStep());
        List<Comparison> used = new ArrayList<Comparison>();
        List<Comparison> asked = new ArrayList<Comparison>();
        decide(Collections.singletonList(topicPool), verdicts, used, asked, recorder);
        Unions unions = unite(topicPool, true);
        List<CardCase> cardCases = options.isCardStep() ? cardStep(topicPool, unions, heads(topicPool, unions),
                verdicts, used, asked, recorder) : Collections.<CardCase>emptyList();

        // Held back: a failed case that could have merged something is asked again next run.
        Set<Integer> heldRoots = new HashSet<Integer>();
        Map<Integer, String> heldReason = new HashMap<Integer, String>();
        for (Edge edge : topicPool.edges) {
            if (edge.certain || edge.comparison == null || edge.comparison.isOk()) {
                continue;
            }
            int asking = unions.find(edge.group);
            int other = edge.toAnchor ? -1 : unions.find(edge.target);
            int hold = unions.anchorOf(asking) < 0 ? asking : other >= 0 && unions.anchorOf(other) < 0 ? other : -1;
            if (hold >= 0 && heldRoots.add(hold)) {
                heldReason.put(hold, "comparison with " + edge.comparison.getBId() + " failed: "
                        + edge.comparison.getProblem());
            }
        }
        // A failed card case holds the whole family back, so the next run puts it together again.
        for (CardCase card : cardCases) {
            if (card.comparison.isOk()) {
                continue;
            }
            for (int root : familyOf(card.root, topicPool.groups.size(), unions)) {
                if (heldRoots.add(root)) {
                    heldReason.put(root, "card step failed: " + card.comparison.getProblem());
                }
            }
        }
        // The parent step over the heads that stand apart; a failed case holds its heads back too.
        List<Parent> parents = options.isParentStep() ? parentStep(topicPool, unions, heldRoots, heldReason, verdicts,
                used, asked, recorder) : Collections.<Parent>emptyList();

        // The merged topics: known ones that took proposals in, then new ones by their first proposal.
        Map<Integer, List<Integer>> membersOfRoot = new LinkedHashMap<Integer, List<Integer>>();
        for (int g = 0; g < topicPool.groups.size(); g++) {
            int root = unions.find(g);
            List<Integer> members = membersOfRoot.get(root);
            if (members == null) {
                members = new ArrayList<Integer>();
                membersOfRoot.put(root, members);
            }
            members.addAll(topicPool.groups.get(g));
        }
        for (List<Integer> members : membersOfRoot.values()) {
            Collections.sort(members);
        }
        List<Integer> roots = new ArrayList<Integer>(membersOfRoot.keySet());
        Collections.sort(roots, (x, y) -> Integer.compare(membersOfRoot.get(x).get(0), membersOfRoot.get(y).get(0)));
        int next = nextNumber(known);
        Map<Integer, String> topicIdOfRoot = new HashMap<Integer, String>();
        for (int root : roots) {
            int anchor = unions.anchorOf(root);
            if (anchor >= 0) {
                topicIdOfRoot.put(root, anchors.get(anchor).id);
            } else if (!heldRoots.contains(root)) {
                topicIdOfRoot.put(root, String.format(Locale.ROOT, "%s%04d", ID_PREFIX, next++));
            }
        }
        for (Parent parent : parents) {
            if (parent.node >= 0 && !parent.held && unions.anchorOf(parent.node) < 0) {
                topicIdOfRoot.put(parent.node, String.format(Locale.ROOT, "%s%04d", ID_PREFIX, next++));
            }
        }
        Map<String, TopicProposal> proposalById = new HashMap<String, TopicProposal>();
        for (TopicProposal proposal : todo) {
            proposalById.put(proposal.getId(), proposal);
        }
        Map<String, String> knownParent = new HashMap<String, String>();
        for (ResearchTopic topic : known) {
            knownParent.put(topic.getId(), topic.getParentId());
        }
        Map<String, String> parentOfTopic = new HashMap<String, String>();
        for (int root : roots) {
            String id = topicIdOfRoot.get(root);
            if (id == null || unions.anchorOf(root) >= 0) {
                continue;
            }
            Integer parentNode = unions.parentOf.get(root);
            String parent = "";
            if (parentNode != null) {
                int parentRoot = unions.find(parentNode);
                if (unions.anchorOf(parentRoot) >= 0) {
                    parent = anchors.get(unions.anchorOf(parentRoot)).id;
                } else if (topicIdOfRoot.containsKey(parentRoot)) {
                    parent = topicIdOfRoot.get(parentRoot);
                }
            }
            parentOfTopic.put(id, parent);
        }
        for (Parent formed : parents) {
            String id = formed.node >= 0 ? topicIdOfRoot.get(formed.node) : null;
            if (id == null) {
                continue;
            }
            Integer up = unions.parentOf.get(formed.node);
            String parent = "";
            if (up != null) {
                int upRoot = unions.find(up);
                parent = unions.anchorOf(upRoot) >= 0 ? anchors.get(unions.anchorOf(upRoot)).id
                        : topicIdOfRoot.containsKey(upRoot) ? topicIdOfRoot.get(upRoot) : "";
            }
            parentOfTopic.put(id, parent);
        }

        // Questions: per merged topic, with the known questions of a known topic as anchors.
        List<TopicProposal> placed = new ArrayList<TopicProposal>();
        Map<String, String> heldBack = new LinkedHashMap<String, String>();
        List<Pool> questionPools = new ArrayList<Pool>();
        Map<Pool, Integer> rootOfPool = new HashMap<Pool, Integer>();
        Map<String, String> topicOfQuestionItem = new HashMap<String, String>();
        Map<String, String> proposalOfQuestion = new HashMap<String, String>();
        List<String> questionTexts = new ArrayList<String>();
        for (int root : roots) {
            String topicId = topicIdOfRoot.get(root);
            if (topicId == null) {
                for (int index : membersOfRoot.get(root)) {
                    heldBack.put(items.get(index).id, heldReason.get(root));
                }
                continue;
            }
            List<Node> questionItems = new ArrayList<Node>();
            for (int index : membersOfRoot.get(root)) {
                TopicProposal proposal = proposalById.get(items.get(index).id);
                placed.add(proposal);
                for (int q = 0; q < proposal.getQuestions().size(); q++) {
                    String text = proposal.getQuestions().get(q);
                    String questionId = proposal.getId() + "-f" + (q + 1);
                    questionItems.add(Node.item(questionId, text, "", text));
                    topicOfQuestionItem.put(questionId, topicId);
                    proposalOfQuestion.put(questionId, proposal.getId());
                    questionTexts.add(text);
                }
            }
            List<Node> questionAnchors = new ArrayList<Node>();
            if (unions.anchorOf(root) >= 0) {
                for (ResearchQuestion question : memory.questions()) {
                    ResearchTopic of = memory.currentTopic(question.getTopicId());
                    if (of != null && of.getId().equals(topicId)) {
                        questionAnchors.add(Node.anchor(question.getId(), question.getText()));
                        questionTexts.add(question.getText());
                    }
                }
            }
            if (questionItems.isEmpty()) {
                continue;
            }
            String topicName = unions.anchorOf(root) >= 0 ? anchors.get(unions.anchorOf(root)).name
                    : items.get(central(membersOfRoot.get(root), items)).name;
            Pool pool = new Pool(questionItems, questionAnchors, Comparison.Kind.FRAGE, topicName);
            questionPools.add(pool);
            rootOfPool.put(pool, root);
        }
        Collections.sort(placed, (x, y) -> Integer.compare(indexOf(items, x.getId()), indexOf(items, y.getId())));
        embedAll(questionTexts);
        List<Pool> plannedPools = new ArrayList<Pool>();
        for (Pool pool : questionPools) {
            for (Node node : pool.items) {
                node.vectors.add(vectorCache.get(node.embedText));
            }
            for (Node node : pool.anchors) {
                node.vectors.add(vectorCache.get(node.embedText));
            }
            Pool planned = plan(pool.items, pool.anchors, options.getQuestionSame(), options.getQuestionDoubt(),
                    Comparison.Kind.FRAGE, pool.context, false);
            plannedPools.add(planned);
            rootOfPool.put(planned, rootOfPool.get(pool));
        }
        decide(plannedPools, verdicts, used, asked, recorder);

        List<MergedQuestion> questions = new ArrayList<MergedQuestion>();
        Map<String, List<String>> standingOfTopic = new HashMap<String, List<String>>();
        for (Pool pool : plannedPools) {
            Unions q = unite(pool, false);
            String topicId = topicIdOfRoot.get(rootOfPool.get(pool));
            Map<Integer, String> survivorOfRoot = new HashMap<Integer, String>();
            Map<Integer, Integer> survivorGroup = new HashMap<Integer, Integer>();
            List<String> standing = new ArrayList<String>();
            for (Node anchor : pool.anchors) {
                standing.add(anchor.id);
            }
            for (int g = 0; g < pool.groups.size(); g++) {
                int root = q.find(g);
                if (q.anchorOf(root) >= 0) {
                    survivorOfRoot.put(root, pool.anchors.get(q.anchorOf(root)).id);
                }
            }
            for (int i = 0; i < pool.items.size(); i++) {
                int g = pool.groupOf[i];
                int root = q.find(g);
                if (!survivorOfRoot.containsKey(root)) {
                    survivorOfRoot.put(root, pool.items.get(i).id);
                    survivorGroup.put(root, g);
                    standing.add(pool.items.get(i).id);
                }
            }
            for (int i = 0; i < pool.items.size(); i++) {
                Node node = pool.items.get(i);
                int g = pool.groupOf[i];
                int root = q.find(g);
                String into = survivorOfRoot.get(root);
                MergedQuestion.How how = null;
                if (into.equals(node.id)) {
                    into = "";
                } else {
                    String intoKey = keyOf(pool, into);
                    Integer intoGroup = survivorGroup.get(root);
                    how = node.key.equals(intoKey) ? MergedQuestion.How.WORTLAUT
                            : intoGroup != null && intoGroup == g ? MergedQuestion.How.AEHNLICHKEIT
                            : q.viaModel.contains(root) ? MergedQuestion.How.MODELL : MergedQuestion.How.AEHNLICHKEIT;
                }
                questions.add(new MergedQuestion(node.id, node.name, proposalOfQuestion.get(node.id), topicId, into,
                        how));
            }
            standingOfTopic.put(topicId, standing);
        }
        Collections.sort(questions, (x, y) -> {
            int byProposal = Integer.compare(indexOf(items, x.getProposalId()), indexOf(items, y.getProposalId()));
            return byProposal != 0 ? byProposal : x.getId().compareTo(y.getId());
        });

        // The topics as the result gives them.
        List<MergedTopic> topics = new ArrayList<MergedTopic>();
        List<MergedTopic> fresh = new ArrayList<MergedTopic>();
        for (int root : roots) {
            String id = topicIdOfRoot.get(root);
            if (id == null) {
                continue;
            }
            List<Integer> members = membersOfRoot.get(root);
            List<String> proposalIds = new ArrayList<String>();
            List<ResearchTopic.Finding> findings = new ArrayList<ResearchTopic.Finding>();
            Set<String> placesSeen = new HashSet<String>();
            Set<String> sources = new LinkedHashSet<String>();
            for (int index : members) {
                TopicProposal proposal = proposalById.get(items.get(index).id);
                proposalIds.add(proposal.getId());
                sources.add(proposal.getSourceId());
                if (proposal.hasFinding() && placesSeen.add(proposal.getSourceId() + "\u0000"
                        + proposal.getPassageId() + "\u0000" + wording(proposal.getQuote()))) {
                    findings.add(proposal.toFinding());
                }
            }
            List<String> standing = standingOfTopic.containsKey(id) ? standingOfTopic.get(id)
                    : Collections.<String>emptyList();
            int anchor = unions.anchorOf(root);
            if (anchor >= 0) {
                Node known1 = anchors.get(anchor);
                topics.add(new MergedTopic(id, known1.name, known1.description, Mapping.GLEICH,
                        knownParent.containsKey(id) ? knownParent.get(id) : "", id, proposalIds, findings,
                        new ArrayList<String>(sources), standing));
            } else {
                Node center = items.get(central(members, items));
                String parent = parentOfTopic.get(id);
                fresh.add(new MergedTopic(id, center.name, center.description,
                        parent.isEmpty() ? Mapping.NEU : Mapping.UNTERTHEMA, parent, "", proposalIds, findings,
                        new ArrayList<String>(sources), standing));
            }
        }
        int formedTopics = 0;
        for (Parent formed : parents) {
            String id = formed.node >= 0 ? topicIdOfRoot.get(formed.node) : null;
            if (id == null) {
                continue;
            }
            String parent = parentOfTopic.get(id);
            fresh.add(new MergedTopic(id, formed.name, formed.description,
                    parent.isEmpty() ? Mapping.NEU : Mapping.UNTERTHEMA, parent, "", Collections.<String>emptyList(),
                    Collections.<ResearchTopic.Finding>emptyList(), Collections.<String>emptyList(),
                    Collections.<String>emptyList(), true));
            formedTopics++;
        }
        Set<String> knownIds = new HashSet<String>(knownParent.keySet());
        for (MergedTopic topic : fresh) {
            topics.add(new MergedTopic(topic.getId(), topic.getName(), topic.getDescription(), topic.getMapping(),
                    topic.getParentId(), knownAbove(topic.getId(), parentOfTopic, knownParent, knownIds),
                    topic.getProposalIds(), topic.getFindings(), topic.getSourceIds(), topic.getQuestionIds(),
                    topic.isFormed()));
        }
        log("topic merge: " + todo.size() + " proposals, " + topicPool.groups.size() + " groups, " + topics.size()
                + " topics (" + (topics.size() - fresh.size()) + " known, " + formedTopics + " parents formed), "
                + heldBack.size() + " held back, " + cardCases.size() + " card cases, " + parents.size()
                + " parents named, " + asked.size() + " model calls, " + (used.size() - asked.size()) + " reused");
        return new Result(placed, topics, questions, used, asked, heldBack, topicPool.groups.size(),
                chat.modelName(), now);
    }

    /** The known topic a new topic lies under through its parents, {@code ""} when none. */
    private static String knownAbove(String id, Map<String, String> parentOfTopic, Map<String, String> knownParent,
                                     Set<String> knownIds) {
        Set<String> seen = new HashSet<String>();
        String at = parentOfTopic.containsKey(id) ? parentOfTopic.get(id) : "";
        while (!at.isEmpty() && seen.add(at)) {
            if (knownIds.contains(at)) {
                return at;
            }
            at = parentOfTopic.containsKey(at) ? parentOfTopic.get(at) : "";
        }
        return "";
    }

    /** The levels above a known topic in the memory: 0 for a topic under none. */
    private static int levelsAbove(ResearchTopic topic, ResearchMemory memory) {
        int levels = 0;
        Set<String> seen = new HashSet<String>();
        seen.add(topic.getId());
        String at = topic.getParentId();
        while (at != null && !at.isEmpty()) {
            ResearchTopic parent = memory.currentTopic(at);
            if (parent == null || !seen.add(parent.getId())) {
                break;
            }
            levels++;
            at = parent.getParentId();
        }
        return levels;
    }

    private static String keyOf(Pool pool, String id) {
        for (Node node : pool.items) {
            if (node.id.equals(id)) {
                return node.key;
            }
        }
        for (Node node : pool.anchors) {
            if (node.id.equals(id)) {
                return node.key;
            }
        }
        return "";
    }

    private static int indexOf(List<Node> items, String id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id.equals(id)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** One past the highest {@code thema-NNNN} the memory holds. */
    private static int nextNumber(List<ResearchTopic> known) {
        int highest = 0;
        for (ResearchTopic topic : known) {
            String id = topic.getId();
            if (id.startsWith(ID_PREFIX) && id.substring(ID_PREFIX.length()).matches("[0-9]{1,9}")) {
                highest = Math.max(highest, Integer.parseInt(id.substring(ID_PREFIX.length())));
            }
        }
        return highest + 1;
    }

    // ----------------------------------------------------------------------------------------------- step 1

    /** Something to merge: a proposal or a question (an item), or a known topic or question (an anchor). */
    private static final class Node {
        final String id;
        final String name;
        final String description;
        final String key;
        final String embedText;
        final List<String> otherNames;
        final boolean anchor;
        /** A card of the mind map (a known topic of origin KONZEPT). */
        final boolean card;
        /** The source of a proposal; {@code ""} for a question and an anchor. */
        final String source;
        /** For a known topic: the levels above it in the memory (0 for a topic under none). */
        final int depth;
        /** For a card: its questions, as the card step shows them. */
        final List<String> questions;
        /** Unit vectors: one for an item; for an anchor its own and those of the topics merged into it. */
        final List<float[]> vectors = new ArrayList<float[]>();

        private Node(String id, String name, String description, String embedText, List<String> otherNames,
                     boolean anchor, boolean card, String source, int depth, List<String> questions) {
            this.id = id;
            this.name = clean(name);
            this.description = clean(description);
            this.key = wording(name);
            this.embedText = embedText;
            this.otherNames = otherNames;
            this.anchor = anchor;
            this.card = card;
            this.source = clean(source);
            this.depth = Math.max(0, depth);
            this.questions = questions;
        }

        /** A question of a proposal. */
        static Node item(String id, String name, String description, String embedText) {
            return item(id, name, description, embedText, "");
        }

        /** A proposal of a source. */
        static Node item(String id, String name, String description, String embedText, String source) {
            return new Node(id, name, description, embedText, Collections.<String>emptyList(), false, false, source,
                    0, Collections.<String>emptyList());
        }

        /** A known question. */
        static Node anchor(String id, String name) {
            return new Node(id, name, "", name, Collections.<String>emptyList(), true, false, "", 0,
                    Collections.<String>emptyList());
        }

        /** A known topic, a card with its questions. */
        static Node anchor(String id, String name, String description, String embedText, List<String> memberNames,
                           boolean card, int depth, List<String> questions) {
            return new Node(id, name, description, embedText, memberNames, true, card, "", depth, questions);
        }
    }

    /** The groups of step 1 and the cases they call for. */
    private static final class Pool {
        final List<Node> items;
        final List<Node> anchors;
        final Comparison.Kind kind;
        final String context;
        final List<List<Integer>> groups = new ArrayList<List<Integer>>();
        final List<float[]> centroids = new ArrayList<float[]>();
        final List<Edge> edges = new ArrayList<Edge>();
        int[] groupOf;

        Pool(List<Node> items, List<Node> anchors, Comparison.Kind kind, String context) {
            this.items = items;
            this.anchors = anchors;
            this.kind = kind;
            this.context = context;
        }
    }

    /** A group's nearest known topic or nearest larger group, at or above the lower end of the band. */
    private static final class Edge {
        final int group;
        final int target;
        final boolean toAnchor;
        final double similarity;
        final boolean certain;
        Comparison comparison;

        Edge(int group, int target, boolean toAnchor, double similarity, boolean certain) {
            this.group = group;
            this.target = target;
            this.toAnchor = toAnchor;
            this.similarity = similarity;
            this.certain = certain;
        }

        /** GLEICH for a certain edge, the verdict for a decided one, {@code null} without a verdict. */
        Relation relation() {
            if (certain) {
                return Relation.GLEICH;
            }
            return comparison == null || !comparison.isOk() ? null : comparison.getRelation();
        }
    }

    /**
     * Step 1: groups of items at or above {@code same} (average linkage) or with the same wording, largest first; each
     * group's nearest anchors and nearest earlier groups at or above {@code doubt}, up to
     * {@link Options#getCandidates()} of each. An anchor of the same wording is the only anchor of its group. With
     * {@code cardsApart} a card is a candidate only at or above {@code same}: its doubtful cases go to the card step.
     * A group with a certain candidate (the same wording, or at or above {@code same}) gets no doubtful case: it joins
     * that candidate, and so a topic fed in again is placed without the model.
     */
    private Pool plan(List<Node> items, List<Node> anchors, double same, double doubt, Comparison.Kind kind,
                      String context, boolean cardsApart) {
        Pool pool = new Pool(items, anchors, kind, context);
        int n = items.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (List<Integer> cluster : clusters(items, same)) {
            for (int i : cluster) {
                join(parent, cluster.get(0), i);
            }
        }
        Map<String, Integer> firstOfKey = new HashMap<String, Integer>();
        for (int i = 0; i < n; i++) {
            if (items.get(i).key.isEmpty()) {
                continue;
            }
            Integer first = firstOfKey.get(items.get(i).key);
            if (first == null) {
                firstOfKey.put(items.get(i).key, i);
            } else {
                join(parent, first, i);
            }
        }
        Map<Integer, List<Integer>> classes = new LinkedHashMap<Integer, List<Integer>>();
        for (int i = 0; i < n; i++) {
            int root = root(parent, i);
            List<Integer> members = classes.get(root);
            if (members == null) {
                members = new ArrayList<Integer>();
                classes.put(root, members);
            }
            members.add(i);
        }
        List<List<Integer>> groups = new ArrayList<List<Integer>>(classes.values());
        Collections.sort(groups, new Comparator<List<Integer>>() {
            @Override
            public int compare(List<Integer> x, List<Integer> y) {
                return x.size() != y.size() ? Integer.compare(y.size(), x.size()) : Integer.compare(x.get(0), y.get(0));
            }
        });
        pool.groups.addAll(groups);
        pool.groupOf = new int[n];
        for (int g = 0; g < groups.size(); g++) {
            List<float[]> vectors = new ArrayList<float[]>();
            for (int i : groups.get(g)) {
                pool.groupOf[i] = g;
                vectors.add(items.get(i).vectors.get(0));
            }
            pool.centroids.add(unit(mean(vectors)));
        }
        Map<String, Integer> anchorOfKey = new HashMap<String, Integer>();
        for (int a = 0; a < anchors.size(); a++) {
            if (!anchors.get(a).key.isEmpty() && !anchorOfKey.containsKey(anchors.get(a).key)) {
                anchorOfKey.put(anchors.get(a).key, a);
            }
        }
        int perKind = options.getCandidates();
        for (int g = 0; g < groups.size(); g++) {
            float[] centroid = pool.centroids.get(g);
            List<Edge> certain = new ArrayList<Edge>();
            List<Edge> doubtful = new ArrayList<Edge>();
            Integer sameWording = null;
            for (int i : groups.get(g)) {
                sameWording = anchorOfKey.get(items.get(i).key);
                if (sameWording != null) {
                    break;
                }
            }
            if (sameWording != null) {
                certain.add(new Edge(g, sameWording, true, 1.0, true));
            } else {
                double[] toAnchors = new double[anchors.size()];
                for (int a = 0; a < anchors.size(); a++) {
                    toAnchors[a] = -2;
                    for (float[] vector : anchors.get(a).vectors) {
                        toAnchors[a] = Math.max(toAnchors[a], dot(centroid, vector));
                    }
                    if (cardsApart && anchors.get(a).card && toAnchors[a] < same) {
                        toAnchors[a] = -2;
                    }
                }
                for (int a : nearest(toAnchors, perKind, doubt)) {
                    Edge edge = new Edge(g, a, true, toAnchors[a], toAnchors[a] >= same);
                    (edge.certain ? certain : doubtful).add(edge);
                }
            }
            double[] toGroups = new double[g];
            for (int h = 0; h < g; h++) {
                toGroups[h] = dot(centroid, pool.centroids.get(h));
            }
            for (int h : nearest(toGroups, perKind, doubt)) {
                Edge edge = new Edge(g, h, false, toGroups[h], toGroups[h] >= same);
                (edge.certain ? certain : doubtful).add(edge);
            }
            pool.edges.addAll(certain.isEmpty() ? doubtful : certain);
        }
        return pool;
    }

    /** The indexes of the highest scores at or above the floor, at most {@code count}, highest first. */
    private static List<Integer> nearest(final double[] scores, int count, double floor) {
        List<Integer> indexes = new ArrayList<Integer>();
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] >= floor) {
                indexes.add(i);
            }
        }
        Collections.sort(indexes, (x, y) -> scores[x] != scores[y] ? Double.compare(scores[y], scores[x])
                : Integer.compare(x, y));
        return indexes.size() <= count ? indexes : new ArrayList<Integer>(indexes.subList(0, count));
    }

    /** Average-linkage groups at or above the similarity: the clusterer of the topic analysis over stand-ins. */
    private static List<List<Integer>> clusters(List<Node> items, double same) {
        List<List<Integer>> out = new ArrayList<List<Integer>>();
        if (items.size() < 2) {
            if (items.size() == 1) {
                out.add(Collections.singletonList(0));
            }
            return out;
        }
        List<Passage> standIns = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> vectors = new HashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int i = 0; i < items.size(); i++) {
            String id = Integer.toString(i);
            standIns.add(new Passage(id, "", null, "", "", "merge"));
            vectors.put(id, new EmbeddingPort.EmbeddingVector("merge", "merge", items.get(i).vectors.get(0)));
        }
        double cut = 1.0 - same + 1e-9;
        TopicClusterer.Tree tree = new TopicClusterer(IdSequence.counting(), cut, 1).clusterTree(standIns, vectors,
                ClusteringMonitor.NONE);
        for (List<String> group : tree.getDendrogram().cut(cut)) {
            List<Integer> indexes = new ArrayList<Integer>();
            for (String id : group) {
                indexes.add(Integer.parseInt(id));
            }
            out.add(indexes);
        }
        return out;
    }

    /** The member closest to the members' mean; ties (within float precision) go to the first. */
    private static int central(List<Integer> members, List<Node> items) {
        List<float[]> vectors = new ArrayList<float[]>();
        for (int i : members) {
            vectors.add(items.get(i).vectors.get(0));
        }
        float[] centroid = unit(mean(vectors));
        int best = members.get(0);
        double bestSimilarity = -2;
        for (int i : members) {
            double similarity = dot(centroid, items.get(i).vectors.get(0));
            if (similarity > bestSimilarity + 1e-6) {
                bestSimilarity = similarity;
                best = i;
            }
        }
        return best;
    }

    // ----------------------------------------------------------------------------------------------- step 2

    /**
     * The groups joined by the verdicts; for topics also which group is a subtopic of which. Nodes: the groups, then
     * the known topics, then the parent topics the parent step forms ({@link #addNode}).
     */
    private static final class Unions {
        final int groups;
        int[] parent;
        int[] anchor;
        final int[] anchorDepth;
        final Map<Integer, Integer> parentOf = new HashMap<Integer, Integer>();
        final Set<Integer> viaModel = new HashSet<Integer>();

        Unions(int groups, List<Node> anchors) {
            this.groups = groups;
            parent = new int[groups + anchors.size()];
            anchor = new int[groups + anchors.size()];
            anchorDepth = new int[anchors.size()];
            for (int i = 0; i < parent.length; i++) {
                parent[i] = i;
                anchor[i] = i >= groups ? i - groups : -1;
            }
            for (int a = 0; a < anchors.size(); a++) {
                anchorDepth[a] = anchors.get(a).depth;
            }
        }

        int find(int node) {
            return root(parent, node);
        }

        /** A node for a parent topic the parent step forms: no group and no known topic. */
        int addNode() {
            int node = parent.length;
            parent = Arrays.copyOf(parent, node + 1);
            anchor = Arrays.copyOf(anchor, node + 1);
            parent[node] = node;
            anchor[node] = -1;
            return node;
        }

        int anchorOf(int root) {
            return anchor[find(root)];
        }

        /** The levels above a node: through its parents up to a top, a known topic counting its own levels. */
        int depth(int node) {
            int at = find(node);
            int levels = 0;
            Set<Integer> seen = new HashSet<Integer>();
            while (seen.add(at)) {
                if (anchor[at] >= 0) {
                    return levels + anchorDepth[anchor[at]];
                }
                Integer up = parentOf.get(at);
                if (up == null) {
                    return levels;
                }
                at = find(up);
                levels++;
            }
            return levels;
        }

        /** The levels of new topics below a node: the longest chain of parents that ends at it. */
        int height(int node) {
            int top = find(node);
            int height = 0;
            for (int g = 0; g < groups; g++) {
                int at = find(g);
                int levels = 0;
                Set<Integer> seen = new HashSet<Integer>();
                while (at != top && seen.add(at)) {
                    Integer up = parentOf.get(at);
                    if (up == null) {
                        break;
                    }
                    at = find(up);
                    levels++;
                }
                if (at == top) {
                    height = Math.max(height, levels);
                }
            }
            return height;
        }
    }

    /**
     * The GLEICH edges join, the most similar first, unless they would join two known ones; for topics, a group that
     * is part of another becomes its subtopic: the most similar such edge per group whose parent is established (a
     * known topic, or a new one with proposals of {@link Options#getParentSources()} sources) and that hangs the group
     * with everything below it at most {@link Options#getDepth()} levels below its top; no known topic below a new
     * one, no circle. Only GLEICH merges; "part of" only places a topic.
     */
    private Unions unite(Pool pool, boolean subtopics) {
        int groups = pool.groups.size();
        Unions unions = new Unions(groups, pool.anchors);
        List<Edge> edges = new ArrayList<Edge>(pool.edges);
        Collections.sort(edges, new Comparator<Edge>() {
            @Override
            public int compare(Edge x, Edge y) {
                int bySimilarity = Double.compare(y.similarity, x.similarity);
                return bySimilarity != 0 ? bySimilarity : Integer.compare(x.group, y.group);
            }
        });
        for (Edge edge : edges) {
            if (edge.relation() != Relation.GLEICH) {
                continue;
            }
            int a = unions.find(edge.group);
            int b = unions.find(edge.toAnchor ? groups + edge.target : edge.target);
            if (a == b || (unions.anchor[a] >= 0 && unions.anchor[b] >= 0)) {
                continue;
            }
            int keep = unions.anchor[b] >= 0 || (unions.anchor[a] < 0 && b < a) ? b : a;
            int drop = keep == a ? b : a;
            unions.parent[drop] = keep;
            if (!edge.certain) {
                unions.viaModel.add(keep);
            }
            if (unions.viaModel.remove(drop)) {
                unions.viaModel.add(keep);
            }
        }
        if (!subtopics) {
            return unions;
        }
        Map<Integer, Set<String>> sourcesOfRoot = new HashMap<Integer, Set<String>>();
        for (int i = 0; i < pool.items.size(); i++) {
            int root = unions.find(pool.groupOf[i]);
            if (!sourcesOfRoot.containsKey(root)) {
                sourcesOfRoot.put(root, new HashSet<String>());
            }
            if (!pool.items.get(i).source.isEmpty()) {
                sourcesOfRoot.get(root).add(pool.items.get(i).source);
            }
        }
        for (Edge edge : edges) {
            Relation relation = edge.relation();
            if (relation != Relation.A_TEIL_VON_B && relation != Relation.B_TEIL_VON_A) {
                continue;
            }
            int a = unions.find(edge.group);
            int b = unions.find(edge.toAnchor ? groups + edge.target : edge.target);
            int child = relation == Relation.A_TEIL_VON_B ? a : b;
            int parentNode = child == a ? b : a;
            if (child == parentNode || unions.anchor[child] >= 0 || unions.parentOf.containsKey(child)) {
                continue;
            }
            boolean established = unions.anchor[parentNode] >= 0 || (sourcesOfRoot.containsKey(parentNode)
                    && sourcesOfRoot.get(parentNode).size() >= options.getParentSources());
            if (!established || unions.depth(parentNode) + 1 + unions.height(child) > options.getDepth()) {
                continue;
            }
            boolean circle = false;
            Set<Integer> seen = new HashSet<Integer>();
            for (Integer at = parentNode; at != null && seen.add(at); at = unions.parentOf.get(at)) {
                if (at == child) {
                    circle = true;
                    break;
                }
            }
            if (!circle) {
                unions.parentOf.put(child, parentNode);
            }
        }
        return unions;
    }

    /** The verdicts for the doubtful edges: reused when the same two texts were judged before, else asked. */
    private void decide(List<Pool> pools, Map<String, Comparison> verdicts, List<Comparison> used,
                        List<Comparison> asked, Recorder recorder) {
        List<Edge> open = new ArrayList<Edge>();
        List<String[]> requests = new ArrayList<String[]>();
        List<Pool> poolOfOpen = new ArrayList<Pool>();
        for (Pool pool : pools) {
            for (Edge edge : pool.edges) {
                if (edge.certain) {
                    continue;
                }
                String a = shown(pool, edge.group);
                String b = edge.toAnchor ? shownAnchor(pool, pool.anchors.get(edge.target))
                        : shown(pool, edge.target);
                String aId = pool.items.get(pool.groups.get(edge.group).get(0)).id;
                String bId = edge.toAnchor ? pool.anchors.get(edge.target).id
                        : pool.items.get(pool.groups.get(edge.target).get(0)).id;
                Comparison earlier = verdicts.get(Comparison.key(pool.kind, a, b));
                Comparison flipped = verdicts.get(Comparison.key(pool.kind, b, a));
                if (earlier != null) {
                    edge.comparison = earlier;
                    used.add(earlier);
                } else if (flipped != null) {
                    edge.comparison = new Comparison(pool.kind, aId, a, bId, b, edge.toAnchor, edge.similarity,
                            Comparison.Status.OK, flipped.getRelation().flipped(), flipped.getReason(), "",
                            flipped.getAnswers(), flipped.getModel(), flipped.getTimeMillis(), 0L,
                            flipped.getVersion());
                    used.add(edge.comparison);
                } else {
                    open.add(edge);
                    requests.add(new String[]{aId, a, bId, b});
                    poolOfOpen.add(pool);
                }
            }
        }
        List<Case> cases = new ArrayList<Case>();
        for (int i = 0; i < open.size(); i++) {
            final Edge edge = open.get(i);
            final String[] request = requests.get(i);
            final Pool pool = poolOfOpen.get(i);
            cases.add(new Case() {
                @Override
                public Comparison ask() {
                    return TopicMerger.this.ask(pool.kind, pool.context, request, edge.toAnchor, edge.similarity);
                }

                @Override
                public Comparison notAsked(String why) {
                    return new Comparison(pool.kind, request[0], request[1], request[2], request[3], edge.toAnchor,
                            edge.similarity, Comparison.Status.FEHLER, null, "", why, null, chat.modelName(),
                            clock.millis(), 0L);
                }
            });
        }
        List<Comparison> results = run(cases, recorder);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).comparison = results.get(i);
            used.add(results.get(i));
            asked.add(results.get(i));
        }
    }

    /** A case for the model: how it is asked, and the FEHLER it becomes when the batch stops before it. */
    private interface Case {
        Comparison ask();

        Comparison notAsked(String why);
    }

    /**
     * The cases, at most {@link Options#getParallel()} at a time, the results in the order of the cases; after
     * {@link Options#STOP_AFTER_FAILURES} failed cases in a row the rest is not asked (FEHLER). Each result goes to
     * the recorder as soon as it is there.
     */
    private List<Comparison> run(List<Case> cases, final Recorder recorder) {
        List<Comparison> results = new ArrayList<Comparison>();
        if (cases.isEmpty()) {
            return results;
        }
        final AtomicInteger failuresInRow = new AtomicInteger();
        final AtomicReference<String> lastFailure = new AtomicReference<String>("");
        final AtomicBoolean unrecorded = new AtomicBoolean();
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(options.getParallel(), cases.size()));
        List<Future<Comparison>> running = new ArrayList<Future<Comparison>>();
        try {
            for (final Case next : cases) {
                running.add(workers.submit(() -> {
                    if (unrecorded.get()) {
                        return next.notAsked("not asked: an earlier answer could not be recorded");
                    }
                    if (failuresInRow.get() >= Options.STOP_AFTER_FAILURES) {
                        return recorded(next.notAsked("not asked: the model failed " + Options.STOP_AFTER_FAILURES
                                + " cases in a row; last: " + lastFailure.get()), recorder, unrecorded);
                    }
                    Comparison result = next.ask();
                    if (result.isOk()) {
                        failuresInRow.set(0);
                    } else {
                        lastFailure.set(result.getProblem());
                        failuresInRow.incrementAndGet();
                    }
                    return recorded(result, recorder, unrecorded);
                }));
            }
            for (Future<Comparison> result : running) {
                results.add(result.get());
            }
        } catch (ExecutionException crashed) {
            throw new IllegalStateException("topic merge crashed", crashed.getCause());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("topic merge interrupted", interrupted);
        } finally {
            workers.shutdownNow();
        }
        return results;
    }

    /** The result after the recorder took it; a failed recording stops the cases that have not begun. */
    private static Comparison recorded(Comparison result, Recorder recorder, AtomicBoolean unrecorded) {
        if (recorder != null) {
            try {
                recorder.record(result);
            } catch (IOException failed) {
                unrecorded.set(true);
                throw new UncheckedIOException("an answer of the topic merge could not be recorded", failed);
            }
        }
        return result;
    }

    /** A head of a family, or a parent topic of the parent step, put to the card step, with its verdict. */
    private static final class CardCase {
        final int root;
        Comparison comparison;

        CardCase(int root) {
            this.root = root;
        }
    }

    /** What the card step shows of a topic: its node, the id it is logged under, its text and its mean vector. */
    private static final class CardSubject {
        final int root;
        final String aId;
        final String aText;
        final float[] centroid;

        CardSubject(int root, String aId, String aText, float[] centroid) {
            this.root = root;
            this.aId = aId;
            this.aText = aText;
            this.centroid = centroid;
        }
    }

    /** The proposals of each merged topic (a group root after the unions), in order. */
    private static Map<Integer, List<Integer>> itemsOfRoots(Pool pool, Unions unions) {
        Map<Integer, List<Integer>> itemsOf = new LinkedHashMap<Integer, List<Integer>>();
        for (int g = 0; g < pool.groups.size(); g++) {
            int root = unions.find(g);
            if (!itemsOf.containsKey(root)) {
                itemsOf.put(root, new ArrayList<Integer>());
            }
            itemsOf.get(root).addAll(pool.groups.get(g));
        }
        for (List<Integer> items : itemsOf.values()) {
            Collections.sort(items);
        }
        return itemsOf;
    }

    /** The mean unit vector of some proposals. */
    private static float[] centroidOf(Pool pool, List<Integer> items) {
        List<float[]> vectors = new ArrayList<float[]>();
        for (int i : items) {
            vectors.add(pool.items.get(i).vectors.get(0));
        }
        return unit(mean(vectors));
    }

    /**
     * The heads of the families: merged topics that are no known topic and lie under no topic, in the order of their
     * first proposal.
     */
    private List<CardSubject> heads(Pool pool, Unions unions) {
        Map<Integer, List<Integer>> itemsOf = itemsOfRoots(pool, unions);
        List<CardSubject> heads = new ArrayList<CardSubject>();
        for (Map.Entry<Integer, List<Integer>> entry : itemsOf.entrySet()) {
            int root = entry.getKey();
            if (unions.anchorOf(root) >= 0 || unions.parentOf.containsKey(root)) {
                continue;
            }
            List<Integer> items = entry.getValue();
            heads.add(new CardSubject(root, pool.items.get(items.get(0)).id, shownMembers(pool, items),
                    centroidOf(pool, items)));
        }
        Collections.sort(heads, (x, y) -> Integer.compare(itemsOf.get(x.root).get(0), itemsOf.get(y.root).get(0)));
        return heads;
    }

    /**
     * The card step: every head of a family (a merged topic that is no known topic and lies under no topic), and every
     * parent topic the parent step formed, is put to the model once with all cards of the mind map and their
     * questions, because cards have broad names that a similarity does not reach. The same as a card merges the topic
     * into the card; part of a card (it answers or deepens one of the card's questions) makes it a subtopic of the
     * card; none leaves it where it is. Both need the question of the chosen card the topic answers or deepens: an
     * answer without one counts as none, so the card's subject alone does not draw a topic in (the architect's fourth
     * run of 22.11.: general law under the card on law). A card without questions is left out. A topic whose family
     * would then reach deeper than {@link Options#getDepth()} levels stays where it is. A verdict on the same topic
     * text and the same list of cards is reused. The verdicts are applied to the unions; the cases come back for the
     * held-back rule.
     */
    private List<CardCase> cardStep(Pool pool, Unions unions, List<CardSubject> subjects,
                                    Map<String, Comparison> verdicts, List<Comparison> used, List<Comparison> asked,
                                    Recorder recorder) {
        int groups = pool.groups.size();
        final List<String> cardIds = new ArrayList<String>();
        List<Integer> cards = new ArrayList<Integer>();
        for (int a = 0; a < pool.anchors.size(); a++) {
            if (pool.anchors.get(a).card && !shownQuestions(pool.anchors.get(a)).isEmpty()) {
                cards.add(a);
                cardIds.add(pool.anchors.get(a).id);
            }
        }
        List<CardCase> cases = new ArrayList<CardCase>();
        if (cards.isEmpty() || subjects.isEmpty()) {
            return cases;
        }
        final CardList list = cardList(pool.anchors, cards);
        List<CardCase> open = new ArrayList<CardCase>();
        List<Case> calls = new ArrayList<Case>();
        for (final CardSubject subject : subjects) {
            final double[] toCards = new double[cards.size()];
            for (int c = 0; c < cards.size(); c++) {
                toCards[c] = -2;
                for (float[] vector : pool.anchors.get(cards.get(c)).vectors) {
                    toCards[c] = Math.max(toCards[c], dot(subject.centroid, vector));
                }
            }
            CardCase next = new CardCase(subject.root);
            cases.add(next);
            Comparison earlier = verdicts.get(Comparison.key(Comparison.Kind.KARTE, subject.aText, list.text));
            if (earlier != null && (earlier.getBId().isEmpty() || cardIds.contains(earlier.getBId()))) {
                next.comparison = earlier;
                used.add(earlier);
                continue;
            }
            open.add(next);
            calls.add(new Case() {
                @Override
                public Comparison ask() {
                    return askCard(subject.aId, subject.aText, cardIds, list, toCards);
                }

                @Override
                public Comparison notAsked(String why) {
                    return new Comparison(Comparison.Kind.KARTE, subject.aId, subject.aText, "", list.text, true,
                            highest(toCards), Comparison.Status.FEHLER, null, "", why, null, chat.modelName(),
                            clock.millis(), 0L);
                }
            });
        }
        List<Comparison> results = run(calls, recorder);
        for (int i = 0; i < open.size(); i++) {
            open.get(i).comparison = results.get(i);
            used.add(results.get(i));
            asked.add(results.get(i));
        }
        for (CardCase next : cases) {
            Comparison verdict = next.comparison;
            if (!verdict.isOk() || verdict.getRelation() == Relation.VERSCHIEDEN) {
                continue;
            }
            int card = groups + cards.get(cardIds.indexOf(verdict.getBId()));
            int below = unions.height(next.root);
            if (verdict.getRelation() == Relation.GLEICH && unions.depth(card) + below <= options.getDepth()) {
                unions.parent[next.root] = unions.find(card);
            } else if (verdict.getRelation() == Relation.A_TEIL_VON_B
                    && unions.depth(card) + 1 + below <= options.getDepth()) {
                unions.parentOf.put(next.root, card);
            } else {
                log("topic merge: " + verdict.getAId() + " stays where it is: under " + verdict.getBId()
                        + " its family would reach deeper than " + options.getDepth() + " levels");
            }
        }
        return cases;
    }

    // ----------------------------------------------------------------------------------------- the parent step

    /** A parent topic the parent step formed, or a head it made the parent of other heads. */
    private static final class Parent {
        /** The node of a new parent topic; -1 when a head became the parent. */
        final int node;
        /** The head that became the parent; -1 for a new parent topic. */
        final int head;
        final String name;
        final String description;
        /** The heads that became its parts. */
        final List<Integer> parts;
        final Comparison comparison;
        /** A failed card case of a new parent topic holds its family back. */
        boolean held;

        Parent(int node, int head, String name, String description, List<Integer> parts, Comparison comparison) {
            this.node = node;
            this.head = head;
            this.name = name;
            this.description = description;
            this.parts = parts;
            this.comparison = comparison;
        }
    }

    /** Heads put to the parent step together, in the order of their first proposal, as the model reads them. */
    private static final class ParentCase {
        final List<Integer> heads;
        final double similarity;
        final List<String> labels = new ArrayList<String>();
        /** The wordings of each head's proposals, to know a parent named like one of them. */
        final List<Set<String>> keys = new ArrayList<Set<String>>();
        final List<String> headIds = new ArrayList<String>();
        String aText = "";
        Comparison comparison;

        ParentCase(List<Integer> heads, double similarity) {
            this.heads = heads;
            this.similarity = similarity;
        }
    }

    /** A head that can take a place under a parent: no known topic, under no topic, not held back. */
    private static boolean free(int root, Unions unions, Set<Integer> heldRoots) {
        return unions.anchorOf(root) < 0 && !unions.parentOf.containsKey(root) && !heldRoots.contains(root);
    }

    /**
     * The parent step: the heads that stand apart after the merge and the card step (no known topic, under no topic,
     * not held back, room for one level above them) are grouped by their vectors at
     * {@link Options#getParentSimilarity()} (average linkage; a group larger than {@value #PARENT_MAX} is split at a
     * higher similarity), and each group of at least {@value #PARENT_MIN} goes to the model once: it may name a
     * common parent topic with a description, or one of the heads as the parent, and the heads that fall wholly
     * under it, at least {@value #PARENT_MIN}; "keins" is the answer in doubt. The heads it names become subtopics
     * of the parent; the heads a parent left out are asked again as a group of their own, up to
     * {@value #PARENT_ROUNDS} rounds. A new parent topic then goes through the card step like a head. A failed case
     * holds its heads back, so the next run asks again; a verdict on the same list of heads is reused. The siblings
     * the model rightly calls different get the parent no proposal named (the architect's fourth run of 22.11.: 29
     * proposals of one subject in 22 topics at the top).
     */
    private List<Parent> parentStep(Pool pool, Unions unions, Set<Integer> heldRoots, Map<Integer, String> heldReason,
                                    Map<String, Comparison> verdicts, List<Comparison> used, List<Comparison> asked,
                                    Recorder recorder) {
        int groups = pool.groups.size();
        final Map<Integer, List<Integer>> itemsOf = itemsOfRoots(pool, unions);
        List<Integer> heads = new ArrayList<Integer>();
        for (int root : itemsOf.keySet()) {
            if (free(root, unions, heldRoots) && unions.height(root) < options.getDepth()) {
                heads.add(root);
            }
        }
        Collections.sort(heads, (x, y) -> Integer.compare(itemsOf.get(x).get(0), itemsOf.get(y).get(0)));
        final Set<String> taken = new HashSet<String>();
        for (Node item : pool.items) {
            taken.add(item.key);
        }
        for (Node anchor : pool.anchors) {
            taken.add(anchor.key);
        }
        taken.remove("");
        List<Parent> parents = new ArrayList<Parent>();
        List<ParentCase> open = parentCases(pool, itemsOf, heads);
        for (int round = 1; round <= PARENT_ROUNDS && !open.isEmpty(); round++) {
            List<ParentCase> toAsk = new ArrayList<ParentCase>();
            List<Case> calls = new ArrayList<Case>();
            for (final ParentCase next : open) {
                describe(next, pool, itemsOf);
                Comparison earlier = verdicts.get(Comparison.key(Comparison.Kind.OBERTHEMA, next.aText,
                        Comparison.PARENT_MARK));
                if (earlier != null) {
                    next.comparison = earlier;
                    used.add(earlier);
                    continue;
                }
                toAsk.add(next);
                calls.add(new Case() {
                    @Override
                    public Comparison ask() {
                        return askParent(next, taken);
                    }

                    @Override
                    public Comparison notAsked(String why) {
                        return new Comparison(Comparison.Kind.OBERTHEMA, next.headIds.get(0), next.aText, "",
                                Comparison.PARENT_MARK, false, next.similarity, Comparison.Status.FEHLER, null, "",
                                why, null, chat.modelName(), clock.millis(), 0L);
                    }
                });
            }
            List<Comparison> results = run(calls, recorder);
            for (int i = 0; i < toAsk.size(); i++) {
                toAsk.get(i).comparison = results.get(i);
                used.add(results.get(i));
                asked.add(results.get(i));
            }
            List<ParentCase> again = new ArrayList<ParentCase>();
            for (ParentCase next : open) {
                Comparison verdict = next.comparison;
                if (!verdict.isOk()) {
                    for (int head : next.heads) {
                        for (int root : familyOf(head, groups, unions)) {
                            if (heldRoots.add(root)) {
                                heldReason.put(root, "parent step failed: " + verdict.getProblem());
                            }
                        }
                    }
                    continue;
                }
                if (verdict.getRelation() != Relation.A_TEIL_VON_B) {
                    continue;
                }
                List<String> answers = verdict.getAnswers();
                ParentAnswer answer = parseParent(answers.isEmpty() ? "" : answers.get(answers.size() - 1),
                        next.labels, next.keys, taken);
                if (!answer.formed()) {
                    continue;
                }
                int head = answer.head.isEmpty() ? -1 : next.heads.get(next.labels.indexOf(answer.head));
                List<Integer> parts = new ArrayList<Integer>();
                for (String label : answer.parts) {
                    int root = next.heads.get(next.labels.indexOf(label));
                    if (root != head && free(root, unions, heldRoots)
                            && 1 + unions.height(root) <= options.getDepth()) {
                        parts.add(root);
                    }
                }
                if (parts.size() < PARENT_MIN || (head >= 0 && !free(head, unions, heldRoots))) {
                    log("topic merge: no parent over " + next.headIds.get(0) + ": " + parts.size() + " heads could "
                            + "take their place under it");
                    continue;
                }
                Parent parent;
                if (head >= 0) {
                    for (int part : parts) {
                        unions.parentOf.put(part, head);
                    }
                    Node center = pool.items.get(central(itemsOf.get(head), pool.items));
                    parent = new Parent(-1, head, center.name, center.description, parts, verdict);
                } else {
                    int node = unions.addNode();
                    for (int part : parts) {
                        unions.parentOf.put(part, node);
                    }
                    parent = new Parent(node, -1, answer.name, answer.description, parts, verdict);
                    taken.add(wording(answer.name));
                }
                parents.add(parent);
                List<Integer> rest = new ArrayList<Integer>();
                for (int other : next.heads) {
                    if (other != head && !parts.contains(other) && free(other, unions, heldRoots)) {
                        rest.add(other);
                    }
                }
                if (rest.size() >= PARENT_MIN) {
                    again.add(new ParentCase(rest, next.similarity));
                }
            }
            open = again;
        }
        // A new parent topic goes through the card step like a head; a failed card case holds its family back.
        List<CardSubject> subjects = new ArrayList<CardSubject>();
        Map<Integer, Parent> parentOfNode = new HashMap<Integer, Parent>();
        for (Parent parent : parents) {
            if (parent.node < 0) {
                continue;
            }
            List<float[]> vectors = new ArrayList<float[]>();
            for (int part : parent.parts) {
                vectors.add(centroidOf(pool, itemsOf.get(part)));
            }
            subjects.add(new CardSubject(parent.node, PARENT_ID_PREFIX + pool.items.get(itemsOf.get(parent.parts
                    .get(0)).get(0)).id, parentText(parent, pool, itemsOf), unit(mean(vectors))));
            parentOfNode.put(parent.node, parent);
        }
        if (options.isCardStep() && !subjects.isEmpty()) {
            for (CardCase card : cardStep(pool, unions, subjects, verdicts, used, asked, recorder)) {
                if (card.comparison.isOk()) {
                    continue;
                }
                parentOfNode.get(card.root).held = true;
                for (int root : familyOf(card.root, groups, unions)) {
                    if (heldRoots.add(root)) {
                        heldReason.put(root, "card step failed: " + card.comparison.getProblem());
                    }
                }
            }
        }
        return parents;
    }

    /** The groups of heads for the parent step, each at least {@value #PARENT_MIN} and at most {@value #PARENT_MAX}. */
    private List<ParentCase> parentCases(Pool pool, final Map<Integer, List<Integer>> itemsOf, List<Integer> heads) {
        List<ParentCase> cases = new ArrayList<ParentCase>();
        if (heads.size() < PARENT_MIN) {
            return cases;
        }
        List<Node> standIns = new ArrayList<Node>();
        List<Integer> all = new ArrayList<Integer>();
        for (int i = 0; i < heads.size(); i++) {
            Node standIn = Node.item(Integer.toString(heads.get(i)), "", "", "");
            standIn.vectors.add(centroidOf(pool, itemsOf.get(heads.get(i))));
            standIns.add(standIn);
            all.add(i);
        }
        split(standIns, heads, all, options.getParentSimilarity(), cases);
        for (ParentCase next : cases) {
            Collections.sort(next.heads, (x, y) -> Integer.compare(itemsOf.get(x).get(0), itemsOf.get(y).get(0)));
        }
        Collections.sort(cases, (x, y) -> Integer.compare(itemsOf.get(x.heads.get(0)).get(0),
                itemsOf.get(y.heads.get(0)).get(0)));
        return cases;
    }

    /**
     * The groups among some heads at or above the similarity; a group larger than {@value #PARENT_MAX} is split at a
     * similarity {@value #PARENT_RAISE} higher, below the upper end of the band; what is still too large there keeps
     * the heads nearest its mean.
     */
    private void split(List<Node> standIns, List<Integer> heads, List<Integer> among, double similarity,
                       List<ParentCase> cases) {
        List<Node> part = new ArrayList<Node>();
        for (int i : among) {
            part.add(standIns.get(i));
        }
        for (List<Integer> cluster : clusters(part, similarity)) {
            List<Integer> indexes = new ArrayList<Integer>();
            for (int at : cluster) {
                indexes.add(among.get(at));
            }
            if (indexes.size() < PARENT_MIN) {
                continue;
            }
            double higher = similarity + PARENT_RAISE;
            if (indexes.size() > PARENT_MAX && higher < options.getSame() - 1e-9) {
                split(standIns, heads, indexes, higher, cases);
                continue;
            }
            if (indexes.size() > PARENT_MAX) {
                List<float[]> vectors = new ArrayList<float[]>();
                for (int i : indexes) {
                    vectors.add(standIns.get(i).vectors.get(0));
                }
                final float[] centroid = unit(mean(vectors));
                Collections.sort(indexes, (x, y) -> Double.compare(dot(centroid, standIns.get(y).vectors.get(0)),
                        dot(centroid, standIns.get(x).vectors.get(0))));
                log("topic merge: " + (indexes.size() - PARENT_MAX) + " heads left out of a parent case of "
                        + indexes.size() + " at " + String.format(Locale.ROOT, "%.2f", similarity));
                indexes = new ArrayList<Integer>(indexes.subList(0, PARENT_MAX));
            }
            List<Integer> group = new ArrayList<Integer>();
            for (int i : indexes) {
                group.add(heads.get(i));
            }
            cases.add(new ParentCase(group, similarity));
        }
    }

    /** The labels, wordings, ids and the list of a case as the model reads it: one line per head. */
    private void describe(ParentCase next, Pool pool, Map<Integer, List<Integer>> itemsOf) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < next.heads.size(); i++) {
            List<Integer> items = itemsOf.get(next.heads.get(i));
            String label = "T" + (i + 1);
            Set<String> keys = new HashSet<String>();
            for (int item : items) {
                keys.add(pool.items.get(item).key);
            }
            next.labels.add(label);
            next.keys.add(keys);
            next.headIds.add(pool.items.get(items.get(0)).id);
            Node center = pool.items.get(central(items, pool.items));
            String line = center.name + (center.description.isEmpty() ? "" : " (" + center.description + ")");
            if (line.length() > TOPIC_LINE_CHARS) {
                line = line.substring(0, TOPIC_LINE_CHARS).trim() + " …";
            }
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(label).append(": ").append(line);
        }
        next.aText = text.toString();
    }

    /** A new parent topic as the card step reads it: its name, what it is about and some of its parts. */
    private String parentText(Parent parent, Pool pool, Map<Integer, List<Integer>> itemsOf) {
        List<String> names = new ArrayList<String>();
        for (int part : parent.parts) {
            if (names.size() < PARTS_SHOWN) {
                names.add(pool.items.get(central(itemsOf.get(part), pool.items)).name);
            }
        }
        StringBuilder text = new StringBuilder(parent.name);
        if (!parent.description.isEmpty()) {
            text.append('\n').append(german ? "Worum es geht: " : "What it is about: ").append(parent.description);
        }
        text.append('\n').append(german ? "Teilthemen: " : "Parts: ").append(String.join("; ", names))
                .append(parent.parts.size() > names.size() ? "; …" : "");
        return text.toString();
    }

    /** One case of the parent step: at most {@link #ATTEMPTS} calls. */
    Comparison askParent(ParentCase next, Set<String> taken) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? PARENT_SYSTEM_DE : PARENT_SYSTEM_EN));
        messages.add(ChatMessage.user(parentPrompt(mission, next.aText, german)));
        String schema = parentSchema(next.labels);
        List<String> answers = new ArrayList<String>();
        String problem = "";
        long start = clock.millis();
        String aId = next.headIds.get(0);
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, PARENT_ANSWER_TOKENS,
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
            ParentAnswer answer = parseParent(text, next.labels, next.keys, taken);
            if (answer.problem.isEmpty()) {
                String bId = answer.head.isEmpty() ? "" : next.headIds.get(next.labels.indexOf(answer.head));
                return new Comparison(Comparison.Kind.OBERTHEMA, aId, next.aText, bId, Comparison.PARENT_MARK, false,
                        next.similarity, Comparison.Status.OK, answer.formed() ? Relation.A_TEIL_VON_B
                        : Relation.VERSCHIEDEN, answer.reason, "", answers, chat.modelName(), clock.millis(),
                        clock.millis() - start, versionOf(Comparison.Kind.OBERTHEMA), answer.note);
            }
            problem = "invalid answer: " + answer.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + answer.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("parent step failed for " + aId + ": " + problem);
        return new Comparison(Comparison.Kind.OBERTHEMA, aId, next.aText, "", Comparison.PARENT_MARK, false,
                next.similarity, Comparison.Status.FEHLER, null, "", problem, answers, chat.modelName(),
                clock.millis(), clock.millis() - start);
    }

    /** The roots of a family: its head and every group root whose chain of parents reaches the head. */
    private static Set<Integer> familyOf(int head, int groups, Unions unions) {
        Set<Integer> family = new LinkedHashSet<Integer>();
        for (int g = 0; g < groups; g++) {
            int root = unions.find(g);
            Set<Integer> seen = new HashSet<Integer>();
            Integer at = root;
            while (at != null && seen.add(at)) {
                if (at == head) {
                    family.add(root);
                    break;
                }
                Integer up = unions.parentOf.get(at);
                at = up == null ? null : unions.find(up);
            }
        }
        return family;
    }

    private static double highest(double[] values) {
        double best = -2;
        for (double value : values) {
            best = Math.max(best, value);
        }
        return best;
    }

    /** One topic against the cards: at most {@link #ATTEMPTS} calls. */
    Comparison askCard(String aId, String aText, List<String> cardIds, CardList list, double[] toCards) {
        String cardsText = list.text;
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? CARD_SYSTEM_DE : CARD_SYSTEM_EN));
        messages.add(ChatMessage.user(cardPrompt(mission, aText, cardsText, german)));
        String schema = cardSchema(cardIds, list.labels);
        List<String> answers = new ArrayList<String>();
        String problem = "";
        long start = clock.millis();
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
            Verdict verdict = parseCard(text, cardIds, list.labelsOfCard);
            if (verdict.problem.isEmpty()) {
                double similarity = verdict.card.isEmpty() ? highest(toCards) : toCards[cardIds.indexOf(verdict.card)];
                return new Comparison(Comparison.Kind.KARTE, aId, aText, verdict.card, cardsText, true, similarity,
                        Comparison.Status.OK, verdict.relation, verdict.reason, "", answers, chat.modelName(),
                        clock.millis(), clock.millis() - start, versionOf(Comparison.Kind.KARTE), verdict.note);
            }
            problem = "invalid answer: " + verdict.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + verdict.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("card step failed for " + aId + ": " + problem);
        return new Comparison(Comparison.Kind.KARTE, aId, aText, "", cardsText, true, highest(toCards),
                Comparison.Status.FEHLER, null, "", problem, answers, chat.modelName(), clock.millis(),
                clock.millis() - start);
    }

    /** One case: at most {@link #ATTEMPTS} calls. */
    Comparison ask(Comparison.Kind kind, String context, String[] request, boolean bKnown, double similarity) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SYSTEM_DE : SYSTEM_EN));
        messages.add(ChatMessage.user(kind == Comparison.Kind.THEMA
                ? topicPrompt(mission, request[1], request[3], bKnown, german)
                : questionPrompt(context, request[1], request[3], german)));
        boolean topics = kind == Comparison.Kind.THEMA;
        List<String> answers = new ArrayList<String>();
        String problem = "";
        long start = clock.millis();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            MainModelChatResult result;
            try {
                result = chat.completeJson(new ArrayList<ChatMessage>(messages), TEMPERATURE, ANSWER_TOKENS,
                        schema(topics));
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
            Verdict verdict = parse(text, topics);
            if (verdict.problem.isEmpty()) {
                return new Comparison(kind, request[0], request[1], request[2], request[3], bKnown, similarity,
                        Comparison.Status.OK, verdict.relation, verdict.reason, "", answers, chat.modelName(),
                        clock.millis(), clock.millis() - start);
            }
            problem = "invalid answer: " + verdict.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + verdict.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("topic merge failed for " + request[0] + " / " + request[2] + ": " + problem);
        return new Comparison(kind, request[0], request[1], request[2], request[3], bKnown, similarity,
                Comparison.Status.FEHLER, null, "", problem, answers, chat.modelName(), clock.millis(),
                clock.millis() - start);
    }

    /**
     * The OK verdict per pair of texts under the prompt this build uses for its kind ({@link #versionOf}), the latest
     * when a pair was judged twice.
     */
    static Map<String, Comparison> reusable(List<Comparison> earlier) {
        Map<String, Comparison> verdicts = new HashMap<String, Comparison>();
        if (earlier != null) {
            for (Comparison comparison : earlier) {
                if (comparison.isOk() && comparison.getVersion() == versionOf(comparison.getKind())) {
                    verdicts.put(comparison.key(), comparison);
                }
            }
        }
        return verdicts;
    }

    // ------------------------------------------------------------------------------------------- the prompt

    static final String SYSTEM_DE = "Du ordnest die Themen und Fragen eines Sachbuchs. Du vergleichst jeweils zwei "
            + "und sagst, wie sie zusammenhängen. Antworte nur mit JSON.";
    static final String SYSTEM_EN = "You order the topics and questions of a non-fiction book. You compare two at a "
            + "time and say how they relate. Answer only with JSON.";
    static final String CARD_SYSTEM_DE = "Du ordnest die Themen eines Sachbuchs den Karten seiner Mindmap zu. "
            + "Antworte nur mit JSON.";
    static final String CARD_SYSTEM_EN = "You assign the topics of a non-fiction book to the cards of its mind map. "
            + "Answer only with JSON.";
    /** The word of the card step for a topic that belongs to no card. */
    static final String NO_CARD = "keine";
    static final String PARENT_SYSTEM_DE = "Du ordnest die Themen eines Sachbuchs. Du prüfst, ob mehrere Themen Teile "
            + "eines gemeinsamen Oberthemas sind. Antworte nur mit JSON.";
    static final String PARENT_SYSTEM_EN = "You order the topics of a non-fiction book. You check whether several "
            + "topics are parts of a common parent topic. Answer only with JSON.";
    /** The word of the parent step for no parent. */
    static final String NO_PARENT = "keins";

    /** A group as the model reads it: its central member, then up to three other names of the group. */
    private String shown(Pool pool, int group) {
        return shownMembers(pool, pool.groups.get(group));
    }

    /** Members of one or more groups as the model reads them: the central member and up to three other names. */
    private String shownMembers(Pool pool, List<Integer> members) {
        Node center = pool.items.get(central(members, pool.items));
        if (pool.kind == Comparison.Kind.FRAGE) {
            return center.name;
        }
        List<String> others = new ArrayList<String>();
        Set<String> keys = new HashSet<String>();
        keys.add(center.key);
        for (int i : members) {
            Node node = pool.items.get(i);
            if (others.size() < OTHER_NAMES && keys.add(node.key)) {
                others.add(node.name);
            }
        }
        return label(center.name, center.description, others);
    }

    /** A known topic with its description and up to three names of topics merged into it; a known question. */
    private String shownAnchor(Pool pool, Node anchor) {
        if (pool.kind == Comparison.Kind.FRAGE) {
            return anchor.name;
        }
        List<String> others = new ArrayList<String>();
        Set<String> keys = new HashSet<String>();
        keys.add(anchor.key);
        for (String name : anchor.otherNames) {
            if (others.size() < OTHER_NAMES && keys.add(wording(name))) {
                others.add(name);
            }
        }
        return label(anchor.name, anchor.description, others);
    }

    private String label(String name, String description, List<String> others) {
        StringBuilder text = new StringBuilder(name);
        if (!description.isEmpty()) {
            text.append('\n').append(german ? "Worum es geht: " : "What it is about: ").append(description);
        }
        if (!others.isEmpty()) {
            text.append('\n').append(german ? "Auch so genannt: " : "Also called: ").append(String.join("; ", others));
        }
        return text.toString();
    }

    static String topicPrompt(String mission, String a, String b, boolean bKnown, boolean german) {
        String task = mission.length() <= MISSION_CHARS ? mission : mission.substring(0, MISSION_CHARS).trim() + " …";
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Thema A:\n" + a + "\n\n"
                    + "Thema B" + (bKnown ? ", ein bekanntes Thema des Buchs" : "") + ":\n" + b + "\n\n"
                    + "Wie hängen A und B zusammen? Wähle eine Beziehung:\n"
                    + "- \"gleich\": A und B meinen denselben Gegenstand. Ein Abschnitt über A deckt B ab und "
                    + "umgekehrt, auch wenn die Namen verschieden sind.\n"
                    + "- \"a_teil_von_b\": A ist ein Teilaspekt von B: B ist der Oberbegriff, und ein Abschnitt "
                    + "über B müsste A behandeln.\n"
                    + "- \"b_teil_von_a\": B ist ein Teilaspekt von A: A ist der Oberbegriff, und ein Abschnitt "
                    + "über A müsste B behandeln.\n"
                    + "- \"verschieden\": A und B meinen verschiedene Gegenstände, auch wenn sie verwandt sind, im "
                    + "selben Umfeld liegen oder dieselben Wörter benutzen.\n"
                    + "Ein gemeinsames Umfeld (etwa beide Recht, beide Technik, beide Arbeit) macht ein Thema noch "
                    + "nicht zum Teil des anderen. Im Zweifel \"verschieden\". Ob die Themen ins Buch gehören, "
                    + "spielt hier keine Rolle.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz, zuerst) und \"beziehung\" "
                    + "(\"gleich\", \"a_teil_von_b\", \"b_teil_von_a\" oder \"verschieden\").";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "Topic A:\n" + a + "\n\n"
                + "Topic B" + (bKnown ? ", a known topic of the book" : "") + ":\n" + b + "\n\n"
                + "How do A and B relate? Choose one relation:\n"
                + "- \"gleich\": A and B mean the same subject. A section on A covers B and the other way round, even "
                + "if the names differ.\n"
                + "- \"a_teil_von_b\": A is a part of B: B is the wider term, and a section on B would have to "
                + "treat A.\n"
                + "- \"b_teil_von_a\": B is a part of A: A is the wider term, and a section on A would have to "
                + "treat B.\n"
                + "- \"verschieden\": A and B mean different subjects, even if they are related, lie in the same "
                + "field or use the same words.\n"
                + "A shared field (both law, both technology, both work) does not make one topic a part of the "
                + "other. When in doubt, \"verschieden\". Whether the topics belong in the book does not matter "
                + "here.\n\n"
                + "Answer only with JSON with the fields \"grund\" (one sentence, first) and \"beziehung\" "
                + "(\"gleich\", \"a_teil_von_b\", \"b_teil_von_a\" or \"verschieden\").";
    }

    static String questionPrompt(String topic, String a, String b, boolean german) {
        if (german) {
            return "Thema: " + (topic.isEmpty() ? "(ohne Namen)" : topic) + "\n\n"
                    + "Frage A: " + a + "\n"
                    + "Frage B: " + b + "\n\n"
                    + "Fragen A und B dasselbe?\n"
                    + "- \"gleich\": Jede Antwort auf A beantwortet auch B vollständig und umgekehrt, auch wenn die "
                    + "Wörter verschieden sind.\n"
                    + "- \"verschieden\": Die Fragen zielen auf Verschiedenes, oder eine ist enger oder weiter als die "
                    + "andere.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz, zuerst) und \"beziehung\" "
                    + "(\"gleich\" oder \"verschieden\").";
        }
        return "Topic: " + (topic.isEmpty() ? "(no name)" : topic) + "\n\n"
                + "Question A: " + a + "\n"
                + "Question B: " + b + "\n\n"
                + "Do A and B ask the same?\n"
                + "- \"gleich\": Every answer to A fully answers B too and the other way round, even if the words "
                + "differ.\n"
                + "- \"verschieden\": The questions aim at different things, or one is narrower or wider than the "
                + "other.\n\n"
                + "Answer only with JSON with the fields \"grund\" (one sentence, first) and \"beziehung\" "
                + "(\"gleich\" or \"verschieden\").";
    }

    /** The cards as the card step lists them, and the labels of each card's questions. */
    static final class CardList {
        final String text;
        /** Every label of the list, in order. */
        final List<String> labels;
        /** The labels of each card's questions, by card id. */
        final Map<String, List<String>> labelsOfCard;

        CardList(String text, List<String> labels, Map<String, List<String>> labelsOfCard) {
            this.text = text;
            this.labels = labels;
            this.labelsOfCard = labelsOfCard;
        }
    }

    /**
     * The cards as the card step lists them: a line with id and name, the description in brackets, then one line per
     * question of the card (what the card foresees), up to {@link #CARD_QUESTIONS} of them, each with a label that is
     * unique in the list ({@code F1}, {@code F2}, ...; {@code Q1}, ... in English) for the answer to name.
     */
    private CardList cardList(List<Node> anchors, List<Integer> cards) {
        StringBuilder text = new StringBuilder();
        List<String> labels = new ArrayList<String>();
        Map<String, List<String>> labelsOfCard = new LinkedHashMap<String, List<String>>();
        for (int a : cards) {
            Node card = anchors.get(a);
            if (text.length() > 0) {
                text.append('\n');
            }
            text.append(card.id).append(": ").append(card.name);
            if (!card.description.isEmpty()) {
                text.append(" (").append(card.description).append(')');
            }
            List<String> own = new ArrayList<String>();
            for (String question : shownQuestions(card)) {
                String label = (german ? "F" : "Q") + (labels.size() + 1);
                labels.add(label);
                own.add(label);
                text.append("\n  ").append(label).append(": ").append(question);
            }
            labelsOfCard.put(card.id, own);
        }
        return new CardList(text.toString(), labels, labelsOfCard);
    }

    /** The questions of a card as the card step shows them: up to {@link #CARD_QUESTIONS}, each cut. */
    private static List<String> shownQuestions(Node card) {
        List<String> questions = new ArrayList<String>();
        for (String question : card.questions) {
            String q = clean(question);
            if (!q.isEmpty() && questions.size() < CARD_QUESTIONS) {
                questions.add(q.length() <= QUESTION_CHARS ? q : q.substring(0, QUESTION_CHARS).trim() + " …");
            }
        }
        return questions;
    }

    static String cardPrompt(String mission, String topic, String cards, boolean german) {
        String task = mission.length() <= MISSION_CHARS ? mission : mission.substring(0, MISSION_CHARS).trim() + " …";
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Karten der Mindmap (sie geben die Richtung des Buchs vor, nicht seinen ganzen Inhalt), jede mit "
                    + "ihren Fragen:\n"
                    + cards + "\n\n"
                    + "Thema:\n" + topic + "\n\n"
                    + "Sieht eine der Karten das Thema schon vor? Wähle eine Zuordnung:\n"
                    + "- \"gleich\": Das Thema ist eine der Karten selbst, nur anders benannt. Ein Abschnitt über das "
                    + "Thema deckt die ganze Karte ab.\n"
                    + "- \"teil_von\": Das Thema beantwortet oder vertieft eine bestimmte Frage einer Karte.\n"
                    + "- \"keine\": Das Thema bedient keine Frage einer Karte, gehört nur zum selben Sachgebiet wie "
                    + "eine Karte, berührt eine Karte nur oder verdient einen eigenen Platz im Buch. Das ist erlaubt: "
                    + "Ein Buch darf Themen enthalten, die keine Karte vorsieht.\n"
                    + "Bei \"gleich\" und \"teil_von\" nennst du die Frage der gewählten Karte, die das Thema "
                    + "beantwortet oder vertieft. Dasselbe Sachgebiet reicht nicht: Findest du keine solche Frage, ist "
                    + "die Antwort \"keine\".\n"
                    + "Im Zweifel \"keine\". Ob das Thema ins Buch gehört, entscheidet ein anderer Schritt; hier geht "
                    + "es nur darum, ob eine Karte es schon vorsieht.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz), \"zuordnung\" (\"gleich\", "
                    + "\"teil_von\" oder \"keine\"), \"karte\" (die Kennung der Karte vor dem Doppelpunkt, bei "
                    + "\"keine\" das Wort keine) und \"frage\" (die Kennung der Frage dieser Karte vor dem "
                    + "Doppelpunkt, bei \"keine\" das Wort keine).";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "Cards of the mind map (they give the book its direction, not all of its content), each with its "
                + "questions:\n"
                + cards + "\n\n"
                + "Topic:\n" + topic + "\n\n"
                + "Does one of the cards already foresee the topic? Choose one assignment:\n"
                + "- \"gleich\": The topic is one of the cards itself, only named differently. A section on the topic "
                + "covers the whole card.\n"
                + "- \"teil_von\": The topic answers or deepens a particular question of a card.\n"
                + "- \"keine\": The topic serves no question of a card, only shares a card's field, only touches a "
                + "card or deserves a place of its own in the book. That is allowed: a book may hold topics no card "
                + "foresees.\n"
                + "For \"gleich\" and \"teil_von\" you name the question of the chosen card the topic answers or "
                + "deepens. The same field is not enough: if you find no such question, the answer is \"keine\".\n"
                + "When in doubt, \"keine\". Whether the topic belongs in the book is decided by another step; this is "
                + "only about whether a card already foresees it.\n\n"
                + "Answer only with JSON with the fields \"grund\" (one sentence), \"zuordnung\" (\"gleich\", "
                + "\"teil_von\" or \"keine\"), \"karte\" (the card's id before the colon, for \"keine\" the word "
                + "keine) and \"frage\" (the id of the card's question before the colon, for \"keine\" the word "
                + "keine).";
    }

    /** {@code grund} first, then {@code beziehung} from the four relations (topics) or two (questions). */
    static String schema(boolean topics) {
        JsonArray words = new JsonArray();
        for (Relation relation : Relation.values()) {
            if (topics || relation == Relation.GLEICH || relation == Relation.VERSCHIEDEN) {
                words.add(relation.word());
            }
        }
        JsonObject relation = new JsonObject();
        relation.addProperty("type", "string");
        relation.add("enum", words);
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        JsonObject properties = new JsonObject();
        properties.add("grund", reason);
        properties.add("beziehung", relation);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("beziehung");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /**
     * {@code grund} first, then {@code zuordnung} (gleich, teil_von, keine), {@code karte} (a card id or keine) and
     * {@code frage} (a label of a card's question or keine).
     */
    static String cardSchema(List<String> cardIds, List<String> questionLabels) {
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        JsonArray words = new JsonArray();
        words.add("gleich");
        words.add("teil_von");
        words.add(NO_CARD);
        JsonObject assignment = new JsonObject();
        assignment.addProperty("type", "string");
        assignment.add("enum", words);
        JsonArray ids = new JsonArray();
        for (String id : cardIds) {
            ids.add(id);
        }
        ids.add(NO_CARD);
        JsonObject card = new JsonObject();
        card.addProperty("type", "string");
        card.add("enum", ids);
        JsonArray labels = new JsonArray();
        for (String label : questionLabels) {
            labels.add(label);
        }
        labels.add(NO_CARD);
        JsonObject question = new JsonObject();
        question.addProperty("type", "string");
        question.add("enum", labels);
        JsonObject properties = new JsonObject();
        properties.add("grund", reason);
        properties.add("zuordnung", assignment);
        properties.add("karte", card);
        properties.add("frage", question);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("zuordnung");
        required.add("karte");
        required.add("frage");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /** A verdict as read from an answer, or why the answer holds none. */
    static final class Verdict {
        Relation relation;
        String reason = "";
        String problem = "";
        /** The card of a card-step verdict, {@code ""} for none. */
        String card = "";
        /** The label of the card's question a card-step verdict names, {@code ""} for none. */
        String question = "";
        /** Where the code changed or read the answer, {@code ""} for nowhere. */
        String note = "";
    }

    /** The answer as a JSON object, or {@code null} with the problem set. */
    private static JsonObject object(String answer, Verdict verdict) {
        try {
            JsonElement element = JsonParser.parseString(answer == null ? "" : answer.trim());
            if (!element.isJsonObject()) {
                verdict.problem = "the answer is no JSON object";
                return null;
            }
            return element.getAsJsonObject();
        } catch (RuntimeException notJson) {
            verdict.problem = "the answer is no JSON";
            return null;
        }
    }

    private static String reasonOf(JsonObject json) {
        JsonElement reason = json.get("grund");
        String text = reason != null && reason.isJsonPrimitive()
                ? reason.getAsString().replaceAll("\\s+", " ").trim() : "";
        return text.length() <= REASON_CHARS ? text : text.substring(0, REASON_CHARS).trim() + " …";
    }

    /**
     * gleich, teil_von (part of the card) or keine, and for the first two a card of the list and the label of one of
     * that card's questions. A card named without a question of that card counts as keine, with a note.
     */
    static Verdict parseCard(String answer, List<String> cardIds, Map<String, List<String>> labelsOfCard) {
        Verdict verdict = new Verdict();
        JsonObject json = object(answer, verdict);
        if (json == null) {
            return verdict;
        }
        JsonElement word = json.get("zuordnung");
        String assignment = word != null && word.isJsonPrimitive() ? word.getAsString().trim().toLowerCase(Locale.ROOT)
                : "";
        if ("gleich".equals(assignment)) {
            verdict.relation = Relation.GLEICH;
        } else if ("teil_von".equals(assignment)) {
            verdict.relation = Relation.A_TEIL_VON_B;
        } else if (NO_CARD.equals(assignment)) {
            verdict.relation = Relation.VERSCHIEDEN;
        } else {
            verdict.problem = "\"zuordnung\" must be gleich, teil_von or keine";
            return verdict;
        }
        if (verdict.relation != Relation.VERSCHIEDEN) {
            JsonElement card = json.get("karte");
            String id = card != null && card.isJsonPrimitive() ? card.getAsString().trim() : "";
            if (!cardIds.contains(id)) {
                verdict.relation = null;
                verdict.problem = "\"karte\" must name one of the cards (" + String.join(", ", cardIds) + ")";
                return verdict;
            }
            JsonElement asked = json.get("frage");
            String label = asked != null && asked.isJsonPrimitive()
                    ? asked.getAsString().trim().split("[:\\s]", 2)[0].toUpperCase(Locale.ROOT) : "";
            label = NO_CARD.equalsIgnoreCase(label) ? "" : label;
            List<String> own = labelsOfCard == null ? null : labelsOfCard.get(id);
            if (own == null || !own.contains(label)) {
                verdict.note = assignment + " " + id + " without a question of that card (frage: "
                        + (label.isEmpty() ? "none" : label) + "): counts as keine";
                verdict.relation = Relation.VERSCHIEDEN;
            } else {
                verdict.card = id;
                verdict.question = label;
            }
        }
        verdict.reason = reasonOf(json);
        return verdict;
    }

    static String parentPrompt(String mission, String topics, boolean german) {
        String task = mission.length() <= MISSION_CHARS ? mission : mission.substring(0, MISSION_CHARS).trim() + " …";
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Themen aus der Recherche (jedes steht bisher für sich, unter keinem anderen Thema):\n"
                    + topics + "\n\n"
                    + "Gehören mindestens drei dieser Themen zu einem gemeinsamen Oberthema, das im Buch einen eigenen "
                    + "Abschnitt tragen könnte?\n"
                    + "- Wenn ja: Gib dem Oberthema einen kurzen Namen und sage in einem Satz, worum es geht. "
                    + "Nenne die Themen, die ganz darunter fallen; Themen, die es nur berühren, lässt du weg.\n"
                    + "- Ist eines der Themen selbst schon dieses Oberthema, nennst du seine Kennung als Oberthema und "
                    + "führst es nicht unter den Themen auf.\n"
                    + "- Wenn nein, oder wenn das gemeinsame Thema nur das Sachgebiet des ganzen Buchs oder ein sehr "
                    + "breites Feld wäre: Antworte \"keins\".\n"
                    + "Im Zweifel \"keins\". Ob ein Thema ins Buch gehört, entscheidet ein anderer Schritt; hier geht "
                    + "es nur darum, welche Themen zusammengehören.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz), \"oberthema\" (der Name des "
                    + "Oberthemas, die Kennung eines der Themen oder keins), \"beschreibung\" (ein Satz, bei keins "
                    + "leer) und \"themen\" (die Kennungen der Themen, die darunter fallen, bei keins eine leere "
                    + "Liste).";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "Topics from the research (each stands on its own so far, under no other topic):\n"
                + topics + "\n\n"
                + "Do at least three of these topics belong to a common parent topic that could carry a section of its "
                + "own in the book?\n"
                + "- If so: give the parent topic a short name and say in one sentence what it is about. Name the "
                + "topics that fall wholly under it; leave out topics that only touch it.\n"
                + "- If one of the topics already is this parent topic, name its id as the parent topic and do not "
                + "list it among the topics.\n"
                + "- If not, or if the common topic would only be the field of the whole book or a very broad field: "
                + "answer \"keins\".\n"
                + "When in doubt, \"keins\". Whether a topic belongs in the book is decided by another step; this is "
                + "only about which topics belong together.\n\n"
                + "Answer only with JSON with the fields \"grund\" (one sentence), \"oberthema\" (the name of the "
                + "parent topic, the id of one of the topics or keins), \"beschreibung\" (one sentence, empty for "
                + "keins) and \"themen\" (the ids of the topics that fall under it, an empty list for keins).";
    }

    /**
     * {@code grund} first, then {@code oberthema} (a name, a label of the list or keins), {@code beschreibung} and
     * {@code themen} (labels of the list).
     */
    static String parentSchema(List<String> labels) {
        JsonObject text = new JsonObject();
        text.addProperty("type", "string");
        JsonArray ids = new JsonArray();
        for (String label : labels) {
            ids.add(label);
        }
        JsonObject label = new JsonObject();
        label.addProperty("type", "string");
        label.add("enum", ids);
        JsonObject parts = new JsonObject();
        parts.addProperty("type", "array");
        parts.add("items", label);
        JsonObject properties = new JsonObject();
        properties.add("grund", text);
        properties.add("oberthema", text.deepCopy());
        properties.add("beschreibung", text.deepCopy());
        properties.add("themen", parts);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add("oberthema");
        required.add("beschreibung");
        required.add("themen");
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /** A parent answer as read: the parent (a head of the list or a new name) with its parts, none, or the problem. */
    static final class ParentAnswer {
        /** The label of the head that is the parent; {@code ""} for a new parent topic and for none. */
        String head = "";
        /** The name of a new parent topic. */
        String name = "";
        String description = "";
        /** The labels of the parts; empty for none. */
        final List<String> parts = new ArrayList<String>();
        String reason = "";
        String note = "";
        String problem = "";

        /** A parent was named over at least {@value TopicMerger#PARENT_MIN} heads. */
        boolean formed() {
            return problem.isEmpty() && !parts.isEmpty();
        }
    }

    /**
     * keins, or a parent with at least {@value #PARENT_MIN} parts of the list: one of the heads (named by its label, or
     * by the wording of one of its proposals) or a new topic with a name and a description. A new parent named like
     * another topic of the run or a known topic, or named over fewer heads, counts as keins, with a note; labels that
     * are not in the list are left out.
     *
     * @param keys  per label, the wordings of the head's proposals
     * @param taken the wordings of every proposal of the run and every known topic
     */
    static ParentAnswer parseParent(String answer, List<String> labels, List<Set<String>> keys, Set<String> taken) {
        ParentAnswer parent = new ParentAnswer();
        Verdict verdict = new Verdict();
        JsonObject json = object(answer, verdict);
        if (json == null) {
            parent.problem = verdict.problem;
            return parent;
        }
        JsonElement named = json.get("oberthema");
        JsonElement listed = json.get("themen");
        if (named == null || !named.isJsonPrimitive()) {
            parent.problem = "\"oberthema\" must be a name, the id of one of the topics or keins";
            return parent;
        }
        if (listed == null || !listed.isJsonArray()) {
            parent.problem = "\"themen\" must be a list of the ids of the topics";
            return parent;
        }
        parent.reason = reasonOf(json);
        String name = named.getAsString().replaceAll("\\s+", " ").trim();
        String word = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty() || NO_PARENT.equals(word) || NO_CARD.equals(word) || "kein".equals(word)
                || "none".equals(word)) {
            return parent;
        }
        String head = "";
        String asLabel = name.split("[:\\s]", 2)[0].toUpperCase(Locale.ROOT);
        String note = "";
        if (labels.contains(asLabel)) {
            head = asLabel;
        } else {
            String key = wording(name);
            for (int i = 0; i < labels.size() && head.isEmpty(); i++) {
                if (keys.get(i).contains(key)) {
                    head = labels.get(i);
                    note = "named like " + head + ", so " + head + " is the parent; ";
                }
            }
            if (head.isEmpty() && taken.contains(key)) {
                parent.note = "\u201e" + name + "\u201c is named like another topic: counts as keins";
                return parent;
            }
        }
        Set<String> parts = new LinkedHashSet<String>();
        List<String> unknown = new ArrayList<String>();
        for (JsonElement element : listed.getAsJsonArray()) {
            String label = element.isJsonPrimitive()
                    ? element.getAsString().trim().split("[:\\s]", 2)[0].toUpperCase(Locale.ROOT) : "";
            if (labels.contains(label)) {
                if (!label.equals(head)) {
                    parts.add(label);
                }
            } else {
                unknown.add(label.isEmpty() ? "?" : label);
            }
        }
        String left = unknown.isEmpty() ? "" : "; not in the list: " + String.join(", ", unknown);
        if (parts.size() < PARENT_MIN) {
            parent.note = note + "a parent over " + parts.size() + " of the topics (it needs " + PARENT_MIN
                    + "): counts as keins" + left;
            return parent;
        }
        parent.head = head;
        if (head.isEmpty()) {
            parent.name = name.length() <= PARENT_NAME_CHARS ? name : name.substring(0, PARENT_NAME_CHARS).trim();
            JsonElement about = json.get("beschreibung");
            String description = about != null && about.isJsonPrimitive()
                    ? about.getAsString().replaceAll("\\s+", " ").trim() : "";
            parent.description = description.length() <= PARENT_DESCRIPTION_CHARS ? description
                    : description.substring(0, PARENT_DESCRIPTION_CHARS).trim() + " …";
        }
        parent.parts.addAll(parts);
        parent.note = note + (head.isEmpty() ? "new parent \u201e" + parent.name + "\u201c" : head + " is the parent")
                + " of " + String.join(", ", parts) + left;
        return parent;
    }

    static Verdict parse(String answer, boolean topics) {
        Verdict verdict = new Verdict();
        JsonObject json = object(answer, verdict);
        if (json == null) {
            return verdict;
        }
        JsonElement word = json.get("beziehung");
        Relation relation = word != null && word.isJsonPrimitive() ? Relation.of(word.getAsString()) : null;
        if (relation == null || (!topics && relation != Relation.GLEICH && relation != Relation.VERSCHIEDEN)) {
            verdict.problem = "\"beziehung\" must be " + (topics ? "gleich, a_teil_von_b, b_teil_von_a or verschieden"
                    : "gleich or verschieden");
            return verdict;
        }
        verdict.relation = relation;
        verdict.reason = reasonOf(json);
        return verdict;
    }

    // ----------------------------------------------------------------------------------------------- vectors

    private String embeddingText(String name, String description) {
        String n = clean(name);
        String d = clean(description);
        return options.isNamesOnly() || d.isEmpty() ? n : n + ": " + d;
    }

    /**
     * Every text without a vector yet, in batches. All vectors must come from one model: when the model changed since
     * the cache was filled, the cache is dropped and the texts are embedded again; vectors of two models are never
     * compared.
     */
    private void embedAll(List<String> texts) {
        for (int round = 1; round <= 2; round++) {
            List<String> missing = new ArrayList<String>(new LinkedHashSet<String>(texts));
            missing.removeAll(vectorCache.keySet());
            boolean changed = false;
            for (int from = 0; from < missing.size() && !changed; from += options.getEmbedBatch()) {
                List<String> batch = new ArrayList<String>(missing.subList(from,
                        Math.min(missing.size(), from + options.getEmbedBatch())));
                List<EmbeddingPort.EmbeddingVector> vectors = embedTried(batch);
                if (vectors == null || vectors.size() != batch.size()) {
                    throw new IllegalStateException("the embedding model returned " + (vectors == null ? 0
                            : vectors.size()) + " vectors for " + batch.size() + " texts");
                }
                String fingerprint = vectors.get(0).getModelFingerprint() + "/" + vectors.get(0).getDimension();
                for (EmbeddingPort.EmbeddingVector vector : vectors) {
                    if (!fingerprint.equals(vector.getModelFingerprint() + "/" + vector.getDimension())) {
                        throw new IllegalStateException("the embedding model returned vectors of two models");
                    }
                }
                if (!fingerprint.equals(cachedFingerprint) && !vectorCache.isEmpty()) {
                    vectorCache.clear();
                    changed = true;
                }
                cachedFingerprint = fingerprint;
                for (int i = 0; i < batch.size(); i++) {
                    vectorCache.put(batch.get(i), unit(vectors.get(i).getValues()));
                }
            }
            if (!changed) {
                return;
            }
        }
        throw new IllegalStateException("the embedding model changed while the texts were embedded");
    }

    /** One embedding request, tried {@value #EMBED_ATTEMPTS} times when it fails (a timeout, a refused connection). */
    private List<EmbeddingPort.EmbeddingVector> embedTried(List<String> batch) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= EMBED_ATTEMPTS; attempt++) {
            try {
                return embeddings.embed(EmbeddingPort.Role.DOCUMENT, batch);
            } catch (RuntimeException failed) {
                last = failed;
                if (attempt < EMBED_ATTEMPTS) {
                    log("embedding request failed (try " + attempt + " of " + EMBED_ATTEMPTS + "): "
                            + failed.getMessage() + "; trying again in " + embedPauseMillis / 1000 + " s");
                    try {
                        Thread.sleep(embedPauseMillis);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("topic merge interrupted", interrupted);
                    }
                }
            }
        }
        throw new IllegalStateException("the embedding model failed " + EMBED_ATTEMPTS + " times: "
                + last.getMessage(), last);
    }

    private static float[] mean(List<float[]> vectors) {
        float[] sum = new float[vectors.get(0).length];
        for (float[] vector : vectors) {
            for (int k = 0; k < sum.length; k++) {
                sum[k] += vector[k];
            }
        }
        return sum;
    }

    private static float[] unit(float[] values) {
        double norm = 0;
        for (float v : values) {
            norm += v * (double) v;
        }
        float[] out = new float[values.length];
        if (norm == 0) {
            return out;
        }
        double scale = 1.0 / Math.sqrt(norm);
        for (int k = 0; k < values.length; k++) {
            out[k] = (float) (values[k] * scale);
        }
        return out;
    }

    private static double dot(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("vectors of different dimensions: " + a.length + " vs " + b.length);
        }
        double sum = 0;
        for (int k = 0; k < a.length; k++) {
            sum += a[k] * (double) b[k];
        }
        return sum;
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private static int root(int[] parent, int node) {
        int at = node;
        while (parent[at] != at) {
            parent[at] = parent[parent[at]];
            at = parent[at];
        }
        return at;
    }

    private static void join(int[] parent, int a, int b) {
        int ra = root(parent, a);
        int rb = root(parent, b);
        if (ra != rb) {
            parent[Math.max(ra, rb)] = Math.min(ra, rb);
        }
    }

    static String wording(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
