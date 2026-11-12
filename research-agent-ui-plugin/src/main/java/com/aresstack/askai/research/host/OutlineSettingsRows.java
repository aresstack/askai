package com.aresstack.askai.research.host;

import com.aresstack.askai.plugin.api.service.WorkspaceStateStore;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;
import com.aresstack.askai.research.outline.generate.OutlineProvenanceMerge;
import com.aresstack.askai.research.outline.synthesis.OutlineLengthSettings;
import com.aresstack.askai.research.outline.synthesis.OutlineSynthesisSettings;
import com.aresstack.comiccontrols.control.ComicControls;
import com.aresstack.comiccontrols.control.ComicForm;

import javax.swing.JTextField;

/**
 * Phase 3: the outline sections of the "Runtime" settings tab: the shared topic analysis that the Sources phase runs and the outline is built
 * on ({@link EvidenceAnalysisSettings}, {@code research.evidence.*}), the synthesis on {@code phase.outline} and the
 * book length ({@link OutlineGenerationSettings}, {@code research.outline.*}). Each field saves on Enter and on focus
 * loss; an invalid value is reset to the stored one. The values are read at the next analysis or generation.
 * They sit on the Runtime tab, beside the research and writer sections, so the dialog's tab row stays one line
 * at the narrowest dialog width (a second tab row takes the height the Search tab's engine table needs).
 */
final class OutlineSettingsRows {

    private OutlineSettingsRows() {
    }

