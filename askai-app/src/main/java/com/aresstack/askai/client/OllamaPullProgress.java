package com.aresstack.askai.client;

/**
 * One progress update from an Ollama model pull, in AskAI domain terms.
 */
public final class OllamaPullProgress {

    private final String status;
    private final long completed;
    private final long total;

    public OllamaPullProgress(String status, long completed, long total) {
        this.status = status == null ? "" : status;
        this.completed = completed;
        this.total = total;
    }

    public String getStatus() {
        return status;
    }

    public long getCompleted() {
        return completed;
    }

    public long getTotal() {
        return total;
    }

    public boolean hasMeasurableProgress() {
        return total > 0L && completed >= 0L;
    }

    /**
     * Completion percentage in {@code [0, 100]}, or {@code -1} when not measurable.
     */
    public int percent() {
        if (!hasMeasurableProgress()) {
            return -1;
        }
        return (int) Math.max(0L, Math.min(100L, Math.round(completed * 100.0d / total)));
    }
}
