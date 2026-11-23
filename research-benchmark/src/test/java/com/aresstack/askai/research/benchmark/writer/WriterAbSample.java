package com.aresstack.askai.research.benchmark.writer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One written sample of the A/B: what an arm (a model, or a model with a pipeline variant) wrote for one item, with
 * time, tokens and the measurements. {@link #getModel()} is the arm's label, the identity the blind sheet hides.
 */
public final class WriterAbSample {

    /** One section of the sample, with the pack it was written from and its quality figures. */
    public static final class Section {
        public final String id;
        public final String text;
        public final java.util.List<com.aresstack.askai.research.runtime.writer.EvidencePassage> passages;
        public final int gaps;
        public final WriterQualityMetrics quality;
        public final CitationSupportJudge.Result support;

        public Section(String id, String text,
                       java.util.List<com.aresstack.askai.research.runtime.writer.EvidencePassage> passages, int gaps,
                       WriterQualityMetrics quality, CitationSupportJudge.Result support) {
            this.id = id;
            this.text = text == null ? "" : text;
            this.passages = passages;
            this.gaps = gaps;
            this.quality = quality;
            this.support = support == null ? CitationSupportJudge.Result.none() : support;
        }
    }

    private java.util.List<Section> sections = java.util.Collections.emptyList();
    private double chapterRepetition = -1;
    private int run = 1;

    private final String item;
    private final String model;
    private final String text;
    private final double seconds;
    private final long tokens;
    private final WriterSampleMetrics metrics;
    private final Map<String, Integer> qualityCounts;

    /**
     * @param qualityCounts the counts of the quality report (PR 30) for this sample, e.g. unresolved citations,
     *                      writer gaps; empty while the report is not available
     */
    public WriterAbSample(String item, String model, String text, double seconds, long tokens,
                          WriterSampleMetrics metrics, Map<String, Integer> qualityCounts) {
        this.item = item;
        this.model = model;
        this.text = text == null ? "" : text;
        this.seconds = seconds;
        this.tokens = tokens;
        this.metrics = metrics;
        this.qualityCounts = Collections.unmodifiableMap(qualityCounts == null
                ? new LinkedHashMap<String, Integer>() : new LinkedHashMap<String, Integer>(qualityCounts));
    }

    public String getItem() {
        return item;
    }

    public String getModel() {
        return model;
    }

    public String getText() {
        return text;
    }

    public double getSeconds() {
        return seconds;
    }

    public long getTokens() {
        return tokens;
    }

    public WriterSampleMetrics getMetrics() {
        return metrics;
    }

    public Map<String, Integer> getQualityCounts() {
        return qualityCounts;
    }

    /** This sample with its sections, the chapter-wide repetition (-1 = not measured) and its run number. */
    public WriterAbSample with(java.util.List<Section> sections, double chapterRepetition, int run) {
        this.sections = java.util.Collections.unmodifiableList(new java.util.ArrayList<Section>(sections));
        this.chapterRepetition = chapterRepetition;
        this.run = run;
        return this;
    }

    public java.util.List<Section> getSections() {
        return sections;
    }

    /** Repeated words over the whole sample (all sections in order); -1 when not measured. */
    public double getChapterRepetition() {
        return chapterRepetition;
    }

    public int getRun() {
        return run;
    }
}
