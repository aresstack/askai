package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The plan record of plan PR 14 (D5): the concept revision the plan was built from, its working language, the
 * concept nodes with their base queries and whether each is still part of the plan, and the entry order (priority
 * entries first). It lives in its own file next to the tasks, because {@code orchestrator/run.json} belongs to the
 * orchestrated run. Immutable.
 */
public final class SearchPlanRecord {

    static final int VERSION = 1;

    /** One concept node of the plan. */
    public static final class Node {
        private final ConceptQueryPlan.Node node;
        private final boolean active;

        public Node(ConceptQueryPlan.Node node, boolean active) {
            this.node = node;
            this.active = active;
        }

        public ConceptQueryPlan.Node node() {
            return node;
        }

        public String getId() {
            return node.getId();
        }

        public String getLabel() {
            return node.getLabel();
        }

        public String getBaseQuery() {
            return node.getBaseQuery();
        }

        /** {@code false} once the node was removed from the concept or newly blacklisted. */
        public boolean isActive() {
            return active;
        }
    }

    private final String phase;
    private final int conceptRevision;
    private final String language;
    private final List<Node> nodes;
    private final List<String> order;
    private final List<String> priority;
    private final int cycle;

    /** The first research cycle. */
    public SearchPlanRecord(String phase, int conceptRevision, String language, List<Node> nodes,
                            List<String> order, List<String> priority) {
        this(phase, conceptRevision, language, nodes, order, priority, 1);
    }

    /** @param cycle the research cycle the plan is in (1 = the plan built from the concept) */
    public SearchPlanRecord(String phase, int conceptRevision, String language, List<Node> nodes,
                            List<String> order, List<String> priority, int cycle) {
        this.cycle = Math.max(1, cycle);
        this.phase = phase == null ? "" : phase;
        this.conceptRevision = conceptRevision;
        this.language = language == null ? "" : language;
        this.nodes = Collections.unmodifiableList(new ArrayList<Node>(nodes == null ? new ArrayList<Node>() : nodes));
        this.order = Collections.unmodifiableList(new ArrayList<String>(
                order == null ? new ArrayList<String>() : order));
        this.priority = Collections.unmodifiableList(new ArrayList<String>(
                priority == null ? new ArrayList<String>() : priority));
    }

    public String getPhase() {
        return phase;
    }

    public int getConceptRevision() {
        return conceptRevision;
    }

    /** The working language recorded with the concept; planned queries keep it after a language switch. */
    public String getLanguage() {
        return language;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    /** The entry ids in run order (without the priority entries). */
    public List<String> getOrder() {
        return order;
    }

    /** The priority entries ("Request changes"), run before every other entry. */
    public List<String> getPriority() {
        return priority;
    }

    public Node node(String id) {
        for (Node node : nodes) {
            if (node.getId().equals(id)) {
                return node;
            }
        }
        return null;
    }

    /** The research cycle the plan is in: 1 after the build, one more per re-plan of the uncovered nodes. */
    public int getCycle() {
        return cycle;
    }

    public SearchPlanRecord withOrder(List<String> newOrder, List<String> newPriority) {
        return new SearchPlanRecord(phase, conceptRevision, language, nodes, newOrder, newPriority, cycle);
    }

    /** The next research cycle with the entries it added appended to the order. */
    public SearchPlanRecord nextCycle(List<String> addedIds) {
        List<String> newOrder = new ArrayList<String>(order);
        newOrder.addAll(addedIds);
        return new SearchPlanRecord(phase, conceptRevision, language, nodes, newOrder, priority, cycle + 1);
    }

    // ---- JSON ------------------------------------------------------------------------------------------

    String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("phase", phase);
        root.addProperty("conceptRevision", conceptRevision);
        root.addProperty("language", language);
        JsonArray nodeArray = new JsonArray();
        for (Node node : nodes) {
            JsonObject n = new JsonObject();
            n.addProperty("id", node.getId());
            n.addProperty("label", node.getLabel());
            n.add("path", strings(node.node().getPath()));
            n.addProperty("parentId", node.node().getParentId());
            n.addProperty("baseQuery", node.getBaseQuery());
            n.addProperty("active", node.isActive());
            nodeArray.add(n);
        }
        root.add("nodes", nodeArray);
        root.add("order", strings(order));
        root.add("priority", strings(priority));
        root.addProperty("cycle", cycle);
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root) + "\n";
    }

    static SearchPlanRecord fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("version") || root.get("version").getAsInt() != VERSION) {
            throw new IllegalArgumentException("unsupported search plan version");
        }
        List<Node> nodes = new ArrayList<Node>();
        for (JsonElement element : root.getAsJsonArray("nodes")) {
            JsonObject n = element.getAsJsonObject();
            nodes.add(new Node(new ConceptQueryPlan.Node(n.get("id").getAsString(), n.get("label").getAsString(),
                    strings(n.getAsJsonArray("path")), n.get("parentId").getAsString(),
                    n.get("baseQuery").getAsString()), n.get("active").getAsBoolean()));
        }
        return new SearchPlanRecord(root.get("phase").getAsString(), root.get("conceptRevision").getAsInt(),
                root.get("language").getAsString(), nodes, strings(root.getAsJsonArray("order")),
                strings(root.getAsJsonArray("priority")), root.has("cycle") ? root.get("cycle").getAsInt() : 1);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<String>();
        if (array != null) {
            for (JsonElement element : array) {
                values.add(element.getAsString());
            }
        }
        return values;
    }
}
