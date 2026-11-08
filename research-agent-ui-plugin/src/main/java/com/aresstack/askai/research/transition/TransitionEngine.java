package com.aresstack.askai.research.transition;

import com.aresstack.askai.research.state.ResearchCommand;
import com.aresstack.askai.research.state.ResearchCommandType;
import com.aresstack.askai.research.state.oo.OoResearchStateMachine;
import com.aresstack.askai.research.state.oo.ResearchStateIds;
import com.aresstack.askai.research.state.oo.ResearchStateMemento;
import com.aresstack.askai.research.state.oo.ResearchStateTransitionResult;
import com.aresstack.askai.research.store.SessionStateFileStore;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one transition chokepoint of a productive research session (D2, D3). {@link #dispatch} checks the
 * actor policy, the revision the sender saw and every hook, then writes in a fixed order:
 * <ol>
 * <li>a {@code prepared} record in the transition journal (the whole transition with its effect names),</li>
 * <li>the hooks' records (pins, approvals), each carrying the transition id,</li>
 * <li>the memento with the id of its last transition: the commit point,</li>
 * <li>a {@code committed} record,</li>
 * <li>a {@code done} record per effect, once the effect ran on the worker thread.</li>
 * </ol>
 * {@link #recover} runs on session open before any job or command: a {@code prepared} transition that the
 * memento does not name never happened; one it names without {@code committed} is committed now; every open
 * effect runs again. The chained {@code START_RESEARCH} (after {@code SUBMIT_SCOPE}) and
 * {@code START_DRAFTING} (after {@code APPROVE_OUTLINE}) are the approval's last effects and carry its actor
 * and correlation id; they are dispatched only while the state still is the approval's target and no later
 * transition committed, otherwise they are closed as obsolete.
 */
public final class TransitionEngine {

    public static final String OUTDATED_REVISION =
            "The workflow changed. Check the current state and try again.";
    /** Effect name prefix of the chained start of an approval. */
    public static final String CHAIN_EFFECT_PREFIX = "chain:";

    private final OoResearchStateMachine machine;
    private final SessionStateFileStore stateStore;
    private final TransitionJournal journal;
    private final List<PhaseTransitionHook> hooks = new CopyOnWriteArrayList<PhaseTransitionHook>();
    private final Map<String, TransitionEffect> effects = new ConcurrentHashMap<String, TransitionEffect>();
    private final List<TransitionListener> listeners = new CopyOnWriteArrayList<TransitionListener>();
    private final AtomicInteger queuedChains = new AtomicInteger();
    private final AtomicInteger pendingEffects = new AtomicInteger();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "research-transition-effects");
            thread.setDaemon(true);
            return thread;
        }
    });
    private volatile TransitionFaults faults = TransitionFaults.NONE;
    private volatile ResearchStateMemento state;
    private volatile String lastTransitionId;
    private volatile String blockedReason;
    private volatile boolean halted;
    private volatile boolean recovered;
    private final Set<String> committedIds = Collections.synchronizedSet(new HashSet<String>());

    /**
     * Open the engine over a project's state directory. A missing memento starts at the initial state and is
     * written at once; an unreadable memento or journal is kept as it is (plus a copy aside) and blocks the
     * engine with a repair hint, so the project is never reset to {@code scoping/new}.
     */
    public TransitionEngine(OoResearchStateMachine machine, SessionStateFileStore stateStore, File journalFile) {
        this.machine = machine;
        this.stateStore = stateStore;
        this.journal = new TransitionJournal(journalFile);
        SessionStateFileStore.StoredState stored = null;
        try {
            stored = stateStore.loadStored();
        } catch (CorruptProjectFileException corrupt) {
            block(corrupt);
        }
        if (blockedReason == null) {
            try {
                for (JsonObject record : journal.read()) {
                    if ("committed".equals(kind(record))) {
                        committedIds.add(record.get("id").getAsString());
                    }
                }
            } catch (CorruptProjectFileException corrupt) {
                block(corrupt);
            }
        }
        if (stored != null) {
            state = stored.getMemento();
            lastTransitionId = stored.getLastTransitionId();
        } else {
            state = machine.initialMemento();
            if (blockedReason == null) {
                try {
                    stateStore.save(state, null);
                } catch (IOException persistFailed) {
                    // FAIL-CLOSED: a productive context whose state store cannot write never activates
                    throw new IllegalStateException("The research project state store is not writable ("
                            + persistFailed.getMessage() + ") - the productive session must not start",
                            persistFailed);
                }
            }
        }
    }

    private void block(CorruptProjectFileException corrupt) {
        File copy = AtomicFiles.keepAside(corrupt.getFile());
        blockedReason = "This project cannot be opened: " + corrupt.getMessage() + ". The file was kept"
                + (copy == null ? "" : " (copy: " + copy.getName() + ")")
                + ". Repair or restore " + corrupt.getFile().getName()
                + " from a backup, then reopen the chat. Nothing was started.";
    }

    // ------------------------------------------------------------------ wiring

    public void addHook(PhaseTransitionHook hook) {
        hooks.add(hook);
    }

    public void registerEffect(String name, TransitionEffect effect) {
        effects.put(name, effect);
    }

    public void addListener(TransitionListener listener) {
        listeners.add(listener);
    }

    /** Test seam: inject a crash after a write step. */
    public void setFaults(TransitionFaults faults) {
        this.faults = faults == null ? TransitionFaults.NONE : faults;
    }

    // ------------------------------------------------------------------ state

    public ResearchStateMemento currentState() {
        return state;
    }

    public String lastTransitionId() {
        return lastTransitionId;
    }

    /** The repair hint when an unreadable project file blocks the session, else {@code null}. */
    public String blockedReason() {
        return blockedReason;
    }

    public boolean isCommitted(String transitionId) {
        return transitionId != null && committedIds.contains(transitionId);
    }

    /** Whether a chained {@code START_*} of an approval is still queued (then no "continue" is offered). */
    public boolean hasQueuedChainStart() {
        return queuedChains.get() > 0;
    }

    public File journalFile() {
        return journal.getFile();
    }

    /** The journal's complete records (for the timeline and tests). */
    public List<JsonObject> journalRecords() throws CorruptProjectFileException {
        return journal.read();
    }

    // ------------------------------------------------------------------ dispatch

    public TransitionOutcome dispatch(ResearchCommand command) {
        TransitionOutcome outcome = dispatchLocked(command);
        if (outcome.isCommitted()) {
            for (TransitionListener listener : listeners) {
                try {
                    listener.committed(outcome.getTransition());
                } catch (RuntimeException ignored) {
                    // a listener never undoes a committed transition
                }
            }
        }
        return outcome;
    }

    private synchronized TransitionOutcome dispatchLocked(ResearchCommand command) {
        ResearchStateMemento current = state;
        if (blockedReason != null) {
            return TransitionOutcome.refused(TransitionOutcome.Kind.BLOCKED, blockedReason, current);
        }
        String actorRefusal = ActorPolicy.refusal(command.getType(), command.getActor());
        if (actorRefusal != null) {
            return TransitionOutcome.refused(TransitionOutcome.Kind.ACTOR_REFUSED, actorRefusal, current);
        }
        if (command.getSeenRevision() != ResearchCommand.NO_REVISION
                && command.getSeenRevision() != current.getRevision()) {
            return TransitionOutcome.refused(TransitionOutcome.Kind.OUTDATED, OUTDATED_REVISION, current);
        }
        ResearchStateTransitionResult result = machine.dispatch(current, command);
        if (!result.isAccepted()) {
            return TransitionOutcome.refused(TransitionOutcome.Kind.INVALID_STATE,
                    result.getRejectionReason(), current);
        }
        PhaseTransition transition = new PhaseTransition("t-" + UUID.randomUUID(), command.getType(),
                current, result.getNextMemento(), command.getActor(), command.getSurface(),
                command.getCorrelationId(), command.getSeenRevision(), command.getComment(),
                System.currentTimeMillis(), Collections.<String>emptyList());
        for (PhaseTransitionHook hook : hooks) {
            String refusal = hook.check(transition);
            if (refusal != null) {
                return TransitionOutcome.refused(TransitionOutcome.Kind.HOOK_REFUSED, refusal, current);
            }
        }
        List<String> effectNames = new ArrayList<String>();
        for (PhaseTransitionHook hook : hooks) {
            effectNames.addAll(hook.effects(transition));
        }
        ResearchCommandType chained = chainedStart(command.getType());
        if (chained != null) {
            effectNames.add(CHAIN_EFFECT_PREFIX + chained.name()); // always the approval's LAST effect
        }
        transition = transition.withEffects(effectNames);
        // (1) prepared
        try {
            journal.dropTornTail();
            journal.append(record("prepared", transition.getId(), transition.toJson()));
        } catch (IOException failed) {
            return TransitionOutcome.refused(TransitionOutcome.Kind.NOT_PERSISTED,
                    "state transition not journalled (" + failed.getMessage()
                            + ") - the previous state stays active", current);
        }
        faults.after("prepared", transition);
        // (2) the hooks' records
        for (PhaseTransitionHook hook : hooks) {
            try {
                hook.record(transition);
            } catch (IOException failed) {
                return TransitionOutcome.refused(TransitionOutcome.Kind.HOOK_REFUSED,
                        "the approval could not be recorded (" + failed.getMessage()
                                + ") - the previous state stays active", current);
            }
        }
        // (3) the memento: the commit point
        try {
            stateStore.save(transition.getTo(), transition.getId());
        } catch (IOException failed) {
            return TransitionOutcome.refused(TransitionOutcome.Kind.NOT_PERSISTED,
                    "state transition not persisted (" + failed.getMessage()
                            + ") - the previous state stays active", current);
        }
        state = transition.getTo();
        lastTransitionId = transition.getId();
        faults.after("memento", transition);
        // (4) committed
        commitRecord(transition);
        faults.after("committed", transition);
        enqueueEffects(transition, transition.getEffects());
        return TransitionOutcome.committed(transition);
    }

    private void commitRecord(PhaseTransition transition) {
        JsonObject body = new JsonObject();
        JsonArray open = new JsonArray();
        for (String effect : transition.getEffects()) {
            open.add(effect);
        }
        body.add("openEffects", open);
        try {
            journal.append(record("committed", transition.getId(), body));
        } catch (IOException failed) {
            // the memento already names the transition: the next recovery writes the committed record
            System.err.println("[transition] committed record not written for " + transition.getId()
                    + ": " + failed.getMessage());
        }
        committedIds.add(transition.getId());
    }

    private static ResearchCommandType chainedStart(ResearchCommandType command) {
        if (command == ResearchCommandType.SUBMIT_SCOPE) {
            return ResearchCommandType.START_RESEARCH;
        }
        if (command == ResearchCommandType.APPROVE_OUTLINE) {
            return ResearchCommandType.START_DRAFTING;
        }
        return null;
    }

    // ------------------------------------------------------------------ effects

    private void enqueueEffects(final PhaseTransition transition, final List<String> names) {
        if (names.isEmpty()) {
            return;
        }
        for (String name : names) {
            if (name.startsWith(CHAIN_EFFECT_PREFIX)) {
                queuedChains.incrementAndGet();
            }
        }
        pendingEffects.incrementAndGet();
        worker.execute(new Runnable() {
            public void run() {
                try {
                    for (String name : names) {
                        if (halted) {
                            return;
                        }
                        runEffect(transition, name);
                    }
                } catch (Error crash) {
                    halted = true; // a simulated crash: nothing after it runs in this process
                } finally {
                    for (String name : names) {
                        if (name.startsWith(CHAIN_EFFECT_PREFIX)) {
                            queuedChains.decrementAndGet();
                        }
                    }
                    pendingEffects.decrementAndGet();
                }
            }
        });
    }

    private void runEffect(PhaseTransition transition, String name) {
        String outcome = "done";
        try {
            if (name.startsWith(CHAIN_EFFECT_PREFIX)) {
                outcome = runChain(transition,
                        ResearchCommandType.valueOf(name.substring(CHAIN_EFFECT_PREFIX.length())));
            } else {
                TransitionEffect effect = effects.get(name);
                if (effect == null) {
                    outcome = "skipped: no handler";
                } else {
                    effect.run(transition);
                }
            }
        } catch (Exception failed) {
            outcome = "failed: " + failed.getMessage();
            System.err.println("[transition] effect " + name + " of " + transition.getId() + " failed: "
                    + failed.getMessage());
        }
        faults.after("effect:" + name, transition);
        JsonObject body = new JsonObject();
        body.addProperty("effect", name);
        body.addProperty("outcome", outcome);
        try {
            journal.append(record("done", transition.getId(), body));
        } catch (IOException failed) {
            System.err.println("[transition] done record not written for " + name + ": " + failed.getMessage());
        }
    }

    private String runChain(PhaseTransition approval, ResearchCommandType start) {
        synchronized (this) {
            ResearchStateMemento current = state;
            boolean stillAtTarget = approval.getId().equals(lastTransitionId)
                    && current.getPhaseId().equals(approval.getTo().getPhaseId())
                    && ResearchStateIds.WAITING.equals(current.getStateId());
            if (!stillAtTarget) {
                return "obsolete";
            }
        }
        // after the lock: dispatch() takes it again and tells the listeners
        TransitionOutcome outcome = dispatch(new ResearchCommand(start, "chain-" + approval.getId(),
                approval.getActor(), approval.getSurface(), approval.getCorrelationId(),
                ResearchCommand.NO_REVISION, ""));
        return outcome.isCommitted() ? "dispatched " + outcome.getTransition().getId()
                : "refused: " + outcome.getReason();
    }

    /** Test/teardown helper: wait until every queued effect ran. */
    public boolean awaitEffects(long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (pendingEffects.get() > 0) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(5L);
        }
        return true;
    }

    // ------------------------------------------------------------------ recovery

    /**
     * The recovery pass of D3; idempotent, runs the open effects synchronously on the caller's thread.
     * @return a short report (for the technical log), or the blocked reason.
     */
    public String recover() {
        if (blockedReason != null) {
            return blockedReason;
        }
        if (recovered) {
            return "already recovered";
        }
        recovered = true;
        List<JsonObject> records;
        try {
            journal.dropTornTail();
            records = journal.read();
        } catch (Exception corrupt) {
            return "journal unreadable: " + corrupt.getMessage();
        }
        Map<String, PhaseTransition> prepared = new LinkedHashMap<String, PhaseTransition>();
        Set<String> committed = new LinkedHashSet<String>();
        Map<String, Set<String>> done = new LinkedHashMap<String, Set<String>>();
        for (JsonObject record : records) {
            String id = record.get("id").getAsString();
            String kind = kind(record);
            if ("prepared".equals(kind)) {
                prepared.put(id, PhaseTransition.fromJson(record.getAsJsonObject("transition")));
            } else if ("committed".equals(kind)) {
                committed.add(id);
            } else if ("done".equals(kind)) {
                Set<String> set = done.get(id);
                if (set == null) {
                    set = new HashSet<String>();
                    done.put(id, set);
                }
                set.add(record.get("effect").getAsString());
            }
        }
        int committedNow = 0;
        int rerun = 0;
        int ignored = 0;
        for (PhaseTransition transition : prepared.values()) {
            if (!committed.contains(transition.getId())) {
                if (transition.getId().equals(lastTransitionId)) {
                    commitRecord(transition);
                    committed.add(transition.getId());
                    committedNow++;
                } else {
                    ignored++; // the memento never named it: it never happened
                    continue;
                }
            }
            Set<String> ran = done.get(transition.getId());
            for (String effect : transition.getEffects()) {
                if (ran == null || !ran.contains(effect)) {
                    runEffect(transition, effect);
                    rerun++;
                }
            }
        }
        return "recovered: committed " + committedNow + ", effects re-run " + rerun
                + ", never committed " + ignored;
    }

    public void close() {
        worker.shutdownNow();
    }

    // ------------------------------------------------------------------ json

    private static JsonObject record(String kind, String id, JsonObject body) {
        JsonObject record = new JsonObject();
        record.addProperty("kind", kind);
        record.addProperty("id", id);
        if ("prepared".equals(kind)) {
            record.add("transition", body);
        } else {
            for (Map.Entry<String, com.google.gson.JsonElement> entry : body.entrySet()) {
                record.add(entry.getKey(), entry.getValue());
            }
        }
        return record;
    }

    private static String kind(JsonObject record) {
        return record.get("kind").getAsString();
    }

    /** Test helper. */
    boolean awaitTermination(long millis) throws InterruptedException {
        worker.shutdown();
        return worker.awaitTermination(millis, TimeUnit.MILLISECONDS);
    }
}
