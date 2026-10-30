package com.aresstack.askai.research.store;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskInputs;
import com.aresstack.askai.research.domain.task.TaskReceipt;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.domain.task.TaskTransition;
import com.aresstack.askai.research.state.ResearchTaskResumption;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The task model of one project in the project store (ADR 0001, section 4.4): every work package is persisted
 * as {@code tasks/<taskId>.json}, written atomically WHENEVER it changes, together with its artifacts,
 * receipts, budget account and full transition history. One file per task keeps a write small and isolates a
 * damaged file to the one task it holds.
 *
 * <p>The JSON is mapped field by field with Gson's tree model — the domain classes carry no annotations and
 * no reflection reaches into them. Loading goes through {@link ResearchTask#restore}, so a file whose history
 * breaks the transition table is REJECTED and reported in {@link TaskLoadResult#getRejected()}, never
 * silently repaired. Tasks are never deleted: a finished task stays on record with its terminal state.</p>
 *
 * <p>{@link #resumeAfterInterruption(long)} is the resume pass the task executor runs before it schedules
 * anything after a restart; the rules themselves live in {@link ResearchTaskResumption}.</p>
 */
public final class FileResearchTaskStore {

    static final int SCHEMA_VERSION = 1;
    private static final String SUFFIX = ".json";

    private final File dir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public FileResearchTaskStore(File tasksDir) {
        this.dir = tasksDir;
    }

    /** Persist the current version of {@code task}, replacing the previous one atomically. */
    public synchronized void save(ResearchTask task) throws IOException {
        if (task == null) {
            return;
        }
        StoreIo.atomicWrite(fileFor(task.getTaskId()), gson.toJson(toJson(task)) + "\n");
    }

    /** Persist several tasks; each is its own atomic write. */
    public synchronized void saveAll(List<ResearchTask> tasks) throws IOException {
        if (tasks == null) {
            return;
        }
        for (ResearchTask task : tasks) {
            save(task);
        }
    }

    /** One task by id, or {@code null} when it is not stored or its file was rejected. */
    public synchronized ResearchTask load(String taskId) {
        File file = taskId == null ? null : fileFor(taskId);
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return fromJson(JsonParser.parseString(StoreIo.readUtf8(file)).getAsJsonObject());
        } catch (Exception corrupt) {
            return null;
        }
    }

    /** Every stored task, oldest submission first, plus the files that could not be restored. */
    public synchronized TaskLoadResult loadAll() {
        List<ResearchTask> tasks = new ArrayList<ResearchTask>();
        Map<String, String> rejected = new LinkedHashMap<String, String>();
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                if (!file.isFile() || !file.getName().endsWith(SUFFIX)) {
                    continue;
                }
                try {
                    tasks.add(fromJson(JsonParser.parseString(StoreIo.readUtf8(file)).getAsJsonObject()));
                } catch (Exception corrupt) {
                    rejected.put(file.getName(), corrupt.getMessage() == null
                            ? corrupt.getClass().getSimpleName() : corrupt.getMessage());
                }
            }
        }
        Collections.sort(tasks, new Comparator<ResearchTask>() {
            public int compare(ResearchTask a, ResearchTask b) {
                int byTime = Long.compare(a.getCreatedAtMillis(), b.getCreatedAtMillis());
                return byTime != 0 ? byTime : a.getTaskId().compareTo(b.getTaskId());
            }
        });
        return new TaskLoadResult(tasks, rejected);
    }

    /**
     * The resume pass after an interruption: loads every task, applies {@link ResearchTaskResumption} and
     * persists exactly the tasks it changed. Completed tasks are left untouched, working tasks are re-queued
     * within their retry limit (or failed once it is spent), input-required tasks keep waiting for the user,
     * budgets carry over unchanged.
     */
    public synchronized ResearchTaskResumption.Outcome resumeAfterInterruption(long nowMillis)
            throws IOException {
        ResearchTaskResumption.Outcome outcome =
                ResearchTaskResumption.resume(loadAll().getTasks(), nowMillis);
        saveAll(outcome.getChangedTasks());
        return outcome;
    }

    // ---- mapping -----------------------------------------------------------------------------------------

    static JsonObject toJson(ResearchTask task) {
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("taskId", task.getTaskId());
        json.addProperty("phase", task.getPhase());
        json.addProperty("role", task.getRole());
        json.add("dependsOn", strings(task.getDependsOn()));
        json.addProperty("state", task.getState().wireName());
        JsonObject inputs = new JsonObject();
        inputs.addProperty("brief", task.getInputs().getBrief());
        inputs.add("artifactRefs", strings(task.getInputs().getArtifactRefs()));
        json.add("inputs", inputs);
        JsonArray artifacts = new JsonArray();
        for (TaskArtifact artifact : task.getArtifacts()) {
            JsonObject a = new JsonObject();
            a.addProperty("artifactId", artifact.getArtifactId());
            a.addProperty("kind", artifact.getKind().name());
            a.addProperty("content", artifact.getContent());
            a.addProperty("projectRef", artifact.getProjectRef());
            a.addProperty("createdAtMillis", artifact.getCreatedAtMillis());
            artifacts.add(a);
        }
        json.add("artifacts", artifacts);
        json.addProperty("modelSlot", task.getModelSlot());
        json.addProperty("contextSize", task.getContextSize());
        JsonObject budget = new JsonObject();
        budget.addProperty("timeLimitMillis", task.getBudget().getTimeLimitMillis());
        budget.addProperty("tokenLimit", task.getBudget().getTokenLimit());
        budget.addProperty("timeUsedMillis", task.getBudget().getTimeUsedMillis());
        budget.addProperty("tokensUsed", task.getBudget().getTokensUsed());
        json.add("budget", budget);
        json.addProperty("retryCount", task.getRetryCount());
        json.addProperty("retryLimit", task.getRetryLimit());
        JsonArray receipts = new JsonArray();
        for (TaskReceipt receipt : task.getReceipts()) {
            JsonObject r = new JsonObject();
            r.addProperty("toolName", receipt.getToolName());
            r.addProperty("succeeded", receipt.isSucceeded());
            r.addProperty("receipt", receipt.getReceipt());
            r.addProperty("recordedAtMillis", receipt.getRecordedAtMillis());
            receipts.add(r);
        }
        json.add("receipts", receipts);
        JsonArray transitions = new JsonArray();
        for (TaskTransition transition : task.getTransitions()) {
            JsonObject t = new JsonObject();
            if (transition.getFrom() != null) {
                t.addProperty("from", transition.getFrom().wireName());
            }
            t.addProperty("to", transition.getTo().wireName());
            t.addProperty("atMillis", transition.getAtMillis());
            t.addProperty("reason", transition.getReason());
            transitions.add(t);
        }
        json.add("transitions", transitions);
        json.addProperty("endpoint", task.getEndpoint());
        return json;
    }

    static ResearchTask fromJson(JsonObject json) {
        int version = json.has("schemaVersion") ? json.get("schemaVersion").getAsInt() : -1;
        if (version != SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported task schema version " + version);
        }
        JsonObject inputs = object(json, "inputs");
        List<TaskArtifact> artifacts = new ArrayList<TaskArtifact>();
        for (JsonElement element : array(json, "artifacts")) {
            JsonObject a = element.getAsJsonObject();
            artifacts.add(new TaskArtifact(text(a, "artifactId"), TaskArtifact.Kind.valueOf(text(a, "kind")),
                    text(a, "content"), text(a, "projectRef"), number(a, "createdAtMillis")));
        }
        JsonObject budget = object(json, "budget");
        List<TaskReceipt> receipts = new ArrayList<TaskReceipt>();
        for (JsonElement element : array(json, "receipts")) {
            JsonObject r = element.getAsJsonObject();
            receipts.add(new TaskReceipt(text(r, "toolName"),
                    r.has("succeeded") && r.get("succeeded").getAsBoolean(), text(r, "receipt"),
                    number(r, "recordedAtMillis")));
        }
        List<TaskTransition> transitions = new ArrayList<TaskTransition>();
        for (JsonElement element : array(json, "transitions")) {
            JsonObject t = element.getAsJsonObject();
            TaskState from = t.has("from") && !t.get("from").isJsonNull()
                    ? TaskState.fromWireName(t.get("from").getAsString()) : null;
            transitions.add(new TaskTransition(from, TaskState.fromWireName(text(t, "to")),
                    number(t, "atMillis"), text(t, "reason")));
        }
        return ResearchTask.restore(text(json, "taskId"), text(json, "phase"), text(json, "role"),
                strings(array(json, "dependsOn")), TaskState.fromWireName(text(json, "state")),
                new TaskInputs(text(inputs, "brief"), strings(array(inputs, "artifactRefs"))), artifacts,
                text(json, "modelSlot"), (int) number(json, "contextSize"),
                new TaskBudget(number(budget, "timeLimitMillis"), number(budget, "tokenLimit"),
                        number(budget, "timeUsedMillis"), number(budget, "tokensUsed")),
                (int) number(json, "retryCount"), (int) number(json, "retryLimit"), receipts, transitions,
                text(json, "endpoint"));
    }

    private File fileFor(String taskId) {
        return new File(dir, safeFileName(taskId) + SUFFIX);
    }

    /** Task ids are chosen by the orchestrator; anything outside {@code [A-Za-z0-9._-]} is encoded. */
    static String safeFileName(String taskId) {
        StringBuilder sb = new StringBuilder();
        for (char c : taskId.trim().toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || (c == '.' && sb.length() > 0)) {
                sb.append(c);
            } else {
                sb.append('~').append(String.format("%04x", (int) c));
            }
        }
        return sb.toString();
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<String>();
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }

    private static JsonObject object(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonObject() ? json.getAsJsonObject(key) : new JsonObject();
    }

    private static JsonArray array(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonArray() ? json.getAsJsonArray(key) : new JsonArray();
    }

    private static String text(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : "";
    }

    private static long number(JsonObject json, String key) {
        return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsLong() : 0L;
    }

    /** What {@link #loadAll()} found: the restored tasks and, per file name, why a file was rejected. */
    public static final class TaskLoadResult {

        private final List<ResearchTask> tasks;
        private final Map<String, String> rejected;

        TaskLoadResult(List<ResearchTask> tasks, Map<String, String> rejected) {
            this.tasks = Collections.unmodifiableList(new ArrayList<ResearchTask>(tasks));
            this.rejected = Collections.unmodifiableMap(new LinkedHashMap<String, String>(rejected));
        }

        public List<ResearchTask> getTasks() {
            return tasks;
        }

        public Map<String, String> getRejected() {
            return rejected;
        }
    }
}
