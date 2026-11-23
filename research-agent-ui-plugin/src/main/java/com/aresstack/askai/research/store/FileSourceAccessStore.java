package com.aresstack.askai.research.store;

import com.aresstack.askai.research.sources.SourceAccessLog;
import com.aresstack.askai.research.sources.SourceAccessState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists the project's {@link SourceAccessLog} as {@code state/source-access.properties} (UTF-8, atomic):
 * {@code <family>=<STATE> <url>}. A missing or corrupt file yields {@link SourceAccessLog#EMPTY}.
 */
public final class FileSourceAccessStore {

    private final File file;

    public FileSourceAccessStore(File stateDir) {
        this.file = new File(stateDir, "source-access.properties");
    }

    public synchronized SourceAccessLog load() {
        if (!file.isFile()) {
            return SourceAccessLog.EMPTY;
        }
        try {
            Properties props = StoreIo.loadUtf8Properties(file);
            List<SourceAccessLog.Entry> entries = new ArrayList<SourceAccessLog.Entry>();
            for (String family : new java.util.TreeSet<String>(props.stringPropertyNames())) {
                String value = props.getProperty(family, "").trim();
                int space = value.indexOf(' ');
                String state = space < 0 ? value : value.substring(0, space);
                String url = space < 0 ? "" : value.substring(space + 1).trim();
                try {
                    entries.add(new SourceAccessLog.Entry(family, SourceAccessState.valueOf(state), url));
                } catch (IllegalArgumentException unknownState) {
                    // an unknown state is skipped, never guessed
                }
            }
            return SourceAccessLog.of(entries);
        } catch (Exception corrupt) {
            return SourceAccessLog.EMPTY;
        }
    }

    public synchronized void save(SourceAccessLog log) throws IOException {
        Properties props = new Properties();
        for (SourceAccessLog.Entry e : log.entries()) {
            props.setProperty(e.getFamily(), e.getState().name() + (e.getUrl().isEmpty() ? "" : " " + e.getUrl()));
        }
        java.io.StringWriter out = new java.io.StringWriter();
        props.store(out, null);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        StoreIo.atomicWrite(file, out.toString());
    }
}
