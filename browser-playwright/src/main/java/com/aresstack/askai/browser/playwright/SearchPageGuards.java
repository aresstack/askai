package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.CaptchaHandlingSettings;
import com.aresstack.askai.browser.search.ConsentHandlingSettings;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The SERP guards: consent RESOLUTION and manual-challenge detection, adapted from MainframeMate's
 * {@code CookieBannerDismisser} onto Playwright. Consent controls are located with Playwright locators and clicked
 * with the real mouse ({@link #consentTargets}); only the read-only report and detection scripts still run in the
 * page (they move into the research add-on, issue #23 stage 2). All of it is deliberately conservative:
 * <ul>
 * <li>Consent resolution clicks only UNAMBIGUOUS controls — never a "first button in the container" guess.
 *     Priority is minimal consent: <b>REJECT_ALL → ONLY_NECESSARY → ACCEPT_ALL → CLOSE</b>. The accept
 *     selectors/texts remain configurable ({@link ConsentHandlingSettings}); the reject/only-necessary/close
 *     controls are a FIXED, unambiguous policy (never guessed) held here so the reject-first behaviour cannot
 *     be configured away.</li>
 * <li>Challenge detection looks for the configured CAPTCHA/"one last step" markers and must run BEFORE any
 *     generic content fallback — a challenge page is never a readable search result. It NEVER overlaps with
 *     consent (different scripts, different markers).</li>
 * </ul>
 * The scripts are used on SEARCH pages only; visited target pages are captured as-is.
 */
final class SearchPageGuards {

    private SearchPageGuards() {
    }

    /** Unambiguous "reject all / decline all" controls, tried FIRST (minimal consent). */
    private static final List<String> REJECT_SELECTORS = Arrays.asList(
            "#onetrust-reject-all-handler",
            "#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll",
            "#CybotCookiebotDialogBodyButtonDecline",
            "#uc-btn-deny-banner",
            ".qc-cmp2-summary-buttons button[mode='secondary']",
            "button[data-action='reject']", "button[data-action='reject-all']",
            "button[data-action='rejectAll']", "button[data-testid='reject-all-button']",
            "[class*='cookie'] button[class*='reject']", "[class*='consent'] button[class*='reject']",
            "[id*='cookie'] button[id*='reject']", "[id*='consent'] button[id*='reject']");
    /** Unambiguous reject/decline button texts (cookie-specific phrases only — never a bare "decline"). */
    private static final List<String> REJECT_TEXTS = Arrays.asList(
            "reject all", "decline all", "deny all", "reject cookies", "decline cookies",
            "continue without accepting", "do not accept",
            "alle ablehnen", "cookies ablehnen", "weiter ohne zustimmung", "nicht zustimmen");
    /** "Only necessary / essential" controls — also minimal consent, tried after reject. */
    private static final List<String> ONLY_NECESSARY_TEXTS = Arrays.asList(
            "only necessary", "necessary only", "use necessary only", "essential only",
            "only essential cookies", "accept only necessary",
            "nur notwendige", "nur erforderliche", "nur essenzielle", "nur essentielle");
    /** Last-resort close/X controls, SCOPED to a cookie/consent container to avoid closing unrelated UI. */
    private static final List<String> CLOSE_SELECTORS = Arrays.asList(
            "[class*='cookie'] [aria-label='Close']", "[class*='consent'] [aria-label='Close']",
            "[id*='cookie'] [aria-label='Close']", "[id*='consent'] [aria-label='Close']",
            "[class*='cookie'] button[class*='close']", "[class*='consent'] button[class*='close']");

    /** The clickable controls a consent text is looked for in (the same set the report script scans). */
    static final String BUTTON_SELECTOR =
            "button, a[role='button'], [class*='btn'], [role='button'], input[type='button'], input[type='submit']";

    /**
     * One consent control to try: a CSS selector, optionally narrowed by a text pattern (matched by Playwright's
     * {@code hasText} filter in its own selector engine, never by a page script). {@code action} is one of
     * REJECT_ALL / ONLY_NECESSARY / ACCEPT_ALL / CLOSE.
     */
    static final class ConsentTarget {
        final String action;
        final String selector;
        /** Null for a plain selector target. */
        final Pattern text;
        /** What the result line names: the selector or the text phrases. */
        final String label;

        ConsentTarget(String action, String selector, Pattern text, String label) {
            this.action = action;
            this.selector = selector;
            this.text = text;
            this.label = label;
        }
    }

    /**
     * The consent controls in minimal-consent priority order: <b>REJECT_ALL → ONLY_NECESSARY → ACCEPT_ALL →
     * CLOSE</b>, selectors before texts within a group (as the old in-page script tried them). The driver locates
     * each target with Playwright locators, measures it with {@code boundingBox} and clicks it with the real,
     * human-paced mouse; nothing here is ever run inside the page.
     */
    static List<ConsentTarget> consentTargets(ConsentHandlingSettings consent) {
        List<ConsentTarget> targets = new ArrayList<ConsentTarget>();
        addSelectors(targets, REJECT_SELECTORS, "REJECT_ALL");
        addTexts(targets, REJECT_TEXTS, "REJECT_ALL");
        addTexts(targets, ONLY_NECESSARY_TEXTS, "ONLY_NECESSARY");
        addSelectors(targets, consent.positiveButtonSelectors, "ACCEPT_ALL");
        addTexts(targets, consent.positiveButtonTexts, "ACCEPT_ALL");
        addSelectors(targets, CLOSE_SELECTORS, "CLOSE");
        return targets;
    }

    private static void addSelectors(List<ConsentTarget> targets, List<String> selectors, String action) {
        for (String selector : selectors) {
            targets.add(new ConsentTarget(action, selector, null, selector));
        }
    }

    private static void addTexts(List<ConsentTarget> targets, List<String> phrases, String action) {
        if (phrases.isEmpty()) {
            return;
        }
        StringBuilder alternatives = new StringBuilder();
        StringBuilder inputValues = new StringBuilder();
        for (String phrase : phrases) {
            if (alternatives.length() > 0) {
                alternatives.append('|');
                inputValues.append(", ");
            }
            alternatives.append(regexLiteral(phrase));
            String value = cssString(phrase);
            inputValues.append("input[type='button'][value^=").append(value).append(" i], ")
                    .append("input[type='submit'][value^=").append(value).append(" i]");
        }
        // Equal to or starting with a phrase, case-insensitive: the old in-page rule (txt === p || startsWith(p)).
        // CASE_INSENSITIVE only: Playwright passes no other Java flag on to the browser (it becomes the JS flag i).
        Pattern text = Pattern.compile("^\\s*(?:" + alternatives + ")", Pattern.CASE_INSENSITIVE);
        String label = String.join("|", phrases);
        targets.add(new ConsentTarget(action, BUTTON_SELECTOR, text, label));
        // Input buttons carry their label in the value attribute, which hasText does not see.
        targets.add(new ConsentTarget(action, inputValues.toString(), null, label));
    }

    /**
     * A phrase as a regex literal both Java and JavaScript read the same way: Playwright hands the pattern source to
     * the browser's selector engine, which knows no {@code \\Q...\\E} quoting.
     */
    static String regexLiteral(String phrase) {
        StringBuilder literal = new StringBuilder();
        for (char c : phrase.toCharArray()) {
            if ("\\^$.|?*+()[]{}/-".indexOf(c) >= 0) {
                literal.append('\\');
            }
            literal.append(c);
        }
        return literal.toString();
    }

    private static String cssString(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /**
     * The consent-REPORT script: detects an unambiguous consent control WITHOUT clicking (reject, only-necessary,
     * accept, or a scoped close), so a banner is flagged even when it offers ONLY a reject/close control. Returns
     * {@code 'candidate:<selector>'} / {@code 'candidate-text:<label>'} or {@code 'none'}.
     */
    static String consentReportScript(ConsentHandlingSettings consent) {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const hasSelector = (arr) => { for (const s of arr) { try { const el = "
                + "document.querySelector(s); if (el && el.offsetParent !== null) return s; } catch(e){} } "
                + "return null; };\n");
        sb.append("  const buttons = document.querySelectorAll(\"button, a[role='button'], [class*='btn'], "
                + "[role='button'], input[type='button'], input[type='submit']\");\n");
        sb.append("  const hasText = (arr) => { for (let i = 0; i < buttons.length && i < 120; i++) { "
                + "const b = buttons[i]; const txt = (b.innerText || b.value || '').toLowerCase().trim(); "
                + "if (!txt || b.offsetParent === null) continue; "
                + "if (arr.some(p => txt === p || txt.startsWith(p))) return txt; } return null; };\n");
        sb.append("  let s;\n");
        sb.append("  s = hasSelector(").append(jsArray(REJECT_SELECTORS)).append("); if (s) return 'candidate:' + s;\n");
        sb.append("  s = hasSelector(").append(jsArray(consent.positiveButtonSelectors))
                .append("); if (s) return 'candidate:' + s;\n");
        sb.append("  s = hasSelector(").append(jsArray(CLOSE_SELECTORS)).append("); if (s) return 'candidate:' + s;\n");
        sb.append("  let t;\n");
        sb.append("  t = hasText(").append(jsArray(REJECT_TEXTS)).append("); if (t) return 'candidate-text:' + t;\n");
        sb.append("  t = hasText(").append(jsArray(ONLY_NECESSARY_TEXTS))
                .append("); if (t) return 'candidate-text:' + t;\n");
        sb.append("  t = hasText(").append(jsArray(consent.positiveButtonTexts))
                .append("); if (t) return 'candidate-text:' + t;\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * The challenge-detection script. Returns an EVIDENCE-BEARING marker:
     * <ul>
     *   <li>{@code 'visible:<selector>'} — a genuinely visible, blocking challenge widget (laid out, not
     *       display:none/visibility:hidden/opacity~0, real box intersecting the viewport);</li>
     *   <li>{@code 'visible:text:<marker>'} — a challenge phrase in the visible body text;</li>
     *   <li>{@code 'hidden:<selector>'} — a challenge ARTIFACT exists but is invisible (e.g. a contact-form
     *       recaptcha) — present for diagnostics, but NOT blocking;</li>
     *   <li>{@code 'none'} — no artifact at all.</li>
     * </ul>
     * The visibility gate is the reactree false-positive fix: a hidden recaptcha must never force a user-wait.
     */
    static String challengeDetectScript(CaptchaHandlingSettings captcha) {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        // A real, blocking widget: laid out, not display:none / visibility:hidden / opacity~0, with a box of
        // meaningful size that at least partially intersects the viewport. Tiny badges (invisible-recaptcha
        // score badge) and off-screen/zero-box nodes do NOT count.
        sb.append("  const visible = (el) => {\n");
        sb.append("    try {\n");
        sb.append("      if (!el || el.offsetParent === null) return false;\n");
        sb.append("      const st = window.getComputedStyle(el);\n");
        sb.append("      if (!st || st.display === 'none' || st.visibility === 'hidden'\n");
        sb.append("          || parseFloat(st.opacity || '1') < 0.05) return false;\n");
        sb.append("      const r = el.getBoundingClientRect();\n");
        sb.append("      if (r.width < 8 || r.height < 8) return false;\n");
        sb.append("      const vw = window.innerWidth || document.documentElement.clientWidth;\n");
        sb.append("      const vh = window.innerHeight || document.documentElement.clientHeight;\n");
        sb.append("      if (r.bottom < 0 || r.right < 0 || r.top > vh || r.left > vw) return false;\n");
        sb.append("      return true;\n");
        sb.append("    } catch (e) { return false; }\n");
        sb.append("  };\n");
        sb.append("  const selectors = ").append(jsArray(captcha.challengeSelectors)).append(";\n");
        sb.append("  let hidden = null;\n");
        sb.append("  for (const s of selectors) {\n");
        sb.append("    try {\n");
        sb.append("      const el = document.querySelector(s);\n");
        sb.append("      if (!el) continue;\n");
        sb.append("      if (visible(el)) return 'visible:' + s;\n");
        sb.append("      if (hidden === null) hidden = s;\n"); // remember the artifact, keep scanning for a visible one
        sb.append("    } catch (e) {}\n");
        sb.append("  }\n");
        // A challenge PHRASE in the visible body text is itself a blocking signal.
        sb.append("  const texts = ").append(jsArray(captcha.challengeTexts)).append(";\n");
        sb.append("  const body = (document.body ? document.body.innerText : '').toLowerCase();\n");
        sb.append("  for (const t of texts) { if (body.includes(t)) return 'visible:text:' + t; }\n");
        sb.append("  if (hidden !== null) return 'hidden:' + hidden;\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
    }

    private static String jsArray(List<String> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('\'').append(values.get(i).replace("\\", "\\\\").replace("'", "\\'")).append('\'');
        }
        return sb.append(']').toString();
    }
}
