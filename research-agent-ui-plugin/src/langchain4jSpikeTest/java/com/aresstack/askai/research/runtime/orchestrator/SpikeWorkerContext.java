package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ModelPort;
import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.askai.research.runtime.team.MainModelChat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One planner task as the executor hands it to a worker: the chat transport is the PRODUCT one
 * ({@link RoleChats#overPort}, metered on {@link #tokens}), so the original twin runs exactly as in a run.
 */
final class SpikeWorkerContext implements WorkerContext {

    final ResearchTask task;
    final List<ResearchTask> run = new ArrayList<ResearchTask>();
    final List<String> logs = new ArrayList<String>();
    final AtomicLong tokens = new AtomicLong();
    ResearchTask recorded;
    private final MainModelChat chat;
    private long clock = 100L;

    SpikeWorkerContext(String aspect, String brief, ModelPort port, Duration timeout) {
        this.task = ResearchTask.submit("run.plan", "research", SearchPlannerWorker.ROLE.getId(),
                Collections.<String>emptyList(), TaskBriefs.inputsOf(new PlannedTask("plan",
                        SearchPlannerWorker.ROLE.getId(), aspect, brief, Collections.singletonList("s1"),
                        Collections.<String>emptyList())), "", 0, TaskBudget.unlimited(), 1, 1L)
                .start("local", 2L);
        this.recorded = task;
        this.chat = RoleChats.overPort(port, timeout).chatFor(SearchPlannerWorker.ROLE, tokens);
    }

    /** A completed web research package of the run that already searched {@code query}. */
    SpikeWorkerContext searched(String query) {
        ResearchTask web = ResearchTask.submit("run.web" + run.size(), "research", WebResearchWorker.ROLE.getId(),
                Collections.<String>emptyList(), TaskBriefs.inputsOf(new PlannedTask("web" + run.size(),
                        WebResearchWorker.ROLE.getId(), query, "", Collections.singletonList("s1"),
                        Collections.<String>emptyList())), "", 0, TaskBudget.unlimited(), 1, 1L)
                .start("local", 2L).complete(3L);
        run.add(web);
        return this;
    }

    List<String> plannedQueries() {
        return WorkerArtifacts.plannedQueries(Collections.singletonList(recorded));
    }

    public String query() {
        return TaskBriefs.queryOf(task.getInputs());
    }

    public String brief() {
        return TaskBriefs.briefOf(task.getInputs());
    }

    public List<ResearchTask> runTasks() {
        List<ResearchTask> all = new ArrayList<ResearchTask>(run);
        all.add(recorded);
        return all;
    }

    public MainModelChat chat() {
        return chat;
    }

    public ToolInvoker permitted(ToolInvoker lane) {
        return lane;
    }

    public void artifact(TaskArtifact.Kind kind, String content, String projectRef) {
        recorded = recorded.withArtifact(new TaskArtifact(recorded.getTaskId() + ".a"
                + (recorded.getArtifacts().size() + 1), kind, content, projectRef, ++clock));
    }

    public void receipt(String toolName, boolean succeeded, String receipt) {
    }

    public void log(String line) {
        logs.add(line);
    }

    public boolean isCancelled() {
        return false;
    }

    public boolean isBudgetExhausted() {
        return false;
    }

    public AtomicLong tokens() {
        return tokens;
    }
}
