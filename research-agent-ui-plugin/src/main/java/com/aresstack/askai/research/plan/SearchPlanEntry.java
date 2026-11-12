package com.aresstack.askai.research.plan;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskBudget;
import com.aresstack.askai.research.domain.task.TaskInputs;
import com.aresstack.askai.research.domain.task.TaskReceipt;
import com.aresstack.askai.research.domain.task.TaskState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * One entry of the search plan (plan PR 14, D5): a query for one concept node, stored as one task of the project's
 * task store. The entry is a read view over its {@link ResearchTask}; every change returns the next task version.
 * Status maps onto {@link TaskState}: pending {@code SUBMITTED}, running {@code WORKING}, done {@code COMPLETED},
 * failed {@code FAILED}, skipped {@code CANCELED}.
 */
public final class SearchPlanEntry {

    /** The role every plan task carries; the orchestrated run's task board leaves these tasks alone. */
    public static final String ROLE = "search-plan";
    /** The endpoint a running entry is assigned to. */
    static final String ENDPOINT = "search-plan-queue";
    /** How often an interrupted entry goes back to pending before it fails. */
    static final int RETRY_LIMIT = 3;

    private static final String NODE = "node=";
    private static final String ORIGIN = "origin=";
    private static final String LABEL = "label=";
    private static final String REQUEST = "request";
    private static final String RANKING = "ranking";
    /** Plan T4: a page worker's message to the orchestrator about this query (one receipt per message). */
    private static final String TEAM = "team";

    /** Where the query came from. */
    public enum Origin {
        /** The node's deterministic base query. */
        BASE,
        /** A variant the planner role wrote. */
        VARIANT,
        /** A query the planner wrote in a later research cycle for a node the earlier cycles left uncovered. */
        CYCLE,
        /** Added or edited by the user. */
        USER,
        /** The comment of "Request changes" at the Sources gate; runs first. */
        PRIORITY,
        /**
         * Plan T4: a query a page worker found while reading a hit of an earlier search, inside the Weidezaun; runs
         * before every planned entry, so the searches build on what was read.
         */
        WORKER;

        String token() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Origin of(String token) {
            for (Origin origin : values()) {
                if (origin.token().equals(token)) {
                    return origin;
                }
            }
            return USER;
        }
    }

    /** The entry status shown on the checklist. */
    public enum Status {
        PENDING, RUNNING, DONE, FAILED, SKIPPED;

        static Status of(TaskState state) {
            switch (state) {
                case SUBMITTED:
                case INPUT_REQUIRED:
                    return PENDING;
                case WORKING:
                    return RUNNING;
                case COMPLETED:
                    return DONE;
                case FAILED:
                    return FAILED;
                default:
                    return SKIPPED;
            }
        }
    }

    private final ResearchTask task;

    SearchPlanEntry(ResearchTask task) {
        this.task = task;
    }

    /** A new pending entry. */
    static SearchPlanEntry submit(String entryId, String phase, String nodeId, String nodeLabel, String query,
                                  Origin origin, long atMillis) {
        List<String> refs = Arrays.asList(NODE + clean(nodeId), ORIGIN + origin.token(), LABEL + clean(nodeLabel));
        return new SearchPlanEntry(ResearchTask.submit(entryId, phase, ROLE, Collections.<String>emptyList(),
                new TaskInputs(clean(query), refs), "", 0, TaskBudget.unlimited(), RETRY_LIMIT, atMillis));
    }

    static boolean isPlanTask(ResearchTask task) {
        return task != null && ROLE.equals(task.getRole());
    }

    public ResearchTask task() {
        return task;
    }

    public String getId() {
        return task.getTaskId();
    }

    /** The phase whose plan the entry belongs to. */
    public String getPhase() {
        return task.getPhase();
    }

    public String getNodeId() {
        return ref(NODE);
    }

    public String getNodeLabel() {
        return ref(LABEL);
    }

    public Origin getOrigin() {
        return Origin.of(ref(ORIGIN));
    }

    public String getQuery() {
        return task.getInputs().getBrief();
    }

    public Status getStatus() {
        return Status.of(task.getState());
    }

