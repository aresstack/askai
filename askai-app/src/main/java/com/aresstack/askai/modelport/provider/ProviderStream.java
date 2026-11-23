package com.aresstack.askai.modelport.provider;

/** Receives the streamed parts of one model turn, in order, on the provider's (port's) thread. */
public interface ProviderStream {

    void onThinkingDelta(String delta);

    void onContentDelta(String delta);
}
