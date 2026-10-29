package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.domain.DomainClassification;
import com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver;
import com.aresstack.askai.browser.render.LinkRedirectResolution;
import com.aresstack.askai.browser.render.RenderedContainerDescriptor;
import com.aresstack.askai.browser.render.RenderedLinkDescriptor;
import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The Java side of the capture: conversion of the in-page result into a consistent
 * RenderedPageDocument — snapshot-local ids, similar-sibling computation, background distances,
 * search-redirect enrichment (the resolved DIRECT url is the navigation candidate, the raw wrapper
 * url stays diagnostic), domain classification against the page host, and the fingerprint guard
 * that settles + recaptures (bounded) and then marks the capture inconsistent instead of mixing
 * two DOM states.
 */
public class RenderedPageDocumentCaptureTest {

    private final List<Long> settles = new ArrayList<Long>();
    private final RenderedPageDocumentCapture capture = new RenderedPageDocumentCapture(
            LegacyBrowserSearchDefaults.create().analysis,
            new RenderedPageDocumentCapture.SettleDelay() {
                public void settle(long millis) {
                    settles.add(millis);
                }
            });

    @Test
    public void convertsContainersLinksAndSimilarSiblings() {
        FakeRunner page = new FakeRunner("https://www.bing.com/search?q=x", "SERP",
                Arrays.asList("f1", "f1", "f1")); // stable DOM
        page.result = result(
                Arrays.asList(
                        container("container-0001", "", 0, "body", "sig-body"),
                        container("container-0002", "container-0001", 0, "li", "sig-result"),
                        container("container-0003", "container-0001", 1, "li", "sig-result"),
                        container("container-0004", "container-0001", 2, "nav", "sig-nav")),
                Arrays.asList(
                        link("link-0001", "container-0002",
                                "https://www.google.com/url?q=https://example.org/page"),
                        link("link-0002", "container-0003", "https://docs.example.org/manual"),
                        link("link-0003", "container-0004", "https://www.bing.com/images/search")));
        RenderedPageDocument document = capture.capture(page,
                new PublicSuffixDomainKeyResolver(), 7L);

        assertEquals(4, document.containers.size());
        assertEquals(7L, document.snapshotGeneration);
        assertTrue(document.owns(document.snapshotId, "container-0002"));
        assertFalse("stale snapshot ids must never resolve",
                document.owns("snap-other", "container-0002"));

        RenderedContainerDescriptor result1 = document.container("container-0002");
        assertEquals("the two sig-result siblings see each other, not the nav",
                1, result1.similarSiblingCount);
        assertEquals(0, document.container("container-0004").similarSiblingCount);

        RenderedLinkDescriptor wrapped = document.links.get(0);
        assertEquals(LinkRedirectResolution.RESOLVED, wrapped.redirectResolutionStatus);
        assertEquals("the DIRECT target is the navigation candidate",
                "https://example.org/page", wrapped.resolvedTargetUrl);
        assertTrue("the raw wrapper stays diagnostic provenance",
                wrapped.rawHref.contains("google.com/url"));
        assertEquals(DomainClassification.EXTERNAL_DOMAIN, wrapped.domainClassification);

        RenderedLinkDescriptor engineInternal = document.links.get(2);
        assertEquals(LinkRedirectResolution.NOT_A_REDIRECT,
                engineInternal.redirectResolutionStatus);
        assertEquals("engine-internal target keeps the engine's own host relation",
                DomainClassification.SAME_HOST, engineInternal.domainClassification);
    }

    @Test
    public void domChangeThenSettledRecaptureHealsWithoutWarning() {
        // f1 → capture → f2 (mismatch) → settle → f3 → capture → f3 (stable): the recapture heals
        // the snapshot, no DOM_CHANGED warning survives into the ticket.
        FakeRunner page = new FakeRunner("https://www.bing.com/search?q=x", "SERP",
                Arrays.asList("f1", "f2", "f3", "f3"));
        page.result = result(
                Arrays.asList(container("container-0001", "", 0, "body", "s")),
                java.util.Collections.<Map<String, Object>>emptyList());
        RenderedPageDocument document = capture.capture(page,
                new PublicSuffixDomainKeyResolver(), 1L);
        assertEquals("one settled recapture must have run", 2, page.captureRuns);
        assertEquals("exactly one settle before the recapture", 1, settles.size());
        assertEquals(RenderedPageDocumentCapture.SETTLE_MILLIS, settles.get(0).longValue());
        assertTrue("a healed capture carries no consistency warning",
                document.captureWarnings.isEmpty());
    }

    @Test
    public void unsettledDomExhaustsBoundedRecapturesThenMarksInconsistent() {
        // Fingerprints keep changing through every settled recapture: after the bounded budget the
        // capture is marked inconsistent — never an unbounded loop, never silently mixed states.
        FakeRunner page = new FakeRunner("https://www.bing.com/search?q=x", "SERP",
                Arrays.asList("f1", "f2", "f3", "f4", "f5", "f6"));
        page.result = result(
                Arrays.asList(container("container-0001", "", 0, "body", "s")),
                java.util.Collections.<Map<String, Object>>emptyList());
        RenderedPageDocument document = capture.capture(page,
                new PublicSuffixDomainKeyResolver(), 1L);
        assertEquals("initial capture + the bounded recaptures",
                1 + RenderedPageDocumentCapture.MAX_RECAPTURES, page.captureRuns);
        assertEquals(RenderedPageDocumentCapture.MAX_RECAPTURES, settles.size());
        assertTrue(describe(document.captureWarnings).contains("DOM_CHANGED_DURING_CAPTURE"));
    }

