package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.plugin.spike.langchain4j.ModelPortChatModel;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailException;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import dev.langchain4j.guardrail.config.OutputGuardrailsConfig;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.service.AiServices;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ADR 0001, work item I: the {@link SearchPlannerWorker} built a second time with LangChain4j as the engine of
 * the role loop. Same role, prompt, schema and strict reader (this class shares the package to use them
 * verbatim, so only the engine differs); the loop is LangChain4j's: an {@link AiServices} proxy with chat memory,
 * the repair round is an {@link OutputGuardrail} that re-prompts with the reader's reason, and the model is
 * reached through {@link ModelPortChatModel} over the host's model port. SPIKE ONLY: not registered as a worker
 * and not in the plugin's lib/.
 *
 * <p>What it cannot do through today's {@link WorkerContext}: book its tokens on the task budget. The context
 * only hands out the original's chat transport; this twin needs the port, so it gets the port at construction
 * and counts its tokens on its own counter.</p>
 */
public final class LangChain4jSearchPlannerWorker implements TaskWorker {

    /** The AI service: one method, the answer is the raw JSON text the guardrail already accepted. */
    interface Planner {
        String plan(String prompt);
    }

    private static final double TEMPERATURE = 0.3;

    private final ModelPort port;
    private final Duration timeout;
    private final int maxQueries;
    private final int repairAttempts;
    private final int maxOutputTokens;
    private final AtomicLong tokens = new AtomicLong();

    public LangChain4jSearchPlannerWorker(ModelPort port, Duration timeout, int maxQueries, int repairAttempts,
                                          int maxOutputTokens) {
        this.port = port;
        this.timeout = timeout;
        this.maxQueries = Math.max(1, maxQueries);
        this.repairAttempts = Math.max(0, repairAttempts);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    @Override
    public ResearchRole role() {
        return SearchPlannerWorker.ROLE;
    }

    @Override
    public boolean needsBrowser() {
        return false;
    }

    /** Tokens of every call this twin made (it cannot book them on the task budget, see the class doc). */
    public long tokens() {
        return tokens.get();
    }

    @Override
    public WorkerResult execute(ResearchTask task, final WorkerContext context) {
        String aspect = context.query();
        if (aspect.isEmpty()) {
            return WorkerResult.failed("the package names no aspect to plan");
        }
        final Set<String> searched = WorkerArtifacts.searchedQueries(context.runTasks(),
                WebResearchWorker.ROLE.getId());
        final AtomicReference<List<String>> accepted = new AtomicReference<List<String>>();
        final ResponseFormat format = ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("search_plan")
                        .rootElement(JsonRawSchema.from(SearchPlannerWorker.schema(maxQueries))).build())
                .build();
        OutputGuardrail strictReader = new OutputGuardrail() {
            @Override
            public OutputGuardrailResult validate(AiMessage answer) {
                if (context.isCancelled()) {
                    return fatal("run stopped");
                }
                try {
                    accepted.set(SearchPlannerWorker.read(answer.text(), maxQueries, searched));
                    return success();
                } catch (WorkerAnswerException invalid) {
                    if (context.isBudgetExhausted()) {
                        return fatal(invalid.getMessage());
                    }
                    return reprompt(invalid.getMessage(), "Your answer was rejected: " + invalid.getMessage()
                            + "\nAnswer again with the corrected JSON object only.");
                }
            }
        };
        Planner planner = AiServices.builder(Planner.class)
                .chatModel(new ModelPortChatModel(port, SearchPlannerWorker.ROLE.getSlot(),
                        SearchPlannerWorker.ROLE.getContextTokens(), timeout, tokens))
                .systemMessage(SearchPlannerWorker.SYSTEM)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(3 + 2 * (repairAttempts + 1)))
                .chatRequestTransformer(request -> request.toBuilder()
                        .responseFormat(format)
                        .temperature(TEMPERATURE)
                        .maxOutputTokens(maxOutputTokens == 0 ? null : maxOutputTokens)
                        .build())
                .outputGuardrails(strictReader)
                // LangChain4j counts the first answer as a "retry": maxRetries is the number of ATTEMPTS, so our
                // repair setting (answers sent back) is one less (found by SearchPlannerTwinTest).
                .outputGuardrailsConfig(OutputGuardrailsConfig.builder().maxRetries(repairAttempts + 1).build())
                .build();
        try {
            planner.plan(SearchPlannerWorker.prompt(aspect, context.brief(), searched));
        } catch (OutputGuardrailException invalid) {
            if (context.isCancelled()) {
                return WorkerResult.canceled("run stopped");
            }
            return WorkerResult.failed("invalid search plan: " + reason(invalid));
        } catch (ModelPortChatModel.ModelPortCallException failed) {
            return WorkerResult.retryable("planner model call failed: " + failed.getFailure());
        }
        List<String> queries = accepted.get();
        for (String query : queries) {
            context.artifact(TaskArtifact.Kind.TEXT, query, WorkerArtifacts.PLANNED_QUERY_REF);
        }
        context.log("planned " + queries.size() + " quer" + (queries.size() == 1 ? "y" : "ies") + ": "
                + String.join(" | ", queries));
        return WorkerResult.completed(queries.size() + " search quer" + (queries.size() == 1 ? "y" : "ies")
                + " planned");
    }

    private static String reason(OutputGuardrailException invalid) {
        if (invalid.result() != null && !invalid.result().failures().isEmpty()) {
            return String.valueOf(((OutputGuardrailResult.Failure) invalid.result().failures().get(0)).message());
        }
        return invalid.getMessage();
    }
}
