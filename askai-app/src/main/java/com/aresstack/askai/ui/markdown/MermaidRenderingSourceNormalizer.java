package com.aresstack.askai.ui.markdown;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Make LLM-generated Mermaid flowchart source parser-compatible for <em>rendering only</em>.
 *
 * <p>Language models routinely emit rectangle node labels that contain unquoted special characters
 * (round brackets, slashes), e.g. {@code A[Tierreich (Animalia)]}. Mermaid's flowchart grammar rejects
 * these — the opening {@code (} is parsed as shape syntax ("got 'PS'") — while the quoted form
 * {@code A["Tierreich (Animalia)"]} is accepted. This normalizer wraps exactly those clearly-repairable
 * rectangle labels in double quotes and changes nothing else.
 *
 * <p>Deliberately conservative — it is <strong>not</strong> a Mermaid grammar. It only ever quotes a
 * rectangle label {@code id[...]} whose opening character is not a shape marker ({@code [ ( / \ "}) and
 * which contains a character that requires quoting. Every other Mermaid construct — already-quoted
 * labels, subroutine {@code [[...]]}, cylinder {@code [(...)]}, circle {@code ((...))}, rhombus
 * {@code {...}}, hexagon {@code {{...}}}, asymmetric {@code >...]}, parallelograms {@code [/.../]} /
 * {@code [\...\]}, comments, and non-flowchart diagram types — is left byte-for-byte unchanged.
 *
 * <p>The transformation is idempotent: {@code normalize(normalize(x)).equals(normalize(x))}.
 *
 * <p>No Swing, no I/O — plain string in, string out, so it is trivially unit-testable.
 */
final class MermaidRenderingSourceNormalizer {

    /**
     * A rectangle node: an id, {@code [}, then a label whose first character is not a shape marker or a
     * quote, followed by label text that contains no further brackets, closed by a single {@code ]}.
     */
    private static final Pattern RECTANGLE_NODE =
            Pattern.compile("([A-Za-z0-9_-]+)\\[([^\\[\\]\"(/\\\\][^\\[\\]]*)\\]");

    /** Characters that make an unquoted rectangle label illegal and are safely fixed by quoting. */
    private static final String QUOTING_TRIGGERS = "()/";

    /**
     * @param diagramCode the original Mermaid source (may be {@code null})
     * @return a rendering-compatible copy, or the original reference when nothing needed fixing
     */
    String normalize(String diagramCode) {
        if (diagramCode == null || diagramCode.trim().isEmpty()) {
            return diagramCode;
        }
        String[] lines = diagramCode.split("\n", -1);
        if (!isFlowchart(lines)) {
            return diagramCode;
        }
        boolean changed = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("%%")) {
                continue; // never touch comment / %%{init}%% lines
            }
            String rewritten = quoteRectangleLabels(line);
            if (!rewritten.equals(line)) {
                lines[i] = rewritten;
                changed = true;
            }
        }
        return changed ? String.join("\n", lines) : diagramCode;
    }

    private static String quoteRectangleLabels(String line) {
        Matcher matcher = RECTANGLE_NODE.matcher(line);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String id = matcher.group(1);
            String label = matcher.group(2);
            String replacement = needsQuoting(label)
                    ? id + "[\"" + label + "\"]"
                    : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static boolean needsQuoting(String label) {
        if (label.indexOf('"') >= 0) {
            return false; // a label already carrying quotes cannot be safely re-quoted
        }
        for (int i = 0; i < label.length(); i++) {
            if (QUOTING_TRIGGERS.indexOf(label.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** True only for {@code graph}/{@code flowchart} diagrams; other diagram types are left untouched. */
    private static boolean isFlowchart(String[] lines) {
        boolean inFrontmatter = false;
        boolean frontmatterPossible = true;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (inFrontmatter) {
                if (line.equals("---")) {
                    inFrontmatter = false;
                }
                continue;
            }
            if (frontmatterPossible && line.equals("---")) {
                inFrontmatter = true;
                frontmatterPossible = false;
                continue;
            }
            frontmatterPossible = false;
            if (line.startsWith("%%")) {
                continue; // comments and %%{init}%% directives precede the diagram keyword
            }
            String keyword = line.toLowerCase(Locale.ROOT);
            return keyword.equals("graph") || keyword.startsWith("graph ")
                    || keyword.equals("flowchart") || keyword.startsWith("flowchart ");
        }
        return false;
    }
}
