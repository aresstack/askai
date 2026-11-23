package com.aresstack.askai.research.sectionplan;

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
 * The section plans of one project: {@code section-plan/plans.jsonl} in the project directory (next to
 * {@code research-memory/}). One JSON object per line, UTF-8, one per plan ({@code "art":"plan"}) with its time and
 * the plan as {@link SectionPlan#toJson()} gives it, so the plan a section was written from stays readable.
 * <p>
 * The rules of the other logs of the build plan: only appended to; a line that cannot be read fails closed
 * ({@link CorruptProjectFileException}), only an unfinished last line (no line end) is cut off; every line carries its
 * version ({@code "v"}), a newer version or a pointer {@code section-plan/current} to another generation stops the
 * opening with {@link NewerVersionException} before anything is written.
 */
public final class SectionPlans {

    /** The version this build writes; it reads every version from 1 up to it. */
    static final int VERSION = 1;

    /** The generation this build writes and reads, named as the pointer names it. */
    static final String GENERATION = "plans.jsonl";

    private static final Gson GSON = new Gson();

    private final File logFile;
    private final File pointerFile;
    private SectionPlan latest;
    private int size;
    private boolean opened;

    public SectionPlans(File projectDirectory) {
        File folder = new File(projectDirectory, "section-plan");
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
            super("The section plans were written by a newer AskAI (" + what + "; this build reads versions 1 to "
                    + VERSION + "). Nothing was changed; open the project with the newer build.");
            this.file = file;
        }

        public File getFile() {
            return file;
        }
    }

    /**
     * Reads every plan; writing is allowed from now on. Nothing to read before the first plan.
     *
     * @return the number of plans read
     */
    public synchronized int open() throws IOException, CorruptProjectFileException, NewerVersionException {
        if (pointerFile.isFile()) {
            String current = new String(Files.readAllBytes(pointerFile.toPath()), AtomicFiles.UTF8).trim();
            if (!GENERATION.equals(current)) {
                throw new NewerVersionException(pointerFile, "the current generation is '" + current + "'");
            }
        }
        SectionPlan last = null;
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
                if (!record.has("art") || !"plan".equals(record.get("art").getAsString())
                        || !record.has("plan") || !record.get("plan").isJsonObject()) {
                    throw corrupt(lineNumber, "not a plan");
                }
                try {
                    last = SectionPlan.fromJson(record.getAsJsonObject("plan"));
                } catch (RuntimeException unreadable) {
                    throw corrupt(lineNumber, unreadable.getMessage());
                }
                read++;
            }
            if (end < content.length()) {
                System.err.println("[section-plan] dropping an unfinished last record of " + logFile);
                AtomicFiles.write(logFile, content.substring(0, end));
            }
        }
        latest = last;
        size = read;
        opened = true;
        return read;
    }

    /** One plan, written and flushed to disk as one line. Only after {@link #open()}. */
    public synchronized void record(SectionPlan plan) throws IOException {
        if (plan == null) {
            throw new IllegalArgumentException("no plan");
        }
        if (!opened) {
            throw new IllegalStateException("open the section plans before writing to them");
        }
        JsonObject line = new JsonObject();
        line.addProperty("v", VERSION);
        line.addProperty("art", "plan");
        line.addProperty("zeit", plan.getTimeMillis());
        line.add("plan", plan.toJson());
        Files.createDirectories(logFile.getAbsoluteFile().getParentFile().toPath());
        FileOutputStream out = new FileOutputStream(logFile, true);
        try {
            out.write((GSON.toJson(line) + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        latest = plan;
        size++;
    }

    /** The latest plan, {@code null} before the first. */
    public synchronized SectionPlan latest() {
        return latest;
    }

    /** The number of plans. */
    public synchronized int size() {
        return size;
    }

    private CorruptProjectFileException corrupt(int lineNumber, String why) {
        return new CorruptProjectFileException(logFile, "line " + lineNumber + " of the section plans cannot be "
                + "read (" + why + ")");
    }
}
