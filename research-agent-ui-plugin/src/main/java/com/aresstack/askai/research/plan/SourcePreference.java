package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The source preference of the mission as a rule of the search plan. The mission of the 2026-11-10 live run said
 * "Bevorzuge frei zugängliche Primärquellen (DGUV, BAuA, EU-OSHA, Gesetze, wissenschaftliche Studien)", and the
 * book cited not one of them: the planner never heard of the preference, and the ranking weighed a vendor blog
 * like a guideline of the statutory accident insurance.
 * <p>
 * The rule has two halves, both deterministic and both settings:
 * <ul>
 * <li><b>Queries:</b> per node and cycle up to {@link Settings#getQueriesPerNode()} additional queries aimed at a
 * preferred publisher: {@code <base query> site:<domain>} for a publisher with a known domain (or a domain written
 * in the mission), {@code <base query> <term>} for a preferred kind without one ("Studien"). The target rotates
 * with the node and the cycle, so later cycles ask the next publisher.</li>
 * <li><b>Ranking:</b> the hosts of the preferred publishers and the authority host suffixes
 * ({@link #getPreferredHosts()}) move up by {@link Settings#getRankShift()} places among the selected hits of a
 * planned query, pages whose URL carries a vendor marker ({@link #getDemotedMarkers()}: blog, product, solution
 * pages) move down by as many. Nothing is forbidden: the Weidezaun and the blacklist stay the only hard limits.</li>
 * </ul>
 * Immutable; {@link #NONE} when the setting is off or the mission names no preference and no authority host is set.
 */
public final class SourcePreference {

    /** The settings the rule is built from (Research → Source preference). */
    public static final class Settings {
        private final boolean enabled;
        private final List<String> cues;
        private final Map<String, String> publisherDomains;
        private final List<String> authorityHosts;
        private final List<String> demotedMarkers;
        private final int queriesPerNode;
        private final int rankShift;

        public Settings(boolean enabled, List<String> cues, Map<String, String> publisherDomains,
                        List<String> authorityHosts, List<String> demotedMarkers, int queriesPerNode, int rankShift) {
            this.enabled = enabled;
            this.cues = lower(cues);
            Map<String, String> domains = new LinkedHashMap<String, String>();
            if (publisherDomains != null) {
                for (Map.Entry<String, String> e : publisherDomains.entrySet()) {
                    String name = e.getKey() == null ? "" : e.getKey().trim().toLowerCase(Locale.ROOT);
                    String host = host(e.getValue());
                    if (!name.isEmpty() && !host.isEmpty()) {
                        domains.put(name, host);
                    }
                }
            }
            this.publisherDomains = Collections.unmodifiableMap(domains);
            this.authorityHosts = lower(authorityHosts);
            this.demotedMarkers = lower(demotedMarkers);
            this.queriesPerNode = Math.max(0, queriesPerNode);
            this.rankShift = Math.max(0, rankShift);
        }

        public boolean isEnabled() {
            return enabled;
        }

        /** The words that open the preference clause of a mission ("bevorzuge", "prefer"). */
        public List<String> getCues() {
            return cues;
        }

        /** Publisher name (lower case) → its domain. */
        public Map<String, String> getPublisherDomains() {
            return publisherDomains;
        }

        /** Host suffixes of authorities that are preferred in the ranking whatever the mission says. */
        public List<String> getAuthorityHosts() {
            return authorityHosts;
        }

        /** URL parts of vendor pages that move down in the ranking. */
        public List<String> getDemotedMarkers() {
            return demotedMarkers;
        }

        /** The additional publisher queries per node and cycle; 0 = none. */
        public int getQueriesPerNode() {
            return queriesPerNode;
        }

        /** The places a preferred hit moves up and a vendor page moves down; 0 = the ranking stays as it is. */
        public int getRankShift() {
            return rankShift;
        }
    }

    /** No preference: no extra query, the ranking stays as it is. */
    public static final SourcePreference NONE = new SourcePreference(Collections.<String>emptyList(),
            Collections.<String>emptyList(), Collections.<String>emptyList(), Collections.<String>emptyList(), 0, 0);

    private static final Pattern DOMAIN = Pattern.compile("(?i)^(?:https?://)?(?:www\\.)?([a-z0-9-]+(?:\\.[a-z0-9-]+)+)/?$");
    private static final Pattern SPLIT = Pattern.compile("\\s*(?:,|;|/|\\bund\\b|\\band\\b|\\boder\\b|\\bor\\b)\\s*",
            Pattern.CASE_INSENSITIVE);

    private final List<String> targets;
    private final List<String> preferredHosts;
    private final List<String> demotedMarkers;
    private final List<String> terms;
    private final int queriesPerNode;
    private final int rankShift;

    private SourcePreference(List<String> targets, List<String> preferredHosts, List<String> demotedMarkers,
                             List<String> terms, int queriesPerNode, int rankShift) {
        this.targets = Collections.unmodifiableList(new ArrayList<String>(targets));
        this.preferredHosts = Collections.unmodifiableList(new ArrayList<String>(preferredHosts));
        this.demotedMarkers = Collections.unmodifiableList(new ArrayList<String>(demotedMarkers));
        this.terms = Collections.unmodifiableList(new ArrayList<String>(terms));
        this.queriesPerNode = queriesPerNode;
        this.rankShift = rankShift;
    }

    /** The preference of a mission under the settings. */
    public static SourcePreference of(String mission, Settings settings) {
        if (settings == null || !settings.isEnabled()) {
            return NONE;
        }
        List<String> terms = preferredTerms(mission, settings.getCues());
        LinkedHashSet<String> targets = new LinkedHashSet<String>();
        LinkedHashSet<String> hosts = new LinkedHashSet<String>();
        for (String term : terms) {
            String domain = host(term);
            if (domain.isEmpty()) {
                domain = publisherDomain(term, settings.getPublisherDomains());
            }
            if (!domain.isEmpty()) {
                targets.add("site:" + domain);
                hosts.add(domain);
            } else {
                targets.add(term);
            }
        }
        hosts.addAll(settings.getAuthorityHosts());
        if (targets.isEmpty() && hosts.isEmpty() && settings.getDemotedMarkers().isEmpty()) {
            return NONE;
        }
        return new SourcePreference(new ArrayList<String>(targets), new ArrayList<String>(hosts),
                settings.getDemotedMarkers(), terms, settings.getQueriesPerNode(), settings.getRankShift());
    }

    /**
     * The terms of the mission's preference clause: the text after the first cue up to the end of its sentence,
     * the part in parentheses when there is one, split at commas, slashes and "und/oder/and/or".
     */
    static List<String> preferredTerms(String mission, List<String> cues) {
        List<String> out = new ArrayList<String>();
        if (mission == null || mission.trim().isEmpty() || cues == null) {
            return out;
        }
        String lower = mission.toLowerCase(Locale.ROOT);
        int at = -1;
        String cue = "";
        for (String c : cues) {
            Matcher m = Pattern.compile("\\b" + Pattern.quote(c)).matcher(lower);
            if (m.find() && (at < 0 || m.start() < at)) {
                at = m.start();
                cue = c;
            }
        }
        if (at < 0) {
            return out;
        }
        String rest = mission.substring(at + cue.length());
        int open = rest.indexOf('(');
        int end = sentenceEnd(rest);
        String clause;
        if (open >= 0 && open < end) {
            int close = rest.indexOf(')', open);
            clause = rest.substring(open + 1, close < 0 ? rest.length() : close);
        } else {
            clause = rest.substring(0, end);
            int colon = clause.indexOf(':');
            if (colon >= 0) {
                clause = clause.substring(colon + 1);
            }
        }
        LinkedHashSet<String> unique = new LinkedHashSet<String>();
        for (String part : SPLIT.split(clause)) {
            String term = part.replaceAll("[\"'„“”«»]", "").trim();
            if (term.length() >= 2) {
                unique.add(term);
            }
        }
        out.addAll(unique);
        return out;
    }

    /** The end of the sentence (". ", ";", "!" or the end), never a dot inside a domain. */
    private static int sentenceEnd(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ';' || c == '!' || c == '\n') {
                return i;
            }
            if (c == '.' && (i + 1 >= text.length() || Character.isWhitespace(text.charAt(i + 1)))) {
                return i;
            }
        }
        return text.length();
    }

    private static String publisherDomain(String term, Map<String, String> domains) {
        String lower = term.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> e : domains.entrySet()) {
            if (Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}])")
                    .matcher(lower).find()) {
                return e.getValue();
            }
        }
        return "";
    }

    /** The host of a domain-shaped term ("baua.de", "https://www.dguv.de/"); empty otherwise. */
    static String host(String value) {
        if (value == null) {
            return "";
        }
        Matcher m = DOMAIN.matcher(value.trim());
        return m.matches() ? m.group(1).toLowerCase(Locale.ROOT) : "";
    }

    private static List<String> lower(List<String> values) {
        List<String> out = new ArrayList<String>();
        if (values != null) {
            for (String v : values) {
                String t = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
                if (!t.isEmpty() && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** Whether this preference adds nothing and changes no ranking. */
    public boolean isNone() {
        return targets.isEmpty() && (rankShift == 0 || (preferredHosts.isEmpty() && demotedMarkers.isEmpty()));
    }

    /** The query targets in mission order: {@code site:<domain>} or a preferred term. */
    public List<String> getTargets() {
        return targets;
    }

    /** The terms of the mission's preference clause. */
    public List<String> getTerms() {
        return terms;
    }

    /** The hosts and host suffixes preferred in the ranking. */
    public List<String> getPreferredHosts() {
        return preferredHosts;
    }

    /** The URL parts of vendor pages that move down in the ranking. */
    public List<String> getDemotedMarkers() {
        return demotedMarkers;
    }

    /** The places a preferred hit moves up and a vendor page moves down. */
    public int getRankShift() {
        return rankShift;
    }

    /**
     * The publisher queries of one node in one cycle: up to the per-node setting, each the base query with the next
     * target, rotating with the node's position and the cycle so the cycles ask different publishers. Never longer
     * than {@link ConceptQueryPlan#MAX_QUERY_LENGTH}; a base query that already names a {@code site:} gets none.
     *
     * @param nodeIndex the node's position in the plan (spreads the publishers over the nodes)
     * @param cycle     the research cycle the queries are for (1 = the seed plan)
     */
    public List<String> queriesFor(String baseQuery, int nodeIndex, int cycle) {
        List<String> out = new ArrayList<String>();
        String base = baseQuery == null ? "" : baseQuery.trim();
        if (base.isEmpty() || targets.isEmpty() || queriesPerNode == 0
                || base.toLowerCase(Locale.ROOT).contains("site:")) {
            return out;
        }
        int count = Math.min(queriesPerNode, targets.size());
        int start = Math.abs(nodeIndex + (Math.max(1, cycle) - 1) * count) % targets.size();
        for (int i = 0; i < targets.size() && out.size() < count; i++) {
            String target = targets.get((start + i) % targets.size());
            String query = base + " " + target;
            if (query.length() <= ConceptQueryPlan.MAX_QUERY_LENGTH && !out.contains(query)) {
                out.add(query);
            }
        }
        return out;
    }
}
