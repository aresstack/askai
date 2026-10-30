package com.aresstack.askai.java8.modelport;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.TokenUsage;
import com.aresstack.askai.agent.model.port.ToolCall;
import com.aresstack.askai.agent.model.port.ToolDefinition;
import com.aresstack.askai.agent.model.port.resource.ModelCapacity;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.agent.model.port.resource.ModelHandle;
import com.aresstack.askai.agent.model.port.resource.ResidentModel;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotBinding;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;
import com.aresstack.askai.java8.client.AskAiOllamaClient;
import com.aresstack.askai.java8.client.OllamaChatCompletion;
import com.aresstack.askai.java8.client.OllamaChatOptions;
import com.aresstack.askai.java8.client.OllamaChatStreamListener;
import com.aresstack.askai.java8.client.OllamaChatTurn;
import com.aresstack.askai.java8.client.OllamaRequestException;
import com.aresstack.askai.java8.client.OllamaToolCall;
import com.fasterxml.jackson.core.type.TypeReference;
import io.github.ollama4j.utils.Utils;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The platform's MODEL PORT v2 on ollama4j (ADR 0001, 3.2 and 4.5): the one model access of in-process
 * plugins. Plugins name a {@link ModelSlot}; this class resolves it to a model on an endpoint of the pool,
 * admits the call through the {@link ModelResourceScheduler} (capacity per model and endpoint, grouping by
 * model, model switch as a deliberate step), and runs it through {@link AskAiOllamaClient}, the JVM's only
 * Ollama client. Residency comes from {@code GET /api/ps} and is advisory; the active model is kept warm with
 * the {@code keep_alive} setting on every call.
 *
 * <p>Endpoint pool today: {@code ollama} (the configured Ollama base URL) and {@code local-runtime} (AskAI's
 * local model runtime, serving {@code local/...} models). Every limit is a setting
 * ({@link ModelPortSettings}); per-call settings are read at call time, so a save in the settings panel
 * applies to the next call.</p>
 *
 * <p>Threading: no method blocks the caller. Calls run on a daemon pool; listener callbacks arrive there.
 * Exactly one terminal callback per call, after all deltas; a cancel or a time-out delivers its terminal
 * callback at once and aborts the stream at the provider (the next streamed chunk ends the connection,
 * which frees Ollama's slot).</p>
 */
public final class OllamaModelPort implements ModelPort, AutoCloseable {

    /** The endpoint pool. Ids are stable settings keys; base URLs may change (a restarted local runtime). */
    public interface EndpointDirectory {
        List<String> endpointIds();

        ModelEndpoint.Kind kind(String endpointId);

        String label(String endpointId);

        /** The base URL, starting the endpoint if it is AskAI's own runtime; throws when unavailable. */
        String baseUrl(String endpointId) throws IOException;

        /** The base URL if the endpoint is running now, else null; never starts anything. */
        String baseUrlIfRunning(String endpointId);
    }

    /** Resolves slots to models on endpoints from the central configuration. */
    public interface SlotDirectory {
        /** The model name filling the slot, or "" when nothing is configured (no main model either). */
        String modelFor(ModelSlot slot);

        /** The endpoint serving a model. */
        String endpointFor(String model);

        /** The slots the configuration knows (for the resource view). */
        List<ModelSlot> knownSlots();

        /** The user's default per-call timeout (the main-model timeout setting). */
        Duration defaultTimeout();
    }

    /** Creates the Ollama client for a base URL (a seam for tests). */
    public interface ClientFactory {
        AskAiOllamaClient create(String baseUrl);
    }

    private final EndpointDirectory endpoints;
    private final SlotDirectory slots;
    private final ModelPortSettingsRepository settingsRepository;
    private final ClientFactory clientFactory;
    private final Map<String, AskAiOllamaClient> clients = new ConcurrentHashMap<String, AskAiOllamaClient>();
    private final ResidencyMonitor residency;
    private final ModelResourceScheduler scheduler;
    private final ExecutorService calls;
    private final ScheduledExecutorService timers;
    private final Map<String, Boolean> reachability = new ConcurrentHashMap<String, Boolean>();
    private final AtomicInteger reservationIds = new AtomicInteger();
    private volatile boolean closed;

    public OllamaModelPort(EndpointDirectory endpoints, SlotDirectory slots,
                           ModelPortSettingsRepository settingsRepository, ClientFactory clientFactory) {
        if (endpoints == null || slots == null || settingsRepository == null) {
            throw new IllegalArgumentException("endpoints, slots and settings must not be null");
        }
        this.endpoints = endpoints;
        this.slots = slots;
        this.settingsRepository = settingsRepository;
        this.clientFactory = clientFactory == null ? new ClientFactory() {
            public AskAiOllamaClient create(String baseUrl) {
                return new AskAiOllamaClient(baseUrl);
            }
        } : clientFactory;
        this.residency = new ResidencyMonitor(new ResidencyMonitor.Source() {
            public List<String> endpointIds() {
                return OllamaModelPort.this.endpoints.endpointIds();
            }

            public List<com.aresstack.askai.java8.client.OllamaResidentModel> loaded(String endpointId)
                    throws OllamaRequestException {
                String url = OllamaModelPort.this.endpoints.baseUrlIfRunning(endpointId);
                if (url == null) {
                    return null;
                }
                return client(url).getResidentModels();
            }
        });
        this.scheduler = new ModelResourceScheduler(new ModelResourceScheduler.Policy() {
            public int capacity(String endpointId, String model) {
                return settings().capacityOf(model);
            }

            public boolean mayCoexist(String endpointId, List<String> loaded, String next) {
                long budget = settings().memoryBudgetBytes(endpointId);
                if (budget < 0) {
                    return false; // no budget: no memory planning, so a switch is a deliberate step
                }
                long total = residency.lastKnownSize(endpointId, next);
                if (total < 0) {
                    return false;
                }
                for (String model : loaded) {
                    long size = residency.lastKnownSize(endpointId, model);
                    if (size < 0) {
                        return false; // an unknown footprint never counts as fitting
                    }
                    total += size;
                }
                return total <= budget; // the combined footprint, not each pair, must fit
            }
        }, new ModelResourceScheduler.SwitchListener() {
            public void onModelSwitch(String endpointId, String fromModel, String toModel) {
                System.err.println("[model-port] model switch on " + endpointId + ": " + fromModel + " -> "
                        + toModel);
            }
        });
        this.calls = Executors.newCachedThreadPool(daemon("model-port-call"));
        this.timers = Executors.newSingleThreadScheduledExecutor(daemon("model-port-timer"));
    }

    // ------------------------------------------------------------------ ModelPort

    @Override
    public CompletableFuture<SlotReservation> reserve(SlotRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        final ModelSlot slot = request.getSlot();
        CompletableFuture<SlotReservation> result = new CompletableFuture<SlotReservation>();
        if (closed) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.PORT_CLOSED, "the model port is closed"));
            return result;
        }
        final String model = slots.modelFor(slot);
        if (model == null || model.trim().isEmpty()) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.NO_BINDING,
                    "no model is configured for this slot and no main model is selected"));
            return result;
        }
        final String endpointId = slots.endpointFor(model);
        ModelPortSettings settings = settings();
        // Memory planning needs fresh loaded sizes; the probe runs in the background, never here.
        residency.refreshIfOlderThan(Duration.ofSeconds(settings.getResidencyRefreshSeconds()));
        int defaultContext = settings.slotContextTokens(slot.getName());
        final int context = request.getContextTokens() > 0 ? request.getContextTokens() : defaultContext;
        final CompletableFuture<ModelResourceScheduler.Grant> grant =
                scheduler.acquire(slot, endpointId, model, request.getMaxWait());
        grant.whenComplete((granted, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure);
                return;
            }
            Reservation reservation = new Reservation("r" + reservationIds.incrementAndGet(), slot, endpointId,
                    model, context, granted);
            if (!result.complete(reservation)) {
                reservation.close(); // the caller withdrew concurrently: never leak the place
            }
        });
        result.whenComplete((reservation, failure) -> {
            if (result.isCancelled()) {
                grant.cancel(false);
            }
        });
        return result;
    }

    @Override
    public ModelCall chat(SlotReservation reservation, ChatRequest request, ChatListener listener) {
        Call call = new Call(request, listener);
        String invalid = validate(reservation, request);
        if (invalid != null) {
            call.failLater(invalid.startsWith("context")
                    ? ModelFailure.Kind.CONTEXT_TOO_LARGE : ModelFailure.Kind.RESERVATION_INVALID, invalid);
            return call;
        }
        Reservation own = (Reservation) reservation;
        if (!own.running.compareAndSet(null, call)) {
            call.failLater(ModelFailure.Kind.RESERVATION_INVALID, "another call is running under this reservation");
            return call;
        }
        call.start(own, false);
        return call;
    }

    @Override
    public ModelCall chat(final ChatRequest request, final ChatListener listener) {
        final Call call = new Call(request, listener);
        if (closed) {
            call.failLater(ModelFailure.Kind.PORT_CLOSED, "the model port is closed");
            return call;
        }
        CompletableFuture<SlotReservation> pending = reserve(new SlotRequest(request.getSlot(),
                request.getContextTokens(), Duration.ofSeconds(settings().getImplicitWaitSeconds()), "implicit"));
        call.pendingReservation = pending;
        pending.whenComplete((reservation, failure) -> {
            if (failure != null) {
                Throwable cause = failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure;
                if (pending.isCancelled()) {
                    return; // the call was cancelled while waiting; its terminal callback is already out
                }
                call.fail(kindOf(cause), cause.getMessage());
                return;
            }
            Reservation own = (Reservation) reservation;
            if (call.isDone()) {
                own.close();
                return;
            }
            own.running.set(call);
            call.start(own, true);
        });
        return call;
    }

    @Override
    public ResourceSnapshot resources() {
        ModelPortSettings settings = settings();
        residency.refreshIfOlderThan(Duration.ofSeconds(settings.getResidencyRefreshSeconds()));
        List<ModelEndpoint> pool = new ArrayList<ModelEndpoint>();
        for (String id : endpoints.endpointIds()) {
            Boolean reachable = reachability.get(id);
            boolean known = reachable != null ? reachable : endpoints.baseUrlIfRunning(id) != null;
            pool.add(new ModelEndpoint(id, endpoints.label(id), endpoints.kind(id),
                    settings.memoryBudgetBytes(id), known));
        }
        List<SlotBinding> bindings = new ArrayList<SlotBinding>();
        Map<String, List<String>> models = new LinkedHashMap<String, List<String>>();
        for (ModelSlot slot : slots.knownSlots()) {
            String model = slots.modelFor(slot);
            if (model == null || model.isEmpty()) {
                continue;
            }
            String endpointId = slots.endpointFor(model);
            bindings.add(new SlotBinding(slot, endpointId, handle(endpointId, model),
                    settings.slotContextTokens(slot.getName())));
            add(models, endpointId, model);
        }
        for (Map.Entry<String, List<String>> tracked : scheduler.trackedModels().entrySet()) {
            for (String model : tracked.getValue()) {
                add(models, tracked.getKey(), model);
            }
        }
        List<ModelCapacity> capacities = new ArrayList<ModelCapacity>();
        for (Map.Entry<String, List<String>> entry : models.entrySet()) {
            for (String model : entry.getValue()) {
                ModelResourceScheduler.Usage usage = scheduler.usage(entry.getKey(), model);
                capacities.add(new ModelCapacity(entry.getKey(), handle(entry.getKey(), model),
                        settings.capacityOf(model), usage.active, usage.waiting));
            }
        }
        List<ResidentModel> residents = new ArrayList<ResidentModel>();
        ResidencyMonitor.View view = residency.view();
        for (ResidencyMonitor.Loaded loaded : view.loaded) {
            residents.add(new ResidentModel(loaded.endpointId, handle(loaded.endpointId, loaded.model),
                    loaded.sizeBytes, loaded.vramBytes, loaded.expiresAt, loaded.contextLength));
        }
        return new ResourceSnapshot(Instant.now(), pool, bindings, capacities, residents, view.known);
    }

    /** Stops accepting calls, fails waiting reservations, cancels running calls. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.close();
        calls.shutdownNow();
        timers.shutdownNow();
        residency.close();
    }

    // ------------------------------------------------------------------ internals

    /** The opaque handle of a model on an endpoint: equal for equal pairs, no model name inside. */
    static ModelHandle handle(String endpointId, String model) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((endpointId + "\n" + model).getBytes(StandardCharsets.UTF_8));
            StringBuilder token = new StringBuilder("m-");
            for (int i = 0; i < 6; i++) {
                token.append(String.format("%02x", digest[i] & 0xff));
            }
            return ModelHandle.of(token.toString());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private ModelPortSettings settings() {
        return settingsRepository.load();
    }

    private AskAiOllamaClient client(String baseUrl) {
        AskAiOllamaClient client = clients.get(baseUrl);
        if (client == null) {
            client = clientFactory.create(baseUrl);
            AskAiOllamaClient raced = clients.putIfAbsent(baseUrl, client);
            if (raced != null) {
                client = raced;
            }
        }
        return client;
    }

    private String validate(SlotReservation reservation, ChatRequest request) {
        if (!(reservation instanceof Reservation) || ((Reservation) reservation).owner() != this) {
            return "the reservation was not granted by this port";
        }
        if (!reservation.isActive()) {
            return "the reservation is closed or revoked";
        }
        if (!reservation.getSlot().equals(request.getSlot())) {
            return "the reservation is for slot " + reservation.getSlot() + ", the request for "
                    + request.getSlot();
        }
        if (request.getContextTokens() > 0 && reservation.getContextTokens() > 0
                && request.getContextTokens() > reservation.getContextTokens()) {
            return "context of " + request.getContextTokens() + " tokens exceeds the reservation's "
                    + reservation.getContextTokens();
        }
        return null;
    }

    private static ModelFailure.Kind kindOf(Throwable failure) {
        if (failure instanceof SlotUnavailableException) {
            switch (((SlotUnavailableException) failure).getReason()) {
                case NO_BINDING:
                    return ModelFailure.Kind.NO_BINDING;
                case ENDPOINT_UNAVAILABLE:
                    return ModelFailure.Kind.ENDPOINT_UNAVAILABLE;
                case CONTEXT_TOO_LARGE:
                    return ModelFailure.Kind.CONTEXT_TOO_LARGE;
                case WAIT_TIMEOUT:
                    return ModelFailure.Kind.CAPACITY_TIMEOUT;
                default:
                    return ModelFailure.Kind.PORT_CLOSED;
            }
        }
        return ModelFailure.Kind.PROVIDER_ERROR;
    }

    private static void add(Map<String, List<String>> models, String endpointId, String model) {
        List<String> list = models.get(endpointId);
        if (list == null) {
            list = new ArrayList<String>();
            models.put(endpointId, list);
        }
        if (!list.contains(model)) {
            list.add(model);
        }
    }

    private static ThreadFactory daemon(final String name) {
        return new ThreadFactory() {
            private final AtomicInteger count = new AtomicInteger();

            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, name + "-" + count.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    /** The wire value of {@code think}: null = omit. */
    static Object thinkValue(com.aresstack.askai.agent.model.port.ThinkingMode mode) {
        switch (mode) {
            case OFF:
                return Boolean.FALSE;
            case ON:
                return Boolean.TRUE;
            case LOW:
                return "low";
            case MEDIUM:
                return "medium";
            case HIGH:
                return "high";
            default:
                return null;
        }
    }

    static List<OllamaChatTurn> turns(List<ModelMessage> messages) {
        List<OllamaChatTurn> turns = new ArrayList<OllamaChatTurn>();
        for (ModelMessage message : messages) {
            switch (message.getRole()) {
                case SYSTEM:
                    turns.add(OllamaChatTurn.system(message.getContent()));
                    break;
                case ASSISTANT:
                    List<OllamaToolCall> calls = new ArrayList<OllamaToolCall>();
                    for (ToolCall call : message.getToolCalls()) {
                        calls.add(new OllamaToolCall(call.getId(), call.getName(), arguments(call)));
                    }
                    turns.add(OllamaChatTurn.assistant(message.getThinking(), message.getContent(), calls));
                    break;
                case TOOL:
                    turns.add(OllamaChatTurn.tool(message.getToolName(), message.getContent()));
                    break;
                default:
                    turns.add(OllamaChatTurn.user(message.getContent()));
            }
        }
        return turns;
    }

    /** A replayed call's arguments; invalid JSON from a small model is replayed as an empty object. */
    private static Map<String, Object> arguments(ToolCall call) {
        try {
            Map<String, Object> parsed = Utils.getObjectMapper().readValue(call.getArgumentsJson(),
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    });
            return parsed == null ? Collections.<String, Object>emptyMap() : parsed;
        } catch (IOException invalid) {
            return Collections.emptyMap();
        }
    }

    static String argumentsJson(Map<String, Object> arguments) {
        try {
            return Utils.getObjectMapper().writeValueAsString(
                    arguments == null ? Collections.<String, Object>emptyMap() : arguments);
        } catch (IOException unexpected) {
            return "{}";
        }
    }

    static ChatResult.FinishReason finishReason(String doneReason, boolean toolCalls) {
        if (toolCalls) {
            return ChatResult.FinishReason.TOOL_CALLS;
        }
        if ("stop".equalsIgnoreCase(doneReason)) {
            return ChatResult.FinishReason.STOP;
        }
        if ("length".equalsIgnoreCase(doneReason)) {
            return ChatResult.FinishReason.LENGTH;
        }
        return doneReason == null || doneReason.isEmpty() ? ChatResult.FinishReason.STOP
                : ChatResult.FinishReason.OTHER;
    }

    /** One granted place. Pins endpoint and model for its lifetime. */
    private final class Reservation implements SlotReservation {
        private final String id;
        private final ModelSlot slot;
        private final String endpointId;
        private final String model;
        private final int contextTokens;
        private final ModelResourceScheduler.Grant grant;
        private final AtomicBoolean active = new AtomicBoolean(true);
        final AtomicReference<Call> running = new AtomicReference<Call>();
        private Call onWire; // the call whose request is at the endpoint, or null
        private boolean released;

        Reservation(String id, ModelSlot slot, String endpointId, String model, int contextTokens,
                    ModelResourceScheduler.Grant grant) {
            this.id = id;
            this.slot = slot;
            this.endpointId = endpointId;
            this.model = model;
            this.contextTokens = contextTokens;
            this.grant = grant;
        }

        OllamaModelPort owner() {
            return OllamaModelPort.this;
        }

        public String getId() {
            return id;
        }

        public ModelSlot getSlot() {
            return slot;
        }

        public String getEndpointId() {
            return endpointId;
        }

        public ModelHandle getModel() {
            return handle(endpointId, model);
        }

        public int getContextTokens() {
            return contextTokens;
        }

        public boolean isActive() {
            return active.get() && !closed;
        }

        public void close() {
            if (active.compareAndSet(true, false)) {
                Call call = running.getAndSet(null);
                if (call != null) {
                    call.cancel();
                }
                releaseWhenOffTheWire();
            }
        }

        /**
         * Takes the wire for one call: waits while an earlier (aborted) call of this reservation is still
         * streaming, so a reservation never has two requests at the endpoint. False when the reservation was
         * closed meanwhile or the waiting thread was interrupted.
         */
        synchronized boolean enterWire(Call call) {
            try {
                while (onWire != null) {
                    wait();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (!active.get()) {
                return false;
            }
            onWire = call;
            return true;
        }

        /** The transport of {@code call} has really stopped. Idempotent; a no-op for a call off the wire. */
        synchronized void leaveWire(Call call) {
            if (onWire == call) {
                onWire = null;
                notifyAll();
                releaseWhenOffTheWire();
            }
        }

        /**
         * A cancel or time-out reports at once, but the scheduler place is handed on only once the stream has
         * really stopped: otherwise the next call would reach the endpoint while the aborted generation (for
         * example a slow prefill that has not sent its first chunk) is still running, breaking capacity 1.
         */
        private synchronized void releaseWhenOffTheWire() {
            if (!active.get() && onWire == null && !released) {
                released = true;
                grant.release();
            }
        }
    }

    /** One chat call: the handle the caller gets, and its execution. */
    private final class Call implements ModelCall {
        private final ChatRequest request;
        private final ChatListener listener;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        volatile CompletableFuture<SlotReservation> pendingReservation;
        private volatile Future<?> execution;
        private volatile ScheduledFuture<?> deadline;
        private volatile Reservation reservation;
        private volatile boolean implicit;

        Call(ChatRequest request, ChatListener listener) {
            if (request == null || listener == null) {
                throw new IllegalArgumentException("request and listener must not be null");
            }
            this.request = request;
            this.listener = listener;
        }

        void start(final Reservation reservation, boolean implicit) {
            this.reservation = reservation;
            this.implicit = implicit;
            Duration timeout = request.getTimeout() != null ? request.getTimeout() : slots.defaultTimeout();
            if (timeout != null && !timeout.isZero() && !timeout.isNegative()) {
                final Duration limit = timeout;
                deadline = timers.schedule(new Runnable() {
                    public void run() {
                        abort(ModelFailure.Kind.TIMEOUT, "the call exceeded its time limit of "
                                + limit.toMillis() + " ms");
                    }
                }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            }
            try {
                execution = calls.submit(new Runnable() {
                    public void run() {
                        execute(reservation);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                fail(ModelFailure.Kind.PORT_CLOSED, "the model port is closed");
            }
        }

        private void execute(Reservation reservation) {
            if (terminal.get()) {
                finish(); // cancelled or timed out before it reached the wire
                return;
            }
            long started = System.nanoTime();
            String baseUrl;
            try {
                baseUrl = endpoints.baseUrl(reservation.endpointId);
            } catch (IOException unavailable) {
                reachability.put(reservation.endpointId, Boolean.FALSE);
                fail(ModelFailure.Kind.ENDPOINT_UNAVAILABLE, "endpoint " + reservation.endpointId
                        + " is not available: " + unavailable.getMessage());
                return;
            }
            Map<String, Object> options = new LinkedHashMap<String, Object>();
            int context = request.getContextTokens() > 0 ? request.getContextTokens() : reservation.contextTokens;
            if (context > 0) {
                options.put("num_ctx", context);
            }
            if (request.getTemperature() != null) {
                options.put("temperature", request.getTemperature());
            }
            if (request.getMaxOutputTokens() > 0) {
                options.put("num_predict", request.getMaxOutputTokens());
            }
            List<OllamaChatOptions.Tool> tools = new ArrayList<OllamaChatOptions.Tool>();
            for (ToolDefinition tool : request.getTools()) {
                tools.add(new OllamaChatOptions.Tool(tool.getName(), tool.getDescription(),
                        tool.getParametersSchemaJson()));
            }
            OutputFormat format = request.getOutputFormat();
            OllamaChatOptions extras = new OllamaChatOptions(tools,
                    format.getKind() == OutputFormat.Kind.JSON_SCHEMA ? format.getSchemaJson() : null,
                    options, format.getKind() == OutputFormat.Kind.JSON, () -> aborted.get());
            if (!reservation.enterWire(this)) {
                fail(ModelFailure.Kind.RESERVATION_INVALID, "the reservation was closed before the call started");
                finish();
                return;
            }
            if (terminal.get()) {
                reservation.leaveWire(this); // aborted while waiting for the wire
                finish();
                return;
            }
            try {
                OllamaChatCompletion completion = client(baseUrl).streamChat(reservation.model,
                        turns(request.getMessages()), settings().getKeepAlive(), thinkValue(request.getThinking()),
                        extras, new OllamaChatStreamListener() {
                            public void onThinkingDelta(String delta) {
                                if (!terminal.get()) {
                                    listener.onThinkingDelta(delta);
                                }
                            }

                            public void onContent(String content) {
                                if (!terminal.get()) {
                                    listener.onContentDelta(content);
                                }
                            }

                            public void onStatus(String status) {
                            }

                            public void onComplete(OllamaChatCompletion completion) {
                            }
                        });
                reservation.leaveWire(this); // the stream has ended: free the wire before any callback
                reachability.put(reservation.endpointId, Boolean.TRUE);
                complete(completion, reservation, Duration.ofNanos(System.nanoTime() - started));
            } catch (OllamaRequestException failure) {
                reservation.leaveWire(this);
                if (aborted.get()) {
                    return; // cancel or time-out already delivered the terminal callback
                }
                if (isConnectFailure(failure)) {
                    reachability.put(reservation.endpointId, Boolean.FALSE);
                    fail(ModelFailure.Kind.ENDPOINT_UNAVAILABLE, failure.getMessage());
                } else {
                    reachability.put(reservation.endpointId, Boolean.TRUE);
                    fail(ModelFailure.Kind.PROVIDER_ERROR, failure.getMessage());
                }
            } catch (RuntimeException unexpected) {
                reservation.leaveWire(this);
                if (!aborted.get()) {
                    fail(ModelFailure.Kind.PROVIDER_ERROR, String.valueOf(unexpected.getMessage()));
                }
            } finally {
                reservation.leaveWire(this); // idempotent; covers an Error thrown out of the stream
                finish();
            }
        }

        private void complete(OllamaChatCompletion completion, Reservation reservation, Duration duration) {
            List<ToolCall> toolCalls = new ArrayList<ToolCall>();
            for (OllamaToolCall call : completion.getToolCalls()) {
                if (call.getName() != null && !call.getName().trim().isEmpty()) {
                    toolCalls.add(new ToolCall(call.getId(), call.getName(), argumentsJson(call.getArguments())));
                }
            }
            if (completion.getContent().trim().isEmpty() && toolCalls.isEmpty()) {
                // "The model spent everything on reasoning" must never look like a success.
                fail(ModelFailure.Kind.INVALID_RESPONSE, completion.getThinking().trim().isEmpty()
                        ? "the model answered with no text content"
                        : "the model answered with reasoning only, no answer ("
                                + completion.getThinking().trim().length() + " reasoning chars)");
                return;
            }
            if (terminal.compareAndSet(false, true)) {
                finish(); // free the reservation BEFORE the callback: the caller may chain the next call
                listener.onCompleted(new ChatResult(completion.getContent(), completion.getThinking(), toolCalls,
                        finishReason(completion.getDoneReason(), !toolCalls.isEmpty()),
                        new TokenUsage((int) Math.min(Integer.MAX_VALUE, completion.getPromptEvalCount()),
                                completion.getEvalCount() > 0 ? (int) Math.min(Integer.MAX_VALUE,
                                        completion.getEvalCount()) : -1),
                        reservation.endpointId, duration));
            }
        }

        void fail(ModelFailure.Kind kind, String detail) {
            if (terminal.compareAndSet(false, true)) {
                finish(); // free the reservation BEFORE the callback: the caller may chain the next call
                listener.onFailed(new ModelFailure(kind, detail));
            }
            finish();
        }

        /** Validation failures are delivered on a platform thread too, never on the caller's. */
        void failLater(final ModelFailure.Kind kind, final String detail) {
            try {
                calls.submit(new Runnable() {
                    public void run() {
                        fail(kind, detail);
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                fail(kind, detail);
            }
        }

        private void abort(ModelFailure.Kind kind, String detail) {
            if (terminal.get()) {
                return;
            }
            aborted.set(true);
            fail(kind, detail);
            Future<?> running = execution;
            if (running != null) {
                running.cancel(true); // interrupts the blocked read; the next chunk ends the stream anyway
            }
        }

        private void finish() {
            ScheduledFuture<?> timer = deadline;
            if (timer != null) {
                timer.cancel(false);
            }
            Reservation held = reservation;
            if (held != null) {
                held.running.compareAndSet(this, null);
                if (implicit) {
                    held.close();
                }
            }
        }

        @Override
        public void cancel() {
            CompletableFuture<SlotReservation> pending = pendingReservation;
            if (pending != null && !pending.isDone()) {
                pending.cancel(false);
            }
            abort(ModelFailure.Kind.CANCELLED, "cancelled by the caller");
        }

        @Override
        public boolean isDone() {
            return terminal.get();
        }
    }

    private static boolean isConnectFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }
}
