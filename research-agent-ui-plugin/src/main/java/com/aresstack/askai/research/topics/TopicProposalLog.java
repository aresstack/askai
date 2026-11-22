package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.topics.TopicFinder.WindowResult;
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
 * Component 13 on disk: what the topic finder read and proposed, {@code topic-proposals/log.jsonl} in the project
 * directory (next to {@code research-memory/} and {@code search-plan/}). One JSON object per line and window, UTF-8:
 * the source, the passages read, OK or FEHLER with the reason, the proposals with their finding, the proposals not
 * taken with the reason and the raw answers. The proposals stay when the merge (15) turns them into topics, so a merge
 * can be checked and undone.
 * <p>
 * The file is only appended to; a later line for the same window (same source and passages) replaces the earlier one
 * when read ({@link #latest}), so a FEHLER is followed by the retry's result. Rules as for the research memory: a line
 * that cannot be read fails closed ({@link CorruptProjectFileException}), only an unfinished last line (no line end)
 * is cut off; every line carries its version ({@code "v"}), a newer version or a pointer {@code
 * topic-proposals/current} to another generation stops the opening with {@link NewerVersionException} before
 * anything is written.
 */
public final class TopicProposalLog {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "log.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private boolean opened;

    public TopicProposalLog(File projectDirectory) {
        File folder = new File(projectDirectory, "topic-proposals");
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
            super("The topic proposals were written by a newer AskAI (" + what + "; this build reads versions 1 to "
                    + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * Every window result as stored, in the order written; empty before the first one. Appending is allowed from now
     * on.
     */
    public synchronized List<WindowResult> open() throws IOException, CorruptProjectFileException,
            NewerVersionException {
        checkGeneration();
        List<WindowResult> results = new ArrayList<WindowResult>();
        if (!logFile.isFile()) {
            opened = true;
            return results;
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
                throw new CorruptProjectFileException(logFile, "The topic proposals cannot be read (line "
                        + lineNumber + "): " + unreadable.getMessage());
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
                results.add(fromJson(record));
            } catch (RuntimeException unreadable) {
                throw new CorruptProjectFileException(logFile, "The topic proposals cannot be read (record "
                        + lineNumber + "): " + unreadable.getMessage());
            }
        }
        if (end < content.length()) {
            System.err.println("[topic-proposals] dropping an unfinished last record of " + logFile);
            AtomicFiles.write(logFile, content.substring(0, end));
        }
        opened = true;
        return results;
    }

    /** One window result, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void append(WindowResult result) throws IOException {
        if (!opened) {
            throw new IllegalStateException("open the topic proposals before writing to them");
        }
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(toJson(result)) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    /** Every result of a batch, in order. */
    public synchronized void appendAll(List<WindowResult> results) throws IOException {
        for (WindowResult result : results) {
            append(result);
        }
    }

    /** The latest result of every window, in the order the windows first appear. */
    public static List<WindowResult> latest(List<WindowResult> results) {
        Map<String, WindowResult> latest = new LinkedHashMap<String, WindowResult>();
        for (WindowResult result : results) {
            latest.put(result.windowKey(), result);
        }
        return Collections.unmodifiableList(new ArrayList<WindowResult>(latest.values()));
    }

    /** The proposals of the latest OK result of every window, in order. */
    public static List<TopicProposal> proposals(List<WindowResult> results) {
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (WindowResult result : latest(results)) {
            proposals.addAll(result.getProposals());
        }
        return proposals;
    }

    /** The windows whose latest result is FEHLER: to be read again. */
    public static List<WindowResult> failed(List<WindowResult> results) {
        List<WindowResult> failed = new ArrayList<WindowResult>();
        for (WindowResult result : latest(results)) {
            if (!result.isOk()) {
                failed.add(result);
            }
        }
        return failed;
    }

    /** A pointer to another generation than this build's means a newer build moved the log on. */
    private void checkGeneration() throws IOException, NewerVersionException {
        if (!pointerFile.isFile()) {
            return;
        }
        String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
        if (!GENERATION.equals(current)) {
            throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
        }
    }

    // ------------------------------------------------------------------------------------------- format

    static JsonObject toJson(WindowResult result) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "fenster");
        json.addProperty("quelleId", result.getSourceId());
        json.addProperty("quelleUrl", result.getSourceUrl());
        json.addProperty("fenster", result.getWindowIndex());
        json.add("passagen", array(result.getPassageIds()));
        json.addProperty("status", result.getStatus().name());
        json.addProperty("fehler", result.getProblem());
        json.addProperty("modell", result.getModel());
        json.addProperty("runde", result.getRound());
        json.addProperty("zeit", result.getTimeMillis());
        json.addProperty("ms", result.getMillis());
        JsonArray proposals = new JsonArray();
        for (TopicProposal proposal : result.getProposals()) {
            JsonObject p = new JsonObject();
            p.addProperty("vorschlagId", proposal.getId());
            p.addProperty("passageId", proposal.getPassageId());
            p.addProperty("zitat", proposal.getQuote());
            p.addProperty("fundstelle", proposal.getFindingCheck().name());
            p.addProperty("name", proposal.getName());
            p.addProperty("beschreibung", proposal.getDescription());
            p.add("fragen", array(proposal.getQuestions()));
            p.addProperty("zeit", proposal.getTimeMillis());
            proposals.add(p);
        }
        json.add("vorschlaege", proposals);
        json.add("verworfen", array(result.getDropped()));
        json.add("rohantworten", array(result.getAnswers()));
        return json;
    }

    /** One line in the shape of its version; version 1 is the only one so far. */
    static WindowResult fromJson(JsonObject json) {
        int version = json.get("v").getAsInt();
        if (version < 1) {
            throw new IllegalArgumentException("unknown version " + version);
        }
        if (!"fenster".equals(text(json, "art"))) {
            throw new IllegalArgumentException("unknown record '" + text(json, "art") + "'");
        }
        String sourceId = text(json, "quelleId");
        String sourceUrl = text(json, "quelleUrl");
        String model = text(json, "modell");
        int round = json.get("runde").getAsInt();
        List<TopicProposal> proposals = new ArrayList<TopicProposal>();
        for (JsonElement element : json.getAsJsonArray("vorschlaege")) {
            JsonObject p = element.getAsJsonObject();
            proposals.add(new TopicProposal(text(p, "vorschlagId"), sourceId, sourceUrl, text(p, "passageId"),
                    text(p, "zitat"), TopicProposal.FindingCheck.valueOf(text(p, "fundstelle")), text(p, "name"),
                    text(p, "beschreibung"), list(p, "fragen"), model, round, p.get("zeit").getAsLong()));
        }
        return new WindowResult(sourceId, sourceUrl, json.get("fenster").getAsInt(), list(json, "passagen"),
                WindowResult.Status.valueOf(text(json, "status")), text(json, "fehler"), proposals,
                list(json, "verworfen"), list(json, "rohantworten"), model, round, json.get("zeit").getAsLong(),
                json.get("ms").getAsLong());
    }

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static String text(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("field '" + field + "' missing");
        }
        return value.getAsString();
    }

    private static List<String> list(JsonObject json, String field) {
        List<String> values = new ArrayList<String>();
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("field '" + field + "' missing");
        }
        for (JsonElement element : value.getAsJsonArray()) {
            values.add(element.getAsString());
        }
        return values;
    }
}
