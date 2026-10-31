package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.analysis.LegacySearchResultExtractor;
import com.google.gson.Gson;
import org.junit.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Live acceptance of #28 (Angelo, 2026-10-31, "Java 21 LTS release"): Google searched, AskAI extracted nothing
 * ({@code EXTRACTION_FAILED} on all three result pages). Google's 2026 result links are {@code /goto?url=<token>}:
 * the token is opaque, the target is not in the URL. The resolver only knew {@code /url?q=}, so every result link
 * counted as a Google-internal link and nothing external was left to extract.
 *
 * <p>{@code chromium-capture.json} is the current capture script run on the real result page Camoufox saved in that
 * run ({@code page.html.gz}, sanitized: scripts and HTML comments removed (Google keeps result data in them),
 * session parameters and event ids blanked, the location line neutralised, every {@code /goto} token replaced by
 * {@code test-goto-N}). {@code goto-locations.tsv} holds what Google answered for each real token at capture time:
 * the {@code Location} of its 302, the redirect not followed.</p>
 */
public class GoogleGotoRegressionTest {

    private static final String DIR = "/serp/google-java21-2026-10-31/";
    private static final String QUERY = "Java 21 LTS release";
    private static final String URL = "https://www.google.com/search?q=Java+21+LTS+release";

    private final LegacyBrowserSearchSettings settings = LegacyBrowserSearchDefaults.create();

    @Test
    public void withoutGooglesAnswerNoGotoLinkCountsAsAGoogleLinkOrAsAResult() throws Exception {
        RenderedPageDocument page = capture(new HashMap<String, String>());

        List<RenderedLinkDescriptor> gotos = gotoLinks(page);
        assertEquals(33, gotos.size());
        for (RenderedLinkDescriptor link : gotos) {
            assertEquals(link.rawHref, LinkRedirectResolution.UNRESOLVED, link.redirectResolutionStatus);
            assertEquals(link.rawHref, "", link.resolvedTargetUrl);
            assertNotEquals("never judged by the wrapper's own host: " + link.rawHref,
                    DomainClassification.SAME_HOST, link.domainClassification);
        }
        SearchResultExtractionResult extraction = new LegacySearchResultExtractor(settings).extract(page, QUERY);
        for (SearchResultCandidate candidate : extraction.candidates) {
            assertFalse("a Google wrapper is never a result: " + candidate.resolvedTargetUrl,
                    candidate.resolvedTargetUrl.contains("google.com"));
        }
    }

    @Test
    public void withGooglesRedirectAnswerTheVisibleResultsAreExtractedWithTheirRealTargets() throws Exception {
        RenderedPageDocument page = capture(locations());

        SearchResultExtractionResult extraction = new LegacySearchResultExtractor(settings).extract(page, QUERY);

        assertEquals(extraction.diagnostics.toString(), SearchPageAnalysisOutcome.ORGANIC_RESULTS, extraction.outcome);
        Set<String> targets = new LinkedHashSet<String>();
        for (SearchResultCandidate candidate : extraction.candidates) {
            assertFalse("only the real target becomes a link: " + candidate.resolvedTargetUrl,
                    candidate.resolvedTargetUrl.contains("/goto"));
            assertFalse("Google's translate links are Google's own: " + candidate.resolvedTargetUrl,
                    candidate.resolvedTargetUrl.startsWith("https://translate.google.com/"));
            targets.add(candidate.resolvedTargetUrl);
        }
        assertTrue(targets.toString(), targets.containsAll(Arrays.asList(
                "https://openjdk.org/projects/jdk/21/",
                "https://www.oracle.com/java/technologies/javase/21all-relnotes.html",
                "https://www.oracle.com/de/java/technologies/java-se-support-roadmap.html",
                "https://en.wikipedia.org/wiki/Java_version_history",
                "https://adoptium.net/temurin/releases/?version=21",
                "https://www.cmt.de/programmierung/java-lts-version-waehlen/",
                "https://www.oracle.com/java/technologies/javase/21-0-12-1-relnotes.html",
                "https://bluepes.com/blog/why-java-21-is-still-the-enterprise-standard",
                "https://www.oracle.com/de/java/technologies/downloads/")));
    }

    @Test
    public void anAnswerThatIsNoUsableRedirectLeavesTheLinkUnresolved() throws Exception {
        Map<String, String> odd = new HashMap<String, String>();
        odd.put("test-goto-7", "https://www.google.com/goto?url=again"); // a loop
        odd.put("test-goto-9", "javascript:alert(1)");
        odd.put("test-goto-11", "/sorry/index?continue=x"); // Google's own page, relative
        RenderedPageDocument page = capture(odd);

        for (RenderedLinkDescriptor link : gotoLinks(page)) {
            if (link.rawHref.endsWith("=test-goto-11")) {
                assertEquals(LinkRedirectResolution.RESOLVED, link.redirectResolutionStatus);
                assertEquals("https://www.google.com/sorry/index?continue=x", link.resolvedTargetUrl);
                assertNotEquals(DomainClassification.EXTERNAL_DOMAIN, link.domainClassification);
            } else {
                assertEquals(link.rawHref, LinkRedirectResolution.UNRESOLVED, link.redirectResolutionStatus);
            }
        }
    }

    private static List<RenderedLinkDescriptor> gotoLinks(RenderedPageDocument page) {
        List<RenderedLinkDescriptor> gotos = new ArrayList<RenderedLinkDescriptor>();
        for (RenderedLinkDescriptor link : page.links) {
            if (link.rawHref.startsWith("https://www.google.com/goto?url=")) {
                gotos.add(link);
            }
        }
        return gotos;
    }

    /** Google's answers at capture time, by token. */
    private static Map<String, String> locations() throws Exception {
        Map<String, String> byToken = new HashMap<String, String>();
        for (String line : read("goto-locations.tsv").split("\n")) {
            String[] parts = line.trim().split("\t");
            if (parts.length == 2) {
                byToken.put(parts[0], parts[1]);
            }
        }
        return byToken;
    }

    /** Feeds the raw capture through the real Java side; Google "answers" each wrapper from {@code answers}. */
    private RenderedPageDocument capture(final Map<String, String> answers) throws Exception {
        @SuppressWarnings("unchecked")
        final Map<String, Object> raw = new Gson().fromJson(read("chromium-capture.json"), Map.class);
        RenderedPageDocumentCapture capture = new RenderedPageDocumentCapture(settings.analysis);
        return capture.capture(new RenderedPageDocumentCapture.PageScriptRunner() {
            public Object evaluate(String script) {
                return script.equals(RenderedPageDocumentCapture.FINGERPRINT_SCRIPT) ? "f" : raw;
            }

            public String url() {
                return URL;
            }

            public String title() {
                return QUERY + " - Google Suche";
            }

            public Map<String, String> redirectLocations(java.util.Collection<String> wrapperUrls) {
                Map<String, String> byWrapper = new HashMap<String, String>();
                for (String wrapper : wrapperUrls) {
                    String answer = answers.get(SearchRedirectResolver.queryParameter(wrapper, "url"));
                    if (answer != null) {
                        byWrapper.put(wrapper, answer);
                    }
                }
                return byWrapper;
            }
        }, new PublicSuffixDomainKeyResolver(), 1L);
    }

    private static String read(String name) throws Exception {
        InputStream in = GoogleGotoRegressionTest.class.getResourceAsStream(DIR + name);
        if (in == null) {
            throw new IllegalStateException("missing test resource " + DIR + name);
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            for (int n; (n = reader.read(buffer)) > 0; ) {
                sb.append(buffer, 0, n);
            }
            return sb.toString();
        }
    }
}
