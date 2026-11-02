package com.aresstack.askai.groupchat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Append-only file-backed {@link RoomHistoryLog}.
 *
 * <p>Each record is framed as a 4-byte big-endian length followed by
 * {@link GroupChatWire#encodeMessage} bytes. The framing allows a truncated or corrupt tail
 * (e.g. after a crash mid-write) to be detected and skipped: reading simply stops at the first
 * record that cannot be decoded. Appends are flushed immediately; duplicate message IDs are
 * ignored. All methods are thread-safe.</p>
 */
public final class FileRoomHistoryLog implements RoomHistoryLog {

    private final File logFile;
    private final int maxRecordBytes;
    private final Set<String> messageIds = new LinkedHashSet<String>();
    private DataOutputStream out;
    private boolean closed;

    /** Open with the unlimited retention policy (no age/size caps; default per-record guard). */
    public FileRoomHistoryLog(File directory, String roomId) {
        this(directory, roomId, HistoryRetentionPolicy.UNLIMITED);
    }

    /**
     * Open (or create) the log for {@code roomId} inside {@code directory}.  The file name is the
     * sanitized room ID ({@code [^A-Za-z0-9._-]} replaced with {@code '_'}) plus {@code ".log"}.
     * When {@code policy} carries an age or size cap, older records are compacted away on open so
     * the append-only file stays bounded.
     *
     * @throws UncheckedIOException when the directory cannot be created or the file cannot be opened
     */
    public FileRoomHistoryLog(File directory, String roomId, HistoryRetentionPolicy policy) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("roomId must not be blank");
        }
        if (policy == null) {
            policy = HistoryRetentionPolicy.UNLIMITED;
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            throw new UncheckedIOException(new IOException("Cannot create directory: " + directory));
        }
        this.maxRecordBytes = policy.getMaxRecordBytes();
        String fileName = logFileName(roomId);
        this.logFile = new File(directory, fileName);
        truncateCorruptTail();
        List<GroupChatMessage> records = readRecords();
        if (policy.hasCaps()) {
            List<GroupChatMessage> kept = applyRetention(records, policy, System.currentTimeMillis());
            if (kept.size() != records.size()) {
                rewrite(kept);
                records = kept;
            }
        }
        for (GroupChatMessage message : records) {
            messageIds.add(message.getMessageId());
        }
        try {
            this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(logFile, true)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open history log: " + logFile, e);
        }
    }

    /** The sanitized log file name for a room ID (visible for the settings "Clear history" action). */
    public static String logFileName(String roomId) {
        return roomId.replaceAll("[^A-Za-z0-9._-]", "_") + ".log";
    }

    /**
     * Delete the on-disk log for {@code roomId} in {@code directory} without opening it — used by
     * the settings "Clear history" button when no session currently holds the log.
     *
     * @return {@code true} when a file was deleted
     */
    public static boolean deleteLog(File directory, String roomId) {
        if (directory == null || roomId == null || roomId.trim().isEmpty()) {
            return false;
        }
        File file = new File(directory, logFileName(roomId));
        return file.exists() && file.delete();
    }

    /**
     * Retention filter (visible for tests): drop records older than the age cap, then, if still
     * over the size cap, drop the oldest until the encoded total fits.  Input is assumed in
     * chronological append order; the same order is returned.
     */
    static List<GroupChatMessage> applyRetention(List<GroupChatMessage> records,
                                                 HistoryRetentionPolicy policy, long nowMillis) {
        List<GroupChatMessage> kept = new ArrayList<GroupChatMessage>();
        long minCreatedAt = policy.getMaxAgeMillis() > 0 ? nowMillis - policy.getMaxAgeMillis() : Long.MIN_VALUE;
        for (GroupChatMessage message : records) {
            if (message.getCreatedAt() >= minCreatedAt) {
                kept.add(message);
            }
        }
        long maxSize = policy.getMaxSizeBytes();
        if (maxSize > 0) {
            long total = 0;
            for (GroupChatMessage message : kept) {
                total += 4L + GroupChatWire.encodeMessage(message).length;
            }
            int start = 0;
            while (start < kept.size() && total > maxSize) {
                total -= 4L + GroupChatWire.encodeMessage(kept.get(start)).length;
                start++;
            }
            if (start > 0) {
                kept = new ArrayList<GroupChatMessage>(kept.subList(start, kept.size()));
            }
        }
        return kept;
    }

    @Override
    public synchronized void append(GroupChatMessage message) {
        if (message == null || closed || messageIds.contains(message.getMessageId())) {
            return;
        }
        byte[] record = GroupChatWire.encodeMessage(message);
        try {
            out.writeInt(record.length);
            out.write(record);
            out.flush();
            messageIds.add(message.getMessageId());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot append to history log: " + logFile, e);
        }
    }

    @Override
    public synchronized List<GroupChatMessage> readAll() {
        return HistoryMerger.sort(readRecords());
    }

    @Override
    public synchronized List<GroupChatMessage> readSince(long sinceMillis) {
        List<GroupChatMessage> since = new ArrayList<GroupChatMessage>();
        for (GroupChatMessage message : readRecords()) {
            if (message.getCreatedAt() >= sinceMillis) {
                since.add(message);
            }
        }
        return HistoryMerger.sort(since);
    }

    @Override
    public synchronized boolean contains(String messageId) {
        return messageId != null && messageIds.contains(messageId);
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            out.close();
        } catch (IOException e) {
            // Best effort: the log was flushed after every append.
        }
    }

    /**
     * Delete all stored history for this room and start from an empty log.  Safe to call on an
     * open log (used by the settings "Clear history" button while a session holds the log).
     */
    public synchronized void clear() {
        try {
            if (out != null) {
                out.close();
            }
            if (logFile.exists() && !logFile.delete()) {
                // Fall back to truncation when the file can't be deleted (e.g. still mapped).
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "rw");
                try {
                    raf.setLength(0);
                } finally {
                    raf.close();
                }
            }
            messageIds.clear();
            this.out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(logFile, true)));
            closed = false;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clear history log: " + logFile, e);
        }
    }

    /** Overwrite the log file with exactly {@code records}, via a temp file for atomicity. */
    private void rewrite(List<GroupChatMessage> records) {
        File temp = new File(logFile.getParentFile(), logFile.getName() + ".compact");
        try {
            DataOutputStream tempOut =
                    new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temp)));
            try {
                for (GroupChatMessage message : records) {
                    byte[] record = GroupChatWire.encodeMessage(message);
                    tempOut.writeInt(record.length);
                    tempOut.write(record);
                }
            } finally {
                tempOut.close();
            }
            java.nio.file.Files.move(temp.toPath(), logFile.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            temp.delete();
            throw new UncheckedIOException("Cannot compact history log: " + logFile, e);
        }
    }

    /**
     * Cut off a truncated or corrupt tail (e.g. after a crash mid-write) so subsequent appends
     * land on a valid record boundary and stay readable.
     */
    private void truncateCorruptTail() {
        if (!logFile.exists()) {
            return;
        }
        long validLength = 0;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(logFile)));
            try {
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException endOfLog) {
                        break;
                    }
                    if (length < 0 || length > maxRecordBytes) {
                        break;
                    }
                    byte[] record = new byte[length];
                    try {
                        in.readFully(record);
                        GroupChatWire.decodeMessage(record);
                    } catch (IOException corruptOrTruncated) {
                        break;
                    }
                    validLength += 4L + length;
                }
            } finally {
                in.close();
            }
            if (validLength < logFile.length()) {
                java.io.RandomAccessFile raf = new java.io.RandomAccessFile(logFile, "rw");
                try {
                    raf.setLength(validLength);
                } finally {
                    raf.close();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot repair history log: " + logFile, e);
        }
    }

    /** Read all decodable records; stops gracefully at a truncated or corrupt tail. */
    private List<GroupChatMessage> readRecords() {
        List<GroupChatMessage> messages = new ArrayList<GroupChatMessage>();
        if (!logFile.exists()) {
            return messages;
        }
        Set<String> seen = new LinkedHashSet<String>();
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(logFile)));
            try {
                while (true) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (EOFException endOfLog) {
                        break;
                    }
                    if (length < 0 || length > maxRecordBytes) {
                        break; // corrupt length prefix — ignore the tail
                    }
                    byte[] record = new byte[length];
                    try {
                        in.readFully(record);
                    } catch (EOFException truncatedTail) {
                        break; // truncated tail record — ignore
                    }
                    GroupChatMessage message;
                    try {
                        message = GroupChatWire.decodeMessage(record);
                    } catch (IOException corruptRecord) {
                        break; // corrupt tail record — ignore
                    }
                    if (seen.add(message.getMessageId())) {
                        messages.add(message);
                    }
                }
            } finally {
                in.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read history log: " + logFile, e);
        }
        return messages;
    }
}
