package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.knowledge.ClusteringMonitor;
import com.aresstack.askai.research.knowledge.EmbeddingPort;
import com.aresstack.askai.research.knowledge.VectorMath;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineCandidate;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plan PR 22 (D6): the outline proposal from the approved concept and the clustered sources, WITHOUT a model call.
 * <ul>
 * <li>chapters: the effective first-level concept nodes in concept order;</li>
 * <li>sections: the second-level nodes; a chapter without them gets one section per sub-cluster of its passages,
 * titled "&lt;chapter title&gt;, part n" and flagged untitled;</li>
 * <li>nodes below the second level become key points of their section's default brief, and their passages count
 * for that section;</li>
 * <li>every passage goes to the nearest node by cosine against outline anchors (the node's full label path: book
 * title, parent labels, label), embedded once as QUERIES against the stored passage vectors; a passage is
 * assigned only above the threshold and with the margin over the nearest node of ANOTHER lineage (a node and
 * its own ancestors or descendants never compete: a passage near a section is near its chapter by construction,
 * live run 3 lost 1550 of 2068 passages to that false ambiguity);</li>
 * <li>leftover clusters that meet the topic minimums are listed as candidates, never inserted;</li>
 * <li>default briefs: purpose from label path and mission, key points from deeper nodes and the best passages,
 * a word target from the deliverable (a page counts as 300 words, provisional) spread by source count, or 400
 * to 1,100 words without a target length (Q7); sections with fewer than two sources are flagged thin.</li>
 * </ul>
 * The same input gives the same outline: every order is defined (concept order, then sizes and ids).
 */
public final class ConceptSeededOutlineBuilder {

    /** Words per page until the writer calibrates it (provisional, plan PR 22). */
    public static final int WORDS_PER_PAGE = 300;
    public static final int MIN_SECTION_WORDS = 400;
    public static final int MAX_SECTION_WORDS = 1100;
    static final int PASSAGE_KEY_POINTS = 3;
    static final int KEY_POINT_CHARS = 160;
    static final String PATH_SEPARATOR = " > ";

    /** The proposal and what it found. */
    public static final class Result {
        public final Outline outline;
        /** Passages assigned to a node. */
        public final int assignedPassages;
        /** Passages below the threshold or the margin. */
        public final int unassignedPassages;
        /** Unassigned because the best anchor similarity is below the threshold. */
        public final int belowThreshold;
        /** Unassigned because the best and the second-best anchor lie closer than the margin. */
        public final int marginTooSmall;
        /** The median of every passage's best anchor similarity ({@code 0} without passages): the calibration aid. */
        public final double medianBestSimilarity;
        /** The median of every passage's lead over the nearest anchor of another lineage ({@code 0} without). */
        public final double medianMargin;

        Result(Outline outline, int assignedPassages, int unassignedPassages, int belowThreshold,
               int marginTooSmall, double medianBestSimilarity, double medianMargin) {
            this.outline = outline;
            this.assignedPassages = assignedPassages;
            this.unassignedPassages = unassignedPassages;
            this.belowThreshold = belowThreshold;
            this.marginTooSmall = marginTooSmall;
            this.medianBestSimilarity = medianBestSimilarity;
            this.medianMargin = medianMargin;
        }
    }

    private final EmbeddingPort embeddings;

    public ConceptSeededOutlineBuilder(EmbeddingPort embeddings) {
        this.embeddings = embeddings;
    }

    /** One concept node with its place in the tree. */
    private static final class Anchor {
        final ConceptOutlineNode node;
        final int depth;
        final String path;
        final ConceptOutlineNode chapter;
        /** The second-level ancestor (or the node itself at depth 2); {@code null} at depth 1. */
        final ConceptOutlineNode section;

        Anchor(ConceptOutlineNode node, int depth, String path, ConceptOutlineNode chapter,
               ConceptOutlineNode section) {
            this.node = node;
            this.depth = depth;
            this.path = path;
            this.chapter = chapter;
            this.section = section;
        }
    }

    public Result build(OutlineGenerationInput input) {
        return build(input, ClusteringMonitor.NONE);
    }

