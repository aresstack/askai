package com.aresstack.askai.research.plan;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The search plan's own bar for "covered" at a research-cycle boundary, on top of the shared evidence analysis.
 * The analysis measures a node (distinct passages, sources, domains, breadth, balance) and calls it OK once it is
 * significant; that minimum (three distinct passages from two domains by default) says a topic exists, not that the
 * research behind a book chapter is done. Live run 4 (2026-11-12) declared all five concept nodes covered after the
 * first cycle, "Implementierung mit Kosten und Grenzen" with 5 to 12 passages from vendor pages, although the mission
 * preferred DGUV, BAuA, EU-OSHA and laws; no second cycle ran.
 * <p>
 * A node the analysis calls OK is still <em>weak</em> when
 * <ul>
 * <li>its distinct passages stay below {@link #getMinimumDistinctPassages()} (evidence quality), or</li>
 * <li>the mission names preferred sources and the share of its passages from preferred hosts stays below
 * {@link #getMinimumPreferredShare()}.</li>
 * </ul>
 * A weak node is a gap like a thin one: the next cycle plans it again, with the finding for the planner and the
 * mission's publisher queries ({@code site:}), and the outcome names it uncovered. Both bars are settings (Research →
 * Runtime → Research team → Gap review); 0 switches a bar off. Immutable.
 */
public final class CoverageQuality {

    /** No bar of its own: the analysis' status alone decides, as before. */
    public static final CoverageQuality OFF = new CoverageQuality(0, 0.0, Collections.<String>emptyList());

    private final int minimumDistinctPassages;
    private final double minimumPreferredShare;
    private final List<String> preferredHosts;

    /**
     * @param minimumDistinctPassages the distinct passages a node needs before the plan stops researching it; 0 = off
     * @param minimumPreferredShare   the share of a node's passages (or, without an analysis, of its accepted sources)
     *                                that must come from preferred hosts, in [0, 1]; 0 = off
     * @param preferredHosts          the preferred hosts and host suffixes ({@code .bund.de}); empty = no preference,
     *                                the share bar is off
     */
    public CoverageQuality(int minimumDistinctPassages, double minimumPreferredShare, List<String> preferredHosts) {
        this.minimumDistinctPassages = Math.max(0, minimumDistinctPassages);
        double share = Double.isNaN(minimumPreferredShare) ? 0.0 : minimumPreferredShare;
        this.minimumPreferredShare = Math.max(0.0, Math.min(1.0, share));
        List<String> hosts = new ArrayList<String>();
        if (preferredHosts != null) {
            for (String host : preferredHosts) {
                String h = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
                if (!h.isEmpty() && !hosts.contains(h)) {
                    hosts.add(h);
                }
            }
        }
        this.preferredHosts = Collections.unmodifiableList(hosts);
    }

    /**
     * The bars under the mission's source preference: the share bar applies only when the mission itself names
     * preferred sources ({@link SourcePreference#getTargets()}); the authority hosts alone are a ranking rule, not a
     * coverage demand.
     */
    public static CoverageQuality of(int minimumDistinctPassages, double minimumPreferredShare,
                                     SourcePreference preference) {
        SourcePreference p = preference == null ? SourcePreference.NONE : preference;
        return new CoverageQuality(minimumDistinctPassages, minimumPreferredShare,
                p.getTargets().isEmpty() ? Collections.<String>emptyList() : p.getPreferredHosts());
    }

    public int getMinimumDistinctPassages() {
        return minimumDistinctPassages;
    }

    public double getMinimumPreferredShare() {
        return minimumPreferredShare;
    }

    public List<String> getPreferredHosts() {
        return preferredHosts;
    }

    /** Whether the share bar applies (a mission preference and a share above 0). */
    public boolean checksPreferredShare() {
        return minimumPreferredShare > 0 && !preferredHosts.isEmpty();
    }

    /** Whether no bar applies. */
    public boolean isOff() {
        return minimumDistinctPassages == 0 && !checksPreferredShare();
    }

    /**
     * Whether a host is preferred: equal to a preferred domain or below it ({@code www.dguv.de} for {@code dguv.de}),
     * or ending with a preferred suffix ({@code .bund.de}). The same rule as the planned queries' ranking.
     */
    public boolean isPreferred(String host) {
        String h = hostOf(host);
        if (h.isEmpty()) {
            return false;
        }
        for (String p : preferredHosts) {
            if (p.startsWith(".") ? h.endsWith(p) || h.equals(p.substring(1)) : h.equals(p) || h.endsWith("." + p)) {
                return true;
            }
        }
        return false;
    }

    /** The bare host of a domain, an origin ("github.com/pf4j/pf4j") or a URL, without "www.". */
    static String hostOf(String value) {
        String h = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        int scheme = h.indexOf("://");
        if (scheme >= 0) {
            h = h.substring(scheme + 3);
        }
        int end = h.length();
        for (char stop : new char[]{'/', '?', '#', ':'}) {
            int at = h.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        h = h.substring(0, end);
        return h.startsWith("www.") ? h.substring(4) : h;
    }

    /**
     * Why a node the analysis calls OK is still weak, or {@code null} when it meets both bars.
     *
     * @param distinctPassages  the node's distinct passages in the analysis
     * @param passagesByDomain  the node's passages per source domain ("" = unknown domain, never preferred)
     */
    public String weakness(int distinctPassages, Map<String, Integer> passagesByDomain) {
        List<String> reasons = new ArrayList<String>();
        if (minimumDistinctPassages > 0 && distinctPassages < minimumDistinctPassages) {
            reasons.add("only " + distinctPassages + " distinct passages, the plan researches a node until "
                    + minimumDistinctPassages);
        }
        if (checksPreferredShare() && passagesByDomain != null) {
            int total = 0;
            int preferred = 0;
            for (Map.Entry<String, Integer> e : passagesByDomain.entrySet()) {
                int n = e.getValue() == null ? 0 : Math.max(0, e.getValue());
                total += n;
                if (isPreferred(e.getKey())) {
                    preferred += n;
                }
            }
            if (total > 0 && (double) preferred / total < minimumPreferredShare) {
                reasons.add(preferred + " of " + total + " passages (" + percent((double) preferred / total)
                        + ") from the preferred sources, at least " + percent(minimumPreferredShare)
                        + " wanted: search " + publishers());
            }
        }
        return reasons.isEmpty() ? null : join(reasons);
    }

    /**
     * Without a settled analysis: why a node whose source count covers it is still weak, from the domains of its
     * accepted sources (one entry per source), or {@code null}. Only the share bar applies; the passages are unknown.
     */
    public String weaknessOfSources(Collection<String> sourceDomains) {
        if (!checksPreferredShare() || sourceDomains == null || sourceDomains.isEmpty()) {
            return null;
        }
        int preferred = 0;
        for (String domain : sourceDomains) {
            if (isPreferred(domain)) {
                preferred++;
            }
        }
        double share = (double) preferred / sourceDomains.size();
        if (share >= minimumPreferredShare) {
            return null;
        }
        return preferred + " of " + sourceDomains.size() + " sources (" + percent(share)
                + ") from the preferred sources, at least " + percent(minimumPreferredShare) + " wanted: search "
                + publishers();
    }

    /** The preferred domains as {@code site:} targets, the suffixes as written. */
    private String publishers() {
        List<String> out = new ArrayList<String>();
        for (String host : preferredHosts) {
            out.add(host.startsWith(".") ? "*" + host : "site:" + host);
        }
        return String.join(", ", out);
    }

    private static String percent(double share) {
        return Math.round(share * 100) + " %";
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(sb.length() == 0 ? "" : "; ").append(part);
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        return "CoverageQuality{distinct>=" + minimumDistinctPassages + ", preferredShare>=" + minimumPreferredShare
                + ", hosts=" + preferredHosts + "}";
    }
}
