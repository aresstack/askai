package com.aresstack.askai.hf.meta;

/**
 * A single metadata value together with where it came from and how much it is trusted. Immutable.
 *
 * <p>The {@link #source} and {@link #confidence} travel with the value so a later merge can pick a
 * winner on conflict (higher-ranked source, higher confidence) and so only sufficiently trusted values
 * are written into functional Ollama fields.</p>
 *
 * @param <T> the value type (String, Integer, ...)
 */
public final class MetadataValue<T> {

    private final T value;
    private final MetadataSource source;
    private final Confidence confidence;

    private MetadataValue(T value, MetadataSource source, Confidence confidence) {
        this.value = value;
        this.source = source;
        this.confidence = confidence;
    }

    public static <T> MetadataValue<T> of(T value, MetadataSource source, Confidence confidence) {
        if (source == null || confidence == null) {
            throw new IllegalArgumentException("source and confidence are required");
        }
        return new MetadataValue<T>(value, source, confidence);
    }

    /** A high-confidence value from the given source. */
    public static <T> MetadataValue<T> high(T value, MetadataSource source) {
        return of(value, source, Confidence.HIGH);
    }

    public T value() {
        return value;
    }

    public MetadataSource source() {
        return source;
    }

    public Confidence confidence() {
        return confidence;
    }

    /**
     * @return true when this value may be written into a functional Ollama field: a non-null HIGH value,
     *         or a MEDIUM value on a field that explicitly allows MEDIUM.
     */
    public boolean isTrusted(boolean allowMedium) {
        if (value == null) {
            return false;
        }
        return confidence == Confidence.HIGH || (allowMedium && confidence == Confidence.MEDIUM);
    }

    /** @return true when this value more authoritatively describes a field than {@code other}. */
    public boolean outranks(MetadataValue<?> other) {
        if (other == null) {
            return true;
        }
        if (this.confidence != other.confidence) {
            return this.confidence.ordinal() < other.confidence.ordinal();
        }
        return this.source.ordinal() < other.source.ordinal();
    }

    @Override
    public String toString() {
        return String.valueOf(value) + " (" + source + "/" + confidence + ")";
    }
}
