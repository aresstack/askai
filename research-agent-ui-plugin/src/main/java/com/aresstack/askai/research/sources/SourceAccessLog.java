package com.aresstack.askai.research.sources;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable record of the websites (domain families, e.g. {@code researchgate.net}) that demanded a manual
 * security check during the research, with the last page that triggered it. A BLOCKED family stays BLOCKED
 * until a RESOLVED signal arrives; a later new check on a RESOLVED family turns it BLOCKED again.
 */
public final class SourceAccessLog {

    /** One website entry. */
    public static final class Entry {
        private final String family;
        private final SourceAccessState state;
        private final String url;

        public Entry(String family, SourceAccessState state, String url) {
            this.family = normalize(family);
            this.state = state == null ? SourceAccessState.NONE : state;
            this.url = url == null ? "" : url.trim();
        }

        public String getFamily() {
            return family;
        }

        public SourceAccessState getState() {
            return state;
        }

        /** The page that triggered the check ("" when unknown). */
        public String getUrl() {
            return url;
        }
    }

    public static final SourceAccessLog EMPTY = new SourceAccessLog(new LinkedHashMap<String, Entry>());

    private final Map<String, Entry> entries;

    private SourceAccessLog(Map<String, Entry> entries) {
        this.entries = Collections.unmodifiableMap(new LinkedHashMap<String, Entry>(entries));
    }

    public static SourceAccessLog of(Iterable<Entry> entries) {
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        for (Entry e : entries) {
            if (!e.getFamily().isEmpty() && e.getState() != SourceAccessState.NONE) {
                map.put(e.getFamily(), e);
            }
        }
        return new SourceAccessLog(map);
    }

    /** A security check was demanded on {@code family} (url = the triggering page, may be empty). */
    public SourceAccessLog blocked(String family, String url) {
        return with(new Entry(family, SourceAccessState.BLOCKED, url));
    }

    /** The security check on {@code family} was solved; unknown families are recorded as RESOLVED too. */
    public SourceAccessLog resolved(String family, String url) {
        Entry previous = entries.get(normalize(family));
        String keptUrl = url == null || url.trim().isEmpty()
                ? (previous == null ? "" : previous.getUrl()) : url;
        return with(new Entry(family, SourceAccessState.RESOLVED, keptUrl));
    }

    private SourceAccessLog with(Entry entry) {
        if (entry.getFamily().isEmpty()) {
            return this;
        }
        Map<String, Entry> map = new LinkedHashMap<String, Entry>(entries);
        map.put(entry.getFamily(), entry);
        return new SourceAccessLog(map);
    }

    public java.util.Collection<Entry> entries() {
        return entries.values();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** The entry whose family covers the host of {@code url} (host == family or a subdomain of it), or null. */
    public Entry lookup(String url) {
        String host = hostOf(url);
        if (host.isEmpty()) {
            return null;
        }
        Entry best = null;
        for (Entry e : entries.values()) {
            if (host.equals(e.getFamily()) || host.endsWith("." + e.getFamily())) {
                if (best == null || e.getFamily().length() > best.getFamily().length()) {
                    best = e;
                }
            }
        }
        return best;
    }

    /** The access state for a source URL. */
    public SourceAccessState stateOf(String url) {
        Entry e = lookup(url);
        return e == null ? SourceAccessState.NONE : e.getState();
    }

    static String hostOf(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int scheme = u.indexOf("://");
        if (scheme >= 0) {
            u = u.substring(scheme + 3);
        }
        int end = u.length();
        for (char c : new char[] {'/', '?', '#', ':'}) {
            int i = u.indexOf(c);
            if (i >= 0 && i < end) {
                end = i;
            }
        }
        u = u.substring(0, end);
        int at = u.lastIndexOf('@');
        return at >= 0 ? u.substring(at + 1) : u;
    }

    private static String normalize(String family) {
        String f = family == null ? "" : family.trim().toLowerCase(Locale.ROOT);
        while (f.startsWith(".")) {
            f = f.substring(1);
        }
        return f.startsWith("www.") ? f.substring("www.".length()) : f;
    }
}
