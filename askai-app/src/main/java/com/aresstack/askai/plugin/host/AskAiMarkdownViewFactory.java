package com.aresstack.askai.plugin.host;

import com.aresstack.askai.ui.markdown.MarkdownMessageView;
import com.aresstack.askai.plugin.api.service.MarkdownView;
import com.aresstack.askai.plugin.api.service.MarkdownViewFactory;
import com.aresstack.askai.plugin.api.service.MarkdownViewOptions;

/** Host-side factory that builds {@link MarkdownView}s backed by AskAI's {@link MarkdownMessageView}. */
public final class AskAiMarkdownViewFactory implements MarkdownViewFactory {

    @Override
    public MarkdownView create(MarkdownViewOptions options) {
        return new AskAiMarkdownViewAdapter(new MarkdownMessageView());
    }
}
