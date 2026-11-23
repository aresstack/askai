package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.aresstack.askai.modelport.provider.OpenAiRequestWriter.Relaxation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The body of {@code POST /chat/completions}: roles, streaming only in the body, optional fields only when set,
 * tool calls with ids, the response format, how thinking maps onto {@code reasoning_effort}, the request for token
 * usage, and the weaker forms that answer a service's refusal of a field.
 */
public class OpenAiRequestWriterTest {

    private static ProviderCall call(String model, List<ModelMessage> messages, List<ToolDefinition> tools,
                                     OutputFormat format, ThinkingMode requested, ThinkingMode slot,
                                     Double temperature, int budget) {
        return new ProviderCall("planner", model, messages, tools, format, requested, slot, temperature, budget,
                131072, Boolean.FALSE, "30m", null);
    }

    private static ProviderCall simple(String model, ThinkingMode requested, ThinkingMode slot, int budget) {
        return call(model, Collections.singletonList(ModelMessage.user("question")), null, null, requested, slot,
                null, budget);
    }

    private static JsonObject body(ProviderCall call) {
        return JsonParser.parseString(OpenAiRequestWriter.write(call)).getAsJsonObject();
    }

    private static JsonObject body(ProviderCall call, Relaxation... relaxations) {
        Set<Relaxation> forms = EnumSet.noneOf(Relaxation.class);
        forms.addAll(Arrays.asList(relaxations));
        return JsonParser.parseString(OpenAiRequestWriter.write(call, forms)).getAsJsonObject();
    }

    /** A call with every optional field: temperature, budget, a reasoning level and a plain JSON answer. */
    private static ProviderCall full() {
        return call("gpt-5-mini", Collections.singletonList(ModelMessage.user("answer in json")), null,
                OutputFormat.json(), ThinkingMode.LOW, ThinkingMode.DEFAULT, 0.2d, 900);
    }

    private static Relaxation refusal(String param, String message, Relaxation... applied) {
        Set<Relaxation> forms = EnumSet.noneOf(Relaxation.class);
        forms.addAll(Arrays.asList(applied));
        return OpenAiRequestWriter.relaxationFor(OpenAiRequestWriter.write(full(), forms), forms, param, message);
    }

    private static JsonObject message(JsonObject body, int index) {
        return body.getAsJsonArray("messages").get(index).getAsJsonObject();
    }

    private static String effort(ProviderCall call) {
        JsonObject body = body(call);
        return body.has("reasoning_effort") ? body.get("reasoning_effort").getAsString() : null;
    }

    @Test
    public void streamsOnlyThroughTheBodyAndSendsOptionalFieldsOnlyWhenSet() {
        JsonObject body = body(call("gemma-4-31b", Arrays.asList(ModelMessage.system("be brief"),
                ModelMessage.user("question"), ModelMessage.assistant("answer")), null, null,
                ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, null, 0));

        assertEquals("gemma-4-31b", body.get("model").getAsString());
        assertTrue(body.get("stream").getAsBoolean());
        assertEquals("system", message(body, 0).get("role").getAsString());
        assertEquals("be brief", message(body, 0).get("content").getAsString());
        assertEquals("user", message(body, 1).get("role").getAsString());
        assertEquals("assistant", message(body, 2).get("role").getAsString());
        for (String absent : new String[]{"temperature", "max_tokens", "reasoning_effort", "response_format",
                "tools", "tool_choice", "n", "stop", "options", "num_ctx", "keep_alive", "think", "truncate"}) {
            assertFalse(absent + " is not sent", body.has(absent));
        }
        assertFalse("the developer role is never used", body.toString().contains("developer"));
    }

    @Test
    public void temperatureAndAnswerBudgetAreSentWhenSet() {
        JsonObject body = body(call("m", Collections.singletonList(ModelMessage.user("q")), null, null,
                ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, 0.2d, 900));

        assertEquals(0.2d, body.get("temperature").getAsDouble(), 1e-9);
        assertEquals(900, body.get("max_tokens").getAsInt());
    }

