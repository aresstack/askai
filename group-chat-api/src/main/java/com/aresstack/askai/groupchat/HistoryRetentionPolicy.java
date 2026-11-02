package com.aresstack.askai.groupchat;

/**
 * Retention limits for a {@link FileRoomHistoryLog}: an optional age cap, an optional total-size
 * cap and a per-record sanity cap.  Applied when the log is opened (old records are compacted
 * away) so the append-only file cannot grow without bound.
 *
 * <p>{@code 0} disables the age or size cap individually; {@link #getMaxRecordBytes()} always
 * applies as a guard against corrupt frames triggering huge allocations.</p>
 */
public final class HistoryRetentionPolicy {

    /** Default per-record cap (32 MiB). */
    public static final int DEFAULT_MAX_RECORD_BYTES = 32 * 1024 * 1024;

    /** No age or size cap; only the default per-record guard applies. */
    public static final HistoryRetentionPolicy UNLIMITED =
            new HistoryRetentionPolicy(0L, 0L, DEFAULT_MAX_RECORD_BYTES);

    private final long maxAgeMillis;
    private final long maxSizeBytes;
    private final int maxRecordBytes;

    /**
     * @param maxAgeMillis   drop records older than this; {@code 0} disables the age cap
     * @param maxSizeBytes   keep the newest records within this total; {@code 0} disables the size cap
     * @param maxRecordBytes per-record length guard (must be &gt; 0)
     */
    public HistoryRetentionPolicy(long maxAgeMillis, long maxSizeBytes, int maxRecordBytes) {
        if (maxRecordBytes <= 0) {
            throw new IllegalArgumentException("maxRecordBytes must be > 0");
        }
        this.maxAgeMillis = Math.max(0L, maxAgeMillis);
        this.maxSizeBytes = Math.max(0L, maxSizeBytes);
        this.maxRecordBytes = maxRecordBytes;
    }

    /** Records older than this (relative to now) are compacted away; {@code 0} = no age cap. */
    public long getMaxAgeMillis() {
        return maxAgeMillis;
    }

    /** The newest records are kept within this total size; {@code 0} = no size cap. */
    public long getMaxSizeBytes() {
        return maxSizeBytes;
    }

    /** Per-record length guard, always &gt; 0. */
    public int getMaxRecordBytes() {
        return maxRecordBytes;
    }

    /** @return {@code true} when an age or size cap is active. */
    public boolean hasCaps() {
        return maxAgeMillis > 0 || maxSizeBytes > 0;
    }

    @Override
    public String toString() {
        return "HistoryRetentionPolicy{maxAgeMillis=" + maxAgeMillis
                + ", maxSizeBytes=" + maxSizeBytes + ", maxRecordBytes=" + maxRecordBytes + "}";
    }
}
