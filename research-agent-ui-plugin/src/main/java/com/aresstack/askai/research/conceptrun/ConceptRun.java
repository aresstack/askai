package com.aresstack.askai.research.conceptrun;

import com.aresstack.askai.research.domain.scope.ResearchFence;
import com.aresstack.askai.research.evidence.EvidenceBundleJudge;
import com.aresstack.askai.research.evidence.EvidenceJudge;
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
 * The concept-led run (Neuausrichtung of 26.11.2026): from the Weidezaun, the cards and the original questions of a
 * book and a stock of passages to a written book and its report, in one straight line and without topic finding:
 * <ol>
 * <li>a profile per source ({@link SourceRater} with the fence: subject, kind, publisher, date, relation to the fence,
 * parts outside), from its first passage and its headings; no source is dropped;</li>
 * <li>per question one evidence check over its candidates in bundles that fit the context
 * ({@link EvidenceBundleJudge}), each passage with its heading path and its source's profile, applicability decided
 * against the fence;</li>
 * <li>the cards as topics, the original questions under them and the verdicts in the research memory, the section
 * plan from them ({@link SectionPlanner}): the outline comes from questions and evidence;</li>
 * <li>the sections in order with the writing memory, the sentence check bundled by passage
 * ({@link PlanSectionWriter});</li>
 * <li>the mission report ({@link MissionCheck}) and per original question where it is answered or why it stays
 * open.</li>
 * </ol>
 * Applicability is decided once, in the evidence check against the fence; the writer gets no rules of legal spaces and
 * puts in no speakers afterwards. Candidates come from the caller (a candidate search, stored pairs, or every passage
 * of a small stock). Every model call is counted per step: calls, failures, tokens, time and model switches.
 */
public final class ConceptRun {

    /** A card of the concept: a topic of the book inside the fence by definition. */
    public static final class Card {
        private final String id;
        private final String name;
        private final double weight;

        public Card(String id, String name, double weight) {
            this.id = id;
            this.name = name;
            this.weight = weight;
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }
    }

    /** An original question of the book, on one card. It is never dropped. */
    public static final class Question {
        private final String id;
        private final String text;
        private final String cardId;

        public Question(String id, String text, String cardId) {
            this.id = id;
            this.text = text;
            this.cardId = cardId;
        }

        public String getId() {
            return id;
        }

        public String getText() {
            return text;
        }

        public String getCardId() {
            return cardId;
        }
    }

    /** A passage of the stock with its source and the headings above it. */
    public static final class StockPassage {
        private final String passageId;
        private final String sourceId;
        private final String url;
        private final String domain;
        private final String title;
        private final String heading;
        private final String text;

        public StockPassage(String passageId, String sourceId, String url, String domain, String title, String heading,
                            String text) {
            this.passageId = passageId;
            this.sourceId = sourceId == null || sourceId.isEmpty() ? "passage:" + passageId : sourceId;
            this.url = url == null ? "" : url;
            this.domain = domain == null ? "" : domain;
            this.title = title == null ? "" : title;
            this.heading = heading == null ? "" : heading;
            this.text = text == null ? "" : text;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getHeading() {
            return heading;
        }

        public String getText() {
            return text;
        }
    }

    /** The settings of the run; every value is a setting of the component it goes to. */
    public static final class Settings {
        private final String language;
        private final int targetWords;
        private final SourceRater.Options rater;
        private final EvidenceBundleJudge.Options judge;
        private final PlanSectionWriter.Options writer;

        public Settings(String language, int targetWords, SourceRater.Options rater, EvidenceBundleJudge.Options judge,
                        PlanSectionWriter.Options writer) {
            this.language = language == null || language.trim().isEmpty() ? "de" : language.trim();
            this.targetWords = targetWords;
            this.rater = rater == null ? SourceRater.Options.defaults() : rater;
            this.judge = judge == null ? EvidenceBundleJudge.Options.defaults() : judge;
            this.writer = (writer == null ? PlanSectionWriter.Options.defaults().withLanguage(this.language) : writer)
                    .withParallel(1).withCheckBundled(true).withAttributionTail(false);
        }

