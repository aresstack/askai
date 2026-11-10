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

    // ------------------------------------------------------------------ D6: the analysis history

    /** The directory of the analysis history, next to the topic snapshot. */
    private static final String ANALYSIS_DIR = "topic-analysis";

    /** One persisted analysis as the history shows it: identity, revisions and per-node / per-topic results. */
    public static final class AnalysisRecord {
        public final String id;
        public final int analysisVersion;
        public final long createdAt;
        public final String corpusFingerprint;
        public final String embeddingFingerprint;
        public final long conceptRevision;
        public final long scopeRevision;
        public final int passages;
        public final int omittedPassages;
        public final List<String> pendingSourceIds;
        /** Concept node id to its evidence status name (MISSING, REDUNDANT, THIN, UNCONFIRMED, ONE_SIDED, OK). */
        public final java.util.Map<String, String> nodeStatus;
        public final int topicCount;
        public final int novelAreaCount;

        AnalysisRecord(String id, int analysisVersion, long createdAt, String corpusFingerprint,
                       String embeddingFingerprint, long conceptRevision, long scopeRevision, int passages,
                       int omittedPassages, List<String> pendingSourceIds, java.util.Map<String, String> nodeStatus,
                       int topicCount, int novelAreaCount) {
            this.id = id;
            this.analysisVersion = analysisVersion;
            this.createdAt = createdAt;
            this.corpusFingerprint = corpusFingerprint;
            this.embeddingFingerprint = embeddingFingerprint;
            this.conceptRevision = conceptRevision;
            this.scopeRevision = scopeRevision;
            this.passages = passages;
            this.omittedPassages = omittedPassages;
            this.pendingSourceIds = java.util.Collections.unmodifiableList(pendingSourceIds);
            this.nodeStatus = java.util.Collections.unmodifiableMap(nodeStatus);
            this.topicCount = topicCount;
            this.novelAreaCount = novelAreaCount;
        }
    }

    /**
     * Persists one analysis under its id. A snapshot is immutable: an existing file is never overwritten (the same
     * id is the same state), so the history shows every state the research went through.
     */
    public synchronized void saveAnalysis(TopicAnalysisSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        File dir = new File(file.getParentFile(), ANALYSIS_DIR);
        File target = new File(dir, snapshot.getId() + ".properties");
        if (target.isFile()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        line(sb, "schemaVersion", Integer.toString(SCHEMA_VERSION));
        line(sb, "id", snapshot.getId());
        line(sb, "analysisVersion", Integer.toString(snapshot.getAnalysisVersion()));
        line(sb, "createdAt", Long.toString(snapshot.getCreatedAt()));
        line(sb, "corpusFingerprint", snapshot.getCorpusFingerprint());
        line(sb, "embeddingFingerprint", snapshot.getEmbeddingFingerprint());
        line(sb, "conceptRevision", Long.toString(snapshot.getConceptRevision()));
        line(sb, "scopeRevision", Long.toString(snapshot.getScopeRevision()));
        line(sb, "bookTitle", snapshot.getBookTitle());
        line(sb, "bookFrame", Boolean.toString(snapshot.hasBookFrame()));
        line(sb, "passages", Integer.toString(snapshot.getPassages()));
        line(sb, "omittedPassages", Integer.toString(snapshot.getOmittedPassages()));
        line(sb, "pendingSources", join(new ArrayList<String>(snapshot.getPendingSourceIds())));
        List<com.aresstack.askai.research.knowledge.topic.EvidenceReport.NodeEvidence> nodes = snapshot.nodes();
        line(sb, "nodeCount", Integer.toString(nodes.size()));
        for (int i = 0; i < nodes.size(); i++) {
            com.aresstack.askai.research.knowledge.topic.EvidenceReport.NodeEvidence node = nodes.get(i);
            line(sb, "n." + i + ".id", node.getAnchorId());
            line(sb, "n." + i + ".path", node.getPath());
            line(sb, "n." + i + ".status", node.getStatus().name());
            line(sb, "n." + i + ".passages", Integer.toString(node.getPassages()));
            line(sb, "n." + i + ".distinct", Integer.toString(node.getDistinctPassages()));
            line(sb, "n." + i + ".sources", Integer.toString(node.getSources()));
            line(sb, "n." + i + ".domains", Integer.toString(node.getDomains()));
            line(sb, "n." + i + ".keyTerms", join(node.getKeyTerms()));
        }
        List<com.aresstack.askai.research.knowledge.topic.TopicProfile> topics = snapshot.analysis().getTopics();
        line(sb, "topicCount", Integer.toString(topics.size()));
        for (int i = 0; i < topics.size(); i++) {
            com.aresstack.askai.research.knowledge.topic.TopicProfile topic = topics.get(i);
            line(sb, "t." + i + ".id", topic.getTopicId());
            line(sb, "t." + i + ".title", topic.getTitle());
            line(sb, "t." + i + ".passages", Integer.toString(topic.getPassageCount()));
            line(sb, "t." + i + ".distinct", Integer.toString(topic.getDistinctPassageCount()));
            line(sb, "t." + i + ".sources", Integer.toString(topic.getSourceCount()));
            line(sb, "t." + i + ".domains", Integer.toString(topic.getDomainCount()));
            line(sb, "t." + i + ".subtopics", Integer.toString(topic.getSubtopics().size()));
            line(sb, "t." + i + ".group", topic.getGroupId());
            line(sb, "t." + i + ".concept", topic.isConceptAligned() ? topic.getConceptAnchorId() : "");
            line(sb, "t." + i + ".significant", Boolean.toString(topic.isSignificant()));
            line(sb, "t." + i + ".novel", Boolean.toString(topic.isNovel()));
            line(sb, "t." + i + ".scope", topic.getScopeAlignment().getVerdict().name());
            line(sb, "t." + i + ".members", join(topic.getPassageIds()));
        }
        line(sb, "novelAreaCount", Integer.toString(snapshot.novelAreas().size()));
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File tmp = new File(dir, snapshot.getId() + ".properties.tmp");
            OutputStream out = new FileOutputStream(tmp);
            try {
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            Files.move(tmp.toPath(), target.toPath());
        } catch (Exception writeFailed) {
            System.err.println("[topic-analysis] history not written: " + writeFailed.getMessage());
        }
    }

    /** Every persisted analysis, oldest first; unreadable files are skipped. */
    public synchronized List<AnalysisRecord> analysisHistory() {
        List<AnalysisRecord> records = new ArrayList<AnalysisRecord>();
        File[] files = new File(file.getParentFile(), ANALYSIS_DIR).listFiles();
        if (files == null) {
            return records;
        }
        for (File f : files) {
            if (!f.getName().endsWith(".properties")) {
                continue;
            }
            try {
                Properties p = new Properties();
                p.putAll(Utf8Properties.load(f));
                java.util.Map<String, String> statuses = new java.util.LinkedHashMap<String, String>();
                int nodes = Integer.parseInt(p.getProperty("nodeCount", "0"));
                for (int i = 0; i < nodes; i++) {
                    statuses.put(p.getProperty("n." + i + ".id", ""), p.getProperty("n." + i + ".status", ""));
                }
                records.add(new AnalysisRecord(p.getProperty("id", ""),
                        Integer.parseInt(p.getProperty("analysisVersion", "0")),
                        Long.parseLong(p.getProperty("createdAt", "0")), p.getProperty("corpusFingerprint", ""),
                        p.getProperty("embeddingFingerprint", ""),
                        Long.parseLong(p.getProperty("conceptRevision", "0")),
                        Long.parseLong(p.getProperty("scopeRevision", "-1")),
                        Integer.parseInt(p.getProperty("passages", "0")),
                        Integer.parseInt(p.getProperty("omittedPassages", "0")),
                        split(p.getProperty("pendingSources", "")), statuses,
                        Integer.parseInt(p.getProperty("topicCount", "0")),
                        Integer.parseInt(p.getProperty("novelAreaCount", "0"))));
            } catch (Exception unreadable) {
                // one broken history file never hides the others
            }
        }
        java.util.Collections.sort(records, new java.util.Comparator<AnalysisRecord>() {
            public int compare(AnalysisRecord a, AnalysisRecord b) {
                int byTime = Long.compare(a.createdAt, b.createdAt);
                return byTime != 0 ? byTime : a.id.compareTo(b.id);
            }
        });
        return records;
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
