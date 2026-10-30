package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Bounds how many worker browser operations hit the same site at once, over every worker and chat of the
 * pool. The key is the host without a leading {@code www.}; every search-engine operation shares the one key
 * {@value #SEARCH_KEY} (the engines throttle a burst of parallel queries from one address with CAPTCHAs).
 */
final class SiteConcurrencyGate {

    static final String SEARCH_KEY = "search-engines";

    private final Map<String, Integer> active = new HashMap<String, Integer>();
    private volatile int limit;

    SiteConcurrencyGate(int limit) {
        this.limit = Math.max(1, limit);
    }

    void setLimit(int limit) {
        synchronized (this) {
            this.limit = Math.max(1, limit);
            notifyAll();
        }
    }

    /** The gate key of a URL, or null when it has none. */
    static String siteOf(String url) {
        if (url == null || url.trim().isEmpty()) {
            return null;
        }
        String host = PublicSuffixDomainKeyResolver.hostOf(url.trim());
        if (host == null || host.isEmpty()) {
            return null;
        }
        host = host.toLowerCase(Locale.ROOT);
        return host.startsWith("www.") ? host.substring(4) : host;
    }

    /** Enter the site; waits up to {@code timeoutMillis} (0 = unbounded). Null key = no gate. */
    void enter(String key, long timeoutMillis) throws BrowserException, InterruptedException {
        if (key == null) {
            return;
        }
        long deadline = timeoutMillis > 0 ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis) : 0L;
        synchronized (this) {
            while (count(key) >= limit) {
                if (deadline == 0L) {
                    wait();
                } else {
                    long left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
                    if (left <= 0) {
                        throw new BrowserException("Site limit: " + limit + " browser operations on " + key
                                + " were still running after " + (timeoutMillis / 1000L) + " s.");
                    }
                    wait(left);
                }
            }
            active.put(key, count(key) + 1);
        }
    }

    void leave(String key) {
        if (key == null) {
            return;
        }
        synchronized (this) {
            int now = count(key) - 1;
            if (now <= 0) {
                active.remove(key);
            } else {
                active.put(key, now);
            }
            notifyAll();
        }
    }

    synchronized int active(String key) {
        return count(key);
    }

    private int count(String key) {
        Integer value = active.get(key);
        return value == null ? 0 : value;
    }
}
