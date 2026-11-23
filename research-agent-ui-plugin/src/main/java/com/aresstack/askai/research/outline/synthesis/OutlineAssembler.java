package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.topic.ConceptCoverage;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicProfile;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineCandidate;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineReport;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3: the checked plan as an outline proposal (new entries without ids; the store and the provenance merge
 * place them). Without a model call:
 * <ul>
 * <li>provenance: a section names the topic or sub-topic ids of its material and their passages; the rest of a
 * topic used only through its sub-topics goes with its largest sub-topic; a chapter names the concept node of its
 * first requirement (or of its first aligned topic) and the union of its sections;</li>
 * <li>significant topics beyond the prompt limit join the chapter of their topic group as sections of their own
 * (untitled), or become a chapter;</li>
 * <li>word targets follow the DISTINCT material: the book length spread by distinct passages, or the unbounded
 * target per section; a chapter's target is the sum;</li>
 * <li>a section with fewer sources than the analysis minimum is flagged thin;</li>
 * <li>topics the search fence reads as out of scope are candidates (listed, never inserted);</li>
 * <li>the report names the snapshot, the gaps with their reason, the findings and the notes.</li>
 * </ul>
 */
public final class OutlineAssembler {

    private final OutlineLengthSettings length;

    public OutlineAssembler(OutlineLengthSettings length) {
        this.length = length == null ? OutlineLengthSettings.defaults() : length;
    }

    /** A drafted section with its passages, before the word targets. */
    private static final class Draft {
        final String title;
        final String purpose;
        final List<String> keyPoints;
        final List<String> ids;
        final Set<String> passages;
        final boolean untitled;
        int wordTarget;

        Draft(String title, String purpose, List<String> keyPoints, List<String> ids, Set<String> passages,
              boolean untitled) {
            this.title = title;
            this.purpose = purpose;
            this.keyPoints = keyPoints;
            this.ids = ids;
            this.passages = passages;
            this.untitled = untitled;
        }
    }

    private static final class ChapterDraft {
        final String title;
        final String purpose;
        final String conceptNodeId;
        final String groupId;
        final List<String> ids;
        final List<String> keyPoints;
        final List<Draft> sections = new ArrayList<Draft>();

        ChapterDraft(String title, String purpose, String conceptNodeId, String groupId, List<String> ids,
                     List<String> keyPoints) {
            this.title = title;
            this.purpose = purpose;
            this.conceptNodeId = conceptNodeId;
            this.groupId = groupId;
            this.ids = ids;
            this.keyPoints = keyPoints;
        }

        Set<String> passages() {
            Set<String> passages = new LinkedHashSet<String>();
            for (Draft section : sections) {
                passages.addAll(section.passages);
            }
            return passages;
        }
    }

    /**
     * @param plan       the checked plan (after {@link OutlineQualityCheck#fix})
     * @param brief      the brief the plan's keys refer to
     * @param snapshotId the topic-analysis snapshot the brief was made from
     * @param findings   the quality check's findings on {@code plan}
     * @param notes      how the plan came about (synthesis notes, safe fixes)
     */
    public Outline assemble(OutlinePlan plan, SynthesisBrief brief, String snapshotId, List<PlanFinding> findings,
                            List<String> notes) {
        List<String> allNotes = new ArrayList<String>(notes == null ? Collections.<String>emptyList() : notes);
        List<ChapterDraft> chapters = new ArrayList<ChapterDraft>();
        for (PlannedChapter planned : plan.getChapters()) {
            chapters.add(chapter(planned, brief));
        }
        attachRests(plan, brief, chapters);
        placeOmitted(chapters, brief, allNotes);
        for (TopicProfile topic : brief.getFurnitureTopics()) {
            allNotes.add("Topic \"" + topic.getTitle() + "\" is outside the concept and "
                    + Math.round(topic.getRedundancyShare() * 100) + "% of its passages repeat each other; it was left "
                    + "out as page furniture.");
        }
        assignWordTargets(chapters, brief, allNotes);

        TopicAnalysis analysis = brief.getAnalysis();
        int minimumSources = analysis.getSettings().getMinimumSources();
        List<OutlineChapter> outline = new ArrayList<OutlineChapter>();
        for (ChapterDraft chapter : chapters) {
            List<OutlineSection> sections = new ArrayList<OutlineSection>();
            int words = 0;
            for (Draft section : chapter.sections) {
                List<String> passages = new ArrayList<String>(section.passages);
                sections.add(OutlineSection.draft(section.title,
                        new OutlineBrief(section.purpose, section.keyPoints, section.wordTarget),
                        new OutlineProvenance(chapter.conceptNodeId, section.ids, passages))
                        .withUntitled(section.untitled)
                        .withThin(analysis.sourceCount(passages) < minimumSources));
                words += section.wordTarget;
            }
            outline.add(OutlineChapter.draft(chapter.title, new OutlineBrief(chapter.purpose, chapter.keyPoints,
                    words), new OutlineProvenance(chapter.conceptNodeId, chapter.ids,
                    new ArrayList<String>(chapter.passages())), sections));
        }
        return new Outline(outline, candidates(brief), report(plan, brief, snapshotId, findings, allNotes));
    }

