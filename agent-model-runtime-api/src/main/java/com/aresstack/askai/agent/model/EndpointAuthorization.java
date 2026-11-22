package com.aresstack.askai.agent.model;

import java.io.IOException;

/**
 * The bearer token for an endpoint an agent calls directly (an OpenAI-compatible API's {@code /embeddings} or
 * {@code /rerank}): the host installs one {@link Source} that knows the configured API and its key (stored encrypted
 * or read from KeePass); descriptors stay secret-free. The token is handed out per request and never kept here. Any
 * URL outside the configured API gets {@code null}, so a key never reaches Ollama or another server.
 */
public final class EndpointAuthorization {

    /** Gives the token for a request URL, or {@code null} when the URL does not belong to the configured API. */
    public interface Source {
        char[] bearerFor(String url) throws IOException;
    }

    private static volatile Source source;

    private EndpointAuthorization() {
    }

    /** Installed by the host at start; {@code null} removes it. */
    public static void install(Source value) {
        source = value;
    }

    /** The token for {@code url} (the caller clears it), {@code null} when none applies. */
    public static char[] bearerFor(String url) throws IOException {
        Source current = source;
        return current == null || url == null ? null : current.bearerFor(url);
    }
}
