package com.aresstack.askai.browser.playwright;

import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Asks a search engine for the targets of its opaque result wrappers (Google's {@code /goto?url=<token>}, see
 * {@link SearchRedirectResolver#needsLookup}): one GET per wrapper, all at once, the redirect NOT followed, the target
 * page never loaded, no cookies sent. Only the {@code Location} of a 3xx answer counts. This is no browser action and
 * no page script; the browser's tab is not touched. Google answers such a request in a few dozen milliseconds; going
 * through Playwright's request channel instead took about 1.5 s per wrapper (the driver's JavaScript runs interpreted).
 *
 * <p>Requests go through the JVM's default {@link ProxySelector}, the same proxy settings the application uses for its
 * own HTTP. A wrapper whose answer is missing, not a redirect, or later than the shared deadline is simply left out:
 * its link stays unresolved and is discarded, never classified by the engine's host.</p>
 */
final class EngineRedirectLookup {

    /** The deadline all lookups of one result page share. */
    static final int TOTAL_TIMEOUT_MILLIS = 5000;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .proxy(ProxySelector.getDefault())
            .connectTimeout(Duration.ofMillis(TOTAL_TIMEOUT_MILLIS))
            .build();

    private EngineRedirectLookup() {
    }

    /** Wrapper URL to the raw {@code Location} its engine answered with; wrappers without such an answer are absent. */
    static Map<String, String> locations(Collection<String> wrapperUrls, long totalTimeoutMillis) {
        Map<String, CompletableFuture<HttpResponse<Void>>> pending =
                new LinkedHashMap<String, CompletableFuture<HttpResponse<Void>>>();
        for (String wrapper : wrapperUrls) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(wrapper)).GET()
                        .timeout(Duration.ofMillis(totalTimeoutMillis)).build();
                pending.put(wrapper, CLIENT.sendAsync(request, HttpResponse.BodyHandlers.discarding()));
            } catch (IllegalArgumentException unusable) {
                // not a valid URI: no answer
            }
        }
        long deadline = System.currentTimeMillis() + totalTimeoutMillis;
        Map<String, String> answers = new LinkedHashMap<String, String>();
        Map<String, Integer> outcomes = new java.util.TreeMap<String, Integer>();
        for (Map.Entry<String, CompletableFuture<HttpResponse<Void>>> entry : pending.entrySet()) {
            long left = deadline - System.currentTimeMillis();
            try {
                HttpResponse<Void> response = entry.getValue().get(Math.max(0L, left), TimeUnit.MILLISECONDS);
                int status = response.statusCode();
                outcomes.merge(String.valueOf(status), 1, Integer::sum);
                if (status >= 300 && status < 400) {
                    response.headers().firstValue("location").ifPresent(location -> answers.put(entry.getKey(), location));
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception unanswered) {
                Throwable cause = unanswered.getCause() == null ? unanswered : unanswered.getCause();
                outcomes.merge(cause.getClass().getSimpleName(), 1, Integer::sum);
                entry.getValue().cancel(true); // late, refused or failed: this wrapper stays unresolved
            }
        }
        if (!pending.isEmpty()) {
            System.err.println("[serp-capture] redirect lookups " + pending.size() + " answered " + outcomes);
        }
        for (CompletableFuture<HttpResponse<Void>> request : pending.values()) {
            request.cancel(true);
        }
        return answers;
    }
}
