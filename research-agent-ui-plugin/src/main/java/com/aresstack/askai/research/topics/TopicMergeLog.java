package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.topics.TopicMerger.Comparison;
import com.aresstack.askai.research.topics.TopicMerger.Relation;
import com.aresstack.askai.research.transition.AtomicFiles;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Component 15 on disk: every doubtful case the topic merge put to the model, {@code topic-merge/log.jsonl} in the
 * project directory (next to {@code topic-proposals/} and {@code research-memory/}). One JSON object per line and case,
 * UTF-8: both texts as the model read them, the similarity, OK with the relation and its reason or FEHLER with the
 * cause, the raw answers, the version of the prompts ({@code "fassung"}, 1 when missing) and the code's note on the
 * answer ({@code "vermerk"}, empty when missing). The merge reuses an OK
 * verdict on the same two texts under the same prompts, so a rerun asks only what is new or failed; the topics
 * themselves live in the research memory.
 * <p>
 * The same rules as {@link TopicProposalLog}: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code topic-merge/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class TopicMergeLog {

    /**
     * The version this build writes; it reads every version from 1 up to it. 2 (22.11.2026): the kind OBERTHEMA of
     * the parent step and the field {@code "vermerk"}. 3 (22.11.2026): the kinds GEGENSTAND and GEGENSTANDSPAAR of
     * the subject step. 4 (23.11.2026): the kinds ABSCHNITTE, ZUORDNUNG, REST and TEILUNG of the outline.
     */
    static final int VERSION = 4;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "log.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private boolean opened;

    public TopicMergeLog(File projectDirectory) {
        File folder = new File(projectDirectory, "topic-merge");
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
            super("The topic merge was written by a newer AskAI (" + what + "; this build reads versions 1 to "
                    + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** Every case as stored, in the order written; empty before the first one. Appending is allowed from now on. */
    public synchronized List<Comparison> open() throws IOException, CorruptProjectFileException,
            NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        List<Comparison> comparisons = new ArrayList<Comparison>();
        if (!logFile.isFile()) {
            opened = true;
            return comparisons;
        }
        String content = new String(Files.readAllBytes(logFile.toPath()), AtomicFiles.UTF8);
        int end = content.lastIndexOf('\n') + 1;
        List<JsonObject> records = new ArrayList<JsonObject>();
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
                throw new CorruptProjectFileException(logFile, "The topic merge cannot be read (line " + lineNumber
                        + "): " + unreadable.getMessage());
            }
            if (version > VERSION) {
                throw new NewerVersionException(logFile, "line " + lineNumber + " has version " + version);
            }
            records.add(record);
        }
        lineNumber = 0;
        for (JsonObject record : records) {
            lineNumber++;
            try {
                comparisons.add(fromJson(record));
            } catch (RuntimeException unreadable) {
                throw new CorruptProjectFileException(logFile, "The topic merge cannot be read (record " + lineNumber
                        + "): " + unreadable.getMessage());
            }
        }
        if (end < content.length()) {
            System.err.println("[topic-merge] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        opened = true;
        return comparisons;
    }

    /** One case, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void append(Comparison comparison) throws IOException {
        if (!opened) {
            throw new IllegalStateException("open the topic merge log before writing to it");
        }
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(toJson(comparison)) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    /** Every case of a run, in order. */
    public synchronized void appendAll(List<Comparison> comparisons) throws IOException {
        for (Comparison comparison : comparisons) {
            append(comparison);
        }
    }

    // ------------------------------------------------------------------------------------------- format

    static JsonObject toJson(Comparison comparison) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "vergleich");
        json.addProperty("typ", comparison.getKind().name());
        json.addProperty("aId", comparison.getAId());
        json.addProperty("aText", comparison.getAText());
        json.addProperty("bId", comparison.getBId());
        json.addProperty("bText", comparison.getBText());
        json.addProperty("bBekannt", comparison.isBKnown());
        json.addProperty("aehnlichkeit", comparison.getSimilarity());
        json.addProperty("status", comparison.getStatus().name());
        json.addProperty("beziehung", comparison.getRelation() == null ? "" : comparison.getRelation().name());
        json.addProperty("grund", comparison.getReason());
        json.addProperty("fehler", comparison.getProblem());
        json.addProperty("modell", comparison.getModel());
        json.addProperty("zeit", comparison.getTimeMillis());
        json.addProperty("ms", comparison.getMillis());
        json.addProperty("fassung", comparison.getVersion());
        json.addProperty("vermerk", comparison.getNote());
        JsonArray answers = new JsonArray();
        for (String answer : comparison.getAnswers()) {
            answers.add(answer);
        }
        json.add("rohantworten", answers);
        return json;
    }

    static Comparison fromJson(JsonObject json) {
        if (!"vergleich".equals(text(json, "art"))) {
            throw new IllegalArgumentException("unknown record kind " + text(json, "art"));
        }
        Comparison.Status status = Comparison.Status.valueOf(text(json, "status"));
        String relation = text(json, "beziehung");
        List<String> answers = new ArrayList<String>();
        if (json.has("rohantworten") && json.get("rohantworten").isJsonArray()) {
            for (JsonElement answer : json.getAsJsonArray("rohantworten")) {
                answers.add(answer.isJsonNull() ? "" : answer.getAsString());
            }
        }
        return new Comparison(Comparison.Kind.valueOf(text(json, "typ")), text(json, "aId"), text(json, "aText"),
                text(json, "bId"), text(json, "bText"), json.get("bBekannt").getAsBoolean(),
                json.get("aehnlichkeit").getAsDouble(), status,
                relation.isEmpty() ? null : Relation.valueOf(relation), text(json, "grund"), text(json, "fehler"),
                answers, text(json, "modell"), json.get("zeit").getAsLong(), json.get("ms").getAsLong(),
                json.has("fassung") && !json.get("fassung").isJsonNull() ? json.get("fassung").getAsInt() : 1,
                text(json, "vermerk"));
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : "";
    }
}
