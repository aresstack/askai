package com.aresstack.askai.research.bookrun;

import com.aresstack.askai.research.domain.IdSequence;
import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.domain.TopicProposal;
import com.aresstack.askai.research.domain.basket.BasketReader;
import com.aresstack.askai.research.domain.basket.SourceBasket;
import com.aresstack.askai.research.domain.scope.ResearchFence;
import com.aresstack.askai.research.evidence.EvidenceBundleJudge;
import com.aresstack.askai.research.evidence.EvidenceJudge;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.TopicClusterer;
import com.aresstack.askai.research.knowledge.VectorMath;
import com.aresstack.askai.research.memory.EvidenceVerdict;
import com.aresstack.askai.research.memory.QuestionEvidence;
import com.aresstack.askai.research.memory.ResearchMemory;
import com.aresstack.askai.research.memory.ResearchQuestion;
import com.aresstack.askai.research.memory.ResearchTopic;
import com.aresstack.askai.research.missionreport.BookSection;
import com.aresstack.askai.research.missionreport.MissionCheck;
import com.aresstack.askai.research.missionreport.MissionReport;
import com.aresstack.askai.research.planwriter.PassageLookup;
import com.aresstack.askai.research.planwriter.PlanSectionWriter;
import com.aresstack.askai.research.planwriter.WrittenSection;
import com.aresstack.askai.research.planwriter.WrittenSections;
import com.aresstack.askai.research.runtime.sourcerating.KnownDomains;
import com.aresstack.askai.research.runtime.sourcerating.SourceRater;
import com.aresstack.askai.research.runtime.sourcerating.SourceRating;
import com.aresstack.askai.research.runtime.sourcerating.SourceSample;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.aresstack.askai.research.runtime.team.ModelCallMetrics;
import com.aresstack.askai.research.sectionplan.SectionPlan;
import com.aresstack.askai.research.sectionplan.SectionPlanner;
import com.aresstack.askai.research.sentencecheck.SentenceVerdicts;

