package com.aresstack.askai.research.store;

import com.aresstack.askai.research.backend.ScopingAssistantUpdate;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * File-backed persistence of the LATEST scoping projection (the yellow search-suggestion tags + advice) so the
 * suggestions survive an app restart — the chat transcript comes back from history, but this display-only
 * "current working state" was previously in-memory only and vanished on reload.
 *
 * <p>The projection is not a historised artifact: a later scoping turn REPLACES it, so this store keeps exactly
 * ONE projection, overwritten on each update. It is bound to the session's project directory (per project, like
 * the brief store). Persistence is best-effort: an I/O failure just means the tags do not survive that restart,
 * never a crash.</p>
 */
public final class FileScopingProjectionStore {

    private final File file;

    public FileScopingProjectionStore(File dir) {
        this.file = new File(dir, "projection.properties");
    }

    /** Overwrite the persisted projection with {@code projection} (best-effort; a null projection is ignored). */
    public synchronized void save(ScopingAssistantUpdate projection) {
        if (projection == null) {
            return;
        }
        Properties p = new Properties();
        p.setProperty("phase", projection.getPhaseId());
        p.setProperty("advice", projection.getAdviceRecommendation());
        p.setProperty("adviceReason", projection.getAdviceReason());
        List<ScopingAssistantUpdate.Suggestion> suggestions = projection.getSearchSuggestions();
        p.setProperty("count", Integer.toString(suggestions.size()));
        for (int i = 0; i < suggestions.size(); i++) {
            ScopingAssistantUpdate.Suggestion s = suggestions.get(i);
            p.setProperty("q." + i, s.getQuery());
            p.setProperty("purpose." + i, s.getPurpose());
            p.setProperty("priority." + i, Integer.toString(s.getPriority()));
        }
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (OutputStream out = new FileOutputStream(file)) {
            p.store(out, "scoping projection (display-only; replaced each scoping turn)");
        } catch (IOException ignored) {
            // best-effort: the tags simply will not survive this restart
        }
    }

    /** The persisted projection, or {@code null} when none was written or it carries no usable suggestion. */
    public synchronized ScopingAssistantUpdate load() {
        if (!file.isFile()) {
            return null;
        }
        Properties p = new Properties();
        try {
            p.putAll(StoreIo.loadUtf8Properties(file));
        } catch (IOException ex) {
            return null;
        }
        int count = parseInt(p.getProperty("count"), 0);
        List<ScopingAssistantUpdate.Suggestion> suggestions =
                new ArrayList<ScopingAssistantUpdate.Suggestion>();
        for (int i = 0; i < count; i++) {
            String query = p.getProperty("q." + i, "");
            if (query.trim().isEmpty()) {
                continue;
            }
            suggestions.add(new ScopingAssistantUpdate.Suggestion(query,
                    p.getProperty("purpose." + i, ""), parseInt(p.getProperty("priority." + i), 0)));
        }
        if (suggestions.isEmpty()) {
            return null; // nothing worth restoring
        }
        return new ScopingAssistantUpdate(p.getProperty("phase", ""), suggestions,
                p.getProperty("advice", "NEUTRAL"), p.getProperty("adviceReason", ""));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }
}
