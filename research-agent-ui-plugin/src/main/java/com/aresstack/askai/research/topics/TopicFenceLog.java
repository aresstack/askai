package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.memory.ResearchTopic.Fence;
import com.aresstack.askai.research.topics.TopicFenceCheck.Brief;
import com.aresstack.askai.research.topics.TopicFenceCheck.Verdict;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component 14 on disk: {@code topic-fence/log.jsonl} in the project directory (next to {@code topic-merge/} and
 * {@code research-memory/}). One JSON object per line, UTF-8, of two kinds: a brief ({@code "art":"grundlage"}: the
 * mission, edge, scope lines and cards a basis stands for, written once per basis) and a verdict
 * ({@code "art":"zaun"}: topic, basis, OK with the fence and its reason or FEHLER with the cause, who decided, the raw
 * answers). The fence itself also goes to the topic in the research memory; this log says on what it was decided.
 * <p>
 * The same rules as {@link TopicProposalLog}: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code topic-fence/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class TopicFenceLog {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "log.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private final Map<String, Brief> briefs = new LinkedHashMap<String, Brief>();
    private boolean opened;

    public TopicFenceLog(File projectDirectory) {
        File folder = new File(projectDirectory, "topic-fence");
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
            super("The fence check was written by a newer AskAI (" + what + "; this build reads versions 1 to "
                    + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /** Every verdict as stored, in the order written; empty before the first one. Appending is allowed from now on. */
    public synchronized List<Verdict> open() throws IOException, CorruptProjectFileException, NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        briefs.clear();
        List<Verdict> verdicts = new ArrayList<Verdict>();
        if (!logFile.isFile()) {
            opened = true;
            return verdicts;
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
                throw new CorruptProjectFileException(logFile, "The fence check cannot be read (line " + lineNumber
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
                if ("grundlage".equals(text(record, "art"))) {
                    Brief brief = briefFromJson(record);
                    briefs.put(text(record, "grundlage"), brief);
                } else {
                    verdicts.add(fromJson(record));
                }
            } catch (RuntimeException unreadable) {
                throw new CorruptProjectFileException(logFile, "The fence check cannot be read (record " + lineNumber
                        + "): " + unreadable.getMessage());
            }
        }
        if (end < content.length()) {
            System.err.println("[topic-fence] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        opened = true;
        return verdicts;
    }

    /** The briefs stored so far, by basis; read by {@link #open()}. */
    public synchronized Map<String, Brief> briefs() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Brief>(briefs));
    }

    /** The brief of a check, written once per basis. Only after {@link #open()}. */
    public synchronized void appendBrief(Brief brief) throws IOException {
        String basis = brief.basis();
        if (briefs.containsKey(basis)) {
            return;
        }
        write(briefToJson(brief));
        briefs.put(basis, brief);
    }

    /** One verdict, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void append(Verdict verdict) throws IOException {
        write(toJson(verdict));
    }

    /** Every verdict of a check, in order. */
    public synchronized void appendAll(List<Verdict> verdicts) throws IOException {
        for (Verdict verdict : verdicts) {
            append(verdict);
        }
    }

    private void write(JsonObject record) throws IOException {
        if (!opened) {
            throw new IllegalStateException("open the fence check log before writing to it");
        }
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(record) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    // ------------------------------------------------------------------------------------------- format

    static JsonObject toJson(Verdict verdict) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "zaun");
        json.addProperty("themaId", verdict.getTopicId());
        json.addProperty("themaName", verdict.getTopicName());
        json.addProperty("themaSchluessel", verdict.getTopicKey());
        json.addProperty("grundlage", verdict.getBasis());
        json.addProperty("status", verdict.getStatus().name());
        json.addProperty("urteil", verdict.getFence() == null ? "" : verdict.getFence().name());
        json.addProperty("grund", verdict.getReason());
        json.addProperty("fehler", verdict.getProblem());
        json.addProperty("herkunft", verdict.getOrigin().name());
        json.addProperty("modell", verdict.getModel());
        json.addProperty("zeit", verdict.getTimeMillis());
        json.addProperty("ms", verdict.getMillis());
        JsonArray answers = new JsonArray();
        for (String answer : verdict.getAnswers()) {
            answers.add(answer);
        }
        json.add("rohantworten", answers);
        return json;
    }

    static Verdict fromJson(JsonObject json) {
        if (!"zaun".equals(text(json, "art"))) {
            throw new IllegalArgumentException("unknown record kind " + text(json, "art"));
        }
        String fence = text(json, "urteil");
        List<String> answers = new ArrayList<String>();
        if (json.has("rohantworten") && json.get("rohantworten").isJsonArray()) {
            for (JsonElement answer : json.getAsJsonArray("rohantworten")) {
                answers.add(answer.isJsonNull() ? "" : answer.getAsString());
            }
        }
        return new Verdict(text(json, "themaId"), text(json, "themaName"), text(json, "themaSchluessel"),
                text(json, "grundlage"), Verdict.Status.valueOf(text(json, "status")),
                fence.isEmpty() ? null : Fence.valueOf(fence), text(json, "grund"), text(json, "fehler"), answers,
                Verdict.Origin.valueOf(text(json, "herkunft")), text(json, "modell"), json.get("zeit").getAsLong(),
                json.get("ms").getAsLong());
    }

    static JsonObject briefToJson(Brief brief) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "grundlage");
        json.addProperty("grundlage", brief.basis());
        json.addProperty("auftrag", brief.getMission());
        json.addProperty("rand", brief.getEdge());
        json.add("dazu", strings(brief.getIncluded()));
        json.add("ausgeschlossen", strings(brief.getExcluded()));
        json.addProperty("raum", brief.getPlace());
        json.addProperty("zeitraum", brief.getTime());
        json.add("karten", strings(brief.getCards()));
        return json;
    }

    static Brief briefFromJson(JsonObject json) {
        Brief brief = new Brief(text(json, "auftrag"), text(json, "rand"), list(json, "dazu"),
                list(json, "ausgeschlossen"), text(json, "raum"), text(json, "zeitraum"), list(json, "karten"));
        if (!brief.basis().equals(text(json, "grundlage"))) {
            throw new IllegalArgumentException("the brief does not match its basis " + text(json, "grundlage"));
        }
        return brief;
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> list(JsonObject json, String field) {
        List<String> values = new ArrayList<String>();
        if (json.has(field) && json.get(field).isJsonArray()) {
            for (JsonElement value : json.getAsJsonArray(field)) {
                values.add(value.getAsString());
            }
        }
        return values;
    }

    private static String text(JsonObject json, String field) {
        return json.has(field) && !json.get(field).isJsonNull() ? json.get(field).getAsString() : "";
    }
}
