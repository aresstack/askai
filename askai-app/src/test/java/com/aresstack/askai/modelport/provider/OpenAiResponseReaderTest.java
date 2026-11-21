package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.agent.model.port.ChatResult;

import org.junit.Test;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The answer of {@code POST /chat/completions}: server-sent events with content and reasoning deltas, tool calls in
 * pieces, usage, errors inside the stream, and the one-piece answer of a service that ignores streaming.
 */
public class OpenAiResponseReaderTest {

    /** Records the deltas in arrival order: {@code T:} thinking, {@code C:} content. */
    private static final class Deltas implements ProviderStream {
        final List<String> seen = new ArrayList<String>();

        public void onThinkingDelta(String delta) {
            seen.add("T:" + delta);
        }

        public void onContentDelta(String delta) {
            seen.add("C:" + delta);
        }
    }

    private static OpenAiResponseReader read(String events, Deltas deltas) throws Exception {
        OpenAiResponseReader reader = new OpenAiResponseReader(deltas);
        reader.readEvents(new BufferedReader(new StringReader(events)), AbortSignal.NEVER);
        return reader;
    }

    private static String data(String json) {
        return "data: " + json + "\n\n";
    }

    @Test
    public void contentAndReasoningDeltasArriveInOrderAndEndAtDone() throws Exception {
        Deltas deltas = new Deltas();
        OpenAiResponseReader reader = read(": keep-alive comment\n\n"
                + "event: message\n"
                + data("{\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":null}}]}")
                + data("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"Let me \"}}]}")
                + data("{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"think.\"}}]}")
                + data("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"}}]}")
                + data("{\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}")
                + data("{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}")
                + data("{\"choices\":[],\"usage\":{\"prompt_tokens\":42,\"completion_tokens\":7,"
                + "\"total_tokens\":49}}")
                + "data: [DONE]\n\n"
                + data("{\"choices\":[{\"delta\":{\"content\":\"after done is ignored\"}}]}"), deltas);

        assertTrue(reader.isComplete());
        ProviderCompletion completion = reader.completion();
        assertEquals("Hello world", completion.getContent());
        assertEquals("Let me think.", completion.getThinking());
        assertEquals(ChatResult.FinishReason.STOP, completion.getFinishReason());
        assertEquals(42, completion.getUsage().getPromptTokens());
        assertEquals(7, completion.getUsage().getOutputTokens());
        assertTrue(completion.getToolCalls().isEmpty());
        assertEquals("[T:Let me , T:think., C:Hello, C: world]", deltas.seen.toString());
    }

    @Test
    public void reasoningUnderItsOtherNameAndMessageInsteadOfDeltaAreRead() throws Exception {
        Deltas deltas = new Deltas();
        OpenAiResponseReader reader = read(
                data("{\"choices\":[{\"delta\":{\"reasoning\":\"short thought\"}}]}")
                        + data("{\"choices\":[{\"message\":{\"content\":\"answer\"},\"finish_reason\":\"length\"}]}")
                        + data("{\"usage\":{\"prompt_tokens\":0,\"completion_tokens\":0}}")
                        + "data:[DONE]\n", deltas);

        ProviderCompletion completion = reader.completion();
        assertEquals("short thought", completion.getThinking());
        assertEquals("answer", completion.getContent());
        assertEquals(ChatResult.FinishReason.LENGTH, completion.getFinishReason());
        assertEquals("a usage block of zeros means not reported", -1, completion.getUsage().getPromptTokens());
        assertEquals(-1, completion.getUsage().getOutputTokens());
    }

    @Test
    public void toolCallsInPiecesArePutTogetherByIndex() throws Exception {
        OpenAiResponseReader reader = read(
                data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_a\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"search\",\"arguments\":\"\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"{\\\"q\\\":\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":1,\"id\":\"call_b\","
                        + "\"function\":{\"name\":\"fetch\",\"arguments\":{\"url\":\"u\"}}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":"
                        + "{\"arguments\":\"\\\"askai\\\"}\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
                        + "data: [DONE]\n", new Deltas());

        ProviderCompletion completion = reader.completion();
        assertEquals(ChatResult.FinishReason.TOOL_CALLS, completion.getFinishReason());
        assertEquals(2, completion.getToolCalls().size());
        assertEquals("call_a", completion.getToolCalls().get(0).getId());
        assertEquals("search", completion.getToolCalls().get(0).getName());
        assertEquals("{\"q\":\"askai\"}", completion.getToolCalls().get(0).getArgumentsJson());
        assertEquals("call_b", completion.getToolCalls().get(1).getId());
        assertEquals("arguments sent as an object are kept as JSON", "{\"url\":\"u\"}",
                completion.getToolCalls().get(1).getArgumentsJson());
    }

    @Test
    public void completeCallsWithoutIndexInSeparateChunksStayApart() throws Exception {
        // Gemini's compatible endpoint: each complete call in its own chunk, no "index", always at position 0
        OpenAiResponseReader reader = read(
                data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"f\",\"arguments\":\"{\\\"q\\\":1}\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"b\",\"type\":\"function\","
                        + "\"function\":{\"name\":\"g\",\"arguments\":\"{\\\"q\\\":2}\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"c\",\"function\":{\"name\":\"h\","
                        + "\"arguments\":\"{}\"}},{\"id\":\"d\",\"function\":{\"name\":\"k\",\"arguments\":\"{}\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}")
                        + "data: [DONE]\n", new Deltas());

        List<com.aresstack.askai.agent.model.port.ToolCall> calls = reader.completion().getToolCalls();
        assertEquals(4, calls.size());
        assertEquals("f", calls.get(0).getName());
        assertEquals("{\"q\":1}", calls.get(0).getArgumentsJson());
        assertEquals("g", calls.get(1).getName());
        assertEquals("b", calls.get(1).getId());
        assertEquals("{\"q\":2}", calls.get(1).getArgumentsJson());
        assertEquals("two calls of one chunk keep their positions", "h", calls.get(2).getName());
        assertEquals("k", calls.get(3).getName());
    }

    @Test
    public void piecesWithoutIndexThatContinueACallArePutTogether() throws Exception {
        OpenAiResponseReader reader = read(
                data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"function\":{\"name\":\"search\","
                        + "\"arguments\":\"{\\\"q\\\"\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"arguments\":\":1\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"id\":\"a\",\"function\":"
                        + "{\"arguments\":\"}\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{\"tool_calls\":[{\"function\":{\"name\":\"fetch\","
                        + "\"arguments\":\"{}\"}}]}}]}")
                        + data("{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}"), new Deltas());

        List<com.aresstack.askai.agent.model.port.ToolCall> calls = reader.completion().getToolCalls();
        assertEquals("a repeated id continues its call; a new name without an id starts one", 2, calls.size());
        assertEquals("search", calls.get(0).getName());
        assertEquals("{\"q\":1}", calls.get(0).getArgumentsJson());
        assertEquals("fetch", calls.get(1).getName());
    }

    @Test
    public void theParameterAnErrorNamesIsRead() {
        assertEquals("max_tokens", OpenAiResponseReader.errorParam("{\"error\":{\"message\":\"Unsupported\","
                + "\"type\":\"invalid_request_error\",\"param\":\"max_tokens\",\"code\":\"unsupported_parameter\"}}"));
        assertEquals("older vLLM", "temperature", OpenAiResponseReader.errorParam(
                "{\"object\":\"error\",\"message\":\"m\",\"param\":\"temperature\"}"));
        String fastApi = "{\"detail\":[{\"type\":\"extra_forbidden\",\"loc\":[\"body\",\"stream_options\"],"
                + "\"msg\":\"Extra inputs are not permitted\",\"input\":{\"temperature\":0.2,\"max_tokens\":9}}]}";
        assertEquals("FastAPI's validation entry", "stream_options", OpenAiResponseReader.errorParam(fastApi));
        assertEquals("the message names the field, never the echoed input",
                "stream_options: Extra inputs are not permitted", OpenAiResponseReader.errorMessage(fastApi));
        assertNull(OpenAiResponseReader.errorParam("{\"error\":{\"message\":\"x\",\"param\":null}}"));
        assertNull(OpenAiResponseReader.errorParam("not json"));
    }

    @Test
    public void anErrorInsideTheStreamEndsTheCall() throws Exception {
        try {
            read(data("{\"choices\":[{\"delta\":{\"content\":\"par\"}}]}")
                    + data("{\"error\":{\"message\":\"model overloaded\",\"type\":\"server_error\"}}"), new Deltas());
            fail("an error event must end the call");
        } catch (ProviderException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("model overloaded"));
            assertFalse(expected.isUnreachable());
        }
    }

    @Test
    public void aStreamThatJustStopsIsNotComplete() throws Exception {
        OpenAiResponseReader reader = read(data("{\"choices\":[{\"delta\":{\"content\":\"cut\"}}]}"), new Deltas());
        assertFalse("no [DONE] and no finish reason", reader.isComplete());

        OpenAiResponseReader finished = read(data("{\"choices\":[{\"delta\":{\"content\":\"x\"},"
                + "\"finish_reason\":\"stop\"}]}"), new Deltas());
        assertTrue("a finish reason without [DONE] is a proper end", finished.isComplete());
    }

    @Test
    public void unreadableDataIsNamed() throws Exception {
        try {
            read("data: {not json\n\n", new Deltas());
            fail("invalid JSON must fail");
        } catch (ProviderException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("unreadable"));
        }
    }

    @Test
    public void aServiceThatIgnoresStreamingAnswersInOnePiece() throws Exception {
        Deltas deltas = new Deltas();
        OpenAiResponseReader reader = new OpenAiResponseReader(deltas);
        reader.readCompletion("{\"id\":\"x\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"whole answer\",\"reasoning_content\":\"why\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}");

        assertTrue(reader.isComplete());
        ProviderCompletion completion = reader.completion();
        assertEquals("whole answer", completion.getContent());
        assertEquals("why", completion.getThinking());
        assertEquals(10, completion.getUsage().getPromptTokens());
        assertEquals("the listener still sees the text", "[T:why, C:whole answer]", deltas.seen.toString());
    }

    @Test
    public void errorBodiesAndEventLinesAreRead() {
        assertEquals("bad key", OpenAiResponseReader.errorMessage("{\"error\":{\"message\":\"bad key\"}}"));
        assertEquals("plain", OpenAiResponseReader.errorMessage("{\"error\":\"plain\"}"));
        assertNull(OpenAiResponseReader.errorMessage("<html>gateway</html>"));
        assertEquals("FastAPI", "other shape", OpenAiResponseReader.errorMessage("{\"detail\":\"other shape\"}"));
        assertEquals("older vLLM", "top level", OpenAiResponseReader.errorMessage(
                "{\"object\":\"error\",\"message\":\"top level\",\"type\":\"BadRequestError\",\"param\":null}"));
        assertNull(OpenAiResponseReader.errorMessage("{\"unrelated\":true}"));

        assertEquals("{\"a\":1}", OpenAiResponseReader.payloadOf("data: {\"a\":1}"));
        assertEquals("[DONE]", OpenAiResponseReader.payloadOf("data:[DONE]"));
        assertNull(OpenAiResponseReader.payloadOf(": comment"));
        assertNull(OpenAiResponseReader.payloadOf("event: completion"));
        assertNull(OpenAiResponseReader.payloadOf("   "));

        assertEquals(ChatResult.FinishReason.OTHER, OpenAiResponseReader.finishReason("content_filter", false));
        assertEquals(ChatResult.FinishReason.STOP, OpenAiResponseReader.finishReason(null, false));
        assertEquals(ChatResult.FinishReason.TOOL_CALLS, OpenAiResponseReader.finishReason("stop", true));
    }
}
