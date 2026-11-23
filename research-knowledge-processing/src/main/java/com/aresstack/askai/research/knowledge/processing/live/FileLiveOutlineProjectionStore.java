package com.aresstack.askai.research.knowledge.processing.live;

import com.aresstack.askai.research.knowledge.processing.Utf8Properties;

import com.aresstack.askai.research.knowledge.live.LiveOutlineProjection;
import com.aresstack.askai.research.knowledge.live.LiveOutlineSection;
import com.aresstack.askai.research.knowledge.live.LiveTopicProjection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Persists the CURRENT {@link LiveOutlineProjection} of one project as a single rebuildable file under
 * {@code <projectDir>/knowledge/projections/live-outline/current.properties}. This store is deliberately
 * SEPARATE from the canonical {@code FileResearchProjectRepository} (captures/sentences/passages stay the
 * source of truth there): a live projection is derived state — losing or corrupting this file only costs a
 * rebuild from the canonical passages + vectors, never data, and it must NEVER block a session start.
 *
 * <p>Writes are atomic (tmp + validate-by-format + move); {@link #load()} returns {@code null} for a missing
 * OR corrupt file (schema mismatch, unreadable content) so the caller rebuilds instead of failing.</p>
 */
public final class FileLiveOutlineProjectionStore {

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int SCHEMA_VERSION = 2;

    private final File file;

    public FileLiveOutlineProjectionStore(File projectDirectory) {
        this.file = new File(new File(new File(new File(projectDirectory, "knowledge"), "projections"),
                "live-outline"), "current.properties");
    }

    /** Overwrite the persisted projection atomically. */
    public synchronized void save(LiveOutlineProjection projection) {
        if (projection == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        line(sb, "schemaVersion", Integer.toString(SCHEMA_VERSION));
        line(sb, "projectionRevision", Long.toString(projection.getProjectionRevision()));
        line(sb, "corpusFingerprint", projection.getCorpusFingerprint());
        line(sb, "embeddingFingerprint", projection.getEmbeddingFingerprint());
        line(sb, "generatedAtMillis", Long.toString(projection.getGeneratedAtMillis()));
        List<LiveTopicProjection> topics = projection.getTopics();
        line(sb, "topicCount", Integer.toString(topics.size()));
        for (int i = 0; i < topics.size(); i++) {
            LiveTopicProjection t = topics.get(i);
            line(sb, "t." + i + ".clusterId", t.getClusterId());
            line(sb, "t." + i + ".title", t.getTitle());
            line(sb, "t." + i + ".confidence", Double.toString(t.getConfidence()));
            line(sb, "t." + i + ".members", join(t.getMemberPassageIds()));
            line(sb, "t." + i + ".representatives", join(t.getRepresentativePassageIds()));
        }
        List<LiveOutlineSection> sections = projection.getSections();
        line(sb, "sectionCount", Integer.toString(sections.size()));
        for (int i = 0; i < sections.size(); i++) {
            LiveOutlineSection s = sections.get(i);
            line(sb, "s." + i + ".id", s.getProjectionSectionId());
            line(sb, "s." + i + ".title", s.getTitle());
            line(sb, "s." + i + ".parent", s.getParentProjectionSectionId());
            line(sb, "s." + i + ".clusterIds", join(s.getTopicClusterIds()));
            line(sb, "s." + i + ".passageIds", join(s.getPassageIds()));
            line(sb, "s." + i + ".uncovered", joinEscaped(s.getUncoveredQuestions()));
        }
        atomicWrite(file, sb.toString());
    }

    /** The persisted projection, or {@code null} when missing/corrupt/incompatible — the caller rebuilds. */
    public synchronized LiveOutlineProjection load() {
        if (!file.isFile()) {
            return null;
        }
        try {
            Properties p = new Properties();
            p.putAll(Utf8Properties.load(file));
            if (parseInt(p.getProperty("schemaVersion"), -1) != SCHEMA_VERSION) {
                return null; // incompatible → rebuild, never guess
            }
            List<LiveTopicProjection> topics = new ArrayList<LiveTopicProjection>();
            int topicCount = parseInt(p.getProperty("topicCount"), 0);
            for (int i = 0; i < topicCount; i++) {
                topics.add(new LiveTopicProjection(
                        p.getProperty("t." + i + ".clusterId", ""),
                        split(p.getProperty("t." + i + ".members", "")),
                        split(p.getProperty("t." + i + ".representatives", "")),
                        p.getProperty("t." + i + ".title", ""),
                        parseDouble(p.getProperty("t." + i + ".confidence"))));
            }
            List<LiveOutlineSection> sections = new ArrayList<LiveOutlineSection>();
            int sectionCount = parseInt(p.getProperty("sectionCount"), 0);
            for (int i = 0; i < sectionCount; i++) {
                sections.add(new LiveOutlineSection(
                        p.getProperty("s." + i + ".id", ""),
                        p.getProperty("s." + i + ".title", ""),
                        p.getProperty("s." + i + ".parent", ""),
                        split(p.getProperty("s." + i + ".clusterIds", "")),
                        split(p.getProperty("s." + i + ".passageIds", "")),
                        splitEscaped(p.getProperty("s." + i + ".uncovered", ""))));
            }
            return new LiveOutlineProjection(
                    parseLong(p.getProperty("projectionRevision")),
                    p.getProperty("corpusFingerprint", ""),
                    p.getProperty("embeddingFingerprint", ""),
                    parseLong(p.getProperty("generatedAtMillis")),
                    topics, sections);
        } catch (Exception corrupt) {
            return null; // a corrupt projection only costs a rebuild
        }
    }

    // ------------------------------------------------------------------ helpers

    private static void line(StringBuilder sb, String key, String value) {
        String v = value == null ? ""
                : value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
        sb.append(key).append('=').append(v).append('\n');
    }

    /** Comma join for ID lists (ids never contain commas is NOT assumed — ids are ours: hash/#-based). */
    private static String join(List<String> ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(ids.get(i));
        }
        return sb.toString();
    }

    private static List<String> split(String csv) {
        List<String> out = new ArrayList<String>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            if (!part.trim().isEmpty()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Free-text lists (questions may contain commas): tab-separated with tab/backslash escaping. */
    private static String joinEscaped(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append('\t');
            }
            sb.append(values.get(i).replace("\\", "\\\\").replace("\t", "\\t"));
        }
        return sb.toString();
    }

    private static List<String> splitEscaped(String joined) {
        List<String> out = new ArrayList<String>();
        if (joined == null || joined.isEmpty()) {
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (c == '\\' && i + 1 < joined.length()) {
                char next = joined.charAt(++i);
                current.append(next == 't' ? '\t' : next);
            } else if (c == '\t') {
                out.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        out.add(current.toString());
        return out;
    }

    private static void atomicWrite(File target, String content) {
        try {
            File parent = target.getParentFile();
            if (parent != null) {
                Files.createDirectories(parent.toPath());
            }
            File tmp = new File(parent, target.getName() + ".tmp");
            Files.write(tmp.toPath(), content.getBytes(UTF8));
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("cannot persist the live outline projection", ex);
        }
    }

    private static int parseInt(String v, int fallback) {
        try {
            return v == null ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0L : Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static double parseDouble(String v) {
        try {
            return v == null ? 0.0 : Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }
}
