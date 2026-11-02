package com.aresstack.askai.ui;

/**
 * Semantic state of the connection to the Ollama server, so the UI can show "Connected" /
 * "Not reachable" instead of leaving the user to interpret a bare URL. Swing-free on purpose (only a
 * label and the key of its theme color token) so the mapping is unit-testable without a display.
 */
public enum ConnectionStatus {

    NOT_CHECKED("Not checked", "app.connectionNotCheckedDot"),
    CONNECTING("Connecting…", "app.connectionConnectingDot"),
    CONNECTED("Connected", "app.connectionConnectedDot"),
    NOT_REACHABLE("Not reachable", "app.connectionFailedDot"),
    ERROR("Error", "app.connectionFailedDot");

    private final String label;
    private final String colorToken;

    ConnectionStatus(String label, String colorToken) {
        this.label = label;
        this.colorToken = colorToken;
    }

    public String getLabel() {
        return label;
    }

    /** @return the theme token key ({@code group.role}) of the status dot color. */
    public String getColorToken() {
        return colorToken;
    }

    /** @return {@link #CONNECTED} when the server returned a non-empty version, else {@link #NOT_REACHABLE}. */
    public static ConnectionStatus forVersion(String version) {
        return version != null && version.trim().length() > 0 ? CONNECTED : NOT_REACHABLE;
    }
}
