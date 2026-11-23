package com.aresstack.askai.research.plan;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The search plan's own bar for "covered" with data shaped like live run 4 (2026-11-12): five concept nodes after
 * cycle 1, "Implementierung mit Kosten und Grenzen" read from vendor pages only, the mission preferring DGUV, BAuA,
 * EU-OSHA and laws.
 */
public class CoverageQualityTest {

    static SourcePreference run4Preference() {
        return SourcePreference.of(SourcePreferenceTest.RUN3, SourcePreferenceTest.settings(1, 3));
    }

    /** "Implementierung mit Kosten und Grenzen" after cycle 1 of live run 4: 12 passages, all from vendor pages. */
    static Map<String, Integer> run4Implementation() {
        Map<String, Integer> byDomain = new LinkedHashMap<String, Integer>();
        byDomain.put("smart-construction.example", 4);
        byDomain.put("wearable-vendor.example", 3);
        byDomain.put("linkedin.com", 3);
        byDomain.put("facebook.com", 2);
        return byDomain;
    }

    /** "Rechtliche Rahmenbedingungen": 30 passages, a third from DGUV, BAuA, laws and a federal authority. */
    static Map<String, Integer> run4Law() {
        Map<String, Integer> byDomain = new LinkedHashMap<String, Integer>();
        byDomain.put("publikationen.dguv.de", 4);
        byDomain.put("baua.de", 2);
        byDomain.put("gesetze-im-internet.de", 2);
        byDomain.put("bfdi.bund.de", 2);
        byDomain.put("e-recht24.de", 12);
        byDomain.put("datenschutz-blog.example", 8);
        return byDomain;
    }

    @Test
    public void aNodeReadFromVendorPagesOnlyIsWeakAndNamesThePublishersToSearch() {
        CoverageQuality quality = CoverageQuality.of(16, 0.2, run4Preference());
        String weakness = quality.weakness(11, run4Implementation());
        assertTrue(weakness, weakness.startsWith("only 11 distinct passages, the plan researches a node until 16"));
        assertTrue(weakness, weakness.contains("0 of 12 passages (0 %) from the preferred sources, at least 20 % "
                + "wanted: search site:dguv.de, site:baua.de, site:osha.europa.eu, site:gesetze-im-internet.de, "
                + "*.bund.de"));
    }

    @Test
    public void aNodeWithEnoughMaterialAndAThirdFromAuthoritiesIsCovered() {
        CoverageQuality quality = CoverageQuality.of(16, 0.2, run4Preference());
        assertNull(quality.weakness(25, run4Law()));
    }

    @Test
    public void enoughPassagesFromVendorsAloneStayWeak() {
        CoverageQuality quality = CoverageQuality.of(16, 0.2, run4Preference());
        Map<String, Integer> vendors = new LinkedHashMap<String, Integer>();
        vendors.put("vendor-a.example", 20);
        vendors.put("vendor-b.example", 18);
        vendors.put("dguv.de", 2);
        String weakness = quality.weakness(35, vendors);
        assertTrue(weakness, weakness.startsWith("2 of 40 passages (5 %) from the preferred sources"));
        assertFalse(weakness, weakness.contains("distinct passages"));
    }

    @Test
    public void withoutAMissionPreferenceOnlyTheDistinctPassagesCount() {
        CoverageQuality quality = CoverageQuality.of(16, 0.2, SourcePreference.NONE);
        assertFalse(quality.checksPreferredShare());
        assertNull("vendor pages are no reason without a preference", quality.weakness(20, run4Implementation()));
        assertTrue(quality.weakness(5, run4Implementation()).startsWith("only 5 distinct passages"));
    }

    @Test
    public void authorityHostsAloneAreARankingRuleNotACoverageDemand() {
        SourcePreference authoritiesOnly = SourcePreference.of("Ein Buch über Wearables.",
                SourcePreferenceTest.settings(1, 3));
        assertTrue(authoritiesOnly.getTargets().isEmpty());
        assertFalse(CoverageQuality.of(16, 0.2, authoritiesOnly).checksPreferredShare());
    }

    @Test
    public void zeroSwitchesEachBarOff() {
        CoverageQuality off = CoverageQuality.of(0, 0.0, run4Preference());
        assertTrue(off.isOff());
        assertNull(off.weakness(1, run4Implementation()));
        assertTrue(CoverageQuality.OFF.isOff());
        assertNull(CoverageQuality.OFF.weaknessOfSources(Arrays.asList("vendor.example")));
    }

    @Test
    public void preferredHostsMatchDomainsSubdomainsSuffixesOriginsAndUrls() {
        CoverageQuality quality = CoverageQuality.of(16, 0.2, run4Preference());
        assertTrue(quality.isPreferred("dguv.de"));
        assertTrue(quality.isPreferred("publikationen.dguv.de"));
        assertTrue(quality.isPreferred("www.baua.de"));
        assertTrue(quality.isPreferred("bfdi.bund.de"));
        assertTrue(quality.isPreferred("https://www.gesetze-im-internet.de/arbschg/__3.html"));
        assertTrue(quality.isPreferred("osha.europa.eu/de/publications"));
        assertFalse(quality.isPreferred("notdguv.de"));
        assertFalse(quality.isPreferred("dguv.de.vendor.example"));
        assertFalse(quality.isPreferred(""));
    }

    @Test
    public void withoutAnAnalysisTheShareOfAcceptedSourcesCounts() {
        CoverageQuality quality = CoverageQuality.of(16, 0.2, run4Preference());
        assertNull(quality.weaknessOfSources(Arrays.asList("dguv.de", "vendor.example", "baua.de", "blog.example")));
        String weakness = quality.weaknessOfSources(Arrays.asList("vendor.example", "blog.example", "linkedin.com",
                "shop.example", "dguv.de", "news.example"));
        assertTrue(weakness, weakness.startsWith("1 of 6 sources (17 %) from the preferred sources"));
        assertNull("no accepted source: the source counts call it uncovered anyway",
                quality.weaknessOfSources(Collections.<String>emptyList()));
    }

    @Test
    public void aShareOutsideZeroToOneIsClamped() {
        assertEquals(1.0, new CoverageQuality(0, 7.0, Arrays.asList("dguv.de")).getMinimumPreferredShare(), 0.0);
        assertEquals(0.0, new CoverageQuality(0, -1.0, Arrays.asList("dguv.de")).getMinimumPreferredShare(), 0.0);
    }
}
