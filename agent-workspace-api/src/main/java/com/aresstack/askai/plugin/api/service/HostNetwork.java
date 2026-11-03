package com.aresstack.askai.plugin.api.service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Opens HTTP(S) connections the way the host's network settings say (proxy, PAC, certificates, proxy login), so
 * plugin downloads such as the search browser reach the internet in company networks too. Process-wide: the host
 * configures it once at start; the setup wizard may override it while its network settings are not saved yet.
 * Without a host (tests, tools) it falls back to a plain connection.
 */
public final class HostNetwork {

    /** Opens an unconnected connection; the caller sets timeouts and reads it. */
    public interface Connector {
        HttpURLConnection open(String url) throws IOException;
    }

    private static volatile Connector configured;
    private static volatile Connector override;

    private HostNetwork() {
    }

    /** The host's connector from its saved network settings. */
    public static void configure(Connector connector) {
        configured = connector;
    }

    /** Takes precedence over {@link #configure} (e.g. unsaved settings in the setup wizard); {@code null} ends it. */
    public static void override(Connector connector) {
        override = connector;
    }

    public static HttpURLConnection open(String url) throws IOException {
        Connector connector = override != null ? override : configured;
        return connector != null ? connector.open(url) : (HttpURLConnection) new URL(url).openConnection();
    }
}
