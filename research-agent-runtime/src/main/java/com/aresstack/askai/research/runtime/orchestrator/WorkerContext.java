package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.team.MainModelChat;

/**
 * What the {@link TaskExecutor} hands a {@link TaskWorker} for one attempt. Everything a worker records goes
 * onto the task and the board immediately, so a failed or interrupted attempt keeps its partial results
 * (ADR 0001, 4.4).
 */
public interface WorkerContext {

    /** The task's search query (from the orchestrator's brief). */
    String query();

    /** The orchestrator's brief for the task, without the query line. */
    String brief();

    /**
     * The worker role's chat transport; its token usage is booked on this task's budget. Workers that call a
     * model call it only through here.
     */
    MainModelChat chat();

    /**
     * {@code lane} restricted to the worker role's tool catalog (permissions per role, ADR 4.2): a call
     * outside the catalog fails with a {@link ToolInvoker.ToolFailure} and never reaches the host.
     */
    ToolInvoker permitted(ToolInvoker lane);

    /** Records one artifact on the task (and persists it). */
    void artifact(TaskArtifact.Kind kind, String content, String projectRef);

    /** Records the receipt of one tool call on the task (and persists it). */
    void receipt(String toolName, boolean succeeded, String receipt);

    /** A short progress line for the user's technical log. */
    void log(String line);

    /** True once the user stopped the run or the session closes; a worker ends its attempt promptly. */
    boolean isCancelled();

    /**
     * True once the task's own budget (time or tokens) is spent; a worker ends its attempt with what it has.
     */
    boolean isBudgetExhausted();

    /**
     * The task's token account: model calls a worker makes outside {@link #chat()} (e.g. the search run's
     * layout repair) are metered onto it, so they count against the task and the run budget.
     */
    java.util.concurrent.atomic.AtomicLong tokens();
}
