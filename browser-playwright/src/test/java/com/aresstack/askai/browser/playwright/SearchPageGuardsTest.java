package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pins the guard policy: consent clicks only UNAMBIGUOUS controls, located by Playwright locators (no
 * first-button-in-container guessing — that could hit "Settings" or "Only necessary"), and the
 * challenge detection knows the required texts and selectors. The lists come from the settings
 * (single default origin: LegacyBrowserSearchDefaults) — the guards hold no constants of their own.
 */
public class SearchPageGuardsTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    @Test
    public void consentTargetsHaveNoFirstButtonGuessingAndOnlyPositiveTexts() {
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            assertFalse("no dangerous first-button heuristic: " + target.selector,
                    target.selector.contains(":first-of-type"));
        }
        for (String selector : defaults.consent.positiveButtonSelectors) {
            assertFalse("selector must be accept-specific: " + selector,
                    selector.contains(":first-of-type"));
        }
        for (String required : new String[]{"accept all", "allow all", "i agree",
                "alle akzeptieren", "alle annehmen", "alle zulassen", "zustimmen"}) {
            assertTrue("positive text missing: " + required,
                    defaults.consent.positiveButtonTexts.contains(required));
        }
    }

    @Test
    public void consentResolutionPrefersRejectThenNecessaryThenAcceptThenClose() {
        List<String> actions = new ArrayList<String>();
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            if (actions.isEmpty() || !actions.get(actions.size() - 1).equals(target.action)) {
                actions.add(target.action);
            }
        }
        assertEquals("minimal consent first, accept only as a fallback, close last",
                Arrays.asList("REJECT_ALL", "ONLY_NECESSARY", "ACCEPT_ALL", "CLOSE"), actions);
        SearchPageGuards.ConsentTarget first = SearchPageGuards.consentTargets(defaults.consent).get(0);
        assertEquals("#onetrust-reject-all-handler", first.selector);
        assertEquals(null, first.text);
    }

    @Test
    public void consentTextsMatchLikeTheOldInPageRule() {
        Pattern reject = textTarget("REJECT_ALL").text;
        assertTrue(reject.matcher("Reject all").find());
        assertTrue("leading whitespace and longer labels still match", reject.matcher("  Reject all cookies").find());
        assertTrue(reject.matcher("ALLE ABLEHNEN").find());
        assertFalse("never a phrase in the middle of a label", reject.matcher("Settings: reject all").find());
        assertFalse(reject.matcher("Settings").find());
        Pattern accept = textTarget("ACCEPT_ALL").text;
        assertTrue(accept.matcher("Accept all").find());
        assertFalse("accept never matches a reject control", accept.matcher("Reject all").find());
    }

    @Test
    public void consentTextsAlsoFindInputButtonsByTheirValue() {
        boolean found = false;
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            if ("REJECT_ALL".equals(target.action) && target.text == null
                    && target.selector.contains("input[type='submit'][value^='reject all' i]")) {
                found = true;
            }
        }
        assertTrue("input buttons carry their label in value, which hasText does not see", found);
    }

    @Test
    public void regexLiteralsAreReadTheSameByJavaAndJavaScript() {
        assertEquals("i agree \\(cookies\\)", SearchPageGuards.regexLiteral("i agree (cookies)"));
        assertFalse("no \\Q...\\E quoting, the browser's engine would not understand it",
                SearchPageGuards.regexLiteral("a.b").contains("\\Q"));
        assertTrue(Pattern.compile(SearchPageGuards.regexLiteral("a.b+c")).matcher("a.b+c").matches());
        assertFalse(Pattern.compile(SearchPageGuards.regexLiteral("a.b")).matcher("axb").matches());
    }

    private SearchPageGuards.ConsentTarget textTarget(String action) {
        for (SearchPageGuards.ConsentTarget target : SearchPageGuards.consentTargets(defaults.consent)) {
            if (action.equals(target.action) && target.text != null) {
                return target;
            }
        }
        throw new AssertionError("no text target for " + action);
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
