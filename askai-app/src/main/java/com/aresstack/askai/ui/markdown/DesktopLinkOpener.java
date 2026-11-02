package com.aresstack.askai.ui.markdown;

import java.awt.Desktop;
import java.net.URI;
import java.util.Locale;

/** Open links through desktop integration and isolate platform-specific behavior. */
public interface DesktopLinkOpener {

    void open(String url);

    static DesktopLinkOpener systemDefault() {
        return new DesktopLinkOpener() {
            @Override
            public void open(String url) {
                if (url == null || url.trim().isEmpty()) {
                    return;
                }
                try {
                    URI uri = new URI(url);
                    String scheme = uri.getScheme();
                    if (!isAllowedScheme(scheme) || !Desktop.isDesktopSupported()) {
                        return;
                    }
                    Desktop.getDesktop().browse(uri);
                } catch (Exception ignored) {
                    // Ignore desktop integration failures and keep the chat usable.
                }
            }
        };
    }

    static boolean isAllowedScheme(String scheme) {
        if (scheme == null) {
            return false;
        }
        String normalized = scheme.toLowerCase(Locale.ENGLISH);
        return "http".equals(normalized)
                || "https".equals(normalized)
                || "mailto".equals(normalized);
    }
}
