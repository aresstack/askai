package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plan PR 24: labels and briefs for the outline on the slots {@code phase.outline.labels} and
 * {@code phase.outline.briefs} (thinking off; the chats are bound by the caller). Every answer is checked in
 * code; a refused answer is sent back once with the error list; after that the default title or the default brief
 * stays and the caller shows "Brief not generated" with a retry. The worker never writes the store.
 */
public final class OutlineBriefWorker {

    /** The telemetry sink; a no-op by default. */
    public interface Telemetry {
        Telemetry NONE = new Telemetry() {
            @Override
            public void call(BriefCallTelemetry call) {
            }
        };

        void call(BriefCallTelemetry call);
    }

    /** A chapter's brief run: the chapter with new briefs, or the unchanged chapter and the reason. */
    public static final class ChapterBriefs {
        private final OutlineChapter chapter;
        private final String failure;

        private ChapterBriefs(OutlineChapter chapter, String failure) {
            this.chapter = chapter;
            this.failure = failure;
        }

        public OutlineChapter getChapter() {
            return chapter;
        }

        public boolean isGenerated() {
            return failure == null;
        }

        /** Why the default brief stayed ("Brief not generated: ..."), or {@code null}. */
        public String getFailure() {
            return failure;
        }
    }

    /**
     * The label role on {@code phase.outline.labels} (plan PR 24). The prompts are built per call by
     * {@link LabelContract}, hence {@code CALLER}; the answer is JSON checked by that contract. Declared here so
     * the role has exactly one home and cannot be built without a prompt source again (issue #152).
     */
    public static final ResearchRole LABELS_ROLE = ResearchRole.builder("outline-labels",
                    ResearchModelSlots.PHASE_OUTLINE_LABELS)
            .purpose("plan PR 24: titles and one-line summaries for outline chapters and sections")
            .promptFromCaller()
            .output(RoleOutput.json("LabelContract"))
            .thinking(ThinkingMode.OFF)
            .build();

    /** The brief role on {@code phase.outline.briefs} (plan PR 24); prompts from {@link BriefContract}. */
    public static final ResearchRole BRIEFS_ROLE = ResearchRole.builder("outline-briefs",
                    ResearchModelSlots.PHASE_OUTLINE_BRIEFS)
            .purpose("plan PR 24: section briefs for one outline chapter")
            .promptFromCaller()
            .output(RoleOutput.json("BriefContract"))
            .thinking(ThinkingMode.OFF)
            .build();

    /** The two roles of this worker, for the role inventory tests. */
    public static List<ResearchRole> roles() {
        return Collections.unmodifiableList(Arrays.asList(LABELS_ROLE, BRIEFS_ROLE));
    }

    static final String NOT_GENERATED = "Brief not generated";
    private static final double TEMPERATURE = 0.2;

    private final MainModelChat labels;
    private final MainModelChat briefs;
    private final Telemetry telemetry;

    public OutlineBriefWorker(MainModelChat labels, MainModelChat briefs, Telemetry telemetry) {
        if (labels == null || briefs == null) {
            throw new IllegalArgumentException("labels and briefs chats are required");
        }
        this.labels = labels;
        this.briefs = briefs;
        this.telemetry = telemetry == null ? Telemetry.NONE : telemetry;
    }

