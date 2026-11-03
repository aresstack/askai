package com.aresstack.askai.research.jsontree;

import com.aresstack.askai.research.visualize.MindmapLabels;

/**
 * The ONLY Mermaid-coupled piece of this layer: renders a {@link StructuralForest} as a Mermaid
 * {@code mindmap}. The forest itself stays render-neutral, so other presentations (Java2D, debug
 * tree, …) can be added beside this one. Assembly is purely mechanical — structure comes from
 * DATA, labels go through the shared sanitizer, so the diagram always parses.
 */
public final class MermaidMindmapRenderer {

    private static final int MAX_LABEL_CHARS = 48;

    private MermaidMindmapRenderer() {
    }

    /**
     * @param rootTitle the label of the synthetic root, used ONLY when the forest has no single
     *                  root of its own: a forest with exactly one root renders that root as the
     *                  CENTER (never a helper node like "Concept" above it); several roots hang
     *                  under {@code rootTitle}. An empty forest renders as the bare root — an
     *                  honest "no structure yet" diagram, never an error.
     */
    public static String render(StructuralForest forest, String rootTitle) {
        StringBuilder out = new StringBuilder("mindmap\n");
        if (forest.getRoots().size() == 1) {
            StructuralNode center = forest.getRoots().get(0);
            out.append("  root((").append(label(center.getName())).append("))\n");
            for (StructuralNode child : center.getChildren()) {
                renderNode(out, child, 2);
            }
            return out.toString();
        }
        out.append("  root((").append(label(rootTitle)).append("))\n");
        for (StructuralNode root : forest.getRoots()) {
            renderNode(out, root, 2);
        }
        return out.toString();
    }

    private static void renderNode(StringBuilder out, StructuralNode node, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
        out.append('(').append(label(node.getName())).append(")\n");
        for (StructuralNode child : node.getChildren()) {
            renderNode(out, child, depth + 1);
        }
    }

    private static String label(String text) {
        return MindmapLabels.sanitize(text, "?", MAX_LABEL_CHARS);
    }
}
