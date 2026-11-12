package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3b: the outline synthesis on {@code phase.outline}. Bounded, traceable steps:
 * <ol>
 * <li>the chapter hierarchy from the brief ({@link ChapterPlanContract});</li>
 * <li>the sections of each chapter ({@link SectionPlanContract});</li>
 * <li>the deterministic review ({@link OutlinePlanReview});</li>
 * <li>on blocking findings, a revised chapter hierarchy with the finding list; chapters whose material did not
 * change keep their sections, the others are planned again; then back to 3.</li>
 * </ol>
 * The revision rounds are limited by the settings; the run ends when the review has no blocking finding or the rounds
 * are spent. Every answer is validated; an invalid answer gets one repair with its error list and is never used.
 * When no valid chapter hierarchy comes back, the plan from the analysis alone is used (and says so); when one
 * chapter's sections fail, that chapter is split by its material. The synthesizer never writes the store.
 */
public final class OutlineSynthesizer {

    /** The synthesis role on {@code phase.outline}; prompts come from the contracts, answers are checked JSON. */
    public static final ResearchRole ROLE = ResearchRole.builder("outline-synthesis",
                    ResearchModelSlots.PHASE_OUTLINE)
            .purpose("Phase 3b: the chapter hierarchy and sections from the topic analysis and the concept")
            .promptFromCaller()
            .output(RoleOutput.json("ChapterPlanContract, SectionPlanContract"))
            .thinking(ThinkingMode.OFF)
            .build();

    /** A prompt estimate: characters per token (the convention of the writer packs). */
    static final int CHARS_PER_TOKEN = 4;

    /** One model call as the log sees it. */
    public interface Telemetry {
        Telemetry NONE = new Telemetry() {
            public void call(String step, String model, int budget, int attempt, boolean ok, String detail) {
            }
        };

        void call(String step, String model, int budget, int attempt, boolean ok, String detail);
    }

    /** The outcome: the plan, the findings left on it, the rounds made and notes for the quality report. */
    public static final class Result {
        private final OutlinePlan plan;
        private final List<PlanFinding> findings;
        private final int revisions;
        private final List<String> notes;
        private final SynthesisBrief brief;

        Result(OutlinePlan plan, List<PlanFinding> findings, int revisions, List<String> notes, SynthesisBrief brief) {
            this.plan = plan;
            this.findings = Collections.unmodifiableList(new ArrayList<PlanFinding>(findings));
            this.revisions = revisions;
            this.notes = Collections.unmodifiableList(new ArrayList<String>(notes));
            this.brief = brief;
        }

        public OutlinePlan getPlan() {
            return plan;
        }

        /** The review's findings on the final plan. */
        public List<PlanFinding> getFindings() {
            return findings;
        }

        /** Revision rounds that were made. */
        public int getRevisions() {
            return revisions;
        }

        /** What the user should know about how the plan came about (fallbacks, limits). */
        public List<String> getNotes() {
            return notes;
        }

        /** The brief the plan's keys refer to (it may list fewer topics than the one given, see notes). */
        public SynthesisBrief getBrief() {
            return brief;
        }
    }

    private final MainModelChat chat;
    private final OutlinePlanReview review;
    private final OutlineSynthesisSettings settings;
    private final Telemetry telemetry;

    public OutlineSynthesizer(MainModelChat chat, OutlinePlanReview review, OutlineSynthesisSettings settings,
                              Telemetry telemetry) {
        if (review == null) {
            throw new IllegalArgumentException("a review is required");
        }
        this.chat = chat;
        this.review = review;
        this.settings = settings == null ? OutlineSynthesisSettings.defaults() : settings;
        this.telemetry = telemetry == null ? Telemetry.NONE : telemetry;
    }