    /** The sections "Topic analysis", "Outline synthesis" and "Book length", in this order. */
    static ComicForm[] forms(WorkspaceStateStore store) {
        ComicForm analysis = ComicForm.titled("Topic analysis");
        analysis.row("Minimum distinct passages", positive(store, EvidenceAnalysisSettings.KEY_MINIMUM_PASSAGES,
                TopicAnalysisSettings.DEFAULT_MINIMUM_PASSAGES,
                "Distinct passages (near-duplicates count once) a topic or concept node needs as material."));
        analysis.row("Minimum sources", positive(store, EvidenceAnalysisSettings.KEY_MINIMUM_SOURCES,
                TopicAnalysisSettings.DEFAULT_MINIMUM_SOURCES,
                "Independent sources that must confirm a topic or concept node."));
        analysis.row("Minimum domains", positive(store, EvidenceAnalysisSettings.KEY_MINIMUM_DOMAINS,
                TopicAnalysisSettings.DEFAULT_MINIMUM_DOMAINS,
                "Different websites among those sources; pages of one site confirm only once."));
        analysis.row("Covered sub-themes (share)", decimal(store, EvidenceAnalysisSettings.KEY_MINIMUM_CHILD_COVERAGE,
                TopicAnalysisSettings.DEFAULT_MINIMUM_CHILD_COVERAGE,
                "Breadth: the share of a concept node's sub-themes that must be covered for the node to count as "
                        + "covered."));
        analysis.row("Largest sub-theme (share)", decimal(store, EvidenceAnalysisSettings.KEY_MAXIMUM_DOMINANT_SHARE,
                TopicAnalysisSettings.DEFAULT_MAXIMUM_DOMINANT_SHARE,
                "Balance: a node whose largest sub-theme holds more of the material than this is one-sided."));
        analysis.row("Major topic (share)", decimal(store, EvidenceAnalysisSettings.KEY_MAJOR_TOPIC_SHARE,
                TopicAnalysisSettings.DEFAULT_MAJOR_TOPIC_SHARE,
                "A topic with at least this share of the distinct material is a major area."));
        analysis.row("Topic distance", decimal(store, EvidenceAnalysisSettings.KEY_TOPIC_DISTANCE,
                TopicAnalysisSettings.DEFAULT_TOPIC_DISTANCE,
                "Passages closer than this (cosine distance) form one research topic; it depends on the embedding "
                        + "model. A topic holding the material of two independent concept nodes is split anyway."));
        analysis.row("Duplicate distance", decimal(store, EvidenceAnalysisSettings.KEY_DUPLICATE_DISTANCE,
                TopicAnalysisSettings.DEFAULT_DUPLICATE_DISTANCE,
                "Passages closer than this (cosine distance) say the same and count as one distinct passage."));
        analysis.row("Sub-topic distance", decimal(store, EvidenceAnalysisSettings.KEY_SUBTOPIC_DISTANCE,
                TopicAnalysisSettings.DEFAULT_SUBTOPIC_DISTANCE,
                "Passages of a topic closer than this belong to one sub-topic."));
        analysis.row("Topic group distance", decimal(store, EvidenceAnalysisSettings.KEY_TOPIC_GROUP_DISTANCE,
                TopicAnalysisSettings.DEFAULT_TOPIC_GROUP_DISTANCE,
                "Topics closer than this form one group of related topics."));
        analysis.row("Concept similarity", decimal(store, EvidenceAnalysisSettings.KEY_CONCEPT_THRESHOLD,
                TopicAnalysisSettings.DEFAULT_CONCEPT_THRESHOLD,
                "A topic belongs to a concept node from this similarity on ..."));
        analysis.row("Concept margin", decimal(store, EvidenceAnalysisSettings.KEY_CONCEPT_MARGIN,
                TopicAnalysisSettings.DEFAULT_CONCEPT_MARGIN,
                "... and only with this lead over the second-best node."));
        analysis.row("Representative passages", positive(store, EvidenceAnalysisSettings.KEY_REPRESENTATIVES,
                TopicAnalysisSettings.DEFAULT_REPRESENTATIVES, "Excerpts per topic that the model sees."));
        analysis.row("Key terms", positive(store, EvidenceAnalysisSettings.KEY_KEY_TERMS,
                EvidenceAnalysisSettings.DEFAULT_KEY_TERMS, "Key terms per concept node or research area."));
        analysis.row("First analysis at passages", positive(store, EvidenceAnalysisSettings.KEY_MINIMUM_CORPUS,
                EvidenceAnalysisSettings.DEFAULT_MINIMUM_CORPUS,
                "The Sources phase analyses the research from this many passages on ..."));
        analysis.row("Again after new passages", positive(store, EvidenceAnalysisSettings.KEY_EVERY_NEW_PASSAGES,
                EvidenceAnalysisSettings.DEFAULT_EVERY_NEW_PASSAGES,
                "... and again after this many new passages, and at the end of every research cycle."));

        ComicForm synthesis = ComicForm.titled("Outline synthesis");
        synthesis.row("Revision rounds", nonNegative(store, OutlineGenerationSettings.KEY_REVISION_ROUNDS,
                OutlineSynthesisSettings.DEFAULT_REVISION_ROUNDS,
                "How often the model may revise the chapters after the quality check found problems."));
        synthesis.row("Topics in the prompt", positive(store, OutlineGenerationSettings.KEY_MAX_PROMPT_TOPICS,
                OutlineSynthesisSettings.DEFAULT_MAX_PROMPT_TOPICS,
                "Research topics the model sees; the others are placed by code in the chapter of their group."));
        synthesis.row("Excerpt characters", nonNegative(store, OutlineGenerationSettings.KEY_EXCERPT_CHARS,
                OutlineSynthesisSettings.DEFAULT_EXCERPT_CHARS, "Length of each excerpt in the prompt."));
        synthesis.row("Concept depth", positive(store, OutlineGenerationSettings.KEY_REQUIREMENT_DEPTH,
                OutlineSynthesisSettings.DEFAULT_REQUIREMENT_DEPTH,
                "Concept levels below the book frame that count as requirements of the outline."));
        synthesis.row("Chapter plan tokens", positive(store, OutlineGenerationSettings.KEY_PLAN_ANSWER_TOKENS,
                OutlineSynthesisSettings.DEFAULT_PLAN_ANSWER_TOKENS, "Answer budget of the chapter plan call."));
        synthesis.row("Section plan tokens", positive(store, OutlineGenerationSettings.KEY_SECTION_ANSWER_TOKENS,
                OutlineSynthesisSettings.DEFAULT_SECTION_ANSWER_TOKENS, "Answer budget of each section call."));
        synthesis.row("Call timeout (s)", positive(store, OutlineGenerationSettings.KEY_CALL_TIMEOUT_SECONDS,
                OutlineSynthesisSettings.DEFAULT_CALL_TIMEOUT_SECONDS, "Timeout of one synthesis call."));
        synthesis.row("Temperature", decimal(store, OutlineGenerationSettings.KEY_TEMPERATURE,
                OutlineSynthesisSettings.DEFAULT_TEMPERATURE, "Temperature of the synthesis calls."));

        ComicForm length = ComicForm.titled("Book length");
        length.row("Words per page", positive(store, OutlineGenerationSettings.KEY_WORDS_PER_PAGE,
                OutlineLengthSettings.DEFAULT_WORDS_PER_PAGE,
                "How a length in pages becomes words when the budgets are spread."));
        length.row("Minimum section words", positive(store, OutlineGenerationSettings.KEY_MIN_SECTION_WORDS,
                OutlineLengthSettings.DEFAULT_MIN_SECTION_WORDS,
                "Shorter sections are not worth their own heading; also the base without a book length."));
        length.row("Maximum section words", positive(store, OutlineGenerationSettings.KEY_MAX_SECTION_WORDS,
                OutlineLengthSettings.DEFAULT_MAX_SECTION_WORDS,
                "Longer sections should be split; also the cap without a book length."));
        length.row("Words per distinct passage", nonNegative(store,
                OutlineGenerationSettings.KEY_WORDS_PER_DISTINCT_PASSAGE,
                OutlineLengthSettings.DEFAULT_WORDS_PER_DISTINCT_PASSAGE,
                "Without a book length: words per distinct passage on top of the base."));
        length.row("Word step", positive(store, OutlineGenerationSettings.KEY_WORD_STEP,
                OutlineLengthSettings.DEFAULT_WORD_STEP, "Word targets are rounded to this step."));
        length.row("Regeneration overlap", decimal(store, OutlineGenerationSettings.KEY_MERGE_OVERLAP,
                OutlineProvenanceMerge.DEFAULT_MIN_OVERLAP,
                "A regenerated chapter or section takes the place of a stored one from this passage overlap on "
                        + "(0 to 1); edited entries are always kept."));

        return new ComicForm[]{analysis, synthesis, length};
    }

