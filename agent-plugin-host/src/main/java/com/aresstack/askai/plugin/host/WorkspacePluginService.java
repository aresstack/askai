package com.aresstack.askai.plugin.host;

import com.aresstack.askai.plugin.api.WorkspacePluginDescriptor;
import com.aresstack.askai.plugin.api.service.UiExecutor;
import com.aresstack.askai.plugin.pf4j.api.AgentPluginExtension;
import com.aresstack.askai.plugin.pf4j.api.WorkspacePluginExtension;

import org.pf4j.PluginWrapper;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Discovers workspace/agent plugins from a single controlled root, off the EDT, and publishes a validated
 * {@link PluginCatalogSnapshot} to all listeners on the EDT. It owns the PF4J runtime as a single
 * {@link PluginRuntimeGeneration}.
 *
 * <p>A refresh is transactional: a fresh candidate generation is built entirely off-EDT in local variables; on
 * a build failure the half-built manager is retired and the previous generation is kept. On a successful build
 * the swap runs as EDT → off-EDT → EDT so the EDT never blocks: the EDT only detaches the outgoing generation's
 * sessions from routing; the (potentially blocking) session close and plugin stop/unload run off-EDT; and the
 * old generation is retired <em>only after</em> its sessions are proven closed — so no live session ever
 * outlives its plugin classloader. A generation that cannot be fully stopped/unloaded is kept in
 * {@code retiringGenerations} and retried on the next refresh and at shutdown; the failure is surfaced as a
 * global lifecycle failure in Plugin Management rather than being swallowed.</p>
 *
 * <p>Disable semantics: a disabled plugin is loaded but never started (keyed on the stable PF4J plugin id); it
 * exposes no selectable extension and shows an honest {@code Enabled=false} / {@link PluginCompatibility#NOT_EVALUATED}
 * row. A start failure is reported as {@link PluginCompatibility#START_FAILED}, never as a missing extension.</p>
 */
public final class WorkspacePluginService {

    private final Path pluginsRoot;
    private final String systemVersion;
    private final UiExecutor uiExecutor;
    private final PluginCompatibilityChecker compatibilityChecker;
    private final PluginEnablementService enablement;
    private final PluginManagerFactory managerFactory;
    private final ExecutorService discoveryExecutor;
    private final CopyOnWriteArrayList<WorkspaceCatalogListener> listeners =
            new CopyOnWriteArrayList<WorkspaceCatalogListener>();

    /** The single active runtime generation; {@code null} until the first successful discovery. */
    private volatile PluginRuntimeGeneration activeGeneration;
    private volatile PluginCatalogSnapshot latestSnapshot =
            new PluginCatalogSnapshot(0L, Collections.<PluginCatalogEntry>emptyList(),
                    Collections.<PluginLoadFailure>emptyList(), 0L, false);

    /**
     * Generations that could not be fully retired, deduplicated by generation id (so repeated sweeps never
     * enqueue the same generation twice), with the latest {@link GenerationRetirementResult} kept so Plugin
     * Management can report the exact plugin id and stop/unload phase. Retried on the next refresh and at shutdown.
     */
    private final Object retireLock = new Object();
    private final LinkedHashMap<Long, PluginRuntimeGeneration> retiringGenerations =
            new LinkedHashMap<Long, PluginRuntimeGeneration>();
    private final Map<Long, GenerationRetirementResult> lastRetireResult =
            new HashMap<Long, GenerationRetirementResult>();

    /** Bounded waits so a broken/hung EDT can never wedge a refresh or shutdown forever. */
    private static final long EDT_WAIT_TIMEOUT_MS = 30_000L;
    /** Injectable for tests so cancel-safety can be exercised without a real 30s wait. */
    private volatile long edtWaitTimeoutMs = EDT_WAIT_TIMEOUT_MS;

    /** The lifecycle of one bounded EDT round-trip; a runnable mutates only after CAS {@code PENDING→RUNNING}. */
    private enum EdtCallState { PENDING, RUNNING, COMPLETED, CANCELLED, FAILED }

    /**
     * Coordinates the session side of a generation swap across the EDT boundary (detach on EDT, close off-EDT).
     * May be {@code null} (no agent sessions to manage).
     */
    private volatile GenerationSwapHook generationSwapHook;

    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicLong refreshGeneration =
            new java.util.concurrent.atomic.AtomicLong(0L);

    public WorkspacePluginService(Path pluginsRoot, String systemVersion, int supportedApiVersion,
                                  UiExecutor uiExecutor, PluginEnablementService enablement) {
        this(pluginsRoot, systemVersion, supportedApiVersion, uiExecutor, enablement, null);
    }

    /**
     * Package-private overload with an injectable {@link PluginManagerFactory}, used by tests to deterministically
     * simulate discovery/start failures. Production always uses the default factory (a fresh
     * {@link AskAiPluginManager}).
     */
    WorkspacePluginService(Path pluginsRoot, String systemVersion, int supportedApiVersion,
                           UiExecutor uiExecutor, PluginEnablementService enablement,
                           PluginManagerFactory managerFactory) {
        this.pluginsRoot = pluginsRoot;
        this.systemVersion = systemVersion;
        this.uiExecutor = uiExecutor;
        this.enablement = enablement;
        this.managerFactory = managerFactory != null ? managerFactory : new PluginManagerFactory() {
            public AskAiPluginManager create() {
                return new AskAiPluginManager(pluginsRoot, systemVersion);
            }
        };
        this.compatibilityChecker = new PluginCompatibilityChecker(supportedApiVersion);
        this.discoveryExecutor = Executors.newSingleThreadExecutor(new DaemonThreadFactory());
    }

    /** Creates the PF4J manager for a candidate generation. A test seam for fault injection. */
    interface PluginManagerFactory {
        AskAiPluginManager create() throws Exception;
    }

    public void addCatalogListener(WorkspaceCatalogListener listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeCatalogListener(WorkspaceCatalogListener listener) {
        listeners.remove(listener);
    }

    /** Set the hook that detaches/closes the outgoing generation's agent sessions during a swap. May be null. */
    public void setGenerationSwapHook(GenerationSwapHook hook) {
        this.generationSwapHook = hook;
    }

    /**
     * Re-discovers and validates plugins off-EDT, then transactionally swaps in the new generation. Rejected
     * once shutdown has begun. Concurrent refreshes are serialized on a single-thread executor and only the
     * newest generation is applied.
     */
    public void refreshAsync() {
        if (shuttingDown.get()) {
            return;
        }
        final long generation = refreshGeneration.incrementAndGet();
        try {
            discoveryExecutor.execute(new Runnable() {
                public void run() {
                    runRefresh(generation);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The executor was already shut down; nothing to deliver.
        }
    }

    private boolean stale(long generation) {
        return shuttingDown.get() || generation != refreshGeneration.get();
    }

    /** The whole off-EDT refresh, hopping briefly to the EDT only to detach sessions and publish. */
    private void runRefresh(final long generation) {
        if (stale(generation)) {
            return;
        }
        Candidate candidate = buildCandidate(generation);
        if (stale(generation)) {
            retireCandidate(candidate);
            return;
        }
        if (candidate.generation == null) {
            publishOnEdt(generation, keptSnapshot(generation, candidate.globalFailures, true));
            return;
        }
        final PluginRuntimeGeneration cand = candidate.generation;

        // EDT: detach the outgoing generation's sessions from routing (cheap, non-blocking). A failed or
        // timed-out detach aborts the swap — the candidate is retired and the previous generation is kept.
        final GenerationSwapHook hook = generationSwapHook;
        final GenerationSwapHook.OutgoingSessions[] outgoing = new GenerationSwapHook.OutgoingSessions[1];
        UiCallResult detachResult = runOnEdtAndWait(new Runnable() {
            public void run() {
                outgoing[0] = hook == null ? null : hook.detachOutgoing();
            }
        });
        if (!detachResult.ok) {
            trackIfIncomplete(cand, cand.retire());
            List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
            failures.add(lifecycleFailure(PluginFailurePhase.SESSION_CLOSE,
                    "Detaching the previous generation's sessions failed; refresh aborted. "
                            + detachResult.describe()));
            publishOnEdt(generation, keptSnapshot(generation, failures, true));
            return;
        }

        // Off-EDT: close the detached sessions. The old classloader is retired only if this succeeds.
        SessionCloseResult close = outgoing[0] == null ? SessionCloseResult.ok() : outgoing[0].closeAll();
        if (!close.isSuccessful()) {
            GenerationRetirementResult candRetire = cand.retire();
            trackIfIncomplete(cand, candRetire);
            List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
            for (String f : close.getFailures()) {
                failures.add(lifecycleFailure(PluginFailurePhase.SESSION_CLOSE,
                        "A session of the previous plugin generation could not be closed; refresh aborted. " + f));
            }
            addRetirementFailures(failures, candRetire);
            publishOnEdt(generation, keptSnapshot(generation, failures, true));
            return;
        }
        if (stale(generation)) {
            trackIfIncomplete(cand, cand.retire());
            return;
        }

        // EDT: atomically publish the new generation. The activeGeneration write cannot throw and delivery to
        // listeners is guarded, so the runnable only mutates once it wins PENDING→RUNNING. If it was cancelled
        // (this thread timed out first), it performs no swap at all — the candidate never becomes active — so we
        // discard the candidate and keep the previous generation. No "ghost publish" can activate it later.
        final PluginRuntimeGeneration[] previous = new PluginRuntimeGeneration[1];
        UiCallResult publishResult = runOnEdtAndWait(new Runnable() {
            public void run() {
                previous[0] = activeGeneration;
                activeGeneration = cand;
                PluginCatalogSnapshot snapshot = new PluginCatalogSnapshot(generation, cand.entries(),
                        combinedGlobalFailures(cand.globalFailures()), System.currentTimeMillis(), false);
                latestSnapshot = snapshot;
                try {
                    deliver(snapshot);
                } catch (RuntimeException | Error ignored) {
                    // a listener failure must not abort an already-completed swap
                }
            }
        });
        if (!publishResult.ok) {
            trackIfIncomplete(cand, cand.retire()); // candidate never became active: discard it, keep previous
            publishOnEdt(generation, keptSnapshot(generation,
                    Collections.singletonList(lifecycleFailure(PluginFailurePhase.SESSION_CLOSE,
                            "Publishing the new plugin generation did not complete; refresh aborted. "
                                    + publishResult.describe())), true));
            return;
        }

        // Off-EDT: retire the previous generation (and retry any earlier incomplete retirements).
        sweepRetiringGenerations();
        if (previous[0] != null) {
            trackIfIncomplete(previous[0], previous[0].retire());
        }
        if (getPendingRetirementCount() > 0) {
            publishLifecycleUpdate(generation, cand);
        }
    }

    /** The outcome of a bounded EDT round-trip: completed cleanly, or failed (task threw) / timed out. */
    static final class UiCallResult {
        final boolean ok;
        private final Throwable failure;
        private final boolean timedOut;

        private UiCallResult(boolean ok, Throwable failure, boolean timedOut) {
            this.ok = ok;
            this.failure = failure;
            this.timedOut = timedOut;
        }

        String describe() {
            if (timedOut) {
                return "the UI thread did not respond in time";
            }
            if (failure != null) {
                return failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            }
            return "";
        }
    }

    /** For tests: shorten the EDT wait so cancel-safety can be exercised without a real 30-second timeout. */
    void setEdtWaitTimeoutMillisForTest(long millis) {
        this.edtWaitTimeoutMs = millis;
    }

    /**
     * Run {@code task} on the EDT and wait for it (bounded), without blocking the EDT itself. The task mutates
     * only after atomically moving {@code PENDING→RUNNING}; if this lifecycle thread times out first it atomically
     * moves {@code PENDING→CANCELLED}, after which the queued runnable — should it ever run — finds the CAS failed
     * and performs no mutation. This makes a timed-out detach/publish genuinely inert (no late "ghost" mutation).
     * If the task is already {@code RUNNING} at the timeout it is <em>not</em> treated as cancelled: we wait for
     * its true final state. An exception thrown by the task is propagated back as a non-ok result.
     */
    UiCallResult runOnEdtAndWait(final Runnable task) {
        if (uiExecutor.isUiThread()) {
            try {
                task.run();
                return new UiCallResult(true, null, false);
            } catch (RuntimeException | Error ex) {
                return new UiCallResult(false, ex, false);
            }
        }
        final java.util.concurrent.atomic.AtomicReference<EdtCallState> status =
                new java.util.concurrent.atomic.AtomicReference<EdtCallState>(EdtCallState.PENDING);
        final Throwable[] thrown = new Throwable[1];
        final CountDownLatch done = new CountDownLatch(1);
        try {
            uiExecutor.execute(new Runnable() {
                public void run() {
                    if (!status.compareAndSet(EdtCallState.PENDING, EdtCallState.RUNNING)) {
                        return; // cancelled before we started: must NOT mutate
                    }
                    try {
                        task.run();
                        status.set(EdtCallState.COMPLETED);
                    } catch (RuntimeException | Error ex) {
                        thrown[0] = ex;
                        status.set(EdtCallState.FAILED);
                    } finally {
                        done.countDown();
                    }
                }
            });
        } catch (RuntimeException ex) {
            status.set(EdtCallState.FAILED);
            return new UiCallResult(false, ex, false);
        }
        boolean finishedInTime;
        try {
            finishedInTime = done.await(edtWaitTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new UiCallResult(false, ex, false);
        }
        if (!finishedInTime) {
            if (status.compareAndSet(EdtCallState.PENDING, EdtCallState.CANCELLED)) {
                return new UiCallResult(false, null, true); // safely cancelled; the runnable will no-op
            }
            // Already RUNNING: the mutation is in flight; wait for its real final state rather than guessing.
            try {
                done.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return new UiCallResult(false, ex, false);
            }
        }
        EdtCallState finalState = status.get();
        if (finalState == EdtCallState.COMPLETED) {
            return new UiCallResult(true, null, false);
        }
        if (finalState == EdtCallState.CANCELLED) {
            return new UiCallResult(false, null, true);
        }
        return new UiCallResult(false, thrown[0], false); // FAILED
    }

    private void publishOnEdt(final long generation, final PluginCatalogSnapshot snapshot) {
        runOnEdtAndWait(new Runnable() {
            public void run() {
                if (shuttingDown.get() || generation != refreshGeneration.get()) {
                    return;
                }
                latestSnapshot = snapshot;
                deliver(snapshot);
            }
        });
    }

    /** A snapshot that keeps the previous generation's entries (build/swap failed); reports failures globally. */
    private PluginCatalogSnapshot keptSnapshot(long generation, List<PluginLoadFailure> failures,
                                               boolean generationFailed) {
        PluginRuntimeGeneration prev = activeGeneration;
        List<PluginCatalogEntry> entries = prev == null
                ? Collections.<PluginCatalogEntry>emptyList() : prev.entries();
        return new PluginCatalogSnapshot(generation, entries, combinedGlobalFailures(failures),
                System.currentTimeMillis(), generationFailed);
    }

    private void publishLifecycleUpdate(final long generation, final PluginRuntimeGeneration current) {
        runOnEdtAndWait(new Runnable() {
            public void run() {
                if (shuttingDown.get() || generation != refreshGeneration.get() || activeGeneration != current) {
                    return;
                }
                PluginCatalogSnapshot snapshot = new PluginCatalogSnapshot(generation, current.entries(),
                        combinedGlobalFailures(current.globalFailures()), System.currentTimeMillis(), false);
                latestSnapshot = snapshot;
                deliver(snapshot);
            }
        });
    }

    /** Combine per-refresh failures with the standing lifecycle failures from not-yet-retired generations. */
    private List<PluginLoadFailure> combinedGlobalFailures(List<PluginLoadFailure> refreshFailures) {
        List<PluginLoadFailure> all = new ArrayList<PluginLoadFailure>();
        if (refreshFailures != null) {
            all.addAll(refreshFailures);
        }
        synchronized (retireLock) {
            for (Map.Entry<Long, PluginRuntimeGeneration> entry : retiringGenerations.entrySet()) {
                long id = entry.getKey();
                GenerationRetirementResult result = lastRetireResult.get(id);
                if (result == null) {
                    all.add(lifecycleFailure(PluginFailurePhase.GENERATION_RETIREMENT,
                            "Plugin generation " + id + " could not be fully retired; will retry."));
                    continue;
                }
                for (Map.Entry<String, String> f : result.getStopFailures().entrySet()) {
                    all.add(lifecycleFailure(PluginFailurePhase.GENERATION_RETIREMENT,
                            "Generation " + id + ": could not stop plugin " + f.getKey()
                                    + " (" + f.getValue() + "); will retry."));
                }
                for (Map.Entry<String, String> f : result.getUnloadFailures().entrySet()) {
                    all.add(lifecycleFailure(PluginFailurePhase.GENERATION_RETIREMENT,
                            "Generation " + id + ": could not unload plugin " + f.getKey()
                                    + " (" + f.getValue() + "); will retry."));
                }
            }
        }
        return all;
    }

    private void deliver(PluginCatalogSnapshot snapshot) {
        for (WorkspaceCatalogListener listener : listeners) {
            listener.onCatalogSnapshot(snapshot);
        }
    }

    private void retireCandidate(Candidate candidate) {
        if (candidate != null && candidate.generation != null) {
            trackIfIncomplete(candidate.generation, candidate.generation.retire());
        }
    }

    /** Track (or clear) a generation by its stable id, deduplicated, keeping the latest retirement result. */
    private void trackIfIncomplete(PluginRuntimeGeneration generation, GenerationRetirementResult result) {
        if (generation == null) {
            return;
        }
        synchronized (retireLock) {
            long id = generation.generationId();
            if (result == null || !result.isComplete()) {
                retiringGenerations.put(id, generation);
                if (result != null) {
                    lastRetireResult.put(id, result);
                }
            } else {
                retiringGenerations.remove(id);
                lastRetireResult.remove(id);
            }
        }
    }

    private void sweepRetiringGenerations() {
        List<PluginRuntimeGeneration> copy;
        synchronized (retireLock) {
            copy = new ArrayList<PluginRuntimeGeneration>(retiringGenerations.values());
        }
        for (PluginRuntimeGeneration generation : copy) {
            GenerationRetirementResult result = generation.retire();
            synchronized (retireLock) {
                long id = generation.generationId();
                if (result.isComplete()) {
                    retiringGenerations.remove(id);
                    lastRetireResult.remove(id);
                } else {
                    lastRetireResult.put(id, result);
                }
            }
        }
    }

    private static void addRetirementFailures(List<PluginLoadFailure> into, GenerationRetirementResult result) {
        if (result == null) {
            return;
        }
        for (String id : result.getStopFailures().keySet()) {
            into.add(lifecycleFailure(PluginFailurePhase.GENERATION_RETIREMENT,
                    "Could not stop plugin " + id + " while discarding the candidate."));
        }
        for (String id : result.getUnloadFailures().keySet()) {
            into.add(lifecycleFailure(PluginFailurePhase.GENERATION_RETIREMENT,
                    "Could not unload plugin " + id + " while discarding the candidate."));
        }
    }

    private static PluginLoadFailure lifecycleFailure(PluginFailurePhase phase, String message) {
        return new PluginLoadFailure("", "", phase, message, null);
    }

    /** @return the compatible, enabled workspace extension for a descriptor id, or {@code null}. */
    public WorkspacePluginExtension getSelectableExtension(String descriptorId) {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? null : generation.selectableExtension(descriptorId);
    }

    /** @return the compatible, enabled <em>agent</em> extension for an agent id, or {@code null}. */
    public AgentPluginExtension getSelectableAgentExtension(String agentId) {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? null : generation.selectableAgentExtension(agentId);
    }

    /** @return the descriptors of all selectable agents, in discovery order (for the Questing agent list). */
    public List<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor> getSelectableAgentDescriptors() {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null
                ? new ArrayList<com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor>()
                : generation.agentDescriptors();
    }

    /** The setup-wizard sections of all selectable agents, in discovery order; a failing plugin is left out. */
    public List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> getSetupContributions() {
        List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> all =
                new ArrayList<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution>();
        for (com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor descriptor : getSelectableAgentDescriptors()) {
            try {
                AgentPluginExtension extension = getSelectableAgentExtension(descriptor.getId());
                List<com.aresstack.askai.plugin.api.agent.setup.AgentSetupContribution> contributions =
                        extension == null ? null : extension.getSetupContributions();
                if (contributions != null) {
                    all.addAll(contributions);
                }
            } catch (RuntimeException | LinkageError broken) {
                // A plugin built against an older API or failing here must not break the wizard.
            }
        }
        return all;
    }

    public List<PluginCatalogEntry> getCatalog() {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? Collections.<PluginCatalogEntry>emptyList() : generation.entries();
    }

    /** @return the most recent snapshot (generation id, entries, global failures, failed-refresh flag). */
    public PluginCatalogSnapshot getCatalogSnapshot() {
        return latestSnapshot;
    }

    /** @return the id of the currently active generation, or {@code 0} if none has been published. */
    public long getActiveGenerationId() {
        PluginRuntimeGeneration generation = activeGeneration;
        return generation == null ? 0L : generation.generationId();
    }

    /** @return the number of generations still awaiting a complete retirement (0 when everything is clean). */
    public int getPendingRetirementCount() {
        synchronized (retireLock) {
            return retiringGenerations.size();
        }
    }

    private volatile CountDownLatch shutdownComplete;

    /**
     * Serialized, idempotent shutdown, and the single production teardown path (the host must not close sessions
     * itself first). Two-stage so the EDT is never blocked: the caller thread (the EDT at window close) only
     * detaches routing and sessions — cheap, non-blocking — while the actual session close and plugin stop/unload
     * run <em>off</em> the EDT on a dedicated non-daemon lifecycle thread (which keeps the JVM alive to finish,
     * without a blocking {@code join} on the EDT). A session close failure is honoured: the generation is then
     * <em>not</em> unloaded (that would strand live objects behind a dead classloader) but kept tracked. Retiring
     * one-by-one avoids PF4J's {@code stopPlugins()} CME.
     */
    public void shutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            return; // idempotent
        }
        discoveryExecutor.shutdownNow();
        listeners.clear();
        final CountDownLatch complete = new CountDownLatch(1);
        shutdownComplete = complete;
        // Stage 1 (caller thread, EDT-safe): detach the outgoing sessions from routing.
        GenerationSwapHook hook = generationSwapHook;
        GenerationSwapHook.OutgoingSessions detached = null;
        if (hook != null) {
            try {
                detached = hook.detachOutgoing();
            } catch (RuntimeException | Error ignored) {
                // a failing detach must not stop plugin retirement at shutdown
            }
        }
        final GenerationSwapHook.OutgoingSessions outgoing = detached;
        final PluginRuntimeGeneration generation = activeGeneration;
        activeGeneration = null;
        // Stage 2 (off the EDT): close the detached sessions, then stop/unload the plugins.
        Runnable work = new Runnable() {
            public void run() {
                try {
                    SessionCloseResult close = outgoing == null
                            ? SessionCloseResult.ok() : outgoing.closeAll();
                    if (generation != null) {
                        if (close.isSuccessful()) {
                            trackIfIncomplete(generation, generation.retire());
                            sweepRetiringGenerations();
                        } else {
                            // A failed session close must NOT be followed by a classloader unload; keep the
                            // generation tracked (the JVM exit reclaims it) rather than stranding live objects.
                            trackIfIncomplete(generation, null);
                        }
                    } else {
                        sweepRetiringGenerations();
                    }
                } finally {
                    complete.countDown();
                }
            }
        };
        if (!uiExecutor.isUiThread()) {
            work.run(); // already off the EDT (tests, background callers): synchronous
        } else {
            Thread worker = new Thread(work, "askai-plugin-shutdown");
            worker.setDaemon(false); // keep the JVM alive to finish; do NOT block the EDT with a join
            worker.start();
        }
    }

    /** For tests: await the off-EDT shutdown work. @return true if it completed within the timeout. */
    boolean awaitShutdownForTest(long millis) throws InterruptedException {
        CountDownLatch complete = shutdownComplete;
        return complete == null || complete.await(millis, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ discovery (off-EDT)

    /** Result of an off-EDT candidate build: either a fully-built generation, or a global failure. */
    private static final class Candidate {
        private final PluginRuntimeGeneration generation; // null on global failure
        private final List<PluginLoadFailure> globalFailures;

        private Candidate(PluginRuntimeGeneration generation, List<PluginLoadFailure> globalFailures) {
            this.generation = generation;
            this.globalFailures = globalFailures;
        }
    }

    /**
     * Build a fresh candidate generation off the EDT: new manager → load all plugins → start only <em>enabled</em>
     * plugins individually (keyed on the stable PF4J plugin id) → inspect started plugins and validate → add
     * honest disabled rows for loaded-but-not-started plugins. Any failure between {@code create()} and full
     * discovery retires the half-built manager and returns a global failure so the caller keeps the previous
     * generation.
     */
    private Candidate buildCandidate(long generation) {
        AskAiPluginManager manager = null;
        try {
            manager = managerFactory.create();
            manager.loadPlugins();
        } catch (Exception | Error ex) {
            cleanUpHalfBuilt(generation, manager);
            return globalFailure("Plugin discovery failed.", ex);
        }

        PluginRuntimeGeneration.Builder builder = PluginRuntimeGeneration.builder(generation, manager);

        // Decide enablement per plugin BEFORE starting anything, so a disabled plugin is never started.
        List<PluginWrapper> loaded;
        try {
            loaded = new ArrayList<PluginWrapper>(manager.getPlugins());
        } catch (RuntimeException | Error ex) {
            cleanUpHalfBuilt(generation, manager);
            return globalFailure("Could not enumerate loaded plugins.", ex);
        }

        Set<String> startedIds = new HashSet<String>();
        Set<String> failedStartIds = new HashSet<String>();
        for (PluginWrapper wrapper : loaded) {
            String pf4jId = wrapper.getPluginId();
            boolean enabled = enablement == null || enablement.isEnabled(pf4jId);
            if (!enabled) {
                continue; // loaded but not started; an honest NOT_EVALUATED row is added below
            }
            try {
                manager.startPlugin(pf4jId);
                startedIds.add(pf4jId);
            } catch (RuntimeException | Error ex) {
                failedStartIds.add(pf4jId);
                builder.addEntry(notStartedRow(wrapper, true, PluginCompatibility.START_FAILED,
                        new PluginLoadFailure(String.valueOf(wrapper.getPluginPath()), pf4jId,
                                PluginFailurePhase.PLUGIN_START, "The plugin failed to start.", ex)));
            }
        }

        Set<String> seenIds = new HashSet<String>();
        for (PluginWrapper wrapper : loaded) {
            String pf4jId = wrapper.getPluginId();
            if (startedIds.contains(pf4jId)) {
                inspectStarted(manager, wrapper, seenIds, builder);
            } else if (failedStartIds.contains(pf4jId)) {
                continue; // START_FAILED row already added
            } else {
                builder.addEntry(notStartedRow(wrapper, false, PluginCompatibility.NOT_EVALUATED, null));
            }
        }
        PluginRuntimeGeneration built = builder.build();
        return new Candidate(built, built.globalFailures());
    }

    /** Retire a manager whose candidate build failed; track it if it could not be fully retired. */
    private void cleanUpHalfBuilt(long generation, AskAiPluginManager manager) {
        if (manager == null) {
            return;
        }
        PluginRuntimeGeneration halfBuilt = PluginRuntimeGeneration.builder(generation, manager).build();
        trackIfIncomplete(halfBuilt, halfBuilt.retire());
    }

    private static Candidate globalFailure(String message, Throwable cause) {
        List<PluginLoadFailure> failures = new ArrayList<PluginLoadFailure>();
        failures.add(new PluginLoadFailure("", "", PluginFailurePhase.EXTENSION_DISCOVERY, message, cause));
        return new Candidate(null, failures);
    }

    /** Inspect a started plugin: read its single extension, validate compatibility, register if selectable. */
    private void inspectStarted(AskAiPluginManager manager, PluginWrapper wrapper, Set<String> seenIds,
                                PluginRuntimeGeneration.Builder builder) {
        String pluginId = wrapper.getPluginId();
        String location = String.valueOf(wrapper.getPluginPath());
        String sha256 = sha256Of(wrapper.getPluginPath());

        List<WorkspacePluginExtension> workspaceExtensions;
        AgentPluginExtension agentExtension;
        try {
            workspaceExtensions = manager.getExtensions(WorkspacePluginExtension.class, pluginId);
            List<AgentPluginExtension> agentExtensions =
                    manager.getExtensions(AgentPluginExtension.class, pluginId);
            agentExtension = agentExtensions == null || agentExtensions.isEmpty()
                    ? null : agentExtensions.get(0);
        } catch (RuntimeException | Error ex) {
            PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                    PluginFailurePhase.EXTENSION_DISCOVERY, "Could not load the plugin's extension.", ex);
            builder.addEntry(PluginCatalogEntry.builder().pluginId(pluginId).location(location).sha256(sha256)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.MISSING_EXTENSION).lastError(failure).build());
            return;
        }

        boolean hasWorkspace = workspaceExtensions != null && !workspaceExtensions.isEmpty();
        WorkspacePluginDescriptor descriptor = null;
        try {
            if (hasWorkspace) {
                descriptor = workspaceExtensions.get(0).getDescriptor();
            } else if (agentExtension != null) {
                descriptor = toWorkspaceDescriptor(agentExtension.getAgentDescriptor());
            }
        } catch (RuntimeException | Error ex) {
            PluginLoadFailure failure = new PluginLoadFailure(location, pluginId,
                    PluginFailurePhase.DESCRIPTOR_VALIDATION, "The plugin descriptor is invalid.", ex);
            builder.addEntry(PluginCatalogEntry.builder().pluginId(pluginId).location(location).sha256(sha256)
                    .pluginState(String.valueOf(wrapper.getPluginState()))
                    .compatibility(PluginCompatibility.DESCRIPTOR_INVALID).lastError(failure).build());
            return;
        }

        PluginCompatibility compatibility = descriptor == null
                ? PluginCompatibility.MISSING_EXTENSION
                : compatibilityChecker.check(descriptor,
                        wrapper.getDescriptor().getPluginId(), wrapper.getDescriptor().getVersion(),
                        1, seenIds);

        if (compatibility == PluginCompatibility.COMPATIBLE && descriptor != null) {
            seenIds.add(descriptor.getId());
            if (hasWorkspace) {
                builder.addSelectableWorkspace(descriptor.getId(), workspaceExtensions.get(0));
            }
            if (agentExtension != null) {
                String agentId = agentExtension.getAgentDescriptor().getId();
                builder.addSelectableAgent(agentId, agentExtension, agentExtension.getAgentDescriptor());
            }
        }

        builder.addEntry(PluginCatalogEntry.builder()
                .pluginId(pluginId)
                .descriptor(descriptor)
                .compatibility(compatibility)
                .pluginState(String.valueOf(wrapper.getPluginState()))
                .location(location)
                .sha256(sha256)
                .enabled(true)
                .build());
    }

    /**
     * A row for a loaded-but-not-started plugin. Its extension/API was deliberately not evaluated (disabled) or
     * its start threw, so no {@link WorkspacePluginDescriptor} is fabricated (the extension — and thus the real
     * plugin-API version — was never loaded); the row is identified by its stable PF4J plugin id and carries an
     * honest {@link PluginCompatibility#NOT_EVALUATED} / {@link PluginCompatibility#START_FAILED} verdict rather
     * than an invented {@code COMPATIBLE}.
     */
    private static PluginCatalogEntry notStartedRow(PluginWrapper wrapper, boolean enabled,
                                                    PluginCompatibility compatibility, PluginLoadFailure failure) {
        return PluginCatalogEntry.builder()
                .pluginId(wrapper.getPluginId())
                .descriptor(null) // not evaluated: never fabricate a descriptor / API version
                .compatibility(compatibility)
                .pluginState(String.valueOf(wrapper.getPluginState()))
                .location(String.valueOf(wrapper.getPluginPath()))
                .sha256(sha256Of(wrapper.getPluginPath()))
                .enabled(enabled)
                .lastError(failure)
                .build();
    }

    /** Bridge an agent descriptor to the catalog's workspace descriptor (same fields) for display/compat. */
    private static WorkspacePluginDescriptor toWorkspaceDescriptor(
            com.aresstack.askai.plugin.api.agent.AgentPluginDescriptor agent) {
        return WorkspacePluginDescriptor.builder()
                .id(agent.getId())
                .displayName(agent.getDisplayName())
                .description(agent.getDescription())
                .version(agent.getVersion())
                .pluginApiVersion(agent.getPluginApiVersion())
                .provider(agent.getProvider())
                .iconKey(agent.getIconKey())
                .displayOrder(agent.getDisplayOrder())
                .build();
    }

    private static String sha256Of(Path path) {
        if (path == null) {
            return "";
        }
        File file = path.toFile();
        if (!file.isFile()) {
            return ""; // dev-mode unpacked plugin directories have no single-file hash
        }
        InputStream in = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(file);
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception ex) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Exception ignored) {
                    // ignore
                }
            }
        }
    }

    private static final class DaemonThreadFactory implements java.util.concurrent.ThreadFactory {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "askai-plugin-discovery");
            thread.setDaemon(true);
            return thread;
        }
    }
}
