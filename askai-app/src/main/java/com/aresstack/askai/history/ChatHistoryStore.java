package com.aresstack.askai.history;

import com.aresstack.askai.settings.AskAiPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * File-backed store for chat conversations and their image attachments, keyed by chat UUID.
 *
 * <p>Layout under {@code <appDir>/chats}:</p>
 * <pre>
 *   chats/&lt;uuid&gt;.json                  the ChatRecord (messages + metadata)
 *   chats/&lt;uuid&gt;/&lt;attachmentId&gt;.&lt;ext&gt;  one file per stored image
 * </pre>
 *
 * <p>A chat and its attachments live together, so there is no separate image-only persistence.
 * All methods are best-effort and never throw to the caller — a failed read starts empty, a failed
 * write is swallowed so the UI is never disrupted.</p>
 */
public final class ChatHistoryStore {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final File root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ChatHistoryStore() {
        this(AskAiPaths.appDirectory().resolve("chats").toFile());
    }

    public ChatHistoryStore(File root) {
        this.root = root;
    }

    /** Persist (or overwrite) a chat.  Empty chats are removed instead of written. */
    public synchronized void save(ChatRecord record) {
        if (record == null || record.getId() == null) {
            return;
        }
        if (record.isEmpty()) {
            delete(record.getId());
            return;
        }
        ensureDir(root);
        File target = chatFile(record.getId());
        File temp = new File(root, record.getId() + ".json.tmp");
        try {
            Writer writer = new OutputStreamWriter(new java.io.FileOutputStream(temp), UTF_8);
            try {
                gson.toJson(record, writer);
            } finally {
                writer.close();
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ex) {
            temp.delete();
        }
    }

    /** @return the chat with this id, or {@code null} when it is absent or unreadable. */
    public synchronized ChatRecord load(String id) {
        File file = chatFile(id);
        if (!file.isFile()) {
            return null;
        }
        try {
            Reader reader = new InputStreamReader(new java.io.FileInputStream(file), UTF_8);
            try {
                return gson.fromJson(reader, ChatRecord.class);
            } finally {
                reader.close();
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** @return all stored chats, most-recently-modified first. */
    public synchronized List<ChatRecord> list() {
        List<ChatRecord> records = new ArrayList<ChatRecord>();
        File[] files = root.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".json")) {
                    ChatRecord record = load(file.getName().substring(0, file.getName().length() - 5));
                    if (record != null && record.getId() != null && !record.isEmpty()) {
                        records.add(record);
                    }
                }
            }
        }
        Collections.sort(records, new Comparator<ChatRecord>() {
            public int compare(ChatRecord a, ChatRecord b) {
                return Long.compare(b.getModifiedAt(), a.getModifiedAt());
            }
        });
        return records;
    }

    /** Delete a chat's JSON and its attachment directory. */
    public synchronized void delete(String id) {
        if (id == null) {
            return;
        }
        chatFile(id).delete();
        deleteRecursively(attachmentDir(id));
    }

    /**
     * Copy {@code source} into this chat's attachment directory and return its metadata record
     * (checksum, size, image dimensions).  Returns {@code null} when the file cannot be read.
     */
    public synchronized AttachmentRecord storeAttachment(String chatId, File source, String mediaType) {
        if (chatId == null || source == null || !source.isFile()) {
            return null;
        }
        try {
            byte[] bytes = Files.readAllBytes(source.toPath());
            String id = UUID.randomUUID().toString();
            String ext = extensionOf(source.getName());
            String storedName = id + (ext.isEmpty() ? "" : "." + ext);
            File dir = attachmentDir(chatId);
            ensureDir(dir);
            Files.write(new File(dir, storedName).toPath(), bytes);

            int width = 0;
            int height = 0;
            try {
                BufferedImage img = ImageIO.read(source);
                if (img != null) {
                    width = img.getWidth();
                    height = img.getHeight();
                }
            } catch (Exception ignored) {
                // Non-decodable formats (rare) keep 0×0 dimensions.
            }
            return new AttachmentRecord(id, source.getName(), mediaType,
                    bytes.length, width, height, sha256(bytes), storedName);
        } catch (Exception ex) {
            return null;
        }
    }

    /** @return the stored attachment file for a record, or {@code null} if it is missing. */
    public synchronized File attachmentFile(String chatId, String storedName) {
        if (chatId == null || storedName == null) {
            return null;
        }
        File file = new File(attachmentDir(chatId), storedName);
        return file.isFile() ? file : null;
    }

    // ------------------------------------------------------------------ internals

    private File chatFile(String id) {
        return new File(root, id + ".json");
    }

    private File attachmentDir(String id) {
        return new File(root, id);
    }

    private static void ensureDir(File dir) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && dot < name.length() - 1
                ? name.substring(dot + 1).replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT)
                : "";
    }

    private static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(Character.forDigit((b >> 4) & 0xF, 16));
                builder.append(Character.forDigit(b & 0xF, 16));
            }
            return builder.toString();
        } catch (Exception ex) {
            return "";
        }
    }
}
