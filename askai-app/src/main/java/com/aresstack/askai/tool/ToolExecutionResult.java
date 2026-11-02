package com.aresstack.askai.tool;

/**
 * The outcome of a tool execution, in terms safe to show a user. It deliberately carries only a success
 * flag and a short public summary — never the raw tool output, arguments, credentials or stack traces.
 */
public final class ToolExecutionResult {

    private final boolean success;
    private final String publicSummary;

    private ToolExecutionResult(boolean success, String publicSummary) {
        this.success = success;
        this.publicSummary = publicSummary == null ? "" : publicSummary;
    }

    public static ToolExecutionResult success(String publicSummary) {
        return new ToolExecutionResult(true, publicSummary);
    }

    public static ToolExecutionResult failure(String publicSummary) {
        return new ToolExecutionResult(false, publicSummary);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getPublicSummary() {
        return publicSummary;
    }
}
