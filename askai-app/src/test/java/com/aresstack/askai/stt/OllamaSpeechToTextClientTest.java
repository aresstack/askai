package com.aresstack.askai.stt;

import com.aresstack.audio.domain.PcmAudioFormat;
import com.aresstack.audio.infrastructure.WavFileAudioSink;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** The Ollama STT adapter against a local fake HTTP server: request shape, JSON parsing, structured errors. */
public class OllamaSpeechToTextClientTest {

    private HttpServer server;
    private volatile int responseStatus = 200;
    private volatile String responseBody = "{\"text\":\"hallo welt\"}";
    private volatile long responseDelayMillis = 0;
    private volatile CountDownLatch responseGate; // when set, handler waits on it before responding

    private volatile String requestPath;
    private volatile String requestContentType;
    private volatile byte[] requestBody;

    private File wav;

    @Before
    public void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpHandler() {
            public void handle(HttpExchange exchange) throws java.io.IOException {
                requestPath = exchange.getRequestURI().getPath();
                requestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
                requestBody = drain(exchange.getRequestBody());
                CountDownLatch gate = responseGate;
                if (gate != null) {
                    try {
                        gate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                    }
                }
                if (responseDelayMillis > 0) {
                    try {
                        Thread.sleep(responseDelayMillis);
                    } catch (InterruptedException ignored) {
                    }
                }
                byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                out.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        wav = File.createTempFile("askai-stt-", ".wav");
        wav.deleteOnExit();
        WavFileAudioSink sink = new WavFileAudioSink(wav);
        sink.open(new PcmAudioFormat(16000, 1, 16));
        sink.write(new short[]{1, 2, 3, 4, 5, 6, 7, 8}, 8);
        sink.close();
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    private OllamaSpeechToTextClient client() {
        return client(600);
    }

    private OllamaSpeechToTextClient client(int timeoutSeconds) {
        return new OllamaSpeechToTextClient("http://127.0.0.1:" + server.getAddress().getPort(), timeoutSeconds);
    }

    private SpeechToTextService.TranscriptionRequest request(String model, String language, String prompt) {
        return new SpeechToTextService.TranscriptionRequest(wav, model, language, prompt);
    }

    // ------------------------------------------------------------------ request shape + success

    @Test
    public void postsMultipartWithModelWavAndParsesJson() throws Exception {
        OllamaSpeechToTextClient client = client();
        String text = client.transcribe(request("voxtral:latest", "de", "hello"));
        assertEquals("hallo welt", text);
        assertEquals("/v1/audio/transcriptions", requestPath);
        assertNotNull(requestContentType);
        assertTrue(requestContentType, requestContentType.startsWith("multipart/form-data; boundary="));

        String body = new String(requestBody, StandardCharsets.ISO_8859_1);
        assertTrue("model field", body.contains("name=\"model\"") && body.contains("voxtral:latest"));
        assertTrue("file part", body.contains("name=\"file\""));
        assertTrue("audio/wav mime", body.contains("Content-Type: audio/wav"));
        assertTrue("response_format json", body.contains("name=\"response_format\"") && body.contains("json"));
        assertTrue("language", body.contains("name=\"language\"") && body.contains("de"));
        assertTrue("prompt", body.contains("name=\"prompt\"") && body.contains("hello"));
        assertTrue("wav bytes uploaded", body.contains("RIFF") && body.contains("WAVE"));
        assertEquals(200, client.lastHttpStatus());
    }

    @Test
    public void omitsLanguageWhenAuto() throws Exception {
        client().transcribe(request("m", "auto", ""));
        String body = new String(requestBody, StandardCharsets.ISO_8859_1);
        assertTrue("no language field for auto", !body.contains("name=\"language\""));
        assertTrue("no prompt field when empty", !body.contains("name=\"prompt\""));
    }

    // ------------------------------------------------------------------ structured errors

    @Test
    public void missingTextFieldIsBadJson() {
        responseBody = "{\"model\":\"m\"}";
        assertKind(TranscriptionErrorKind.BAD_JSON);
    }

    @Test
    public void invalidJsonIsBadJson() {
        responseBody = "not really json";
        assertKind(TranscriptionErrorKind.BAD_JSON);
    }

    @Test
    public void emptyTextIsEmptyResult() {
        responseBody = "{\"text\":\"   \"}";
        assertKind(TranscriptionErrorKind.EMPTY_RESULT);
    }

    @Test
    public void http404IsEndpointNotFound() {
        responseStatus = 404;
        responseBody = "404 page not found";
        assertKind(TranscriptionErrorKind.ENDPOINT_NOT_FOUND);
    }

    @Test
    public void http400IsBadRequest() {
        responseStatus = 400;
        responseBody = "{\"error\":\"bad request\"}";
        assertKind(TranscriptionErrorKind.BAD_REQUEST);
    }

    @Test
    public void http422IsBadRequest() {
        responseStatus = 422;
        responseBody = "{\"error\":\"unprocessable\"}";
        assertKind(TranscriptionErrorKind.BAD_REQUEST);
    }

    @Test
    public void http500IsServerError() {
        responseStatus = 500;
        responseBody = "{\"error\":\"boom\"}";
        assertKind(TranscriptionErrorKind.SERVER_ERROR);
    }

    @Test
    public void modelWithoutAudioEncoderIsModelNotAudio() {
        responseStatus = 400;
        responseBody = "{\"error\":{\"message\":\"this model does not support audio input (missing mmproj)\"}}";
        SpeechToTextException ex = assertKind(TranscriptionErrorKind.MODEL_NOT_AUDIO);
        // The adapter must not contain hardcoded install instructions / example models.
        assertTrue("no install instruction", !ex.getMessage().toLowerCase().contains("ollama pull"));
    }

    @Test
    public void timeoutIsTimeout() {
        responseDelayMillis = 2500;
        try {
            client(1).transcribe(request("m", "", ""));
            fail("expected timeout");
        } catch (SpeechToTextException ex) {
            assertEquals(TranscriptionErrorKind.TIMEOUT, ex.getKind());
        }
    }

    @Test
    public void abortDuringResponseIsCancelled() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        responseGate = gate;
        final OllamaSpeechToTextClient client = client();
        final AtomicReference<SpeechToTextException> caught = new AtomicReference<SpeechToTextException>();
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    client.transcribe(request("m", "", ""));
                } catch (SpeechToTextException ex) {
                    caught.set(ex);
                }
            }
        });
        worker.start();
        Thread.sleep(300);       // let the request reach the server and block on the gate
        client.abort();          // user cancels
        gate.countDown();        // release the server so it can finish
        worker.join(5000);
        assertNotNull("expected a failure", caught.get());
        assertEquals(TranscriptionErrorKind.CANCELLED, caught.get().getKind());
    }

    private SpeechToTextException assertKind(TranscriptionErrorKind expected) {
        try {
            client().transcribe(request("m", "", ""));
            fail("expected " + expected);
            return null;
        } catch (SpeechToTextException ex) {
            assertEquals(expected, ex.getKind());
            return ex;
        }
    }

    private static byte[] drain(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