    /**
     * Labels one candidate cluster or untitled section from its representative passages.
     * @return the label, or {@code null} when no valid label came back (the caller keeps the default title)
     */
    public OutlineLabel label(BriefContext context, String target, String chapterTitle, List<String> excerpts,
                              String comment) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(LabelContract.SYSTEM));
        messages.add(ChatMessage.user(LabelContract.prompt(context, chapterTitle, excerpts, comment)));
        for (int attempt = 0; attempt < 2; attempt++) {
            MainModelChatResult result = labels.completeJson(messages, TEMPERATURE, LabelContract.BUDGET,
                    LabelContract.SCHEMA);
            if (!result.isOk()) {
                telemetry.call(new BriefCallTelemetry(labels.modelName(), BriefCallTelemetry.Kind.LABEL, target,
                        LabelContract.BUDGET, attempt, false));
                return null;
            }
            try {
                OutlineLabel label = LabelContract.read(result.getText(), chapterTitle);
                telemetry.call(new BriefCallTelemetry(labels.modelName(), BriefCallTelemetry.Kind.LABEL, target,
                        LabelContract.BUDGET, attempt, true));
                return label;
            } catch (BriefAnswerException refused) {
                telemetry.call(new BriefCallTelemetry(labels.modelName(), BriefCallTelemetry.Kind.LABEL, target,
                        LabelContract.BUDGET, attempt, false));
                repair(messages, result.getText(), refused);
            }
        }
        return null;
    }

    /**
     * Briefs every section of {@code chapter}. Word targets stay as they are. On a model failure or a second
     * invalid answer the chapter comes back unchanged with "Brief not generated".
     */
    public ChapterBriefs brief(BriefContext context, OutlineChapter chapter, String comment) {
        List<OutlineSection> sections = chapter.getSections();
        if (sections.isEmpty()) {
            return new ChapterBriefs(chapter, null);
        }
        List<OutlineSection> briefed = new ArrayList<OutlineSection>();
        for (List<OutlineSection> batch : BriefContract.batches(sections)) {
            Map<Integer, OutlineBrief> answer = briefBatch(context, chapter, batch, comment);
            if (answer == null) {
                return new ChapterBriefs(chapter, NOT_GENERATED + " for \"" + chapter.getTitle() + "\".");
            }
            for (int i = 0; i < batch.size(); i++) {
                OutlineSection section = batch.get(i);
                OutlineBrief brief = answer.get(i + 1);
                briefed.add(section.withBrief(new OutlineBrief(brief.getPurpose(), brief.getKeyPoints(),
                        section.getBrief().getWordTarget())));
            }
        }
        return new ChapterBriefs(chapter.withSections(briefed), null);
    }

    private Map<Integer, OutlineBrief> briefBatch(BriefContext context, OutlineChapter chapter,
                                                  List<OutlineSection> batch, String comment) {
        int budget = BriefContract.budget(batch.size());
        String schema = BriefContract.schema(batch.size());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(BriefContract.SYSTEM));
        messages.add(ChatMessage.user(BriefContract.prompt(context, chapter, batch, comment)));
        for (int attempt = 0; attempt < 2; attempt++) {
            MainModelChatResult result = briefs.completeJson(messages, TEMPERATURE, budget, schema);
            if (!result.isOk()) {
                telemetry.call(new BriefCallTelemetry(briefs.modelName(), BriefCallTelemetry.Kind.BRIEF,
                        chapter.getId(), budget, attempt, false));
                return null;
            }
            try {
                Map<Integer, OutlineBrief> answer = BriefContract.read(result.getText(), batch.size());
                telemetry.call(new BriefCallTelemetry(briefs.modelName(), BriefCallTelemetry.Kind.BRIEF,
                        chapter.getId(), budget, attempt, true));
                return answer;
            } catch (BriefAnswerException refused) {
                telemetry.call(new BriefCallTelemetry(briefs.modelName(), BriefCallTelemetry.Kind.BRIEF,
                        chapter.getId(), budget, attempt, false));
                repair(messages, result.getText(), refused);
            }
        }
        return null;
    }

    private static void repair(List<ChatMessage> messages, String answer, BriefAnswerException refused) {
        messages.add(ChatMessage.assistant(answer == null ? "" : answer));
        StringBuilder text = new StringBuilder("Your answer was rejected:\n");
        for (String error : refused.errors()) {
            text.append("- ").append(error).append('\n');
        }
        text.append("Answer again with the corrected JSON object only.");
        messages.add(ChatMessage.user(text.toString()));
    }
}
