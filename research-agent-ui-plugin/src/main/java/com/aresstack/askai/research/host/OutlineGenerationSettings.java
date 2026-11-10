package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.outline.generate.OutlineProvenanceMerge;
import com.aresstack.askai.research.outline.synthesis.OutlineLengthSettings;
import com.aresstack.askai.research.outline.synthesis.OutlineSynthesisSettings;

/**
 * Phase 3: the settings of "Generate outline": the synthesis on {@code phase.outline} (revision rounds, prompt
 * limits, answer budgets, timeout, temperature), the book length and word budgets, and the passage overlap at which
 * a regenerated entry takes a stored entry's place. Each value is read from the session's state store under
 * {@code research.outline.*} with the default of its owner; the Outline settings group edits them. Immutable.
 */
public final class OutlineGenerationSettings {

    public static final String KEY_REVISION_ROUNDS = "research.outline.revisionRounds";
    public static final String KEY_MAX_PROMPT_TOPICS = "research.outline.maxPromptTopics";
    public static final String KEY_EXCERPT_CHARS = "research.outline.excerptChars";
    public static final String KEY_REQUIREMENT_DEPTH = "research.outline.requirementDepth";
    public static final String KEY_PLAN_ANSWER_TOKENS = "research.outline.planAnswerTokens";
    public static final String KEY_SECTION_ANSWER_TOKENS = "research.outline.sectionAnswerTokens";
    public static final String KEY_CALL_TIMEOUT_SECONDS = "research.outline.callTimeoutSeconds";
    public static final String KEY_TEMPERATURE = "research.outline.temperature";
    public static final String KEY_WORDS_PER_PAGE = "research.outline.wordsPerPage";
    public static final String KEY_MIN_SECTION_WORDS = "research.outline.minSectionWords";
    public static final String KEY_MAX_SECTION_WORDS = "research.outline.maxSectionWords";
    public static final String KEY_WORDS_PER_DISTINCT_PASSAGE = "research.outline.wordsPerDistinctPassage";
    public static final String KEY_WORD_STEP = "research.outline.wordStep";
    public static final String KEY_MERGE_OVERLAP = "research.outline.mergeOverlap";

    private final OutlineSynthesisSettings synthesis;
    private final OutlineLengthSettings length;
    private final double mergeOverlap;

    public OutlineGenerationSettings(OutlineSynthesisSettings synthesis, OutlineLengthSettings length,
                                     double mergeOverlap) {
        this.synthesis = synthesis == null ? OutlineSynthesisSettings.defaults() : synthesis;
        this.length = length == null ? OutlineLengthSettings.defaults() : length;
        this.mergeOverlap = Math.min(1.0, Math.max(0.0, mergeOverlap));
    }

    public static OutlineGenerationSettings defaults() {
        return new OutlineGenerationSettings(OutlineSynthesisSettings.defaults(), OutlineLengthSettings.defaults(),
                OutlineProvenanceMerge.DEFAULT_MIN_OVERLAP);
    }

    /** The stored values; an unreadable or inconsistent value falls back to its default. */
    public static OutlineGenerationSettings load(WorkspaceStateStore store) {
        OutlineSynthesisSettings synthesis = new OutlineSynthesisSettings(
                ResearchRuntimeSettings.loadNonNegativeInt(store, KEY_REVISION_ROUNDS,
                        OutlineSynthesisSettings.DEFAULT_REVISION_ROUNDS),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_MAX_PROMPT_TOPICS,
                        OutlineSynthesisSettings.DEFAULT_MAX_PROMPT_TOPICS),
                ResearchRuntimeSettings.loadNonNegativeInt(store, KEY_EXCERPT_CHARS,
                        OutlineSynthesisSettings.DEFAULT_EXCERPT_CHARS),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_REQUIREMENT_DEPTH,
                        OutlineSynthesisSettings.DEFAULT_REQUIREMENT_DEPTH),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_PLAN_ANSWER_TOKENS,
                        OutlineSynthesisSettings.DEFAULT_PLAN_ANSWER_TOKENS),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_SECTION_ANSWER_TOKENS,
                        OutlineSynthesisSettings.DEFAULT_SECTION_ANSWER_TOKENS),
                ResearchRuntimeSettings.loadPositiveInt(store, KEY_CALL_TIMEOUT_SECONDS,
                        OutlineSynthesisSettings.DEFAULT_CALL_TIMEOUT_SECONDS),
                ResearchRuntimeSettings.loadDouble(store, KEY_TEMPERATURE,
                        OutlineSynthesisSettings.DEFAULT_TEMPERATURE));
        OutlineLengthSettings length;
        try {
            length = new OutlineLengthSettings(
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_WORDS_PER_PAGE,
                            OutlineLengthSettings.DEFAULT_WORDS_PER_PAGE),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_MIN_SECTION_WORDS,
                            OutlineLengthSettings.DEFAULT_MIN_SECTION_WORDS),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_MAX_SECTION_WORDS,
                            OutlineLengthSettings.DEFAULT_MAX_SECTION_WORDS),
                    ResearchRuntimeSettings.loadNonNegativeInt(store, KEY_WORDS_PER_DISTINCT_PASSAGE,
                            OutlineLengthSettings.DEFAULT_WORDS_PER_DISTINCT_PASSAGE),
                    ResearchRuntimeSettings.loadPositiveInt(store, KEY_WORD_STEP,
                            OutlineLengthSettings.DEFAULT_WORD_STEP));
        } catch (IllegalArgumentException inconsistent) {
            System.err.println("[outline-settings] length settings ignored: " + inconsistent.getMessage());
            length = OutlineLengthSettings.defaults();
        }
        return new OutlineGenerationSettings(synthesis, length, ResearchRuntimeSettings.loadDouble(store,
                KEY_MERGE_OVERLAP, OutlineProvenanceMerge.DEFAULT_MIN_OVERLAP));
    }

    public OutlineSynthesisSettings getSynthesis() {
        return synthesis;
    }

    public OutlineLengthSettings getLength() {
        return length;
    }

    public double getMergeOverlap() {
        return mergeOverlap;
    }
}
