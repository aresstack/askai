package com.aresstack.askai.browser.playwright;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Raw page state as reported by the {@link PlaywrightDriver}: final URL (after redirects), title, RENDERED
 * text (post-JavaScript, via the DOM — not the HTTP body) and the anchors with absolute hrefs. This is the
 * only data crossing the driver boundary — no Playwright objects, DOM handles or GraalJS values leak out.
 */
final class PlaywrightPageState {

    /** One anchor: visible text + absolute href. */
    static final class Anchor {
        final String text;
        final String href;

        Anchor(String text, String href) {
            this.text = text == null ? "" : text;
            this.href = href == null ? "" : href;
        }
    }

    final String url;
    final String title;
    final String text;
    final List<Anchor> anchors;

    PlaywrightPageState(String url, String title, String text, List<Anchor> anchors) {
        this.url = url == null ? "" : url;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.anchors = Collections.unmodifiableList(new ArrayList<Anchor>(
                anchors == null ? Collections.<Anchor>emptyList() : anchors));
    }
}
