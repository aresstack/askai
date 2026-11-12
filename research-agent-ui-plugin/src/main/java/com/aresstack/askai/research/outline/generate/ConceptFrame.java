package com.aresstack.askai.research.outline.generate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3 (D6): the approved concept as the evidence analysis compares it with the research. When the concept has
 * a single first-level node, that node is the book FRAME (title or overall subject), not a theme: its children are
 * the main themes. Otherwise the first-level nodes are the main themes. {@link #nodes()} lists every node below the
 * frame in concept order with its depth (1 = main theme), its parent and its full label path (book title, frame,
 * parent labels, label), the text embedded as a query. Immutable.
 */
public final class ConceptFrame {

    /** One concept node below the frame. */
    public static final class Node {
        private final String id;
        private final String label;
        private final String path;
        private final int depth;
        private final String parentId;

        Node(String id, String label, String path, int depth, String parentId) {
            this.id = id;
            this.label = label;
            this.path = path;
            this.depth = depth;
            this.parentId = parentId;
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public String getPath() {
            return path;
        }

        public int getDepth() {
            return depth;
        }

        public String getParentId() {
            return parentId;
        }
    }

    static final String PATH_SEPARATOR = " > ";

    private final String frameLabel;
    private final String framePath;
    private final List<Node> nodes;

    private ConceptFrame(String frameLabel, String framePath, List<Node> nodes) {
        this.frameLabel = frameLabel;
        this.framePath = framePath;
        this.nodes = Collections.unmodifiableList(nodes);
    }

    /**
     * @param bookTitle  the approved title (or the mission when the concept has none); the start of every path
     * @param firstLevel the effective first-level nodes of the approved revision
     */
    public static ConceptFrame of(String bookTitle, List<ConceptOutlineNode> firstLevel) {
        String root = bookTitle == null ? "" : bookTitle.trim();
        List<Node> nodes = new ArrayList<Node>();
        if (firstLevel.size() == 1) {
            ConceptOutlineNode frame = firstLevel.get(0);
            String framePath = join(root, frame.getLabel());
            for (ConceptOutlineNode child : frame.getChildren()) {
                collect(child, 1, framePath, "", nodes);
            }
            return new ConceptFrame(frame.getLabel(), framePath, nodes);
        }
        for (ConceptOutlineNode node : firstLevel) {
            collect(node, 1, root, "", nodes);
        }
        return new ConceptFrame("", root, nodes);
    }

    private static void collect(ConceptOutlineNode node, int depth, String parentPath, String parentId,
                                List<Node> nodes) {
        String path = join(parentPath, node.getLabel());
        nodes.add(new Node(node.getId(), node.getLabel(), path, depth, parentId));
        for (ConceptOutlineNode child : node.getChildren()) {
            collect(child, depth + 1, path, node.getId(), nodes);
        }
    }

    private static String join(String parent, String label) {
        return parent == null || parent.isEmpty() ? label : parent + PATH_SEPARATOR + label;
    }

    /** The single first-level node acting as book frame, or "" when the concept has several main themes. */
    public String getFrameLabel() {
        return frameLabel;
    }

    /**
     * The prefix every node path shares (book title, frame label), or "" when the paths share none. The analysis
     * embeds it too: the bare frame is no evidence for a node.
     */
    public String getFramePath() {
        return framePath;
    }

    public boolean hasFrame() {
        return !frameLabel.isEmpty();
    }

    /** Every node below the frame, in concept order. */
    public List<Node> nodes() {
        return nodes;
    }
}
