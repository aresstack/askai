package com.aresstack.askai.research.host;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured requirement checking for the productive research mode — every prerequisite is reported
 * individually, never as a blanket "research unavailable". The browser-level status (driver, installed
 * browser, browser start) comes from the Playwright readiness probe, run inside AskAI (askai #50): READY /
 * INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED / BROWSER_START_FAILED. Blocking —
 * callers run it OFF the EDT.
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
        for (String problem : settings.validateProductive()) {
            items.add(new Item("Configuration", false, problem));
        }
        com.aresstack.askai.browser.sidecar.PlaywrightReadiness readiness =
                new com.aresstack.askai.browser.sidecar.PlaywrightCapabilityProbe()
                        .probe(settings.getBrowserChannel());
        items.add(new Item("Browser readiness (" + settings.getBrowserChannel() + ")", readiness.isReady(),
                readiness.render()));
        return items;
    }
}