import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Phase 4, from the basket to the book (Angelo 27.11.2026; later Writing4j). The input is the book idea (mission and
 * Weidezaun, {@link ResearchFence}), the basket ({@link SourceBasket}, references with a {@link BasketReader}) and the
 * writing settings; nothing else. The concept of the research is not an input: mission and fence are the only measure.
 * <ol>
 * <li>{@link #open}, opening up the basket: a profile per source against the fence ({@link SourceRater}); the
 * passages of a source outside the fence and of the parts of a mixed source outside it (by the profile's headings)
 * are set aside with their reason, mixed and unclear sources stay; the clustering of the remaining passages
 * ({@link TopicClusterer}); questions from two sides, those the mission demands (Pflichtfragen) and per group those
 * its passages answer (Befundfragen), then every question checked against mission and fence
 * ({@link StockQuestions}). Questions outside the fence stay in the question list with their reason and are not
 * pursued. A demanded question goes to the group whose passages lie nearest to it.</li>
 * <li>{@link #write}: per question one evidence check ({@link EvidenceBundleJudge}, applicability against the
 * fence) over the passages of its group, for a demanded question over the passages nearest to it, as many as one call
 * of the check holds (the vectors only choose the candidates, the context window how many); the groups as topics, their questions and the verdicts in the research memory;
 * the section plan from questions and evidence ({@link SectionPlanner}); the sections in order with the writing
 * memory and the bundled sentence check ({@link PlanSectionWriter}); the mission report ({@link MissionCheck}) and
 * per question where it is answered or why it stays open; a demanded question without evidence in the basket is a
 * gap for a further search.</li>
 * </ol>
 * Every model call is counted per step: calls, failures, tokens, time and model switches.
 */
public final class BookRun {

    /** A passage of the basket with its source. */
    public static final class BasketPassage {
        private final String passageId;
        private final SourceBasket.SourceRef source;
        private final String domain;
        private final String heading;
        private final String text;

        public BasketPassage(String passageId, SourceBasket.SourceRef source, String domain, String heading,
                             String text) {
            this.passageId = passageId;
            this.source = source;
            this.domain = domain == null || domain.isEmpty() ? SourceSample.hostOf(source.getUrl()) : domain;
            this.heading = heading == null ? "" : heading;
            this.text = text == null ? "" : text;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return source.getSourceId();
        }

        public String getHeading() {
            return heading;
        }

        public String getText() {
            return text;
        }
    }

    /** The writing settings; every value is a setting of the component it goes to. */
    public static final class Settings {
        private final String language;
        private final int targetWords;
        private final double groupDistance;
        private final SourceRater.Options rater;
        private final StockQuestions.Options questions;
        private final EvidenceBundleJudge.Options judge;
        private final PlanSectionWriter.Options writer;

        /**
         * @param groupDistance passages joined at or below this cosine distance form one group (the setting of the
         *                      topic analysis, {@link TopicClusterer#DEFAULT_TOPIC_DISTANCE})
         */
        public Settings(String language, int targetWords, double groupDistance, SourceRater.Options rater,
                        StockQuestions.Options questions, EvidenceBundleJudge.Options judge,
                        PlanSectionWriter.Options writer) {
            this.language = language == null || language.trim().isEmpty() ? "de" : language.trim();
            this.targetWords = targetWords;
            this.groupDistance = groupDistance > 0 ? groupDistance : TopicClusterer.DEFAULT_TOPIC_DISTANCE;
            this.rater = rater == null ? SourceRater.Options.defaults() : rater;
            this.questions = questions == null ? StockQuestions.Options.defaults() : questions;
            this.judge = judge == null ? EvidenceBundleJudge.Options.defaults() : judge;
            this.writer = (writer == null ? PlanSectionWriter.Options.defaults().withLanguage(this.language) : writer)
                    .withParallel(1).withCheckBundled(true).withAttributionTail(false);
        }

        public static Settings of(String language, int targetWords) {
            return new Settings(language, targetWords, 0, null, null, null, null);
        }
    }

    /** What one step's model calls came to. */
    public static final class Step {
        private final String name;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        final AtomicLong promptTokens = new AtomicLong();
        final AtomicLong answerTokens = new AtomicLong();
        final AtomicLong modelMillis = new AtomicLong();
        long millis;

        Step(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public int getCalls() {
            return calls.get();
        }

        public int getFailures() {
            return failures.get();
        }

        public long getPromptTokens() {
            return promptTokens.get();
        }

        public long getAnswerTokens() {
            return answerTokens.get();
        }

        public long getMillis() {
            return millis;
        }
    }

    /** A group of the clustering with its passages and the questions drafted from it. */
    public static final class Group {
        private final String id;
        private final String title;
        private final List<String> passageIds;
        private final String problem;

        Group(String id, String title, List<String> passageIds, String problem) {
            this.id = id;
            this.title = title;
            this.passageIds = Collections.unmodifiableList(new ArrayList<String>(passageIds));
            this.problem = problem;
        }

        public String getId() {
            return id;
        }

        public String getTitle() {
            return title;
        }

        public List<String> getPassageIds() {
            return passageIds;
        }

        /** Why (a part of) the group got no questions; {@code ""} when the drafting succeeded. */
        public String getProblem() {
            return problem;
        }
    }

    /** A question of the book, with its side and its verdict against mission and fence. */
    public static final class Question {
        /** Where it comes from: the mission demands it, or the passages of a group answer it. */
        public enum Origin { PFLICHT, BEFUND }

        private final String id;
        private final String text;
        private final Origin origin;
        private final String groupId;
        private final StockQuestions.Verdict verdict;
        /** The vector of a demanded question, to rank its candidates; {@code null} for a question of a group. */
        EmbeddingPort.EmbeddingVector vector;

        Question(String id, String text, Origin origin, String groupId, StockQuestions.Verdict verdict) {
            this.id = id;
            this.text = text;
            this.origin = origin;
            this.groupId = groupId;
            this.verdict = verdict;
        }

        public Origin getOrigin() {
            return origin;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        /** The group whose passages are its candidates; {@code ""} when the basket has none. */
        public String getGroupId() {
            return groupId;
        }

        public StockQuestions.Verdict getVerdict() {
            return verdict;
        }

        /** Pursued: inside the fence, unclear (the evidence check decides per passage) or not checked. */
        public boolean isPursued() {
            return verdict.getRelation() != StockQuestions.Relation.AUSSEN;
        }
    }

    /** The basket opened up: profiles, groups and questions. */
    public static final class Opening {
        private final ResearchFence fence;
        private final List<BasketPassage> passages;
        private final Map<String, String> setAside;
        private final Map<String, EmbeddingPort.EmbeddingVector> vectors;
        private final Map<String, SourceRating> profiles;
        private final List<Group> groups;
        private final List<Question> questions;
        private final List<Step> steps;
        private final Switches switches;

        Opening(ResearchFence fence, List<BasketPassage> passages, Map<String, String> setAside,
                Map<String, EmbeddingPort.EmbeddingVector> vectors, Map<String, SourceRating> profiles, List<Group> groups, List<Question> questions, List<Step> steps,
                Switches switches) {
            this.fence = fence;
            this.passages = Collections.unmodifiableList(new ArrayList<BasketPassage>(passages));
            this.setAside = Collections.unmodifiableMap(new LinkedHashMap<String, String>(setAside));
            this.vectors = vectors;
            this.profiles = Collections.unmodifiableMap(new LinkedHashMap<String, SourceRating>(profiles));
            this.groups = Collections.unmodifiableList(new ArrayList<Group>(groups));
            this.questions = Collections.unmodifiableList(new ArrayList<Question>(questions));
            this.steps = new ArrayList<Step>(steps);
            this.switches = switches;
        }

        public List<BasketPassage> getPassages() {
            return passages;
        }

        /** Passages outside the fence by their source's profile, not clustered: the reason by passage id. */
        public Map<String, String> getSetAside() {
            return setAside;
        }

        public Map<String, SourceRating> getProfiles() {
            return profiles;
        }

        public List<Group> getGroups() {
            return groups;
        }

        public List<Question> getQuestions() {
            return questions;
        }

        public List<Step> getSteps() {
            return Collections.unmodifiableList(steps);
        }

        public int getModelSwitches() {
            return switches.count;
        }
    }

    /** Where a question stands at the end. */
    public static final class QuestionLine {
        /** NACHRECHERCHE: a question the mission demands without direct evidence in the basket. */
        public enum State { BEANTWORTET, NUR_HINWEISE, OFFEN, NACHRECHERCHE, AUSSERHALB }

        private final Question question;
        private final State state;
        private final String section;
        private final String reason;
        private final int direct;
        private final int leads;

        QuestionLine(Question question, State state, String section, String reason, int direct, int leads) {
            this.question = question;
            this.state = state;
            this.section = section;
            this.reason = reason;
            this.direct = direct;
            this.leads = leads;
        }

        public Question getQuestion() {
            return question;
        }

        public State getState() {
            return state;
        }

        /** The title of the section that answers it, {@code ""} when none does. */
        public String getSection() {
            return section;
        }

        /** Why it stays open, only has leads or lies outside; {@code ""} when answered. */
        public String getReason() {
            return reason;
        }

        public int getDirect() {
            return direct;
        }

        public int getLeads() {
            return leads;
        }
    }

    /** The outcome of a whole run. */
    public static final class Result {
        private final Opening opening;
        private final List<EvidenceVerdict> verdicts;
        private final SectionPlan plan;
        private final List<WrittenSection> sections;
        private final MissionReport report;
        private final List<QuestionLine> lines;
        private final List<Step> steps;
        private final int citedOutsideFence;

        Result(Opening opening, List<EvidenceVerdict> verdicts, SectionPlan plan, List<WrittenSection> sections,
               MissionReport report, List<QuestionLine> lines, List<Step> steps, int citedOutsideFence) {
            this.opening = opening;
            this.verdicts = Collections.unmodifiableList(new ArrayList<EvidenceVerdict>(verdicts));
            this.plan = plan;
            this.sections = Collections.unmodifiableList(new ArrayList<WrittenSection>(sections));
            this.report = report;
            this.lines = Collections.unmodifiableList(new ArrayList<QuestionLine>(lines));
            this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
            this.citedOutsideFence = citedOutsideFence;
        }

        public Opening getOpening() {
            return opening;
        }

        public List<EvidenceVerdict> getVerdicts() {
            return verdicts;
        }

        public SectionPlan getPlan() {
            return plan;
        }

        public List<WrittenSection> getSections() {
            return sections;
        }

        public MissionReport getReport() {
            return report;
        }

        public List<QuestionLine> getQuestionLines() {
            return lines;
        }

        /** The steps of the opening and the writing in order. */
        public List<Step> getSteps() {
            return steps;
        }

        public int getModelSwitches() {
            return opening.getModelSwitches();
        }

        /** Distinct passages cited in the book whose source the profile places outside the fence. */
        public int getCitedOutsideFence() {
            return citedOutsideFence;
        }

        /** The book as Markdown: chapter and section headings, the written text with its markers. */
        public String book() {
            StringBuilder out = new StringBuilder();
            String chapter = null;
            for (WrittenSection section : sections) {
                if (!section.getChapterTitle().equals(chapter)) {
                    chapter = section.getChapterTitle();
                    out.append("# ").append(chapter).append("\n\n");
                }
                out.append("## ").append(section.getTitle()).append("\n\n");
                out.append(section.isOk() ? section.getText().trim() : "(" + section.getStatus() + ": "
                        + section.getFailure() + ")").append("\n\n");
            }
            return out.toString();
        }
    }

    private final MainModelChat researchChat;
    private final MainModelChat writerChat;
    private final MainModelChat checkChat;
    private final EmbeddingPort embeddings;
    private final Settings settings;
    private final Clock clock;
    private final Consumer<String> log;

    /**
     * @param researchChat the model of profiles, questions and evidence check
     * @param writerChat   the model of the writer; the same as {@code researchChat} avoids a model switch
     * @param checkChat    the model of the sentence check; {@code null} leaves the sentences unchecked
     * @param embeddings   the vectors of the passages for the clustering (stored ones or freshly embedded)
     */
    public BookRun(MainModelChat researchChat, MainModelChat writerChat, MainModelChat checkChat,
                   EmbeddingPort embeddings, Settings settings, Clock clock, Consumer<String> log) {
        if (researchChat == null || writerChat == null || embeddings == null) {
            throw new IllegalArgumentException("the run needs a research model, a writer model and embeddings");
        }
        this.researchChat = researchChat;
        this.writerChat = writerChat;
        this.checkChat = checkChat;
        this.embeddings = embeddings;
        this.settings = settings == null ? Settings.of("de", 0) : settings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.log = log == null ? line -> {
        } : log;
    }

    /** The whole run: {@link #open}, then {@link #write}. */
    public Result run(ResearchFence fence, SourceBasket basket, BasketReader reader) throws IOException {
        return write(open(fence, basket, reader));
    }

    // ------------------------------------------------------------------------------------------------- open

    /** Opening up the basket: profiles, groups, questions with their verdict against mission and fence. */
    public Opening open(ResearchFence fence, SourceBasket basket, BasketReader reader) {
        ResearchFence idea = fence == null ? ResearchFence.of("") : fence;
        Switches switches = new Switches();
        List<Step> steps = new ArrayList<Step>();
        List<BasketPassage> passages = new ArrayList<BasketPassage>();
        Map<String, List<BasketPassage>> bySource = new LinkedHashMap<String, List<BasketPassage>>();
        Set<String> seen = new LinkedHashSet<String>();
        for (SourceBasket.SourceRef source : basket.getSources()) {
            List<BasketPassage> list = new ArrayList<BasketPassage>();
            for (BasketReader.Passage read : reader.passages(source)) {
                if (!read.getText().trim().isEmpty() && seen.add(read.getPassageId())) {
                    list.add(new BasketPassage(read.getPassageId(), source, "", read.getHeading(), read.getText()));
                }
            }
            if (!list.isEmpty()) {
                bySource.put(source.getSourceId(), list);
                passages.addAll(list);
            }
        }

        // 1 source profiles
        Step profileStep = step(steps, "quellensteckbriefe");
        long began = clock.millis();
        List<SourceSample> samples = new ArrayList<SourceSample>();
        for (List<BasketPassage> list : bySource.values()) {
            BasketPassage first = list.get(0);
            List<String> headings = new ArrayList<String>();
            for (BasketPassage passage : list) {
                headings.add(passage.heading);
            }
            samples.add(new SourceSample(first.getSourceId(), first.source.getUrl(), first.source.getTitle(),
                    first.domain, first.text, headings));
        }
        SourceRater rater = new SourceRater(new Metered(researchChat, profileStep, switches), KnownDomains.none(),
                settings.language, settings.rater, log, idea);
        Map<String, SourceRating> profiles = new LinkedHashMap<String, SourceRating>();
        for (SourceRating rating : rater.rateAll(samples, Collections.<String, SourceRating>emptyMap())) {
            profiles.put(rating.getSourceId(), rating);
        }
        profileStep.millis = clock.millis() - began;
        log.accept("book run: " + profiles.size() + " source profiles");

        // 2 passages outside the fence set aside by the profile and the heading path; the rest is clustered
        Map<String, String> setAside = new LinkedHashMap<String, String>();
        List<BasketPassage> inside = new ArrayList<BasketPassage>();
        for (BasketPassage passage : passages) {
            String reason = outside(passage, profiles.get(passage.getSourceId()));
            if (reason.isEmpty()) {
                inside.add(passage);
            } else {
                setAside.put(passage.passageId, reason);
            }
        }
        Step groupStep = step(steps, "gruppierung");
        began = clock.millis();
        Map<String, EmbeddingPort.EmbeddingVector> vectors = embed(inside);
        List<Passage> clustered = new ArrayList<Passage>();
        for (BasketPassage passage : inside) {
            clustered.add(new Passage(passage.passageId, passage.getSourceId(), null, passage.heading, passage.text,
                    vectors.get(passage.passageId).getModelFingerprint()));
        }
        List<TopicProposal> proposals = clustered.isEmpty() ? Collections.<TopicProposal>emptyList()
                : new TopicClusterer(IdSequence.counting(), settings.groupDistance,
                TopicClusterer.DEFAULT_REPRESENTATIVES).cluster(clustered, vectors);
        groupStep.millis = clock.millis() - began;
        log.accept("book run: " + inside.size() + " of " + passages.size() + " passages in " + proposals.size()
                + " groups, " + setAside.size() + " set aside outside the fence");

        // 3 questions from two sides: demanded by the mission, answered by a group
        Step questionStep = step(steps, "fragen");
        began = clock.millis();
        Map<String, BasketPassage> byId = byId(inside);
        Map<String, List<StockQuestions.Passage>> work = new LinkedHashMap<String, List<StockQuestions.Passage>>();
        Map<String, List<String>> members = new LinkedHashMap<String, List<String>>();
        int number = 0;
        for (TopicProposal proposal : proposals) {
            String groupId = "gruppe-" + (++number);
            List<StockQuestions.Passage> list = new ArrayList<StockQuestions.Passage>();
            for (String id : proposal.getMemberPassageIds()) {
                BasketPassage passage = byId.get(id);
                list.add(new StockQuestions.Passage(id, passage.heading, passage.text));
            }
            work.put(groupId, list);
            members.put(groupId, proposal.getMemberPassageIds());
        }
        StockQuestions questions = new StockQuestions(new Metered(researchChat, questionStep, switches), idea,
                settings.language, settings.questions, log);
        List<String> drafted = new ArrayList<String>();
        List<Question.Origin> draftedOrigin = new ArrayList<Question.Origin>();
        List<String> draftedGroup = new ArrayList<String>();
        StockQuestions.Drafted demanded = questions.draftMission();
        List<EmbeddingPort.EmbeddingVector> demandedVectors = demanded.getQuestions().isEmpty() || inside.isEmpty()
                ? Collections.<EmbeddingPort.EmbeddingVector>emptyList()
                : embeddings.embedDocuments(demanded.getQuestions());
        List<String> nearest = nearestGroups(demandedVectors, demanded.getQuestions().size(), members, vectors);
        for (int i = 0; i < demanded.getQuestions().size(); i++) {
            drafted.add(demanded.getQuestions().get(i));
            draftedOrigin.add(Question.Origin.PFLICHT);
            draftedGroup.add(nearest.get(i));
        }
        List<Group> groups = new ArrayList<Group>();
        for (StockQuestions.Drafted result : questions.draftAll(work)) {
            groups.add(new Group(result.getGroupId(), result.getTitle(), members.get(result.getGroupId()),
                    result.getProblem()));
            for (String question : result.getQuestions()) {
                if (drafted.contains(question)) {
                    continue; // the same question the mission demands: it stays one question
                }
                drafted.add(question);
                draftedOrigin.add(Question.Origin.BEFUND);
                draftedGroup.add(result.getGroupId());
            }
        }
        questionStep.millis = clock.millis() - began;
        if (!demanded.getProblem().isEmpty()) {
            log.accept("book run: no questions of the mission: " + demanded.getProblem());
        }

        Step checkStep = step(steps, "fragenpruefung");
        began = clock.millis();
        List<StockQuestions.Verdict> verdicts = drafted.isEmpty()
                ? Collections.<StockQuestions.Verdict>emptyList()
                : new StockQuestions(new Metered(researchChat, checkStep, switches), idea, settings.language,
                settings.questions, log).check(drafted);
        List<Question> list = new ArrayList<Question>();
        for (int i = 0; i < drafted.size(); i++) {
            Question question = new Question(String.format("F%03d", i + 1), drafted.get(i), draftedOrigin.get(i),
                    draftedGroup.get(i), verdicts.get(i));
            question.vector = i < demandedVectors.size() ? demandedVectors.get(i) : null;
            list.add(question);
        }
        checkStep.millis = clock.millis() - began;
        int pursued = 0;
        for (Question question : list) {
            pursued += question.isPursued() ? 1 : 0;
        }
        log.accept("book run: " + list.size() + " questions (" + demanded.getQuestions().size()
                + " demanded by the mission), " + pursued + " pursued");
        return new Opening(idea, inside, setAside, vectors, profiles, groups, list, steps, switches);
    }

    // ------------------------------------------------------------------------------------------------- write

    /** From the opened basket to the book. */
    public Result write(Opening opening) throws IOException {
        List<Step> steps = new ArrayList<Step>(opening.steps);
        Switches switches = opening.switches;
        Map<String, BasketPassage> byId = byId(opening.passages);
        Map<String, Group> groups = new LinkedHashMap<String, Group>();
        for (Group group : opening.groups) {
            groups.put(group.id, group);
        }

        // 4 evidence check per pursued question over the passages of its group
        Step judgeStep = step(steps, "belegpruefung");
        long began = clock.millis();
        EvidenceBundleJudge judge = new EvidenceBundleJudge(new Metered(researchChat, judgeStep, switches),
                opening.fence, settings.language, settings.judge, log);
        Map<EvidenceJudge.Question, List<EvidenceBundleJudge.Candidate>> work =
                new LinkedHashMap<EvidenceJudge.Question, List<EvidenceBundleJudge.Candidate>>();
        for (Question question : opening.questions) {
            if (!question.isPursued()) {
                continue;
            }
            Group group = groups.get(question.groupId);
            List<String> ids = question.vector == null
                    ? (group == null ? Collections.<String>emptyList() : group.passageIds)
                    : nearest(question.vector, opening);
            List<EvidenceBundleJudge.Candidate> candidates = new ArrayList<EvidenceBundleJudge.Candidate>();
            for (String id : ids) {
                BasketPassage passage = byId.get(id);
                candidates.add(new EvidenceBundleJudge.Candidate(new EvidenceJudge.Passage(passage.passageId,
                        passage.getSourceId(), passage.source.getUrl(), passage.domain, passage.text),
                        passage.heading, profileLine(opening.profiles.get(passage.getSourceId()))));
            }
            EvidenceJudge.Question asked = new EvidenceJudge.Question(question.id, question.text);
            work.put(asked, question.vector == null ? candidates : judge.fitting(asked, candidates));
        }
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>();
        for (EvidenceBundleJudge.Result result : judge.judgeAll(work, Collections.<EvidenceVerdict>emptyList(), 1)) {
            verdicts.addAll(result.getVerdicts());
        }
        judgeStep.millis = clock.millis() - began;

        // 5 memory and section plan: the groups as topics, the pursued questions under them
        Step planStep = step(steps, "abschnittsplan");
        began = clock.millis();
        ResearchMemory memory = new ResearchMemory();
        Set<String> withQuestions = new LinkedHashSet<String>();
        for (Question question : opening.questions) {
            if (question.isPursued() && groups.containsKey(question.groupId)) {
                withQuestions.add(question.groupId);
            }
        }
        for (Group group : opening.groups) {
            if (withQuestions.contains(group.id)) {
                memory.addTopic(ResearchTopic.found(group.id, group.title.isEmpty() ? group.id : group.title, "",
                        null, researchChat.modelName(), clock.millis()).withFence(ResearchTopic.Fence.INNEN,
                        "Fragen der Gruppe im Weidezaun", researchChat.modelName(), clock.millis()));
            }
        }
        for (Question question : opening.questions) {
            if (question.isPursued() && withQuestions.contains(question.groupId)) {
                memory.addQuestion(ResearchQuestion.open(question.id, question.text, question.groupId,
                        ResearchQuestion.Origin.BESTAND, clock.millis()));
            }
        }
        for (EvidenceVerdict verdict : verdicts) {
            memory.record(verdict);
        }
        SectionPlan plan = new SectionPlanner(memory).targetWords(settings.targetWords).plan(clock.millis());
        planStep.millis = clock.millis() - began;

        // 6 writing in order with the writing memory, bundled sentence check
        Step writeStep = step(steps, "schreiben");
        began = clock.millis();
        List<PassageLookup.Passage> lookup = new ArrayList<PassageLookup.Passage>();
        for (BasketPassage passage : opening.passages) {
            SourceRating profile = opening.profiles.get(passage.getSourceId());
            lookup.add(new PassageLookup.Passage(passage.passageId, passage.getSourceId(), passage.source.getUrl(),
                    passage.text, profile == null || !profile.isOk() ? "" : profile.getKind().name(), ""));
        }
        PlanSectionWriter writer = new PlanSectionWriter(new Metered(writerChat, writeStep, switches),
                checkChat == null ? null : new Metered(checkChat, writeStep, switches), PassageLookup.of(lookup),
                new SentenceVerdicts(), new WrittenSections(), settings.writer, clock, log);
        List<WrittenSection> sections = writer.writeAll(plan);
        writeStep.millis = clock.millis() - began;

        // 7 report
        List<BookSection> book = new ArrayList<BookSection>();
        for (WrittenSection section : sections) {
            book.add(section.toBookSection());
        }
        Map<String, MissionCheck.SourceInfo> sourceInfo = new LinkedHashMap<String, MissionCheck.SourceInfo>();
        for (SourceRating profile : opening.profiles.values()) {
            if (profile.isOk()) {
                sourceInfo.put(profile.getSourceId(), new MissionCheck.SourceInfo(profile.getKind().name(),
                        profile.getJurisdiction().name()));
            }
        }
        MissionReport report = new MissionCheck(memory).sections(book).sentences(writer.sentenceLookup())
                .placements(plan.getPlacements()).sources(sourceInfo).report(clock.millis());
        List<QuestionLine> lines = questionLines(opening.questions, memory, plan, sections, verdicts);
        log.accept("book run: " + sections.size() + " sections, " + switches.count + " model switches");
        return new Result(opening, verdicts, plan, sections, report, lines, steps,
                citedOutside(sections, opening.profiles));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static Step step(List<Step> steps, String name) {
        Step step = new Step(name);
        steps.add(step);
        return step;
    }

    /**
     * Why a passage lies outside the fence by its source's profile: the whole source outside, or a part of a mixed
     * source the profile names by its heading (the heading itself or a heading below it); {@code ""} when it stays.
     */
    static String outside(BasketPassage passage, SourceRating profile) {
        if (profile == null || !profile.isOk() || !profile.getFit().isAsked()) {
            return "";
        }
        SourceRating.Fit fit = profile.getFit();
        if (fit.getRelation() == SourceRating.Relation.AUSSEN) {
            return "Quelle außerhalb des Weidezauns" + (fit.getSubject().isEmpty() ? "" : ": " + fit.getSubject());
        }
        if (fit.getRelation() == SourceRating.Relation.GEMISCHT) {
            String heading = passage.heading.trim();
            for (String part : fit.getOutsideParts()) {
                String p = part.trim();
                if (!p.isEmpty() && (heading.equals(p) || heading.startsWith(p + " >"))) {
                    return "Teil außerhalb des Weidezauns: " + p;
                }
            }
        }
        return "";
    }

    /** The vectors of the passages, heading path and text as one document each. */
    private Map<String, EmbeddingPort.EmbeddingVector> embed(List<BasketPassage> passages) {
        Map<String, EmbeddingPort.EmbeddingVector> out = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        if (passages.isEmpty()) {
            return out;
        }
        List<String> texts = new ArrayList<String>();
        for (BasketPassage passage : passages) {
            texts.add(passage.heading.isEmpty() ? passage.text : passage.heading + "\n" + passage.text);
        }
        List<EmbeddingPort.EmbeddingVector> embedded = embeddings.embedDocuments(texts);
        for (int i = 0; i < passages.size(); i++) {
            out.put(passages.get(i).passageId, embedded.get(i));
        }
        return out;
    }

    /** Per question the group whose mean vector lies nearest to it; {@code ""} without groups. */
    private static List<String> nearestGroups(List<EmbeddingPort.EmbeddingVector> questions, int count,
                                              Map<String, List<String>> members,
                                              Map<String, EmbeddingPort.EmbeddingVector> vectors) {
        List<String> out = new ArrayList<String>();
        if (members.isEmpty() || questions.isEmpty()) {
            for (int i = 0; i < count; i++) {
                out.add("");
            }
            return out;
        }
        Map<String, EmbeddingPort.EmbeddingVector> centres = new LinkedHashMap<String, EmbeddingPort.EmbeddingVector>();
        for (Map.Entry<String, List<String>> group : members.entrySet()) {
            List<EmbeddingPort.EmbeddingVector> list = new ArrayList<EmbeddingPort.EmbeddingVector>();
            for (String id : group.getValue()) {
                list.add(vectors.get(id));
            }
            centres.put(group.getKey(), VectorMath.mean(list));
        }
        for (EmbeddingPort.EmbeddingVector question : questions) {
            String best = "";
            double bestSimilarity = Double.NEGATIVE_INFINITY;
            for (Map.Entry<String, EmbeddingPort.EmbeddingVector> centre : centres.entrySet()) {
                double similarity = VectorMath.cosine(question, centre.getValue());
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    best = centre.getKey();
                }
            }
            out.add(best);
        }
        return out;
    }

    /** The passages of the opening, nearest to the vector first (ties in reading order). */
    private static List<String> nearest(final EmbeddingPort.EmbeddingVector vector, Opening opening) {
        final Map<String, Double> similarity = new LinkedHashMap<String, Double>();
        for (BasketPassage passage : opening.passages) {
            similarity.put(passage.passageId, VectorMath.cosine(vector, opening.vectors.get(passage.passageId)));
        }
        List<String> ids = new ArrayList<String>(similarity.keySet());
        Collections.sort(ids, (a, b) -> Double.compare(similarity.get(b), similarity.get(a)));
        return ids;
    }

    private static Map<String, BasketPassage> byId(List<BasketPassage> passages) {
        Map<String, BasketPassage> out = new LinkedHashMap<String, BasketPassage>();
        for (BasketPassage passage : passages) {
            out.put(passage.passageId, passage);
        }
        return out;
    }

    /** The profile of a source in one line for the evidence check; {@code ""} without a usable profile. */
    static String profileLine(SourceRating profile) {
        if (profile == null || !profile.isOk()) {
            return "";
        }
        List<String> parts = new ArrayList<String>();
        if (!profile.getPublisher().isEmpty()) {
            parts.add(profile.getPublisher());
        }
        parts.add(profile.getKind().name());
        parts.add(profile.getJurisdiction().name());
        if (!profile.getDate().isEmpty()) {
            parts.add(profile.getDate());
        }
        SourceRating.Fit fit = profile.getFit();
        if (fit.isAsked()) {
            if (!fit.getSubject().isEmpty()) {
                parts.add("Gegenstand: " + fit.getSubject());
            }
            parts.add("Bezug zum Weidezaun: " + fit.getRelation());
            if (!fit.getOutsideParts().isEmpty()) {
                parts.add("außerhalb: " + String.join("; ", fit.getOutsideParts()));
            }
        }
        return String.join(" · ", parts);
    }

    /** Every question with where it is answered or why it stays open or outside. */
    static List<QuestionLine> questionLines(List<Question> questions, ResearchMemory memory, SectionPlan plan,
                                            List<WrittenSection> sections, List<EvidenceVerdict> verdicts) {
        Map<String, WrittenSection> written = new LinkedHashMap<String, WrittenSection>();
        for (WrittenSection section : sections) {
            written.put(section.getSectionId(), section);
        }
        List<QuestionLine> out = new ArrayList<QuestionLine>();
        for (Question question : questions) {
            if (!question.isPursued()) {
                out.add(new QuestionLine(question, QuestionLine.State.AUSSERHALB, "", "außerhalb des Weidezauns: "
                        + question.verdict.getReason(), 0, 0));
                continue;
            }
            int notApplicable = 0;
            int unclear = 0;
            for (EvidenceVerdict verdict : verdicts) {
                if (verdict.getQuestionId().equals(question.id)) {
                    notApplicable += verdict.getReason().startsWith("nicht anwendbar")
                            || verdict.getReason().startsWith("does not apply") ? 1 : 0;
                    unclear += verdict.getReason().startsWith("Anwendbarkeit unklar")
                            || verdict.getReason().startsWith("applicability unclear") ? 1 : 0;
                }
            }
            QuestionEvidence evidence = memory.evidenceOf(question.id);
            int direct = evidence == null ? 0 : evidence.getDirectCount();
            int leads = evidence == null ? 0 : evidence.getLeadCount();
            Set<String> directIds = new LinkedHashSet<String>();
            if (evidence != null) {
                for (EvidenceVerdict verdict : evidence.getDirect()) {
                    directIds.add(verdict.getPassageId());
                }
            }
            SectionPlan.Section planned = null;
            for (SectionPlan.Section section : plan.getSections()) {
                for (SectionPlan.Question asked : section.getQuestions()) {
                    if (asked.getId().equals(question.id)) {
                        planned = section;
                    }
                }
            }
            WrittenSection done = planned == null ? null : written.get(planned.getId());
            boolean cited = false;
            if (done != null && done.isOk()) {
                for (WrittenSection.Citation citation : done.getCitations().values()) {
                    cited |= directIds.contains(citation.getPassageId());
                }
            }
            QuestionLine.State state;
            String reason;
            if (cited) {
                state = QuestionLine.State.BEANTWORTET;
                reason = "";
            } else if (direct == 0 && question.origin == Question.Origin.PFLICHT) {
                state = QuestionLine.State.NACHRECHERCHE;
                reason = "vom Auftrag verlangt, aber kein direkter Beleg im Korb"
                        + (leads > 0 ? " (nur weiterführende Hinweise)" : "") + ": Nachrecherche oder ehrliche Lücke";
            } else if (direct == 0 && leads > 0) {
                state = QuestionLine.State.NUR_HINWEISE;
                reason = "nur weiterführende Hinweise, kein direkter Beleg im Korb";
            } else {
                state = QuestionLine.State.OFFEN;
                reason = direct == 0 ? (unclear > 0 ? "Belege nur mit unklarer Anwendbarkeit"
                        : notApplicable > 0 ? "Fundstellen gelten außerhalb des Weidezauns"
                        : "kein Beleg im Korb")
                        : planned == null ? "nicht im Abschnittsplan"
                        : done == null || !done.isOk() ? "Abschnitt nicht geschrieben"
                        : "Abschnitt zitiert keinen direkten Beleg der Frage";
            }
            out.add(new QuestionLine(question, state, cited ? planned.getTitle() : "", reason, direct, leads));
        }
        return out;
    }

    private static int citedOutside(List<WrittenSection> sections, Map<String, SourceRating> profiles) {
        Set<String> outside = new LinkedHashSet<String>();
        for (WrittenSection section : sections) {
            for (WrittenSection.Citation citation : section.getCitations().values()) {
                SourceRating profile = profiles.get(citation.getSourceId());
                if (profile != null && profile.getFit().getRelation() == SourceRating.Relation.AUSSEN) {
                    outside.add(citation.getPassageId());
                }
            }
        }
        return outside.size();
    }

    /** The model switches of the whole run, by the model label of each answer. */
    static final class Switches {
        private String last = "";
        int count;

        synchronized void saw(String label) {
            if (label == null || label.isEmpty()) {
                return;
            }
            if (!last.isEmpty() && !last.equals(label)) {
                count++;
            }
            last = label;
        }
    }

    /** A chat that counts its calls into a step; it passes everything through unchanged. */
    static final class Metered implements MainModelChat {
        private final MainModelChat chat;
        private final Step step;
        private final Switches switches;

        Metered(MainModelChat chat, Step step, Switches switches) {
            this.chat = chat;
            this.step = step;
            this.switches = switches;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature, int maxOutputTokens) {
            return count(chat.complete(messages, temperature, maxOutputTokens));
        }

        @Override
        public MainModelChatResult completeJson(List<ChatMessage> messages, double temperature, int maxOutputTokens,
                                                String schemaJson) {
            return count(chat.completeJson(messages, temperature, maxOutputTokens, schemaJson));
        }

        @Override
        public String modelName() {
            return chat.modelName();
        }

        private MainModelChatResult count(MainModelChatResult result) {
            step.calls.incrementAndGet();
            if (result == null || !result.isOk()) {
                step.failures.incrementAndGet();
            }
            ModelCallMetrics metrics = result == null ? null : result.getMetrics();
            if (metrics != null) {
                step.promptTokens.addAndGet(metrics.getPromptEvalCount());
                step.answerTokens.addAndGet(metrics.getEvalCount());
                step.modelMillis.addAndGet(metrics.getDurationMillis());
            }
            switches.saw(metrics == null || metrics.getModelLabel().isEmpty() ? chat.modelName()
                    : metrics.getModelLabel());
            return result;
        }
    }
}
