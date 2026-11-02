package com.aresstack.askai.research.runtime.host;

/** The text of one host tool call and whether the host rejected it; {@link #toString()} is the text. */
public final class HostToolResult {

    private final String text;
    private final boolean error;

    public HostToolResult(String text, boolean error) {
        this.text = text == null ? "" : text;
        this.error = error;
    }

    public boolean isError() {
        return error;
    }

    public String getText() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }
}