    private static ChapterDraft chapter(PlannedChapter planned, SynthesisBrief brief) {
        String conceptNodeId = "";
        List<String> keyPoints = new ArrayList<String>();
        for (String key : planned.getConceptKeys()) {
            SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
            if (requirement == null) {
                continue;
            }
            if (conceptNodeId.isEmpty()) {
                conceptNodeId = requirement.getCoverage().getAnchorId();
            }
            keyPoints.add(requirement.getCoverage().getLabel());
        }
        String groupId = "";
        List<String> ids = new ArrayList<String>();
        for (String key : planned.getMaterialKeys()) {
            ids.add(brief.idOf(key));
            SynthesisBrief.TopicEntry topic = brief.topic(brief.topicKeyOf(key));
            if (topic == null) {
                continue;
            }
            if (groupId.isEmpty()) {
                groupId = topic.getProfile().getGroupId();
            }
            if (conceptNodeId.isEmpty() && topic.getProfile().isConceptAligned()) {
                conceptNodeId = topic.getProfile().getConceptAnchorId();
            }
        }
        ChapterDraft chapter = new ChapterDraft(planned.getTitle(), planned.getPurpose(), conceptNodeId, groupId, ids,
                keyPoints);
        for (PlannedSection section : planned.getSections()) {
            Set<String> passages = new LinkedHashSet<String>();
            List<String> sectionIds = new ArrayList<String>();
            List<String> points = new ArrayList<String>();
            for (String key : section.getMaterialKeys()) {
                passages.addAll(brief.passagesOf(key));
                sectionIds.add(brief.idOf(key));
                points.addAll(materialPoints(brief, key, section.getTitle()));
            }
            // A title that only repeats the chapter title (kept from an earlier round or a fallback) is no name: the
            // label call names the section.
            chapter.sections.add(new Draft(section.getTitle(), section.getPurpose(), points, sectionIds, passages,
                    section.isUntitled() || OutlineChapter.repeatsChapterTitle(section.getTitle(),
                            planned.getTitle())));
        }
        return chapter;
    }

    /** The concept card of a planned chapter: of its first requirement, else of its first concept-aligned topic. */
    static String conceptNodeOf(PlannedChapter planned, SynthesisBrief brief) {
        for (String key : planned.getConceptKeys()) {
            SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
            if (requirement != null) {
                return requirement.getCoverage().getAnchorId();
            }
        }
        for (String key : planned.getMaterialKeys()) {
            SynthesisBrief.TopicEntry topic = brief.topic(brief.topicKeyOf(key));
            if (topic != null && topic.getProfile().isConceptAligned()) {
                return topic.getProfile().getConceptAnchorId();
            }
        }
        return "";
    }

