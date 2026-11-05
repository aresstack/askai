package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.knowledge.processing.Utf8Properties;

import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * #42 — persistence for the SHARED topic-discovery snapshot (topics only, no outline): the
 * same properties idiom as {@link FileLiveOutlineProjectionStore}, atomic write, {@code null}
 * for missing/corrupt/incompatible (the caller simply rebuilds). Phase 1 consumes this
 * snapshot directly; the outline build reads the SAME topics — one discovery, two consumers.
 */
public final class FileTopicSnapshotStore {

    private static final int SCHEMA_VERSION = 2;
    private static final String FILE_NAME = "topic-snapshot.properties";

    /** One persisted discovery result, pinned to corpus + embedding fingerprints. */
    public static final class TopicSnapshot {
        public final long revision;
        public final String corpusFingerprint;
        public final String embeddingFingerprint;
        public final long generatedAtMillis;
        public final List<LiveTopicProjection> topics;

        public TopicSnapshot(long revision, String corpusFingerprint,
                             String embeddingFingerprint, long generatedAtMillis,
                             List<LiveTopicProjection> topics) {
            this.revision = revision;
            this.corpusFingerprint = corpusFingerprint == null ? "" : corpusFingerprint;
            this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
            this.generatedAtMillis = generatedAtMillis;
            this.topics = java.util.Collections.unmodifiableList(
                    new ArrayList<LiveTopicProjection>(topics));
        }
    }

    private final File file;

    public FileTopicSnapshotStore(File projectDirectory) {
        this.file = new File(projectDirectory, FILE_NAME);
    }

    public synchronized void save(TopicSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        line(sb, "schemaVersion", Integer.toString(SCHEMA_VERSION));
        line(sb, "revision", Long.toString(snapshot.revision));
        line(sb, "corpusFingerprint", snapshot.corpusFingerprint);
        line(sb, "embeddingFingerprint", snapshot.embeddingFingerprint);
        line(sb, "generatedAtMillis", Long.toString(snapshot.generatedAtMillis));
        line(sb, "topicCount", Integer.toString(snapshot.topics.size()));
        for (int i = 0; i < snapshot.topics.size(); i++) {
            LiveTopicProjection t = snapshot.topics.get(i);
            line(sb, "t." + i + ".clusterId", t.getClusterId());
            line(sb, "t." + i + ".title", t.getTitle());
            line(sb, "t." + i + ".confidence", Double.toString(t.getConfidence()));
            line(sb, "t." + i + ".members", join(t.getMemberPassageIds()));
            line(sb, "t." + i + ".representatives", join(t.getRepresentativePassageIds()));
        }
        atomicWrite(sb.toString());
    }

    /** The persisted snapshot, or {@code null} when missing/corrupt/incompatible. */
    public synchronized TopicSnapshot load() {
        if (!file.isFile()) {
            return null;
        }
        try {
            Properties p = new Properties();
            p.putAll(Utf8Properties.load(file));
            if (Integer.parseInt(p.getProperty("schemaVersion", "-1")) != SCHEMA_VERSION) {
                return null; // incompatible → rebuild, never guess
            }
            List<LiveTopicProjection> topics = new ArrayList<LiveTopicProjection>();
            int count = Integer.parseInt(p.getProperty("topicCount", "0"));
            for (int i = 0; i < count; i++) {
                topics.add(new LiveTopicProjection(
                        p.getProperty("t." + i + ".clusterId", ""),
                        split(p.getProperty("t." + i + ".members", "")),
                        split(p.getProperty("t." + i + ".representatives", "")),
                        p.getProperty("t." + i + ".title", ""),
                        Double.parseDouble(p.getProperty("t." + i + ".confidence", "0"))));
            }
            return new TopicSnapshot(
                    Long.parseLong(p.getProperty("revision", "0")),
                    p.getProperty("corpusFingerprint", ""),
                    p.getProperty("embeddingFingerprint", ""),
                    Long.parseLong(p.getProperty("generatedAtMillis", "0")),
                    topics);
        } catch (Exception corrupt) {
            return null; // a corrupt snapshot only costs a rebuild
        }
    }

    private static void line(StringBuilder sb, String key, String value) {
        sb.append(key).append('=')
                .append(value == null ? "" : value.replace("\\", "\\\\").replace("\n", "\\n"))
                .append('\n');
    }

    private static String join(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    private static List<String> split(String joined) {
        List<String> values = new ArrayList<String>();
        if (joined != null && !joined.trim().isEmpty()) {
            for (String value : joined.split(",")) {
                if (!value.trim().isEmpty()) {
                    values.add(value.trim());
                }
            }
        }
        return values;
    }

    private void atomicWrite(String content) {
        try {
            File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception writeFailed) {
            // best effort — the next refresh retries; a missing snapshot only costs a rebuild
        }
    }
}
