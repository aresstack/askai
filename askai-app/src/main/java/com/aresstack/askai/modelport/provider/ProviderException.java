package com.aresstack.askai.modelport.provider;

/**
 * A provider call that did not succeed. The message is human-readable and secret-free; {@link #isUnreachable()}
 * tells the port whether the endpoint itself could not be reached (a connection failure) or answered with an error.
 */
public final class ProviderException extends Exception {

    private static final long serialVersionUID = 1L;

    private final boolean unreachable;

    public ProviderException(String message, boolean unreachable) {
        super(message);
        this.unreachable = unreachable;
    }

    public ProviderException(String message, boolean unreachable, Throwable cause) {
        super(message, cause);
        this.unreachable = unreachable;
    }

    /** True when the endpoint could not be reached at all (connection refused, connect time-out). */
    public boolean isUnreachable() {
        return unreachable;
    }
}