    /**
     * @throws ClusteringMonitor.ClusteringCancelledException when the monitor cancels
     */
    public Result build(OutlineGenerationInput input, ClusteringMonitor monitor) {
        ClusteringMonitor m = monitor == null ? ClusteringMonitor.NONE : monitor;
        List<Anchor> anchors = new ArrayList<Anchor>();
        for (ConceptOutlineNode chapter : input.chapters) {
            collect(chapter, 1, input.bookTitle, chapter, null, anchors);
        }
        Map<String, OutlineGenerationInput.CorpusPassage> passages =
                new LinkedHashMap<String, OutlineGenerationInput.CorpusPassage>();
        for (OutlineGenerationInput.CorpusPassage passage : sortedPassages(input.passages)) {
            passages.put(passage.passageId, passage);
        }

        // Assignment: label paths as queries against the stored passage vectors (never re-embedded).
        Map<String, Anchor> assignment = new HashMap<String, Anchor>();
        Map<String, Double> similarity = new HashMap<String, Double>();
        int belowThreshold = 0;
        int marginTooSmall = 0;
        List<Double> bestScores = new ArrayList<Double>();
        List<Double> margins = new ArrayList<Double>();
        if (!anchors.isEmpty() && !passages.isEmpty()) {
            m.progress("Embedding the outline anchors", 0, passages.size());
            List<String> paths = new ArrayList<String>();
            for (Anchor anchor : anchors) {
                paths.add(anchor.path);
            }
            List<EmbeddingPort.EmbeddingVector> anchorVectors = embeddings.embedQueries(paths);
            if (anchorVectors.size() != anchors.size()) {
                throw new IllegalStateException("The embedding model returned " + anchorVectors.size()
                        + " vectors for " + anchors.size() + " outline anchors.");
            }
            int done = 0;
            for (OutlineGenerationInput.CorpusPassage passage : passages.values()) {
                if (m.isCancelled()) {
                    throw new ClusteringMonitor.ClusteringCancelledException();
                }
                int best = -1;
                double bestScore = Double.NEGATIVE_INFINITY;
                double[] scores = new double[anchors.size()];
                for (int i = 0; i < anchors.size(); i++) {
                    scores[i] = VectorMath.cosine(anchorVectors.get(i), passage.vector);
                    if (scores[i] > bestScore) {
                        bestScore = scores[i];
                        best = i;
                    }
                }
                double margin = bestScore - nearestOfAnotherLineage(anchors, scores, best);
                bestScores.add(bestScore);
                margins.add(margin);
                if (best >= 0 && bestScore >= input.settings.threshold && margin >= input.settings.margin) {
                    assignment.put(passage.passageId, anchors.get(best));
                    similarity.put(passage.passageId, bestScore);
                } else if (bestScore < input.settings.threshold) {
                    belowThreshold++;
                } else {
                    marginTooSmall++;
                }
                done++;
                if (done % 64 == 0 || done == passages.size()) {
                    m.progress("Assigning passages to the concept", done, passages.size());
                }
            }
        }

        Map<String, String> clusterOf = new HashMap<String, String>();
        for (OutlineGenerationInput.Cluster cluster : input.clusters) {
            for (String id : cluster.memberPassageIds) {
                clusterOf.put(id, cluster.clusterId);
            }
        }

        // Pools per chapter (direct passages) and per second-level node.
        Map<String, List<String>> chapterPool = new HashMap<String, List<String>>();
        Map<String, List<String>> sectionPool = new HashMap<String, List<String>>();
        for (String passageId : passages.keySet()) {
            Anchor anchor = assignment.get(passageId);
            if (anchor == null) {
                continue;
            }
            Map<String, List<String>> pool = anchor.section == null ? chapterPool : sectionPool;
            String key = anchor.section == null ? anchor.chapter.getId() : anchor.section.getId();
            List<String> list = pool.get(key);
            if (list == null) {
                list = new ArrayList<String>();
                pool.put(key, list);
            }
            list.add(passageId);
        }

        // Chapters and sections, with briefs filled after the word targets are known.
        List<ChapterDraft> drafts = new ArrayList<ChapterDraft>();
        for (ConceptOutlineNode chapter : input.chapters) {
            String chapterPath = join(input.bookTitle, chapter.getLabel());
            ChapterDraft draft = new ChapterDraft(chapter, chapterPath);
            List<String> direct = orEmpty(chapterPool.get(chapter.getId()));
            if (!chapter.getChildren().isEmpty()) {
                draft.passages.addAll(direct);
                for (ConceptOutlineNode child : chapter.getChildren()) {
                    SectionDraft section = new SectionDraft(child.getId(), child.getLabel(),
                            join(chapterPath, child.getLabel()), false,
                            Collections.<String>emptyList(), orEmpty(sectionPool.get(child.getId())));
                    deeperLabels(child, section.keyPoints);
                    draft.sections.add(section);
                }
            } else {
                subClusterSections(input, chapter, chapterPath, direct, clusterOf, draft);
            }
            drafts.add(draft);
        }

        assignWordTargets(drafts, input.deliverable, passages);

        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        for (ChapterDraft draft : drafts) {
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            int chapterWords = 0;
            for (SectionDraft section : draft.sections) {
                List<String> keyPoints = new ArrayList<String>(section.keyPoints);
                for (String passageId : bestPassages(section.passages, similarity, PASSAGE_KEY_POINTS)) {
                    String point = keyPoint(passages.get(passageId).text);
                    if (!point.isEmpty() && !keyPoints.contains(point)) {
                        keyPoints.add(point);
                    }
                }
                chapterWords += section.wordTarget;
                int sources = distinctSources(section.passages, passages);
                sections.add(new OutlineSection("", "", 0, section.title,
                        new OutlineBrief(purpose(section.path, input.mission), keyPoints, section.wordTarget),
                        new OutlineProvenance(section.conceptNodeId, section.clusterIds, sorted(section.passages)),
                        false, "", section.untitled, sources < 2));
            }
            chapters.add(new OutlineChapter("", 0, draft.node.getLabel(),
                    new OutlineBrief(purpose(draft.path, input.mission), Collections.<String>emptyList(),
                            chapterWords),
                    new OutlineProvenance(draft.node.getId(), Collections.<String>emptyList(),
                            sorted(draft.passages)),
                    false, "", sections));
        }
        List<OutlineCandidate> candidates = candidates(input, assignment, passages);
        return new Result(new Outline(chapters, candidates), assignment.size(),
                passages.size() - assignment.size(), belowThreshold, marginTooSmall, median(bestScores),
                median(margins));
    }

