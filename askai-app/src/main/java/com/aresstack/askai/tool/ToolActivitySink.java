package com.aresstack.askai.tool;

/**
 * The UI operations a {@link ToolActivityCoordinator} drives, abstracted from Swing so the coordinator
 * is unit-testable. An implementation maps these to the amber activity bubble (e.g. over
 * {@code BubbleTranscriptPanel}). {@code H} is the opaque per-activity handle the UI returns.
 */
public interface ToolActivitySink<H> {

    H start(String title, String explanation);

    void update(H handle, String title, String explanation);

    void complete(H handle, String summary);

    void fail(H handle, String summary);

    void cancel(H handle, String summary);
}