    /** The titles a section covers: its sub-topics, or the topic or sub-topic itself when the title differs. */
    private static List<String> materialPoints(SynthesisBrief brief, String key, String sectionTitle) {
        List<String> points = new ArrayList<String>();
        SynthesisBrief.TopicEntry topic = brief.topic(key);
        if (topic != null && !topic.getSubtopics().isEmpty()) {
            for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                points.add(subtopic.getSubtopic().getTitle());
            }
            return points;
        }
        String title = topic != null ? topic.getProfile().getTitle()
                : brief.subtopic(key) != null ? brief.subtopic(key).getSubtopic().getTitle() : "";
        if (!title.isEmpty() && !title.equalsIgnoreCase(sectionTitle)) {
            points.add(title);
        }
        return points;
    }

    /**
     * The rest of a topic that no section names as a whole is used once, each passage in the section of its nearest
     * sub-topic over all chapters: concept parts of one topic may lie in different chapters, the rest is still used only once. The
     * sections decide, not the chapters: live outline acceptance 2026-11-12, a chapter named the whole topic, its
     * sections named the sub-topics, and the 8 passages of the topic's rest were in no section.
     */
    private static void attachRests(OutlinePlan plan, SynthesisBrief brief, List<ChapterDraft> chapters) {
        Set<String> topicKeysNamed = new LinkedHashSet<String>();
        Set<String> topicKeysOfSubtopics = new LinkedHashSet<String>();
        for (PlannedChapter planned : plan.getChapters()) {
            for (PlannedSection section : planned.getSections()) {
                for (String key : section.getMaterialKeys()) {
                    if (brief.topic(key) != null) {
                        topicKeysNamed.add(key);
                    } else if (brief.subtopic(key) != null) {
                        topicKeysOfSubtopics.add(brief.subtopic(key).getTopicKey());
                    }
                }
            }
        }
        for (String topicKey : topicKeysOfSubtopics) {
            if (topicKeysNamed.contains(topicKey)) {
                continue;
            }
            List<String> rest = brief.restOf(topicKey);
            if (rest.isEmpty()) {
                continue;
            }
            Draft largest = null;
            int best = -1;
            Map<String, Draft> sectionOf = new HashMap<String, Draft>();
            for (ChapterDraft chapter : chapters) {
                for (Draft section : chapter.sections) {
                    for (SynthesisBrief.SubtopicEntry subtopic : brief.topic(topicKey).getSubtopics()) {
                        String id = subtopic.getSubtopic().getSubtopicId();
                        if (!section.ids.contains(id)) {
                            continue;
                        }
                        sectionOf.put(id, section);
                        int distinct = subtopic.getSubtopic().getDistinctPassageCount();
                        if (distinct > best) {
                            largest = section;
                            best = distinct;
                        }
                    }
                }
            }
            // Each rest passage goes to the section of its nearest sub-topic (the analysis' rest home); a passage
            // whose home is in no section goes to the section of the largest sub-topic.
            Map<String, String> home = brief.topic(topicKey).getProfile().getRestSubtopicOf();
            for (String passage : rest) {
                Draft target = sectionOf.get(home.get(passage));
                if (target == null) {
                    target = largest;
                }
                if (target != null) {
                    target.passages.add(passage);
                }
            }
        }
    }

    /** Topics beyond the prompt limit join the chapter of their group, or become chapters. */
    private static void placeOmitted(List<ChapterDraft> chapters, SynthesisBrief brief, List<String> notes) {
        TopicAnalysis analysis = brief.getAnalysis();
        for (TopicProfile topic : brief.getOmittedTopics()) {
            Draft section = new Draft(topic.getTitle(), "", Collections.<String>emptyList(),
                    Collections.singletonList(topic.getTopicId()), new LinkedHashSet<String>(topic.getPassageIds()),
                    true);
            ChapterDraft target = null;
            int best = 0;
            for (ChapterDraft chapter : chapters) {
                if (!chapter.groupId.equals(topic.getGroupId())) {
                    continue;
                }
                int distinct = analysis.distinctCount(chapter.passages());
                if (distinct > best) {
                    target = chapter;
                    best = distinct;
                }
            }
            if (target == null) {
                target = new ChapterDraft(topic.getTitle(), "", topic.isConceptAligned() ? topic.getConceptAnchorId()
                        : "", topic.getGroupId(), new ArrayList<String>(Collections.singletonList(topic.getTopicId())),
                        new ArrayList<String>());
                chapters.add(target);
                notes.add("Topic \"" + topic.getTitle() + "\" did not fit the model's prompt; code made it a "
                        + "chapter.");
            } else {
                target.ids.add(topic.getTopicId());
                notes.add("Topic \"" + topic.getTitle() + "\" did not fit the model's prompt; code added it to \""
                        + target.title + "\", the chapter of its topic group.");
            }
            target.sections.add(section);
        }
    }

    /** The book length spread by distinct material, or the unbounded target per section. */
    private void assignWordTargets(List<ChapterDraft> chapters, SynthesisBrief brief, List<String> notes) {
        TopicAnalysis analysis = brief.getAnalysis();
        List<Draft> all = new ArrayList<Draft>();
        for (ChapterDraft chapter : chapters) {
            all.addAll(chapter.sections);
        }
        int total = length.targetWords(brief.getContext().getDeliverable());
        if (total <= 0) {
            for (Draft section : all) {
                section.wordTarget = length.unboundedTarget(analysis.distinctCount(section.passages));
            }
            return;
        }
        if (length.isSectionBudget()) {
            budgetWordTargets(chapters, brief, total, notes);
            return;
        }
        long weights = 0;
        for (Draft section : all) {
            weights += Math.max(1, analysis.distinctCount(section.passages));
        }
        for (Draft section : all) {
            int weight = Math.max(1, analysis.distinctCount(section.passages));
            section.wordTarget = length.round(Math.round((double) total * weight / weights));
        }
    }

    /**
     * Repair plan step 5a: the book length goes to the chapters (an explicit share first, the rest by distinct
     * material), a chapter keeps at most its words / the budget section words sections (the quality check already
     * merged; sections code added here are merged the same way), and each section gets at least the floor.
     */
    private void budgetWordTargets(List<ChapterDraft> chapters, SynthesisBrief brief, int total, List<String> notes) {
        TopicAnalysis analysis = brief.getAnalysis();
        long[] chapterWeights = new long[chapters.size()];
        double[] shares = new double[chapters.size()];
        for (int c = 0; c < chapters.size(); c++) {
            ChapterDraft chapter = chapters.get(c);
            for (Draft section : chapter.sections) {
                chapterWeights[c] += Math.max(1, analysis.distinctCount(section.passages));
            }
            shares[c] = brief.getContext().shareOf(chapter.conceptNodeId, chapter.title);
        }
        shares = ChapterShares.splitAmongSiblings(shares, conceptKeys(chapters), chapterWeights);
        int floor = length.getSectionWordFloor();
        int[] chapterWords = SectionBudget.spread(total, chapterWeights, shares, floor, length);
        for (int c = 0; c < chapters.size(); c++) {
            ChapterDraft chapter = chapters.get(c);
            mergeToLimit(chapter, length.budgetSectionLimit(chapterWords[c]), analysis, notes);
            long[] weights = new long[chapter.sections.size()];
            for (int i = 0; i < weights.length; i++) {
                weights[i] = analysis.distinctCount(chapter.sections.get(i).passages);
            }
            int[] words = SectionBudget.withFloor(chapterWords[c], weights, floor, length);
            for (int i = 0; i < weights.length; i++) {
                chapter.sections.get(i).wordTarget = words[i];
            }
        }
    }

    private static List<String> conceptKeys(List<ChapterDraft> chapters) {
        List<String> keys = new ArrayList<String>();
        for (ChapterDraft chapter : chapters) {
            keys.add(chapter.conceptNodeId);
        }
        return keys;
    }

    /**
     * While {@code chapter} has more sections than {@code limit}, its section with the fewest distinct passages
     * (an untitled one first) goes into the section whose title is closest, else into its previous (for the first:
     * next) neighbour; the receiving section keeps its title and takes the other's title as a key point.
     */
    private static void mergeToLimit(ChapterDraft chapter, int limit, TopicAnalysis analysis, List<String> notes) {
        List<Draft> sections = chapter.sections;
        while (sections.size() > Math.max(1, limit)) {
            int smallest = 0;
            int fewest = Integer.MAX_VALUE;
            boolean untitled = false;
            for (int i = 0; i < sections.size(); i++) {
                Draft section = sections.get(i);
                int distinct = analysis.distinctCount(section.passages);
                if ((section.untitled && !untitled) || (section.untitled == untitled && distinct < fewest)) {
                    smallest = i;
                    fewest = distinct;
                    untitled = section.untitled;
                }
            }
            Draft thin = sections.get(smallest);
            int into = smallest > 0 ? smallest - 1 : 1;
            double best = 0;
            for (int j = 0; j < sections.size(); j++) {
                if (j == smallest) {
                    continue;
                }
                double kinship = OutlineQualityCheck.titleKinship(thin.title, sections.get(j).title);
                if (kinship > best) {
                    best = kinship;
                    into = j;
                }
            }
            Draft target = sections.get(into);
            List<String> points = new ArrayList<String>(target.keyPoints);
            if (!thin.untitled && !thin.title.trim().isEmpty() && !points.contains(thin.title)) {
                points.add(thin.title);
            }
            for (String point : thin.keyPoints) {
                if (!points.contains(point)) {
                    points.add(point);
                }
            }
            List<String> ids = new ArrayList<String>(target.ids);
            for (String id : thin.ids) {
                if (!ids.contains(id)) {
                    ids.add(id);
                }
            }
            Set<String> passages = new LinkedHashSet<String>(target.passages);
            passages.addAll(thin.passages);
            sections.set(into, new Draft(target.title, target.purpose, points, ids, passages, target.untitled));
            sections.remove(smallest);
            notes.add("Section \"" + (thin.untitled ? "(untitled)" : thin.title) + "\" of \"" + chapter.title
                    + "\" went into \"" + (target.untitled ? "(untitled)" : target.title) + "\": the chapter's "
                    + "words allow " + Math.max(1, limit) + " sections.");
        }
    }

    private static List<OutlineCandidate> candidates(SynthesisBrief brief) {
        List<OutlineCandidate> candidates = new ArrayList<OutlineCandidate>();
        for (TopicProfile topic : brief.getOutOfScopeTopics()) {
            candidates.add(new OutlineCandidate(topic.getTopicId(), topic.getTitle(), topic.getPassageIds(),
                    topic.getRepresentativePassageIds(), topic.getSourceCount()));
        }
        return candidates;
    }

    private static OutlineReport report(OutlinePlan plan, SynthesisBrief brief, String snapshotId,
                                        List<PlanFinding> findings, List<String> notes) {
        List<OutlineReport.Gap> gaps = new ArrayList<OutlineReport.Gap>();
        for (String key : plan.getGapKeys()) {
            SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
            if (requirement == null) {
                continue;
            }
            ConceptCoverage coverage = requirement.getCoverage();
            gaps.add(new OutlineReport.Gap(coverage.getLabel(), coverage.getPath(), coverage.getStatus().name(),
                    gapReason(coverage)));
        }
        List<OutlineReport.Finding> reported = new ArrayList<OutlineReport.Finding>();
        if (findings != null) {
            for (PlanFinding finding : findings) {
                reported.add(new OutlineReport.Finding(finding.isBlocking(), finding.getCode(), finding.getMessage()));
            }
        }
        List<String> outOfScope = new ArrayList<String>();
        for (TopicProfile topic : brief.getOutOfScopeTopics()) {
            outOfScope.add(topic.getTitle());
        }
        return new OutlineReport(snapshotId, plan.isFromAnalysisOnly(), gaps, reported, notes, outOfScope);
    }

    /** Why a requirement is a gap, in the words of the analysis. */
    static String gapReason(ConceptCoverage coverage) {
        switch (coverage.getStatus()) {
            case GAP:
                return "no evidence in the research";
            case THIN:
                return ChapterPlanContract.thinReason(coverage);
            default:
                return "evidence exists, but no chapter treats it";
        }
    }
}