    /**
     * The best similarity among the anchors that are neither ancestors nor descendants of {@code best}: the
     * competitors the margin is measured against. {@code -1} (the cosine floor, so the margin is maximal) when no
     * anchor of another lineage exists, e.g. a concept with one chapter and no sections.
     */
    private static double nearestOfAnotherLineage(List<Anchor> anchors, double[] scores, int best) {
        double nearest = -1;
        String bestPath = anchors.get(best).path;
        for (int i = 0; i < anchors.size(); i++) {
            if (i == best || sameLineage(bestPath, anchors.get(i).path)) {
                continue;
            }
            nearest = Math.max(nearest, scores[i]);
        }
        return nearest;
    }

    /** Whether one label path is the other or a prefix of it (the node is an ancestor or a descendant). */
    static boolean sameLineage(String path, String other) {
        return path.equals(other) || path.startsWith(other + PATH_SEPARATOR)
                || other.startsWith(path + PATH_SEPARATOR);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<Double>(values);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static final class ChapterDraft {
        final ConceptOutlineNode node;
        final String path;
        final List<String> passages = new ArrayList<String>();
        final List<SectionDraft> sections = new ArrayList<SectionDraft>();

        ChapterDraft(ConceptOutlineNode node, String path) {
            this.node = node;
            this.path = path;
        }
    }

    private static final class SectionDraft {
        final String conceptNodeId;
        final String title;
        final String path;
        final boolean untitled;
        final List<String> clusterIds;
        final List<String> passages;
        final List<String> keyPoints = new ArrayList<String>();
        int wordTarget;

        SectionDraft(String conceptNodeId, String title, String path, boolean untitled, List<String> clusterIds,
                     List<String> passages) {
            this.conceptNodeId = conceptNodeId;
            this.title = title;
            this.path = path;
            this.untitled = untitled;
            this.clusterIds = new ArrayList<String>(clusterIds);
            this.passages = new ArrayList<String>(passages);
        }
    }

    private static void collect(ConceptOutlineNode node, int depth, String parentPath, ConceptOutlineNode chapter,
                                ConceptOutlineNode section, List<Anchor> anchors) {
        String path = join(parentPath, node.getLabel());
        ConceptOutlineNode sectionNode = depth == 2 ? node : section;
        anchors.add(new Anchor(node, depth, path, chapter, sectionNode));
        for (ConceptOutlineNode child : node.getChildren()) {
            collect(child, depth + 1, path, chapter, sectionNode, anchors);
        }
    }

    /** A chapter without second-level nodes: one section per qualifying sub-cluster of its passages. */
    private static void subClusterSections(OutlineGenerationInput input, ConceptOutlineNode chapter,
                                           String chapterPath, List<String> direct, Map<String, String> clusterOf,
                                           ChapterDraft draft) {
        final Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        for (String passageId : direct) {
            String clusterId = clusterOf.get(passageId);
            if (clusterId == null) {
                continue;
            }
            List<String> group = groups.get(clusterId);
            if (group == null) {
                group = new ArrayList<String>();
                groups.put(clusterId, group);
            }
            group.add(passageId);
        }
        List<String> qualifying = new ArrayList<String>();
        for (Map.Entry<String, List<String>> group : groups.entrySet()) {
            if (group.getValue().size() >= input.settings.minimumPassagesPerTopic) {
                qualifying.add(group.getKey());
            }
        }
        Collections.sort(qualifying, new Comparator<String>() {
            public int compare(String a, String b) {
                int bySize = Integer.compare(groups.get(b).size(), groups.get(a).size());
                return bySize != 0 ? bySize : a.compareTo(b);
            }
        });
        Set<String> placed = new LinkedHashSet<String>();
        int part = 0;
        for (String clusterId : qualifying) {
            part++;
            List<String> members = groups.get(clusterId);
            placed.addAll(members);
            draft.sections.add(new SectionDraft(chapter.getId(), partTitle(chapter.getLabel(), part),
                    chapterPath, true, Collections.singletonList(clusterId), members));
        }
        List<String> rest = new ArrayList<String>();
        for (String passageId : direct) {
            if (!placed.contains(passageId)) {
                rest.add(passageId);
            }
        }
        if (draft.sections.isEmpty() && !rest.isEmpty()) {
            // No sub-cluster is large enough: the chapter's passages form its one section.
            draft.sections.add(new SectionDraft(chapter.getId(), partTitle(chapter.getLabel(), 1), chapterPath,
                    true, Collections.<String>emptyList(), rest));
        } else {
            draft.passages.addAll(rest);
        }
    }

    static String partTitle(String chapterTitle, int part) {
        return chapterTitle + ", part " + part;
    }

    private static void deeperLabels(ConceptOutlineNode node, List<String> keyPoints) {
        for (ConceptOutlineNode child : node.getChildren()) {
            if (!child.getLabel().isEmpty() && !keyPoints.contains(child.getLabel())) {
                keyPoints.add(child.getLabel());
            }
            deeperLabels(child, keyPoints);
        }
    }

    /** Leftover clusters: most members unassigned, and the unassigned part meets both topic minimums. */
    private static List<OutlineCandidate> candidates(OutlineGenerationInput input, Map<String, Anchor> assignment,
                                                     Map<String, OutlineGenerationInput.CorpusPassage> passages) {
        List<OutlineCandidate> candidates = new ArrayList<OutlineCandidate>();
        for (OutlineGenerationInput.Cluster cluster : input.clusters) {
            List<String> leftover = new ArrayList<String>();
            int known = 0;
            for (String id : cluster.memberPassageIds) {
                if (!passages.containsKey(id)) {
                    continue;
                }
                known++;
                if (!assignment.containsKey(id)) {
                    leftover.add(id);
                }
            }
            if (known == 0 || leftover.size() * 2 <= known) {
                continue;
            }
            int sources = distinctSources(leftover, passages);
            if (leftover.size() < input.settings.minimumPassagesPerTopic
                    || sources < input.settings.minimumDistinctSourcesPerTopic) {
                continue;
            }
            List<String> representatives = new ArrayList<String>();
            for (String id : cluster.representativePassageIds) {
                if (leftover.contains(id)) {
                    representatives.add(id);
                }
            }
            candidates.add(new OutlineCandidate(cluster.clusterId, cluster.title, sorted(leftover), representatives,
                    sources));
        }
        Collections.sort(candidates, new Comparator<OutlineCandidate>() {
            public int compare(OutlineCandidate a, OutlineCandidate b) {
                int bySize = Integer.compare(b.getPassageIds().size(), a.getPassageIds().size());
                return bySize != 0 ? bySize : a.getClusterId().compareTo(b.getClusterId());
            }
        });
        return candidates;
    }

    /**
     * Word targets: the deliverable's length spread over all sections, weighted by their number of sources (at
     * least one); without a target length 400 words plus 100 per source, at most 1,100 (Q7).
     */
    static void assignWordTargets(List<ChapterDraft> drafts, String deliverable,
                                  Map<String, OutlineGenerationInput.CorpusPassage> passages) {
        List<SectionDraft> all = new ArrayList<SectionDraft>();
        for (ChapterDraft draft : drafts) {
            all.addAll(draft.sections);
        }
        if (all.isEmpty()) {
            return;
        }
        int total = targetWords(deliverable);
        if (total <= 0) {
            for (SectionDraft section : all) {
                int sources = distinctSources(section.passages, passages);
                section.wordTarget = Math.min(MAX_SECTION_WORDS, MIN_SECTION_WORDS + 100 * sources);
            }
            return;
        }
        long weights = 0;
        for (SectionDraft section : all) {
            weights += Math.max(1, distinctSources(section.passages, passages));
        }
        for (SectionDraft section : all) {
            int weight = Math.max(1, distinctSources(section.passages, passages));
            long share = Math.round((double) total * weight / weights);
            section.wordTarget = (int) Math.max(50L, Math.round(share / 50.0) * 50L);
        }
    }

    private static final Pattern LENGTH = Pattern.compile(
            "(\\d+)\\s*(?:-\\s*(\\d+))?\\s*(pages?|words?)", Pattern.CASE_INSENSITIVE);

    /**
     * The target length in words of a deliverable description ("120 pages", "20000-30000 words"), the middle of
     * a range; {@code 0} when it names no length.
     */
    public static int targetWords(String deliverable) {
        if (deliverable == null) {
            return 0;
        }
        Matcher matcher = LENGTH.matcher(deliverable);
        if (!matcher.find()) {
            return 0;
        }
        long min = Long.parseLong(matcher.group(1));
        long max = matcher.group(2) == null ? min : Long.parseLong(matcher.group(2));
        long middle = (min + max) / 2;
        boolean pages = matcher.group(3).toLowerCase(Locale.ROOT).startsWith("page");
        long words = pages ? middle * WORDS_PER_PAGE : middle;
        return (int) Math.min(Integer.MAX_VALUE, words);
    }

    private static List<String> bestPassages(List<String> ids, final Map<String, Double> similarity, int limit) {
        List<String> ranked = new ArrayList<String>(ids);
        Collections.sort(ranked, new Comparator<String>() {
            public int compare(String a, String b) {
                int byScore = Double.compare(score(similarity, b), score(similarity, a));
                return byScore != 0 ? byScore : a.compareTo(b);
            }
        });
        return ranked.size() > limit ? ranked.subList(0, limit) : ranked;
    }

    private static double score(Map<String, Double> similarity, String id) {
        Double value = similarity.get(id);
        return value == null ? Double.NEGATIVE_INFINITY : value;
    }

    /** The passage's first sentence, cut at a word boundary. */
    static String keyPoint(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        int end = -1;
        for (int i = 0; i < clean.length(); i++) {
            char c = clean.charAt(i);
            if ((c == '.' || c == '!' || c == '?') && (i + 1 == clean.length() || clean.charAt(i + 1) == ' ')
                    && i >= 20) {
                end = i + 1;
                break;
            }
        }
        String sentence = end > 0 ? clean.substring(0, end) : clean;
        if (sentence.length() <= KEY_POINT_CHARS) {
            return sentence;
        }
        int cut = sentence.lastIndexOf(' ', KEY_POINT_CHARS - 1);
        return sentence.substring(0, cut > 40 ? cut : KEY_POINT_CHARS - 1) + "…";
    }

    private static String purpose(String path, String mission) {
        return mission.isEmpty() ? "Covers " + path + "." : "Covers " + path + " for the mission: " + mission;
    }

    static int distinctSources(List<String> ids, Map<String, OutlineGenerationInput.CorpusPassage> passages) {
        Set<String> sources = new TreeSet<String>();
        for (String id : ids) {
            OutlineGenerationInput.CorpusPassage passage = passages.get(id);
            if (passage != null && !passage.sourceId.isEmpty()) {
                sources.add(passage.sourceId);
            }
        }
        return sources.size();
    }

    private static String join(String parent, String label) {
        return parent == null || parent.isEmpty() ? label : parent + PATH_SEPARATOR + label;
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? Collections.<String>emptyList() : list;
    }

    private static List<String> sorted(List<String> ids) {
        return new ArrayList<String>(new TreeSet<String>(ids));
    }

    private static List<OutlineGenerationInput.CorpusPassage> sortedPassages(
            List<OutlineGenerationInput.CorpusPassage> passages) {
        List<OutlineGenerationInput.CorpusPassage> sorted =
                new ArrayList<OutlineGenerationInput.CorpusPassage>(passages);
        Collections.sort(sorted, new Comparator<OutlineGenerationInput.CorpusPassage>() {
            public int compare(OutlineGenerationInput.CorpusPassage a, OutlineGenerationInput.CorpusPassage b) {
                return a.passageId.compareTo(b.passageId);
            }
        });
        return sorted;
    }
}
