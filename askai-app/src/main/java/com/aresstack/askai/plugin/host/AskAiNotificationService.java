package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.service.NotificationService;

/**
 * Minimal {@link NotificationService}: routes transient plugin messages to an optional host sink (e.g. a
 * status strip). Without a sink it logs to stderr. Plugins never pop their own dialogs through this.
 */
public final class AskAiNotificationService implements NotificationService {

    /** Host sink for notifications; may be replaced by the frame to show a status line. */
    public interface Sink {
        void show(Severity severity, String message);
    }

    private volatile Sink sink;

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    @Override
    public void notify(Severity severity, String message) {
        Sink current = sink;
        if (current != null) {
            current.show(severity, message);
        } else {
            System.err.println("[plugin][" + severity + "] " + message);
        }
    }
}
