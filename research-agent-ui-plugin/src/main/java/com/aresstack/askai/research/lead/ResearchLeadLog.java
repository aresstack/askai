package com.aresstack.askai.research.lead;

import com.aresstack.askai.research.memory.ResearchQuestion;
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
 * Component 8 on disk: {@code research-lead/log.jsonl} in the project directory (next to {@code research-memory/}).
 * One JSON object per line, UTF-8, one per round ({@code "art":"runde"}): how the plan came about (model, rule or a
 * failed call), the model's judgement of the situation, the tasks for the search planner, the questions taken in, what
 * the guardrails changed, the open list at the end, and the raw answers. The decisions themselves are in the research
 * memory; this log says how the round was planned.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code research-lead/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class ResearchLeadLog {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "log.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private boolean opened;

    public ResearchLeadLog(File projectDirectory) {
        File folder = new File(projectDirectory, "research-lead");
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
            super("The research lead's log was written by a newer AskAI (" + what + "; this build reads versions 1 "
                    + "to " + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** Every round as stored, in the order written; empty before the first one. Appending is allowed from now on. */
    public synchronized List<JsonObject> open() throws IOException, CorruptProjectFileException,
            NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        List<JsonObject> rounds = new ArrayList<JsonObject>();
        if (!logFile.isFile()) {
            opened = true;
            return rounds;
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
                throw new CorruptProjectFileException(logFile, "The research lead's log cannot be read (line "
                        + lineNumber + "): " + unreadable.getMessage());
            }
            if (version > VERSION) {
                throw new NewerVersionException(logFile, "line " + lineNumber + " has version " + version);
            }
            if (!"runde".equals(record.has("art") ? record.get("art").getAsString() : "")) {
                throw new CorruptProjectFileException(logFile, "The research lead's log cannot be read (line "
                        + lineNumber + "): unknown record kind");
            }
            rounds.add(record);
        }
        if (end < content.length()) {
            System.err.println("[research-lead] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        opened = true;
        return rounds;
    }

    /** One round, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void append(LeadPlan plan, long timeMillis) throws IOException {
        if (!opened) {
            throw new IllegalStateException("open the research lead's log before writing to it");
        }
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(toJson(plan, timeMillis)) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static JsonObject toJson(LeadPlan plan, long timeMillis) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "runde");
        json.addProperty("runde", plan.getRound());
        json.addProperty("status", plan.getStatus().name());
        json.addProperty("ende", plan.isEnd());
        json.addProperty("modell", plan.getModel());
        json.addProperty("lage", plan.getSituation());
        json.addProperty("suchbudget", plan.getSearchBudget());
        JsonArray tasks = new JsonArray();
        for (LeadTask task : plan.getTasks()) {
            JsonObject item = new JsonObject();
            item.addProperty("ziel", task.getTarget().name());
            item.addProperty("zielId", task.getTargetId());
            item.add("fragen", strings(task.getQuestionIds()));
            item.addProperty("strategie", task.getStrategy().wire());
            item.addProperty("vorgabe", task.getInstruction());
            item.addProperty("grund", task.getReason());
            item.addProperty("herkunft", task.getOrigin().name());
            tasks.add(item);
        }
        json.add("aufgaben", tasks);
        List<String> taken = new ArrayList<String>();
        for (ResearchQuestion question : plan.getNewQuestions()) {
            taken.add(question.getId());
        }
        json.add("neueFragen", strings(taken));
        json.addProperty("entscheidungen", plan.getDecisions().size());
        json.add("leitplanken", strings(plan.getNotes()));
        if (plan.getOpenList() != null) {
            JsonObject open = new JsonObject();
            open.addProperty("grund", plan.getOpenList().getReason());
            open.addProperty("offen", plan.getOpenList().getOpen().size());
            open.addProperty("zurueckgestellt", plan.getOpenList().getSetAside().size());
            open.addProperty("autor", plan.getOpenList().getForTheAuthor().size());
            json.add("offeneListe", open);
        }
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
