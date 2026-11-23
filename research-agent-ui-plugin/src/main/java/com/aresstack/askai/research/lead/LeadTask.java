package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.memory.LeadDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * One task of the next round for the search planner (component 3): which questions to search for, with which
 * strategy, what the lead wants found (Vorgabe) and why. The planner turns it into one to three searches. Immutable.
 */
public final class LeadTask {

    /** Who set the task: the model, or a guardrail rule (a free slot, a failed call). */
    public enum Origin { MODELL, REGEL }

    private final int round;
    private final LeadDecision.Target target;
    private final String targetId;
    private final List<String> questionIds;
    private final SearchStrategy strategy;
    private final String instruction;
    private final String reason;
    private final Origin origin;

    /**
     * @param target      {@code FRAGE} for one question, {@code THEMA} for questions of one topic
     * @param questionIds the questions the searches serve, at least one
     */
    public LeadTask(int round, LeadDecision.Target target, String targetId, List<String> questionIds,
                    SearchStrategy strategy, String instruction, String reason, Origin origin) {
        if (target != LeadDecision.Target.FRAGE && target != LeadDecision.Target.THEMA) {
            throw new IllegalArgumentException("a task is about a question or a topic");
        }
        this.round = Math.max(1, round);
        this.target = target;
        this.targetId = clean(targetId);
        this.questionIds = ids(questionIds);
        this.strategy = strategy == null ? SearchStrategy.STANDARD : strategy;
        this.instruction = clean(instruction);
        this.reason = clean(reason);
        this.origin = origin == null ? Origin.REGEL : origin;
        if (this.targetId.isEmpty() || this.questionIds.isEmpty()) {
            throw new IllegalArgumentException("a task needs its target and at least one question");
        }
    }

    public int getRound() {
        return round;
    }

    public LeadDecision.Target getTarget() {
        return target;
    }

    public String getTargetId() {
        return targetId;
    }

    /** The questions the searches of this task serve, in order. */
    public List<String> getQuestionIds() {
        return questionIds;
    }

    public SearchStrategy getStrategy() {
        return strategy;
    }

    /** What the lead wants found, for the planner; {@code ""} when it gave nothing. */
    public String getInstruction() {
        return instruction;
    }

    public String getReason() {
        return reason;
    }

    public Origin getOrigin() {
        return origin;
    }

    @Override
    public String toString() {
        return "R" + round + " " + target + " " + targetId + " " + questionIds + " " + strategy.wire() + " (" + origin
                + ")";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> ids(List<String> values) {
        LinkedHashSet<String> kept = new LinkedHashSet<String>();
        if (values != null) {
            for (String value : values) {
                String id = clean(value);
                if (!id.isEmpty()) {
                    kept.add(id);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(kept));
    }
}
