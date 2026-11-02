package io.github.ollama4j.models;

public final class PullProgress {

    private final String status;
    private final long completed;
    private final long total;

    public PullProgress(String status, long completed, long total) {
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

    public boolean hasTotal() {
        return total > 0L;
    }

    public int getPercent() {
        if (!hasTotal()) {
            return 0;
        }
        long percent = completed * 100L / total;
        return (int) Math.max(0L, Math.min(100L, percent));
    }
}
