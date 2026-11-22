package com.aresstack.askai.research.searchplanner;

import com.aresstack.askai.research.transition.AtomicFiles;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Component 3 on disk: {@code search-planner/log.jsonl} in the project directory (next to {@code research-memory/}).
 * One JSON object per line, UTF-8, one per task of the research lead ({@code "art":"aufgabe"}): the task, the status,
 * the searches taken (their ids; the searches themselves are in the memory), the searches not taken with the reason,
 * the raw answers.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code search-planner/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class SearchPlannerLog {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "log.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private boolean opened;

    public SearchPlannerLog(File projectDirectory) {
        File folder = new File(projectDirectory, "search-planner");
        this.logFile = new File(folder, GENERATION);
        this.pointerFile = new File(folder, "current");
    }

    public File logFile() {
        return logFile;
    }

    /** The log was written by a newer AskAI: this build neither reads nor changes it. */
    public static final class NewerVersionException extends Exception {
        private final File file;

        NewerVersionException(File file, String what) {
            super("The search planner's log was written by a newer AskAI (" + what + "; this build reads versions 1 "
                    + "to " + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** Every task as stored, in the order written; empty before the first one. Appending is allowed from now on. */
    public synchronized List<JsonObject> open() throws IOException, CorruptProjectFileException,
            NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        List<JsonObject> tasks = new ArrayList<JsonObject>();
        if (!logFile.isFile()) {
            opened = true;
            return tasks;
        }
        String content = new String(Files.readAllBytes(logFile.toPath()), AtomicFiles.UTF8);
        int end = content.lastIndexOf('\n') + 1;
        int lineNumber = 0;
        for (String line : content.substring(0, end).split("\n")) {
            lineNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }
            JsonObject record;
            int version;
            try {
                record = JsonParser.parseString(line).getAsJsonObject();
                version = record.get("v").getAsInt();
            } catch (RuntimeException unreadable) {
                throw new CorruptProjectFileException(logFile, "The search planner's log cannot be read (line "
                        + lineNumber + "): " + unreadable.getMessage());
            }
            if (version > VERSION) {
                throw new NewerVersionException(logFile, "line " + lineNumber + " has version " + version);
            }
            if (!"aufgabe".equals(record.has("art") ? record.get("art").getAsString() : "")) {
                throw new CorruptProjectFileException(logFile, "The search planner's log cannot be read (line "
                        + lineNumber + "): unknown record kind");
            }
            tasks.add(record);
        }
        if (end < content.length()) {
            System.err.println("[search-planner] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        opened = true;
        return tasks;
    }

    /** Every task of a round, each written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void append(RoundPlan round, long timeMillis) throws IOException {
        if (!opened) {
            throw new IllegalStateException("open the search planner's log before writing to it");
        }
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            for (TaskPlan task : round.getTasks()) {
                out.write((GSON.toJson(toJson(task, timeMillis)) + "\n").getBytes(AtomicFiles.UTF8));
            }
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static JsonObject toJson(TaskPlan plan, long timeMillis) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "aufgabe");
        json.addProperty("runde", plan.getTask().getRound());
        json.addProperty("ziel", plan.getTask().getTarget().name());
        json.addProperty("zielId", plan.getTask().getTargetId());
        json.add("fragen", strings(plan.getTask().getQuestionIds()));
        json.addProperty("strategie", plan.getTask().getStrategy().wire());
        json.addProperty("status", plan.getStatus().name());
        json.addProperty("modell", plan.getModel());
        JsonArray searches = new JsonArray();
        for (PlannedSearch search : plan.getSearches()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", search.getId());
            item.addProperty("frage", search.getQuestionId());
            item.addProperty("anfrage", search.getQuery());
            item.addProperty("zweck", search.getPurpose());
            searches.add(item);
        }
        json.add("suchen", searches);
        JsonArray rejected = new JsonArray();
        for (Rejection rejection : plan.getRejected()) {
            JsonObject item = new JsonObject();
            item.addProperty("anfrage", rejection.getQuery());
            item.addProperty("grund", rejection.getReason());
            rejected.add(item);
        }
        json.add("verworfen", rejected);
        json.addProperty("fehler", plan.getProblem());
        json.add("rohantworten", strings(plan.getAnswers()));
        json.addProperty("zeit", timeMillis);
        json.addProperty("ms", plan.getMillis());
        return json;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }
}
