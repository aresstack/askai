package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskInputs;
import com.aresstack.askai.research.domain.task.TaskReceipt;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.domain.task.TaskTransition;
import com.aresstack.askai.research.state.ResearchTaskResumption;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * ADR 0001, section 4.4: the task model is persisted in the project store as it changes, survives a restart
 * byte for byte in meaning, and the resume pass after an interruption follows the ADR rules — completed never
 * re-run, working re-queued within the retry limit, input-required waits, budgets carry over.
 */
public class FileResearchTaskStoreTest {

    private static File tempDir() throws Exception {
        return Files.createTempDirectory("askai-task-store").toFile();
    }

    private static ResearchTask submitted(String id, int retryLimit, String... dependsOn) {
        return ResearchTask.submit(id, "research", "web-research", Arrays.asList(dependsOn),
                new TaskInputs("Find primary sources on " + id, Arrays.asList("outline#s1", "source-7")),
                "judge", 8192, TaskBudget.of(60_000L, 4_000L), retryLimit, 1_000L);
    }

    @Test
    public void everyFieldOfATaskRoundTrips() throws Exception {
        File dir = tempDir();
        ResearchTask task = submitted("t-1", 2)
                .start("ollama@localhost:11434", 2_000L)
                .withArtifact(new TaskArtifact("a-1", TaskArtifact.Kind.SOURCE_REFERENCE, "found it",
                        "source-9", 2_100L))
                .withArtifact(new TaskArtifact("a-2", TaskArtifact.Kind.TEXT, "multi\nline \"quoted\" <text>",
                        "", 2_200L))
                .withReceipt(new TaskReceipt("web_search", true, "{\"accepted\":3}", 2_150L))
                .withSpending(1_500L, 900L)
                .requeue("timeout", 3_000L)
                .start("ollama@gpu-2:11434", 3_500L)
                .complete(4_000L);

        new FileResearchTaskStore(dir).save(task);
        ResearchTask restored = new FileResearchTaskStore(dir).load("t-1");

        assertEquals("t-1", restored.getTaskId());
        assertEquals("research", restored.getPhase());
        assertEquals("web-research", restored.getRole());
        assertEquals(TaskState.COMPLETED, restored.getState());
        assertEquals("Find primary sources on t-1", restored.getInputs().getBrief());
        assertEquals(Arrays.asList("outline#s1", "source-7"), restored.getInputs().getArtifactRefs());
        assertEquals(2, restored.getArtifacts().size());
        assertEquals(TaskArtifact.Kind.SOURCE_REFERENCE, restored.getArtifacts().get(0).getKind());
        assertEquals("source-9", restored.getArtifacts().get(0).getProjectRef());
        assertEquals("multi\nline \"quoted\" <text>", restored.getArtifacts().get(1).getContent());
        assertEquals(2_200L, restored.getArtifacts().get(1).getCreatedAtMillis());
        assertEquals("judge", restored.getModelSlot());
        assertEquals(8192, restored.getContextSize());
        assertEquals(60_000L, restored.getBudget().getTimeLimitMillis());
        assertEquals(4_000L, restored.getBudget().getTokenLimit());
        assertEquals(1_500L, restored.getBudget().getTimeUsedMillis());
        assertEquals(900L, restored.getBudget().getTokensUsed());
        assertEquals(1, restored.getRetryCount());
        assertEquals(2, restored.getRetryLimit());
        assertEquals(1, restored.getReceipts().size());
        assertEquals("web_search", restored.getReceipts().get(0).getToolName());
        assertTrue(restored.getReceipts().get(0).isSucceeded());
        assertEquals("{\"accepted\":3}", restored.getReceipts().get(0).getReceipt());
        assertEquals("the full history survives", 5, restored.getTransitions().size());
        assertNull(restored.getTransitions().get(0).getFrom());
        assertEquals("timeout", restored.getTransitions().get(2).getReason());
        assertEquals("ollama@gpu-2:11434", restored.getEndpoint());
        assertEquals(1_000L, restored.getCreatedAtMillis());
        assertEquals(2_000L, restored.getStartedAtMillis());
        assertEquals(4_000L, restored.getFinishedAtMillis());
    }

    @Test
    public void dependenciesAndOrderSurviveAndTheFileIsTheOneJsonPerTaskLayout() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(ResearchTask.submit("t-2", "research", "source-verifier",
                Collections.singletonList("t-1"), null, "", 0, null, 1, 5_000L));
        store.save(submitted("t-1", 1));

