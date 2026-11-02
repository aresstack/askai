package com.aresstack.askai.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns the lifecycle of one or more tool calls into amber activity bubbles, keeping each tool call
 * independent by its stable id. Ollama can request several tool calls at once, so there is never a single
 * global "active activity": every call has its own handle in {@link #handlesById}.
 *
 * <p>The public explanation and result summary come only from a {@link ToolPresentation} (via the
 * registry) — never from raw arguments or results. A call that requires user approval keeps its bubble
 * visible (it must not burst) until it is approved-and-finished, or cancelled.</p>
 *
 * @param <H> the sink's opaque activity handle type
 */
public final class ToolActivityCoordinator<H> {

    private final ToolActivitySink<H> sink;
    private final ToolPresentationRegistry registry;
    private final Map<String, H> handlesById = new LinkedHashMap<String, H>();
    private final Map<String, ToolPresentation> presentationById = new LinkedHashMap<String, ToolPresentation>();
    private final Set<String> awaitingApproval = new LinkedHashSet<String>();

    public ToolActivityCoordinator(ToolActivitySink<H> sink, ToolPresentationRegistry registry) {
        if (sink == null || registry == null) {
            throw new IllegalArgumentException("sink and registry are required");
        }
        this.sink = sink;
        this.registry = registry;
    }

    /** A tool call is about to run: open its amber bubble with a safe title and public rationale. */
    public void started(String toolCallId, String toolName, Map<String, Object> arguments) {
        requireId(toolCallId);
        if (handlesById.containsKey(toolCallId)) {
            return; // idempotent: already shown
        }
        ToolPresentation presentation = registry.presentationFor(toolName);
        presentationById.put(toolCallId, presentation);
        Map<String, Object> safe = ToolPresentationRegistry.sanitizeArguments(arguments);
        H handle = sink.start(presentation.getDisplayName(), presentation.describePurpose(safe));
        handlesById.put(toolCallId, handle);
    }

    /** A phase change while the tool runs (still the same activity/bubble). */
    public void updated(String toolCallId, String title, String explanation) {
        H handle = handlesById.get(toolCallId);
        if (handle != null) {
            sink.update(handle, title, explanation);
        }
    }

    /** The call needs user approval: keep the bubble visible (it must not burst) and mark it waiting. */
    public void approvalRequired(String toolCallId, String publicExplanation) {
        H handle = handlesById.get(toolCallId);
        if (handle != null) {
            awaitingApproval.add(toolCallId);
            sink.update(handle, "Waiting for approval", publicExplanation);
        }
    }

    public boolean isAwaitingApproval(String toolCallId) {
        return awaitingApproval.contains(toolCallId);
    }

    /** Approval granted: clear the waiting flag; the bubble stays until the call succeeds/fails. */
    public void approved(String toolCallId) {
        awaitingApproval.remove(toolCallId);
    }

    public void succeeded(String toolCallId, ToolExecutionResult result) {
        finish(toolCallId, true, result);
    }

    public void failed(String toolCallId, ToolExecutionResult result) {
        finish(toolCallId, false, result);
    }

    public void cancelled(String toolCallId, String publicSummary) {
        H handle = remove(toolCallId);
        if (handle != null) {
            sink.cancel(handle, publicSummary);
        }
    }

    /** @return the number of activities currently on screen (not yet completed/cancelled). */
    public int activeCount() {
        return handlesById.size();
    }

    private void finish(String toolCallId, boolean success, ToolExecutionResult result) {
        ToolPresentation presentation = presentationById.get(toolCallId);
        H handle = remove(toolCallId);
        if (handle == null) {
            return;
        }
        String summary = presentation != null ? presentation.summarizeResult(result)
                : (success ? "Done" : "Failed");
        if (success) {
            sink.complete(handle, summary);
        } else {
            sink.fail(handle, summary);
        }
    }

    private H remove(String toolCallId) {
        awaitingApproval.remove(toolCallId);
        presentationById.remove(toolCallId);
        return handlesById.remove(toolCallId);
    }

    private static void requireId(String toolCallId) {
        if (toolCallId == null || toolCallId.trim().isEmpty()) {
            throw new IllegalArgumentException("toolCallId is required");
        }
    }
}
