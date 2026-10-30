package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRunCodec;
import com.aresstack.askai.research.runtime.orchestrator.TaskBoard;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * The orchestrated run's BLACKBOARD over this project's folder (ADR 0001, 4.3): the tasks in the project's
 * task store ({@code tasks/}, lane E) and the run record in {@code orchestrator/run.json}. Both are written
 * atomically as they change, so a closed session leaves exactly what the run had done, and the State tab
 * shows the same tasks the executor works on.
 */
public final class ProjectTaskBoard implements TaskBoard {

    private final FileResearchTaskStore tasks;
    private final File runFile;

    public ProjectTaskBoard(ResearchProjectContext context) {
        this(context.getTaskStore(), new File(new File(context.getProjectDirectory(), "orchestrator"), "run.json"));
    }

    ProjectTaskBoard(FileResearchTaskStore tasks, File runFile) {
        this.tasks = tasks;
        this.runFile = runFile;
    }

    @Override
    public List<ResearchTask> tasks() {
        return tasks.loadAll().getTasks();
    }

    @Override
    public void save(ResearchTask task) {
        try {
            tasks.save(task);
        } catch (IOException failed) {
            throw new TaskBoardException("task " + task.getTaskId() + " could not be saved: "
                    + failed.getMessage(), failed);
        }
    }

    @Override
    public synchronized OrchestratorRun currentRun() {
        if (!runFile.isFile()) {
            return null;
        }
        try {
            return OrchestratorRunCodec.decode(StoreIo.readUtf8(runFile));
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new TaskBoardException("the run record " + runFile.getName() + " is unreadable: "
                    + unreadable.getMessage(), unreadable);
        }
    }

    @Override
    public synchronized void saveRun(OrchestratorRun run) {
        try {
            StoreIo.atomicWrite(runFile, OrchestratorRunCodec.encode(run) + "\n");
        } catch (IOException failed) {
            throw new TaskBoardException("the run record could not be saved: " + failed.getMessage(), failed);
        }
    }
}
