package com.aresstack.askai.java8.stt;

import com.aresstack.askai.java8.json.SimpleJson;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Adapter for Ollama's OpenAI-compatible {@code POST /v1/audio/transcriptions} endpoint. The UI
 * never sees HTTP, multipart encoding, or JSON — it talks to {@link SpeechToTextService} and this
 * class stays an implementation detail behind {@link DefaultSpeechToTextService}.
 *
 * <p>One instance handles one request; {@link #abort()} disconnects the live connection so an
 * in-flight upload or read fails fast with an {@link IOException} (used for user cancellation).</p>
 *
 * <p>Note: the endpoint is available in newer Ollama versions only, and the model must accept
 * audio input. Both failure modes are mapped to clear messages.</p>
 */
final class OllamaSpeechToTextClient {

    private static final String BOUNDARY_PREFIX = "askai-java8-stt-";
    private static final String CRLF = "\r\n";

    /** Cap on how much of a response/error body is read, so a huge/hostile body can't exhaust memory. */
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final String baseUrl;
    private final int timeoutSeconds;
    private volatile HttpURLConnection activeConnection;
    private volatile boolean aborted;
    private volatile int lastStatus;

    OllamaSpeechToTextClient(String baseUrl, int timeoutSeconds) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.timeoutSeconds = timeoutSeconds > 0 ? timeoutSeconds : SpeechToTextConfiguration.DEFAULT_TIMEOUT_SECONDS;
    }

    /** Aborts the request in flight: in-progress IO fails with an IOException. Safe to call anytime. */
    void abort() {
        aborted = true;
        HttpURLConnection connection = activeConnection;
        if (connection != null) {
            connection.disconnect();
        }
    }

    /** @return the HTTP status of the last transcription attempt (for diagnostics), or 0. */
    int lastHttpStatus() {
        return lastStatus;
    }

    /**
     * Uploads the audio file and returns the transcription text.
     *
     * @throws SpeechToTextException with a user-readable message for every known failure mode.
     */
    String transcribe(SpeechToTextService.TranscriptionRequest request) throws SpeechToTextException {
        File audioFile = request.getAudioFile();
        String boundary = BOUNDARY_PREFIX + System.nanoTime();
        byte[] preamble = buildPreamble(boundary, request, audioFile.getName());
        byte[] epilogue = (CRLF + "--" + boundary + "--" + CRLF).getBytes(StandardCharsets.UTF_8);
        long contentLength = preamble.length + audioFile.length() + epilogue.length;

        HttpURLConnection connection = null;
        try {
            connection = open(contentLength, boundary);
            activeConnection = connection;
            writeBody(connection, preamble, audioFile, epilogue);
            return readTranscription(connection, request.getModelName());
        } catch (SpeechToTextException ex) {
            throw ex;
        } catch (SocketTimeoutException ex) {
            throw new SpeechToTextException(TranscriptionErrorKind.TIMEOUT, 0,
                    "The transcription timed out after " + timeoutSeconds + " seconds.", ex);
        } catch (ConnectException ex) {
            throw new SpeechToTextException(TranscriptionErrorKind.UNREACHABLE, 0,
                    "Ollama at " + baseUrl + " is not reachable: " + messageOf(ex), ex);
        } catch (IOException ex) {
            if (aborted) {
                throw new SpeechToTextException(TranscriptionErrorKind.CANCELLED, 0, "Transcription cancelled.", ex);
            }
            throw new SpeechToTextException(TranscriptionErrorKind.FAILED, 0,
                    "Transcription failed: " + messageOf(ex), ex);
        } finally {
            activeConnection = null;
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private HttpURLConnection open(long contentLength, String boundary) throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(baseUrl + "/v1/audio/transcriptions").openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(timeoutSeconds * 1000);
        // Stream the file instead of buffering it in memory; audio files can be large.
        connection.setFixedLengthStreamingMode(contentLength);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "askai-java8");
        return connection;
    }

    /** The multipart fields (model, optional language/prompt) plus the header of the file part. */
    private byte[] buildPreamble(String boundary, SpeechToTextService.TranscriptionRequest request,
                                 String fileName) {
        StringBuilder builder = new StringBuilder();
        if (request.getModelName().length() > 0) {
            appendField(builder, boundary, "model", request.getModelName());
        }
        String language = request.getLanguage();
        if (language.length() > 0 && !"auto".equalsIgnoreCase(language)) {
            appendField(builder, boundary, "language", language);
        }
        if (request.getPrompt().length() > 0) {
            appendField(builder, boundary, "prompt", request.getPrompt());
        }
        appendField(builder, boundary, "response_format", "json");
        builder.append("--").append(boundary).append(CRLF);
        builder.append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(sanitizeFileName(fileName)).append('"').append(CRLF);
        builder.append("Content-Type: ").append(mimeForFile(fileName)).append(CRLF);
        builder.append(CRLF);
        return builder.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** @return the audio MIME type for the file extension; generated recordings are {@code audio/wav}. */
    private static String mimeForFile(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase();
        if (lower.endsWith(".wav")) {
            return "audio/wav";
        }
        if (lower.endsWith(".mp3")) {
            return "audio/mpeg";
        }
        if (lower.endsWith(".m4a")) {
            return "audio/mp4";
        }
        if (lower.endsWith(".ogg")) {
            return "audio/ogg";
        }
        if (lower.endsWith(".flac")) {
            return "audio/flac";
        }
        return "application/octet-stream";
    }

    private static void appendField(StringBuilder builder, String boundary, String name, String value) {
        builder.append("--").append(boundary).append(CRLF);
        builder.append("Content-Disposition: form-data; name=\"").append(name).append('"').append(CRLF);
        builder.append(CRLF);
        builder.append(value).append(CRLF);
    }

    private static String sanitizeFileName(String fileName) {
        return fileName.replace('"', '_').replace('\\', '_').replace("\r", "").replace("\n", "");
    }

    private void writeBody(HttpURLConnection connection, byte[] preamble, File audioFile, byte[] epilogue)
            throws IOException {
        OutputStream outputStream = null;
        InputStream fileStream = null;
        try {
            outputStream = connection.getOutputStream();
            outputStream.write(preamble);
            fileStream = new FileInputStream(audioFile);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = fileStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
            }
            outputStream.write(epilogue);
            outputStream.flush();
        } finally {
            closeQuietly(fileStream);
            closeQuietly(outputStream);
        }
    }

    private String readTranscription(HttpURLConnection connection, String modelName)
            throws IOException, SpeechToTextException {
        int status = connection.getResponseCode();
        lastStatus = status;
        String body = readText(status >= 200 && status < 300
                ? connection.getInputStream() : connection.getErrorStream());

        if (status == 404) {
            throw new SpeechToTextException(TranscriptionErrorKind.ENDPOINT_NOT_FOUND, 404,
                    "This Ollama server does not offer /v1/audio/transcriptions.", null);
        }
        if (status < 200 || status >= 300) {
            throw classifyHttpError(status, body);
        }

        Object parsed;
        try {
            parsed = SimpleJson.parse(body);
        } catch (RuntimeException ex) {
            throw new SpeechToTextException(TranscriptionErrorKind.BAD_JSON, status,
                    "Ollama returned an invalid transcription response (not JSON): " + excerpt(body), ex);
        }
        if (!(parsed instanceof Map)) {
            throw new SpeechToTextException(TranscriptionErrorKind.BAD_JSON, status,
                    "Ollama returned an unexpected transcription response: " + excerpt(body), null);
        }
        Object text = ((Map) parsed).get("text");
        if (text == null) {
            throw new SpeechToTextException(TranscriptionErrorKind.BAD_JSON, status,
                    "The transcription response contained no text field: " + excerpt(body), null);
        }
        String transcription = String.valueOf(text).trim();
        if (transcription.length() == 0) {
            throw new SpeechToTextException(TranscriptionErrorKind.EMPTY_RESULT, status,
                    "The transcription came back empty.", null);
        }
        return transcription;
    }

    /**
     * Classifies a non-2xx response into a structured {@link TranscriptionErrorKind}, keeping the
     * server's own error text but adding no UI wording or install instructions (that is the
     * application layer's job).
     */
    private SpeechToTextException classifyHttpError(int status, String body) {
        String serverMessage = extractErrorMessage(body);
        String lower = serverMessage.toLowerCase();
        if (lower.contains("mmproj") || lower.contains("audio input is not supported")
                || (lower.contains("does not support") && lower.contains("audio"))) {
            return new SpeechToTextException(TranscriptionErrorKind.MODEL_NOT_AUDIO, status,
                    "The selected model cannot accept audio"
                            + (serverMessage.length() > 0 ? " (server: " + serverMessage + ")" : "") + ".", null);
        }
        TranscriptionErrorKind kind = status == 400 || status == 422
                ? TranscriptionErrorKind.BAD_REQUEST
                : status >= 500 ? TranscriptionErrorKind.SERVER_ERROR : TranscriptionErrorKind.FAILED;
        return new SpeechToTextException(kind, status, "Transcription failed with HTTP " + status
                + (serverMessage.length() > 0 ? ": " + serverMessage : ""), null);
    }

    private String extractErrorMessage(String body) {
        if (body == null || body.trim().length() == 0) {
            return "";
        }
        try {
            Object parsed = SimpleJson.parse(body);
            if (parsed instanceof Map) {
                Object error = ((Map) parsed).get("error");
                if (error instanceof Map) {
                    Object message = ((Map) error).get("message");
                    if (message != null) {
                        return String.valueOf(message);
                    }
                }
                if (error != null) {
                    return String.valueOf(error);
                }
            }
        } catch (RuntimeException ignored) {
            // Not JSON: fall through to the raw excerpt.
        }
        return excerpt(body);
    }

    private static String excerpt(String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return text.length() > 300 ? text.substring(0, 300) + " …" : text;
    }

    /** Reads at most {@link #MAX_BODY_BYTES} of the stream (a transcription JSON is tiny; error/HTML
     *  bodies are bounded so a hostile response can't exhaust memory). */
    private String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] bytes = new byte[8192];
        int read;
        try {
            while (buffer.size() < MAX_BODY_BYTES && (read = inputStream.read(bytes)) >= 0) {
                buffer.write(bytes, 0, read);
            }
        } finally {
            closeQuietly(inputStream);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable.getMessage();
        return message != null && message.trim().length() > 0 ? message : throwable.getClass().getSimpleName();
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String normalizeBaseUrl(String value) {
        String normalized = value == null || value.trim().length() == 0
                ? "http://127.0.0.1:11434" : value.trim();
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
