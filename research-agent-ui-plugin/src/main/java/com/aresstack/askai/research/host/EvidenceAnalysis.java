package com.aresstack.askai.research.host;

import com.aresstack.askai.research.domain.Passage;
import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjection;
import com.aresstack.askai.research.knowledge.live.LiveOutlineProjectionBuilder;
import com.aresstack.askai.research.knowledge.live.LiveTopicTree;
import com.aresstack.askai.research.knowledge.processing.live.ActiveKnowledgeCorpusReader;
import com.aresstack.askai.research.knowledge.processing.live.CorpusLimit;
import com.aresstack.askai.research.knowledge.topic.AnalysisPassage;
import com.aresstack.askai.research.knowledge.topic.ConceptAnchor;
import com.aresstack.askai.research.knowledge.topic.EvidenceReport;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicAnalyzer;
import com.aresstack.askai.research.outline.generate.ConceptFrame;
import com.aresstack.askai.research.outline.generate.ConceptOutlineNode;
import com.aresstack.askai.research.sources.ResearchSourceRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Phase 2 and 3 (D6): the ONE evidence analysis of a session. Phase 2 and Phase 3 share the knowledge store and this
 * analysis; only the use differs. The Sources phase reads the {@link #latest() current} snapshot to steer the research
 * (missing, thin and redundant concept nodes, research areas without a node, key terms for follow-up queries); the
 * outline generation takes one {@link #snapshotFor(ClusteringMonitor) consistent} snapshot and works only on it.
 * <p>
 * A run reads the eligible corpus (D4) with its STORED vectors, clusters it once with the merge tree
 * ({@link LiveOutlineProjectionBuilder#discoverTopicTree}), compares it with the concept nodes below the book frame
 * ({@link ConceptFrame}) and measures it ({@link TopicAnalyzer}, {@link EvidenceReport}). No language model is called;
 * the only embedding call embeds the concept label paths as queries, cached per path, so a later run with the same
 * concept embeds nothing. Every snapshot is immutable and named by an id derived from the corpus, the embedding model,
 * the concept and the settings: the same state gives the same id.
 */
public final class EvidenceAnalysis {

    /** What a run reads from the session. */
    public interface Inputs {
        /** The approved title, or the mission when the concept has none; "" before the concept is approved. */
        String bookTitle();

        /** The concept's effective first-level nodes of the approved revision; empty before one. */
        List<ConceptOutlineNode> conceptNodes() throws Exception;

        /** The approved concept revision (part of the snapshot id); 0 before one. */
        long conceptRevision();

        /** The eligible source ids (D4: research sources and the sources pin, minus excluded and blacklisted). */
        Set<String> eligibleSourceIds() throws Exception;

        Collection<ResearchSourceRecord> sources();

        /** The fingerprint of the stored passage vectors (part of the snapshot id). */
        String embeddingFingerprint();

        long now();
    }

    /** One immutable analysis state. */
    public static final class Snapshot {
        private final String id;
        private final long createdAt;
        private final String corpusFingerprint;
        private final int passages;
        private final int omittedPassages;
        private final boolean bookFrame;
        private final TopicAnalysis analysis;
        private final EvidenceReport report;
        private final Map<String, String> texts;

        Snapshot(String id, long createdAt, String corpusFingerprint, int passages, int omittedPassages,
                 boolean bookFrame, TopicAnalysis analysis, EvidenceReport report, Map<String, String> texts) {
            this.id = id;
            this.createdAt = createdAt;
            this.corpusFingerprint = corpusFingerprint;
            this.passages = passages;
            this.omittedPassages = omittedPassages;
            this.bookFrame = bookFrame;
            this.analysis = analysis;
            this.report = report;
            this.texts = Collections.unmodifiableMap(texts);
        }

        /** {@code analysis-<16 hex>}: the same corpus, embedding model, concept and settings give the same id. */
        public String getId() {
            return id;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public String getCorpusFingerprint() {
            return corpusFingerprint;
        }

        /** Eligible passages analysed. */
        public int getPassages() {
            return passages;
        }

        /** Passages the corpus limit kept out of the clustering (they still count for the concept coverage). */
        public int getOmittedPassages() {
            return omittedPassages;
        }

        /** Whether the concept's single first-level node was taken as the book frame. */
        public boolean hasBookFrame() {
            return bookFrame;
        }

        public TopicAnalysis analysis() {
            return analysis;
        }

        /** Per concept node OK / THIN / REDUNDANT / MISSING, with key terms. */
        public List<EvidenceReport.NodeEvidence> nodes() {
            return report.getNodes();
        }

        /** Significant research areas without a concept node, with key terms. */
        public List<EvidenceReport.ResearchArea> novelAreas() {
            return report.getNovelAreas();
        }

        public EvidenceReport report() {
            return report;
        }

        /** The text of an analysed passage ("" when unknown). */
        public String text(String passageId) {
            String text = texts.get(passageId);
            return text == null ? "" : text;
        }
    }

    private final Inputs inputs;
    private final ActiveKnowledgeCorpusReader corpus;
    private final EmbeddingPort embeddings;
    private final Supplier<EvidenceAnalysisSettings> settings;
    private final Executor background;
    private final Object runLock = new Object();
    private final Map<String, EmbeddingPort.EmbeddingVector> queryCache =
            new HashMap<String, EmbeddingPort.EmbeddingVector>();
    private final CopyOnWriteArrayList<Consumer<Snapshot>> listeners = new CopyOnWriteArrayList<Consumer<Snapshot>>();
    private final AtomicBoolean checkPending = new AtomicBoolean();
    private volatile Snapshot latest;
    private volatile int analysedPassages = -1;

    public EvidenceAnalysis(Inputs inputs, ActiveKnowledgeCorpusReader corpus, EmbeddingPort embeddings,
                            Supplier<EvidenceAnalysisSettings> settings, Executor background) {
        this.inputs = inputs;
        this.corpus = corpus;
        this.embeddings = embeddings;
        this.settings = settings == null ? new Supplier<EvidenceAnalysisSettings>() {
            public EvidenceAnalysisSettings get() {
                return EvidenceAnalysisSettings.defaults();
            }
        } : settings;
        this.background = background;
    }

    /** The current snapshot (Phase 2 reads this), or {@code null} before the first run. */
    public Snapshot latest() {
        return latest;
    }

    public void addListener(Consumer<Snapshot> listener) {
        if (listener != null) {
            listeners.addIfAbsent(listener);
        }
    }

    public void removeListener(Consumer<Snapshot> listener) {
        listeners.remove(listener);
    }

    /**
     * Runs the analysis now on the calling thread (one run at a time per session) and publishes the snapshot.
     * @throws ClusteringMonitor.ClusteringCancelledException when the monitor cancels
     * @throws Exception when the concept or the sources cannot be read
     */
    public Snapshot analyze(ClusteringMonitor monitor) throws Exception {
        synchronized (runLock) {
            Snapshot snapshot = run(monitor == null ? ClusteringMonitor.NONE : monitor);
            latest = snapshot;
            analysedPassages = snapshot.getPassages();
            for (Consumer<Snapshot> listener : listeners) {
                try {
                    listener.accept(snapshot);
                } catch (RuntimeException ignored) {
                    // a broken observer never stops the analysis
                }
            }
            return snapshot;
        }
    }

    /**
     * Phase 3: one consistent snapshot for the outline generation: the current one when it still describes the
     * eligible corpus with the same concept and settings, otherwise a fresh run. The caller works only on the
     * returned snapshot; sources accepted meanwhile change later snapshots, never this one.
     */
    public Snapshot snapshotFor(ClusteringMonitor monitor) throws Exception {
        if (monitor == null) {
            monitor = ClusteringMonitor.NONE;
        }
        synchronized (runLock) {
            Snapshot current = latest;
            if (current != null) {
                monitor.progress("Reading the corpus", 0, 1);
                ActiveKnowledgeCorpusReader.Corpus whole = read();
                if (current.getId().equals(snapshotId(fingerprint(whole), settings.get()))) {
                    return current;
                }
            }
            return analyze(monitor);
        }
    }

    /**
     * Phase 2 trigger: the knowledge store got new passages. A background check counts the eligible passages and runs
     * the analysis once the corpus has the minimum size and grew by the configured number of passages since the last
     * run. At most one check is pending; it never blocks the caller.
     */
    public void knowledgeChanged() {
        if (background == null || !checkPending.compareAndSet(false, true)) {
            return;
        }
        try {
            background.execute(new Runnable() {
                public void run() {
                    checkPending.set(false);
                    try {
                        int passages = read().getPassages().size();
                        if (dueFor(passages)) {
                            Snapshot snapshot = analyze(ClusteringMonitor.NONE);
                            System.err.println("[evidence-analysis] " + snapshot.getId() + " passages="
                                    + snapshot.getPassages() + " topics=" + snapshot.analysis().getTopics().size()
                                    + " novel=" + snapshot.novelAreas().size());
                        }
                    } catch (Exception failed) {
                        System.err.println("[evidence-analysis] background run failed: " + failed.getMessage());
                    }
                }
            });
        } catch (RuntimeException rejected) {
            checkPending.set(false);
        }
    }

    /** Whether a corpus of {@code passages} eligible passages is due for a new run. */
    boolean dueFor(int passages) {
        EvidenceAnalysisSettings current = settings.get();
        if (passages < current.getMinimumCorpusPassages()) {
            return false;
        }
        int last = analysedPassages;
        return last < 0 || passages - last >= current.getEveryNewPassages();
    }

    private ActiveKnowledgeCorpusReader.Corpus read() throws Exception {
        final Set<String> eligible = inputs.eligibleSourceIds();
        return corpus.read(new ActiveKnowledgeCorpusReader.SourceFilter() {
            public boolean includeSource(String sourceId) {
                return eligible.contains(sourceId);
            }

            public boolean isUserRelevant(String sourceId) {
                return false;
            }
        });
    }

    private Snapshot run(ClusteringMonitor monitor) throws Exception {
        EvidenceAnalysisSettings current = settings.get();
        monitor.progress("Reading the corpus", 0, 1);
        ActiveKnowledgeCorpusReader.Corpus whole = read();
        ActiveKnowledgeCorpusReader.Corpus limited = CorpusLimit.apply(whole, current.getCorpusLimit());
        LiveTopicTree tree = new LiveOutlineProjectionBuilder().discoverTopicTree(limited.getPassages(),
                limited.getVectors(), monitor);

        ConceptFrame frame = ConceptFrame.of(inputs.bookTitle(), inputs.conceptNodes());
        List<ConceptAnchor> anchors = anchors(frame);

        Map<String, String> hosts = new HashMap<String, String>();
        for (ResearchSourceRecord record : inputs.sources()) {
            hosts.put(record.getSourceId(), host(record.getUrl()));
        }
        List<AnalysisPassage> passages = new ArrayList<AnalysisPassage>();
        Map<String, String> texts = new LinkedHashMap<String, String>();
        for (Passage passage : whole.getPassages()) {
            String sourceId = whole.sourceIdOf(passage.getPassageId());
            String host = hosts.get(sourceId);
            passages.add(new AnalysisPassage(passage.getPassageId(), sourceId, host, passage.getText()));
            texts.put(passage.getPassageId(), passage.getText());
        }
        TopicAnalysis analysis = new TopicAnalyzer(current.getAnalysis()).analyze(passages, whole.getVectors(), tree,
                anchors, monitor);
        EvidenceReport report = EvidenceReport.of(analysis, texts, current.getKeyTerms());
        String corpusFingerprint = fingerprint(whole);
        return new Snapshot(snapshotId(corpusFingerprint, current), inputs.now(), corpusFingerprint,
                whole.getPassages().size(), whole.getPassages().size() - limited.getPassages().size(),
                frame.hasFrame(), analysis, report, texts);
    }

    /** The concept nodes below the frame with their query vectors; label paths are embedded once per text. */
    private List<ConceptAnchor> anchors(ConceptFrame frame) {
        List<ConceptFrame.Node> nodes = frame.nodes();
        List<String> missing = new ArrayList<String>();
        String world = inputs.embeddingFingerprint();
        for (ConceptFrame.Node node : nodes) {
            if (!queryCache.containsKey(world + "\n" + node.getPath()) && !missing.contains(node.getPath())) {
                missing.add(node.getPath());
            }
        }
        if (!missing.isEmpty()) {
            List<EmbeddingPort.EmbeddingVector> vectors = embeddings.embedQueries(missing);
            if (vectors.size() != missing.size()) {
                throw new IllegalStateException("The embedding model returned " + vectors.size() + " vectors for "
                        + missing.size() + " concept label paths.");
            }
            for (int i = 0; i < missing.size(); i++) {
                queryCache.put(world + "\n" + missing.get(i), vectors.get(i));
            }
        }
        List<ConceptAnchor> anchors = new ArrayList<ConceptAnchor>();
        for (ConceptFrame.Node node : nodes) {
            anchors.add(new ConceptAnchor(node.getId(), node.getLabel(), node.getPath(), node.getDepth(),
                    node.getParentId(), queryCache.get(world + "\n" + node.getPath())));
        }
        return anchors;
    }

    private static String fingerprint(ActiveKnowledgeCorpusReader.Corpus corpus) {
        List<String> ids = new ArrayList<String>();
        for (Passage passage : corpus.getPassages()) {
            ids.add(passage.getPassageId());
        }
        return LiveOutlineProjection.corpusFingerprintOf(ids);
    }

    private String snapshotId(String corpusFingerprint, EvidenceAnalysisSettings current) {
        String key = corpusFingerprint + "|" + inputs.embeddingFingerprint() + "|" + inputs.conceptRevision() + "|"
                + inputs.bookTitle() + "|" + current.fingerprint();
        return "analysis-" + Sha256.sha256(key).substring(0, 16);
    }

    /** The host of a URL, without "www."; "" when it has none. */
    static String host(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "";
        }
        try {
            String host = new java.net.URI(url.trim()).getHost();
            if (host == null) {
                return "";
            }
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (java.net.URISyntaxException invalid) {
            return "";
        }
    }

    /** SHA-256 hex for the snapshot id. */
    private static final class Sha256 {
        static String sha256(String value) {
            try {
                byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                    hex.append(Character.forDigit(b & 0xF, 16));
                }
                return hex.toString();
            } catch (java.security.NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 unavailable", impossible);
            }
        }
    }
}
