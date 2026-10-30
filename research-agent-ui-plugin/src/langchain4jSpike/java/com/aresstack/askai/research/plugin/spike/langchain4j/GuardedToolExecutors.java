package com.aresstack.askai.research.plugin.spike.langchain4j;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The controllability probe of the spike: our tool-loop guards (ADR 0001, 1.3: receipts, the post-probe sensor
 * lock, the exact-repetition guard) placed where LangChain4j lets code decide about a tool call, the
 * {@link ToolExecutor}. {@code beforeToolExecution} is only an observer (a {@code Consumer}), so a guard that
 * must REFUSE a call before the host has to wrap the executor. A refused call never reaches the
 * {@link ToolInvoker}; its reason goes back to the model as the tool result, as in our own loop.
 *
 * <p>One instance per model turn (the guards are turn-scoped, like {@code ConceptToolRounds}).</p>
 */
public final class GuardedToolExecutors {

    /** One tool call as the guards saw it: what was asked, whether the host ran it, what came back. */
    public static final class Receipt {
        public final String tool;
        public final String arguments;
        public final boolean executed;
        public final String outcome;

        Receipt(String tool, String arguments, boolean executed, String outcome) {
            this.tool = tool;
            this.arguments = arguments;
            this.executed = executed;
            this.outcome = outcome;
        }

        @Override
        public String toString() {
            return tool + " " + arguments + (executed ? " -> " : " -> REFUSED ") + outcome;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Type ARGS = new TypeToken<Map<String, Object>>() { }.getType();

    private final ToolInvoker host;
    private final Set<String> sensors;
    private final Set<String> mutations;
    private final List<Receipt> receipts = Collections.synchronizedList(new ArrayList<Receipt>());
    private final Set<String> executedMutations = new HashSet<String>();
    private boolean sensorLocked;

    /**
     * @param sensors   tools whose observation must not turn into a decision in the same turn (probe tools)
     * @param mutations tools that change project state; refused after a sensor call and on exact repetition
     */
    public GuardedToolExecutors(ToolInvoker host, Set<String> sensors, Set<String> mutations) {
        this.host = host;
        this.sensors = sensors;
        this.mutations = mutations;
    }

    /** The executors for {@code AiServices.tools(Map)}. */
    public Map<ToolSpecification, ToolExecutor> executors(List<ToolSpecification> specifications) {
        Map<ToolSpecification, ToolExecutor> out = new LinkedHashMap<ToolSpecification, ToolExecutor>();
        for (ToolSpecification specification : specifications) {
            out.put(specification, (request, memoryId) -> execute(request));
        }
        return out;
    }

    public List<Receipt> receipts() {
        synchronized (receipts) {
            return new ArrayList<Receipt>(receipts);
        }
    }

    synchronized String execute(ToolExecutionRequest request) {
        String name = request.name();
        String signature = name + " " + request.arguments();
        if (mutations.contains(name) && sensorLocked) {
            return refuse(request, "refused (post-probe sensor lock): this turn only observes; finish with "
                    + "your answer");
        }
        if (mutations.contains(name) && executedMutations.contains(signature)) {
            return refuse(request, "refused: you already executed exactly this action in this turn; do not "
                    + "repeat it");
        }
        String outcome;
        try {
            Map<String, Object> args = request.arguments() == null || request.arguments().trim().isEmpty()
                    ? new LinkedHashMap<String, Object>() : GSON.<Map<String, Object>>fromJson(request.arguments(), ARGS);
            outcome = host.call(name, args);
        } catch (ToolInvoker.ToolFailure failure) {
            receipts.add(new Receipt(name, request.arguments(), true, "failed: " + failure.getMessage()));
            return "Tool failed: " + failure.getMessage();
        } catch (ToolInvoker.EndpointUnavailable gone) {
            receipts.add(new Receipt(name, request.arguments(), false, "unavailable: " + gone.getMessage()));
            return "Tool unavailable: " + gone.getMessage();
        }
        if (mutations.contains(name)) {
            executedMutations.add(signature);
        }
        if (sensors.contains(name)) {
            sensorLocked = true;
        }
        receipts.add(new Receipt(name, request.arguments(), true, outcome));
        return outcome;
    }

    private String refuse(ToolExecutionRequest request, String reason) {
        receipts.add(new Receipt(request.name(), request.arguments(), false, reason));
        return reason;
    }
}
