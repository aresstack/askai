package com.aresstack.askai.video;

/** A structured backend/recording failure — the infrastructure never touches Swing/JOptionPane itself. */
public final class RecordingException extends Exception {

    public RecordingException(String message) {
        super(message);
    }

    public RecordingException(String message, Throwable cause) {
        super(message, cause);
    }
}
