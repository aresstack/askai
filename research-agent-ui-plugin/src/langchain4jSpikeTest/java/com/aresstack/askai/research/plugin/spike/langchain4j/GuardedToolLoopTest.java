package com.aresstack.askai.research.plugin.spike.langchain4j;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.AiServices;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Controllability of LangChain4j's tool loop (ADR 0001, item I): can our guards (receipts, the post-probe sensor
 * lock, the exact-repetition guard, the tool-round budget) be plugged in? The tools are concept-board shaped:
 * a sensor ({@code concept_probe}), a mutation ({@code concept_add_cards}) and a read.
 */
public class GuardedToolLoopTest {

    interface Assistant {
        String chat(String message);
    }

    /** The host: records what really ran. */
    private static final class Host implements ToolInvoker {
        final List<String> executed = new ArrayList<String>();

        public String call(String toolName, Map<String, Object> args) {
            executed.add(toolName + " " + args);
            return toolName + " ok";
        }
    }

    private static List<ToolSpecification> tools() {
        JsonObjectSchema cards = JsonObjectSchema.builder().addStringProperty("title").required("title").build();
        return Arrays.asList(
                ToolSpecification.builder().name("concept_probe").description("probe the scope")
                        .parameters(JsonObjectSchema.builder().addStringProperty("query").build()).build(),
                ToolSpecification.builder().name("concept_add_cards").description("add a card")
                        .parameters(cards).build(),
                ToolSpecification.builder().name("concept_read").description("read the board").build());
    }

    private static Assistant assistant(ScriptedModelPort port, GuardedToolExecutors guards, int maxToolRounds) {
        return AiServices.builder(Assistant.class)
                .chatModel(new ModelPortChatModel(port, ModelSlot.of("conversation"), 0, Duration.ofSeconds(10),
                        new AtomicLong()))
                .chatMemory(MessageWindowChatMemory.withMaxMessages(40))
                .tools(guards.executors(tools()))
                .maxSequentialToolsInvocations(maxToolRounds)
                .build();
    }

    private static GuardedToolExecutors guards(Host host) {
        return new GuardedToolExecutors(host, new HashSet<String>(Arrays.asList("concept_probe")),
                new HashSet<String>(Arrays.asList("concept_add_cards")));
    }

    @Test
    public void theSensorLockRefusesAMutationAfterAProbeBeforeTheHost() {
        Host host = new Host();
        GuardedToolExecutors guards = guards(host);
        ScriptedModelPort port = new ScriptedModelPort()
                .toolCall("c1", "concept_probe", "{\"query\":\"heat pumps\"}")
                .toolCall("c2", "concept_add_cards", "{\"title\":\"Subsidies\"}")
                .answer("Here is what the probe found.");

        String answer = assistant(port, guards, 10).chat("probe the scope");

        assertEquals("Here is what the probe found.", answer);
        assertEquals("only the probe reached the host", 1, host.executed.size());
        assertFalse(guards.receipts().get(1).executed);
        // The refusal went back to the model as the tool result of its call.
        ChatRequest last = port.requests().get(2);
        ModelMessage refusal = last.getMessages().get(last.getMessages().size() - 1);
        assertEquals(ModelMessage.Role.TOOL, refusal.getRole());
        assertEquals("c2", refusal.getToolCallId());
        assertTrue(refusal.getContent(), refusal.getContent().contains("sensor lock"));
    }

    @Test
    public void anExactRepetitionOfAnExecutedMutationNeverReachesTheHost() {
        Host host = new Host();
        GuardedToolExecutors guards = guards(host);
        ScriptedModelPort port = new ScriptedModelPort()
                .toolCall("c1", "concept_add_cards", "{\"title\":\"Subsidies\"}")
                .toolCall("c2", "concept_add_cards", "{\"title\":\"Subsidies\"}")
                .toolCall("c3", "concept_add_cards", "{\"title\":\"Costs\"}")
                .answer("Done.");

        assistant(port, guards, 10).chat("add the cards");

        assertEquals(Arrays.asList("concept_add_cards {title=Subsidies}", "concept_add_cards {title=Costs}"),
                host.executed);
        assertEquals("every call has a receipt, refused ones included", 3, guards.receipts().size());
        assertTrue(guards.receipts().get(1).outcome.contains("already executed"));
    }

    @Test
    public void theToolRoundBudgetIsAHardStopNotAGracefulCloseOfTheTurn() {
        Host host = new Host();
        ScriptedModelPort port = new ScriptedModelPort()
                .toolCall("c1", "concept_read", "{}")
                .toolCall("c2", "concept_read", "{}")
                .toolCall("c3", "concept_read", "{}")
                .answer("never reached");
        try {
            assistant(port, guards(host), 2).chat("read the board");
            fail("LangChain4j ends an exhausted tool budget with an exception");
        } catch (RuntimeException exhausted) {
            // Our loop closes the turn with what it has (ConceptToolRounds: "tool budget exhausted -> close the
            // turn"); with LangChain4j the turn's text is lost and the caller has to build the close itself.
            assertTrue(String.valueOf(exhausted.getMessage()), String.valueOf(exhausted.getMessage())
                    .toLowerCase().contains("2"));
        }
    }
}
