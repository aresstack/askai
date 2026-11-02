package com.aresstack.askai.ui;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Small Swing helpers shared by panels.
 */
public final class UiSupport {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private UiSupport() {
    }

    public static void appendLog(final JTextArea logArea, final String message) {
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                logArea.append("[" + LocalDateTime.now().format(TIME_FORMAT) + "] " + message + System.lineSeparator());
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
    }
}
