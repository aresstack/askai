package com.aresstack.askai.research.search;

import com.aresstack.askai.research.runtime.search.service.UrlRegistry;
import com.aresstack.askai.research.transition.AtomicFiles;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The project's URL registry on disk, {@code <project>/search-plan/urls.jsonl}: one JSON line per registered page
 * ({@code url}, {@code origin}, {@code at}), appended in UTF-8 and flushed before {@link #register} returns. Read
 * once on construction; a torn or unreadable line (a crash mid-append) is skipped, never repaired.
 */
public final class FileUrlRegistry implements UrlRegistry {

    public static final String DIRECTORY = "search-plan";
    public static final String FILE_NAME = "urls.jsonl";

    private final File file;
    private final Map<String, String> origins = new LinkedHashMap<String, String>();

    public FileUrlRegistry(File projectDirectory) {
        this.file = new File(new File(projectDirectory, DIRECTORY), FILE_NAME);
        load();
    }

    public File getFile() {
        return file;
    }

    @Override
    public synchronized boolean isKnown(String normalizedUrl) {
        return origins.containsKey(normalizedUrl);
    }

    @Override
    public synchronized boolean register(String normalizedUrl, String origin) {
        if (normalizedUrl == null || normalizedUrl.isEmpty() || origins.containsKey(normalizedUrl)) {
            return false;
        }
        String from = origin == null ? "" : origin;
        origins.put(normalizedUrl, from);
        JsonObject line = new JsonObject();
        line.addProperty("url", normalizedUrl);
        line.addProperty("origin", from);
        line.addProperty("at", System.currentTimeMillis());
        try {
            append(line.toString());
        } catch (IOException e) {
            System.err.println("[search-service] cannot write " + file + ": " + e.getMessage());
        }
        return true;
    }

    private void append(String json) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create " + dir);
        }
        boolean tornTail = endsWithoutNewline();
        FileOutputStream out = new FileOutputStream(file, true);
        try {
            Writer writer = new OutputStreamWriter(out, AtomicFiles.UTF8);
            if (tornTail) {
                writer.write('\n'); // a crash left half a line: start on a fresh one
            }
            writer.write(json);
            writer.write('\n');
            writer.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
    }

    private boolean endsWithoutNewline() throws IOException {
        if (!file.isFile() || file.length() == 0) {
            return false;
        }
        RandomAccessFile raf = new RandomAccessFile(file, "r");
        try {
            raf.seek(raf.length() - 1);
            return raf.read() != '\n';
        } finally {
            raf.close();
        }
    }

    private void load() {
        if (!file.isFile()) {
            return;
        }
        String content;
        try {
            content = new String(Files.readAllBytes(file.toPath()), AtomicFiles.UTF8);
        } catch (IOException e) {
            System.err.println("[search-service] cannot read " + file + ": " + e.getMessage());
            return;
        }
        for (String line : content.split("\n")) {
            if (line.trim().isEmpty()) {
                continue;
            }
            try {
                JsonObject record = JsonParser.parseString(line).getAsJsonObject();
                String url = record.get("url").getAsString();
                String origin = record.has("origin") ? record.get("origin").getAsString() : "";
                if (!url.isEmpty() && !origins.containsKey(url)) {
                    origins.put(url, origin);
                }
            } catch (RuntimeException torn) {
                // a line a crash cut off: skipped, the registry stays usable
            }
        }
    }
}