        List<ResearchTask> tasks = store.loadAll().getTasks();
        assertEquals("oldest submission first", "t-1", tasks.get(0).getTaskId());
        assertEquals(Collections.singletonList("t-1"), tasks.get(1).getDependsOn());
        assertTrue(new File(dir, "t-1.json").isFile());
        String json = new String(Files.readAllBytes(new File(dir, "t-1.json").toPath()), StandardCharsets.UTF_8);
        assertTrue("states use their wire names", json.contains("\"state\": \"submitted\""));
    }

    @Test
    public void saveReplacesTheEarlierVersionOfTheSameTask() throws Exception {
        FileResearchTaskStore store = new FileResearchTaskStore(tempDir());
        ResearchTask task = submitted("t-1", 1);
        store.save(task);
        store.save(task.start("local", 1_500L));

        assertEquals(1, store.loadAll().getTasks().size());
        assertEquals(TaskState.WORKING, store.load("t-1").getState());
    }

    @Test
    public void aFileThatBreaksTheTransitionRulesIsRejectedAndReported() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(submitted("good", 1));
        // completed -> working is not in the table: a store edited by hand must not resurrect a task
        com.google.gson.JsonObject json = FileResearchTaskStore.toJson(
                submitted("bad", 1).start("local", 2_000L).complete(3_000L));
        com.google.gson.JsonObject resurrect = new com.google.gson.JsonObject();
        resurrect.addProperty("from", "completed");
        resurrect.addProperty("to", "working");
        resurrect.addProperty("atMillis", 4_000L);
        resurrect.addProperty("reason", "");
        json.getAsJsonArray("transitions").add(resurrect);
        json.addProperty("state", "working");
        Files.write(new File(dir, "bad.json").toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
        Files.write(new File(dir, "garbage.json").toPath(), "{not json".getBytes(StandardCharsets.UTF_8));

        FileResearchTaskStore.TaskLoadResult result = store.loadAll();
        assertEquals(1, result.getTasks().size());
        assertEquals("good", result.getTasks().get(0).getTaskId());
        assertTrue(result.getRejected().containsKey("bad.json"));
        assertTrue(result.getRejected().containsKey("garbage.json"));
        assertNull(store.load("bad"));
    }

    @Test
    public void anUnknownSchemaVersionIsRejectedNotGuessed() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(submitted("t-1", 1));
        File file = new File(dir, "t-1.json");
        String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        Files.write(file.toPath(), json.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
                .getBytes(StandardCharsets.UTF_8));

        assertTrue(store.loadAll().getTasks().isEmpty());
        assertTrue(store.loadAll().getRejected().get("t-1.json").contains("99"));
    }

    @Test
    public void taskIdsThatAreNoSafeFileNamesAreEncoded() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(ResearchTask.submit("../x/y z", "research", "writer", null, null, "", 0, null, 0, 1L));

        assertEquals("../x/y z", store.load("../x/y z").getTaskId());
        String[] names = dir.list();
        assertEquals(1, names.length);
        assertFalse(names[0].contains("/"));
        assertFalse(names[0].startsWith("."));
    }

    @Test
    public void idsDifferingOnlyInCaseNeverShareAFile() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(submitted("task-A", 1));
        store.save(submitted("task-a", 2));

        String[] names = dir.list();
        assertEquals(2, names.length);
        assertFalse("distinct even on a case-insensitive file system",
                names[0].equalsIgnoreCase(names[1]));
        assertEquals(1, store.load("task-A").getRetryLimit());
        assertEquals(2, store.load("task-a").getRetryLimit());
        assertEquals(2, store.loadAll().getTasks().size());
    }

    @Test
    public void windowsDeviceNamesAreNotUsedAsFileNames() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(submitted("con", 1));
        store.save(submitted("nul.x", 1));

        for (String name : dir.list()) {
            assertFalse(name, name.toLowerCase().matches("(con|nul)(\\..*)?"));
        }
        assertEquals("con", store.load("con").getTaskId());
        assertEquals("nul.x", store.load("nul.x").getTaskId());
    }

    @Test
    public void aMissingOrMalformedBudgetIsRejectedNotTurnedIntoUnlimited() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);

        com.google.gson.JsonObject missing = FileResearchTaskStore.toJson(submitted("missing", 1));
        missing.remove("budget");
        com.google.gson.JsonObject partial = FileResearchTaskStore.toJson(submitted("partial", 1));
        partial.getAsJsonObject("budget").remove("tokenLimit");
        com.google.gson.JsonObject nulled = FileResearchTaskStore.toJson(submitted("nulled", 1));
        nulled.add("budget", com.google.gson.JsonNull.INSTANCE);
        com.google.gson.JsonObject wrongType = FileResearchTaskStore.toJson(submitted("wrong", 1));
        wrongType.addProperty("retryLimit", "many");
        for (com.google.gson.JsonObject json : Arrays.asList(missing, partial, nulled, wrongType)) {
            Files.write(new File(dir, json.get("taskId").getAsString() + ".json").toPath(),
                    json.toString().getBytes(StandardCharsets.UTF_8));
        }

        FileResearchTaskStore.TaskLoadResult result = store.loadAll();
        assertTrue(result.getTasks().isEmpty());
        assertEquals(4, result.getRejected().size());
        assertTrue(result.getRejected().get("missing.json").contains("budget"));
        assertTrue(result.getRejected().get("partial.json").contains("tokenLimit"));
        assertNull(store.load("nulled"));
    }

    @Test
    public void anEmptyOrMissingDirectoryHoldsNoTasks() throws Exception {
        FileResearchTaskStore store = new FileResearchTaskStore(new File(tempDir(), "tasks"));
        assertTrue(store.loadAll().getTasks().isEmpty());
        assertNull(store.load("t-1"));
        assertTrue(store.resumeAfterInterruption(1L).getTasks().isEmpty());
    }

    // ---- resume after an interruption ------------------------------------------------------------------

    @Test
    public void resumeFollowsTheAdrRulesAndPersistsOnlyWhatItChanged() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        ResearchTask done = submitted("done", 1).start("local", 2_000L).complete(3_000L);
        ResearchTask running = submitted("running", 2, "done").start("local", 4_000L).withSpending(700L, 300L);
        ResearchTask spent = submitted("spent", 1).start("local", 2_000L).requeue("timeout", 2_500L)
                .start("local", 2_600L);
        ResearchTask asking = submitted("asking", 1).start("local", 2_000L)
                .requireInput("Which edition?", 2_100L);
        ResearchTask queued = submitted("queued", 1, "running");
        store.saveAll(Arrays.asList(done, running, spent, asking, queued));
        long doneFileModified = new File(dir, "done.json").lastModified();
        String doneBefore = new String(Files.readAllBytes(new File(dir, "done.json").toPath()),
                StandardCharsets.UTF_8);

        ResearchTaskResumption.Outcome outcome = new FileResearchTaskStore(dir).resumeAfterInterruption(10_000L);

        assertEquals(Collections.singletonList("running"), outcome.getRequeuedTaskIds());
        assertEquals(Collections.singletonList("spent"), outcome.getFailedTaskIds());
        assertEquals(Collections.singletonList("asking"), outcome.getAwaitingInputTaskIds());
        assertEquals(Collections.singletonList("running"), outcome.getReadyTaskIds());
        assertEquals(2, outcome.getChangedTasks().size());

        FileResearchTaskStore reopened = new FileResearchTaskStore(dir);
        assertEquals("completed is never touched", TaskState.COMPLETED, reopened.load("done").getState());
        assertEquals(doneBefore, new String(Files.readAllBytes(new File(dir, "done.json").toPath()),
                StandardCharsets.UTF_8));
        assertEquals(doneFileModified, new File(dir, "done.json").lastModified());

        ResearchTask requeued = reopened.load("running");
        assertEquals(TaskState.SUBMITTED, requeued.getState());
        assertEquals("the interruption counts a retry", 1, requeued.getRetryCount());
        TaskTransition last = requeued.getTransitions().get(requeued.getTransitions().size() - 1);
        assertEquals(TaskState.WORKING, last.getFrom());
        assertEquals(ResearchTaskResumption.INTERRUPTED, last.getReason());
        assertEquals(10_000L, last.getAtMillis());
        assertEquals("budgets carry over", 700L, requeued.getBudget().getTimeUsedMillis());
        assertEquals(300L, requeued.getBudget().getTokensUsed());

        assertEquals(TaskState.FAILED, reopened.load("spent").getState());
        assertTrue(reopened.load("spent").getTransitions().get(4).getReason().contains("retry limit 1 spent"));
        assertEquals("input-required waits for the user", TaskState.INPUT_REQUIRED,
                reopened.load("asking").getState());
        assertEquals(TaskState.SUBMITTED, reopened.load("queued").getState());
    }

    @Test
    public void aSecondResumeChangesNothing() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(submitted("t-1", 3).start("local", 2_000L));
        store.resumeAfterInterruption(5_000L);

        ResearchTaskResumption.Outcome second = store.resumeAfterInterruption(6_000L);
        assertTrue(second.getChangedTasks().isEmpty());
        assertEquals(1, store.load("t-1").getRetryCount());
    }

    @Test
    public void aResumedTaskCanRunAgainAndKeepsAccumulatingItsBudget() throws Exception {
        File dir = tempDir();
        FileResearchTaskStore store = new FileResearchTaskStore(dir);
        store.save(submitted("t-1", 1).start("local", 2_000L).withSpending(1_000L, 100L));
        store.resumeAfterInterruption(5_000L);

        ResearchTask again = store.load("t-1").start("local", 6_000L).withSpending(500L, 50L).complete(7_000L);
        store.save(again);
        ResearchTask restored = store.load("t-1");
        assertEquals(TaskState.COMPLETED, restored.getState());
        assertEquals(1_500L, restored.getBudget().getTimeUsedMillis());
        assertEquals(150L, restored.getBudget().getTokensUsed());
    }

    @Test
    public void theProjectStoreExposesTheTaskStoreUnderTasks() throws Exception {
        File root = tempDir();
        ResearchProjectContext context = ResearchProjectContext.open("p-1", root);
        context.getTaskStore().save(submitted("t-1", 0));
        assertTrue(new File(new File(root, "tasks"), "t-1.json").isFile());
    }
}
