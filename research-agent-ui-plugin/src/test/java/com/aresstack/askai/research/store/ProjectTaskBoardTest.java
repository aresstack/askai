package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.runtime.orchestrator.OrchestratorRun;
import com.aresstack.askai.research.runtime.orchestrator.PlannedTask;
import com.aresstack.askai.research.runtime.orchestrator.ResearchOutline;
import com.aresstack.askai.research.runtime.orchestrator.TaskBoard;
import com.aresstack.askai.research.runtime.orchestrator.TaskBriefs;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public class ProjectTaskBoardTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void theRunAndItsTasksLiveInTheProjectFolderAndSurviveANewBoard() throws Exception {
        File tasks = folder.newFolder("tasks");
        File runFile = new File(new File(folder.getRoot(), "orchestrator"), "run.json");
        ProjectTaskBoard board = new ProjectTaskBoard(new FileResearchTaskStore(tasks), runFile);
        assertNull(board.currentRun());
        OrchestratorRun run = OrchestratorRun.builder("run-1", "Why?", 1L)
                .state(OrchestratorRun.State.RUNNING).sections(ResearchOutline.ofQuestion("Why?").getSections())
                .build();
        board.saveRun(run);
        board.save(ResearchTask.submit(run.taskIdOf("p1"), "research", "web-researcher", null,
                TaskBriefs.inputsOf(new PlannedTask("p1", "web-researcher", "q", "", Collections.singletonList(
                        ResearchOutline.QUESTION_SECTION), null)), "judge", 0, TaskBudget.unlimited(), 1, 2L));

        ProjectTaskBoard reopened = new ProjectTaskBoard(new FileResearchTaskStore(tasks), runFile);
        assertEquals(OrchestratorRun.State.RUNNING, reopened.currentRun().getState());
        assertEquals(1, reopened.tasks().size());
        assertEquals("q", TaskBriefs.queryOf(reopened.tasks().get(0).getInputs()));
    }

    @Test
    public void aBrokenRunRecordIsAVisibleErrorNotAnEmptyBoard() throws Exception {
        File runFile = new File(folder.newFolder("orchestrator"), "run.json");
        Files.write(runFile.toPath(), "{ not json".getBytes(StandardCharsets.UTF_8));
        try {
            new ProjectTaskBoard(new FileResearchTaskStore(folder.newFolder("tasks")), runFile).currentRun();
            fail();
        } catch (TaskBoard.TaskBoardException expected) {
            assertEquals(true, expected.getMessage().contains("run.json"));
        }
    }
}
