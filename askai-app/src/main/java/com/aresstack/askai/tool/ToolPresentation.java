package com.aresstack.askai.tool;

import java.util.Map;

/**
 * Produces the user-facing, public description of a tool action for the amber activity bubble. A tool
 * call by itself (name + arguments) is not a reliable explanation of <em>why</em> the agent uses it, so
 * this is a deliberate, registered presentation layer.
 *
 * <p>Implementations must never surface raw tool JSON, full argument objects, credentials, tokens,
 * cookies, complete tool results or stack traces. {@code describePurpose} receives already-sanitized
 * arguments and should still only use them to phrase a safe explanation.</p>
 */
public interface ToolPresentation {

    /** A short human title, e.g. "Open manufacturer page". */
    String getDisplayName();

    /** A one-line public rationale, phrased from (sanitized) arguments — never echoing them verbatim. */
    String describePurpose(Map<String, Object> safeArguments);

    /** A short public summary of the outcome — never the raw result. */
    String summarizeResult(ToolExecutionResult result);
}
