package com.aresstack.askai.research.outline.brief;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineCandidate;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan PR 24: "Regenerate chapter" and "Add suggested chapter" as pure outline changes. The regenerated chapter
 * keeps its id, new sections and an adopted chapter come without ids (the store assigns fresh ones on save), and
 * every other chapter stays exactly as stored. The caller saves the result with the expected revision under the
 * session's {@code OutlineJobGuard}.
 */
public final class ChapterRegenerator {

    public static final int MIN_SECTIONS = 2;
    public static final int MAX_SECTIONS = 6;

    /** A regeneration's outcome: the new outline and, when the brief call failed, its reason. */
    public static final class Result {
        private final Outline outline;
        private final String briefFailure;

        Result(Outline outline, String briefFailure) {
            this.outline = outline;
            this.briefFailure = briefFailure;
        }

        public Outline getOutline() {
            return outline;
        }

        /** "Brief not generated ..." when the default briefs stayed, or {@code null}. */
        public String getBriefFailure() {
            return briefFailure;
        }
    }

    private final OutlineBriefWorker worker;
    private final OutlinePassages passages;
    public ChapterRegenerator(OutlineBriefWorker worker, OutlinePassages passages) {
        if (worker == null || passages == null) {
            throw new IllegalArgumentException("worker and passages are required");
        }
        this.worker = worker;
        this.passages = passages;
    }

