package com.aresstack.askai.research.benchmark.writer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** One written sample of the A/B: what a model wrote for one item, with time, tokens and the measurements. */
public final class WriterAbSample {

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
}
