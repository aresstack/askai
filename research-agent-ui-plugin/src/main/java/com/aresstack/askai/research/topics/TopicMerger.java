package com.aresstack.askai.research.topics;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.TopicClusterer;
import com.aresstack.askai.research.knowledge.TopicDendrogram;
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
import java.util.Iterator;
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
        private final boolean consolidation;
        private final boolean acrossPlaces;
        private final int fullParts;
        private final double rareShare;
        private final boolean fullLoose;
        private final double concentration;
        private final int concentrationSources;
        private final int concentrationMin;
        private final boolean termPairs;
        private final boolean subjects;
        private final double subjectSimilarity;

        private Options(double same, double doubt, double questionSame, double questionDoubt, int parallel,
                        boolean namesOnly, int embedBatch, int candidates, boolean cardStep, int parentSources,
                        int depth, boolean parentStep, double parentSimilarity, boolean consolidation,
                        boolean acrossPlaces, int fullParts, double rareShare, boolean fullLoose,
                        double concentration, int concentrationSources, int concentrationMin, boolean termPairs,
                        boolean subjects, double subjectSimilarity) {
            if (!(doubt > 0 && doubt <= same && same <= 1) || !(questionDoubt > 0 && questionDoubt <= questionSame
                    && questionSame <= 1)) {
                throw new IllegalArgumentException("a band needs 0 < doubt <= same <= 1");
            }
            if (!(parentSimilarity > 0 && parentSimilarity <= 1)) {
                throw new IllegalArgumentException("the parent step needs 0 < similarity <= 1");
            }
            if (fullParts < 0) {
                throw new IllegalArgumentException("a full parent needs 0 (no limit) or more parts");
            }
            if (!(rareShare >= 0 && rareShare < 1)) {
                throw new IllegalArgumentException("a rare term needs a share 0 <= share < 1 (0: off)");
            }
            if (!(concentration >= 0 && concentration <= 1) || concentrationSources < 1 || concentrationMin < 1) {
                throw new IllegalArgumentException("a concentrated term needs a share 0 <= share <= 1 (0: off), one "
                        + "source or more and one proposal or more");
            }
            if (!(subjectSimilarity > 0 && subjectSimilarity <= 1)) {
                throw new IllegalArgumentException("the subject step needs 0 < similarity <= 1");
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
            this.consolidation = consolidation;
            this.acrossPlaces = acrossPlaces;
            this.fullParts = fullParts;
            this.rareShare = rareShare;
            this.fullLoose = fullLoose;
            this.concentration = concentration;
            this.concentrationSources = concentrationSources;
            this.concentrationMin = concentrationMin;
            this.termPairs = termPairs;
            this.subjects = subjects;
            this.subjectSimilarity = subjectSimilarity;
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
         * for a common parent among heads at 0.60, below the band: the architect's fourth run of 22.11. left 29
         * proposals of one subject in 22 topics at the top, siblings the model rightly called different, and in his
         * run c15e no head of a scattered subject reached the model at 0.65; by the same measurement pairs with the
         * same silver label reach 0.65 in 21 % of cases and 0.60 in 44 %, pairs with different labels in 0.5 % and
         * 2.3 %.
         */
        public static Options defaults() {
            return new Options(0.85, 0.65, 0.92, 0.85, 4, false, 32, 3, true, 2, 2, true, 0.60, true, true, 30,
                    0.0, true, 0.6, 2, 4, true, false,
                    0.85);
        }

        public Options withTopicBand(double newSame, double newDoubt) {
            return new Options(newSame, newDoubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch,
                    candidates, cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation,
                    acrossPlaces, fullParts, rareShare, fullLoose, concentration, concentrationSources,
                    concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        public Options withQuestionBand(double newSame, double newDoubt) {
            return new Options(same, doubt, newSame, newDoubt, parallel, namesOnly, embedBatch, candidates, cardStep,
                    parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        public Options withParallel(int calls) {
            return new Options(same, doubt, questionSame, questionDoubt, calls, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /** Compare proposals by their names alone, without the description. */
        public Options withNamesOnly(boolean names) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, names, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * How many candidates a group is compared with: up to this many known topics and as many earlier groups, the
         * most similar ones at or above the lower end of the band.
         */
        public Options withCandidates(int perKind) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, perKind,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * With the card step (the default) the cards of the mind map get no doubtful pairwise case: every head of a
         * family is put to the model once with all cards. Without it, cards are compared like any known topic.
         */
        public Options withCardStep(boolean on) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    on, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * Where a "part of" verdict may hang a topic: under a known topic, or under a new topic with proposals of at
         * least {@code sources} sources (an established topic, not a single mention); and at most {@code levels}
         * levels below the top of its family (a card, a known topic or a new topic under none). A verdict that breaks
         * either rule leaves the topic where it is.
         */
        public Options withSubtopics(int sources, int levels) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, sources, levels, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * With the parent step (the default) heads of families that stand apart but lie at or above {@code similarity}
         * of each other (average linkage over their vectors) are put to the model in groups: it may name a common
         * parent topic, and the heads that fall under it become its subtopics. Without it, no topic is formed that no
         * proposal named.
         */
        public Options withParentStep(boolean on, double similarity) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, on, similarity, consolidation, acrossPlaces, fullParts,
                    rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * With the consolidation (the default) the parent step ends with two follow-ups at each place: the new parents
         * are compared in pairs, and a parent that is the same as another or a part of it gives its parts to the other;
         * then each head no case took in is compared with the most similar parent of its place and joins it when it is
         * a part of it (the architect's run c15f of 22.11.2026: near-identical parents side by side under one card, and
         * the heads of their subject left loose beside them).
         */
        public Options withConsolidation(boolean on) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, on, acrossPlaces, fullParts,
                    rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * Across places (the default; only with the consolidation): the free parents at the top go to the card step
         * again when the consolidation changed their text, and to the most similar parents of the other places in
         * pairs; then each head that stands alone (no subtopic, at the top or right under a card) is compared with the
         * most similar parent of any place (the architect's levers (a) and (b) after his run c15g of 22.11.2026: a
         * subject spread over the top and a card, and many single heads). Across places two parents merge only as the
         * same subject (his lever (3) after c15h: "part of" across places hung subjects of their own under general
         * parents of method or law).
         */
        public Options withAcrossPlaces(boolean on) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, on, fullParts,
                    rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * A parent with at least {@code parts} parts is full: the heads that stand alone ({@link #withAcrossPlaces})
         * join it only as the same subject ("gleich"), not as a part of it; 0 sets no limit. The default is 30 (the
         * architect's lever (2) after his run c15h of 22.11.2026: a collecting parent grew past 100 parts from the
         * steps across places and was one of the mixed families). Across places a free parent merges with the parent
         * of another place only as the same subject anyway, so the limit changes nothing there.
         */
        public Options withFullParts(int parts) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces, parts,
                    rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * Whether a full parent ({@link #withFullParts}) takes a loose head of its own place (the second step of the
         * consolidation) only as the same subject too, as it does a head that stands alone; on by default (the
         * architect's proposal after his run c15i of 22.11.2026: the largest parent grew from 102 to 110 direct parts
         * through the loose heads of its own place, which the limit did not cover).
         */
        public Options withFullLoose(boolean on) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, on, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * A term of the proposals' names that fewer than {@code share} of the distinct names hold is rare
         * ({@link RareTerms}); 0 switches the lever off, and off is the default since the architect's run c15j (the
         * terms under 2 % were mostly general words; {@link #withConcentration} took its place). With it a head or a
         * parent that shares a rare term with a parent becomes one more case of the consolidation even below
         * {@link #getParentSimilarity()}: at most one per loose head and per head that stands alone, after its case by
         * similarity did not join it, at most one per free parent across places, where only the same subject merges,
         * and with {@link #withTermPairs} one per parent among the parents of its place. The verdict stays the model's
         * under the same prompt (the architect's lever after his run c15h of 22.11.2026: of 383 proposals of the 17
         * expected topics 79 stood alone and 151 in other families than the largest, subjects whose wording kept
         * their vectors apart).
         */
        public Options withRareTerms(double share) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, share, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * A term of the proposals' names counts when at least {@code min} proposals hold it and at least
         * {@code share} of them come from at most {@code sources} sources ({@link RareTerms}); a share of 0 switches
         * the lever off. It brings about the same extra cases as a rare term ({@link #withRareTerms}); with both
         * levers on a term counts when it is rare or concentrated. Default 0.6, 2 sources, 4 proposals (the
         * architect's lever after his run c15j of 22.11.2026: the terms under 2 % of the names were mostly general
         * words, "analy", "standard", "management", and the terms of a subject lay near 2 % themselves; a subject's
         * terms hang on few sources, a general word spreads over many). Below 4 proposals every term would pass: 3
         * proposals from 3 sources already give 67 % to the largest two.
         */
        public Options withConcentration(double share, int sources, int min) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, share, sources, min, termPairs,
                    subjects, subjectSimilarity);
        }

        /**
         * Whether the pairs of parents of one place (the first step of the consolidation) get the extra case of a
         * shared term too: each parent meets the most similar parent of its place it shares a counting term with
         * ({@link #withRareTerms}, {@link #withConcentration}), at any similarity, once a round. On by default (the
         * architect's analysis of c15j: within one card a subject fell into several families, PNA under the
         * technology card into three).
         */
        public Options withTermPairs(boolean on) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, on,
                    subjects, subjectSimilarity);
        }

        /**
         * The subject step (the builder's lever after the architect's run c15k of 22.11.2026, agreed by him the same
         * evening): after the pairs of parents and the loose heads of the consolidation, the model names the subject
         * of each family at the top and under each card, from its name and some of its parts, without the aspect; two
         * families of one place whose subjects are worded alike or whose subject vectors reach {@code similarity} go
         * to the model as a pair ("the same subject, also from different angles?"), and the families it joins go
         * under a new parent named by the subject. Off by default; the architect's measurement decides. The
         * architect's run c15k left one subject spread over sibling parents formed by aspect (definition,
         * infrastructure, strategy, law), at the top and under a card, which the pairs of parents rightly call
         * different.
         */
        public Options withSubjects(boolean on, double similarity) {
            return new Options(same, doubt, questionSame, questionDoubt, parallel, namesOnly, embedBatch, candidates,
                    cardStep, parentSources, depth, parentStep, parentSimilarity, consolidation, acrossPlaces,
                    fullParts, rareShare, fullLoose, concentration, concentrationSources, concentrationMin, termPairs,
                    on, similarity);
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

        /** Whether the parent step compares its parents in pairs and joins loose heads to them. */
        public boolean isConsolidation() {
            return consolidation;
        }

        /** Whether the consolidation also compares across places ({@link #withAcrossPlaces}). */
        public boolean isAcrossPlaces() {
            return acrossPlaces;
        }

        /** The parts from which a parent takes a head that stands alone only as the same subject; 0: no limit. */
        public int getFullParts() {
            return fullParts;
        }

        /** The share of the distinct proposal names under which a term is rare; 0: the lever is off. */
        public double getRareTermShare() {
            return rareShare;
        }

        /** Whether a full parent takes a loose head of its own place only as the same subject too. */
        public boolean isFullLoose() {
            return fullLoose;
        }

        /** The share of a term's proposals its largest sources must hold for it to count; 0: the lever is off. */
        public double getConcentration() {
            return concentration;
        }

        /** How many of a term's largest sources count for {@link #getConcentration()}. */
        public int getConcentrationSources() {
            return concentrationSources;
        }

        /** The proposals a term needs at least to count as concentrated. */
        public int getConcentrationMin() {
            return concentrationMin;
        }

        /** Whether the pairs of parents of one place get the extra case of a shared term. */
        public boolean isTermPairs() {
            return termPairs;
        }

        /** Whether the subject step runs ({@link #withSubjects}). */
        public boolean isSubjects() {
            return subjects;
        }

        /** The similarity of two subjects' vectors from which their families go to the model as a pair. */
        public double getSubjectSimilarity() {
            return subjectSimilarity;
        }

        /** Whether a term can count at all: rarity or concentration is on. */
        boolean countsTerms() {
            return rareShare > 0 || concentration > 0;
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
     * card counts as none, a parent named over fewer than three heads as none. For the subject of a family (kind
     * {@link Kind#GEGENSTAND}) A is the family as the model read it (its name and some of its parts) and B the fixed
     * word {@value #SUBJECT_MARK}; the verdict is {@link Relation#A_TEIL_VON_B} (a subject was named), and the note
     * carries the subject, which is read again from the last raw answer. For two families of one place (kind
     * {@link Kind#GEGENSTANDSPAAR}) A and B are the two families; the verdict is {@link Relation#GLEICH} (the same
     * subject) or {@link Relation#VERSCHIEDEN}.
     */
    public static final class Comparison {
        /**
         * Two topics, two questions of one topic, a topic and the cards of the mind map, heads and a parent, the
         * subject of a family, or two families of one place.
         */
        public enum Kind { THEMA, FRAGE, KARTE, OBERTHEMA, GEGENSTAND, GEGENSTANDSPAAR }

        /** B of a case of the parent step. */
        public static final String PARENT_MARK = "oberthema";

        /** B of a case that names the subject of a family. */
        public static final String SUBJECT_MARK = "gegenstand";

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

    /**
     * One finding of the subject step ({@link Options#withSubjects}); immutable: a new parent formed over families of
     * one place with the same subject ({@link #ROOF}), or a family left where it is because its subject is that of its
     * card ({@link #CARD}).
     */
    public static final class SubjectFinding {
        /** A new parent named by the subject. */
        public static final String ROOF = "DACH";
        /** A family whose subject is that of its card. */
        public static final String CARD = "KARTE";

        private final String kind;
        private final String place;
        private final String name;
        private final List<String> families;
        private final int parts;
        private final int proposals;

        SubjectFinding(String kind, String place, String name, List<String> families, int parts, int proposals) {
            this.kind = kind;
            this.place = clean(place);
            this.name = clean(name);
            this.families = Collections.unmodifiableList(new ArrayList<String>(families));
            this.parts = parts;
            this.proposals = proposals;
        }

        /** {@link #ROOF} or {@link #CARD}. */
        public String getKind() {
            return kind;
        }

        /** The name of the card the families stand under, {@code ""} at the top. */
        public String getPlace() {
            return place;
        }

        /** The subject: the name of the new parent, or the subject the family shares with its card. */
        public String getName() {
            return name;
        }

        /** The names of the families: those under the new parent, or the one family left. */
        public List<String> getFamilies() {
            return families;
        }

        /** The direct parts of the new parent, or of the family left. */
        public int getParts() {
            return parts;
        }

        /** The proposals below the new parent, or in the family left. */
        public int getProposals() {
            return proposals;
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
        private final Map<String, Integer> parentCounts;
        private final List<SubjectFinding> subjects;

        Result(List<TopicProposal> placed, List<MergedTopic> topics, List<MergedQuestion> questions,
               List<Comparison> comparisons, List<Comparison> asked, Map<String, String> heldBack, int groups,
               String model, long timeMillis, Map<String, Integer> parentCounts) {
            this(placed, topics, questions, comparisons, asked, heldBack, groups, model, timeMillis, parentCounts,
                    Collections.<SubjectFinding>emptyList());
        }

        Result(List<TopicProposal> placed, List<MergedTopic> topics, List<MergedQuestion> questions,
               List<Comparison> comparisons, List<Comparison> asked, Map<String, String> heldBack, int groups,
               String model, long timeMillis, Map<String, Integer> parentCounts, List<SubjectFinding> subjects) {
            this.subjects = Collections.unmodifiableList(new ArrayList<SubjectFinding>(subjects));
            this.placed = Collections.unmodifiableList(new ArrayList<TopicProposal>(placed));
            this.topics = Collections.unmodifiableList(new ArrayList<MergedTopic>(topics));
            this.questions = Collections.unmodifiableList(new ArrayList<MergedQuestion>(questions));
            this.comparisons = Collections.unmodifiableList(new ArrayList<Comparison>(comparisons));
            this.asked = Collections.unmodifiableList(new ArrayList<Comparison>(asked));
            this.heldBack = Collections.unmodifiableMap(new LinkedHashMap<String, String>(heldBack));
            this.groups = groups;
            this.model = model;
            this.timeMillis = timeMillis;
            this.parentCounts = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(parentCounts));
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

        /**
         * What the parent step did, for the report: of the heads at the top ({@code freeHeads}) how many came into a
         * case ({@code freeInCases}), stood in no group of three ({@code freeAlone}), joined a piece of their group
         * after a split ({@code freeReattached}) or found no place ({@code freeLost}); the same for the topics with
         * loose subtopics ({@code siblingTopics}) and those subtopics ({@code siblingHeads} and so on); the parents
         * formed at the top ({@code formedTop}) and under a topic ({@code formedUnder}), the heads that became a
         * parent ({@code headParents}) and the heads that joined a parent formed before ({@code joined}). The
         * consolidation: pairs of parents of one place ({@code parentPairs}), parents that gave their parts to another
         * ({@code parentsMerged}), verdicts not applied because a parent changed since it was asked
         * ({@code parentPairsStale}), loose heads put to a parent ({@code looseCases}) and joined
         * ({@code looseJoined}); across places, free parents at the top put to the card step again
         * ({@code cardCasesAgain}) and placed by it ({@code cardPlacedAgain}), pairs with a parent of another place
         * ({@code acrossPairs}), parents that gave their parts to it ({@code acrossMerged}) or had no room there
         * ({@code acrossTooDeep}), verdicts of those pairs not applied because a parent changed since it was asked
         * ({@code acrossPairsStale}), and heads that stand alone put to a parent of any place ({@code singleCases})
         * and joined ({@code singleJoined}). The cases a shared term brought about ({@link RareTerms}): of loose heads
         * ({@code rareLoose}, joined {@code rareLooseJoined}), across places ({@code rareAcross}, merged
         * {@code rareAcrossMerged}), of heads that stand alone ({@code rareSingle}, joined {@code rareSingleJoined})
         * and of the parents of one place ({@code rarePairs}, merged {@code rarePairsMerged}). The subject step
         * ({@link Options#withSubjects}): families named ({@code subjectFamilies}) or failed ({@code subjectFailed}),
         * of them with the subject of their card ({@code subjectCard}), pairs of families asked or reused
         * ({@code subjectPairs}), failed ({@code subjectPairsFailed}) and judged the same subject ({@code subjectYes}),
         * new parents formed ({@code subjectRoofs}) over families ({@code subjectMerged}), parents kept as subfamilies
         * ({@code subjectKept}) or laid flat ({@code subjectFlat}), families left out because the place had no room
         * for a new level ({@code subjectTooDeep}) or because every subject they name is the name of another topic
         * ({@code subjectNameTaken}), groups whose parent is one of their heads named like the subject
         * ({@code subjectHeadRoofs}, counted in {@code subjectRoofs} too), and the largest new family by its proposals
         * ({@code subjectLargest}) and by its direct parts ({@code subjectLargestParts}).
         */
        public Map<String, Integer> getParentCounts() {
            return parentCounts;
        }

        /** What the subject step did: the new parents it formed and the families it left with their card's subject. */
        public List<SubjectFinding> getSubjects() {
            return subjects;
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
     * topics and two questions stay those of version 3. 5 (22.11.): the parent step also over the loose subtopics of
     * a topic, with a prompt of its own, and a parent named like one formed before at the same place takes the heads
     * in; the card prompt stays that of version 4. 6 (22.11.): the subject step ({@link Options#withSubjects}), the
     * subject of a family and the pair of two families, with prompts of their own; the other prompts stay.
     */
    public static final int PROMPT_VERSION = 6;

    /**
     * The version of the prompt of a kind of case: 3 for two topics or two questions, 4 for cards, 5 for parents, 6
     * for the subject step.
     */
    public static int versionOf(Comparison.Kind kind) {
        return kind == Comparison.Kind.GEGENSTAND || kind == Comparison.Kind.GEGENSTANDSPAAR ? 6
                : kind == Comparison.Kind.OBERTHEMA ? 5 : kind == Comparison.Kind.KARTE ? 4 : 3;
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
    /** Answer budget of a parent case: a sentence, a name, a sentence and the labels of the parts. */
    static final int PARENT_ANSWER_TOKENS = 500;
    /** The id a new parent topic is logged under in the card step, before it has its number. */
    static final String PARENT_ID_PREFIX = "oberthema/";
    /** The note of a case of two new parents at one place (consolidation). */
    static final String NOTE_PAIR = "Oberthemen derselben Stelle";
    /** The note of a case of a head no case took in and a new parent of its place (consolidation). */
    static final String NOTE_LOOSE = "loser Kopf zu einem Oberthema";
    /** The note of a case of a free parent at the top and a new parent of another place (across places). */
    static final String NOTE_ACROSS = "freies Oberthema zu einem Oberthema anderer Stelle";
    /** The note of a case of a head that stands alone and a new parent of any place (across places). */
    static final String NOTE_SINGLE = "einzelner Kopf zu einem Oberthema";
    /** Added to the note of a case that a rare term shared by both sides brought about, before the term. */
    static final String NOTE_RARE = "; seltener Begriff: ";
    /** As {@link #NOTE_RARE}, for a term that counts as concentrated over the sources and is not rare. */
    static final String NOTE_CONCENTRATED = "; gehäufter Begriff: ";
    /** The rounds of the pairs of parents: a verdict on a parent that changed in a round is asked again in the next. */
    static final int PAIR_ROUNDS = 3;
    /** The note of the subject of a family, before the subject. */
    static final String NOTE_SUBJECT = "Gegenstand: ";
    /** The note of a pair of families of one place in the subject step, before their two subjects. */
    static final String NOTE_SUBJECT_PAIR = "Familien derselben Stelle; Gegenstände: ";
    /** The parts of a family the subject step shows, by their names. */
    static final int SUBJECT_SAMPLES = 8;
    /** A subject has at most this many words. */
    static final int SUBJECT_WORDS = 4;
    private static final int SUBJECT_CHARS = 60;
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
                    Collections.<String, String>emptyMap(), 0, chat.modelName(), now, parentCounts());
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
        // The parent step over the heads that stand apart, then over the loose subtopics of each topic; a failed case
        // holds its heads back too.
        Map<String, Integer> counts = parentCounts();
        List<SubjectFinding> subjectFindings = new ArrayList<SubjectFinding>();
        List<Parent> parents = options.isParentStep() ? parentStep(topicPool, unions, heldRoots, heldReason, verdicts,
                used, asked, recorder, counts, subjectFindings) : Collections.<Parent>emptyList();

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
                chat.modelName(), now, counts, subjectFindings);
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
        double cut = 1.0 - same + 1e-9;
        for (List<String> group : clusterTree(items, cut).getDendrogram().cut(cut)) {
            List<Integer> indexes = new ArrayList<Integer>();
            for (String id : group) {
                indexes.add(Integer.parseInt(id));
            }
            out.add(indexes);
        }
        return out;
    }

    /** The tree of the topic analysis over stand-ins, by their first vector; the passage ids are the indexes. */
    private static TopicClusterer.Tree clusterTree(List<Node> items, double cut) {
        List<Passage> standIns = new ArrayList<Passage>();
        Map<String, EmbeddingPort.EmbeddingVector> vectors = new HashMap<String, EmbeddingPort.EmbeddingVector>();
        for (int i = 0; i < items.size(); i++) {
            String id = Integer.toString(i);
            standIns.add(new Passage(id, "", null, "", "", "merge"));
            vectors.put(id, new EmbeddingPort.EmbeddingVector("merge", "merge", items.get(i).vectors.get(0)));
        }
        return new TopicClusterer(IdSequence.counting(), cut, 1).clusterTree(standIns, vectors,
                ClusteringMonitor.NONE);
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

        /** A node of a parent topic the parent step formed. */
        boolean isFormedNode(int node) {
            return node >= groups + anchorDepth.length;
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
        /**
         * The heads judged for it already: those of the cases that formed it or gave it parts, and the heads put to it
         * on their own (consolidation, end step), joined or not.
         */
        final Set<Integer> seen = new HashSet<Integer>();
        /** Held back with its family: a failed case, or every part held back. */
        boolean held;
        /** It gave its parts to another parent (consolidation) and is formed no more. */
        boolean merged;
        /** Its text as the card step read it, to tell whether the consolidation changed it since. */
        String carded = "";

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
        /** The topic the heads stand under as loose subtopics; -1 for heads at the top. */
        final int under;
        /** Its name as the prompt shows it; empty for heads at the top. */
        final String underName;
        final List<String> labels = new ArrayList<String>();
        /** The wordings of each head's proposals, to know a parent named like one of them. */
        final List<Set<String>> keys = new ArrayList<Set<String>>();
        final List<String> headIds = new ArrayList<String>();
        /** The heads as the model reads them, one line each. */
        String lines = "";
        /** The case as logged and reused: the topic the heads stand under, if any, then the lines. */
        String aText = "";
        Comparison comparison;

        ParentCase(List<Integer> heads, double similarity, int under, String underName) {
            this.heads = heads;
            this.similarity = similarity;
            this.under = under;
            this.underName = underName;
        }
    }

    /** A head that can take a place under a parent: no known topic, under no topic, not held back. */
    private static boolean free(int root, Unions unions, Set<Integer> heldRoots) {
        return unions.anchorOf(root) < 0 && !unions.parentOf.containsKey(root) && !heldRoots.contains(root);
    }

    /** A head that can take a place in a case: free at the top, or still a loose subtopic of the case's topic. */
    private static boolean stands(int root, ParentCase next, Unions unions, Set<Integer> heldRoots) {
        if (next.under < 0) {
            return free(root, unions, heldRoots);
        }
        Integer up = unions.parentOf.get(root);
        return unions.anchorOf(root) < 0 && up != null && unions.find(up) == unions.find(next.under)
                && !heldRoots.contains(root);
    }

    /** How deep a head reaches below its top as a part of a parent under {@code under} (-1: at the top). */
    private static int reach(int root, int under, Unions unions) {
        return (under < 0 ? 0 : unions.depth(under) + 1) + 1 + unions.height(root);
    }

    /** A word is a term from this many letters on. */
    static final int RARE_WORD = 5;
    /** Words of five letters or more that name no subject, German and English, left out before folding. */
    private static final Set<String> RARE_STOP = new HashSet<String>(Arrays.asList("werden", "wurde", "wurden",
            "worden", "haben", "hatte", "hatten", "sowie", "durch", "unter", "zwischen", "gegen", "nicht", "einer",
            "eines", "einem", "einen", "diese", "dieser", "dieses", "diesem", "diesen", "welche", "welcher", "welches",
            "welchen", "andere", "anderen", "anderer", "anderes", "allem", "allen", "alles", "deren", "dessen",
            "können", "müssen", "sollen", "dürfen", "wollen", "seine", "seinen", "seiner", "ihrer", "ihren", "damit",
            "dabei", "dafür", "davon", "darin", "darauf", "warum", "sowohl", "beide", "beiden", "which", "their",
            "there", "these", "those", "about", "under", "between", "through", "other", "others", "using", "being",
            "where", "while", "within", "without", "should", "could", "would"));

    /** A parent among candidates that shares a rare term, with that term. */
    private static final class RareCandidate {
        final int index;
        final String term;

        RareCandidate(int index, String term) {
            this.index = index;
            this.term = term;
        }
    }

    /**
     * The rare terms of the names of a run's proposals (the architect's lever after his run c15h of 22.11.2026). A
     * term is a word of at least {@value #RARE_WORD} letters, in lower case with a plural or case ending folded
     * ("Exoskeletten" and "Exoskelette" give "exoskelett"), or an acronym of two to five capitals as written ("RFID";
     * "PNAs" gives "PNA"). A word matches a word that is the same or holds it whole, as a compound does
     * ("rückenexoskelett" holds "exoskelett"); an acronym matches only itself in capitals, so the word "Arm" never
     * matches "ARM". A term is rare when fewer than the share of all distinct names hold a match of it; an acronym
     * counts the names that hold it in any case, so an acronym that is also a common word is not rare; a word that no
     * name holds (one that only two descriptions share) is no term. Two sets of terms share a rare term when a term
     * of one matches a term of the other and the shorter of the two is rare.
     * <p>
     * Concentration (the architect's lever after his run c15j of 22.11.2026) counts proposals, not distinct names: a
     * term is concentrated when at least a minimum of proposals hold a match of it and its largest sources hold at
     * least a share of them. With both levers on a term counts when it is rare or concentrated; two sets then share a
     * counting term.
     */
    static final class RareTerms {
        /** The terms of each distinct name. */
        private final List<Set<String>> terms = new ArrayList<Set<String>>();
        /** The tokens of each distinct name in lower case: an acronym counts names that hold it in any case. */
        private final List<Set<String>> tokens = new ArrayList<Set<String>>();
        private final double limit;
        private final Map<String, Integer> holding = new HashMap<String, Integer>();
        /** The terms, tokens and source of each proposal, a name proposed twice counted twice (concentration). */
        private final List<Set<String>> proposalTerms = new ArrayList<Set<String>>();
        private final List<Set<String>> proposalTokens = new ArrayList<Set<String>>();
        private final List<String> proposalSources = new ArrayList<String>();
        private final double concentration;
        private final int concentrationSources;
        private final int concentrationMin;
        private final Map<String, Map<String, Integer>> sources = new HashMap<String, Map<String, Integer>>();

        /** Rarity alone, over the distinct names. */
        RareTerms(List<String> names, double share) {
            this(names, null, share, 0, 1, 1);
        }

        /**
         * Rarity over the distinct names ({@code share}, 0: off) and concentration over the proposals: a term counts
         * when at least {@code min} proposals hold it and its {@code top} largest sources hold at least
         * {@code concentration} of them (0: off). {@code sourceOf} gives each name's source, in the order of
         * {@code names}; null without concentration.
         */
        RareTerms(List<String> names, List<String> sourceOf, double share, double concentration, int top, int min) {
            Set<String> seen = new HashSet<String>();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                String key = name.trim().toLowerCase(Locale.ROOT);
                if (key.isEmpty()) {
                    continue;
                }
                Set<String> nameTerms = termsOf(name);
                Set<String> lower = tokensOf(name);
                if (sourceOf != null) {
                    proposalTerms.add(nameTerms);
                    proposalTokens.add(lower);
                    proposalSources.add(sourceOf.get(i));
                }
                if (seen.add(key)) {
                    terms.add(nameTerms);
                    tokens.add(lower);
                }
            }
            limit = share * terms.size();
            this.concentration = sourceOf == null ? 0 : concentration;
            this.concentrationSources = Math.max(1, top);
            this.concentrationMin = Math.max(1, min);
        }

        private static Set<String> tokensOf(String name) {
            Set<String> lower = new HashSet<String>();
            for (String token : name.split("[^\\p{L}]+")) {
                if (!token.isEmpty()) {
                    lower.add(token.toLowerCase(Locale.ROOT));
                }
            }
            return lower;
        }

        /**
         * How many proposals of each source hold a match of the term, the most first (then by the source); an
         * acronym counts proposals that hold it in any case, as {@link #holding} does.
         */
        Map<String, Integer> sourcesOf(String term) {
            Map<String, Integer> known = sources.get(term);
            if (known != null) {
                return known;
            }
            boolean acronym = term.startsWith("#");
            String lower = acronym ? term.substring(1).toLowerCase(Locale.ROOT) : term;
            final Map<String, Integer> count = new HashMap<String, Integer>();
            for (int i = 0; i < proposalTerms.size(); i++) {
                boolean holds = acronym ? proposalTerms.get(i).contains(term) || proposalTokens.get(i).contains(lower)
                        : holds(proposalTerms.get(i), term);
                if (holds) {
                    Integer before = count.get(proposalSources.get(i));
                    count.put(proposalSources.get(i), before == null ? 1 : before + 1);
                }
            }
            List<String> order = new ArrayList<String>(count.keySet());
            Collections.sort(order, (x, y) -> count.get(y).equals(count.get(x)) ? x.compareTo(y)
                    : count.get(y) - count.get(x));
            Map<String, Integer> out = new LinkedHashMap<String, Integer>();
            for (String source : order) {
                out.put(source, count.get(source));
            }
            sources.put(term, out);
            return out;
        }

        /** The share of the term's proposals that its largest sources hold; 0 when no proposal holds it. */
        double topShare(String term) {
            int all = 0;
            int top = 0;
            int k = 0;
            for (int n : sourcesOf(term).values()) {
                all += n;
                top += k++ < concentrationSources ? n : 0;
            }
            return all == 0 ? 0 : (double) top / all;
        }

        /** Whether the lever is on, at least the minimum of proposals hold the term and its largest sources most. */
        boolean concentrated(String term) {
            if (concentration <= 0) {
                return false;
            }
            int all = 0;
            for (int n : sourcesOf(term).values()) {
                all += n;
            }
            return all >= concentrationMin && topShare(term) >= concentration;
        }

        /** Whether the term counts: rare, or concentrated over the sources. */
        boolean counts(String term) {
            return (limit > 0 && rare(term)) || concentrated(term);
        }

        /** How many distinct names hold a match of the term. */
        int holding(String term) {
            Integer known = holding.get(term);
            if (known != null) {
                return known;
            }
            boolean acronym = term.startsWith("#");
            String lower = acronym ? term.substring(1).toLowerCase(Locale.ROOT) : term;
            int count = 0;
            for (int i = 0; i < terms.size(); i++) {
                boolean holds = acronym ? terms.get(i).contains(term) || tokens.get(i).contains(lower)
                        : holds(terms.get(i), term);
                if (holds) {
                    count++;
                }
            }
            holding.put(term, count);
            return count;
        }

        /** A term of at least one name (a word only in texts beside the names is no term) in fewer than the share. */
        boolean rare(String term) {
            int count = holding(term);
            return count >= 1 && count < limit;
        }

        /** The counting term ({@link #counts}) the two sets share, the fewest names first, then by the term. */
        String shared(Set<String> a, Set<String> b) {
            String best = null;
            int fewest = Integer.MAX_VALUE;
            for (String x : a) {
                for (String y : b) {
                    String term = match(x, y);
                    if (term == null || !counts(term)) {
                        continue;
                    }
                    int count = holding(term);
                    if (count < fewest || (count == fewest && term.compareTo(best) < 0)) {
                        best = term;
                        fewest = count;
                    }
                }
            }
            return best;
        }

        /** The term two terms share: an acronym only itself; of two words the shorter, when the other holds it. */
        static String match(String x, String y) {
            if (x.startsWith("#") || y.startsWith("#")) {
                return x.equals(y) ? x : null;
            }
            if (x.length() <= y.length()) {
                return y.contains(x) ? x : null;
            }
            return x.contains(y) ? y : null;
        }

        private static boolean holds(Set<String> terms, String word) {
            for (String term : terms) {
                if (!term.startsWith("#") && term.contains(word)) {
                    return true;
                }
            }
            return false;
        }

        /** The terms of a text: words split at every character that is no letter; an acronym marked with "#". */
        static Set<String> termsOf(String text) {
            Set<String> out = new LinkedHashSet<String>();
            for (String token : text.split("[^\\p{L}]+")) {
                if (token.isEmpty()) {
                    continue;
                }
                String acronym = acronym(token);
                if (acronym != null) {
                    out.add("#" + acronym);
                    continue;
                }
                String lower = token.toLowerCase(Locale.ROOT);
                if (lower.length() >= RARE_WORD && !RARE_STOP.contains(lower)) {
                    out.add(fold(lower));
                }
            }
            return out;
        }

        /** Two to five capitals, also with a plural "s" ("PNAs"); else null. */
        static String acronym(String token) {
            if (capitals(token)) {
                return token;
            }
            if (token.endsWith("s") && capitals(token.substring(0, token.length() - 1))) {
                return token.substring(0, token.length() - 1);
            }
            return null;
        }

        private static boolean capitals(String token) {
            if (token.length() < 2 || token.length() > 5) {
                return false;
            }
            for (int i = 0; i < token.length(); i++) {
                if (!Character.isUpperCase(token.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * A word in lower case without its plural or case ending: at most twice "en", "e", "n" after "r" or "l"
         * ("Lasern", "Regeln") or "s" (not after "s", "u" or "i": "Prozess", "Status", "Praxis") off a word of six
         * letters or more, as long as {@value #RARE_WORD} letters stay; "Person" and "Station" keep their "n".
         */
        static String fold(String word) {
            String out = word;
            for (int step = 0; step < 2 && out.length() >= 6; step++) {
                String next = out.endsWith("en") ? out.substring(0, out.length() - 2)
                        : out.endsWith("e") || out.endsWith("rn") || out.endsWith("ln")
                        || (out.endsWith("s") && !out.endsWith("ss") && !out.endsWith("us") && !out.endsWith("is"))
                        ? out.substring(0, out.length() - 1) : out;
                if (next.equals(out) || next.length() < RARE_WORD) {
                    break;
                }
                out = next;
            }
            return out;
        }

        /** The term as a note shows it: an acronym without its mark. */
        static String shown(String term) {
            return term.startsWith("#") ? term.substring(1) : term;
        }

        /** The term a note shows ({@link #shown}): a word is in lower case, an acronym in capitals. */
        static String termOfShown(String shown) {
            return capitals(shown) ? "#" + shown : shown;
        }
    }

    /** The counts of the parent step, every one at 0, in the order {@link Result#getParentCounts()} lists them. */
    static Map<String, Integer> parentCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String key : new String[]{"freeHeads", "freeInCases", "freeAlone", "freeReattached", "freeLost",
                "siblingTopics", "siblingHeads", "siblingInCases", "siblingAlone", "siblingReattached", "siblingLost",
                "formedTop", "formedUnder", "headParents", "joined", "parentPairs", "parentsMerged",
                "parentPairsStale", "looseCases", "looseJoined", "cardCasesAgain", "cardPlacedAgain", "acrossPairs",
                "acrossMerged", "acrossTooDeep", "acrossPairsStale", "acrossPartOf", "singleCases", "singleJoined",
                "singleFull", "looseFull", "rareLoose", "rareLooseJoined", "rareAcross", "rareAcrossMerged",
                "rareSingle", "rareSingleJoined", "rarePairs", "rarePairsMerged", "subjectFamilies", "subjectFailed",
                "subjectCard", "subjectPairs", "subjectPairsFailed", "subjectYes", "subjectRoofs", "subjectMerged",
                "subjectKept", "subjectFlat", "subjectTooDeep", "subjectNameTaken", "subjectHeadRoofs",
                "subjectLargest", "subjectLargestParts"}) {
            counts.put(key, 0);
        }
        return counts;
    }

    private static void add(Map<String, Integer> counts, String key, int n) {
        Integer before = counts.get(key);
        counts.put(key, (before == null ? 0 : before) + n);
    }

    /**
     * The parent step, in two passes. First over the heads that stand apart after the merge and the card step (no
     * known topic, under no topic, not held back, room for one level above them); then, under every topic, over its
     * loose subtopics that have room for one level between it and them (the architect's run c15e of 22.11.: of 29
     * proposals of one subject, 23 hung as loose siblings directly under a card, which the first pass never sees).
     * The heads of a pass are grouped by their vectors ({@link #parentCases}), and each case goes to the model once:
     * it may name a common parent topic with a description, or one of the heads as the parent, and the heads that
     * fall wholly under it, at least {@value #PARENT_MIN}; "keins" is the answer in doubt. Under a topic the parent
     * must be narrower than that topic, and it stays under it. The heads it names become subtopics of the parent;
     * the heads a parent left out are asked again as a case of their own, up to {@value #PARENT_ROUNDS} rounds. A new
     * parent named like one this step formed before at the same place takes the heads in, so a subject larger than
     * one case ends in one parent; named like one at another place, or like any other topic, it counts as keins. A
     * new parent topic of the first pass then goes through the card step like a head. A failed case holds its heads
     * back, so the next run asks again; a verdict on the same list of heads is reused. With the consolidation
     * ({@link Options#isConsolidation()}) the new parents of each place are then compared in pairs
     * ({@link ParentPass#pairs()}) and the heads no case took in are put to the parents of their place
     * ({@link ParentPass#loose()}); across places ({@link Options#isAcrossPlaces()}) the free parents at the top go
     * to the cards and the parents of the other places ({@link ParentPass#acrossPlaces()}), and the heads that stand
     * alone to the most similar parent of any place ({@link ParentPass#singles()}). A parent whose parts are all held
     * back is held back too ({@link ParentPass#holdEmptied()}).
     */
    private List<Parent> parentStep(Pool pool, Unions unions, Set<Integer> heldRoots, Map<Integer, String> heldReason,
                                    Map<String, Comparison> verdicts, List<Comparison> used, List<Comparison> asked,
                                    Recorder recorder, Map<String, Integer> counts,
                                    List<SubjectFinding> subjectFindings) {
        final Map<Integer, List<Integer>> itemsOf = itemsOfRoots(pool, unions);
        List<Integer> heads = new ArrayList<Integer>();
        for (int root : itemsOf.keySet()) {
            if (free(root, unions, heldRoots) && unions.height(root) < options.getDepth()) {
                heads.add(root);
            }
        }
        Set<String> taken = new HashSet<String>();
        for (Node item : pool.items) {
            taken.add(item.key);
        }
        for (Node anchor : pool.anchors) {
            taken.add(anchor.key);
        }
        taken.remove("");
        ParentPass pass = new ParentPass(pool, unions, itemsOf, heldRoots, heldReason, verdicts, used, asked, recorder,
                taken, counts);
        add(counts, "freeHeads", heads.size());
        pass.rounds(parentCases(pool, itemsOf, heads, -1, "", counts, "free"));

        // A new parent topic goes through the card step like a head; a failed card case holds its family back.
        List<Parent> formedHere = new ArrayList<Parent>();
        for (Parent parent : pass.parents) {
            if (parent.node >= 0) {
                formedHere.add(parent);
            }
        }
        pass.toCards(formedHere);

        // The loose subtopics under each topic; a head that became a parent in the first pass keeps its parts.
        Set<Integer> headsAsParents = new HashSet<Integer>();
        for (Parent parent : pass.parents) {
            if (parent.head >= 0) {
                headsAsParents.add(unions.find(parent.head));
            }
        }
        Map<Integer, List<Integer>> siblingsOf = new LinkedHashMap<Integer, List<Integer>>();
        for (int root : itemsOf.keySet()) {
            Integer up = unions.parentOf.get(root);
            if (up == null || unions.anchorOf(root) >= 0 || heldRoots.contains(root)) {
                continue;
            }
            int top = unions.find(up);
            if (unions.isFormedNode(top) || headsAsParents.contains(top)
                    || reach(root, top, unions) > options.getDepth()) {
                continue;
            }
            if (!siblingsOf.containsKey(top)) {
                siblingsOf.put(top, new ArrayList<Integer>());
            }
            siblingsOf.get(top).add(root);
        }
        List<ParentCase> siblingCases = new ArrayList<ParentCase>();
        for (Map.Entry<Integer, List<Integer>> siblings : siblingsOf.entrySet()) {
            if (siblings.getValue().size() < PARENT_MIN) {
                continue;
            }
            add(counts, "siblingTopics", 1);
            add(counts, "siblingHeads", siblings.getValue().size());
            siblingCases.addAll(parentCases(pool, itemsOf, siblings.getValue(), siblings.getKey(),
                    nameOf(siblings.getKey(), pool, unions, itemsOf), counts, "sibling"));
        }
        Collections.sort(siblingCases, (x, y) -> Integer.compare(itemsOf.get(x.heads.get(0)).get(0),
                itemsOf.get(y.heads.get(0)).get(0)));
        pass.rounds(siblingCases);

        // The parents of one place among themselves, then the heads no case took in to the parents of their place;
        // across places the free parents at the top to the cards and the parents of other places, and the heads that
        // stand alone to the most similar parent of any place. No parent comes out without a part.
        if (options.isConsolidation()) {
            pass.pairs();
            pass.loose();
        }
        // The subject step: the families of each place with the same subject under a new parent named by it.
        if (options.isSubjects()) {
            List<Parent> topRoofs = pass.subjects();
            // without the step across places a new parent at the top still goes through the card step once
            if (!(options.isConsolidation() && options.isAcrossPlaces())) {
                pass.toCards(topRoofs);
            }
        }
        if (options.isConsolidation() && options.isAcrossPlaces()) {
            pass.acrossPlaces();
            pass.singles();
        }
        pass.holdEmptied();
        subjectFindings.addAll(pass.subjectFindings);
        return pass.parents;
    }

    /** The name of a topic as a case under it shows it: a known topic's own, else that of its central proposal. */
    private static String nameOf(int node, Pool pool, Unions unions, Map<Integer, List<Integer>> itemsOf) {
        int anchor = unions.anchorOf(node);
        if (anchor >= 0) {
            return pool.anchors.get(anchor).name;
        }
        List<Integer> items = itemsOf.get(unions.find(node));
        return items == null ? "" : pool.items.get(central(items, pool.items)).name;
    }

    /** One run of the parent step: what its two passes share, and the parents they form. */
    private final class ParentPass {
        final Pool pool;
        final Unions unions;
        final Map<Integer, List<Integer>> itemsOf;
        final Set<Integer> heldRoots;
        final Map<Integer, String> heldReason;
        final Map<String, Comparison> verdicts;
        final List<Comparison> used;
        final List<Comparison> asked;
        final Recorder recorder;
        /** The wordings of every proposal of the run and every known topic. */
        final Set<String> taken;
        final Map<String, Integer> counts;
        final List<Parent> parents = new ArrayList<Parent>();
        /** The new parent topics of this step by the wording of their name. */
        final Map<String, Parent> formed = new HashMap<String, Parent>();
        /** The two texts of every case of two topics in this run ({@link Comparison#key()}), asked or reused. */
        final Set<String> judged = new HashSet<String>();
        /** What the subject step did. */
        final List<SubjectFinding> subjectFindings = new ArrayList<SubjectFinding>();
        /** The rare terms of the run's proposal names, built on first use; none when the lever is off. */
        private RareTerms rareTerms;
        private final Map<Integer, Set<String>> rootTerms = new HashMap<Integer, Set<String>>();
        private final Map<Parent, Set<String>> parentTerms = new HashMap<Parent, Set<String>>();

        ParentPass(Pool pool, Unions unions, Map<Integer, List<Integer>> itemsOf, Set<Integer> heldRoots,
                   Map<Integer, String> heldReason, Map<String, Comparison> verdicts, List<Comparison> used,
                   List<Comparison> asked, Recorder recorder, Set<String> taken, Map<String, Integer> counts) {
            this.pool = pool;
            this.unions = unions;
            this.itemsOf = itemsOf;
            this.heldRoots = heldRoots;
            this.heldReason = heldReason;
            this.verdicts = verdicts;
            this.used = used;
            this.asked = asked;
            this.recorder = recorder;
            this.taken = taken;
            this.counts = counts;
        }

        /**
         * The counting terms of the run ({@link Options#withRareTerms}, {@link Options#withConcentration}), or null
         * when both levers are off.
         */
        private RareTerms rareTerms() {
            if (rareTerms == null && options.countsTerms()) {
                List<String> names = new ArrayList<String>();
                List<String> sourceOf = new ArrayList<String>();
                for (Node item : pool.items) {
                    names.add(item.name);
                    sourceOf.add(item.source);
                }
                rareTerms = new RareTerms(names, sourceOf, options.getRareTermShare(), options.getConcentration(),
                        options.getConcentrationSources(), options.getConcentrationMin());
            }
            return rareTerms;
        }

        /** The note of a case a shared term brought about: rare or concentrated, then the term. */
        private String termNote(String term) {
            RareTerms terms = rareTerms();
            return (terms != null && terms.limit > 0 && terms.rare(term) ? NOTE_RARE : NOTE_CONCENTRATED)
                    + RareTerms.shown(term);
        }

        /** The terms of a head: those of the names of its proposals. */
        private Set<String> termsOfRoot(int root) {
            Set<String> terms = rootTerms.get(root);
            if (terms == null) {
                terms = new LinkedHashSet<String>();
                for (int item : itemsOf.get(root)) {
                    terms.addAll(RareTerms.termsOf(pool.items.get(item).name));
                }
                rootTerms.put(root, terms);
            }
            return terms;
        }

        /** The terms of a parent: those of its name and of what it is about, not of its parts. */
        private Set<String> termsOfParent(Parent parent) {
            Set<String> terms = parentTerms.get(parent);
            if (terms == null) {
                terms = new LinkedHashSet<String>(RareTerms.termsOf(parent.name));
                terms.addAll(RareTerms.termsOf(parent.description));
                parentTerms.put(parent, terms);
            }
            return terms;
        }

        /**
         * Of the candidates whose score is a similarity (not marked below -1.5) and that are not chosen already, the
         * most similar one that shares a rare term with the given terms; null when there is none or the lever is off.
         */
        private RareCandidate rareAmong(Set<String> terms, List<Parent> candidates, double[] scores,
                                        List<Integer> chosen) {
            RareTerms rare = rareTerms();
            if (rare == null) {
                return null;
            }
            RareCandidate best = null;
            for (int j = 0; j < candidates.size(); j++) {
                if (scores[j] < -1.5 || chosen.contains(j)) {
                    continue;
                }
                String term = rare.shared(terms, termsOfParent(candidates.get(j)));
                if (term != null && (best == null || scores[j] > scores[best.index])) {
                    best = new RareCandidate(j, term);
                }
            }
            return best;
        }

        /** The cases, then the heads each parent left out, up to {@value #PARENT_ROUNDS} rounds. */
        void rounds(List<ParentCase> open) {
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
                                    Comparison.PARENT_MARK, false, next.similarity, Comparison.Status.FEHLER, null,
                                    "", why, null, chat.modelName(), clock.millis(), 0L);
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
                    List<Integer> rest = decide(next);
                    if (rest.size() >= PARENT_MIN) {
                        again.add(new ParentCase(rest, next.similarity, next.under, next.underName));
                    }
                }
                open = again;
            }
        }

        /** What a verdict does to its heads; the heads a parent left out, to ask again (empty when none was named). */
        private List<Integer> decide(ParentCase next) {
            Comparison verdict = next.comparison;
            if (!verdict.isOk()) {
                for (int head : next.heads) {
                    for (int root : familyOf(head, pool.groups.size(), unions)) {
                        if (heldRoots.add(root)) {
                            heldReason.put(root, "parent step failed: " + verdict.getProblem());
                        }
                    }
                }
                return Collections.emptyList();
            }
            if (verdict.getRelation() != Relation.A_TEIL_VON_B) {
                return Collections.emptyList();
            }
            List<String> answers = verdict.getAnswers();
            ParentAnswer answer = parseParent(answers.isEmpty() ? "" : answers.get(answers.size() - 1), next.labels,
                    next.keys, taken);
            if (!answer.formed()) {
                return Collections.emptyList();
            }
            int head = answer.head.isEmpty() ? -1 : next.heads.get(next.labels.indexOf(answer.head));
            List<Integer> parts = new ArrayList<Integer>();
            for (String label : answer.parts) {
                int root = next.heads.get(next.labels.indexOf(label));
                if (root != head && stands(root, next, unions, heldRoots)
                        && reach(root, next.under, unions) <= options.getDepth()) {
                    parts.add(root);
                }
            }
            Parent before = head < 0 ? formed.get(wording(answer.name)) : null;
            if (before != null && (before.held || place(before) != (next.under < 0 ? -1 : unions.find(next.under)))) {
                log("topic merge: no parent over " + next.headIds.get(0) + ": „" + answer.name + "“ was "
                        + "formed at another place");
                return Collections.emptyList();
            }
            if (parts.size() < (before == null ? PARENT_MIN : 1)
                    || (head >= 0 && !stands(head, next, unions, heldRoots))) {
                log("topic merge: no parent over " + next.headIds.get(0) + ": " + parts.size() + " heads could "
                        + "take their place under it");
                return Collections.emptyList();
            }
            if (before != null) {
                for (int part : parts) {
                    unions.parentOf.put(part, before.node);
                }
                before.parts.addAll(parts);
                before.seen.addAll(next.heads);
                add(counts, "joined", parts.size());
            } else if (head >= 0) {
                for (int part : parts) {
                    unions.parentOf.put(part, head);
                }
                Node center = pool.items.get(central(itemsOf.get(head), pool.items));
                parents.add(new Parent(-1, head, center.name, center.description, parts, verdict));
                add(counts, "headParents", 1);
            } else {
                int node = unions.addNode();
                if (next.under >= 0) {
                    unions.parentOf.put(node, next.under);
                }
                for (int part : parts) {
                    unions.parentOf.put(part, node);
                }
                Parent parent = new Parent(node, -1, answer.name, answer.description, parts, verdict);
                parent.seen.addAll(next.heads);
                parents.add(parent);
                formed.put(wording(answer.name), parent);
                add(counts, next.under < 0 ? "formedTop" : "formedUnder", 1);
            }
            List<Integer> rest = new ArrayList<Integer>();
            for (int other : next.heads) {
                if (other != head && !parts.contains(other) && stands(other, next, unions, heldRoots)) {
                    rest.add(other);
                }
            }
            return rest;
        }

        /**
         * New parents through the card step, each on its text as it stands; the text is kept, so the consolidation
         * can tell whether it changed since. A failed case holds the parent and its family back. Returns the cases.
         */
        List<CardCase> toCards(List<Parent> some) {
            List<CardSubject> subjects = new ArrayList<CardSubject>();
            Map<Integer, Parent> parentOfNode = new HashMap<Integer, Parent>();
            for (Parent parent : some) {
                parent.carded = parentText(parent, pool, itemsOf);
                subjects.add(new CardSubject(parent.node, logId(parent), parent.carded, vectorOf(parent)));
                parentOfNode.put(parent.node, parent);
            }
            if (!options.isCardStep() || subjects.isEmpty()) {
                return Collections.emptyList();
            }
            List<CardCase> cases = cardStep(pool, unions, subjects, verdicts, used, asked, recorder);
            for (CardCase card : cases) {
                if (!card.comparison.isOk()) {
                    hold(parentOfNode.get(card.root), "card step failed: " + card.comparison.getProblem());
                }
            }
            return cases;
        }

        /**
         * The new parents that stand at one place, compared in pairs (the architect's run c15f of 22.11.2026: under
         * one card several near-identical parents formed side by side from different cases). Each parent goes with up
         * to {@link Options#getCandidates()} of the most similar other parents of its place, at or above
         * {@link Options#getParentSimilarity()} between their mean vectors, to the model as two topics; the most
         * similar pairs are applied first. "gleich", or a part of the other either way, puts the parts of one under
         * the other: under the wider one, else under the one with more parts (the earlier on a tie). The parts keep
         * their level, so no depth changes. A parent that gave its parts away is formed no more. A verdict on a parent
         * whose text changed since it was asked, because it gave its parts away or took others in earlier in the
         * round, is not applied: "part of" does not pass on to the parent that took the parts (the review of
         * 22.11.2026). The next round, up to {@value #PAIR_ROUNDS}, asks again on the parents as they then stand; two
         * texts judged once in the run are not asked again. A failed case holds back both parents as they stand now,
         * so a parent that gave its parts away holds back the one that took them; the next run asks again. A verdict
         * on the same two texts is reused, in either order.
         */
        void pairs() {
            int round = 1;
            while (pairRound(false) && round < PAIR_ROUNDS) {
                round++;
            }
        }

        /**
         * Lever (a) of the architect after c15g (22.11.2026: a parent on data protection law hung under the card on
         * law while two parents of the same subject stood free at the top; the pairs compare within one place only,
         * so they never met). First each free parent at the top whose text changed in the consolidation goes through
         * the card step again on its new text, so the card's question rule holds for it as for every topic (the
         * subject of a card alone draws nothing in: the architect's fourth run of 22.11.); one whose text did not
         * change was answered on the same text already. Then each free parent at the top goes with up to
         * {@link Options#getCandidates()} of the most similar new parents of the other places, at or above
         * {@link Options#getParentSimilarity()}, to the model as two topics, in rounds as in {@link #pairs()}: "gleich"
         * puts its parts under the other parent when each part's family has room there, and the parent at the top is
         * formed no more; "a_teil_von_b" and "b_teil_von_a" change nothing (the architect's lever (3) after his run
         * c15h of 22.11.2026: of the 8 parents merged across places 5 were subjects of their own put under a general
         * parent of method or law, a "part of" that a shared field was enough for; a free parent has parts of its
         * own, so across places it merges only with the same subject). Beside them it meets the most similar parent of
         * another place it shares a rare term with ({@link Options#withRareTerms}), at any similarity, once a round.
         * Last the pairs of each place once more, for the parents that came to a place. A free parent at the top whose
         * text these pairs changed again goes through the card step again on that text, and so on, up to
         * {@value #PAIR_ROUNDS} times (the review of 22.11.2026: a parent that took another's parts in the last pairs
         * never met the card's question rule on its final text).
         */
        void acrossPlaces() {
            for (int round = 1; round <= PAIR_ROUNDS; round++) {
                List<Parent> changed = changedAtTheTop();
                if (round > 1 && changed.isEmpty()) {
                    return;
                }
                for (CardCase card : toCards(changed)) {
                    add(counts, "cardCasesAgain", 1);
                    if (card.comparison.isOk()
                            && (unions.anchorOf(card.root) >= 0 || unions.parentOf.containsKey(card.root))) {
                        add(counts, "cardPlacedAgain", 1);
                    }
                }
                int pairRounds = 1;
                while (pairRound(true) && pairRounds < PAIR_ROUNDS) {
                    pairRounds++;
                }
                pairs();
            }
        }

        /** The free parents at the top whose text changed since the card step last read it. */
        private List<Parent> changedAtTheTop() {
            List<Parent> changed = new ArrayList<Parent>();
            List<Parent> top = standing().get(-1);
            for (Parent parent : top == null ? Collections.<Parent>emptyList() : top) {
                if (!parentText(parent, pool, itemsOf).equals(parent.carded)) {
                    changed.add(parent);
                }
            }
            return changed;
        }

        /**
         * One round of pairs: at each place, or ({@code across}) from each free parent at the top to the parents of
         * the other places. Beside the most similar ones each parent meets the most similar parent it shares a
         * counting term with, at any similarity ({@link RareTerms}; at a place only with
         * {@link Options#isTermPairs()}). The verdicts are asked on the parents as they stand and applied from the most
         * similar pair down. Returns whether a parent gave its parts away, so that a next round asks again.
         */
        private boolean pairRound(boolean across) {
            List<Parent[]> pairs = new ArrayList<Parent[]>();
            final List<Double> scores = new ArrayList<Double>();
            // the rare term that brought a pair about, null for a pair by similarity
            List<String> rareOf = new ArrayList<String>();
            Map<Integer, List<Parent>> byPlace = standing();
            if (across) {
                List<Parent> top = byPlace.get(-1);
                List<Parent> others = new ArrayList<Parent>();
                for (Map.Entry<Integer, List<Parent>> here : byPlace.entrySet()) {
                    if (here.getKey() >= 0) {
                        others.addAll(here.getValue());
                    }
                }
                if (top == null || others.isEmpty()) {
                    return false;
                }
                List<float[]> vectors = vectorsOf(others);
                for (Parent free : top) {
                    float[] vector = vectorOf(free);
                    double[] to = new double[others.size()];
                    for (int j = 0; j < others.size(); j++) {
                        // a parent inside the free parent's own subtree is no parent of another place (the review of
                        // 22.11.2026: it could take nothing, and a failed case would hold back the whole subtree)
                        to[j] = isBelow(others.get(j).node, free.node) ? -2.0 : dot(vector, vectors.get(j));
                    }
                    List<Integer> near = nearest(to, options.getCandidates(), options.getParentSimilarity());
                    for (int j : near) {
                        pairs.add(new Parent[]{free, others.get(j)});
                        scores.add(to[j]);
                        rareOf.add(null);
                    }
                    RareCandidate extra = rareAmong(termsOfParent(free), others, to, near);
                    if (extra != null) {
                        pairs.add(new Parent[]{free, others.get(extra.index)});
                        scores.add(to[extra.index]);
                        rareOf.add(extra.term);
                    }
                }
            } else {
                Set<String> pairKeys = new HashSet<String>();
                for (List<Parent> here : byPlace.values()) {
                    if (here.size() < 2) {
                        continue;
                    }
                    List<float[]> vectors = vectorsOf(here);
                    for (int i = 0; i < here.size(); i++) {
                        double[] to = new double[here.size()];
                        for (int j = 0; j < here.size(); j++) {
                            to[j] = i == j ? -2.0 : dot(vectors.get(i), vectors.get(j));
                        }
                        List<Integer> near = nearest(to, options.getCandidates(), options.getParentSimilarity());
                        for (int j : near) {
                            int a = Math.min(i, j);
                            int b = Math.max(i, j);
                            if (pairKeys.add(parents.indexOf(here.get(a)) + ":" + parents.indexOf(here.get(b)))) {
                                pairs.add(new Parent[]{here.get(a), here.get(b)});
                                scores.add(to[j]);
                                rareOf.add(null);
                            }
                        }
                        RareCandidate extra = options.isTermPairs()
                                ? rareAmong(termsOfParent(here.get(i)), here, to, near) : null;
                        if (extra != null) {
                            int a = Math.min(i, extra.index);
                            int b = Math.max(i, extra.index);
                            if (pairKeys.add(parents.indexOf(here.get(a)) + ":" + parents.indexOf(here.get(b)))) {
                                pairs.add(new Parent[]{here.get(a), here.get(b)});
                                scores.add(to[extra.index]);
                                rareOf.add(extra.term);
                            }
                        }
                    }
                }
            }
            List<Integer> order = new ArrayList<Integer>();
            for (int k = 0; k < pairs.size(); k++) {
                order.add(k);
            }
            Collections.sort(order, (x, y) -> Double.compare(scores.get(y), scores.get(x)));
            List<Parent[]> toJudge = new ArrayList<Parent[]>();
            List<String[]> requests = new ArrayList<String[]>();
            List<Double> similarities = new ArrayList<Double>();
            List<String> notes = new ArrayList<String>();
            List<Boolean> byRare = new ArrayList<Boolean>();
            String base = across ? NOTE_ACROSS : NOTE_PAIR;
            for (int k : order) {
                Parent a = pairs.get(k)[0];
                Parent b = pairs.get(k)[1];
                String aText = parentText(a, pool, itemsOf);
                String bText = parentText(b, pool, itemsOf);
                if (judgedBefore(aText, bText)) {
                    continue;
                }
                toJudge.add(pairs.get(k));
                requests.add(new String[]{logId(a), aText, logId(b), bText});
                similarities.add(scores.get(k));
                String term = rareOf.get(k);
                notes.add(term == null ? base : base + termNote(term));
                byRare.add(term != null);
                if (term != null) {
                    add(counts, across ? "rareAcross" : "rarePairs", 1);
                }
            }
            if (requests.isEmpty()) {
                return false;
            }
            List<Comparison> said = judge(requests, similarities, notes);
            Map<Parent, Parent> into = new HashMap<Parent, Parent>();
            boolean changed = false;
            for (int n = 0; n < toJudge.size(); n++) {
                Parent first = toJudge.get(n)[0];
                Parent second = toJudge.get(n)[1];
                Comparison verdict = said.get(n);
                add(counts, across ? "acrossPairs" : "parentPairs", 1);
                if (!verdict.isOk()) {
                    String why = (across ? "parent pair across places failed: " : "parent pair failed: ")
                            + verdict.getProblem();
                    hold(top(into, first), why);
                    hold(top(into, second), why);
                    continue;
                }
                Relation relation = verdict.getRelation();
                if (relation == Relation.VERSCHIEDEN || (across && relation == Relation.B_TEIL_VON_A)
                        || first.held || second.held) {
                    continue;
                }
                if (first.merged || second.merged || !requests.get(n)[1].equals(parentText(first, pool, itemsOf))
                        || !requests.get(n)[3].equals(parentText(second, pool, itemsOf))) {
                    add(counts, across ? "acrossPairsStale" : "parentPairsStale", 1);
                    log("topic merge: the verdict on „" + first.name + "“ and „" + second.name + "“ is not applied: "
                            + "one of them changed since it was asked");
                    continue;
                }
                if (!isStanding(first) || !isStanding(second)) {
                    continue;
                }
                if (across && relation != Relation.GLEICH) {
                    add(counts, "acrossPartOf", 1);
                    log("topic merge: the parent „" + first.name + "“ stays at the top: across places only the same "
                            + "subject merges, and „" + second.name + "“ is no such (" + relation.word() + ")");
                    continue;
                }
                Parent keep = across || relation == Relation.A_TEIL_VON_B ? second
                        : relation == Relation.B_TEIL_VON_A ? first
                        : first.parts.size() > second.parts.size() ? first
                        : second.parts.size() > first.parts.size() ? second
                        : parents.indexOf(first) <= parents.indexOf(second) ? first : second;
                Parent gone = keep == first ? second : first;
                if (across && !fits(gone, keep)) {
                    add(counts, "acrossTooDeep", 1);
                    log("topic merge: the parent „" + gone.name + "“ stays at the top: under „" + keep.name + "“ "
                            + "its parts would reach deeper than " + options.getDepth() + " levels");
                    continue;
                }
                for (int part : gone.parts) {
                    unions.parentOf.put(part, keep.node);
                }
                keep.parts.addAll(gone.parts);
                keep.seen.addAll(gone.seen);
                gone.parts.clear();
                gone.merged = true;
                dropPart(gone);
                into.put(gone, keep);
                changed = true;
                add(counts, across ? "acrossMerged" : "parentsMerged", 1);
                if (byRare.get(n)) {
                    add(counts, across ? "rareAcrossMerged" : "rarePairsMerged", 1);
                }
                log("topic merge: the parent „" + gone.name + "“ gave its parts to „" + keep.name + "“ ("
                        + relation.word() + (across ? ", another place" : "") + ")");
            }
            for (Iterator<Parent> it = parents.iterator(); it.hasNext(); ) {
                if (it.next().merged) {
                    it.remove();
                }
            }
            return changed;
        }

        /**
         * The heads no case took in, each put to the most similar new parent of its place (the architect's run c15f
         * of 22.11.2026: heads of a subject left loose beside its parents). A head counts when it is free at the top
         * or a loose subtopic of a topic, is no known topic, is not held back and has room for one level above it; the
         * parent's mean vector must reach {@link Options#getParentSimilarity()}, and a head a case already judged for
         * that parent is not put to it again. The model reads the head and the parent as two topics; "a_teil_von_b"
         * or "gleich" puts the head under the parent, when the head may still go there as the batch then stands
         * ({@link #canJoin}); a full parent takes it only on "gleich" ({@link Options#isFullLoose()}). A failed case
         * holds the head back; a verdict on the same two texts is reused. Then a head no case joined meets the most
         * similar parent of its place it shares a rare term with ({@link Options#withRareTerms}), at any similarity,
         * once.
         */
        void loose() {
            Map<Integer, List<Parent>> byPlace = standing();
            if (byPlace.isEmpty()) {
                return;
            }
            Map<Integer, List<float[]>> vectorsAt = new HashMap<Integer, List<float[]>>();
            for (Map.Entry<Integer, List<Parent>> here : byPlace.entrySet()) {
                vectorsAt.put(here.getKey(), vectorsOf(here.getValue()));
            }
            List<Integer> heads = new ArrayList<Integer>();
            List<Parent> targets = new ArrayList<Parent>();
            List<String[]> requests = new ArrayList<String[]>();
            List<Double> similarities = new ArrayList<Double>();
            List<Integer> loose = new ArrayList<Integer>();
            for (int root : itemsOf.keySet()) {
                List<Parent> here = looseAt(root, byPlace);
                if (here == null) {
                    continue;
                }
                loose.add(root);
                int at = placeOf(root);
                float[] vector = centroidOf(pool, itemsOf.get(root));
                Parent best = null;
                double score = -2.0;
                for (int i = 0; i < here.size(); i++) {
                    double s = dot(vector, vectorsAt.get(at).get(i));
                    if (!here.get(i).seen.contains(root) && s > score) {
                        best = here.get(i);
                        score = s;
                    }
                }
                if (best == null || score < options.getParentSimilarity()) {
                    continue;
                }
                heads.add(root);
                targets.add(best);
                requests.add(new String[]{pool.items.get(itemsOf.get(root).get(0)).id,
                        shownMembers(pool, itemsOf.get(root)), logId(best), parentText(best, pool, itemsOf)});
                similarities.add(score);
            }
            joinAll(heads, targets, judge(requests, similarities, NOTE_LOOSE), "loose", false);

            // the rare-term case: a head its case by similarity did not join, or that had none
            if (rareTerms() == null) {
                return;
            }
            List<Integer> rareHeads = new ArrayList<Integer>();
            List<Parent> rareTargets = new ArrayList<Parent>();
            List<String[]> rareRequests = new ArrayList<String[]>();
            List<Double> rareSimilarities = new ArrayList<Double>();
            List<String> rareNotes = new ArrayList<String>();
            for (int root : loose) {
                List<Parent> here = looseAt(root, byPlace);
                if (here == null) {
                    continue;
                }
                int at = placeOf(root);
                float[] vector = centroidOf(pool, itemsOf.get(root));
                double[] to = new double[here.size()];
                for (int i = 0; i < here.size(); i++) {
                    to[i] = here.get(i).seen.contains(root) || !isStanding(here.get(i)) ? -2.0
                            : dot(vector, vectorsAt.get(at).get(i));
                }
                RareCandidate extra = rareAmong(termsOfRoot(root), here, to, Collections.<Integer>emptyList());
                if (extra == null) {
                    continue;
                }
                Parent parent = here.get(extra.index);
                String aText = shownMembers(pool, itemsOf.get(root));
                String bText = parentText(parent, pool, itemsOf);
                if (judgedBefore(aText, bText)) {
                    continue;
                }
                rareHeads.add(root);
                rareTargets.add(parent);
                rareRequests.add(new String[]{pool.items.get(itemsOf.get(root).get(0)).id, aText, logId(parent),
                        bText});
                rareSimilarities.add(to[extra.index]);
                rareNotes.add(NOTE_LOOSE + termNote(extra.term));
            }
            joinAll(rareHeads, rareTargets, judge(rareRequests, rareSimilarities, rareNotes), "loose", true);
        }

        /**
         * The parents of a head's place when the head counts as loose ({@link #loose()}): free at the top or a loose
         * subtopic of a topic, no known topic, not held back, with room for one level above it; else null.
         */
        private List<Parent> looseAt(int root, Map<Integer, List<Parent>> byPlace) {
            if (unions.anchorOf(root) >= 0 || heldRoots.contains(root)) {
                return null;
            }
            int at = placeOf(root);
            List<Parent> here = byPlace.get(at);
            if (here == null || (at >= 0 && unions.isFormedNode(at)) || reach(root, at, unions) > options.getDepth()) {
                return null;
            }
            return here;
        }

        /** The topic a head hangs under, or -1 at the top. */
        private int placeOf(int root) {
            Integer up = unions.parentOf.get(root);
            return up == null ? -1 : unions.find(up);
        }

        /**
         * Lever (b) of the architect after c15g (22.11.2026: of the families of one expected topic most were single
         * proposals, heads that no case and no consolidation took in, or single children right under a card). Each
         * head that stands alone, with no subtopic, at the top or right under a card, no known topic, not held back
         * and taken in by no parent as a part, is put to the most similar new parent of any place, at or above
         * {@link Options#getParentSimilarity()}, that has not judged it yet: a parent whose case or whose
         * consolidation at the head's place judged the head is passed over, and the next most similar one is asked.
         * The same two texts are not asked twice in a run; such a head stays where it is. "a_teil_von_b" or "gleich"
         * puts the head under the parent when it may still go there ({@link #canJoin}); a full parent
         * ({@link Options#getFullParts()}, counted as the batch then stands) takes it only on "gleich". A failed case
         * holds the head back. Then a head that still stands alone meets the most similar parent of any place it
         * shares a rare term with ({@link Options#withRareTerms}) and that has not judged it, at any similarity, once.
         */
        void singles() {
            List<Parent> all = new ArrayList<Parent>();
            for (List<Parent> here : standing().values()) {
                all.addAll(here);
            }
            if (all.isEmpty()) {
                return;
            }
            Set<Integer> takenIn = new HashSet<Integer>();
            for (Parent parent : parents) {
                takenIn.addAll(parent.parts);
            }
            List<float[]> vectors = vectorsOf(all);
            List<Integer> heads = new ArrayList<Integer>();
            List<Parent> targets = new ArrayList<Parent>();
            List<String[]> requests = new ArrayList<String[]>();
            List<Double> similarities = new ArrayList<Double>();
            for (int root : itemsOf.keySet()) {
                if (takenIn.contains(root) || !alone(root)) {
                    continue;
                }
                float[] vector = centroidOf(pool, itemsOf.get(root));
                Parent best = null;
                double score = -2.0;
                for (int i = 0; i < all.size(); i++) {
                    double s = dot(vector, vectors.get(i));
                    if (!all.get(i).seen.contains(root) && s > score) {
                        best = all.get(i);
                        score = s;
                    }
                }
                if (best == null || score < options.getParentSimilarity()) {
                    continue;
                }
                String aText = shownMembers(pool, itemsOf.get(root));
                String bText = parentText(best, pool, itemsOf);
                if (judgedBefore(aText, bText)) {
                    continue;
                }
                heads.add(root);
                targets.add(best);
                requests.add(new String[]{pool.items.get(itemsOf.get(root).get(0)).id, aText, logId(best), bText});
                similarities.add(score);
            }
            joinAll(heads, targets, judge(requests, similarities, NOTE_SINGLE), "single", false);

            // the rare-term case: a head its case by similarity did not join, or that had none
            if (rareTerms() == null) {
                return;
            }
            List<Integer> rareHeads = new ArrayList<Integer>();
            List<Parent> rareTargets = new ArrayList<Parent>();
            List<String[]> rareRequests = new ArrayList<String[]>();
            List<Double> rareSimilarities = new ArrayList<Double>();
            List<String> rareNotes = new ArrayList<String>();
            for (int root : itemsOf.keySet()) {
                // a head the first batch joined hangs under a parent now and stands alone no more
                if (takenIn.contains(root) || !alone(root)) {
                    continue;
                }
                float[] vector = centroidOf(pool, itemsOf.get(root));
                double[] to = new double[all.size()];
                for (int i = 0; i < all.size(); i++) {
                    to[i] = all.get(i).seen.contains(root) || !isStanding(all.get(i)) ? -2.0
                            : dot(vector, vectors.get(i));
                }
                RareCandidate extra = rareAmong(termsOfRoot(root), all, to, Collections.<Integer>emptyList());
                if (extra == null) {
                    continue;
                }
                Parent parent = all.get(extra.index);
                String aText = shownMembers(pool, itemsOf.get(root));
                String bText = parentText(parent, pool, itemsOf);
                if (judgedBefore(aText, bText)) {
                    continue;
                }
                rareHeads.add(root);
                rareTargets.add(parent);
                rareRequests.add(new String[]{pool.items.get(itemsOf.get(root).get(0)).id, aText, logId(parent),
                        bText});
                rareSimilarities.add(to[extra.index]);
                rareNotes.add(NOTE_SINGLE + termNote(extra.term));
            }
            joinAll(rareHeads, rareTargets, judge(rareRequests, rareSimilarities, rareNotes), "single", true);
        }

        /**
         * A head that stands alone: no known topic, not held back, no subtopic, at the top or right under a card. The
         * parts of a parent the card step joined into a card hang right under the card too, but a case took them in.
         */
        private boolean alone(int root) {
            if (unions.anchorOf(root) >= 0 || heldRoots.contains(root) || unions.height(root) > 0) {
                return false;
            }
            Integer up = unions.parentOf.get(root);
            if (up == null) {
                return true;
            }
            int above = unions.anchorOf(up);
            return above >= 0 && pool.anchors.get(above).card;
        }

        /**
         * The verdicts of heads put to parents ({@code kind} "loose" or "single" for the counts, {@code rare} for a
         * case a rare term brought about): "a_teil_von_b" or "gleich" puts the head under the parent when it may still
         * go there, and a full parent takes a head that stands alone, and with {@link Options#isFullLoose()} a loose
         * head, only on "gleich"; a failed case holds its family back.
         */
        private void joinAll(List<Integer> heads, List<Parent> targets, List<Comparison> said, String kind,
                             boolean rare) {
            String rareKind = "rare" + Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
            for (int k = 0; k < heads.size(); k++) {
                int root = heads.get(k);
                Parent parent = targets.get(k);
                Comparison verdict = said.get(k);
                add(counts, kind + "Cases", 1);
                if (rare) {
                    add(counts, rareKind, 1);
                }
                if (!verdict.isOk()) {
                    String why = kind + " head case failed: " + verdict.getProblem();
                    for (int member : familyOf(root, pool.groups.size(), unions)) {
                        if (heldRoots.add(member)) {
                            heldReason.put(member, why);
                        }
                    }
                    continue;
                }
                Relation relation = verdict.getRelation();
                // judged for this parent, joined or not: singles() passes the parent over for this head even when the
                // parent's text changed since, as for the heads its case judged (the review of 22.11.2026)
                parent.seen.add(root);
                if (relation == Relation.A_TEIL_VON_B && full(parent)
                        && ("single".equals(kind) || ("loose".equals(kind) && options.isFullLoose()))) {
                    add(counts, kind + "Full", 1);
                    log("topic merge: " + pool.items.get(itemsOf.get(root).get(0)).id + " stays where it is: „"
                            + parent.name + "“ has " + parent.parts.size() + " parts and takes a head only as the "
                            + "same subject");
                    continue;
                }
                if ((relation == Relation.A_TEIL_VON_B || relation == Relation.GLEICH) && canJoin(root, parent)) {
                    unions.parentOf.put(root, parent.node);
                    parent.parts.add(root);
                    add(counts, kind + "Joined", 1);
                    if (rare) {
                        add(counts, rareKind + "Joined", 1);
                    }
                }
            }
        }

        /** Whether a parent has as many parts as {@link Options#getFullParts()} (never, when that is 0). */
        private boolean full(Parent parent) {
            return options.getFullParts() > 0 && parent.parts.size() >= options.getFullParts();
        }

        /**
         * Whether a head may go under a parent as things stand now, not as they stood when its batch was put
         * together (the review of 22.11.2026: two joins of one batch could stack past the depth): the head not held
         * back, the parent still standing and not below the head, and room for the head's family under the parent.
         */
        private boolean canJoin(int root, Parent parent) {
            if (heldRoots.contains(root) || !isStanding(parent) || isBelow(parent.node, root)) {
                return false;
            }
            if (reach(root, place(parent), unions) > options.getDepth()) {
                log("topic merge: " + pool.items.get(itemsOf.get(root).get(0)).id + " stays where it is: under „"
                        + parent.name + "“ its family would reach deeper than " + options.getDepth() + " levels");
                return false;
            }
            return true;
        }

        /** Whether every part of one parent has room for its family under another, and none lies above it. */
        private boolean fits(Parent gone, Parent keep) {
            int at = place(keep);
            for (int part : gone.parts) {
                if (reach(part, at, unions) > options.getDepth() || isBelow(keep.node, part)) {
                    return false;
                }
            }
            return !isBelow(keep.node, gone.node);
        }

        /** Whether a node lies below another, through its chain of parents. */
        private boolean isBelow(int node, int above) {
            int target = unions.find(above);
            Set<Integer> seen = new HashSet<Integer>();
            Integer up = unions.parentOf.get(unions.find(node));
            while (up != null) {
                int at = unions.find(up);
                if (at == target) {
                    return true;
                }
                if (!seen.add(at)) {
                    return false;
                }
                up = unions.parentOf.get(at);
            }
            return false;
        }

        /**
         * A new parent whose parts are all held back is held back too: a parent of the second pass under a head
         * whose family a failed case held (the review of 22.11.2026) would else come out as an empty topic at the top.
         */
        void holdEmptied() {
            for (Parent parent : parents) {
                if (parent.node < 0 || parent.held || parent.merged) {
                    continue;
                }
                boolean all = true;
                for (int part : parent.parts) {
                    all &= partHeld(part);
                }
                if (all) {
                    parent.held = true;
                    log("topic merge: the parent „" + parent.name + "“ is held back with its parts");
                }
            }
        }

        /** The new parents that stand ({@link #isStanding}) by their place, in the order they formed. */
        private Map<Integer, List<Parent>> standing() {
            Map<Integer, List<Parent>> byPlace = new LinkedHashMap<Integer, List<Parent>>();
            for (Parent parent : parents) {
                if (!isStanding(parent)) {
                    continue;
                }
                int at = place(parent);
                if (!byPlace.containsKey(at)) {
                    byPlace.put(at, new ArrayList<Parent>());
                }
                byPlace.get(at).add(parent);
            }
            return byPlace;
        }

        /**
         * A new parent topic that still stands for itself: not held back, not given away, with a part that is not
         * held back, and not joined into a card. The card step can answer "gleich" for a parent of the first pass;
         * its node is then the card, its parts hang right under the card, and it is no parent to compare any more
         * (the review of 22.11.2026: such a parent counted as standing at the top).
         */
        private boolean isStanding(Parent parent) {
            if (parent.node < 0 || parent.held || parent.merged || unions.find(parent.node) != parent.node
                    || unions.anchorOf(parent.node) >= 0) {
                return false;
            }
            for (int part : parent.parts) {
                if (!partHeld(part)) {
                    return true;
                }
            }
            return false;
        }

        /** The mean vector of a parent's parts. */
        private float[] vectorOf(Parent parent) {
            List<float[]> vectors = new ArrayList<float[]>();
            for (int part : parent.parts) {
                List<Integer> items = itemsOfPart(part);
                if (!items.isEmpty()) {
                    vectors.add(centroidOf(pool, items));
                }
            }
            return unit(mean(vectors));
        }

        /**
         * The proposals of a part: those of a head, or for a parent the subject step kept as a subfamily under a new
         * parent, those of every head below it.
         */
        private List<Integer> itemsOfPart(int part) {
            List<Integer> own = itemsOf.get(part);
            if (own != null) {
                return own;
            }
            List<Integer> below = new ArrayList<Integer>();
            for (int root : familyOf(part, pool.groups.size(), unions)) {
                List<Integer> items = itemsOf.get(root);
                if (items != null) {
                    below.addAll(items);
                }
            }
            Collections.sort(below);
            return below;
        }

        /**
         * A parent formed no more is no part of another any more: a subfamily the subject step kept under a new parent
         * that gave its parts away (the review of fcca35cf: the new parent kept the dead node as a part).
         */
        private void dropPart(Parent gone) {
            if (gone.node < 0) {
                return;
            }
            for (Parent parent : parents) {
                parent.parts.remove(Integer.valueOf(gone.node));
            }
        }

        /** Whether a part is held back: a head, or every head below a subfamily. */
        private boolean partHeld(int part) {
            if (itemsOf.containsKey(part)) {
                return heldRoots.contains(part);
            }
            for (int root : familyOf(part, pool.groups.size(), unions)) {
                if (!heldRoots.contains(root)) {
                    return false;
                }
            }
            return true;
        }

        /** The name a part shows: that of a head's central proposal, or of a subfamily's parent. */
        private String partName(int part) {
            List<Integer> own = itemsOf.get(part);
            if (own != null) {
                return pool.items.get(central(own, pool.items)).name;
            }
            for (Parent parent : parents) {
                if (parent.node == part) {
                    return parent.name;
                }
            }
            List<Integer> below = itemsOfPart(part);
            return below.isEmpty() ? "" : pool.items.get(central(below, pool.items)).name;
        }

        /** A new parent topic as the card step reads it: its name, what it is about and some of its parts. */
        private String parentText(Parent parent, Pool pool, Map<Integer, List<Integer>> itemsOf) {
            List<String> names = new ArrayList<String>();
            for (int part : parent.parts) {
                if (names.size() < PARTS_SHOWN) {
                    names.add(partName(part));
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

        private List<float[]> vectorsOf(List<Parent> some) {
            List<float[]> vectors = new ArrayList<float[]>();
            for (Parent parent : some) {
                vectors.add(vectorOf(parent));
            }
            return vectors;
        }

        /** The parent a parent's parts went to, through every parent that gave its parts on. */
        private Parent top(Map<Parent, Parent> into, Parent parent) {
            Parent at = parent;
            while (into.containsKey(at)) {
                at = into.get(at);
            }
            return at;
        }

        /** Holds a parent and its family back, so the next run asks again. */
        private void hold(Parent parent, String why) {
            if (parent.held || parent.merged) {
                return;
            }
            parent.held = true;
            for (int root : familyOf(parent.node, pool.groups.size(), unions)) {
                if (heldRoots.add(root)) {
                    heldReason.put(root, why);
                }
            }
        }

        /** The id a new parent is logged under before it has its number, as in the card step. */
        private String logId(Parent parent) {
            for (int part : parent.parts) {
                List<Integer> items = itemsOfPart(part);
                if (!items.isEmpty()) {
                    return PARENT_ID_PREFIX + pool.items.get(items.get(0)).id;
                }
            }
            return PARENT_ID_PREFIX + parent.name;
        }

        /** Whether two texts were judged in this run already, in either order. */
        private boolean judgedBefore(String a, String b) {
            return judged.contains(Comparison.key(Comparison.Kind.THEMA, a, b))
                    || judged.contains(Comparison.key(Comparison.Kind.THEMA, b, a));
        }

        /**
         * Two topics each ({id A, text A, id B, text B}): reused when the same two texts were judged before, in
         * either order (the other order with the verdict turned round), else asked, at most
         * {@link Options#getParallel()} at a time; the verdicts in the order given, with the note. The texts count as
         * judged in this run.
         */
        private List<Comparison> judge(List<String[]> requests, List<Double> similarities, String note) {
            return judge(requests, similarities, Collections.nCopies(requests.size(), note));
        }

        /** As {@link #judge(List, List, String)}, with a note for each case. */
        private List<Comparison> judge(List<String[]> requests, List<Double> similarities, List<String> notes) {
            Comparison[] out = new Comparison[requests.size()];
            List<Integer> open = new ArrayList<Integer>();
            List<Case> calls = new ArrayList<Case>();
            for (int i = 0; i < requests.size(); i++) {
                final String[] request = requests.get(i);
                final double similarity = similarities.get(i);
                final String note = notes.get(i);
                judged.add(Comparison.key(Comparison.Kind.THEMA, request[1], request[3]));
                Comparison earlier = verdicts.get(Comparison.key(Comparison.Kind.THEMA, request[1], request[3]));
                Comparison turned = verdicts.get(Comparison.key(Comparison.Kind.THEMA, request[3], request[1]));
                if (earlier != null) {
                    out[i] = note.equals(earlier.getNote()) ? earlier : noted(earlier, note);
                    used.add(out[i]);
                    continue;
                }
                if (turned != null) {
                    out[i] = new Comparison(Comparison.Kind.THEMA, request[0], request[1], request[2], request[3],
                            false, similarity, Comparison.Status.OK, turned.getRelation().flipped(),
                            turned.getReason(), "", turned.getAnswers(), turned.getModel(), turned.getTimeMillis(), 0L,
                            turned.getVersion(), note);
                    used.add(out[i]);
                    continue;
                }
                open.add(i);
                calls.add(new Case() {
                    @Override
                    public Comparison ask() {
                        return noted(TopicMerger.this.ask(Comparison.Kind.THEMA, "", request, false, similarity),
                                note);
                    }

                    @Override
                    public Comparison notAsked(String why) {
                        return new Comparison(Comparison.Kind.THEMA, request[0], request[1], request[2], request[3],
                                false, similarity, Comparison.Status.FEHLER, null, "", why, null, chat.modelName(),
                                clock.millis(), 0L, versionOf(Comparison.Kind.THEMA), note);
                    }
                });
            }
            List<Comparison> results = run(calls, recorder);
            for (int k = 0; k < open.size(); k++) {
                out[open.get(k)] = results.get(k);
                used.add(results.get(k));
                asked.add(results.get(k));
            }
            return Arrays.asList(out);
        }

        /**
         * The subject step ({@link Options#withSubjects}; the builder's lever after the architect's run c15k of
         * 22.11.2026, with the architect's four conditions of the same evening). The families of each place, the top
         * and each card: the new parents that stand there and the heads that stand there for themselves, each with
         * what hangs below it. First the model names the subject of each family from the family's name and up to
         * {@value #SUBJECT_SAMPLES} of its parts, in one to {@value #SUBJECT_WORDS} words without the aspect (not
         * only from the parent's name: the architect's first condition). A family under a card whose subject is
         * worded like the card's name, or whose subject vector reaches {@link Options#getSubjectSimilarity()} to the
         * card's name, stays where it is, with a log line and a finding (his second condition: the subject of the card
         * alone draws nothing together). Then each family goes to the model with up to {@link Options#getCandidates()}
         * families of its place whose subject is worded alike or whose subject vector reaches that similarity, the
         * families most similar by their proposals first: "the same subject, also from different angles?", always,
         * also for the same wording. The families the "ja" verdicts join, directly or through others, go under a new
         * parent at their place named by the subject most of them name (then that of the largest family): at the
         * top a parent keeps its place below the new one as a subfamily where the depth allows, under a card it is
         * laid flat, its parts going under the new parent; a head goes under the new parent with what hangs below it
         * where the depth allows, else laid flat with it (his third condition). A new parent takes any number of
         * parts ({@link Options#getFullParts()} does not hold here); its size is counted and logged. A parent named
         * like the subject is laid flat, so the new parent takes its place. A failed case holds its families back, so
         * the next run asks again; a verdict on the same texts is reused, a pair in either order. A family alone at
         * its place is not named: it has nothing to meet. Across places nothing changes here; a new parent at the top
         * goes through the card step with {@link #acrossPlaces()}, or right after this step when that does not run.
         * Returns the new parents formed at the top.
         */
        List<Parent> subjects() {
            List<Parent> topRoofs = new ArrayList<Parent>();
            // a family alone at its place has nothing to meet: it is not named (the review of fcca35cf)
            Map<Integer, Integer> atPlace = new HashMap<Integer, Integer>();
            List<Family> found = families();
            for (Family family : found) {
                Integer before = atPlace.get(family.place);
                atPlace.put(family.place, before == null ? 1 : before + 1);
            }
            List<Family> families = new ArrayList<Family>();
            for (Family family : found) {
                if (atPlace.get(family.place) >= 2) {
                    families.add(family);
                }
            }
            if (families.isEmpty()) {
                return topRoofs;
            }
            // the subject of each family
            List<Family> toAsk = new ArrayList<Family>();
            List<Case> calls = new ArrayList<Case>();
            for (Family family : families) {
                final String[] request = {family.id, family.text, "", Comparison.SUBJECT_MARK};
                Comparison earlier = verdicts.get(Comparison.key(Comparison.Kind.GEGENSTAND, family.text,
                        Comparison.SUBJECT_MARK));
                if (earlier != null) {
                    family.verdict = earlier;
                    used.add(earlier);
                    continue;
                }
                toAsk.add(family);
                calls.add(new Case() {
                    @Override
                    public Comparison ask() {
                        return askSubject(Comparison.Kind.GEGENSTAND, request, "", 0.0);
                    }

                    @Override
                    public Comparison notAsked(String why) {
                        return new Comparison(Comparison.Kind.GEGENSTAND, request[0], request[1], request[2],
                                request[3], false, 0.0, Comparison.Status.FEHLER, null, "", why, null,
                                chat.modelName(), clock.millis(), 0L, versionOf(Comparison.Kind.GEGENSTAND), "");
                    }
                });
            }
            List<Comparison> results = run(calls, recorder);
            for (int i = 0; i < toAsk.size(); i++) {
                toAsk.get(i).verdict = results.get(i);
                used.add(results.get(i));
                asked.add(results.get(i));
            }
            List<Family> named = new ArrayList<Family>();
            for (Family family : families) {
                if (!family.verdict.isOk()) {
                    add(counts, "subjectFailed", 1);
                    holdFamily(family, "subject step failed: " + family.verdict.getProblem());
                    continue;
                }
                family.subject = subjectOf(family.verdict);
                if (family.subject.isEmpty()) {
                    add(counts, "subjectFailed", 1);
                    continue;
                }
                add(counts, "subjectFamilies", 1);
                log("topic merge: the subject of " + family.id + " (\u201e" + family.name + "\u201c) is \u201e"
                        + family.subject + "\u201c");
                named.add(family);
            }

            // the vectors of the subjects and of the cards' names; the card's own subject draws nothing together
            List<String> texts = new ArrayList<String>();
            for (Family family : named) {
                texts.add(family.subject);
                if (family.place >= 0) {
                    texts.add(cardName(family.place));
                }
            }
            if (texts.isEmpty()) {
                return topRoofs;
            }
            embedAll(texts);
            Map<Integer, List<Family>> byPlace = new LinkedHashMap<Integer, List<Family>>();
            for (Family family : named) {
                if (family.place >= 0) {
                    String card = cardName(family.place);
                    double toCard = dot(vectorCache.get(family.subject), vectorCache.get(card));
                    if (wording(card).equals(wording(family.subject)) || toCard >= options.getSubjectSimilarity()) {
                        add(counts, "subjectCard", 1);
                        log("topic merge: " + family.id + " (\u201e" + family.name + "\u201c) stays where it is: its "
                                + "subject \u201e" + family.subject + "\u201c is that of the card \u201e" + card
                                + "\u201c (" + String.format(Locale.ROOT, "%.3f", toCard) + ")");
                        subjectFindings.add(new SubjectFinding(SubjectFinding.CARD, card, family.subject,
                                Collections.singletonList(family.name), childrenOf(family.root).size(),
                                proposalsBelow(family.root)));
                        continue;
                    }
                }
                if (!byPlace.containsKey(family.place)) {
                    byPlace.put(family.place, new ArrayList<Family>());
                }
                byPlace.get(family.place).add(family);
            }

            // the pairs of each place: the same subject, also from different angles?
            List<Family[]> pairs = new ArrayList<Family[]>();
            List<Double> similarities = new ArrayList<Double>();
            for (List<Family> here : byPlace.values()) {
                Set<String> keys = new HashSet<String>();
                for (int i = 0; i < here.size(); i++) {
                    final Family a = here.get(i);
                    final double[] near = new double[here.size()];
                    List<Integer> alike = new ArrayList<Integer>();
                    for (int j = 0; j < here.size(); j++) {
                        Family b = here.get(j);
                        double subject = dot(vectorCache.get(a.subject), vectorCache.get(b.subject));
                        if (j != i && (wording(a.subject).equals(wording(b.subject))
                                || subject >= options.getSubjectSimilarity())) {
                            alike.add(j);
                            near[j] = dot(a.vector, b.vector);
                        }
                    }
                    Collections.sort(alike, (x, y) -> near[y] != near[x] ? Double.compare(near[y], near[x])
                            : Integer.compare(x, y));
                    for (int j : alike.subList(0, Math.min(options.getCandidates(), alike.size()))) {
                        int lo = Math.min(i, j);
                        int hi = Math.max(i, j);
                        if (keys.add(lo + ":" + hi)) {
                            pairs.add(new Family[]{here.get(lo), here.get(hi)});
                            similarities.add(dot(vectorCache.get(here.get(lo).subject),
                                    vectorCache.get(here.get(hi).subject)));
                        }
                    }
                }
            }
            List<Comparison> said = judgeSubjects(pairs, similarities);
            for (int n = 0; n < pairs.size(); n++) {
                add(counts, "subjectPairs", 1);
                if (!said.get(n).isOk()) {
                    add(counts, "subjectPairsFailed", 1);
                    String why = "subject pair failed: " + said.get(n).getProblem();
                    holdFamily(pairs.get(n)[0], why);
                    holdFamily(pairs.get(n)[1], why);
                }
            }
            Map<Family, Family> joined = new HashMap<Family, Family>();
            List<Integer> yes = new ArrayList<Integer>();
            for (int n = 0; n < pairs.size(); n++) {
                Family a = pairs.get(n)[0];
                Family b = pairs.get(n)[1];
                if (!said.get(n).isOk() || said.get(n).getRelation() != Relation.GLEICH) {
                    continue;
                }
                add(counts, "subjectYes", 1);
                if (a.held || b.held) {
                    continue;
                }
                yes.add(n);
                Family ra = familyRoot(joined, a);
                Family rb = familyRoot(joined, b);
                if (ra != rb) {
                    joined.put(rb, ra);
                }
            }

            // a new parent over the families each group of "ja" verdicts joins
            for (Map.Entry<Integer, List<Family>> here : byPlace.entrySet()) {
                Map<Family, List<Family>> groups = new LinkedHashMap<Family, List<Family>>();
                for (Family family : here.getValue()) {
                    if (family.held || !joined.containsKey(family) && !joined.containsValue(family)) {
                        continue;
                    }
                    Family top = familyRoot(joined, family);
                    if (!groups.containsKey(top)) {
                        groups.put(top, new ArrayList<Family>());
                    }
                    groups.get(top).add(family);
                }
                for (Map.Entry<Family, List<Family>> group : groups.entrySet()) {
                    if (group.getValue().size() < 2) {
                        continue;
                    }
                    Comparison verdict = null;
                    for (int n : yes) {
                        if (verdict == null && familyRoot(joined, pairs.get(n)[0]) == group.getKey()) {
                            verdict = said.get(n);
                        }
                    }
                    Parent roof = roof(here.getKey(), group.getValue(), verdict);
                    if (roof != null && roof.node >= 0 && here.getKey() < 0) {
                        topRoofs.add(roof);
                    }
                }
            }
            for (Iterator<Parent> it = parents.iterator(); it.hasNext(); ) {
                if (it.next().merged) {
                    it.remove();
                }
            }
            return topRoofs;
        }

        /** A family of the subject step: a new parent that stands at the place, or a head with what hangs below it. */
        private final class Family {
            /** The node of the parent, or the head's group root. */
            final int root;
            /** The new parent, null for a head. */
            final Parent parent;
            /** The card it stands under, -1 at the top. */
            final int place;
            /** The id it is logged under. */
            final String id;
            final String name;
            /** The family as the model reads it: its name and some of its parts. */
            final String text;
            /** The mean vector of its proposals' vectors, of the head's own for a head. */
            final float[] vector;
            Comparison verdict;
            String subject = "";
            boolean held;

            Family(int root, Parent parent, int place) {
                this.root = root;
                this.parent = parent;
                this.place = place;
                this.id = parent != null ? logId(parent) : pool.items.get(itemsOf.get(root).get(0)).id;
                this.name = parent != null ? parent.name : pool.items.get(central(itemsOf.get(root), pool.items)).name;
                this.vector = parent != null ? vectorOf(parent) : centroidOf(pool, itemsOf.get(root));
                List<String> samples = new ArrayList<String>();
                List<Integer> children = childrenOf(root);
                for (int child : children) {
                    if (samples.size() < SUBJECT_SAMPLES) {
                        samples.add(partName(child));
                    }
                }
                String head = parent != null ? (german ? "Oberthema: " : "Parent topic: ") + parent.name
                        : (german ? "Thema: " : "Topic: ") + shownMembers(pool, itemsOf.get(root));
                this.text = head + (samples.isEmpty() ? "" : "\n" + (german ? "Teilthemen: " : "Parts: ")
                        + String.join("; ", samples) + (children.size() > samples.size() ? "; …" : ""));
            }
        }

        /** The families of the places the subject step looks at, the new parents first, then the heads. */
        private List<Family> families() {
            List<Family> families = new ArrayList<Family>();
            for (Map.Entry<Integer, List<Parent>> here : standing().entrySet()) {
                if (subjectPlace(here.getKey())) {
                    for (Parent parent : here.getValue()) {
                        families.add(new Family(parent.node, parent, here.getKey()));
                    }
                }
            }
            for (int root : itemsOf.keySet()) {
                if (unions.find(root) != root || unions.anchorOf(root) >= 0 || heldRoots.contains(root)) {
                    continue;
                }
                int at = placeOf(root);
                if (subjectPlace(at)) {
                    families.add(new Family(root, null, at));
                }
            }
            return families;
        }

        /** The top (-1) or a card of the mind map. */
        private boolean subjectPlace(int place) {
            return place < 0 || (unions.anchorOf(place) >= 0 && pool.anchors.get(unions.anchorOf(place)).card);
        }

        private String cardName(int place) {
            return pool.anchors.get(unions.anchorOf(place)).name;
        }

        /** The subject a verdict named: read again from its last answer, else from its note. */
        private String subjectOf(Comparison verdict) {
            List<String> answers = verdict.getAnswers();
            Verdict read = parseSubject(answers.isEmpty() ? "" : answers.get(answers.size() - 1));
            if (read.problem.isEmpty()) {
                return read.subject;
            }
            String note = verdict.getNote();
            return note.startsWith(NOTE_SUBJECT) ? note.substring(NOTE_SUBJECT.length()).trim() : "";
        }

        /** The family a family's group goes up to through the "ja" verdicts. */
        private Family familyRoot(Map<Family, Family> joined, Family family) {
            Family at = family;
            while (joined.containsKey(at)) {
                at = joined.get(at);
            }
            return at;
        }

        /** What hangs right below a node: heads and new parents that stand. */
        private List<Integer> childrenOf(int node) {
            int at = unions.find(node);
            List<Integer> children = new ArrayList<Integer>();
            for (int root : itemsOf.keySet()) {
                if (root != at && unions.find(root) == root && placeOf(root) == at) {
                    children.add(root);
                }
            }
            for (Parent parent : parents) {
                if (parent.node >= 0 && parent.node != at && !parent.merged && unions.find(parent.node) == parent.node
                        && place(parent) == at) {
                    children.add(parent.node);
                }
            }
            return children;
        }

        /** The proposals in a node's family. */
        private int proposalsBelow(int node) {
            return itemsOfPart(node).size() + (itemsOf.containsKey(node) ? itemsBelowHead(node) : 0);
        }

        /** The proposals of the heads below a head, not its own. */
        private int itemsBelowHead(int head) {
            int n = 0;
            for (int root : familyOf(head, pool.groups.size(), unions)) {
                if (root != head && itemsOf.containsKey(root)) {
                    n += itemsOf.get(root).size();
                }
            }
            return n;
        }

        /** Holds a family of the subject step back, so the next run asks again. */
        private void holdFamily(Family family, String why) {
            family.held = true;
            if (family.parent != null) {
                hold(family.parent, why);
                return;
            }
            for (int root : familyOf(family.root, pool.groups.size(), unions)) {
                if (heldRoots.add(root)) {
                    heldReason.put(root, why);
                }
            }
        }

        /**
         * The pairs of families: reused when the same two texts were judged before, in either order (the answer is the
         * same either way), else asked, at most {@link Options#getParallel()} at a time; the verdicts in the order
         * given, each with the note of the two subjects.
         */
        private List<Comparison> judgeSubjects(List<Family[]> pairs, List<Double> similarities) {
            Comparison[] out = new Comparison[pairs.size()];
            List<Integer> open = new ArrayList<Integer>();
            List<Case> calls = new ArrayList<Case>();
            for (int i = 0; i < pairs.size(); i++) {
                Family a = pairs.get(i)[0];
                Family b = pairs.get(i)[1];
                final String[] request = {a.id, a.text, b.id, b.text};
                final String note = NOTE_SUBJECT_PAIR + a.subject + " / " + b.subject;
                final double similarity = similarities.get(i);
                Comparison earlier = verdicts.get(Comparison.key(Comparison.Kind.GEGENSTANDSPAAR, a.text, b.text));
                Comparison turned = verdicts.get(Comparison.key(Comparison.Kind.GEGENSTANDSPAAR, b.text, a.text));
                if (earlier != null || turned != null) {
                    Comparison found = earlier != null ? earlier : turned;
                    out[i] = new Comparison(Comparison.Kind.GEGENSTANDSPAAR, request[0], request[1], request[2],
                            request[3], false, similarity, Comparison.Status.OK, found.getRelation(),
                            found.getReason(), "", found.getAnswers(), found.getModel(), found.getTimeMillis(),
                            found.getMillis(), found.getVersion(), note);
                    out[i] = earlier != null && note.equals(earlier.getNote()) ? earlier : out[i];
                    used.add(out[i]);
                    continue;
                }
                open.add(i);
                calls.add(new Case() {
                    @Override
                    public Comparison ask() {
                        return askSubject(Comparison.Kind.GEGENSTANDSPAAR, request, note, similarity);
                    }

                    @Override
                    public Comparison notAsked(String why) {
                        return new Comparison(Comparison.Kind.GEGENSTANDSPAAR, request[0], request[1], request[2],
                                request[3], false, similarity, Comparison.Status.FEHLER, null, "", why, null,
                                chat.modelName(), clock.millis(), 0L, versionOf(Comparison.Kind.GEGENSTANDSPAAR),
                                note);
                    }
                });
            }
            List<Comparison> results = run(calls, recorder);
            for (int k = 0; k < open.size(); k++) {
                out[open.get(k)] = results.get(k);
                used.add(results.get(k));
                asked.add(results.get(k));
            }
            return Arrays.asList(out);
        }

        /**
         * A new parent over families of one place with the same subject ({@link #subjects()}), or null when none is
         * formed. Its name is the subject most of them name, then that of the family with the most proposals, then
         * that of the first; a name must be free as for every new parent (the review of fcca35cf): a head of the
         * families worded like it becomes the parent itself, with the others below it; a name worded like another
         * proposal or a known topic, or like a new parent standing at the same place, is passed over for the next
         * subject the families name, and when none is free no parent is formed ({@code subjectNameTaken}).
         */
        private Parent roof(int place, List<Family> members, Comparison verdict) {
            final Map<String, Integer> votes = new HashMap<String, Integer>();
            final Map<String, Integer> largest = new HashMap<String, Integer>();
            final Map<String, String> wordingOf = new LinkedHashMap<String, String>();
            for (Family member : members) {
                String key = wording(member.subject);
                add(votes, key, 1);
                int proposals = proposalsBelow(member.root);
                if (!largest.containsKey(key) || proposals > largest.get(key)) {
                    largest.put(key, proposals);
                }
                if (!wordingOf.containsKey(key)) {
                    wordingOf.put(key, member.subject);
                }
            }
            List<String> candidates = new ArrayList<String>(wordingOf.keySet());
            final List<String> firstSeen = new ArrayList<String>(candidates);
            Collections.sort(candidates, (x, y) -> !votes.get(x).equals(votes.get(y))
                    ? Integer.compare(votes.get(y), votes.get(x))
                    : !largest.get(x).equals(largest.get(y)) ? Integer.compare(largest.get(y), largest.get(x))
                    : Integer.compare(firstSeen.indexOf(x), firstSeen.indexOf(y)));
            List<String> names = new ArrayList<String>();
            for (Family member : members) {
                names.add(member.name);
            }
            String name = null;
            Family headRoof = null;
            for (String key : candidates) {
                for (Family member : members) {
                    if (member.parent == null && headRoof == null && name == null && holds(member.root, key)) {
                        headRoof = member;
                        name = pool.items.get(central(itemsOf.get(member.root), pool.items)).name;
                    }
                }
                if (name == null && !taken.contains(key) && !standsNamed(key, place, members)) {
                    name = wordingOf.get(key);
                }
                if (name != null) {
                    break;
                }
            }
            if (name == null) {
                add(counts, "subjectNameTaken", members.size());
                log("topic merge: no new parent over " + String.join(", ", names) + ": every subject they name ("
                        + String.join(", ", wordingOf.values()) + ") is the name of another topic");
                return null;
            }
            int level = place < 0 ? 0 : unions.depth(place) + 1;
            if (level + 1 > options.getDepth()) {
                add(counts, "subjectTooDeep", members.size());
                log("topic merge: no new parent \u201e" + name + "\u201c over " + String.join(", ", names) + ": its "
                        + "parts would reach deeper than " + options.getDepth() + " levels");
                return null;
            }
            String joinedNames = String.join("; ", names);
            String description = (german ? "Gemeinsamer Gegenstand von: " : "Common subject of: ") + joinedNames;
            description = description.length() <= PARENT_DESCRIPTION_CHARS ? description
                    : description.substring(0, PARENT_DESCRIPTION_CHARS).trim() + " …";
            int node;
            List<Integer> parts = new ArrayList<Integer>();
            Parent roof;
            if (headRoof != null) {
                node = headRoof.root;
                roof = null;
                for (Parent parent : parents) {
                    if (parent.head == node) {
                        roof = parent;
                    }
                }
                if (roof == null) {
                    roof = new Parent(-1, node, name, description, parts, verdict);
                    parents.add(roof);
                    for (int child : childrenOf(node)) {
                        parts.add(child);
                    }
                }
                parts = roof.parts;
                add(counts, "subjectHeadRoofs", 1);
            } else {
                node = unions.addNode();
                if (place >= 0) {
                    unions.parentOf.put(node, place);
                }
                roof = new Parent(node, -1, name, description, parts, verdict);
            }
            for (Family member : members) {
                if (member == headRoof) {
                    continue;
                }
                if (member.parent != null) {
                    roof.seen.addAll(member.parent.seen);
                    boolean alike = wording(member.parent.name).equals(wording(name));
                    if (place < 0 && !alike && level + 1 + unions.height(member.root) <= options.getDepth()) {
                        unions.parentOf.put(member.root, node);
                        parts.add(member.root);
                        add(counts, "subjectKept", 1);
                    } else {
                        for (int child : childrenOf(member.root)) {
                            unions.parentOf.put(child, node);
                            parts.add(child);
                        }
                        member.parent.parts.clear();
                        member.parent.merged = true;
                        dropPart(member.parent);
                        add(counts, "subjectFlat", 1);
                    }
                    continue;
                }
                roof.seen.add(member.root);
                if (level + 1 + unions.height(member.root) > options.getDepth()) {
                    for (int child : childrenOf(member.root)) {
                        unions.parentOf.put(child, node);
                        parts.add(child);
                    }
                    for (Parent parent : parents) {
                        if (parent.head == member.root) {
                            parent.parts.clear();
                            parent.merged = true;
                        }
                    }
                    add(counts, "subjectFlat", 1);
                }
                unions.parentOf.put(member.root, node);
                parts.add(member.root);
            }
            if (headRoof == null) {
                parents.add(roof);
                formed.put(wording(name), roof);
            }
            int proposals = proposalsBelow(node);
            add(counts, "subjectRoofs", 1);
            add(counts, "subjectMerged", members.size());
            counts.put("subjectLargest", Math.max(counts.get("subjectLargest"), proposals));
            counts.put("subjectLargestParts", Math.max(counts.get("subjectLargestParts"), parts.size()));
            subjectFindings.add(new SubjectFinding(SubjectFinding.ROOF, place < 0 ? "" : cardName(place), name,
                    names, parts.size(), proposals));
            log("topic merge: the families " + joinedNames + " go under " + (headRoof != null ? "the head " : "the new "
                    + "parent ") + "\u201e" + name + "\u201c (" + (place < 0 ? "at the top" : "under the card \u201e"
                    + cardName(place) + "\u201c") + ", " + parts.size() + " parts, " + proposals + " proposals)");
            return roof;
        }

        /** Whether one of a head's own proposals is worded so. */
        private boolean holds(int head, String key) {
            for (int item : itemsOf.get(head)) {
                if (pool.items.get(item).key.equals(key)) {
                    return true;
                }
            }
            return false;
        }

        /** Whether a new parent that is none of the families stands at the place under that wording. */
        private boolean standsNamed(String key, int place, List<Family> members) {
            for (Parent parent : parents) {
                if (isStanding(parent) && place(parent) == place && wording(parent.name).equals(key)) {
                    boolean member = false;
                    for (Family family : members) {
                        member |= family.parent == parent;
                    }
                    if (!member) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** Where a new parent topic stands: the topic above it, or -1 at the top. */
        private int place(Parent parent) {
            Integer up = unions.parentOf.get(parent.node);
            return up == null ? -1 : unions.find(up);
        }
    }

    /**
     * The cases of a pass among some heads: their groups at {@link Options#getParentSimilarity()} (average linkage)
     * with at least {@value #PARENT_MIN} heads. A group larger than {@value #PARENT_MAX} falls apart along its tree
     * into pieces of at most {@value #PARENT_MAX}; each head of a piece smaller than {@value #PARENT_MIN} joins the
     * piece of its group nearest to it that has room. The heads that find no room are a case of their own when there
     * are at least {@value #PARENT_MIN} of them, else each joins the nearest piece all the same (a case then has up to
     * two heads more). So no head of a group is lost on the way (in the architect's run c15e the old split at a higher
     * similarity dropped every piece of fewer than three heads). Counted under {@code pass}: the heads in a case, alone
     * (in no group of {@value #PARENT_MIN}), reattached and lost.
     */
    private List<ParentCase> parentCases(Pool pool, final Map<Integer, List<Integer>> itemsOf, List<Integer> heads,
                                         int under, String underName, Map<String, Integer> counts, String pass) {
        List<ParentCase> cases = new ArrayList<ParentCase>();
        List<Integer> ordered = new ArrayList<Integer>(heads);
        Collections.sort(ordered, (x, y) -> Integer.compare(itemsOf.get(x).get(0), itemsOf.get(y).get(0)));
        List<Node> standIns = new ArrayList<Node>();
        for (int head : ordered) {
            Node standIn = Node.item(Integer.toString(head), "", "", "");
            standIn.vectors.add(centroidOf(pool, itemsOf.get(head)));
            standIns.add(standIn);
        }
        for (List<List<Integer>> group : pieces(standIns, options.getParentSimilarity(), PARENT_MAX)) {
            int size = 0;
            for (List<Integer> piece : group) {
                size += piece.size();
            }
            if (size < PARENT_MIN) {
                add(counts, pass + "Alone", size);
                continue;
            }
            List<List<Integer>> kept = new ArrayList<List<Integer>>();
            List<Integer> small = new ArrayList<Integer>();
            for (List<Integer> piece : group) {
                if (piece.size() >= PARENT_MIN) {
                    kept.add(new ArrayList<Integer>(piece));
                } else {
                    small.addAll(piece);
                }
            }
            List<float[]> centres = new ArrayList<float[]>();
            for (List<Integer> piece : kept) {
                centres.add(centre(standIns, piece));
            }
            List<Integer> left = new ArrayList<Integer>();
            for (int i : small) {
                int best = nearestPiece(standIns.get(i).vectors.get(0), kept, centres, PARENT_MAX);
                if (best < 0) {
                    left.add(i);
                } else {
                    kept.get(best).add(i);
                    add(counts, pass + "Reattached", 1);
                }
            }
            if (left.size() >= PARENT_MIN || kept.isEmpty()) {
                for (List<Integer> chunk : chunks(left, PARENT_MAX)) {
                    if (chunk.size() >= PARENT_MIN) {
                        kept.add(chunk);
                    } else {
                        add(counts, pass + "Lost", chunk.size());
                    }
                }
            } else {
                for (int i : left) {
                    kept.get(nearestPiece(standIns.get(i).vectors.get(0), kept, centres, Integer.MAX_VALUE)).add(i);
                    add(counts, pass + "Reattached", 1);
                }
            }
            for (List<Integer> piece : kept) {
                Collections.sort(piece);
                List<Integer> members = new ArrayList<Integer>();
                for (int i : piece) {
                    members.add(ordered.get(i));
                }
                cases.add(new ParentCase(members, options.getParentSimilarity(), under, underName));
                add(counts, pass + "InCases", members.size());
            }
        }
        Collections.sort(cases, (x, y) -> Integer.compare(itemsOf.get(x.heads.get(0)).get(0),
                itemsOf.get(y.heads.get(0)).get(0)));
        return cases;
    }

    /**
     * The groups at or above the similarity (average linkage, as {@link #clusters}), each as its pieces along the
     * tree of at most {@code max} members: a node of the tree larger than that falls apart into the two it was joined
     * from. Groups in the order of their first member, members as indexes in input order.
     */
    private static List<List<List<Integer>>> pieces(List<Node> items, double same, final int max) {
        List<List<List<Integer>>> out = new ArrayList<List<List<Integer>>>();
        if (items.size() < 2) {
            for (int i = 0; i < items.size(); i++) {
                out.add(Collections.singletonList(Collections.singletonList(i)));
            }
            return out;
        }
        double cut = 1.0 - same + 1e-9;
        TopicDendrogram dendrogram = clusterTree(items, cut).getDendrogram();
        Map<String, Integer> groupOf = new HashMap<String, Integer>();
        List<List<String>> plain = dendrogram.cut(cut);
        for (int g = 0; g < plain.size(); g++) {
            out.add(new ArrayList<List<Integer>>());
            for (String id : plain.get(g)) {
                groupOf.put(id, g);
            }
        }
        for (List<String> piece : dendrogram.cut(cut, (tree, members, height) -> members.size() > max)) {
            List<Integer> indexes = new ArrayList<Integer>();
            for (String id : piece) {
                indexes.add(Integer.parseInt(id));
            }
            out.get(groupOf.get(piece.get(0))).add(indexes);
        }
        return out;
    }

    /** The piece whose centre is nearest to a vector among those with fewer than {@code limit} heads; -1 if none. */
    private static int nearestPiece(float[] vector, List<List<Integer>> pieces, List<float[]> centres, int limit) {
        int best = -1;
        double bestSimilarity = -2;
        for (int k = 0; k < pieces.size(); k++) {
            if (pieces.get(k).size() >= limit) {
                continue;
            }
            double similarity = dot(vector, centres.get(k));
            if (similarity > bestSimilarity + 1e-9) {
                bestSimilarity = similarity;
                best = k;
            }
        }
        return best;
    }

    /** The mean unit vector of some stand-ins. */
    private static float[] centre(List<Node> standIns, List<Integer> members) {
        List<float[]> vectors = new ArrayList<float[]>();
        for (int i : members) {
            vectors.add(standIns.get(i).vectors.get(0));
        }
        return unit(mean(vectors));
    }

    /** A list in consecutive chunks of at most {@code max}, as even as can be. */
    static List<List<Integer>> chunks(List<Integer> list, int max) {
        List<List<Integer>> out = new ArrayList<List<Integer>>();
        int count = (list.size() + max - 1) / max;
        int from = 0;
        for (int c = 0; c < count; c++) {
            int to = from + (list.size() - from) / (count - c);
            out.add(new ArrayList<Integer>(list.subList(from, to)));
            from = to;
        }
        return out;
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
        next.lines = text.toString();
        next.aText = next.under < 0 ? next.lines
                : (german ? "Unter: " : "Under: ") + next.underName + "\n" + next.lines;
    }

    /** One case of the parent step: at most {@link #ATTEMPTS} calls. */
    Comparison askParent(ParentCase next, Set<String> taken) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? PARENT_SYSTEM_DE : PARENT_SYSTEM_EN));
        messages.add(ChatMessage.user(parentPrompt(mission, next.lines, next.under < 0 ? "" : next.underName,
                german)));
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
     * One case of the subject step ({id A, text A, id B, text B}): the subject of family A (kind
     * {@link Comparison.Kind#GEGENSTAND}, B the mark) or two families (kind {@link Comparison.Kind#GEGENSTANDSPAAR}),
     * at most {@link #ATTEMPTS} calls. The note of a pair is the given one; that of a subject names the subject.
     */
    Comparison askSubject(Comparison.Kind kind, String[] request, String note, double similarity) {
        boolean naming = kind == Comparison.Kind.GEGENSTAND;
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(german ? SUBJECT_SYSTEM_DE : SUBJECT_SYSTEM_EN));
        messages.add(ChatMessage.user(naming ? subjectPrompt(mission, request[1], german)
                : subjectPairPrompt(mission, request[1], request[3], german)));
        String schema = naming ? subjectSchema() : subjectPairSchema();
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
            Verdict verdict = naming ? parseSubject(text) : parseSubjectPair(text);
            if (verdict.problem.isEmpty()) {
                return new Comparison(kind, request[0], request[1], request[2], request[3], false, similarity,
                        Comparison.Status.OK, verdict.relation, verdict.reason, "", answers, chat.modelName(),
                        clock.millis(), clock.millis() - start, versionOf(kind), naming ? verdict.note : note);
            }
            problem = "invalid answer: " + verdict.problem;
            messages.add(ChatMessage.assistant(text));
            messages.add(ChatMessage.user((german ? "Deine Antwort war ungültig: " : "Your answer was invalid: ")
                    + verdict.problem + (german ? "\nAntworte noch einmal, nur mit dem JSON-Objekt."
                    : "\nAnswer again with the JSON object only.")));
        }
        log("subject step failed for " + request[0] + (naming ? "" : " / " + request[2]) + ": " + problem);
        return new Comparison(kind, request[0], request[1], request[2], request[3], false, similarity,
                Comparison.Status.FEHLER, null, "", problem, answers, chat.modelName(), clock.millis(),
                clock.millis() - start, versionOf(kind), naming ? "" : note);
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
    static final String SUBJECT_SYSTEM_DE = "Du ordnest die Themen eines Sachbuchs nach ihrem Gegenstand. Antworte nur "
            + "mit JSON.";
    static final String SUBJECT_SYSTEM_EN = "You order the topics of a non-fiction book by their subject. Answer only "
            + "with JSON.";

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
        /** The subject a case of the subject step named, {@code ""} for none. */
        String subject = "";
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
        return parentPrompt(mission, topics, "", german);
    }

    /**
     * The prompt of a parent case; {@code under} names the topic the heads stand under as loose subtopics, empty for
     * heads at the top: then the parent must be narrower than that topic.
     */
    static String parentPrompt(String mission, String topics, String under, boolean german) {
        String task = mission.length() <= MISSION_CHARS ? mission : mission.substring(0, MISSION_CHARS).trim() + " …";
        String place = under.length() <= PARENT_NAME_CHARS ? under.trim()
                : under.substring(0, PARENT_NAME_CHARS).trim() + " …";
        boolean top = place.isEmpty();
        if (german) {
            String quoted = "\u201e" + place + "\u201c";
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + (top ? "Themen aus der Recherche (jedes steht bisher für sich, unter keinem anderen Thema):\n"
                    : "Themen aus der Recherche, alle schon unter dem Thema " + quoted + " (jedes steht dort bisher "
                    + "für sich):\n")
                    + topics + "\n\n"
                    + "Gehören mindestens drei dieser Themen zu einem gemeinsamen Oberthema"
                    + (top ? "" : " innerhalb von " + quoted) + ", das im Buch einen eigenen Abschnitt tragen könnte?\n"
                    + "- Wenn ja: Gib dem Oberthema einen kurzen Namen und sage in einem Satz, worum es geht. "
                    + "Nenne die Themen, die ganz darunter fallen; Themen, die es nur berühren, lässt du weg.\n"
                    + "- Ist eines der Themen selbst schon dieses Oberthema, nennst du seine Kennung als Oberthema und "
                    + "führst es nicht unter den Themen auf.\n"
                    + (top ? "" : "- " + quoted + " selbst ist hier kein Oberthema: Das Oberthema ist enger als "
                    + quoted + " und fasst nur einen Teil seiner Themen zusammen.\n")
                    + "- Wenn nein, oder wenn das gemeinsame Thema nur das Sachgebiet des ganzen Buchs oder ein sehr "
                    + "breites Feld wäre: Antworte \"keins\".\n"
                    + "Im Zweifel \"keins\". Ob ein Thema ins Buch gehört, entscheidet ein anderer Schritt; hier geht "
                    + "es nur darum, welche Themen zusammengehören.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz), \"oberthema\" (der Name des "
                    + "Oberthemas, die Kennung eines der Themen oder keins), \"beschreibung\" (ein Satz, bei keins "
                    + "leer) und \"themen\" (die Kennungen der Themen, die darunter fallen, bei keins eine leere "
                    + "Liste).";
        }
        String quoted = "\u201c" + place + "\u201d";
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + (top ? "Topics from the research (each stands on its own so far, under no other topic):\n"
                : "Topics from the research, all already under the topic " + quoted + " (each stands on its own there "
                + "so far):\n")
                + topics + "\n\n"
                + "Do at least three of these topics belong to a common parent topic" + (top ? "" : " within " + quoted)
                + " that could carry a section of its own in the book?\n"
                + "- If so: give the parent topic a short name and say in one sentence what it is about. Name the "
                + "topics that fall wholly under it; leave out topics that only touch it.\n"
                + "- If one of the topics already is this parent topic, name its id as the parent topic and do not "
                + "list it among the topics.\n"
                + (top ? "" : "- " + quoted + " itself is no parent topic here: the parent topic is narrower than "
                + quoted + " and gathers only part of its topics.\n")
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

    /** The prompt that names the subject of a family: its name and some of its parts. */
    static String subjectPrompt(String mission, String family, boolean german) {
        String task = mission.length() <= MISSION_CHARS ? mission : mission.substring(0, MISSION_CHARS).trim() + " …";
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Eine Themenfamilie aus der Recherche:\n" + family + "\n\n"
                    + "Welchen Gegenstand behandelt diese Familie? Gemeint ist die Sache selbst (etwa ein Gerät, eine "
                    + "Technik, ein Verfahren, eine Regel, eine Gruppe von Menschen), nicht der Blickwinkel, aus dem "
                    + "die Familie sie betrachtet.\n"
                    + "- Leite den Gegenstand aus den Teilthemen ab, nicht nur aus dem Namen der Familie.\n"
                    + "- Nenne ihn in einem bis vier Wörtern, ohne den Blickwinkel: nicht Definition, Grundlagen, "
                    + "Einsatz, Anwendung, Recht, Strategie, Vorteile, Herausforderungen oder Zukunft.\n"
                    + "- Nenne nicht das Sachgebiet des ganzen Buchs, sondern das, was diese Familie von den anderen "
                    + "Familien des Buchs unterscheidet.\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz, zuerst) und \"gegenstand\" (ein "
                    + "bis vier Wörter).";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "A family of topics from the research:\n" + family + "\n\n"
                + "What subject does this family treat? This means the thing itself (a device, a technique, a "
                + "procedure, a rule, a group of people, for instance), not the angle from which the family looks at "
                + "it.\n"
                + "- Derive the subject from the parts, not only from the family's name.\n"
                + "- Name it in one to four words, without the angle: not definition, basics, use, application, law, "
                + "strategy, benefits, challenges or future.\n"
                + "- Do not name the field of the whole book, but what sets this family apart from the book's other "
                + "families.\n\n"
                + "Answer only with JSON with the fields \"grund\" (one sentence, first) and \"gegenstand\" (one to "
                + "four words).";
    }

    /** The prompt of two families of one place: the same subject, also from different angles? */
    static String subjectPairPrompt(String mission, String a, String b, boolean german) {
        String task = mission.length() <= MISSION_CHARS ? mission : mission.substring(0, MISSION_CHARS).trim() + " …";
        if (german) {
            return "Buchauftrag:\n" + (task.isEmpty() ? "(keiner angegeben)" : task) + "\n\n"
                    + "Familie A:\n" + a + "\n\n"
                    + "Familie B:\n" + b + "\n\n"
                    + "Behandeln A und B denselben Gegenstand, auch wenn aus verschiedenen Blickwinkeln (etwa die "
                    + "eine seine Grundlagen, die andere sein Recht oder seinen Einsatz)?\n"
                    + "- \"ja\": Beide handeln von derselben Sache; im Buch gehörten sie unter ein gemeinsames Dach "
                    + "mit dem Namen dieser Sache.\n"
                    + "- \"nein\": Sie handeln von verschiedenen Sachen, auch wenn sie im selben Sachgebiet liegen, "
                    + "dieselben Wörter benutzen oder beide zum Sachgebiet des ganzen Buchs gehören.\n"
                    + "Im Zweifel \"nein\".\n\n"
                    + "Antworte nur mit JSON mit den Feldern \"grund\" (ein Satz, zuerst) und \"antwort\" (\"ja\" "
                    + "oder \"nein\").";
        }
        return "Book mission:\n" + (task.isEmpty() ? "(none given)" : task) + "\n\n"
                + "Family A:\n" + a + "\n\n"
                + "Family B:\n" + b + "\n\n"
                + "Do A and B treat the same subject, even if from different angles (one its basics, the other its "
                + "law or its use, for instance)?\n"
                + "- \"ja\": both are about the same thing; in the book they would belong under one roof named after "
                + "that thing.\n"
                + "- \"nein\": they are about different things, even if they lie in the same field, use the same "
                + "words or both belong to the field of the whole book.\n"
                + "When in doubt, \"nein\".\n\n"
                + "Answer only with JSON with the fields \"grund\" (one sentence, first) and \"antwort\" (\"ja\" or "
                + "\"nein\").";
    }

    /** {@code grund} first, then {@code gegenstand}. */
    static String subjectSchema() {
        return reasonFirst("gegenstand", null);
    }

    /** {@code grund} first, then {@code antwort}: ja or nein. */
    static String subjectPairSchema() {
        JsonArray words = new JsonArray();
        words.add("ja");
        words.add("nein");
        return reasonFirst("antwort", words);
    }

    /** An object of {@code grund} and one more text field, from a list of words when one is given. */
    private static String reasonFirst(String field, JsonArray words) {
        JsonObject reason = new JsonObject();
        reason.addProperty("type", "string");
        JsonObject value = new JsonObject();
        value.addProperty("type", "string");
        if (words != null) {
            value.add("enum", words);
        }
        JsonObject properties = new JsonObject();
        properties.add("grund", reason);
        properties.add(field, value);
        JsonArray required = new JsonArray();
        required.add("grund");
        required.add(field);
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        schema.add("required", required);
        schema.addProperty("additionalProperties", false);
        return schema.toString();
    }

    /**
     * The subject of a family: one to {@value #SUBJECT_WORDS} words of at most {@value #SUBJECT_CHARS} characters, in
     * quotes or not, with no full stop at the end; no word for none.
     */
    static Verdict parseSubject(String answer) {
        Verdict verdict = new Verdict();
        JsonObject json = object(answer, verdict);
        if (json == null) {
            return verdict;
        }
        JsonElement word = json.get("gegenstand");
        String subject = word != null && word.isJsonPrimitive() ? word.getAsString() : "";
        subject = subject.replaceAll("\\s+", " ").trim().replaceAll("^[\"'\u201e\u201c\u201d\u00bb\u00ab]+", "")
                .replaceAll("[\"'\u201e\u201c\u201d\u00bb\u00ab.:;,!?]+$", "").trim();
        String key = wording(subject);
        if (key.isEmpty() || NO_PARENT.equals(key) || "keiner".equals(key) || "keine".equals(key)
                || "none".equals(key)) {
            verdict.problem = "\"gegenstand\" must name the subject of the family";
            return verdict;
        }
        if (subject.split(" ").length > SUBJECT_WORDS || subject.length() > SUBJECT_CHARS) {
            verdict.problem = "\"gegenstand\" must have one to " + SUBJECT_WORDS + " words and at most " + SUBJECT_CHARS
                    + " characters";
            return verdict;
        }
        verdict.relation = Relation.A_TEIL_VON_B;
        verdict.subject = subject;
        verdict.reason = reasonOf(json);
        verdict.note = NOTE_SUBJECT + subject;
        return verdict;
    }

    /** Two families of one place: ja (the same subject, {@link Relation#GLEICH}) or nein. */
    static Verdict parseSubjectPair(String answer) {
        Verdict verdict = new Verdict();
        JsonObject json = object(answer, verdict);
        if (json == null) {
            return verdict;
        }
        JsonElement word = json.get("antwort");
        String said = word != null && word.isJsonPrimitive() ? word.getAsString().trim().toLowerCase(Locale.ROOT)
                : "";
        if ("ja".equals(said)) {
            verdict.relation = Relation.GLEICH;
        } else if ("nein".equals(said)) {
            verdict.relation = Relation.VERSCHIEDEN;
        } else {
            verdict.problem = "\"antwort\" must be ja or nein";
            return verdict;
        }
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

    /** The same case with the code's note. */
    private static Comparison noted(Comparison c, String note) {
        return new Comparison(c.getKind(), c.getAId(), c.getAText(), c.getBId(), c.getBText(), c.isBKnown(),
                c.getSimilarity(), c.getStatus(), c.getRelation(), c.getReason(), c.getProblem(), c.getAnswers(),
                c.getModel(), c.getTimeMillis(), c.getMillis(), c.getVersion(), note);
    }

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