    /**
     * Runs the synthesis. Without a chat (no model port) the plan comes from the analysis alone.
     * @throws ClusteringMonitor.ClusteringCancelledException when the monitor cancels between two calls
     */
    public Result synthesize(SynthesisBrief given, ClusteringMonitor monitor) {
        ClusteringMonitor m = monitor == null ? ClusteringMonitor.NONE : monitor;
        List<String> notes = new ArrayList<String>();
        if (given.getTopics().isEmpty()) {
            OutlinePlan empty = new OutlinePlan(Collections.<PlannedChapter>emptyList(),
                    FallbackOutlinePlan.gaps(given), true);
            notes.add("The research has no topic that meets the minimums yet; there is nothing to outline.");
            return new Result(empty, review.review(empty, given), 0, notes, given);
        }
        if (chat == null) {
            notes.add("No model is available; this outline comes from the topic analysis only.");
            OutlinePlan plan = FallbackOutlinePlan.plan(given);
            return new Result(plan, review.review(plan, given), 0, notes, given);
        }
        SynthesisBrief brief = fitted(given, notes);

        m.progress("Planning the chapters", 0, 1);
        OutlinePlan plan = askChapters(ChapterPlanContract.prompt(brief), brief, "plan", m);
        if (plan == null) {
            notes.add("The model gave no valid chapter plan; this outline comes from the topic analysis only.");
            plan = FallbackOutlinePlan.plan(brief);
            return new Result(plan, review.review(plan, brief), 0, notes, brief);
        }
        plan = withSections(plan, null, brief, notes, m);

        int revisions = 0;
        List<PlanFinding> findings = review.review(plan, brief);
        while (hasBlocking(findings) && revisions < settings.getRevisionRounds()) {
            checkCancel(m);
            revisions++;
            m.progress("Revising the chapters (round " + revisions + ")", revisions, settings.getRevisionRounds());
            OutlinePlan revised = askChapters(ChapterPlanContract.revisePrompt(brief, plan, blocking(findings)),
                    brief, "revise-" + revisions, m);
            if (revised == null) {
                notes.add("Revision round " + revisions + " gave no valid chapter plan; the previous plan stays.");
                break;
            }
            plan = withSections(revised, plan, brief, notes, m);
            findings = review.review(plan, brief);
        }
        if (hasBlocking(findings)) {
            notes.add("The review still has findings after " + revisions + " revision round"
                    + (revisions == 1 ? "" : "s") + "; the safe fixes below correct what code can correct, the "
                    + "report lists what remains.");
        }
        return new Result(plan, findings, revisions, notes, brief);
    }

    /** The brief, shortened while its plan prompt would not fit the model's context. */
    private SynthesisBrief fitted(SynthesisBrief brief, List<String> notes) {
        int context = chat.contextTokens();
        if (context <= 0) {
            return brief;
        }
        SynthesisBrief current = brief;
        while (current.getTopics().size() > 1 && ChapterPlanContract.prompt(current).length() / CHARS_PER_TOKEN
                + settings.getPlanAnswerTokens() > context) {
            current = current.limitedTo(current.getTopics().size() - 1);
        }
        if (current != brief) {
            notes.add("The model's context holds " + current.getTopics().size() + " of "
                    + brief.getTopics().size() + " topics; code places the others.");
        }
        return current;
    }

    /** Sections for every chapter; a chapter with the same material as one of {@code previous} keeps its sections. */
    private OutlinePlan withSections(OutlinePlan plan, OutlinePlan previous, SynthesisBrief brief, List<String> notes,
                                     ClusteringMonitor monitor) {
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        // The sections are planned from the material each chapter will own after the quality check (a concept part
        // goes to the chapter of its requirement, the rest to the first chapter naming it). Live run 4 after #210:
        // every chapter named the whole large topic, so every section call saw all of it and three chapters got the
        // same section "Technologien und Herausforderungen" before the ownership fix took the parts apart.
        java.util.Map<String, Integer> owner = OutlineQualityCheck.owners(plan, brief);
        int index = 0;
        for (PlannedChapter chapter : plan.getChapters()) {
            index++;
            checkCancel(monitor);
            monitor.progress("Planning the sections of chapter " + index + " of " + plan.getChapters().size(), index,
                    plan.getChapters().size());
            PlannedChapter kept = previous == null ? null : sameMaterial(previous, chapter);
            if (kept != null) {
                chapters.add(chapter.withSections(kept.getSections()));
                continue;
            }
            List<String> own = OutlineQualityCheck.owned(chapter.getMaterialKeys(), brief, owner, index - 1);
            if (own.isEmpty()) {
                // Only material of earlier chapters: the quality check drops the chapter; no section call for it.
                chapters.add(chapter.withSections(FallbackOutlinePlan.sections(brief, chapter)));
                continue;
            }
            PlannedChapter owning = new PlannedChapter(chapter.getTitle(), chapter.getPurpose(), own,
                    chapter.getConceptKeys(), null, chapter.isUntitled());
            List<PlannedSection> sections = askSections(brief, owning, monitor);
            if (sections == null) {
                notes.add("The sections of \"" + chapter.getTitle() + "\" were not generated; code split the chapter "
                        + "by its material.");
                sections = FallbackOutlinePlan.sections(brief, owning);
            }
            chapters.add(chapter.withSections(sections));
        }
        return plan.withChapters(chapters);
    }

