package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The SEARCH PLANNER (ADR 0001, 4.1, lane G): turns one aspect of the approved question into a few distinct,
 * concrete search queries. It never searches itself and has no tools; its queries are {@code planned query}
 * artifacts ({@link WorkerArtifacts}) that a web research package depending on it runs through the ONE funnel
 * (SEQUENTIAL inside the package chain: plan, then search). The answer is schema-bound and checked by code:
 * empty, overlong and duplicate queries (also against what the run already searched) are refused and sent back
 * once (a setting) with the reason.
 */
public final class SearchPlannerWorker implements TaskWorker {

    static final String SYSTEM =
            "You are the search planner of a research assistant. You get ONE aspect of a research question and "
                    + "write web search queries for it. You never search yourself.\n"
                    + "Rules:\n"
                    + "- Answer with the JSON object the schema asks for, nothing else.\n"
                    + "- Each query is what a person would type into a web search engine: 3 to 10 words, concrete "
                    + "terms, names, places and years from the aspect; no full sentences, no operators.\n"
                    + "- The queries differ in angle (for example definition, current figures, regulation, "
                    + "criticism), never only in word order.\n"
                    + "- Never repeat a query listed under ALREADY SEARCHED.\n"
                    + "- Stay inside the aspect; do not widen the topic.";

    /** The role: the planner slot, no tools, a per-request schema (its maxItems is the planner setting). */
    public static final ResearchRole ROLE = ResearchRole.builder("search-planner", ResearchModelSlots.PLANNER)
            .purpose("writes distinct search queries for one aspect; a web-researcher package that dependsOn it "
                    + "searches exactly those queries (use it for broad or vague aspects)")
            .systemPrompt(SYSTEM)
            .output(RoleOutput.schemaPerRequest("SearchPlannerWorker.schema"))
            .build();

    static final int MAX_QUERY_LENGTH = 160;
    private static final double TEMPERATURE = 0.3;

    private final int maxQueries;
    private final int repairAttempts;
    private final int maxOutputTokens;

    /**
     * @param maxQueries      queries one package may propose (a setting)
     * @param repairAttempts  how often an invalid answer is sent back (a setting)
     * @param maxOutputTokens the output budget of one answer (the agent's model-turn budget)
     */
    public SearchPlannerWorker(int maxQueries, int repairAttempts, int maxOutputTokens) {
        this.maxQueries = Math.max(1, maxQueries);
        this.repairAttempts = Math.max(0, repairAttempts);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    @Override
    public ResearchRole role() {
        return ROLE;
    }

    @Override
    public boolean needsBrowser() {
        return false;
    }

    @Override
    public WorkerResult execute(ResearchTask task, WorkerContext context) {
        String aspect = context.query();
        if (aspect.isEmpty()) {
            return WorkerResult.failed("the package names no aspect to plan");
        }
        Set<String> searched = WorkerArtifacts.searchedQueries(context.runTasks(), WebResearchWorker.ROLE.getId());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SYSTEM));
        messages.add(ChatMessage.user(prompt(aspect, context.brief(), searched)));
        String schema = schema(maxQueries);
        for (int attempt = 0; ; attempt++) {
            if (context.isCancelled()) {
                return WorkerResult.canceled("run stopped");
            }
            MainModelChatResult result = context.chat().completeJson(messages, TEMPERATURE, maxOutputTokens, schema);
            if (!result.isOk()) {
                return WorkerResult.retryable("planner model call failed (" + result.getStatus() + "): "
                        + result.getDetail());
            }
            try {
                List<String> queries = read(result.getText(), maxQueries, searched);
                for (String query : queries) {
                    context.artifact(TaskArtifact.Kind.TEXT, query, WorkerArtifacts.PLANNED_QUERY_REF);
                }
                context.log("planned " + queries.size() + " quer" + (queries.size() == 1 ? "y" : "ies") + ": "
                        + String.join(" | ", queries));
                return WorkerResult.completed(queries.size() + " search quer" + (queries.size() == 1 ? "y" : "ies")
                        + " planned");
            } catch (WorkerAnswerException invalid) {
                if (attempt >= repairAttempts || context.isBudgetExhausted()) {
                    return WorkerResult.failed("invalid search plan: " + invalid.getMessage());
                }
                messages.add(ChatMessage.assistant(result.getText() == null ? "" : result.getText()));
                messages.add(ChatMessage.user("Your answer was rejected: " + invalid.getMessage()
                        + "\nAnswer again with the corrected JSON object only."));
            }
        }
    }

    static String prompt(String aspect, String brief, Set<String> searched) {
        StringBuilder sb = new StringBuilder("ASPECT\n").append(aspect).append("\n\n");
        if (!brief.isEmpty()) {
            sb.append("BRIEF\n").append(brief).append("\n\n");
        }
        sb.append("ALREADY SEARCHED\n");
        if (searched.isEmpty()) {
            sb.append("(nothing yet)\n");
        }
        for (String query : searched) {
            sb.append("- ").append(query).append('\n');
        }
        return sb.append("\nTASK: write the search queries for the aspect.").toString();
    }

    static String schema(int maxQueries) {
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("minLength", 3);
        query.addProperty("maxLength", MAX_QUERY_LENGTH);
        JsonObject queries = new JsonObject();
        queries.addProperty("type", "array");
        queries.add("items", query);
        queries.addProperty("minItems", 1);
        queries.addProperty("maxItems", maxQueries);
        JsonObject props = new JsonObject();
        props.add("queries", queries);
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("queries");
        root.add("required", required);
        root.addProperty("additionalProperties", false);
        return root.toString();
    }

    /** The strict reader: the distinct new queries, or the reason the answer is unusable. */
    static List<String> read(String answer, int maxQueries, Set<String> searched) throws WorkerAnswerException {
        JsonObject root = WorkerAnswerException.object(answer);
        JsonElement element = root.get("queries");
        if (element == null || !element.isJsonArray()) {
            throw new WorkerAnswerException("\"queries\" must be an array of strings");
        }
        Set<String> known = new java.util.HashSet<String>();
        for (String query : searched) {
            known.add(WorkerArtifacts.normalize(query));
        }
        Map<String, String> distinct = new LinkedHashMap<String, String>();
        List<String> refused = new ArrayList<String>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new WorkerAnswerException("every query must be a string");
            }
            String query = item.getAsString().replaceAll("\\s+", " ").trim();
            String key = WorkerArtifacts.normalize(query);
            if (query.length() < 3 || query.length() > MAX_QUERY_LENGTH) {
                refused.add("\"" + query + "\" (length)");
            } else if (known.contains(key)) {
                refused.add("\"" + query + "\" (already searched)");
            } else if (distinct.putIfAbsent(key, query) != null) {
                refused.add("\"" + query + "\" (duplicate)");
            }
        }
        if (distinct.isEmpty()) {
            throw new WorkerAnswerException("no usable new query" + (refused.isEmpty() ? ""
                    : "; refused: " + String.join(", ", refused)));
        }
        List<String> queries = new ArrayList<String>(distinct.values());
        return queries.size() > maxQueries ? queries.subList(0, maxQueries) : queries;
    }
}
