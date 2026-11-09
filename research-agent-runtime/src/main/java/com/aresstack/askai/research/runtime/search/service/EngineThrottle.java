package com.aresstack.askai.research.runtime.search.service;

/**
 * Spacing and backoff for the search engines, shared by every chat of the JVM (key {@code search-engines}). Every
 * engine request (a SERP or a redirect hop) keeps a minimum distance to the previous one; a challenge or block
 * pauses the engines with an exponentially growing pause (base, 2×base, 4×base, … up to the cap), which the next
 * successful request resets. Not thread-safe on its own: the search service calls it under its engine lock.
 */
public final class EngineThrottle {

    /** The key the engines share in limits and logs. */
    public static final String KEY = "search-engines";

    private final long minSpacingMillis;
    private final long backoffBaseMillis;
    private final long backoffCapMillis;
    private final SearchServiceClock clock;

    private long lastRequestAt = Long.MIN_VALUE;
    private long pausedUntil;
    private int consecutiveBlocks;
    private String pauseReason = "";

    public EngineThrottle(long minSpacingMillis, long backoffBaseMillis, long backoffCapMillis,
                          SearchServiceClock clock) {
        this.minSpacingMillis = Math.max(0L, minSpacingMillis);
        this.backoffBaseMillis = Math.max(1L, backoffBaseMillis);
        this.backoffCapMillis = Math.max(this.backoffBaseMillis, backoffCapMillis);
        this.clock = clock == null ? SearchServiceClock.SYSTEM : clock;
    }

    /** Whether the engines are paused right now because of a block. */
    public boolean isPaused() {
        return clock.nowMillis() < pausedUntil;
    }

    public long pausedUntil() {
        return pausedUntil;
    }

    public String pauseReason() {
        return isPaused() ? pauseReason : "";
    }

    public int consecutiveBlocks() {
        return consecutiveBlocks;
    }

    /** Wait until the minimum spacing to the previous engine request has passed, then count this request. */
    public void awaitSlot() throws InterruptedException {
        if (lastRequestAt != Long.MIN_VALUE) {
            long wait = lastRequestAt + minSpacingMillis - clock.nowMillis();
            if (wait > 0) {
                clock.sleep(wait);
            }
        }
        lastRequestAt = clock.nowMillis();
    }

    /** The engine answered normally: the backoff starts again from the base. */
    public void recordSuccess() {
        consecutiveBlocks = 0;
        pauseReason = "";
    }

    /** The engine answered with a challenge or block: pause it, twice as long as last time. */
    public long recordBlock(String reason) {
        int exponent = Math.min(consecutiveBlocks, 30);
        long pause = backoffBaseMillis << exponent;
        if (pause <= 0 || pause > backoffCapMillis) {
            pause = backoffCapMillis;
        }
        consecutiveBlocks++;
        pausedUntil = clock.nowMillis() + pause;
        pauseReason = reason == null || reason.trim().isEmpty() ? "engine challenge" : reason.trim();
        return pause;
    }
}
