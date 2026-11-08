package com.aresstack.askai.research.transition;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The transition journal of D3, {@code state/transitions.jsonl}: one JSON record per line, appended and
 * flushed to disk. Kinds: {@code prepared} (the whole transition), {@code committed}, {@code done} (one
 * effect). A torn last line, the bytes after the final newline that a crash left behind, is ignored on
 * read and cut off before the next append; any other unreadable line makes the journal corrupt.
 */
public final class TransitionJournal {

    public static final String FILE_NAME = "transitions.jsonl";

    private final File file;

    public TransitionJournal(File file) {
        this.file = file;
    }

    public File getFile() {
        return file;
    }

    /** All complete records in file order; a missing file is an empty journal. */
    public synchronized List<JsonObject> read() throws CorruptProjectFileException {
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), AtomicFiles.UTF8);
        } catch (IOException unreadable) {
            throw new CorruptProjectFileException(file, "cannot read " + file.getName() + ": "
                    + unreadable.getMessage());
        }
        int end = content.lastIndexOf('\n');
        String complete = end < 0 ? "" : content.substring(0, end);
        List<JsonObject> records = new ArrayList<JsonObject>();
        int lineNumber = 0;
        for (String line : complete.split("\n", -1)) {
            lineNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                if (!record.has("kind") || !record.has("id")) {
                    throw new IllegalStateException("record without kind or id");
                }
                String kind = record.get("kind").getAsString();
                record.get("id").getAsString();
                if ("prepared".equals(kind)) {
                    PhaseTransition.fromJson(record.getAsJsonObject("transition")); // payload must decode
                } else if ("done".equals(kind)) {
                    record.get("effect").getAsString();
                } else if (!"committed".equals(kind)) {
                    throw new IllegalStateException("unknown record kind " + kind);
                }
                records.add(record);
            } catch (RuntimeException broken) {
                throw new CorruptProjectFileException(file, file.getName() + " line " + lineNumber
                        + " is unreadable");
            }
        }
        return records;
    }

    /** Cut off a torn last line so the next append starts on a fresh line. */
    synchronized void dropTornTail() throws IOException {
        if (!file.isFile()) {
            return;
        }
        byte[] bytes = Files.readAllBytes(file.toPath());
        int keep = bytes.length;
        while (keep > 0 && bytes[keep - 1] != '\n') {
            keep--;
        }
        if (keep < bytes.length) {
            RandomAccessFile raf = new RandomAccessFile(file, "rw");
            try {
                raf.setLength(keep);
                raf.getFD().sync();
            } finally {
                raf.close();
            }
        }
    }

    /** Append one record as a line and flush it to disk before returning. */
    public synchronized void append(JsonObject record) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) {
            Files.createDirectories(dir.toPath());
        }
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write((record.toString() + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }
}
