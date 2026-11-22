package com.aresstack.askai.research.contradictions;

import com.aresstack.askai.research.memory.Contradiction;
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
 * Component 17 on disk: {@code contradictions/log.jsonl} in the project directory (next to {@code research-memory/}).
 * One JSON object per line, UTF-8, one per pair through the model ({@code "art":"paar"}): where the pair met, its two
 * sides (question, passage, source, text key; the quotes are in the memory's verdicts), the similarity, the status,
 * verdict, difference and reason, the problem of a failed call, the raw answers.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code contradictions/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class ContradictionLog {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "log.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private boolean opened;

    public ContradictionLog(File projectDirectory) {
        File folder = new File(projectDirectory, "contradictions");
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
            super("The contradiction check's log was written by a newer AskAI (" + what + "; this build reads "
                    + "versions 1 to " + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** Every pair as stored, in the order written; empty before the first one. Appending is allowed from now on. */
    public synchronized List<JsonObject> open() throws IOException, CorruptProjectFileException,
            NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        List<JsonObject> pairs = new ArrayList<JsonObject>();
        if (!logFile.isFile()) {
            opened = true;
            return pairs;
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
                throw new CorruptProjectFileException(logFile, "The contradiction check's log cannot be read (line "
                        + lineNumber + "): " + unreadable.getMessage());
            }
            if (version > VERSION) {
                throw new NewerVersionException(logFile, "line " + lineNumber + " has version " + version);
            }
            if (!"paar".equals(record.has("art") ? record.get("art").getAsString() : "")) {
                throw new CorruptProjectFileException(logFile, "The contradiction check's log cannot be read (line "
                        + lineNumber + "): unknown record kind");
            }
            pairs.add(record);
        }
        if (end < content.length()) {
            System.err.println("[contradictions] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        opened = true;
        return pairs;
    }

    /** One pair, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void append(ContradictionCheck.Judgement judgement, long timeMillis) throws IOException {
        if (!opened) {
            throw new IllegalStateException("open the contradiction check's log before writing to it");
        }
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(toJson(judgement, timeMillis)) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    static JsonObject toJson(ContradictionCheck.Judgement judgement, long timeMillis) {
        Contradiction record = judgement.getContradiction();
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "paar");
        json.addProperty("id", record.getId());
        json.addProperty("bereich", record.getScope().name());
        json.addProperty("bereichId", record.getScopeId());
        json.add("a", side(record.getA()));
        json.add("b", side(record.getB()));
        json.addProperty("aehnlichkeit", Math.round(record.getSimilarity() * 10000) / 10000.0);
        json.addProperty("status", record.getStatus().name());
        json.addProperty("urteil", record.getVerdict() == null ? "" : record.getVerdict().name());
        json.addProperty("unterschied", record.getDifference().name());
        json.addProperty("grund", record.getReason());
        json.addProperty("fehler", record.getProblem());
        json.addProperty("modell", record.getModel());
        json.addProperty("version", record.getVersion());
        JsonArray answers = new JsonArray();
        for (String answer : judgement.getAnswers()) {
            answers.add(answer);
        }
        json.add("rohantworten", answers);
        json.addProperty("zeit", timeMillis);
        json.addProperty("ms", judgement.getMillis());
        return json;
    }

    private static JsonObject side(Contradiction.Side side) {
        JsonObject json = new JsonObject();
        json.addProperty("frage", side.getQuestionId());
        json.addProperty("passage", side.getPassageId());
        json.addProperty("quelle", side.getSourceId());
        json.addProperty("adresse", side.getSourceUrl());
        json.addProperty("text", side.getTextKey());
        return json;
    }
}
