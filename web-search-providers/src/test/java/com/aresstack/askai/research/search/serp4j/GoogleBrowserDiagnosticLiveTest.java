package com.aresstack.askai.research.search.serp4j;

import com.aresstack.serp4j.browser.BrowserSession;
import com.aresstack.serp4j.browser.BrowserSessionOptions;
import com.aresstack.serp4j.browser.NavigationRequest;
import com.aresstack.serp4j.browser.NavigationResult;
import com.aresstack.serp4j.browser.PageSnapshot;
import com.aresstack.serp4j.browser.ProfileMode;
import com.aresstack.serp4j.browser.SnapshotOptions;
import com.aresstack.serp4j.browser.WaitUntil;
import com.aresstack.serp4j.browser.playwright.PlaywrightBrowserRuntime;
import org.junit.Assume;
import org.junit.Test;

/**
 * Diagnostic for the live check: opens the same Google URL through the Serp4J browser port and prints only the final
 * URL, HTTP status, title and a few structural markers (no page content). Opt-in with
 * {@code -Daskai.live.googleBrowser=true}.
 */
public final class GoogleBrowserDiagnosticLiveTest {

    @Test
    public void printsWhatGoogleAnswers() throws Exception {
        Assume.assumeTrue("live test disabled", Boolean.getBoolean("askai.live.googleBrowser"));
        PlaywrightBrowserRuntime runtime = PlaywrightBrowserRuntime.builder()
                .channel(System.getProperty("askai.live.googleBrowser.channel", "msedge"))
                .headless(!"false".equals(System.getProperty("askai.live.googleBrowser.headless")))
                .build();
        try {
            BrowserSession session = runtime.openSession(BrowserSessionOptions.builder()
                    .profileMode(ProfileMode.ephemeral()).build());
            try {
                NavigationResult nav = session.navigate(NavigationRequest
                        .to("https://www.google.com/search?q=elasticsearch&hl=de&gl=de")
                        .waitUntil(WaitUntil.DOM_CONTENT_LOADED));
                PageSnapshot snap = session.snapshot(SnapshotOptions.urlAndTitle().withHtml().maxChars(4000000));
                String html = snap.getHtml().orElse("");
                System.out.println("[google-diag] finalUrl=" + host(nav.getFinalUrl()) + " status="
                        + (nav.getHttpStatus().isPresent() ? nav.getHttpStatus().getAsInt() : -1)
                        + " title=" + snap.getTitle() + " htmlChars=" + html.length()
                        + " captchaForm=" + html.contains("captcha-form") + " recaptcha=" + html.contains("g-recaptcha")
                        + " sorry=" + html.contains("/sorry/") + " enablejs=" + html.contains("enablejs")
                        + " rso=" + html.contains("id=\"rso\"") + " search=" + html.contains("id=\"search\"")
                        + " obstacle=" + nav.getObstacle());
            } finally {
                session.close();
            }
        } finally {
            runtime.close();
        }
    }

    /** Host and path only, so no tracking parameters are printed. */
    private static String host(String url) {
        if (url == null) {
            return "null";
        }
        int q = url.indexOf('?');
        return q < 0 ? url : url.substring(0, q);
    }
}