        public static Settings of(String language, int targetWords) {
            return new Settings(language, targetWords, null, null, null);
        }

        public PlanSectionWriter.Options getWriter() {
            return writer;
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

    /** Where an original question stands at the end. */
    public static final class QuestionLine {
        public enum State { BEANTWORTET, NUR_HINWEISE, OFFEN }

        private final Question question;
        private final State state;
        private final String section;
        private final String reason;
        private final int direct;
        private final int leads;
        private final int notApplicable;
        private final int unclear;

        QuestionLine(Question question, State state, String section, String reason, int direct, int leads,
                     int notApplicable, int unclear) {
            this.question = question;
            this.state = state;
            this.section = section;
            this.reason = reason;
            this.direct = direct;
            this.leads = leads;
            this.notApplicable = notApplicable;
            this.unclear = unclear;
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

        /** Why it stays open or only has leads; {@code ""} when answered. */
        public String getReason() {
            return reason;
        }

        public int getDirect() {
            return direct;
        }

        public int getLeads() {
            return leads;
        }

        /** Passages judged not to apply within the fence. */
        public int getNotApplicable() {
            return notApplicable;
        }

        /** Passages whose applicability stayed unclear. */
        public int getUnclear() {
            return unclear;
        }
    }

    /** The outcome of a run. */
    public static final class Result {
        private final Map<String, SourceRating> profiles;
        private final List<EvidenceVerdict> verdicts;
        private final SectionPlan plan;
        private final List<WrittenSection> sections;
        private final MissionReport report;
        private final List<QuestionLine> questions;
        private final List<Step> steps;
        private final int modelSwitches;
        private final int citedOutsideFence;

        Result(Map<String, SourceRating> profiles, List<EvidenceVerdict> verdicts, SectionPlan plan,
               List<WrittenSection> sections, MissionReport report, List<QuestionLine> questions, List<Step> steps,
               int modelSwitches, int citedOutsideFence) {
            this.profiles = Collections.unmodifiableMap(new LinkedHashMap<String, SourceRating>(profiles));
            this.verdicts = Collections.unmodifiableList(new ArrayList<EvidenceVerdict>(verdicts));
            this.plan = plan;
            this.sections = Collections.unmodifiableList(new ArrayList<WrittenSection>(sections));
            this.report = report;
            this.questions = Collections.unmodifiableList(new ArrayList<QuestionLine>(questions));
            this.steps = Collections.unmodifiableList(new ArrayList<Step>(steps));
            this.modelSwitches = modelSwitches;
            this.citedOutsideFence = citedOutsideFence;
        }

        public Map<String, SourceRating> getProfiles() {
            return profiles;
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

        public List<QuestionLine> getQuestions() {
            return questions;
        }

        public List<Step> getSteps() {
            return steps;
        }

        /** Calls whose model differs from the call before (by the label the model reports). */
        public int getModelSwitches() {
            return modelSwitches;
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

    private final MainModelChat writerChat;
    private final MainModelChat checkChat;
    private final MainModelChat researchChat;
    private final Settings settings;
    private final Clock clock;
    private final Consumer<String> log;

    /**
     * @param researchChat the model of the source profiles and the evidence check
     * @param writerChat   the model of the writer; the same as {@code researchChat} avoids a model switch
     * @param checkChat    the model of the sentence check; {@code null} leaves the sentences unchecked
     */
    public ConceptRun(MainModelChat researchChat, MainModelChat writerChat, MainModelChat checkChat, Settings settings,
                      Clock clock, Consumer<String> log) {
        if (researchChat == null || writerChat == null) {
            throw new IllegalArgumentException("the run needs a research and a writer model");
        }
        this.researchChat = researchChat;
        this.writerChat = writerChat;
        this.checkChat = checkChat;
        this.settings = settings == null ? Settings.of("de", 0) : settings;
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.log = log == null ? line -> {
        } : log;
    }

    /**
     * The whole run.
     *
     * @param candidates the candidate passage ids per question id; a question without an entry gets every passage
     *                   of the stock
     * @throws IOException when a verdict or a written section cannot be kept
     */
    public Result run(ResearchFence fence, List<Card> cards, List<Question> questions, List<StockPassage> stock,
                      Map<String, List<String>> candidates) throws IOException {
        Switches switches = new Switches();
        List<Step> steps = new ArrayList<Step>();
        Map<String, StockPassage> byId = new LinkedHashMap<String, StockPassage>();
        Map<String, List<StockPassage>> bySource = new LinkedHashMap<String, List<StockPassage>>();
        for (StockPassage passage : stock) {
            byId.put(passage.passageId, passage);
            List<StockPassage> list = bySource.get(passage.sourceId);
            if (list == null) {
                list = new ArrayList<StockPassage>();
                bySource.put(passage.sourceId, list);
            }
            list.add(passage);
        }

        // 1 source profiles
        Step profileStep = new Step("quellensteckbriefe");
        steps.add(profileStep);
        long began = clock.millis();
        List<SourceSample> samples = new ArrayList<SourceSample>();
        for (List<StockPassage> passages : bySource.values()) {
            StockPassage first = passages.get(0);
            List<String> headings = new ArrayList<String>();
            for (StockPassage passage : passages) {
                headings.add(passage.heading);
            }
            samples.add(new SourceSample(first.sourceId, first.url, first.title,
                    first.domain.isEmpty() ? SourceSample.hostOf(first.url) : first.domain, first.text, headings));
        }
        SourceRater rater = new SourceRater(new Metered(researchChat, profileStep, switches), KnownDomains.none(),
                settings.language, settings.rater, log, fence);
        Map<String, SourceRating> profiles = new LinkedHashMap<String, SourceRating>();
        for (SourceRating rating : rater.rateAll(samples, Collections.<String, SourceRating>emptyMap())) {
            profiles.put(rating.getSourceId(), rating);
        }
        profileStep.millis = clock.millis() - began;
        log.accept("concept run: " + profiles.size() + " source profiles");

        // 2 evidence check per question
        Step judgeStep = new Step("belegpruefung");
        steps.add(judgeStep);
        began = clock.millis();
        Map<EvidenceJudge.Question, List<EvidenceBundleJudge.Candidate>> work =
                new LinkedHashMap<EvidenceJudge.Question, List<EvidenceBundleJudge.Candidate>>();
        for (Question question : questions) {
            List<String> ids = candidates == null ? null : candidates.get(question.id);
            List<EvidenceBundleJudge.Candidate> list = new ArrayList<EvidenceBundleJudge.Candidate>();
            for (String id : ids == null ? new ArrayList<String>(byId.keySet()) : ids) {
                StockPassage passage = byId.get(id);
                if (passage != null && !passage.text.trim().isEmpty()) {
                    list.add(new EvidenceBundleJudge.Candidate(new EvidenceJudge.Passage(passage.passageId,
                            passage.sourceId, passage.url, passage.domain, passage.text), passage.heading,
                            profileLine(profiles.get(passage.sourceId))));
                }
            }
            work.put(new EvidenceJudge.Question(question.id, question.text), list);
        }
        EvidenceBundleJudge judge = new EvidenceBundleJudge(new Metered(researchChat, judgeStep, switches), fence,
                settings.language, settings.judge, log);
        List<EvidenceVerdict> verdicts = new ArrayList<EvidenceVerdict>();
        for (EvidenceBundleJudge.Result result : judge.judgeAll(work, Collections.<EvidenceVerdict>emptyList(), 1)) {
            verdicts.addAll(result.getVerdicts());
        }
        judgeStep.millis = clock.millis() - began;

        // 3 memory and section plan
        Step planStep = new Step("abschnittsplan");
        steps.add(planStep);
        began = clock.millis();
        ResearchMemory memory = new ResearchMemory();
        Set<String> cardIds = new LinkedHashSet<String>();
        for (Card card : cards) {
            memory.addTopic(ResearchTopic.ofCard(card.id, card.name, "anchor-concept-" + card.id, card.weight,
                    clock.millis()));
            cardIds.add(card.id);
        }
        for (Question question : questions) {
            if (!cardIds.contains(question.cardId)) {
                throw new IllegalArgumentException("question " + question.id + " names no card of the concept");
            }
            memory.addQuestion(ResearchQuestion.open(question.id, question.text, question.cardId,
                    ResearchQuestion.Origin.KONZEPT, clock.millis()));
        }
        for (EvidenceVerdict verdict : verdicts) {
            memory.record(verdict);
        }
        SectionPlan plan = new SectionPlanner(memory).targetWords(settings.targetWords).plan(clock.millis());
        planStep.millis = clock.millis() - began;

        // 4 writing in order with the writing memory, bundled sentence check
        Step writeStep = new Step("schreiben");
        steps.add(writeStep);
        began = clock.millis();
        List<PassageLookup.Passage> lookup = new ArrayList<PassageLookup.Passage>();
        for (StockPassage passage : stock) {
            SourceRating profile = profiles.get(passage.sourceId);
            lookup.add(new PassageLookup.Passage(passage.passageId, passage.sourceId, passage.url, passage.text,
                    profile == null || !profile.isOk() ? "" : profile.getKind().name(), ""));
        }
        SentenceVerdicts sentenceVerdicts = new SentenceVerdicts();
        PlanSectionWriter writer = new PlanSectionWriter(new Metered(writerChat, writeStep, switches),
                checkChat == null ? null : new Metered(checkChat, writeStep, switches), PassageLookup.of(lookup),
                sentenceVerdicts, new WrittenSections(), settings.writer, clock, log);
        List<WrittenSection> sections = writer.writeAll(plan);
        writeStep.millis = clock.millis() - began;

        // 5 report
        List<BookSection> book = new ArrayList<BookSection>();
        for (WrittenSection section : sections) {
            book.add(section.toBookSection());
        }
        Map<String, MissionCheck.SourceInfo> sourceInfo = new LinkedHashMap<String, MissionCheck.SourceInfo>();
        for (SourceRating profile : profiles.values()) {
            if (profile.isOk()) {
                sourceInfo.put(profile.getSourceId(), new MissionCheck.SourceInfo(profile.getKind().name(),
                        profile.getJurisdiction().name()));
            }
        }
        MissionReport report = new MissionCheck(memory).sections(book).sentences(writer.sentenceLookup())
                .placements(plan.getPlacements()).sources(sourceInfo).report(clock.millis());
        List<QuestionLine> lines = questionLines(questions, memory, plan, sections, verdicts);
        int outside = citedOutside(sections, profiles);
        log.accept("concept run: " + sections.size() + " sections, " + switches.count + " model switches");
        return new Result(profiles, verdicts, plan, sections, report, lines, steps, switches.count, outside);
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

    /** Every original question with where it is answered or why it stays open. */
    static List<QuestionLine> questionLines(List<Question> questions, ResearchMemory memory, SectionPlan plan,
                                            List<WrittenSection> sections, List<EvidenceVerdict> verdicts) {
        Map<String, WrittenSection> written = new LinkedHashMap<String, WrittenSection>();
        for (WrittenSection section : sections) {
            written.put(section.getSectionId(), section);
        }
        List<QuestionLine> out = new ArrayList<QuestionLine>();
        for (Question question : questions) {
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
            } else if (direct == 0 && leads > 0) {
                state = QuestionLine.State.NUR_HINWEISE;
                reason = "nur weiterführende Hinweise, kein direkter Beleg im Bestand";
            } else {
                state = QuestionLine.State.OFFEN;
                reason = direct == 0 ? (unclear > 0 ? "Belege nur mit unklarer Anwendbarkeit"
                        : notApplicable > 0 ? "Fundstellen gelten außerhalb des Weidezauns"
                        : "kein Beleg im Bestand")
                        : planned == null ? "nicht im Abschnittsplan"
                        : done == null || !done.isOk() ? "Abschnitt nicht geschrieben"
                        : "Abschnitt zitiert keinen direkten Beleg der Frage";
            }
            out.add(new QuestionLine(question, state, cited ? planned.getTitle() : "", reason, direct, leads,
                    notApplicable, unclear));
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
