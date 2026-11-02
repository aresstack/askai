package com.aresstack.askai.speech;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-dictation technical facts for the "Technical details" log. Deliberately excludes the audio data,
 * the transcript text, the system prompt and any credentials — only environment/measurement facts.
 */
public final class DictationDiagnostics {

    private final String device;
    private final String captureFormat;
    private final String targetFormat;
    private final long durationMillis;
    private final long wavBytes;
    private final double rms;
    private final int peak;
    private final long clippedSamples;
    private final long droppedFrames;
    private final String ollamaVersion;
    private final String model;
    private final String capabilityStatus;
    private final int httpStatus;
    private final long transcriptionMillis;

    private DictationDiagnostics(Builder b) {
        this.device = b.device;
        this.captureFormat = b.captureFormat;
        this.targetFormat = b.targetFormat;
        this.durationMillis = b.durationMillis;
        this.wavBytes = b.wavBytes;
        this.rms = b.rms;
        this.peak = b.peak;
        this.clippedSamples = b.clippedSamples;
        this.droppedFrames = b.droppedFrames;
        this.ollamaVersion = b.ollamaVersion;
        this.model = b.model;
        this.capabilityStatus = b.capabilityStatus;
        this.httpStatus = b.httpStatus;
        this.transcriptionMillis = b.transcriptionMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** @return the diagnostics as ordered "key: value" pairs (no sensitive content). */
    public Map<String, String> asMap() {
        Map<String, String> map = new LinkedHashMap<String, String>();
        map.put("Device", n(device));
        map.put("Capture format", n(captureFormat));
        map.put("Target format", n(targetFormat));
        map.put("Duration", durationMillis + " ms");
        map.put("WAV size", wavBytes + " bytes");
        map.put("RMS", String.format("%.0f", rms));
        map.put("Peak", String.valueOf(peak));
        map.put("Clipped samples", String.valueOf(clippedSamples));
        map.put("Dropped frames", String.valueOf(droppedFrames));
        map.put("Ollama version", n(ollamaVersion));
        map.put("Model", n(model));
        map.put("Audio capability", n(capabilityStatus));
        map.put("HTTP status", httpStatus == 0 ? "—" : String.valueOf(httpStatus));
        map.put("Transcription time", transcriptionMillis + " ms");
        return map;
    }

    public String describe() {
        StringBuilder builder = new StringBuilder("Dictation diagnostics:");
        for (Map.Entry<String, String> entry : asMap().entrySet()) {
            builder.append("\n  ").append(entry.getKey()).append(": ").append(entry.getValue());
        }
        return builder.toString();
    }

    private static String n(String value) {
        return value == null || value.isEmpty() ? "—" : value;
    }

    public static final class Builder {
        private String device = "";
        private String captureFormat = "";
        private String targetFormat = "";
        private long durationMillis;
        private long wavBytes;
        private double rms;
        private int peak;
        private long clippedSamples;
        private long droppedFrames;
        private String ollamaVersion = "";
        private String model = "";
        private String capabilityStatus = "";
        private int httpStatus;
        private long transcriptionMillis;

        public Builder device(String v) { this.device = v; return this; }
        public Builder captureFormat(String v) { this.captureFormat = v; return this; }
        public Builder targetFormat(String v) { this.targetFormat = v; return this; }
        public Builder durationMillis(long v) { this.durationMillis = v; return this; }
        public Builder wavBytes(long v) { this.wavBytes = v; return this; }
        public Builder rms(double v) { this.rms = v; return this; }
        public Builder peak(int v) { this.peak = v; return this; }
        public Builder clippedSamples(long v) { this.clippedSamples = v; return this; }
        public Builder droppedFrames(long v) { this.droppedFrames = v; return this; }
        public Builder ollamaVersion(String v) { this.ollamaVersion = v; return this; }
        public Builder model(String v) { this.model = v; return this; }
        public Builder capabilityStatus(String v) { this.capabilityStatus = v; return this; }
        public Builder httpStatus(int v) { this.httpStatus = v; return this; }
        public Builder transcriptionMillis(long v) { this.transcriptionMillis = v; return this; }

        public DictationDiagnostics build() {
            return new DictationDiagnostics(this);
        }
    }
}
