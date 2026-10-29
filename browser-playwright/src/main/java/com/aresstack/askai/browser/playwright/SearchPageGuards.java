package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.CaptchaHandlingSettings;
import com.aresstack.askai.browser.search.ConsentHandlingSettings;

import java.util.Arrays;
import java.util.List;

/**
 * The SERP guard scripts: consent RESOLUTION and manual-challenge detection, adapted from MainframeMate's
 * {@code CookieBannerDismisser} onto Playwright. Both run inside the page via {@code page.evaluate} and are
 * deliberately conservative:
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

    /**
     * The consent-RESOLVE script: tries controls in minimal-consent priority order and clicks the first
     * unambiguous match. Returns {@code 'clicked:<ACTION>:<what>'} (ACTION ∈ REJECT_ALL / ONLY_NECESSARY /
     * ACCEPT_ALL / CLOSE) or {@code 'none'}. Back-compatible: still starts with {@code 'clicked'}.
     */
    static String consentDismissScript(ConsentHandlingSettings consent) {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const clickEl = (el) => { if (el && el.offsetParent !== null) { try { el.focus(); } "
                + "catch(e){} el.click(); return true; } return false; };\n");
        sb.append("  const bySelector = (arr, action) => { for (const s of arr) { try { "
                + "if (clickEl(document.querySelector(s))) return 'clicked:' + action + ':' + s; } catch(e){} } "
                + "return null; };\n");
        sb.append("  const buttons = document.querySelectorAll(\"button, a[role='button'], [class*='btn'], "
                + "[role='button'], input[type='button'], input[type='submit']\");\n");
        sb.append("  const byText = (arr, action) => { for (let i = 0; i < buttons.length && i < 120; i++) { "
                + "const b = buttons[i]; const txt = (b.innerText || b.value || '').toLowerCase().trim(); "
                + "if (!txt || b.offsetParent === null) continue; "
                + "if (arr.some(p => txt === p || txt.startsWith(p))) { if (clickEl(b)) "
                + "return 'clicked:' + action + ':' + txt; } } return null; };\n");
        // Priority: minimal consent first, accept only as a fallback, close last.
        sb.append("  let r;\n");
        sb.append("  r = bySelector(").append(jsArray(REJECT_SELECTORS)).append(", 'REJECT_ALL'); if (r) return r;\n");
        sb.append("  r = byText(").append(jsArray(REJECT_TEXTS)).append(", 'REJECT_ALL'); if (r) return r;\n");
        sb.append("  r = byText(").append(jsArray(ONLY_NECESSARY_TEXTS))
                .append(", 'ONLY_NECESSARY'); if (r) return r;\n");
        sb.append("  r = bySelector(").append(jsArray(consent.positiveButtonSelectors))
                .append(", 'ACCEPT_ALL'); if (r) return r;\n");
        sb.append("  r = byText(").append(jsArray(consent.positiveButtonTexts))
                .append(", 'ACCEPT_ALL'); if (r) return r;\n");
        sb.append("  r = bySelector(").append(jsArray(CLOSE_SELECTORS)).append(", 'CLOSE'); if (r) return r;\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
    }

    /**
     * #44: the consent-LOCATE script — the SAME priority chains as the resolve script, but it
     * returns the matched control's viewport CENTER instead of clicking:
     * {@code 'locate:<ACTION>:<cx>,<cy>'} or {@code 'none'}. The human-paced pointer then
     * travels there and clicks like a person would; the JS click stays the fallback.
     */
    static String consentLocateScript(ConsentHandlingSettings consent) {
        StringBuilder sb = new StringBuilder();
        sb.append("() => {\n");
        sb.append("  const pickEl = (el) => { if (el && el.offsetParent !== null) { "
                + "const r = el.getBoundingClientRect(); if (r.width > 0 && r.height > 0) "
                + "return Math.round(r.left + r.width / 2) + ',' + "
                + "Math.round(r.top + r.height / 2); } return null; };\n");
        sb.append("  const bySelector = (arr, action) => { for (const s of arr) { try { "
                + "const c = pickEl(document.querySelector(s)); "
                + "if (c) return 'locate:' + action + ':' + c; } catch(e){} } return null; };\n");
        sb.append("  const buttons = document.querySelectorAll(\"button, a[role='button'], [class*='btn'], "
                + "[role='button'], input[type='button'], input[type='submit']\");\n");
        sb.append("  const byText = (arr, action) => { for (let i = 0; i < buttons.length && i < 120; i++) { "
                + "const b = buttons[i]; const txt = (b.innerText || b.value || '').toLowerCase().trim(); "
                + "if (!txt || b.offsetParent === null) continue; "
                + "if (arr.some(p => txt === p || txt.startsWith(p))) { const c = pickEl(b); "
                + "if (c) return 'locate:' + action + ':' + c; } } return null; };\n");
        sb.append("  let r;\n");
        sb.append("  r = bySelector(").append(jsArray(REJECT_SELECTORS)).append(", 'REJECT_ALL'); if (r) return r;\n");
        sb.append("  r = byText(").append(jsArray(REJECT_TEXTS)).append(", 'REJECT_ALL'); if (r) return r;\n");
        sb.append("  r = byText(").append(jsArray(ONLY_NECESSARY_TEXTS))
                .append(", 'ONLY_NECESSARY'); if (r) return r;\n");
        sb.append("  r = bySelector(").append(jsArray(consent.positiveButtonSelectors))
                .append(", 'ACCEPT_ALL'); if (r) return r;\n");
        sb.append("  r = byText(").append(jsArray(consent.positiveButtonTexts))
                .append(", 'ACCEPT_ALL'); if (r) return r;\n");
        sb.append("  r = bySelector(").append(jsArray(CLOSE_SELECTORS)).append(", 'CLOSE'); if (r) return r;\n");
        sb.append("  return 'none';\n");
        sb.append("}");
        return sb.toString();
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
