package com.aresstack.askai.research.team;

import com.aresstack.askai.research.runtime.team.mailbox.MailboxStore;
import com.aresstack.askai.research.transition.AtomicFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The mailbox journal of one team run on disk, {@code <project>/team/<runId>/messages.jsonl} (plan T3): UTF-8,
 * append-only, every line flushed to disk before {@link #append} returns. A torn last line (bytes after the final
 * newline that a crash left behind) is ignored on read and cut off before the next append, like the transition
 * journal.
 */
public final class FileMailboxStore implements MailboxStore {

    public static final String FILE_NAME = "messages.jsonl";

    private final File file;
    private boolean tailChecked;

    public FileMailboxStore(File file) {
        this.file = file;
    }

    /** The journal of {@code runId} under {@code projectDir/team/<runId>/}. */
    public static FileMailboxStore forRun(File projectDir, String runId) {
        if (runId == null || !runId.matches("[A-Za-z0-9._-]{1,80}")) {
            throw new IllegalArgumentException("run id not usable as a folder name: " + runId);
        }
        return new FileMailboxStore(new File(new File(new File(projectDir, "team"), runId), FILE_NAME));
    }

    public File getFile() {
        return file;
    }

    public synchronized void append(String line) throws IOException {
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("a journal line must not contain a line break");
        }
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) {
            Files.createDirectories(dir.toPath());
        }
        if (!tailChecked) {
            dropTornTail();
            tailChecked = true;
        }
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            out.write((line + "\n").getBytes(AtomicFiles.UTF8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    public synchronized List<String> readLines() throws IOException {
        if (!file.isFile()) {
            return Collections.emptyList();
        }
        String content = new String(Files.readAllBytes(file.toPath()), AtomicFiles.UTF8);
        int end = content.lastIndexOf('\n');
        List<String> lines = new ArrayList<String>();
        if (end < 0) {
            return lines;
        }
        for (String line : content.substring(0, end).split("\n", -1)) {
            if (!line.trim().isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private void dropTornTail() throws IOException {
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
}