    private interface Parser {
        /** The value to store, or {@code null} when the text is no valid value. */
        String parse(String text);
    }

    private static JTextField positive(WorkspaceStateStore store, String key, int fallback, String tooltip) {
        return field(store, key, String.valueOf(ResearchRuntimeSettings.loadPositiveInt(store, key, fallback)),
                tooltip, new Parser() {
                    public String parse(String text) {
                        try {
                            int value = Integer.parseInt(text);
                            return value > 0 ? String.valueOf(value) : null;
                        } catch (NumberFormatException invalid) {
                            return null;
                        }
                    }
                });
    }

    private static JTextField nonNegative(WorkspaceStateStore store, String key, int fallback, String tooltip) {
        return field(store, key, String.valueOf(ResearchRuntimeSettings.loadNonNegativeInt(store, key, fallback)),
                tooltip, new Parser() {
                    public String parse(String text) {
                        try {
                            int value = Integer.parseInt(text);
                            return value >= 0 ? String.valueOf(value) : null;
                        } catch (NumberFormatException invalid) {
                            return null;
                        }
                    }
                });
    }

    private static JTextField decimal(WorkspaceStateStore store, String key, double fallback, String tooltip) {
        return field(store, key, String.valueOf(ResearchRuntimeSettings.loadDouble(store, key, fallback)), tooltip,
                new Parser() {
                    public String parse(String text) {
                        try {
                            double value = Double.parseDouble(text.replace(',', '.'));
                            return value >= 0 && !Double.isNaN(value) && !Double.isInfinite(value)
                                    ? String.valueOf(value) : null;
                        } catch (NumberFormatException invalid) {
                            return null;
                        }
                    }
                });
    }

    private static JTextField field(final WorkspaceStateStore store, final String key, final String shown,
                                    String tooltip, final Parser parser) {
        final JTextField field = ComicControls.textField(6);
        field.setText(shown);
        field.setToolTipText(tooltip);
        final String[] stored = {shown};
        final Runnable persist = new Runnable() {
            public void run() {
                String value = parser.parse(field.getText().trim());
                if (value == null) {
                    field.setText(stored[0]);
                    return;
                }
                if (store != null) {
                    store.put(key, value);
                }
                stored[0] = value;
                field.setText(value);
            }
        };
        field.addActionListener(e -> persist.run());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent event) {
                persist.run();
            }
        });
        return field;
    }
}
