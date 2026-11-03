package com.aresstack.askai.research.state;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The resume rules of ADR 0001, section 4.4, applied to the persisted task graph of a run after an
 * interruption (app restart, crash, canceled run). Pure and deterministic: it takes the stored tasks and the
 * caller's clock and returns what the graph looks like before the executor schedules anything again.
 * <ul>
 *   <li>{@code completed} (and every other terminal state) is left exactly as it is — never re-executed;</li>
 *   <li>{@code working} was interrupted mid-attempt: it is re-queued, which counts a retry, while its retry
 *       limit allows; once the limit is spent it is failed instead;</li>
 *   <li>{@code input-required} keeps waiting — only the user answers it;</li>
 *   <li>{@code submitted} stays queued;</li>
 *   <li>budgets carry over unchanged: what was spent before the interruption stays spent.</li>
 * </ul>
 * Every change goes through the {@link ResearchTask} transition methods, so the resume pass cannot invent a
 * transition the domain table does not allow. It never touches a research phase.
 */
public final class ResearchTaskResumption {

    /** The reason recorded on the transition of a task that was found working after an interruption. */
    public static final String INTERRUPTED = "interrupted";

    private ResearchTaskResumption() {
    }

    public static Outcome resume(List<ResearchTask> stored, long nowMillis) {
        List<ResearchTask> tasks = new ArrayList<ResearchTask>();
        List<ResearchTask> changed = new ArrayList<ResearchTask>();
        List<String> requeued = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        List<String> awaitingInput = new ArrayList<String>();
        if (stored != null) {
            for (ResearchTask task : stored) {
                if (task == null) {
                    continue;
                }
                ResearchTask resumed = task;
                if (task.getState() == TaskState.WORKING) {
                    // The stored clock may be ahead of this one (another machine, a clock change); the
                    // history must not run backwards, so the resume step is stamped no earlier than the last.
                    long at = Math.max(nowMillis, task.getUpdatedAtMillis());
                    if (task.hasRetriesLeft()) {
                        resumed = task.requeue(INTERRUPTED, at);
                        requeued.add(task.getTaskId());
                    } else {
                        resumed = task.fail(INTERRUPTED + "; retry limit " + task.getRetryLimit() + " spent", at);
                        failed.add(task.getTaskId());
                    }
                    changed.add(resumed);
                } else if (task.getState() == TaskState.INPUT_REQUIRED) {
                    awaitingInput.add(task.getTaskId());
                }
                tasks.add(resumed);
            }
        }
        return new Outcome(tasks, changed, requeued, failed, awaitingInput);
    }

    /** The task graph after the resume pass and what the pass did to it. */
    public static final class Outcome {

        private final List<ResearchTask> tasks;
        private final List<ResearchTask> changedTasks;
        private final List<String> requeuedTaskIds;
        private final List<String> failedTaskIds;
        private final List<String> awaitingInputTaskIds;

        Outcome(List<ResearchTask> tasks, List<ResearchTask> changedTasks, List<String> requeuedTaskIds,
                List<String> failedTaskIds, List<String> awaitingInputTaskIds) {
            this.tasks = Collections.unmodifiableList(tasks);
            this.changedTasks = Collections.unmodifiableList(changedTasks);
            this.requeuedTaskIds = Collections.unmodifiableList(requeuedTaskIds);
            this.failedTaskIds = Collections.unmodifiableList(failedTaskIds);
            this.awaitingInputTaskIds = Collections.unmodifiableList(awaitingInputTaskIds);
        }

        /** Every task in its resumed version, in the order given. */
        public List<ResearchTask> getTasks() {
            return tasks;
        }

        /** Only the tasks the pass changed — the ones a store must persist again. */
        public List<ResearchTask> getChangedTasks() {
            return changedTasks;
        }

        public List<String> getRequeuedTaskIds() {
            return requeuedTaskIds;
        }

        /** Working tasks whose retry limit was already spent. */
        public List<String> getFailedTaskIds() {
            return failedTaskIds;
        }

        public List<String> getAwaitingInputTaskIds() {
            return awaitingInputTaskIds;
        }

        /**
         * The submitted tasks whose dependencies are all completed: what the executor may hand to the resource
         * scheduler next. Tasks waiting for a dependency, for input or already finished are not in it.
         */
        public List<String> getReadyTaskIds() {
            Map<String, TaskState> states = new LinkedHashMap<String, TaskState>();
            for (ResearchTask task : tasks) {
                states.put(task.getTaskId(), task.getState());
            }
            List<String> ready = new ArrayList<String>();
            for (ResearchTask task : tasks) {
                if (task.getState() == TaskState.SUBMITTED && task.dependenciesSatisfiedBy(states)) {
                    ready.add(task.getTaskId());
                }
            }
            return Collections.unmodifiableList(ready);
        }
    }
}
