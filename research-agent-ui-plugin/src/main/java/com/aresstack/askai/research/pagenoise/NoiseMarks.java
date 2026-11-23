package com.aresstack.askai.research.pagenoise;

import com.aresstack.askai.research.memory.PassageNoise;
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
import java.util.Locale;
import java.util.Map;

/**
 * The page-noise check's marks of one project, by text key, and the {@link PassageNoise} the other components read:
 * {@code page-noise/marks.jsonl} in the project directory (next to {@code research-memory/}). One JSON object per line,
 * UTF-8, one per mark ({@code "art":"marke"}): text key, first passage and source, verdict, kind, stage, reason,
 * status, problem, model, the check's version, time, duration and the model's raw answers. The latest mark of a text
 * key holds; the earlier ones stay in the file.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code page-noise/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written. Safe for parallel callers.
 */
public final class NoiseMarks implements PassageNoise {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "marks.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private final Map<String, NoiseMark> marks = new LinkedHashMap<String, NoiseMark>();
    private boolean opened;

    /** Marks in RAM only, for tests and dry runs: open and writable at once, nothing goes to disk. */
    public NoiseMarks() {
        this.logFile = null;
        this.pointerFile = null;
        this.opened = true;
    }

    /** The marks of a project; {@link #open()} reads them before the first one is written. */
    public NoiseMarks(File projectDirectory) {
        File folder = new File(projectDirectory, "page-noise");
        this.logFile = new File(folder, GENERATION);
        this.pointerFile = new File(folder, "current");
    }

    /** The file, {@code null} for marks in RAM only. */
    public File logFile() {
        return logFile;
    }

    /** The file was written by a newer AskAI: this build neither reads nor changes it. */
    public static final class NewerVersionException extends Exception {
        private final File file;

        NewerVersionException(File file, String what) {
            super("The page-noise check's marks were written by a newer AskAI (" + what + "; this build reads versions "
                    + "1 to " + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * Reads every mark, the latest per text key holds; writing is allowed from now on. Nothing to read before the
     * first mark.
     *
     * @return the number of lines read
     */
    public synchronized int open() throws IOException, CorruptProjectFileException, NewerVersionException {
        if (logFile == null) {
            return 0;
        }
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        Map<String, NoiseMark> read = new LinkedHashMap<String, NoiseMark>();
        int lines = 0;
        if (logFile.isFile()) {
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
                    throw corrupt(lineNumber, unreadable.getMessage());
                }
                if (version > VERSION) {
                    throw new NewerVersionException(logFile, "line " + lineNumber + " has version " + version);
                }
                if (!"marke".equals(string(record, "art"))) {
                    throw corrupt(lineNumber, "unknown record kind");
                }
                NoiseMark mark;
                try {
                    mark = fromJson(record);
                } catch (RuntimeException unreadable) {
                    throw corrupt(lineNumber, unreadable.getMessage());
                }
                read.remove(mark.getTextKey());
                read.put(mark.getTextKey(), mark);
                lines++;
            }
            if (end < content.length()) {
                System.err.println("[page-noise] dropping an unfinished last record of " + logFile);
                AtomicFiles.write(logFile, content.substring(0, end));
            }
        }
        marks.clear();
        marks.putAll(read);
        opened = true;
        return lines;
    }

    /** One mark without raw answers (stage 1). */
    public void record(NoiseMark mark) throws IOException {
        record(mark, Collections.<String>emptyList());
    }

    /**
     * One mark, written and flushed to disk as one line before it holds. Only after {@link #open()}; a mark that
     * cannot be written does not hold.
     */
    public synchronized void record(NoiseMark mark, List<String> answers) throws IOException {
        if (mark == null) {
            throw new IllegalArgumentException("no mark");
        }
        if (!opened) {
            throw new IllegalStateException("open the page-noise check's marks before writing to them");
        }
        if (logFile != null) {
            Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
            FileOutputStream out = new FileOutputStream(logFile, true);
            try {
                out.write((GSON.toJson(toJson(mark, answers)) + "\n").getBytes(AtomicFiles.UTF8));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        }
        marks.remove(mark.getTextKey());
        marks.put(mark.getTextKey(), mark);
    }

    /** The mark that holds for a text key, {@code null} when there is none. */
    public synchronized NoiseMark mark(String textKey) {
        return marks.get(textKey);
    }

    /** Whether the text is a page leftover: its mark holds and says RAUSCHEN. */
    @Override
    public synchronized boolean isNoise(String textKey) {
        NoiseMark mark = marks.get(textKey);
        return mark != null && mark.isNoise();
    }

    /** Every mark that holds, the latest written last. */
    public synchronized List<NoiseMark> all() {
        return new ArrayList<NoiseMark>(marks.values());
    }

    /** The number of texts with a mark. */
    public synchronized int size() {
        return marks.size();
    }

    static JsonObject toJson(NoiseMark mark, List<String> answers) {
        JsonObject json = new JsonObject();
        json.addProperty("v", VERSION);
        json.addProperty("art", "marke");
        json.addProperty("text", mark.getTextKey());
        json.addProperty("passage", mark.getPassageId());
        json.addProperty("quelle", mark.getSourceId());
        json.addProperty("urteil", mark.getVerdict() == null ? "" : mark.getVerdict().name());
        json.addProperty("klasse", mark.getKind().name());
        json.addProperty("stufe", mark.getStage());
        json.addProperty("grund", mark.getReason());
        json.addProperty("status", mark.getStatus().name());
        json.addProperty("fehler", mark.getProblem());
        json.addProperty("modell", mark.getModel());
        json.addProperty("fassung", mark.getVersion());
        json.addProperty("zeit", mark.getTimeMillis());
        json.addProperty("ms", mark.getMillis());
        JsonArray raw = new JsonArray();
        for (String answer : answers == null ? Collections.<String>emptyList() : answers) {
            raw.add(answer == null ? "" : answer);
        }
        json.add("rohantworten", raw);
        return json;
    }

    static NoiseMark fromJson(JsonObject json) {
        NoiseMark.Status status = NoiseMark.Status.valueOf(string(json, "status").toUpperCase(Locale.ROOT));
        String verdict = string(json, "urteil").toUpperCase(Locale.ROOT);
        String kind = string(json, "klasse").toUpperCase(Locale.ROOT);
        return new NoiseMark(string(json, "text"), string(json, "passage"), string(json, "quelle"),
                verdict.isEmpty() ? null : NoiseMark.Verdict.valueOf(verdict),
                kind.isEmpty() ? null : NoiseMark.Kind.valueOf(kind), json.get("stufe").getAsInt(),
                string(json, "grund"), status, string(json, "fehler"), string(json, "modell"),
                json.get("fassung").getAsInt(), json.get("zeit").getAsLong(),
                json.has("ms") ? json.get("ms").getAsLong() : 0L);
    }

    private CorruptProjectFileException corrupt(int lineNumber, String why) {
        return new CorruptProjectFileException(logFile, "The page-noise check's marks cannot be read (line "
                + lineNumber + "): " + why);
    }

    private static String string(JsonObject json, String field) {
        JsonElement value = json.get(field);
        return value == null || value.isJsonNull() || !value.isJsonPrimitive() ? "" : value.getAsString();
    }
}
