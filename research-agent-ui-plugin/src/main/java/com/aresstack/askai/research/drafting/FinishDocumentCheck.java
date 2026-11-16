package com.aresstack.askai.research.drafting;

import java.util.List;

/**
 * The PR-29 conditions of {@code finish-document}: refused while sections have neither text nor the user's
 * "Skip" mark; the confirmation names the stale sections.
 */
public final class FinishDocumentCheck {

    private FinishDocumentCheck() {
    }

    /** @return {@code null} when finishing is allowed, otherwise the refusal */
    public static String refusal(List<String> openSections) {
        if (openSections == null || openSections.isEmpty()) {
            return null;
        }
        return "Not yet: " + (openSections.size() == 1 ? "1 section has" : openSections.size() + " sections have")
                + " no text and no Skip mark (" + join(openSections) + "). Write or skip them first.";
    }

    /** The confirmation text of {@code finish-document}. */
    public static String confirmation(List<String> staleSections) {
        String text = "Finish the document? The manuscript becomes read-only until you request changes.";
        if (staleSections != null && !staleSections.isEmpty()) {
            text += " Stale sections: " + join(staleSections) + ".";
        }
        return text;
    }

    private static String join(List<String> titles) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            text.append(i > 0 ? ", " : "").append('"').append(titles.get(i)).append('"');
        }
        return text.toString();
    }
}
