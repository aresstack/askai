package com.aresstack.askai.research.plugin.spike.langchain4j;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.OutputFormat;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonRawSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ModelPortChatModelTest {

    private final AtomicLong tokens = new AtomicLong();

    private ModelPortChatModel model(ScriptedModelPort port) {
        return new ModelPortChatModel(port, ModelSlot.of("planner"), 4096, Duration.ofSeconds(5), tokens);
    }

    @Test
    public void oneChatIsOnePortCallOnTheRoleSlotAndItsTokensAreCounted() {
        ScriptedModelPort port = new ScriptedModelPort().answer("hello");

        ChatResponse response = model(port).chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(SystemMessage.from("sys"), UserMessage.from("hi")).temperature(0.2).build());

        assertEquals("hello", response.aiMessage().text());
        ChatRequest sent = port.requests().get(0);
        assertEquals(ModelSlot.of("planner"), sent.getSlot());
        assertEquals(4096, sent.getContextTokens());
        assertEquals(Double.valueOf(0.2), sent.getTemperature());
        assertEquals(2, sent.getMessages().size());
        assertEquals(120L, tokens.get());
    }

    @Test
    public void toolSpecificationsBecomePortToolDefinitionsWrittenWithGson() {
        ScriptedModelPort port = new ScriptedModelPort().toolCall("c1", "source_review_context",
                "{\"source_ids\":\"s1\"}");
        ToolSpecification tool = ToolSpecification.builder().name("source_review_context").description("read")
                .parameters(JsonObjectSchema.builder().addStringProperty("source_ids").required("source_ids")
                        .build()).build();

        ChatResponse response = model(port).chat(dev.langchain4j.model.chat.request.ChatRequest.builder()
                .messages(UserMessage.from("verify")).toolSpecifications(tool).build());

        String schema = port.requests().get(0).getTools().get(0).getParametersSchemaJson();
        assertTrue(schema, schema.contains("\"source_ids\"") && schema.contains("\"required\""));
        assertEquals(FinishReason.TOOL_EXECUTION, response.finishReason());
        assertEquals("source_review_context", response.aiMessage().toolExecutionRequests().get(0).name());
    }

    @Test
    public void aRawSchemaPassesVerbatimWhileLangChain4jsOwnModelDropsTheLimits() {
        String raw = "{\"type\":\"object\",\"properties\":{\"queries\":{\"type\":\"array\",\"maxItems\":3}}}";
        assertEquals(raw, ModelPortChatModel.outputFormat(ResponseFormat.builder().type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder().name("plan").rootElement(JsonRawSchema.from(raw)).build())
                .build()).getSchemaJson());
        // The typed schema model has no minItems/maxItems/minLength/maxLength: a settings-driven limit can only
        // be expressed as a raw schema (or checked in code afterwards).
        OutputFormat typed = ModelPortChatModel.outputFormat(ResponseFormat.builder()
                .type(ResponseFormatType.JSON).jsonSchema(JsonSchema.builder().name("plan")
                        .rootElement(JsonObjectSchema.builder().addProperty("queries", JsonArraySchema.builder()
                                .items(new JsonStringSchema()).build()).build()).build()).build());
        assertEquals(OutputFormat.Kind.JSON_SCHEMA, typed.getKind());
        assertTrue(typed.getSchemaJson(), !typed.getSchemaJson().contains("maxItems"));
    }

    @Test
    public void aPortFailureIsThrownWithItsKind() {
        ScriptedModelPort port = new ScriptedModelPort().fail(ModelFailure.Kind.NO_BINDING);
        try {
            model(port).chat("hi");
            fail();
        } catch (ModelPortChatModel.ModelPortCallException failed) {
            assertEquals(ModelFailure.Kind.NO_BINDING, failed.getFailure().getKind());
        }
    }
}
