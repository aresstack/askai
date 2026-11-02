package com.aresstack.askai.plugin.host;

import com.aresstack.askai.ui.bubble.BubbleTranscriptPanel;
import com.aresstack.askai.plugin.api.service.ConversationSurface;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceFactory;
import com.aresstack.askai.plugin.api.service.ConversationSurfaceOptions;

/** Host-side factory that builds {@link ConversationSurface}s backed by AskAI's bubble transcript. */
public final class AskAiConversationSurfaceFactory implements ConversationSurfaceFactory {

    @Override
    public ConversationSurface create(ConversationSurfaceOptions options) {
        return new AskAiConversationSurfaceAdapter(new BubbleTranscriptPanel());
    }
}
