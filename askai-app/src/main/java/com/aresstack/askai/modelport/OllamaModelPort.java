package com.aresstack.askai.modelport;

import com.aresstack.askai.agent.model.port.ChatListener;
import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelCall;
import com.aresstack.askai.agent.model.port.ModelFailure;
import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.ModelCapacity;
import com.aresstack.askai.agent.model.port.resource.ModelEndpoint;
import com.aresstack.askai.agent.model.port.resource.ModelHandle;
import com.aresstack.askai.agent.model.port.resource.ResidentModel;
import com.aresstack.askai.agent.model.port.resource.ResourceSnapshot;
import com.aresstack.askai.agent.model.port.resource.SlotBinding;
import com.aresstack.askai.agent.model.port.resource.SlotRequest;
import com.aresstack.askai.agent.model.port.resource.SlotReservation;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;
import com.aresstack.askai.client.AskAiOllamaClient;
import com.aresstack.askai.modelport.provider.AbortSignal;
import com.aresstack.askai.modelport.provider.ModelProvider;
import com.aresstack.askai.modelport.provider.OllamaModelProvider;
import com.aresstack.askai.modelport.provider.ProviderCall;
import com.aresstack.askai.modelport.provider.ProviderCompletion;
import com.aresstack.askai.modelport.provider.ProviderException;
import com.aresstack.askai.modelport.provider.ProviderStream;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The platform's MODEL PORT v2 (ADR 0001, 3.2 and 4.5): the one model access of in-process plugins. Plugins name
 * a {@link ModelSlot}; this class resolves it to a model on an endpoint of the pool, admits the call through the
 * {@link ModelResourceScheduler} (capacity per model and endpoint, grouping by model, model switch as a
 * deliberate step), and runs it through the endpoint's {@link ModelProvider}: {@link OllamaModelProvider} on
 * {@link AskAiOllamaClient} (the JVM's only Ollama client) for Ollama and AskAI's local runtime, or an
 * {@link com.aresstack.askai.modelport.provider.OpenAiCompatibleModelProvider OpenAI-compatible API}. The class
 * keeps its historical name; nothing in it is Ollama-specific any more.
 *
 * <p>The provider decides what kind of resource an endpoint is. Ollama shares one GPU: residency comes from
 * {@code GET /api/ps} and is advisory, the active model is kept warm with the {@code keep_alive} setting on every
 * call, and two models hold places at the same time only when the memory planning allows it. An OpenAI-compatible
 * API serves many models side by side: different models run at the same time, with the API's own default
 * capacity, and nothing is loaded or unloaded.</p>
 *
 * <p>Endpoint pool: {@code ollama} (the configured Ollama base URL) or {@code openai-compatible} (the configured
 * API), and {@code local-runtime} (AskAI's local model runtime, serving {@code local/...} models). Every limit is
 * a setting ({@link ModelPortSettings}, {@link ModelProviderSettings}); per-call settings are read at call time, so
 * a save in the settings panel applies to the next call.</p>
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

        /**
         * The wire of an endpoint; null = the port's {@link OllamaModelProvider} (Ollama and AskAI's local runtime
         * both speak Ollama's API).
         */
        default ModelProvider provider(String endpointId) {
            return null;
        }
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

        /**
         * True when the port checks a resolved model against the endpoint's installed models before a call
         * (plan 5.1); the productive directory does, test directories with fake clients usually not.
         */
        default boolean checksInstalledModels() {
            return false;
        }

        /** The message of a slot without a model: where the user sets one. */
        default String noModelMessage(ModelSlot slot) {
            return OllamaModelPort.noModelMessage(slot);
        }
    }

    /** Creates the Ollama client for a base URL (a seam for tests). */
    public interface ClientFactory {
        AskAiOllamaClient create(String baseUrl);
    }

    private final EndpointDirectory endpoints;
    private final SlotDirectory slots;
    private final ModelPortSettingsRepository settingsRepository;
    private final OllamaModelProvider ollama;
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
        this.ollama = new OllamaModelProvider(clientFactory == null ? null : clientFactory::create);
        this.residency = new ResidencyMonitor(new ResidencyMonitor.Source() {
            public List<String> endpointIds() {
                return OllamaModelPort.this.endpoints.endpointIds();
            }

            public List<com.aresstack.askai.client.OllamaResidentModel> loaded(String endpointId)
                    throws ProviderException {
                String url = OllamaModelPort.this.endpoints.baseUrlIfRunning(endpointId);
                if (url == null) {
                    return null;
                }
                return provider(endpointId).residentModels(url); // null: the provider has no residency
            }
        });
        this.scheduler = new ModelResourceScheduler(new ModelResourceScheduler.Policy() {
            public int capacity(String endpointId, String model) {
                return capacityOf(endpointId, model, settings());
            }

            public boolean mayCoexist(String endpointId, List<String> loaded, String next) {
                if (!provider(endpointId).sharesMemory()) {
                    return true; // a service that serves many models side by side: no switch, no unload
                }
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
                    SlotUnavailableException.Reason.NO_BINDING, slots.noModelMessage(slot)));
            return result;
        }
        final String endpointId = slots.endpointFor(model);
        if (needsInstalledProbe(endpointId, model)) {
            // the installed list is probed off the calling thread (bounded), then admission continues
            final CompletableFuture<SlotReservation> outer = new CompletableFuture<SlotReservation>();
            probeInstalled(endpointId).whenComplete((ignored, failure) -> forward(reserve(request), outer));
            return outer;
        }
        String unusable = preflight(slot, endpointId, model);
        if (unusable != null) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.NO_BINDING, unusable));
            return result;
        }
        ModelPortSettings settings = settings();
        // Memory planning needs fresh loaded sizes; the probe runs in the background, never here.
        residency.refreshIfOlderThan(Duration.ofSeconds(settings.getResidencyRefreshSeconds()));
        // The context is the model's (plan 4.1, rule 1); a request's value is only the minimum it needs.
        final int context = contextOf(endpointId, model, settings);
        if (request.getContextTokens() > context) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.CONTEXT_TOO_LARGE, contextTooLarge(model,
                            request.getContextTokens(), context)));
            return result;
        }
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
                    contextOf(endpointId, model, settings), model));
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
                        capacityOf(entry.getKey(), model, settings), usage.active, usage.waiting));
            }
        }
        List<ResidentModel> residents = new ArrayList<ResidentModel>();
        ResidencyMonitor.View view = residency.view();
        for (ResidencyMonitor.Loaded loaded : view.loaded) {
            residents.add(new ResidentModel(loaded.endpointId, handle(loaded.endpointId, loaded.model),
                    loaded.sizeBytes, loaded.vramBytes, loaded.expiresAt, loaded.contextLength));
        }
        // A service that serves many models side by side keeps every model ready: a bound model there counts as
        // resident (sizes unreported), so nothing announces a load that never happens.
        java.util.Set<ModelHandle> ready = new java.util.HashSet<ModelHandle>();
        for (SlotBinding binding : bindings) {
            if (!provider(binding.getEndpointId()).sharesMemory() && ready.add(binding.getModel())) {
                residents.add(new ResidentModel(binding.getEndpointId(), binding.getModel(), -1, -1, null, -1));
            }
        }
        return new ResourceSnapshot(Instant.now(), pool, bindings, capacities, residents, view.known);
    }

    @Override
    public CompletableFuture<Void> warm(final ModelSlot slot) {
        final CompletableFuture<Void> result = new CompletableFuture<Void>();
        if (slot == null) {
            throw new IllegalArgumentException("slot must not be null");
        }
        if (closed) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.PORT_CLOSED, "the model port is closed"));
            return result;
        }
        final String model = slots.modelFor(slot);
        if (model == null || model.trim().isEmpty()) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.NO_BINDING, slots.noModelMessage(slot)));
            return result;
        }
        final String endpointId = slots.endpointFor(model);
        if (!provider(endpointId).sharesMemory()) {
            // The service keeps its models ready: nothing to load, so no probe, no place and no load. The checks a
            // call would make still answer, from what is known now (never asking the service).
            String unusable = preflight(slot, endpointId, model);
            if (unusable != null) {
                result.completeExceptionally(new SlotUnavailableException(slot,
                        SlotUnavailableException.Reason.NO_BINDING, unusable));
            } else {
                result.complete(null);
            }
            return result;
        }
        if (needsInstalledProbe(endpointId, model)) {
            final CompletableFuture<Void> outer = new CompletableFuture<Void>();
            probeInstalled(endpointId).whenComplete((ignored, failure) -> {
                warm(slot).whenComplete((done, error) -> {
                    if (error != null) {
                        outer.completeExceptionally(error);
                    } else {
                        outer.complete(null);
                    }
                });
            });
            return outer;
        }
        String unusable = preflight(slot, endpointId, model);
        if (unusable != null) {
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.NO_BINDING, unusable));
            return result;
        }
        // The load goes through admission control like a call: it waits its turn, so it never loads another
        // model in the middle of an active batch (the scheduler's model-switch ordering and capacity).
        final CompletableFuture<ModelResourceScheduler.Grant> grant = scheduler.acquire(slot, endpointId, model,
                Duration.ofSeconds(settings().getImplicitWaitSeconds()));
        grant.whenComplete((granted, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure);
                return;
            }
            try {
                calls.submit(() -> {
                    try {
                        load(slot, endpointId, model, result);
                    } finally {
                        granted.release();
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException shutDown) {
                granted.release();
                result.completeExceptionally(new SlotUnavailableException(slot,
                        SlotUnavailableException.Reason.PORT_CLOSED, "the model port is closed"));
            }
        });
        return result;
    }

    private void load(ModelSlot slot, String endpointId, String model, CompletableFuture<Void> result) {
        try {
            ModelPortSettings settings = settings();
            provider(endpointId).load(endpoints.baseUrl(endpointId), model, contextOf(endpointId, model, settings),
                    settings.getKeepAlive());
            reachability.put(endpointId, Boolean.TRUE);
            result.complete(null);
        } catch (IOException unavailable) {
            reachability.put(endpointId, Boolean.FALSE);
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.ENDPOINT_UNAVAILABLE, "endpoint " + endpointId
                            + " is not available: " + unavailable.getMessage()));
        } catch (ProviderException failed) {
            boolean down = failed.isUnreachable();
            reachability.put(endpointId, down ? Boolean.FALSE : Boolean.TRUE);
            result.completeExceptionally(new SlotUnavailableException(slot,
                    SlotUnavailableException.Reason.ENDPOINT_UNAVAILABLE, failed.getMessage()));
        } catch (RuntimeException unexpected) {
            result.completeExceptionally(unexpected);
        }
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

    /** "No model for the Document phase. Set it under Research settings -> Models." */
    static String noModelMessage(ModelSlot slot) {
        return "No model for the " + ModelPortSettings.describe(slot.getName())
                + ". Set it under Research settings -> Models.";
    }

    /**
     * The checks before a call or a load (plan 5.1), or null when the model may be used: a helper slot needs a
     * model that can turn thinking off (a level-only family such as {@code gpt-oss} cannot), and a resolved
     * model must be installed on its endpoint. Reads only the cached installed list (probed off the calling
     * thread); an endpoint that cannot be asked skips the check, so the call itself reports the endpoint.
     */
    String preflight(ModelSlot slot, String endpointId, String model) {
        ModelProvider provider = provider(endpointId);
        if (ModelPortSettings.isHelperSlot(slot.getName()) && provider.isLevelOnlyModel(model)) {
            return provider.cannotTurnThinkingOffMessage(model, slot.getName());
        }
        if (!slots.checksInstalledModels() || com.aresstack.askai.localmodels.LocalModelNames
                .isLocalModelName(model)) {
            return null;
        }
        List<String> installed = installedModels(endpointId);
        if (installed == null || installed.isEmpty() || provider.offers(installed, model)) {
            return null;
        }
        return provider.missingModelMessage(model, slot.getName());
    }

    /**
     * The wire of an endpoint: the directory's provider, else the port's Ollama provider (Ollama and AskAI's local
     * runtime).
     */
    ModelProvider provider(String endpointId) {
        ModelProvider configured = endpoints.provider(endpointId);
        return configured != null ? configured : ollama;
    }

    /**
     * The capacity of a model on an endpoint: the user's per-model value, else the provider's default (an
     * OpenAI-compatible API runs several calls per model), else the port's default (Ollama).
     */
    int capacityOf(String endpointId, String model, ModelPortSettings settings) {
        Integer own = model == null ? null : settings.getModelCapacities().get(model.trim());
        if (own != null) {
            return own;
        }
        int providerDefault = provider(endpointId).defaultCapacity();
        return providerDefault > 0 ? providerDefault : settings.getDefaultCapacity();
    }

    /** The context a model runs with on an endpoint: the provider's, else the port's per-model value (num_ctx). */
    int contextOf(String endpointId, String model, ModelPortSettings settings) {
        int providerContext = provider(endpointId).contextTokens(model);
        return providerContext > 0 ? providerContext : settings.contextOf(model);
    }

    private static final long INSTALLED_TTL_MILLIS = 30_000L;
    /**
     * A model the cached list of an API lacks is asked for again after this long: the list depends on the key and
     * the account, which change without the port noticing (Ollama keeps the plain TTL).
     */
    private static final long INSTALLED_MISS_RECHECK_MILLIS = 10_000L;
    private static final long INSTALLED_PROBE_TIMEOUT_SECONDS = 5L;
    /**
     * endpoint -> {probe time, installed names or null when the endpoint could not be asked, the base URL asked}.
     * An API's list is only ever the list of the URL it came from: a changed URL (the user pointed the endpoint at
     * another service) makes it unknown at once. Ollama keeps its list for the TTL, as before.
     */
    private final Map<String, Object[]> installedCache = new ConcurrentHashMap<String, Object[]>();

    /** True when the installed list of {@code endpointId} must be (re)probed before the preflight. */
    @SuppressWarnings("unchecked")
    private boolean needsInstalledProbe(String endpointId, String model) {
        if (!slots.checksInstalledModels()
                || com.aresstack.askai.localmodels.LocalModelNames.isLocalModelName(model)) {
            return false;
        }
        Object[] cached = installedCache.get(endpointId);
        if (cached == null || !sameUrl(cached, endpointId)) {
            return true;
        }
        long age = System.currentTimeMillis() - (Long) cached[0];
        if (age >= INSTALLED_TTL_MILLIS) {
            return true;
        }
        ModelProvider provider = provider(endpointId);
        List<String> names = (List<String>) cached[1];
        return !provider.sharesMemory() && age >= INSTALLED_MISS_RECHECK_MILLIS && names != null
                && !names.isEmpty() && !provider.offers(names, model);
    }

    /** Whether a cache entry still describes the endpoint: an API's only at the base URL it was probed at. */
    private boolean sameUrl(Object[] cached, String endpointId) {
        return provider(endpointId).sharesMemory()
                || java.util.Objects.equals(cached[2], endpoints.baseUrlIfRunning(endpointId));
    }

    /**
     * Probe the installed models of an endpoint on the port's worker pool, bounded by a short timeout (the
     * client's own timeout is a model-call timeout); an endpoint that cannot be asked is cached as unknown,
     * so the preflight skips the check and the call reports the endpoint itself.
     */
    private CompletableFuture<Void> probeInstalled(final String endpointId) {
        final CompletableFuture<Void> done = new CompletableFuture<Void>();
        final long now = System.currentTimeMillis();
        final AtomicReference<String> asked = new AtomicReference<String>(); // the URL the list belongs to
        CompletableFuture<List<String>> probe;
        try {
            probe = CompletableFuture.supplyAsync(() -> {
                String baseUrl = endpoints.baseUrlIfRunning(endpointId);
                asked.set(baseUrl);
                if (baseUrl == null) {
                    return null;
                }
                try {
                    return provider(endpointId).modelNames(baseUrl);
                } catch (ProviderException unknown) {
                    return null;
                }
            }, calls).orTimeout(INSTALLED_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (java.util.concurrent.RejectedExecutionException shutDown) {
            installedCache.put(endpointId, new Object[]{now, null, null});
            done.complete(null);
            return done;
        }
        probe.whenComplete((names, failure) -> {
            // a probe that timed out before it ran is filed under today's URL, so it is not repeated at once
            String url = asked.get() != null ? asked.get() : endpoints.baseUrlIfRunning(endpointId);
            installedCache.put(endpointId, new Object[]{now, failure == null ? names : null, url});
            done.complete(null);
        });
        return done;
    }

    /**
     * The cached installed model names of an endpoint, or null when unknown (never probed, the endpoint could not
     * be asked, or the list came from another base URL). Never blocks.
     */
    @SuppressWarnings("unchecked")
    private List<String> installedModels(String endpointId) {
        Object[] cached = installedCache.get(endpointId);
        return cached == null || !sameUrl(cached, endpointId) ? null : (List<String>) cached[1];
    }

    private static void forward(CompletableFuture<SlotReservation> inner, CompletableFuture<SlotReservation> outer) {
        inner.whenComplete((reservation, failure) -> {
            if (failure != null) {
                outer.completeExceptionally(failure instanceof java.util.concurrent.CompletionException
                        && failure.getCause() != null ? failure.getCause() : failure);
            } else if (!outer.complete(reservation)) {
                reservation.close(); // the caller withdrew meanwhile: never leak the place
            }
        });
        outer.whenComplete((reservation, failure) -> {
            if (outer.isCancelled()) {
                inner.cancel(false);
            }
        });
    }

    static String contextTooLarge(String model, int needed, int available) {
        return "Context too large for " + model + ": the call needs " + needed + " tokens, the model's context is "
                + available + " (raise it in the model resources)";
    }

    private ModelPortSettings settings() {
        return settingsRepository.load();
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
         * A cancel or time-out reports at once, but on an endpoint whose models share one machine's memory the
         * scheduler place is handed on only once the stream has really stopped: otherwise the next call would reach
         * the endpoint while the aborted generation (for example a slow prefill that has not sent its first chunk)
         * is still running, breaking capacity 1. A service that serves many models side by side has no such order
         * to protect, and its stream may stay silent until the read timeout: there the place is handed on at once.
         */
        private synchronized void releaseWhenOffTheWire() {
            if (!active.get() && !released && (onWire == null || !provider(endpointId).sharesMemory())) {
                released = true;
                grant.release();
            }
        }
    }

    /** One chat call: the handle the caller gets, its execution, and the abort signal its provider watches. */
    private final class Call implements ModelCall, AbortSignal {
        private final ChatRequest request;
        private final ChatListener listener;
        private final AtomicBoolean terminal = new AtomicBoolean();
        private final AtomicBoolean aborted = new AtomicBoolean();
        /** What closes the provider's connection on an abort (an OpenAI-compatible stream); never blocks. */
        private final List<Runnable> abortActions = new CopyOnWriteArrayList<Runnable>();
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
            ModelPortSettings settings = settings();
            String slotName = reservation.slot.getName();
            int budget = request.getMaxOutputTokens() > 0 ? request.getMaxOutputTokens()
                    : settings.slotAnswerTokens(slotName);
            ProviderCall providerCall = new ProviderCall(slotName, reservation.model, request.getMessages(),
                    request.getTools(), request.getOutputFormat(), request.getThinking(),
                    settings.resolveThinking(slotName), request.getTemperature(), budget,
                    reservation.contextTokens, request.getTruncate(), settings.getKeepAlive(), this);
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
                ProviderCompletion completion = provider(reservation.endpointId).chat(baseUrl, providerCall,
                        new ProviderStream() {
                            public void onThinkingDelta(String delta) {
                                if (!terminal.get()) {
                                    listener.onThinkingDelta(delta);
                                }
                            }

                            public void onContentDelta(String delta) {
                                if (!terminal.get()) {
                                    listener.onContentDelta(delta);
                                }
                            }
                        });
                reservation.leaveWire(this); // the stream has ended: free the wire before any callback
                reachability.put(reservation.endpointId, Boolean.TRUE);
                complete(completion, reservation, Duration.ofNanos(System.nanoTime() - started));
            } catch (ProviderException failure) {
                reservation.leaveWire(this);
                if (aborted.get()) {
                    return; // cancel or time-out already delivered the terminal callback
                }
                if (failure.isUnreachable()) {
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

        private void complete(ProviderCompletion completion, Reservation reservation, Duration duration) {
            if (completion.getContent().trim().isEmpty() && completion.getToolCalls().isEmpty()) {
                // "The model spent everything on reasoning" must never look like a success.
                fail(ModelFailure.Kind.INVALID_RESPONSE, completion.getThinking().trim().isEmpty()
                        ? "the model answered with no text content"
                        : "the model answered with reasoning only, no answer ("
                                + completion.getThinking().trim().length() + " reasoning chars)");
                return;
            }
            if (terminal.compareAndSet(false, true)) {
                finish(); // free the reservation BEFORE the callback: the caller may chain the next call
                listener.onCompleted(new ChatResult(completion.getContent(), completion.getThinking(),
                        completion.getToolCalls(), completion.getFinishReason(), completion.getUsage(),
                        reservation.endpointId, duration, reservation.model, completion.getLoadDuration(),
                        completion.getPromptDuration()));
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
            for (Runnable action : abortActions) {
                runQuietly(action); // closes the provider's connection (on its own thread)
            }
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

        @Override
        public boolean isAborted() {
            return aborted.get();
        }

        @Override
        public void onAbort(Runnable action) {
            abortActions.add(action);
            if (aborted.get() && abortActions.remove(action)) {
                runQuietly(action); // aborted before the provider registered: free it now
            }
        }
    }

    private static void runQuietly(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ignored) {
            // freeing a connection that is already closed
        }
    }
}
