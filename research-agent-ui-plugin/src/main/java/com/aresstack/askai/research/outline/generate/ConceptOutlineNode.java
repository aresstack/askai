package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.concept.ConceptIdentity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Plan PR 22: one card of the approved concept as the outline generation sees it: the node id of the identity
 * history, its label and its children in concept order. Immutable.
 */
public final class ConceptOutlineNode {

    private final String id;
    private final String label;
    private final List<ConceptOutlineNode> children;

    public ConceptOutlineNode(String id, String label, List<ConceptOutlineNode> children) {
        this.id = id == null ? "" : id;
        this.label = label == null ? "" : label.trim();
        this.children = Collections.unmodifiableList(new ArrayList<ConceptOutlineNode>(
                children == null ? Collections.<ConceptOutlineNode>emptyList() : children));
    }

    public String getId() {
        return id;
    }

    public String getLabel() {
        return label;
    }

    public List<ConceptOutlineNode> getChildren() {
        return children;
    }

    /**
     * The first-level nodes of a concept document ({@code {"concept":[…]}}) in concept order. Node ids come from
     * {@code identity} over {@code identityRoot} (the working revision the Phase-1 record names); a card the
     * identity does not know gets the id {@code path:<labels>} so it still has a stable provenance.
     */
    public static List<ConceptOutlineNode> firstLevel(String document, ConceptIdentity identity,
                                                      JsonElement identityRoot) {
        JsonArray concept;
        try {
            JsonElement element = JsonParser.parseString(document == null ? "" : document)
                    .getAsJsonObject().get("concept");
            concept = element != null && element.isJsonArray() ? element.getAsJsonArray() : new JsonArray();
        } catch (RuntimeException unreadable) {
            concept = new JsonArray();
        }
        return level(concept, new ArrayList<String>(), identity, identityRoot);
    }

    private static List<ConceptOutlineNode> level(JsonArray array, List<String> parentPath,
                                                  ConceptIdentity identity, JsonElement identityRoot) {
        List<ConceptOutlineNode> nodes = new ArrayList<ConceptOutlineNode>();
        for (JsonElement element : array) {
            if (element.isJsonPrimitive()) {
                String name = element.getAsString();
                if (!name.trim().isEmpty()) {
                    List<String> path = with(parentPath, name);
                    nodes.add(new ConceptOutlineNode(idOf(path, identity, identityRoot), name,
                            Collections.<ConceptOutlineNode>emptyList()));
                }
            } else if (element.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
                    if (entry.getKey().trim().isEmpty()) {
                        continue;
                    }
                    List<String> path = with(parentPath, entry.getKey());
                    List<ConceptOutlineNode> children = entry.getValue().isJsonArray()
                            ? level(entry.getValue().getAsJsonArray(), path, identity, identityRoot)
                            : Collections.<ConceptOutlineNode>emptyList();
                    nodes.add(new ConceptOutlineNode(idOf(path, identity, identityRoot), entry.getKey(), children));
                }
            }
        }
        return nodes;
    }

    private static List<String> with(List<String> path, String name) {
        List<String> next = new ArrayList<String>(path);
        next.add(name);
        return next;
    }

    private static String idOf(List<String> path, ConceptIdentity identity, JsonElement identityRoot) {
        String id = identity == null || identityRoot == null ? null : identity.idAtPath(identityRoot, path);
        if (id != null && !id.isEmpty()) {
            return id;
        }
        StringBuilder sb = new StringBuilder("path:");
        for (int i = 0; i < path.size(); i++) {
            sb.append(i > 0 ? "/" : "").append(path.get(i).trim());
        }
        return sb.toString();
    }
}
