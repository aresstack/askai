package com.aresstack.askai.service;

/**
 * The typed thinking configuration for an {@code /api/chat} request. Maps to the wire {@code think} field:
 * {@code DEFAULT} omits it, {@code DISABLED}/{@code ENABLED} send a boolean, and the effort levels send
 * their string. No untyped strings or {@code Object} leak into the request layer.
 */
public final class ThinkingOption {

    public enum Mode {
        DEFAULT,
        DISABLED,
        ENABLED,
        LOW,
        MEDIUM,
        HIGH,
        MAX
    }

    private static final ThinkingOption DEFAULT = new ThinkingOption(Mode.DEFAULT);

    private final Mode mode;

    public ThinkingOption(Mode mode) {
        this.mode = mode == null ? Mode.DEFAULT : mode;
    }

    public static ThinkingOption defaultOption() {
        return DEFAULT;
    }

    public static ThinkingOption of(Mode mode) {
        return new ThinkingOption(mode);
    }

    /**
     * @return the option for a UI effort level ("low"/"medium"/"high"/"max", case-insensitive); "off" or
     *         an unknown value yields {@link Mode#DEFAULT} (thinking left off / not requested).
     */
    public static ThinkingOption ofLevel(String level) {
        if (level == null) {
            return DEFAULT;
        }
        String normalized = level.trim().toLowerCase(java.util.Locale.ROOT);
        if ("low".equals(normalized)) {
            return of(Mode.LOW);
        }
        if ("medium".equals(normalized)) {
            return of(Mode.MEDIUM);
        }
        if ("high".equals(normalized)) {
            return of(Mode.HIGH);
        }
        if ("max".equals(normalized)) {
            return of(Mode.MAX);
        }
        return DEFAULT;
    }

    public Mode getMode() {
        return mode;
    }

    /**
     * @return the value for the {@code think} field: {@code null} (omit the field), a {@link Boolean}, or a
     *         level string.
     */
    public Object toWireValue() {
        switch (mode) {
            case DISABLED:
                return Boolean.FALSE;
            case ENABLED:
                return Boolean.TRUE;
            case LOW:
                return "low";
            case MEDIUM:
                return "medium";
            case HIGH:
                return "high";
            case MAX:
                return "max";
            case DEFAULT:
            default:
                return null;
        }
    }
}