    @Test
    public void toolCallsWithoutIdsGetIdsAndTheirResultsAnswerTheOldestOpenCall() {
        List<ModelMessage> messages = Arrays.asList(
                ModelMessage.user("find two things"),
                ModelMessage.assistant("", "", Arrays.asList(new ToolCall(null, "search", "{\"q\":\"a\"}"),
                        new ToolCall(null, "search", "{\"q\":\"b\"}"), new ToolCall("given-7", "fetch", "{}"))),
                ModelMessage.toolResult("search", null, "result a"),
                ModelMessage.toolResult("fetch", "given-7", "page"),
                ModelMessage.toolResult("search", null, "result b"));
        JsonObject body = body(call("m", messages, Collections.singletonList(new ToolDefinition("search",
                        "Searches the web", "{\"type\":\"object\",\"properties\":{\"q\":{\"type\":\"string\"}}}")),
                null, ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, null, 0));

        JsonObject assistant = message(body, 1);
        assertTrue("a turn with tool calls and no text sends content null", assistant.get("content").isJsonNull());
        JsonArray calls = assistant.getAsJsonArray("tool_calls");
        assertEquals("call_1", calls.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("call_2", calls.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("given-7", calls.get(2).getAsJsonObject().get("id").getAsString());
        assertEquals("function", calls.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("arguments travel as a JSON string", "{\"q\":\"a\"}",
                calls.get(0).getAsJsonObject().getAsJsonObject("function").get("arguments").getAsString());

        assertEquals("tool", message(body, 2).get("role").getAsString());
        assertEquals("call_1", message(body, 2).get("tool_call_id").getAsString());
        assertEquals("given-7", message(body, 3).get("tool_call_id").getAsString());
        assertEquals("call_2", message(body, 4).get("tool_call_id").getAsString());
        assertEquals("result b", message(body, 4).get("content").getAsString());

        JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("function", tool.get("type").getAsString());
        assertEquals("search", tool.getAsJsonObject("function").get("name").getAsString());
        assertEquals("the schema is sent as JSON, not as a string", "object",
                tool.getAsJsonObject("function").getAsJsonObject("parameters").get("type").getAsString());
        assertFalse("the service decides about tools", body.has("tool_choice"));
    }

    @Test
    public void jsonAndSchemaAnswersAskForTheMatchingResponseFormat() {
        JsonObject json = body(call("m", Collections.singletonList(ModelMessage.user("q")), null,
                OutputFormat.json(), ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, null, 0));
        assertEquals("json_object", json.getAsJsonObject("response_format").get("type").getAsString());

        JsonObject schema = body(call("m", Collections.singletonList(ModelMessage.user("q")), null,
                OutputFormat.jsonSchema("{\"type\":\"object\",\"required\":[\"a\"]}"), ThinkingMode.DEFAULT,
                ThinkingMode.DEFAULT, null, 0)).getAsJsonObject("response_format");
        assertEquals("json_schema", schema.get("type").getAsString());
        assertEquals("answer", schema.getAsJsonObject("json_schema").get("name").getAsString());
        assertEquals("a", schema.getAsJsonObject("json_schema").getAsJsonObject("schema")
                .getAsJsonArray("required").get(0).getAsString());
    }

    @Test(expected = IllegalArgumentException.class)
    public void anInvalidSchemaIsRefusedBeforeAnythingIsSent() {
        OpenAiRequestWriter.write(call("m", Collections.singletonList(ModelMessage.user("q")),
                Collections.singletonList(new ToolDefinition("broken", "d", "{not json")), null,
                ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, null, 0));
    }

    @Test
    public void reasoningLevelsPassAndOffOrAutoSendNothingExceptOnLevelOnlyModels() {
        assertEquals("low", effort(simple("gemma-4-31b", ThinkingMode.LOW, ThinkingMode.DEFAULT, 0)));
        assertEquals("medium", effort(simple("gemma-4-31b", ThinkingMode.MEDIUM, ThinkingMode.DEFAULT, 0)));
        assertEquals("high", effort(simple("gemma-4-31b", ThinkingMode.HIGH, ThinkingMode.DEFAULT, 0)));
        assertEquals("the slot's level when the caller leaves it open", "high",
                effort(simple("gemma-4-31b", ThinkingMode.DEFAULT, ThinkingMode.HIGH, 0)));
        assertEquals("the caller's level wins over the slot's", "low",
                effort(simple("gemma-4-31b", ThinkingMode.LOW, ThinkingMode.HIGH, 0)));
        assertNull("On asks for the model's own default",
                effort(simple("gemma-4-31b", ThinkingMode.ON, ThinkingMode.DEFAULT, 0)));
        assertNull("Off: many services do not know \"none\"",
                effort(simple("gemma-4-31b", ThinkingMode.OFF, ThinkingMode.DEFAULT, 0)));
        assertNull("Auto", effort(simple("gemma-4-31b", ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, 0)));
        assertEquals("gpt-oss cannot switch reasoning off", "low",
                effort(simple("gpt-oss-120b", ThinkingMode.OFF, ThinkingMode.DEFAULT, 0)));
        assertEquals("also under a vendor prefix", "low",
                effort(simple("openai/gpt-oss-120b", ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, 0)));
    }

    @Test
    public void aLevelOnlyModelAskedForNoReasoningGetsReasoningRoomInItsBudget() {
        assertEquals(800 + ProviderCall.LEVEL_ONLY_REASONING_TOKENS,
                body(simple("openai/gpt-oss-120b", ThinkingMode.OFF, ThinkingMode.DEFAULT, 800))
                        .get("max_tokens").getAsInt());
        assertEquals("only when the caller asked for no reasoning", 800,
                body(simple("openai/gpt-oss-120b", ThinkingMode.DEFAULT, ThinkingMode.OFF, 800))
                        .get("max_tokens").getAsInt());
        assertEquals("never for a model that can switch reasoning off", 800,
                body(simple("gemma-4-31b", ThinkingMode.OFF, ThinkingMode.DEFAULT, 800)).get("max_tokens")
                        .getAsInt());
        assertFalse("no budget, no room",
                body(simple("openai/gpt-oss-120b", ThinkingMode.OFF, ThinkingMode.DEFAULT, 0)).has("max_tokens"));
    }

    @Test
    public void aModelWhoseServiceDemandedMaxCompletionTokensIsTreatedAsUnableToSwitchReasoningOff() {
        ProviderCall off = simple("gpt-5-mini", ThinkingMode.OFF, ThinkingMode.DEFAULT, 120);
        JsonObject plain = body(off);
        assertEquals("before any refusal the request is the plain one", 120, plain.get("max_tokens").getAsInt());
        assertFalse(plain.has("reasoning_effort"));

        JsonObject reasoning = body(off, Relaxation.MAX_COMPLETION_TOKENS);
        assertEquals("OpenAI counts the reasoning in this budget: the answer keeps its room",
                120 + ProviderCall.LEVEL_ONLY_REASONING_TOKENS, reasoning.get("max_completion_tokens").getAsInt());
        assertEquals("the lowest level every such model accepts", "low",
                reasoning.get("reasoning_effort").getAsString());
        assertEquals("Auto as well", "low", body(simple("gpt-5-mini", ThinkingMode.DEFAULT, ThinkingMode.DEFAULT,
                120), Relaxation.MAX_COMPLETION_TOKENS).get("reasoning_effort").getAsString());
        assertNull("On keeps the model's own default", body(simple("gpt-5-mini", ThinkingMode.ON,
                ThinkingMode.DEFAULT, 120), Relaxation.MAX_COMPLETION_TOKENS).get("reasoning_effort"));

        JsonObject noLevels = body(off, Relaxation.MAX_COMPLETION_TOKENS, Relaxation.NO_REASONING_EFFORT);
        assertFalse("a model that refuses levels gets none", noLevels.has("reasoning_effort"));
        assertEquals("but keeps the room", 120 + ProviderCall.LEVEL_ONLY_REASONING_TOKENS,
                noLevels.get("max_completion_tokens").getAsInt());
    }

    @Test
    public void aStreamedAnswerAsksForItsTokenUsage() {
        JsonObject body = body(simple("gemma-4-31b", ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, 0));
        assertTrue("without it OpenAI, vLLM and Ollama's /v1 report no usage, and the token budget counts nothing",
                body.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
        assertFalse(body(simple("gemma-4-31b", ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, 0),
                Relaxation.NO_STREAM_OPTIONS).has("stream_options"));
    }

    @Test
    public void weakerFormsRenameOrLeaveOutTheRefusedField() {
        JsonObject strict = body(full());
        assertEquals(900, strict.get("max_tokens").getAsInt());
        assertEquals("low", strict.get("reasoning_effort").getAsString());
        assertTrue(strict.has("temperature"));

        JsonObject renamed = body(full(), Relaxation.MAX_COMPLETION_TOKENS);
        assertFalse(renamed.has("max_tokens"));
        assertEquals("the same budget under the new name", 900, renamed.get("max_completion_tokens").getAsInt());
        assertFalse(body(full(), Relaxation.NO_TEMPERATURE).has("temperature"));
        assertFalse(body(full(), Relaxation.NO_REASONING_EFFORT).has("reasoning_effort"));
        JsonObject others = body(full(), Relaxation.NO_TEMPERATURE);
        assertTrue("only the refused field changes", others.has("max_tokens") && others.has("reasoning_effort")
                && others.has("response_format") && others.has("stream_options"));
    }

    @Test
    public void aRefusedJsonFormSwapsToTheOtherFormThenToNone() {
        JsonObject json = body(full(), Relaxation.OTHER_JSON_FORM).getAsJsonObject("response_format");
        assertEquals("LM Studio accepts only json_schema", "json_schema", json.get("type").getAsString());
        assertEquals("object", json.getAsJsonObject("json_schema").getAsJsonObject("schema").get("type")
                .getAsString());

        ProviderCall schemaCall = call("m", Collections.singletonList(ModelMessage.user("q")), null,
                OutputFormat.jsonSchema("{\"type\":\"object\",\"required\":[\"a\"]}"), ThinkingMode.DEFAULT,
                ThinkingMode.DEFAULT, null, 0);
        assertEquals("a schema answer falls back to json_object", "json_object",
                body(schemaCall, Relaxation.OTHER_JSON_FORM).getAsJsonObject("response_format").get("type")
                        .getAsString());
        assertFalse(body(full(), Relaxation.OTHER_JSON_FORM, Relaxation.NO_RESPONSE_FORMAT).has("response_format"));
    }

    @Test
    public void aRefusalIsAnsweredByTheFieldItNames() {
        assertEquals("OpenAI's reasoning models", Relaxation.MAX_COMPLETION_TOKENS, refusal("max_tokens",
                "Unsupported parameter: 'max_tokens' is not supported with this model. Use 'max_completion_tokens' "
                        + "instead."));
        assertEquals(Relaxation.NO_TEMPERATURE, refusal("temperature", "Unsupported value: 'temperature' does "
                + "not support 0.2 with this model. Only the default (1) value is supported."));
        assertEquals("a model without reasoning levels", Relaxation.NO_REASONING_EFFORT, refusal(null,
                "Unsupported parameter: 'reasoning_effort' is not supported with this model."));
        assertEquals("Ollama's /v1 names thinking", Relaxation.NO_REASONING_EFFORT, refusal(null,
                "registry.ollama.ai/library/gemma3:latest does not support thinking"));
        assertEquals("LM Studio", Relaxation.OTHER_JSON_FORM, refusal(null,
                "'response_format.type' must be 'json_schema'"));
        assertEquals("the other form refused as well", Relaxation.NO_RESPONSE_FORMAT, refusal("response_format",
                "Invalid schema for response_format 'answer'", Relaxation.OTHER_JSON_FORM));
        assertEquals("a parameter outside the fields: the message decides", Relaxation.OTHER_JSON_FORM,
                refusal("messages", "'messages' must contain the word 'json' in some form, to use "
                        + "'response_format' of type 'json_object'."));
        assertEquals("a strict server's unknown field", Relaxation.NO_STREAM_OPTIONS,
                refusal("stream_options", "Extra inputs are not permitted"));
    }

    @Test
    public void aRefusalNamingNoSentFieldOrABudgetTooLargeIsReportedAsItIs() {
        assertNull("the budget's value, not its name: no weaker form helps", refusal("max_tokens",
                "max_tokens is too large: 50000. This model supports at most 16384 completion tokens."));
        assertNull(refusal(null, "'max_tokens' or 'max_completion_tokens' is too large: 2000. This model's "
                + "maximum context length is 4096 tokens"));
        assertNull("an error about something else", refusal("messages", "Invalid 'messages[0].content'"));
        assertNull(refusal(null, null));
        assertNull("a field the request did not send", OpenAiRequestWriter.relaxationFor(
                OpenAiRequestWriter.write(simple("m", ThinkingMode.DEFAULT, ThinkingMode.DEFAULT, 0)),
                EnumSet.noneOf(Relaxation.class), "temperature", "temperature is not supported"));
        assertNull("a renamed budget is not renamed again", refusal("max_completion_tokens",
                "Unsupported parameter: 'max_completion_tokens'", Relaxation.MAX_COMPLETION_TOKENS));
    }
}
