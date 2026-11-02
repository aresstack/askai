package com.aresstack.askai.ui;

import java.awt.Desktop;
import java.net.URI;

/**
 * Opens a URL in the system browser, tolerating headless/unsupported environments: {@link #open}
 * returns {@code false} (never throws) when it cannot launch, so the caller can fall back (e.g. put
 * the URL on the clipboard and log it).
 */
public final class BrowserLauncher {

    private BrowserLauncher() {
    }

    /** @return true when the browse request was dispatched, false when unsupported/failed. */
    public static boolean open(String url) {
        if (url == null || url.trim().length() == 0) {
            return false;
        }
        try {
            if (!Desktop.isDesktopSupported()) {
                return false;
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                return false;
            }
            desktop.browse(new URI(url.trim()));
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
