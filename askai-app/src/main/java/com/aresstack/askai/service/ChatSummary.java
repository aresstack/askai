package com.aresstack.askai.service;

public final class ChatSummary {

    private final long evalCount;
    private final long evalDurationNanos;

    public ChatSummary(long evalCount, long evalDurationNanos) {
        this.evalCount = evalCount;
        this.evalDurationNanos = evalDurationNanos;
    }

    public long getEvalCount() {
        return evalCount;
    }

    public long getEvalDurationNanos() {
        return evalDurationNanos;
    }

    public boolean hasMetrics() {
        return evalCount > 0L && evalDurationNanos > 0L;
    }

    public double tokensPerSecond() {
        if (!hasMetrics()) {
            return 0.0d;
        }
        return evalCount / (evalDurationNanos / 1000000000.0d);
    }
}
