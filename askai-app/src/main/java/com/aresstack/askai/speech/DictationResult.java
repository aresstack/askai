package com.aresstack.askai.speech;

/**
 * A successful dictation: the recognized transcript (to be inserted at the caret, never auto-sent) and
 * the technical diagnostics for the details log.
 */
public final class DictationResult {

    private final String text;
    private final String modelUsed;
    private final DictationDiagnostics diagnostics;

    public DictationResult(String text, String modelUsed, DictationDiagnostics diagnostics) {
        this.text = text == null ? "" : text;
        this.modelUsed = modelUsed == null ? "" : modelUsed;
        this.diagnostics = diagnostics;
    }

    public String getText() {
        return text;
    }

    /** @return the /api/show-verified model that produced the transcript (to persist as last-used). */
    public String getModelUsed() {
        return modelUsed;
    }

    public DictationDiagnostics getDiagnostics() {
        return diagnostics;
    }
}
