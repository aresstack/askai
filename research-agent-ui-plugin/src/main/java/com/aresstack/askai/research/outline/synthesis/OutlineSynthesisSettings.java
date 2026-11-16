package com.aresstack.askai.research.outline.synthesis;

/**
 * Phase 3b: the knobs of the outline synthesis on {@code phase.outline}, in one immutable value with one default
 * origin ({@link #defaults()}). The host shows each as a setting; model defaults are not part of this.
 */
public final class OutlineSynthesisSettings {

    /** Review and revise rounds after the first plan; 0 = check only, never revise. */
    public static final int DEFAULT_REVISION_ROUNDS = 2;
    /** Research topics listed in the prompt (largest distinct material first); the rest is placed by code. */
    public static final int DEFAULT_MAX_PROMPT_TOPICS = 40;
    /** Characters of one excerpt in the prompt. */
    public static final int DEFAULT_EXCERPT_CHARS = 240;
    /** Concept levels below the book frame that count as requirements (1 = main themes only). */
    public static final int DEFAULT_REQUIREMENT_DEPTH = 2;
    /** Answer budget of the chapter plan call, in tokens. */
    public static final int DEFAULT_PLAN_ANSWER_TOKENS = 2048;
    /** Answer budget of one chapter's section call, in tokens. */
    public static final int DEFAULT_SECTION_ANSWER_TOKENS = 1024;
    /** The timeout of one synthesis call, in seconds. */
    public static final int DEFAULT_CALL_TIMEOUT_SECONDS = 300;
    /** Sampling temperature of the synthesis calls (the label and brief calls use the same). */
    public static final double DEFAULT_TEMPERATURE = 0.2;

    private final int revisionRounds;
    private final int maxPromptTopics;
    private final int excerptChars;
    private final int requirementDepth;
    private final int planAnswerTokens;
    private final int sectionAnswerTokens;
    private final int callTimeoutSeconds;
    private final double temperature;

    public OutlineSynthesisSettings(int revisionRounds, int maxPromptTopics, int excerptChars, int requirementDepth,
                                    int planAnswerTokens, int sectionAnswerTokens, int callTimeoutSeconds,
                                    double temperature) {
        this.revisionRounds = Math.max(0, revisionRounds);
        this.maxPromptTopics = Math.max(1, maxPromptTopics);
        this.excerptChars = Math.max(0, excerptChars);
        this.requirementDepth = Math.max(1, requirementDepth);
        this.planAnswerTokens = Math.max(1, planAnswerTokens);
        this.sectionAnswerTokens = Math.max(1, sectionAnswerTokens);
        this.callTimeoutSeconds = Math.max(1, callTimeoutSeconds);
        this.temperature = Math.max(0, temperature);
    }

    /** THE single default origin. */
    public static OutlineSynthesisSettings defaults() {
        return new OutlineSynthesisSettings(DEFAULT_REVISION_ROUNDS, DEFAULT_MAX_PROMPT_TOPICS, DEFAULT_EXCERPT_CHARS,
                DEFAULT_REQUIREMENT_DEPTH, DEFAULT_PLAN_ANSWER_TOKENS, DEFAULT_SECTION_ANSWER_TOKENS,
                DEFAULT_CALL_TIMEOUT_SECONDS, DEFAULT_TEMPERATURE);
    }

    public int getRevisionRounds() {
        return revisionRounds;
    }

    public int getMaxPromptTopics() {
        return maxPromptTopics;
    }

    public int getExcerptChars() {
        return excerptChars;
    }

    public int getRequirementDepth() {
        return requirementDepth;
    }

    public int getPlanAnswerTokens() {
        return planAnswerTokens;
    }

    public int getSectionAnswerTokens() {
        return sectionAnswerTokens;
    }

    public int getCallTimeoutSeconds() {
        return callTimeoutSeconds;
    }

    public double getTemperature() {
        return temperature;
    }
}
