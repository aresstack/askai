package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the guard-script policy: consent clicks only UNAMBIGUOUSLY positive controls (no
 * first-button-in-container guessing — that could hit "Settings" or "Only necessary"), and the
 * challenge detection knows the required texts and selectors. The lists come from the settings
 * (single default origin: LegacyBrowserSearchDefaults) — the guards hold no constants of their own.
 */
public class SearchPageGuardsTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    @Test
    public void consentScriptHasNoFirstButtonGuessingAndOnlyPositiveTexts() {
        String script = SearchPageGuards.consentDismissScript(defaults.consent);
        assertFalse("no dangerous first-button heuristic", script.contains(":first-of-type"));
        for (String selector : defaults.consent.positiveButtonSelectors) {
            assertFalse("selector must be accept-specific: " + selector,
                    selector.contains(":first-of-type"));
        }
        for (String required : new String[]{"accept all", "allow all", "i agree",
                "alle akzeptieren", "alle annehmen", "alle zulassen", "zustimmen"}) {
            assertTrue("positive text missing: " + required,
                    defaults.consent.positiveButtonTexts.contains(required));
        }
        assertTrue(script.contains("clicked"));
    }

    @Test
    public void consentResolutionPrefersRejectThenNecessaryThenAcceptThenClose() {
        String script = SearchPageGuards.consentDismissScript(defaults.consent);
        int reject = script.indexOf("REJECT_ALL");
        int necessary = script.indexOf("ONLY_NECESSARY");
        int accept = script.indexOf("ACCEPT_ALL");
        int close = script.indexOf("CLOSE");
        assertTrue("reject tried first", reject >= 0 && reject < necessary);
        assertTrue("only-necessary before accept", necessary < accept);
        assertTrue("accept before close (close is last resort)", accept < close);
        // Unambiguous reject controls (never a first-button guess); the accept terms stay the fallback.
        assertTrue(script.contains("onetrust-reject-all-handler"));
        assertTrue(script.contains("reject all"));
        assertTrue(script.contains("only necessary") || script.contains("nur notwendige"));
        assertTrue(script.contains("accept all"));
        assertFalse("still no dangerous first-button heuristic", script.contains(":first-of-type"));
    }

    @Test
    public void consentReportDetectsARejectOnlyBanner() {
        String report = SearchPageGuards.consentReportScript(defaults.consent);
        // A banner offering only a "Reject all" control must still be flagged (so it gets resolved before read).
        assertTrue("report detects reject controls",
                report.contains("onetrust-reject-all-handler") && report.contains("reject all"));
        assertTrue(report.contains("candidate"));
    }

    @Test
    public void challengeScriptDetectsTheRequiredTextsAndSelectors() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        for (String text : new String[]{"noch ein letzter schritt", "one last step",
                "verify you are human", "unusual traffic", "complete the challenge", "captcha"}) {
            assertTrue("challenge text missing: " + text,
                    defaults.captcha.challengeTexts.contains(text));
        }
        for (String selector : new String[]{"iframe[src*='captcha']", "iframe[title*='challenge']",
                "#challenge-form", "[class*='captcha']", "[id*='captcha']"}) {
            assertTrue("challenge selector missing: " + selector,
                    defaults.captcha.challengeSelectors.contains(selector));
        }
        // Evidence-bearing, visibility-gated vocabulary.
        assertTrue("must distinguish a visible challenge", script.contains("'visible:'"));
        assertTrue("must distinguish a hidden artifact", script.contains("'hidden:'"));
    }

    @Test
    public void challengeScriptGatesOnRealVisibilityNotMerePresence() {
        String script = SearchPageGuards.challengeDetectScript(defaults.captcha);
        // A proper visibility gate (layout + computed style + viewport box), NOT the old clientHeight OR-fallback
        // that false-positived on hidden contact-form recaptchas (the reactree bug).
        assertTrue(script.contains("getComputedStyle"));
        assertTrue(script.contains("getBoundingClientRect"));
        assertTrue(script.contains("offsetParent"));
        assertFalse("the el.clientHeight>0 OR-fallback (false-positives on hidden widgets) must be gone",
                script.contains("clientHeight > 0"));
    }
}