    private static PlannedChapter sameMaterial(OutlinePlan previous, PlannedChapter chapter) {
        for (PlannedChapter old : previous.getChapters()) {
            if (old.sameMaterial(chapter) && !old.getSections().isEmpty()) {
                return old;
            }
        }
        return null;
    }

    private OutlinePlan askChapters(String prompt, SynthesisBrief brief, String step, ClusteringMonitor monitor) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(ChapterPlanContract.SYSTEM));
        messages.add(ChatMessage.user(prompt));
        String schema = ChapterPlanContract.schema(brief);
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancel(monitor);
            MainModelChatResult result = chat.completeJson(messages, settings.getTemperature(),
                    settings.getPlanAnswerTokens(), schema);
            if (!result.isOk()) {
                telemetry.call(step, chat.modelName(), settings.getPlanAnswerTokens(), attempt, false,
                        result.getDetail());
                return null;
            }
            try {
                OutlinePlan plan = ChapterPlanContract.read(result.getText(), brief);
                telemetry.call(step, chat.modelName(), settings.getPlanAnswerTokens(), attempt, true,
                        plan.getChapters().size() + " chapters");
                return plan;
            } catch (PlanAnswerException refused) {
                telemetry.call(step, chat.modelName(), settings.getPlanAnswerTokens(), attempt, false,
                        refused.getMessage());
                repair(messages, result.getText(), refused);
            }
        }
        return null;
    }

    private List<PlannedSection> askSections(SynthesisBrief brief, PlannedChapter chapter, ClusteringMonitor monitor) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SectionPlanContract.SYSTEM));
        messages.add(ChatMessage.user(SectionPlanContract.prompt(brief, chapter)));
        String schema = SectionPlanContract.schema(brief, chapter);
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancel(monitor);
            MainModelChatResult result = chat.completeJson(messages, settings.getTemperature(),
                    settings.getSectionAnswerTokens(), schema);
            if (!result.isOk()) {
                telemetry.call("sections", chat.modelName(), settings.getSectionAnswerTokens(), attempt, false,
                        result.getDetail());
                return null;
            }
            try {
                List<PlannedSection> sections = SectionPlanContract.read(result.getText(), brief, chapter);
                telemetry.call("sections", chat.modelName(), settings.getSectionAnswerTokens(), attempt, true,
                        sections.size() + " sections for \"" + chapter.getTitle() + "\"");
                return sections;
            } catch (PlanAnswerException refused) {
                telemetry.call("sections", chat.modelName(), settings.getSectionAnswerTokens(), attempt, false,
                        refused.getMessage());
                repair(messages, result.getText(), refused);
            }
        }
        return null;
    }

    private static void repair(List<ChatMessage> messages, String answer, PlanAnswerException refused) {
        messages.add(ChatMessage.assistant(answer == null ? "" : answer));
        StringBuilder text = new StringBuilder("Your answer was rejected:\n");
        for (String error : refused.errors()) {
            text.append("- ").append(error).append('\n');
        }
        text.append("Answer again with the corrected JSON object only.");
        messages.add(ChatMessage.user(text.toString()));
    }

    private static boolean hasBlocking(List<PlanFinding> findings) {
        return !blocking(findings).isEmpty();
    }

    private static List<PlanFinding> blocking(List<PlanFinding> findings) {
        List<PlanFinding> result = new ArrayList<PlanFinding>();
        for (PlanFinding finding : findings) {
            if (finding.isBlocking()) {
                result.add(finding);
            }
        }
        return result;
    }

    private static void checkCancel(ClusteringMonitor monitor) {
        if (monitor.isCancelled()) {
            throw new ClusteringMonitor.ClusteringCancelledException();
        }
    }
}
