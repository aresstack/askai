package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.domain.scope.TypedBlacklist;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Component 4 of the component plan (2026-11-21): the search hygiene. A plain program check, no model and no
 * similarity. It rejects a search query only for
 * <ul>
 * <li>a formal error: no search word (a query of {@code site:} restrictions alone has none), fewer than
 * {@value ConceptQueryPlan#MIN_QUERY_LENGTH} or more than {@value ConceptQueryPlan#MAX_QUERY_LENGTH} characters;</li>
 * <li>a repeat of an earlier search: the same words and the same {@code site:} restriction ({@link #key}). Case,
 * punctuation, the order of the words and a repeated word make no difference; a different {@code site:} makes a
 * different search, so "X site:baua.de", "X site:osha.europa.eu" and "X" are three searches;</li>
 * <li>a hit on the typed blacklist: a term entry as a whole word of the query, a host entry on the host of a
 * {@code site:} restriction.</li>
 * </ul>
 * Everything else may run. How near a query lies to the mind map is not asked here; the topic boundary is drawn on
 * the proposed topic, not on the query (corrections of 2026-11-21, 19:35).
 */
public final class SearchHygiene {

    /** Why a query may not run. */
    public enum Reason {
        NO_WORDS, TOO_SHORT, TOO_LONG, REPEAT, BLACKLIST
    }

    /** The answer for one query. */
    public static final class Verdict {
        private final Reason reason;
        private final String detail;
        private final List<String> sameWordsOtherSite;

        private Verdict(Reason reason, String detail, List<String> sameWordsOtherSite) {
            this.reason = reason;
            this.detail = detail == null ? "" : detail;
            this.sameWordsOtherSite = Collections.unmodifiableList(new ArrayList<String>(sameWordsOtherSite));
        }

        public boolean isAdmitted() {
            return reason == null;
        }

        /** {@code null} for an admitted query. */
        public Reason getReason() {
            return reason;
        }

        /** The earlier search a repeat repeats, or the blacklist entry that was hit; otherwise empty. */
        public String getDetail() {
            return detail;
        }

        /**
         * The earlier searches with the same words and another {@code site:} restriction. They are counted, never a
         * reason to reject: the research lead reads from them whether the planner stacks variants.
         */
        public List<String> getSameWordsOtherSite() {
            return sameWordsOtherSite;
        }

        /** The reason as the plan stores it; empty for an admitted query. */
        public String text() {
            if (reason == null) {
                return "";
            }
            switch (reason) {
                case NO_WORDS:
                    return "no search word";
                case TOO_SHORT:
                    return "shorter than " + ConceptQueryPlan.MIN_QUERY_LENGTH + " characters";
                case TOO_LONG:
                    return "longer than " + ConceptQueryPlan.MAX_QUERY_LENGTH + " characters";
                case REPEAT:
                    return "repeats the search \"" + detail + "\"";
                default:
                    return "blacklist \"" + detail + "\"";
            }
        }

        @Override
        public String toString() {
            return reason == null ? "admitted" : text();
        }
    }

    private static final Pattern SITE = Pattern.compile("(?<![\\p{L}\\p{N}])(-?)site:(\\S+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NOT_A_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private SearchHygiene() {
    }

    /**
     * Whether {@code query} may run.
     *
     * @param earlierSearches the queries searched before (the caller decides which count, see the queue); a repeat
     *                        names the first one it repeats
     * @param blacklist       the typed blacklist; {@code null} = none
     */
    public static Verdict check(String query, Collection<String> earlierSearches, TypedBlacklist blacklist) {
        String text = query == null ? "" : query.replaceAll("\\s+", " ").trim();
        Parts parts = Parts.of(text);
        if (parts.words.isEmpty()) {
            return new Verdict(Reason.NO_WORDS, "", Collections.<String>emptyList());
        }
        if (text.length() < ConceptQueryPlan.MIN_QUERY_LENGTH) {
            return new Verdict(Reason.TOO_SHORT, "", Collections.<String>emptyList());
        }
        if (text.length() > ConceptQueryPlan.MAX_QUERY_LENGTH) {
            return new Verdict(Reason.TOO_LONG, "", Collections.<String>emptyList());
        }
        String hit = blacklistHit(text, parts, blacklist);
        if (hit != null) {
            return new Verdict(Reason.BLACKLIST, hit, Collections.<String>emptyList());
        }
        List<String> otherSite = new ArrayList<String>();
        if (earlierSearches != null) {
            String words = parts.wordKey();
            String key = parts.key();
            for (String earlier : earlierSearches) {
                Parts before = Parts.of(earlier == null ? "" : earlier);
                if (before.words.isEmpty() || !words.equals(before.wordKey())) {
                    continue;
                }
                if (key.equals(before.key())) {
                    return new Verdict(Reason.REPEAT, earlier.replaceAll("\\s+", " ").trim(), otherSite);
                }
                otherSite.add(earlier.replaceAll("\\s+", " ").trim());
            }
        }
        return new Verdict(null, "", otherSite);
    }

    /**
     * The identity of a search: its words in lower case, sorted, each once, and its {@code site:} restrictions.
     * Two queries with the same key are the same search.
     */
    public static String key(String query) {
        return Parts.of(query == null ? "" : query).key();
    }

    private static String blacklistHit(String text, Parts parts, TypedBlacklist blacklist) {
        if (blacklist == null || blacklist.isEmpty()) {
            return null;
        }
        String hit = blacklist.firstMatch(text, "", "");
        if (hit != null) {
            return hit;
        }
        for (String site : parts.sites) {
            if (!site.startsWith("-")) {
                hit = blacklist.firstMatch("", "", "https://" + site + "/");
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    /** A query taken apart: its words and its {@code site:} restrictions ({@code -site:} kept apart). */
    private static final class Parts {
        final TreeSet<String> words = new TreeSet<String>();
        final TreeSet<String> sites = new TreeSet<String>();

        static Parts of(String query) {
            Parts parts = new Parts();
            String text = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
            Matcher site = SITE.matcher(text);
            StringBuffer rest = new StringBuffer();
            while (site.find()) {
                String host = host(site.group(2));
                if (!host.isEmpty()) {
                    parts.sites.add(site.group(1) + host);
                }
                site.appendReplacement(rest, " ");
            }
            site.appendTail(rest);
            for (String word : NOT_A_WORD.split(rest.toString())) {
                if (!word.isEmpty()) {
                    parts.words.add(word);
                }
            }
            return parts;
        }

        /** "https://www.BauA.de/x" and "baua.de." both become "baua.de". */
        private static String host(String value) {
            String host = value.trim();
            int scheme = host.indexOf("://");
            if (scheme >= 0) {
                host = host.substring(scheme + 3);
            }
            for (char end : new char[] {'/', '?', '#', '"', '\'', ')'}) {
                int cut = host.indexOf(end);
                if (cut >= 0) {
                    host = host.substring(0, cut);
                }
            }
            while (host.endsWith(".")) {
                host = host.substring(0, host.length() - 1);
            }
            return host.startsWith("www.") ? host.substring(4) : host;
        }

        String wordKey() {
            return String.join(" ", words);
        }

        String key() {
            return sites.isEmpty() ? wordKey() : wordKey() + " site:" + String.join(",", sites);
        }
    }
}
