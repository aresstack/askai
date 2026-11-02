package com.aresstack.askai.plugin.host;

import com.aresstack.askai.ui.markdown.MarkdownMessageView;
import com.aresstack.askai.plugin.api.service.MarkdownView;

import javax.swing.JComponent;

/**
 * Adapts the existing {@link MarkdownMessageView} (the fixed Markdown/Mermaid renderer) to the generic
 * {@link MarkdownView} host-service contract, so plugins reuse it without importing app UI classes.
 */
final class AskAiMarkdownViewAdapter implements MarkdownView {

    private final MarkdownMessageView view;

    AskAiMarkdownViewAdapter(MarkdownMessageView view) {
        this.view = view;
    }

    @Override
    public JComponent getComponent() {
        return view;
    }

    @Override
    public void setMarkdown(String markdown) {
        view.setMarkdown(markdown);
    }

    @Override
    public void startStreaming() {
        view.startStreaming();
    }

    @Override
    public void appendMarkdownDelta(String delta) {
        view.appendMarkdownDelta(delta);
    }

    @Override
    public void finishStreaming() {
        view.finishStreaming();
    }

    @Override
    public void dispose() {
        // MarkdownMessageView holds no external resources; nothing to release.
    }
}
