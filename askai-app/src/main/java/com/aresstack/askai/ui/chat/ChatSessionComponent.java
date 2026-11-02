package com.aresstack.askai.ui.chat;

import java.awt.Component;

/**
 * A single chat session as seen by its host ({@link ChatWorkspacePanel}). Keeps the host decoupled from the
 * concrete chat UI: the host only needs the stable {@link ChatSessionId}, the Swing component to place in a
 * tab, and a lifecycle hook to release the session's resources when its tab is closed.
 */
public interface ChatSessionComponent {

    ChatSessionId getSessionId();

    /** @return the Swing component shown in the tab (usually the chat panel itself). */
    Component getComponent();

    /** Abort the running chat/dictation/transcription and stop timers; called once when the tab closes. */
    void disposeSession();
}