    /**
     * Whether {@code chapter} was built from sub-clusters (its sections carry a cluster and the chapter's own
     * concept node, plan PR 22) and so may be re-split into a number of sections.
     */
    public static boolean isSubClusterChapter(OutlineChapter chapter) {
        if (chapter.getSections().isEmpty()) {
            return false;
        }
        String node = chapter.getProvenance().getConceptNodeId();
        for (OutlineSection section : chapter.getSections()) {
            if (section.getProvenance().getClusterIds().isEmpty()
                    || !node.equals(section.getProvenance().getConceptNodeId())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Regenerates one chapter. With {@code sectionCount} (2 to 6) a sub-cluster chapter is re-split into that
     * many sections, labelled and briefed; with 0 the sections stay and are briefed again. The comment steers the
     * labels and the briefs.
     * @throws IllegalArgumentException for an unknown chapter, a count out of range, a count for a chapter built
     *                                  from concept nodes (split and merge those in the editor) or too few
     *                                  passages
     */
    public Result regenerate(Outline stored, BriefContext context, String chapterId, int sectionCount,
                             String comment) {
        OutlineChapter chapter = stored.chapter(chapterId);
        if (chapter == null) {
            throw new IllegalArgumentException("No chapter \"" + chapterId + "\" in the outline.");
        }
        OutlineChapter working = chapter;
        if (sectionCount != 0) {
            if (sectionCount < MIN_SECTIONS || sectionCount > MAX_SECTIONS) {
                throw new IllegalArgumentException("A chapter is split into " + MIN_SECTIONS + " to "
                        + MAX_SECTIONS + " sections.");
            }
            if (!isSubClusterChapter(chapter)) {
                throw new IllegalArgumentException("\"" + chapter.getTitle() + "\" follows the concept. Split or "
                        + "merge its sections in the outline editor.");
            }
            working = chapter.withSections(resplit(context, chapter, sectionCount, comment));
        }
        OutlineBriefWorker.ChapterBriefs briefs = worker.brief(context, working, comment);
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        for (OutlineChapter each : stored.getChapters()) {
            chapters.add(each.getId().equals(chapterId) ? briefs.getChapter() : each);
        }
        return new Result(stored.withChapters(chapters), briefs.getFailure());
    }

    /**
     * Adopts a candidate cluster as a new last chapter with fresh ids: the label's title and summary, one section
     * over all its passages, briefed like any chapter.
     */
    public Result adopt(Outline stored, BriefContext context, String clusterId, String suggestedTitle,
                        List<String> passageIds, List<String> representativePassageIds, int wordTarget) {
        OutlineLabel label = worker.label(context, clusterId, "", texts(representativePassageIds), "");
        String title = label != null ? label.getTitle() : clean(suggestedTitle);
        if (title.isEmpty()) {
            throw new IllegalArgumentException("The suggested chapter has no title yet. Label it and try again.");
        }
        String summary = label != null ? label.getSummary() : "";
        OutlineProvenance provenance = new OutlineProvenance("", Collections.singletonList(clusterId), passageIds);
        OutlineSection section = OutlineSection.draft(title, new OutlineBrief(summary,
                Collections.<String>emptyList(), Math.max(0, wordTarget)), provenance);
        OutlineChapter chapter = OutlineChapter.draft(title, new OutlineBrief(summary,
                Collections.<String>emptyList(), 0), provenance, Collections.singletonList(section));
        OutlineBriefWorker.ChapterBriefs briefs = worker.brief(context, chapter, "");
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>(stored.getChapters());
        chapters.add(briefs.getChapter());
        List<OutlineCandidate> remaining = new ArrayList<OutlineCandidate>();
        for (OutlineCandidate candidate : stored.getCandidates()) {
            if (!candidate.getClusterId().equals(clusterId)) {
                remaining.add(candidate);
            }
        }
        return new Result(stored.withChapters(chapters).withCandidates(remaining), briefs.getFailure());
    }

    /** Adopts the stored candidate {@code clusterId} (its suggested title, passages and representatives). */
    public Result adopt(Outline stored, BriefContext context, String clusterId) {
        for (OutlineCandidate candidate : stored.getCandidates()) {
            if (candidate.getClusterId().equals(clusterId)) {
                return adopt(stored, context, clusterId, candidate.getSuggestedTitle(), candidate.getPassageIds(),
                        candidate.getRepresentativePassageIds(), averageSectionWords(stored));
            }
        }
        throw new IllegalArgumentException("The suggested chapter is no longer in the outline.");
    }

    /** The mean word target of the stored sections, or 750 (the middle of Q7's 400 to 1,100) without any. */
    static int averageSectionWords(Outline stored) {
        int total = 0;
        int count = 0;
        for (OutlineChapter chapter : stored.getChapters()) {
            for (OutlineSection section : chapter.getSections()) {
                if (section.getBrief().getWordTarget() > 0) {
                    total += section.getBrief().getWordTarget();
                    count++;
                }
            }
        }
        return count == 0 ? 750 : total / count;
    }

    private List<OutlineSection> resplit(BriefContext context, OutlineChapter chapter, int count, String comment) {
        Set<String> passageIds = new LinkedHashSet<String>(chapter.getProvenance().getPassageIds());
        int words = 0;
        for (OutlineSection section : chapter.getSections()) {
            passageIds.addAll(section.getProvenance().getPassageIds());
            words += section.getBrief().getWordTarget();
        }
        Map<String, float[]> vectors = passages.vectors(new ArrayList<String>(passageIds));
        List<List<String>> groups = SectionReclusterer.split(vectors, count);
        List<OutlineSection> sections = new ArrayList<OutlineSection>();
        for (int i = 0; i < groups.size(); i++) {
            List<String> group = groups.get(i);
            String clusterId = chapter.getId() + "~r" + count + "." + (i + 1);
            String fallback = chapter.getTitle() + ", part " + (i + 1);
            OutlineLabel label = worker.label(context, clusterId, chapter.getTitle(),
                    texts(group.subList(0, Math.min(LabelContract.MAX_EXCERPTS, group.size()))), comment);
            String title = label == null ? fallback : label.getTitle();
            String purpose = label == null ? "" : label.getSummary();
            List<String> sorted = new ArrayList<String>(group);
            Collections.sort(sorted);
            sections.add(OutlineSection.draft(title, new OutlineBrief(purpose, Collections.<String>emptyList(),
                    share(words, count, i)), new OutlineProvenance(chapter.getProvenance().getConceptNodeId(),
                    Collections.singletonList(clusterId), sorted)).withUntitled(label == null));
        }
        return sections;
    }

    /** The chapter's word target spread evenly; the first sections take the remainder. */
    static int share(int words, int count, int index) {
        return words / count + (index < words % count ? 1 : 0);
    }

    private List<String> texts(List<String> passageIds) {
        List<String> texts = new ArrayList<String>();
        if (passageIds != null) {
            for (String id : passageIds) {
                String text = passages.text(id);
                if (text != null && !text.trim().isEmpty()) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
