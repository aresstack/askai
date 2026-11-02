package com.aresstack.askai.browser;

/**
 * Which backend a {@link BrowserSession} runs on. The selection is explicit and surfaced to the user/agent;
 * there is NO automatic fallback from the browser runtime to the static backend. STATIC_HTTP cannot promise
 * JavaScript navigation, dynamic pages or real browser interaction — only static fetch + cleanup.
 */
public enum BrowserBackendKind {
    PLAYWRIGHT,
    STATIC_HTTP
}
