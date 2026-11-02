package com.aresstack.askai.state;

import com.aresstack.askai.settings.AskAiPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A small, separate store for transient UI/application state — which profile or model was last selected,
 * which mode was active, and so on. Each UI component reads and writes its own namespaced key. It lives in
 * its own {@code application-state.json} file next to the functional settings, so remembering a selection
 * never touches the proxy/TLS/STT configuration. Reads are tolerant (a missing or corrupt file starts empty)
 * and writes are atomic and never surface an error to the UI.
 */
public final class ApplicationStateService {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final File file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, String> values;

    public ApplicationStateService() {
        this(AskAiPaths.appDirectory().resolve("application-state.json").toFile());
    }

    public ApplicationStateService(File file) {
        this.file = file;
        this.values = load(file);
    }

    /** @return the stored value for the key, or {@code defaultValue} when it is absent. */
    public synchronized String get(String key, String defaultValue) {
        String value = key == null ? null : values.get(key);
        return value == null ? defaultValue : value;
    }

    public synchronized boolean getBoolean(String key, boolean defaultValue) {
        String value = key == null ? null : values.get(key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    /** Store a value (a null value removes the key) and persist immediately. Never throws to the caller. */
    public synchronized void putAndSave(String key, String value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        save();
    }

    private Map<String, String> load(File source) {
        if (source == null || !source.isFile()) {
            return new LinkedHashMap<String, String>();
        }
        Reader reader = null;
        try {
            reader = new InputStreamReader(new FileInputStream(source), UTF_8);
            Type type = new TypeToken<LinkedHashMap<String, String>>() { }.getType();
            Map<String, String> stored = gson.fromJson(reader, type);
            return stored == null ? new LinkedHashMap<String, String>()
                    : new LinkedHashMap<String, String>(stored);
        } catch (RuntimeException | IOException ex) {
            return new LinkedHashMap<String, String>(); // missing or corrupt state starts empty
        } finally {
            closeQuietly(reader);
        }
    }

    private void save() {
        try {
            File directory = file.getParentFile();
            if (directory != null) {
                directory.mkdirs();
            }
            File temp = new File(file.getParentFile(), file.getName() + ".tmp-" + System.nanoTime());
            Writer writer = null;
            try {
                writer = new OutputStreamWriter(new FileOutputStream(temp), UTF_8);
                gson.toJson(values, writer);
            } finally {
                closeQuietly(writer);
            }
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailed) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (RuntimeException | IOException ex) {
            // Never break the UI because a state file could not be written.
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}
