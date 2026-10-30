package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.research.plugin.spike.langchain4j.ScriptedModelPort;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0001, item I: the search planner built twice, compared on the same scripted model. The original runs over
 * the product's chat transport, the twin over LangChain4j and the ChatModel adapter; both must send the SAME
 * requests to the port and reach the SAME result, or the spike is not comparing engines but behaviour.
 */
public class SearchPlannerTwinTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String ASPECT = "heat pump subsidies germany";

    /** The outcome of one twin on one script. */
    private static final class Run {
        WorkerResult result;
        List<String> queries;
        List<String> requests = new ArrayList<String>();
        long tokens;
    }

    private static Run original(Consumer<ScriptedModelPort> script, String... searched) {
        ScriptedModelPort port = new ScriptedModelPort();
        script.accept(port);
        SpikeWorkerContext context = context(port, searched);
        Run run = new Run();
        run.result = new SearchPlannerWorker(3, 1, 400).execute(context.task, context);
        return finish(run, context, port);
    }

    private static Run twin(Consumer<ScriptedModelPort> script, String... searched) {
        ScriptedModelPort port = new ScriptedModelPort();
        script.accept(port);
        SpikeWorkerContext context = context(port, searched);
        Run run = new Run();
        run.result = new LangChain4jSearchPlannerWorker(port, TIMEOUT, 3, 1, 400).execute(context.task, context);
        return finish(run, context, port);
    }

    private static SpikeWorkerContext context(ScriptedModelPort port, String... searched) {
        SpikeWorkerContext context = new SpikeWorkerContext(ASPECT, "a brief", port, TIMEOUT);
        for (String query : searched) {
            context.searched(query);
        }
        return context;
    }

    private static Run finish(Run run, SpikeWorkerContext context, ScriptedModelPort port) {
        run.queries = context.plannedQueries();
        run.tokens = context.tokens.get();
        for (ChatRequest request : port.requests()) {
            run.requests.add(ScriptedModelPort.describe(request));
        }
        return run;
    }

    private static void assertSameBehaviour(Run original, Run twin) {
        assertEquals(twin.result.getDetail() + "\n" + twin.requests, original.result.getKind(), twin.result.getKind());
        assertEquals(original.result.getDetail(), twin.result.getDetail());
        assertEquals(original.queries, twin.queries);
        assertEquals("the twins send identical requests to the model port", original.requests, twin.requests);
    }

    @Test
    public void aValidPlanIsTheSameOneCallForBothTwins() {
        Consumer<ScriptedModelPort> script = port -> port.answer(
                "{\"queries\":[\"BEG heat pump subsidy 2024 rates\",\"KfW 458 heat pump grant\"]}");
        Run original = original(script);
        Run twin = twin(script);

        assertSameBehaviour(original, twin);
        assertEquals(WorkerResult.Kind.COMPLETED, twin.result.getKind());
        assertEquals(Arrays.asList("BEG heat pump subsidy 2024 rates", "KfW 458 heat pump grant"), twin.queries);
        assertEquals(1, twin.requests.size());
        assertEquals("both twins book the same tokens on the task", original.tokens, twin.tokens);
        assertTrue(twin.tokens > 0);
        assertTrue("the planner setting's maxItems reaches the wire schema", twin.requests.get(0)
                .contains("\"maxItems\":3"));
    }

    @Test
    public void aRepeatedQueryIsSentBackOnceWithTheReasonByBothTwins() {
        Consumer<ScriptedModelPort> script = port -> port
                .answer("{\"queries\":[\"heat pump grant\",\"HEAT  pump grant\"]}")
                .answer("{\"queries\":[\"heat pump grant amount 2024\"]}");
        Run original = original(script, "heat pump grant");
        Run twin = twin(script, "heat pump grant");

        assertSameBehaviour(original, twin);
        assertEquals(Collections.singletonList("heat pump grant amount 2024"), twin.queries);
        assertEquals(2, twin.requests.size());
        assertTrue(twin.requests.get(1), twin.requests.get(1).contains("already searched"));
    }

    @Test
    public void aStillInvalidPlanFailsThePackageInBothTwins() {
        Consumer<ScriptedModelPort> script = port -> port.answer("not json").answer("{\"queries\":[]}");
        Run original = original(script);
        Run twin = twin(script);

        assertSameBehaviour(original, twin);
        assertEquals(WorkerResult.Kind.FAILED, twin.result.getKind());
        assertTrue(twin.queries.isEmpty());
    }

    @Test
    public void aFailedModelCallIsRetryableInBothTwins() {
        Consumer<ScriptedModelPort> script = port -> port.fail(ModelFailure.Kind.ENDPOINT_UNAVAILABLE);
        Run original = original(script);
        Run twin = twin(script);

        assertEquals(WorkerResult.Kind.RETRYABLE, original.result.getKind());
        assertEquals(WorkerResult.Kind.RETRYABLE, twin.result.getKind());
        assertEquals(original.requests, twin.requests);
    }
}