    @Test
    public void stableDomYieldsNoConsistencyWarning() {
        FakeRunner page = new FakeRunner("https://www.bing.com/search?q=x", "SERP",
                Arrays.asList("f1", "f1"));
        page.result = result(
                Arrays.asList(container("container-0001", "", 0, "body", "s")),
                java.util.Collections.<Map<String, Object>>emptyList());
        RenderedPageDocument document = capture.capture(page,
                new PublicSuffixDomainKeyResolver(), 1L);
        assertEquals(1, page.captureRuns);
        assertTrue(document.captureWarnings.isEmpty());
    }

    // ------------------------------------------------------------------ fixture helpers

    private static Map<String, Object> result(List<Map<String, Object>> containers,
                                              List<Map<String, Object>> links) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("viewportWidth", 1280);
        map.put("viewportHeight", 800);
        map.put("documentWidth", 1280);
        map.put("documentHeight", 2400);
        map.put("truncated", false);
        map.put("warnings", new ArrayList<Object>());
        map.put("containers", containers);
        map.put("links", links);
        return map;
    }

    private static Map<String, Object> container(String id, String parentId, int siblingIndex,
                                                 String tag, String signature) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("parentId", parentId);
        map.put("childIds", new ArrayList<Object>());
        map.put("siblingIndex", siblingIndex);
        map.put("depth", parentId.isEmpty() ? 0 : 1);
        map.put("tag", tag);
        map.put("elementId", "");
        map.put("classes", new ArrayList<Object>());
        map.put("role", "");
        map.put("ariaLabel", "");
        map.put("semanticFlags", new ArrayList<Object>());
        map.put("textExcerpt", "Example result title Explanatory snippet text.");
        map.put("textLength", 120);
        map.put("linkTextLength", 30);
        map.put("headingCount", 1);
        map.put("paragraphCount", 1);
        map.put("linkCount", 1);
        map.put("sameHostLinks", 0);
        map.put("sameRegistrableLinks", 0);
        map.put("subdomainLinks", 0);
        map.put("externalLinks", 1);
        map.put("actionLinks", 0);
        map.put("visible", true);
        map.put("box", box(100, 100, 600, 120));
        map.put("viewportIntersection", 1.0);
        map.put("containsCenter", false);
        map.put("centerDx", 0.1);
        map.put("centerDy", 0.2);
        map.put("computedBg", rgba(255, 255, 255, 1));
        map.put("effectiveBg", rgba(255, 255, 255, 1));
        map.put("border", "");
        map.put("borderRadius", 0.0);
        map.put("boxShadow", "");
        map.put("padding", 8.0);
        map.put("margin", 4.0);
        map.put("signature", signature);
        return map;
    }

    private static Map<String, Object> link(String id, String containerId, String href) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("containerId", containerId);
        map.put("href", href);
        map.put("text", "Example result title");
        map.put("surrounding", "Explanatory snippet text.");
        map.put("heading", "Example result title");
        map.put("displayedDomain", "example.org");
        map.put("insideHeading", true);
        map.put("visible", true);
        map.put("box", box(100, 100, 400, 20));
        return map;
    }

    private static Map<String, Object> box(double x, double y, double w, double h) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("x", x);
        map.put("y", y);
        map.put("w", w);
        map.put("h", h);
        return map;
    }

    private static List<Object> rgba(int r, int g, int b, double a) {
        return Arrays.<Object>asList(r, g, b, a);
    }

    private static String describe(List<String> warnings) {
        StringBuilder sb = new StringBuilder();
        for (String warning : warnings) {
            sb.append(warning).append('\n');
        }
        return sb.toString();
    }

    /** Serves fingerprints from a queue and the canned capture result for the capture script. */
    private static final class FakeRunner implements RenderedPageDocumentCapture.PageScriptRunner {
        private final String url;
        private final String title;
        private final List<String> fingerprints = new ArrayList<String>();
        Map<String, Object> result;
        int captureRuns;
        private int fingerprintIndex;

        FakeRunner(String url, String title, List<String> fingerprints) {
            this.url = url;
            this.title = title;
            this.fingerprints.addAll(fingerprints);
        }

        public Object evaluate(String script) {
            if (script.equals(RenderedPageDocumentCapture.FINGERPRINT_SCRIPT)) {
                String fingerprint = fingerprints.get(
                        Math.min(fingerprintIndex, fingerprints.size() - 1));
                fingerprintIndex++;
                return fingerprint;
            }
            captureRuns++;
            return result;
        }

        public String url() {
            return url;
        }

        public String title() {
            return title;
        }
    }

    /**
     * The capture script never reads user-facing text via raw textContent: that includes the content of
     * <style>/<script> elements, which turned Bing's inline CSS into stored search excerpts. Every
     * excerpt, link text and heading goes through the SKIP-aware textOf helper.
     */
    @org.junit.Test
    public void userFacingTextNeverComesFromRawTextContent() {
        String script = capture.captureScript();
        org.junit.Assert.assertTrue("the SKIP-aware text helper exists",
                script.contains("const textOf ="));
        org.junit.Assert.assertTrue("excerpts use it", script.contains("textExcerpt: textOf(el)"));
        org.junit.Assert.assertTrue("link texts use it", script.contains("text: textOf(a)"));
        org.junit.Assert.assertFalse("no user-facing raw textContent excerpt remains",
                script.contains("clean(el.textContent)"));
        org.junit.Assert.assertFalse(script.contains("clean(ctx.containerEl.textContent)"));
    }
}
