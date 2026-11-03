package com.aresstack.askai.research.host;

import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured requirement checking for the productive research mode — every prerequisite is reported
 * individually, never as a blanket "research unavailable". The research agent runs in-process and needs no
 * check of its own; the browser-level status (driver bundle, driver compatibility,
 * installed browser, browser start) comes from the BROWSER RUNTIME'S OWN readiness probe: the check briefly
 * starts the in-process browser and reports its specific status line (READY / INCOMPATIBLE_DRIVER /
 * DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED / BROWSER_START_FAILED), then closes it. Blocking — callers
 * run it OFF the EDT.
 */
public final class ResearchRuntimeCapabilityCheck {

    /** One requirement with its own verdict. */
    public static final class Item {
        public final String label;
        public final boolean ok;
        public final String detail;

        Item(String label, boolean ok, String detail) {
            this.label = label;
            this.ok = ok;
            this.detail = detail;
        }

        public String render() {
            return (ok ? "[OK]   " : "[FAIL] ") + label + (detail.isEmpty() ? "" : " — " + detail);
        }
    }

    private ResearchRuntimeCapabilityCheck() {
    }

    public static List<Item> run(ResearchRuntimeSettings settings) {
        List<Item> items = new ArrayList<Item>();
        try {
            PlaywrightBrowserRuntime probe = PlaywrightBrowserRuntime.start(new PlaywrightBrowserRuntime.Options(
                    settings.getBrowserChannel(), settings.isHeadless(), settings.isAllowPrivateNetworks(),
                    null, null, false));
            try {
                items.add(new Item("Browser readiness (in-process start)", true, probe.readiness()));
            } finally {
                probe.close();
            }
        } catch (PlaywrightBrowserRuntime.StartFailure notReady) {
            // The message carries the browser's SPECIFIC status (e.g. BROWSER_NOT_INSTALLED: ...).
            items.add(new Item("Browser readiness (in-process start)", false, notReady.readiness()));
        } catch (RuntimeException unexpected) {
            items.add(new Item("Browser readiness (in-process start)", false,
                    "start failed: " + unexpected.getMessage()));
        }
        return items;
    }

}