    /** The accepted sources of this entry, in acceptance order. */
    public List<String> getAcceptedSourceIds() {
        LinkedHashSet<String> ids = new LinkedHashSet<String>();
        for (TaskArtifact artifact : task.getArtifacts()) {
            if (artifact.getKind() == TaskArtifact.Kind.SOURCE_REFERENCE) {
                ids.add(artifact.getContent());
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(ids));
    }

    /** When the newest of its accepted sources was accepted, 0 without one. */
    public long getLastSourceAtMillis() {
        long last = 0;
        for (TaskArtifact artifact : task.getArtifacts()) {
            if (artifact.getKind() == TaskArtifact.Kind.SOURCE_REFERENCE) {
                last = Math.max(last, artifact.getCreatedAtMillis());
            }
        }
        return last;
    }

    /** The last search request id of this entry ("" before its first run). */
    public String getRequestId() {
        return lastReceipt(REQUEST);
    }

    /** The ranking note of the last run (empty when the chosen ranking was used). */
    public String getNote() {
        if (task.getState() == TaskState.FAILED || task.getState() == TaskState.CANCELED) {
            String reason = task.getTransitions().get(task.getTransitions().size() - 1).getReason();
            if (!reason.isEmpty()) {
                return reason;
            }
        }
        return lastReceipt(RANKING);
    }

    public long getCreatedAtMillis() {
        return task.getCreatedAtMillis();
    }

    // ---- next versions -----------------------------------------------------------------------------------

    SearchPlanEntry started(String requestId, long atMillis) {
        return new SearchPlanEntry(task.start(ENDPOINT, atMillis)
                .withReceipt(new TaskReceipt(REQUEST, true, clean(requestId), atMillis)));
    }

    SearchPlanEntry withSource(String sourceId, long atMillis) {
        if (getAcceptedSourceIds().contains(sourceId)) {
            return this;
        }
        return new SearchPlanEntry(task.withArtifact(new TaskArtifact("source-" + sourceId,
                TaskArtifact.Kind.SOURCE_REFERENCE, sourceId, sourceId, atMillis)));
    }

    SearchPlanEntry withNote(String note, long atMillis) {
        return new SearchPlanEntry(task.withReceipt(new TaskReceipt(RANKING, true, clean(note), atMillis)));
    }

    /** Plan T4: this entry with one more page worker message to the orchestrator; blank ones are ignored. */
    SearchPlanEntry withTeamNote(String note, long atMillis) {
        String text = clean(note);
        if (text.isEmpty() || getTeamNotes().contains(text)) {
            return this;
        }
        return new SearchPlanEntry(task.withReceipt(new TaskReceipt(TEAM, true, text, atMillis)));
    }

    /** Plan T4: what the page workers of this query reported to the orchestrator, oldest first. */
    public List<String> getTeamNotes() {
        List<String> notes = new java.util.ArrayList<String>();
        for (TaskReceipt receipt : task.getReceipts()) {
            if (TEAM.equals(receipt.getToolName())) {
                notes.add(receipt.getReceipt());
            }
        }
        return notes;
    }

    SearchPlanEntry done(long atMillis) {
        return new SearchPlanEntry(task.complete(atMillis));
    }

    /** A failed run goes back to pending while retries are left, else it fails. */
    SearchPlanEntry failedRun(String reason, long atMillis) {
        return new SearchPlanEntry(task.hasRetriesLeft() ? task.requeue(reason, atMillis)
                : task.fail(reason, atMillis));
    }

    SearchPlanEntry skipped(String reason, long atMillis) {
        return new SearchPlanEntry(task.cancel(reason, atMillis));
    }

    /** Whether a stopped run can go back to pending on the same task (else a new task replaces it). */
    boolean canRequeue() {
        return task.getState() == TaskState.WORKING && task.hasRetriesLeft();
    }

    SearchPlanEntry requeued(String reason, long atMillis) {
        return new SearchPlanEntry(task.requeue(reason, atMillis));
    }

    private String ref(String prefix) {
        for (String ref : task.getInputs().getArtifactRefs()) {
            if (ref.startsWith(prefix)) {
                return ref.substring(prefix.length());
            }
        }
        return "";
    }

    private String lastReceipt(String tool) {
        String value = "";
        for (TaskReceipt receipt : task.getReceipts()) {
            if (tool.equals(receipt.getToolName())) {
                value = receipt.getReceipt();
            }
        }
        return value;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
