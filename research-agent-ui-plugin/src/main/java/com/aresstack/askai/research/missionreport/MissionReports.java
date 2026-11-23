package com.aresstack.askai.research.missionreport;

import com.aresstack.askai.research.transition.AtomicFiles;
import com.aresstack.askai.research.transition.CorruptProjectFileException;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;

/**
 * The mission reports of one project: {@code mission-report/reports.jsonl} in the project directory (next to
 * {@code research-memory/}). One JSON object per line, UTF-8, one per report ({@code "art":"bericht"}) with its time
 * and the report as {@link MissionReport#toJson()} gives it, so the report a release was made on stays readable.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code mission-report/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class MissionReports {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "reports.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private JsonObject latest;
    private int size;
    private boolean opened;

    public MissionReports(File projectDirectory) {
        File folder = new File(projectDirectory, "mission-report");
        this.logFile = new File(folder, GENERATION);
        this.pointerFile = new File(folder, "current");
    }

    public File logFile() {
        return logFile;
    }

    /** The file was written by a newer AskAI: this build neither reads nor changes it. */
    public static final class NewerVersionException extends Exception {
        private final File file;

        NewerVersionException(File file, String what) {
            super("The mission reports were written by a newer AskAI (" + what + "; this build reads versions 1 to "
                    + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * Reads every report; writing is allowed from now on. Nothing to read before the first report.
     *
     * @return the number of reports read
     */
    public synchronized int open() throws IOException, CorruptProjectFileException, NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        JsonObject last = null;
        int read = 0;
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
                if (!record.has("art") || !"bericht".equals(record.get("art").getAsString())
                        || !record.has("bericht") || !record.get("bericht").isJsonObject()) {
                    throw corrupt(lineNumber, "not a report");
                }
                last = record.getAsJsonObject("bericht");
                read++;
            }
            if (end < content.length()) {
                System.err.println("[mission-report] dropping an unfinished last record of " + logFile);
                AtomicFiles.write(logFile, content.substring(0, end));
            }
        }
        latest = last;
        size = read;
        opened = true;
        return read;
    }

    /** One report, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void record(MissionReport report) throws IOException {
        if (report == null) {
            throw new IllegalArgumentException("no report");
        }
        if (!opened) {
            throw new IllegalStateException("open the mission reports before writing to them");
        }
        JsonObject line = new JsonObject();
        line.addProperty("v", VERSION);
        line.addProperty("art", "bericht");
        line.addProperty("zeit", report.getTimeMillis());
        JsonObject json = report.toJson();
        line.add("bericht", json);
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(line) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        latest = json;
        size++;
    }

    /** The latest report as JSON, {@code null} before the first. */
    public synchronized JsonObject latest() {
        return latest == null ? null : latest.deepCopy();
    }

    /** The number of reports. */
    public synchronized int size() {
        return size;
    }

    private CorruptProjectFileException corrupt(int lineNumber, String why) {
        return new CorruptProjectFileException(logFile, "line " + lineNumber + " of the mission reports cannot be "
                + "read (" + why + ")");
    }
}
