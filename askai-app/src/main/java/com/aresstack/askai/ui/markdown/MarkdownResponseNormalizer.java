package com.aresstack.askai.ui.markdown;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unwraps a model answer that is wrapped in a single outer Markdown fence.
 *
 * <p>Models frequently return their whole reply as one fenced block tagged {@code markdown} (or
 * {@code md}/{@code commonmark}/{@code gfm}). Left untouched, Flexmark correctly renders that as a code
 * block — monospace, non-wrapping, with a language label — so headings, lists and inner code fences never
 * render. This normalizer removes only that outer container so the content is parsed as real Markdown.</p>
 *
 * <p>It is deliberately conservative: it strips the outer fence only when the entire non-blank answer is
 * exactly one enclosing fence of an allowed language with nothing after its closing fence. A real code
 * block ({@code java}, {@code json}, {@code mermaid}, …) or any answer with trailing content is left
 * untouched. During streaming (no closing fence yet) the still-open outer container is stripped
 * provisionally so the answer renders live; the final, strict pass runs at {@code finishStreaming()}.</p>
 */
final class MarkdownResponseNormalizer {

    private static final Set<String> CONTAINER_LANGUAGES = containerLanguages();
    // An opening fence: up to 3 leading spaces, 3+ backticks or tildes, an optional single-word info string.
    private static final Pattern OPEN_FENCE =
            Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^\\s`~]*)\\s*$");

    private MarkdownResponseNormalizer() {
    }

    /**
     * @param raw      the accumulated answer text
     * @param complete {@code true} at finish (strict: a proper single enclosing fence is required);
     *                 {@code false} while streaming (lenient: an unterminated outer container is stripped)
     * @return the answer with a single outer Markdown container removed, or {@code raw} unchanged
     */
    static String normalize(String raw, boolean complete) {
        if (raw == null) {
            return "";
        }
        String[] lines = raw.split("\n", -1);

        int firstIndex = firstNonBlank(lines);
        if (firstIndex < 0) {
            return raw;
        }
        Matcher opener = OPEN_FENCE.matcher(lines[firstIndex]);
        if (!opener.matches()) {
            return raw;
        }
        String marker = opener.group(1);
        String language = opener.group(2).toLowerCase();
        if (!CONTAINER_LANGUAGES.contains(language)) {
            return raw; // only unwrap markdown-ish containers, never a real code block
        }

        int lastIndex = lastNonBlank(lines);
        boolean closed = lastIndex > firstIndex && isBareClose(lines[lastIndex], marker);
        if (closed) {
            return join(lines, firstIndex + 1, lastIndex);
        }
        if (complete) {
            return raw; // no proper single enclosing fence — leave the answer as-is
        }
        return join(lines, firstIndex + 1, lines.length); // streaming: drop the still-open container line
    }

    private static int firstNonBlank(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().length() > 0) {
                return i;
            }
        }
        return -1;
    }

    private static int lastNonBlank(String[] lines) {
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].trim().length() > 0) {
                return i;
            }
        }
        return -1;
    }

    /** A closing fence uses the same marker character, is at least as long, and carries no info string. */
    private static boolean isBareClose(String line, String openMarker) {
        String trimmed = line.trim();
        char markerChar = openMarker.charAt(0);
        int minLength = openMarker.length();
        if (trimmed.length() < minLength) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) != markerChar) {
                return false;
            }
        }
        return true;
    }

    private static String join(String[] lines, int fromInclusive, int toExclusive) {
        StringBuilder builder = new StringBuilder();
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (i > fromInclusive) {
                builder.append('\n');
            }
            builder.append(lines[i]);
        }
        return builder.toString();
    }

    private static Set<String> containerLanguages() {
        Set<String> languages = new HashSet<String>();
        languages.add("markdown");
        languages.add("md");
        languages.add("commonmark");
        languages.add("gfm");
        return languages;
    }
}
