package com.aresstack.askai.java8.modelport;

import com.aresstack.askai.agent.model.port.ModelSlot;
import com.aresstack.askai.agent.model.port.resource.SlotUnavailableException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Admission control of the model port (ADR 0001, 4.5): places of the CAPACITY of one model on one endpoint
 * are granted here, and nowhere else. The scheduler never admits more concurrent grants than the capacity,
 * so nothing waits invisibly in Ollama's own queue.
 *
 * <p><b>Grouping by model, model switch as a phase.</b> Two different models on one endpoint only hold
 * places at the same time when the {@link Policy} says they may coexist (memory planning: both loaded sizes
 * known and within the endpoint's GPU budget). Otherwise a waiter for another model needs a MODEL SWITCH:
 * the scheduler finishes the current batch first and only then switches. The batch is frozen the moment a
 * switch becomes necessary: waiters of the active model that were already queued at that moment still run,
 * later ones queue behind the switch. That groups work by model (all judgements, then the synthesis) without
 * starving the other model. Within one model the order is arrival order.</p>
 *
 * <p>The scheduler is ADVISORY with respect to the provider: Ollama may still unload a model on its own.
 * It only orders and bounds what AskAI itself sends.</p>
 *
 * <p>Thread-safe. Futures are completed outside the lock; a grant whose future was cancelled concurrently is
 * released at once, never leaked.</p>
 */
public final class ModelResourceScheduler implements AutoCloseable {

    /** Capacities and memory planning, read at every dispatch so settings changes apply live. */
    public interface Policy {
        /** Maximum concurrent grants of {@code model} on {@code endpointId}; values below 1 count as 1. */
        int capacity(String endpointId, String model);

        /** Whether {@code loaded} and {@code next} may hold places on {@code endpointId} at the same time. */
        boolean mayCoexist(String endpointId, String loaded, String next);
    }

    /** Observes model switches (diagnostics). */
    public interface SwitchListener {
        void onModelSwitch(String endpointId, String fromModel, String toModel);
    }

    /** One granted place. {@link #release()} is idempotent. */
    public final class Grant {
        private final String endpointId;
        private final String model;
        private final AtomicBoolean released = new AtomicBoolean();

        private Grant(String endpointId, String model) {
            this.endpointId = endpointId;
            this.model = model;
        }

        public String getEndpointId() {
            return endpointId;
        }

        public String getModel() {
            return model;
        }

        public void release() {
            if (released.compareAndSet(false, true)) {
                ModelResourceScheduler.this.release(this);
            }
        }
    }

    /** Active and waiting counts of one model on one endpoint. */
    public static final class Usage {
        public final int active;
        public final int waiting;

        Usage(int active, int waiting) {
            this.active = active;
            this.waiting = waiting;
        }
    }

    private final Policy policy;
    private final SwitchListener switches;
    private final ScheduledExecutorService timer;
    private final Map<String, EndpointState> endpoints = new HashMap<String, EndpointState>();
    private long sequence;
    private boolean closed;

    public ModelResourceScheduler(Policy policy, SwitchListener switches) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
        this.switches = switches;
        this.timer = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable task) {
                Thread thread = new Thread(task, "model-port-scheduler");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Requests one place of {@code model}'s capacity on {@code endpointId}. Completes with a {@link Grant}, or
     * exceptionally with a {@link SlotUnavailableException} ({@code WAIT_TIMEOUT} after {@code maxWait},
     * {@code PORT_CLOSED} on close). Cancelling the future withdraws the request.
     */
    public CompletableFuture<Grant> acquire(ModelSlot slot, String endpointId, String model, Duration maxWait) {
        final CompletableFuture<Grant> future = new CompletableFuture<Grant>();
        final Waiter waiter;
        List<Admission> admitted;
        synchronized (this) {
            if (closed) {
                future.completeExceptionally(new SlotUnavailableException(slot,
                        SlotUnavailableException.Reason.PORT_CLOSED, "the model port is closed"));
                return future;
            }
            EndpointState state = state(endpointId);
            waiter = new Waiter(++sequence, endpointId, slot, model, future);
            state.queue.add(waiter);
            admitted = dispatch(endpointId, state);
        }
        complete(admitted);
        if (!future.isDone()) {
            long waitMillis = maxWait == null ? 0L : Math.max(0L, maxWait.toMillis());
            final ScheduledFuture<?> timeout = timer.schedule(new Runnable() {
                public void run() {
                    if (withdraw(endpointId, waiter)) {
                        future.completeExceptionally(new SlotUnavailableException(waiter.slot,
                                SlotUnavailableException.Reason.WAIT_TIMEOUT,
                                "no capacity became free within the maximum wait"));
                    }
                }
            }, waitMillis, TimeUnit.MILLISECONDS);
            future.whenComplete((grant, failure) -> {
                timeout.cancel(false);
                if (future.isCancelled()) {
                    withdraw(endpointId, waiter);
                }
            });
        }
        return future;
    }

    /** Active and waiting counts of one model on one endpoint, for the resource view. */
    public synchronized Usage usage(String endpointId, String model) {
        EndpointState state = endpoints.get(endpointId);
        if (state == null) {
            return new Usage(0, 0);
        }
        int waiting = 0;
        for (Waiter waiter : state.queue) {
            if (waiter.model.equals(model)) {
                waiting++;
            }
        }
        Integer active = state.active.get(model);
        return new Usage(active == null ? 0 : active, waiting);
    }

    /** Every (endpoint, model) pair the scheduler currently tracks with active or waiting grants. */
    public synchronized Map<String, List<String>> trackedModels() {
        Map<String, List<String>> result = new LinkedHashMap<String, List<String>>();
        for (Map.Entry<String, EndpointState> entry : endpoints.entrySet()) {
            List<String> models = new ArrayList<String>();
            for (Map.Entry<String, Integer> active : entry.getValue().active.entrySet()) {
                if (active.getValue() > 0 && !models.contains(active.getKey())) {
                    models.add(active.getKey());
                }
            }
            for (Waiter waiter : entry.getValue().queue) {
                if (!models.contains(waiter.model)) {
                    models.add(waiter.model);
                }
            }
            if (!models.isEmpty()) {
                result.put(entry.getKey(), models);
            }
        }
        return result;
    }

    /** Re-evaluates every queue, e.g. after a capacity setting or the residency changed. */
    public void reevaluate() {
        List<Admission> admitted = new ArrayList<Admission>();
        synchronized (this) {
            for (Map.Entry<String, EndpointState> entry : endpoints.entrySet()) {
                admitted.addAll(dispatch(entry.getKey(), entry.getValue()));
            }
        }
        complete(admitted);
    }

    /** Fails every waiting request with {@code PORT_CLOSED}; later requests fail at once. */
    @Override
    public void close() {
        List<Waiter> failed = new ArrayList<Waiter>();
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            for (EndpointState state : endpoints.values()) {
                failed.addAll(state.queue);
                state.queue.clear();
            }
        }
        for (Waiter waiter : failed) {
            waiter.future.completeExceptionally(new SlotUnavailableException(waiter.slot,
                    SlotUnavailableException.Reason.PORT_CLOSED, "the model port is closed"));
        }
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ internals

    private void release(Grant grant) {
        List<Admission> admitted;
        synchronized (this) {
            EndpointState state = endpoints.get(grant.endpointId);
            if (state == null) {
                return;
            }
            Integer active = state.active.get(grant.model);
            int remaining = active == null ? 0 : Math.max(0, active - 1);
            if (remaining == 0) {
                state.active.remove(grant.model);
            } else {
                state.active.put(grant.model, remaining);
            }
            admitted = dispatch(grant.endpointId, state);
        }
        complete(admitted);
    }

    private boolean withdraw(String endpointId, Waiter waiter) {
        List<Admission> admitted;
        boolean removed;
        synchronized (this) {
            EndpointState state = endpoints.get(endpointId);
            removed = state != null && state.queue.remove(waiter);
            // A withdrawn waiter may have been the reason a batch was frozen; let the others move on.
            admitted = state == null ? new ArrayList<Admission>() : dispatch(endpointId, state);
        }
        complete(admitted);
        return removed;
    }

    private EndpointState state(String endpointId) {
        EndpointState state = endpoints.get(endpointId);
        if (state == null) {
            state = new EndpointState();
            endpoints.put(endpointId, state);
        }
        return state;
    }

    /** Admits whatever may run now. Caller holds the lock; futures are completed by the caller afterwards. */
    private List<Admission> dispatch(String endpointId, EndpointState state) {
        List<Admission> admitted = new ArrayList<Admission>();
        boolean progress = true;
        while (progress) {
            progress = false;
            List<String> batch = batchModels(state);
            if (batch.isEmpty()) {
                state.cutoff = Long.MAX_VALUE; // between batches: the oldest waiter decides the next model
            }
            boolean conflict = false;
            for (Waiter waiter : state.queue) {
                if (conflicts(endpointId, batch, waiter.model)) {
                    conflict = true;
                    break;
                }
            }
            if (!conflict) {
                state.cutoff = Long.MAX_VALUE;
            } else if (state.cutoff == Long.MAX_VALUE) {
                // A switch is needed: freeze the current batch at everything queued so far.
                state.cutoff = state.queue.getLast().seq;
            }
            Iterator<Waiter> it = state.queue.iterator();
            while (it.hasNext()) {
                Waiter waiter = it.next();
                if (waiter.future.isDone()) {
                    it.remove();
                    continue;
                }
                if (conflicts(endpointId, batch, waiter.model)) {
                    continue;
                }
                if (!batch.isEmpty() && waiter.seq > state.cutoff) {
                    continue; // queued behind a pending model switch
                }
                Integer active = state.active.get(waiter.model);
                int used = active == null ? 0 : active;
                if (used >= Math.max(1, policy.capacity(endpointId, waiter.model))) {
                    continue;
                }
                it.remove();
                if (state.active.isEmpty() && state.lastModel != null && !state.lastModel.equals(waiter.model)
                        && switches != null) {
                    switches.onModelSwitch(endpointId, state.lastModel, waiter.model);
                }
                state.active.put(waiter.model, used + 1);
                state.lastModel = waiter.model;
                admitted.add(new Admission(waiter, new Grant(endpointId, waiter.model)));
                progress = true;
                break; // the active set changed: re-evaluate conflicts and the cutoff
            }
        }
        return admitted;
    }

    /**
     * The models of the current batch: those holding places, or, in the gap between two calls of a frozen
     * batch, the last model while it still has waiters inside the batch (so the batch is not lost just
     * because one call ended before the next one started).
     */
    private static List<String> batchModels(EndpointState state) {
        List<String> batch = new ArrayList<String>();
        for (Map.Entry<String, Integer> entry : state.active.entrySet()) {
            if (entry.getValue() > 0) {
                batch.add(entry.getKey());
            }
        }
        if (batch.isEmpty() && state.lastModel != null && state.cutoff != Long.MAX_VALUE) {
            for (Waiter waiter : state.queue) {
                if (waiter.seq <= state.cutoff && waiter.model.equals(state.lastModel) && !waiter.future.isDone()) {
                    batch.add(state.lastModel);
                    break;
                }
            }
        }
        return batch;
    }

    private boolean conflicts(String endpointId, List<String> batch, String model) {
        for (String loaded : batch) {
            if (!loaded.equals(model) && !policy.mayCoexist(endpointId, loaded, model)) {
                return true;
            }
        }
        return false;
    }

    private void complete(List<Admission> admitted) {
        for (Admission admission : admitted) {
            if (!admission.waiter.future.complete(admission.grant)) {
                admission.grant.release(); // cancelled or timed out concurrently: never leak the place
            }
        }
    }

    private static final class EndpointState {
        final Map<String, Integer> active = new LinkedHashMap<String, Integer>();
        final LinkedList<Waiter> queue = new LinkedList<Waiter>();
        long cutoff = Long.MAX_VALUE;
        String lastModel;
    }

    private static final class Waiter {
        final long seq;
        final String endpointId;
        final ModelSlot slot;
        final String model;
        final CompletableFuture<Grant> future;

        Waiter(long seq, String endpointId, ModelSlot slot, String model, CompletableFuture<Grant> future) {
            this.seq = seq;
            this.endpointId = endpointId;
            this.slot = slot;
            this.model = model;
            this.future = future;
        }
    }

    private static final class Admission {
        final Waiter waiter;
        final Grant grant;

        Admission(Waiter waiter, Grant grant) {
            this.waiter = waiter;
            this.grant = grant;
        }
    }
}
