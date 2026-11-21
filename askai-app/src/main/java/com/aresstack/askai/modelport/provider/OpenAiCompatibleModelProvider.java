package com.aresstack.askai.modelport.provider;

import com.aresstack.askai.client.OllamaResidentModel;
import com.aresstack.askai.modelport.ModelPortSettings;
import com.aresstack.askai.modelport.ModelProviderSettings;
import com.aresstack.askai.modelport.provider.OpenAiRequestWriter.Relaxation;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The wire of an OpenAI-compatible service ({@code POST <base URL>/chat/completions}, {@code GET <base URL>/models})
 * behind the model port. No provider is built in: the user sets the base URL, the API key and a model per task
 * (slot); the provider works with any service that speaks the OpenAI chat-completions protocol.
 *
 * <p>Resources: the service serves many models side by side, so {@link #sharesMemory()} is false. Every model runs
 * at the same time as the others, several calls per model ({@link Settings#defaultCapacity()}, default 8), and
 * AskAI never loads, warms or unloads a model there. The context the port plans with is a setting
 * ({@link Settings#contextTokens}); it is never sent, the service knows its models.</p>
 *
 * <p>Wire rules, from the OpenAI-compatible chat adapter of Space Harvester where each was observed against a real
 * service: the body is UTF-8 JSON, streaming is asked for only in the body, the request carries no
 * {@code Accept: text/event-stream} (one service refused it), {@code stop} is never sent as a string. HTTP 429 and
 * 502/503/504 before the first answer byte are retried ({@link Settings#retries()}, waiting {@code Retry-After}
 * up to 30 s, else 1 s, 2 s, ...).</p>
 *
 * <p>Refused fields: services accept different optional fields. When a service refuses one with HTTP 400 (or 422),
 * naming it in the error's {@code param} or message, the request is sent again with that field in a weaker form
 * ({@link OpenAiRequestWriter.Relaxation}: {@code max_completion_tokens} for {@code max_tokens}, no
 * {@code temperature}, no {@code reasoning_effort}, no {@code stream_options}, the other JSON form, then no
 * {@code response_format}). Such a try is no retry. Once a weaker request is accepted, the provider keeps its form
 * for the model's calls of the same kind (answer form and thinking level) at that base URL, so later calls do not
 * pay the refusal again and calls of another kind keep the fields their own requests had accepted.</p>
 *
 * <p>Abort: a cancel or time-out never waits for the wire, and the port hands the call's place on at once (no
 * memory is shared, so there is no order of calls to protect). While the service has not answered yet, the
 * connection is closed at once. During a stream the JDK can close it only when the blocked read returns, so the
 * stream ends at the next chunk: a service that streams nothing while it reasons keeps generating until its next
 * chunk (or the read timeout). Ollama's path ends a stream at the next chunk as well.</p>
 *
 * <p>Credentials: the API key is read for each request from {@link Settings#apiKey()} and sent only as the
 * {@code Authorization: Bearer} header. It never appears in a message, an exception or a log line; a service
 * echoing it back in an error body is redacted, and a key with a line break or another control character is
 * refused before any header is set (the JDK's header check would quote it).</p>
 *
 * <p>UNVERIFIED against the service the API is tested with: {@code response_format} with {@code json_schema},
 * {@code reasoning_effort}, tool calls, whether a closed connection stops the generation there, and the refusal
 * texts the weaker forms react to (taken from the documented errors of OpenAI, Ollama's {@code /v1}, vLLM and
 * LM Studio).</p>
 */
public final class OpenAiCompatibleModelProvider implements ModelProvider {

    /** The settings of the service, read for every request so a save applies to the next call. */
    public interface Settings {
        /** Concurrent calls per model when no per-model value is set. */
        int defaultCapacity();

        /** The context the port plans a call of {@code model} with. */
        int contextTokens(String model);

        int connectTimeoutMillis();

        /** The longest wait for the next bytes of an answer. */
        int readTimeoutMillis();

        /** How often a rate-limited or overloaded request (429, 502, 503, 504) is repeated. */
        int retries();

        /**
         * The API key, or null when none is set; the caller clears the array after use.
         *
         * @throws IOException when a stored key cannot be read (the message names no part of it)
         */
        char[] apiKey() throws IOException;
    }

    /** Opens an unconnected connection (the app's proxy and TLS settings); a seam for tests. */
    public interface ConnectionOpener {
        HttpURLConnection open(String url) throws IOException;
    }

    /** Where a one-off model listing gets its key; the caller of {@link #listModels} clears the array. */
    public interface KeySource {
        /** The key, or null for none. */
        char[] apiKey() throws IOException;
    }

    /**
     * Lists the models of {@code baseUrl} once, without retries (15 s to connect, 30 s to read): the settings page
     * and the setup wizard, before anything is saved.
     */
    public static List<String> listModels(String baseUrl, final KeySource key, ConnectionOpener connections)
            throws ProviderException {
        return new OpenAiCompatibleModelProvider(new Settings() {
            public int defaultCapacity() {
                return 1;
            }

            public int contextTokens(String model) {
                return 1;
            }

            public int connectTimeoutMillis() {
                return 15_000;
            }

            public int readTimeoutMillis() {
                return 30_000;
            }

            public int retries() {
                return 0;
            }

            public char[] apiKey() throws IOException {
                return key == null ? null : key.apiKey();
            }
        }, connections).modelNames(baseUrl);
    }

    /** Opens a plain connection without the app's network settings (tests, tools). */
    public static final ConnectionOpener PLAIN = url -> (HttpURLConnection) new URL(url).openConnection();

    static final int MAX_DETAIL_LENGTH = 300;
    /**
     * A secret a service masked itself before echoing it (OpenAI: {@code sk-proj-****abcd}), masked whole here. A
     * match starts only where a word starts, so a long error body costs linear time.
     */
    private static final Pattern MASKED_SECRET = Pattern.compile("(?<![^\\s\"'`])[^\\s\"'`]*\\*{3,}[^\\s\"'`.,;:)]*");
    private static final long MAX_RETRY_WAIT_MILLIS = 30_000L;
    private static final List<Integer> RETRYABLE = Arrays.asList(429, 502, 503, 504);
    /** The message of a key the JDK's header check would refuse, quoting it in its own message. */
    static final String KEY_WITH_CONTROL_CHARACTERS = "the API key contains a line break or another control "
            + "character; enter it again under Settings -> AI models -> Model provider (or correct "
            + ModelProviderSettings.API_KEY_ENVIRONMENT_VARIABLE + ")";

    private final Settings settings;
    private final ConnectionOpener connections;
    /** "base URL \n model" -> the weaker forms the service accepted for the model (see the class comment). */
    private final Map<String, Set<Relaxation>> acceptedForms = new ConcurrentHashMap<String, Set<Relaxation>>();

    public OpenAiCompatibleModelProvider(Settings settings, ConnectionOpener connections) {
        if (settings == null) {
            throw new IllegalArgumentException("settings must not be null");
        }
        this.settings = settings;
        this.connections = connections == null ? PLAIN : connections;
    }

    @Override
    public boolean sharesMemory() {
        return false;
    }

    @Override
    public int defaultCapacity() {
        return Math.max(1, settings.defaultCapacity());
    }

    @Override
    public int contextTokens(String model) {
        return Math.max(1, settings.contextTokens(model));
    }

    @Override
    public ProviderCompletion chat(String baseUrl, ProviderCall call, ProviderStream stream)
            throws ProviderException {
        String url = join(baseUrl, "/chat/completions");
        // A weaker form is kept for calls of the same kind only: what a refusal means depends on the answer form
        // (the "other" JSON form of a plain JSON call is the schema form) and on the reasoning level (a service may
        // refuse temperature only beside reasoning_effort).
        String formKey = join(baseUrl, "") + "\n" + call.getModel() + "\n" + call.getOutputFormat().getKind()
                + "\n" + call.effectiveThinking();
        Set<Relaxation> forms = EnumSet.noneOf(Relaxation.class);
        Set<Relaxation> accepted = acceptedForms.get(formKey);
        if (accepted != null) {
            forms.addAll(accepted);
        }
        boolean weakened = false;
        AbortSignal abort = call.getAbort();
        int retry = 0;
        while (true) {
            if (abort.isAborted()) {
                throw new ProviderException("the call was aborted", false);
            }
            String json;
            try {
                json = OpenAiRequestWriter.write(call, forms);
            } catch (IllegalArgumentException invalid) {
                throw new ProviderException(invalid.getMessage(), false);
            }
            Response response = send(url, json.getBytes(StandardCharsets.UTF_8), abort);
            try {
                if (response.status >= 200 && response.status < 300) {
                    if (weakened) {
                        acceptedForms.put(formKey, EnumSet.copyOf(forms)); // later calls skip the refusals
                    }
                    return read(response.connection, abort, stream);
                }
                String error = readErrorBody(response.connection);
                String refusal = redact(refusalText(error), response.key);
                if (response.status == 400 || response.status == 422) {
                    Relaxation weaker = OpenAiRequestWriter.relaxationFor(json, forms,
                            OpenAiResponseReader.errorParam(error), refusal);
                    if (weaker != null && forms.add(weaker)) {
                        weakened = true;
                        System.err.println("[model-port] " + call.getModel() + ": the OpenAI-compatible API refused "
                                + weaker.field() + " (HTTP " + response.status + "), sending it again "
                                + weaker.retry());
                        continue;
                    }
                }
                String detail = shorten(refusal.isEmpty() ? redact(error, response.key) : refusal);
                if (RETRYABLE.contains(response.status) && retry < settings.retries()) {
                    long wait = retryWait(response.connection, retry);
                    System.err.println("[model-port] " + call.getModel() + ": HTTP " + response.status
                            + ", retry " + (retry + 1) + " of " + settings.retries() + " in " + wait + " ms");
                    retry++;
                    pause(wait, abort);
                    continue;
                }
                throw new ProviderException(statusMessage(response.status, detail, retry,
                        response.key != null), false);
            } catch (IOException broken) {
                if (abort.isAborted()) {
                    throw new ProviderException("the call was aborted", false);
                }
                throw new ProviderException("the answer from " + host(url) + " broke off: "
                        + broken.getClass().getSimpleName(), false);
            } finally {
                response.connection.disconnect();
            }
        }
    }

    @Override
    public void load(String baseUrl, String model, int contextTokens, String keepAlive) {
        // the service keeps its models ready; nothing to load
    }

    @Override
    public void unload(String baseUrl, String model) {
        // the service manages its own memory; AskAI never unloads a model there
    }

    @Override
    public List<OllamaResidentModel> residentModels(String baseUrl) {
        return null; // no residency to report
    }

    /** The ids of {@code GET <base URL>/models}. */
    @Override
    public List<String> modelNames(String baseUrl) throws ProviderException {
        String url = join(baseUrl, "/models");
        HttpURLConnection connection = null;
        String token = token();
        try {
            connection = connections.open(url);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(settings.connectTimeoutMillis());
            connection.setReadTimeout(settings.connectTimeoutMillis());
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            authorize(connection, token);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new ProviderException(statusMessage(status, readError(connection, token), 0,
                        token != null), false);
            }
            return modelIds(readAll(connection.getInputStream()));
        } catch (IOException failed) {
            throw new ProviderException("connection to " + host(url) + " failed: "
                    + failed.getClass().getSimpleName(), isConnectFailure(failed));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** API model ids are exact: no tag is implied. */
    @Override
    public boolean offers(List<String> names, String model) {
        String wanted = model == null ? "" : model.trim();
        for (String name : names) {
            if (name != null && name.trim().equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String missingModelMessage(String model, String slot) {
        return "Model " + model + " for the " + ModelPortSettings.describe(slot) + " is not offered by the "
                + "OpenAI-compatible API. Pick one of its models under Settings -> AI models -> Model provider.";
    }

    /** API model ids name the publisher first ({@code openai/gpt-oss-120b}); the family is read after it. */
    @Override
    public boolean isLevelOnlyModel(String model) {
        return isLevelOnlyApiModel(model);
    }

    @Override
    public String cannotTurnThinkingOffMessage(String model, String slot) {
        return "The " + ModelPortSettings.describe(slot) + " needs a model that can turn thinking off; "
                + (model == null ? "" : model.trim()) + " cannot. Pick another model for it under Settings -> AI "
                + "models -> Model provider.";
    }

    /** {@link #isLevelOnlyModel} for an API model id: the model-port rule on the name after any vendor prefix. */
    static boolean isLevelOnlyApiModel(String model) {
        String name = model == null ? "" : model.trim();
        return ModelPortSettings.isLevelOnlyModel(name.substring(name.lastIndexOf('/') + 1), null);
    }

    // ------------------------------------------------------------------ internals

    /** One sent request: the open connection, its status and the key it carried (for redaction only). */
    private static final class Response {
        final HttpURLConnection connection;
        final int status;
        final String key;

        Response(HttpURLConnection connection, int status, String key) {
            this.connection = connection;
            this.status = status;
            this.key = key;
        }
    }

    /**
     * The API key for one request, or null; a stored key that cannot be read ends the request, and so does a key
     * with a line break or another control character, before any header is set.
     */
    private String token() throws ProviderException {
        char[] key;
        try {
            key = settings.apiKey();
        } catch (IOException unreadable) {
            throw new ProviderException(unreadable.getMessage(), false);
        }
        if (key == null) {
            return null;
        }
        String token = new String(key).trim();
        clear(key);
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new ProviderException(KEY_WITH_CONTROL_CHARACTERS, false);
            }
        }
        return token.isEmpty() ? null : token;
    }

    /** Sets the bearer header; the JDK's refusal of a header value quotes the value, so its text is never used. */
    private static void authorize(HttpURLConnection connection, String token) throws ProviderException {
        if (token == null) {
            return;
        }
        try {
            connection.setRequestProperty("Authorization", "Bearer " + token);
        } catch (IllegalArgumentException refused) {
            throw new ProviderException(KEY_WITH_CONTROL_CHARACTERS, false);
        }
    }

    private Response send(String url, byte[] body, AbortSignal abort) throws ProviderException {
        String token = token();
        HttpURLConnection connection = null;
        try {
            connection = connections.open(url);
            final HttpURLConnection open = connection;
            abort.onAbort(() -> disconnectInBackground(open)); // the aborting thread never waits for the wire
            if (abort.isAborted()) {
                throw new ProviderException("the call was aborted", false);
            }
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(settings.connectTimeoutMillis());
            connection.setReadTimeout(settings.readTimeoutMillis());
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "*/*");
            authorize(connection, token);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(body);
            } finally {
                output.close();
            }
            return new Response(connection, connection.getResponseCode(), token);
        } catch (IOException failed) {
            if (connection != null) {
                connection.disconnect();
            }
            if (abort.isAborted()) {
                throw new ProviderException("the call was aborted", false);
            }
            throw new ProviderException("connection to " + host(url) + " failed: "
                    + failed.getClass().getSimpleName() + detailOf(failed, token), isConnectFailure(failed));
        }
    }

    private ProviderCompletion read(HttpURLConnection connection, AbortSignal abort, ProviderStream stream)
            throws IOException, ProviderException {
        OpenAiResponseReader reader = new OpenAiResponseReader(stream);
        String type = connection.getContentType();
        if (type != null && type.toLowerCase(Locale.ROOT).contains("application/json")) {
            // a service that ignores "stream": true answers in one piece
            reader.readCompletion(readAll(connection.getInputStream()));
        } else {
            reader.readEvents(new BufferedReader(new InputStreamReader(connection.getInputStream(),
                    StandardCharsets.UTF_8)), abort);
        }
        if (abort.isAborted()) {
            throw new ProviderException("the call was aborted", false);
        }
        if (!reader.isComplete()) {
            throw new ProviderException("the answer stream ended before [DONE]", false);
        }
        return reader.completion();
    }

    /**
     * Closes a connection on its own thread: {@link HttpURLConnection#disconnect()} waits while another thread is
     * blocked reading the stream (measured on JDK 21), and a cancel must return at once. Before the service answers,
     * the close is immediate; during a stream it happens when the blocked read returns.
     */
    private static void disconnectInBackground(final HttpURLConnection connection) {
        Thread closer = new Thread(connection::disconnect, "openai-compatible-abort");
        closer.setDaemon(true);
        closer.start();
    }

    /** {@code Retry-After} in seconds (capped), else 1 s, 2 s, 4 s, ... */
    private static long retryWait(HttpURLConnection connection, int attempt) {
        String header = connection.getHeaderField("Retry-After");
        if (header != null) {
            try {
                long seconds = Long.parseLong(header.trim());
                return Math.max(0L, Math.min(MAX_RETRY_WAIT_MILLIS, seconds * 1000L));
            } catch (NumberFormatException httpDate) {
                // an HTTP date: fall back to the backoff
            }
        }
        return Math.min(MAX_RETRY_WAIT_MILLIS, 1000L << Math.min(attempt, 5));
    }

    private static void pause(long millis, AbortSignal abort) throws ProviderException {
        long end = System.currentTimeMillis() + millis;
        try {
            while (System.currentTimeMillis() < end) {
                if (abort.isAborted()) {
                    throw new ProviderException("the call was aborted", false);
                }
                Thread.sleep(Math.min(100L, Math.max(1L, end - System.currentTimeMillis())));
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ProviderException("the call was aborted", false);
        }
    }

    static String statusMessage(int status, String detail, int retries, boolean keySent) {
        String suffix = detail == null || detail.trim().isEmpty() ? "" : ": " + detail;
        if ((status == 401 || status == 403) && !keySent) {
            return "no API key is set for the OpenAI-compatible API (HTTP " + status + "). Enter it under "
                    + "Settings -> AI models -> Model provider" + suffix;
        }
        if (status == 401 || status == 403) {
            return "the OpenAI-compatible API refused the API key (HTTP " + status + ")" + suffix;
        }
        if (status == 404) {
            return "HTTP 404 from the OpenAI-compatible API" + suffix + " (check the base URL, it usually ends "
                    + "with /v1, and the model name)";
        }
        if (status == 429) {
            return "the OpenAI-compatible API is rate limiting (HTTP 429" + (retries > 0 ? ", after " + retries
                    + " retries" : "") + ")" + suffix;
        }
        return "HTTP " + status + " from the OpenAI-compatible API" + (retries > 0 ? " (after " + retries
                + " retries)" : "") + suffix;
    }

    private static String readError(HttpURLConnection connection, String key) {
        String body = readErrorBody(connection);
        String message = refusalText(body);
        return shorten(redact(message.isEmpty() ? body : message, key));
    }

    private static String readErrorBody(HttpURLConnection connection) {
        try {
            InputStream error = connection.getErrorStream();
            return error == null ? "" : readAll(error);
        } catch (IOException unreadable) {
            return "";
        }
    }

    /**
     * The error's own message ({@link OpenAiResponseReader#errorMessage}), or a body that is plain text; "" for a
     * JSON body without a message, which may echo the whole request and so must not be read for field names.
     */
    static String refusalText(String body) {
        String message = OpenAiResponseReader.errorMessage(body);
        if (message != null) {
            return message;
        }
        String text = body == null ? "" : body.trim();
        return text.startsWith("{") || text.startsWith("[") ? "" : text;
    }

    static List<String> modelIds(String json) throws ProviderException {
        try {
            JsonElement root = JsonParser.parseString(json);
            JsonArray data = root.isJsonObject() && root.getAsJsonObject().get("data") != null
                    && root.getAsJsonObject().get("data").isJsonArray()
                    ? root.getAsJsonObject().getAsJsonArray("data")
                    : root.isJsonArray() ? root.getAsJsonArray() : null;
            if (data == null) {
                throw new ProviderException("the model list of the OpenAI-compatible API has no data array", false);
            }
            List<String> ids = new ArrayList<String>();
            for (JsonElement entry : data) {
                if (entry.isJsonObject()) {
                    JsonObject model = entry.getAsJsonObject();
                    JsonElement id = model.get("id");
                    if (id != null && id.isJsonPrimitive() && !id.getAsString().trim().isEmpty()
                            && !ids.contains(id.getAsString().trim())) {
                        ids.add(id.getAsString().trim());
                    }
                }
            }
            Collections.sort(ids);
            return ids;
        } catch (JsonParseException | IllegalStateException invalid) {
            throw new ProviderException("the model list of the OpenAI-compatible API is not valid JSON", false);
        }
    }

    static String join(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    static String redact(String text, String key) {
        if (text == null) {
            return null;
        }
        String redacted = key == null || key.trim().isEmpty() ? text : text.replace(key.trim(), "***");
        return MASKED_SECRET.matcher(redacted).replaceAll("***"); // a partly masked echo shows no part of it
    }

    static String shorten(String text) {
        String line = text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
        return line.length() <= MAX_DETAIL_LENGTH ? line : line.substring(0, MAX_DETAIL_LENGTH) + "...";
    }

    private static String detailOf(IOException failed, String key) {
        String message = failed.getMessage();
        return message == null || message.trim().isEmpty() ? "" : " (" + shorten(redact(message, key)) + ")";
    }

    private static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (IOException malformed) {
            return "the configured base URL";
        }
    }

    static boolean isConnectFailure(IOException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof UnknownHostException
                    || cause instanceof NoRouteToHostException) {
                return true;
            }
            if (cause instanceof SocketTimeoutException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains("connect")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    private static String readAll(InputStream input) throws IOException {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            input.close();
        }
    }

    private static void clear(char[] key) {
        if (key != null) {
            Arrays.fill(key, '\0');
        }
    }
}
