package com.aresstack.askai.research.search;

import com.aresstack.askai.research.runtime.search.service.PageCandidate;
import com.aresstack.askai.research.runtime.search.service.UrlRegistry;
import com.aresstack.askai.research.runtime.search.service.UrlRegistryException;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The project's URL registry on disk, {@code <project>/search-plan/urls.jsonl}: one JSON line per state change
 * ({@code state} {@code pending} with the page's data, or {@code completed} with the outcome), appended in UTF-8 and
 * synced before the call returns; the last line of an address wins. A write that fails leaves the in-memory state
 * unchanged and is reported as {@link UrlRegistryException}. Read once on construction; a torn or unreadable line
 * (a crash mid-append) is skipped, never repaired.
 */
public final class FileUrlRegistry implements UrlRegistry {

    public static final String DIRECTORY = "search-plan";
    public static final String FILE_NAME = "urls.jsonl";

    private final File file;
    /** null value = COMPLETED, otherwise the PENDING page. */
    private final Map<String, PageCandidate> entries = new LinkedHashMap<String, PageCandidate>();

    public FileUrlRegistry(File projectDirectory) {
        this.file = new File(new File(projectDirectory, DIRECTORY), FILE_NAME);
        load();
    }

    public File getFile() {
        return file;
    }

    @Override
    public synchronized boolean isKnown(String url) {
        return entries.containsKey(url);
    }

    @Override
    public synchronized boolean isCompleted(String url) {
        return entries.containsKey(url) && entries.get(url) == null;
    }

    @Override
    public synchronized boolean enqueue(PageCandidate page, String origin) throws UrlRegistryException {
        if (entries.containsKey(page.getUrl())) {
            return false;
        }
        JsonObject line = new JsonObject();
        line.addProperty("url", page.getUrl());
        line.addProperty("state", "pending");
        line.addProperty("origin", origin == null ? "" : origin);
        line.addProperty("title", page.getTitle());
        line.addProperty("snippet", page.getSnippet());
        line.addProperty("query", page.getQuery());
        line.addProperty("engine", page.getEngineHost());
        line.addProperty("rank", page.getRank());
        line.addProperty("wrapped", page.isWrapped());
        line.addProperty("at", System.currentTimeMillis());
        write(line);
        entries.put(page.getUrl(), page);
        return true;
    }

    @Override
    public synchronized void complete(String url, String outcome) throws UrlRegistryException {
        if (url == null || url.isEmpty() || isCompleted(url)) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("url", url);
        line.addProperty("state", "completed");
        line.addProperty("outcome", outcome == null ? "" : outcome);
        line.addProperty("at", System.currentTimeMillis());
        write(line);
        entries.remove(url);
        entries.put(url, null);
    }

    @Override
    public synchronized List<PageCandidate> pending() {
        List<PageCandidate> open = new ArrayList<PageCandidate>();
        for (PageCandidate page : entries.values()) {
            if (page != null) {
                open.add(page);
            }
        }
        return open;
    }

    private void write(JsonObject line) throws UrlRegistryException {
        try {
            append(line.toString());
        } catch (IOException e) {
            throw new UrlRegistryException("cannot write " + file + ": " + e.getMessage(), e);
        }
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
                JsonObject r = JsonParser.parseString(line).getAsJsonObject();
                String url = r.get("url").getAsString();
                if (url.isEmpty()) {
                    continue;
                }
                entries.remove(url);
                if ("pending".equals(r.get("state").getAsString())) {
                    entries.put(url, new PageCandidate(url, text(r, "title"), text(r, "snippet"), text(r, "query"),
                            text(r, "engine"), r.has("rank") ? r.get("rank").getAsInt() : 0,
                            r.has("wrapped") && r.get("wrapped").getAsBoolean()));
                } else {
                    entries.put(url, null);
                }
            } catch (RuntimeException torn) {
                // a line a crash cut off: skipped, the registry stays usable
            }
        }
    }

    private static String text(JsonObject r, String key) {
        return r.has(key) ? r.get(key).getAsString() : "";
    }
}
