package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.topic.ConceptCoverage;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysisSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3c: the deterministic quality check of a plan against its brief, before anything is saved. It never calls a
 * model. {@link #review} lists the findings (blocking ones send the plan back to the model for a revision round);
 * {@link #fix} applies only the corrections that change no decision of the model's beyond the obvious: a material
 * item used twice stays with its first chapter, an unused item joins the chapter of its topic group, a requirement
 * without evidence moves to the gaps, a major topic squeezed into one section is split by its sub-topics, a chapter
 * without sections is split by its material.
 * <p>Checked: every research topic is used once; every concept requirement is treated or listed as a gap; a
 * requirement named by a chapter has evidence there; two major areas of different groups are not one chapter; a
 * major topic with sub-topics gets more than one section; no two chapters share a title; every chapter has enough
 * evidence; the number of sections fits the book length.
 */
public final class OutlineQualityCheck implements OutlinePlanReview {

    public static final String TOPIC_UNUSED = "topic-unused";
    public static final String MATERIAL_TWICE = "material-twice";
    public static final String REQUIREMENT_LOST = "requirement-lost";
    public static final String REQUIREMENT_WITHOUT_EVIDENCE = "requirement-without-evidence";
    public static final String AREAS_MERGED = "areas-merged";
    public static final String MAJOR_TOPIC_FLAT = "major-topic-flat";
    public static final String DUPLICATE_TITLES = "duplicate-titles";
    public static final String THIN_CHAPTER = "thin-chapter";
    public static final String NO_SECTIONS = "no-sections";
    public static final String TOO_MANY_SECTIONS = "too-many-sections";
    public static final String TOO_FEW_SECTIONS = "too-few-sections";

    /** A plan after the safe fixes and what they changed. */
    public static final class Fixed {
        private final OutlinePlan plan;
        private final List<String> notes;

        Fixed(OutlinePlan plan, List<String> notes) {
            this.plan = plan;
            this.notes = Collections.unmodifiableList(new ArrayList<String>(notes));
        }

        public OutlinePlan getPlan() {
            return plan;
        }

        /** One line per correction, for the quality report. */
        public List<String> getNotes() {
            return notes;
        }
    }

    private final OutlineLengthSettings length;

    public OutlineQualityCheck(OutlineLengthSettings length) {
        this.length = length == null ? OutlineLengthSettings.defaults() : length;
    }

    // ------------------------------------------------------------------ review

    public List<PlanFinding> review(OutlinePlan plan, SynthesisBrief brief) {
        List<PlanFinding> blocking = new ArrayList<PlanFinding>();
        List<PlanFinding> warnings = new ArrayList<PlanFinding>();
        Map<String, Integer> ownerOfUnit = new LinkedHashMap<String, Integer>();
        int index = 0;
        for (PlannedChapter chapter : plan.getChapters()) {
            index++;
            for (String key : chapter.getMaterialKeys()) {
                for (String unit : units(brief, key)) {
                    Integer previous = ownerOfUnit.get(unit);
                    if (previous == null) {
                        ownerOfUnit.put(unit, index);
                    } else if (previous != index) {
                        blocking.add(PlanFinding.blocking(MATERIAL_TWICE, describe(brief, unit) + " is in chapter "
                                + previous + " and chapter " + index + "; one item of material belongs to one "
                                + "chapter."));
                    }
                }
            }
        }
        for (SynthesisBrief.TopicEntry topic : brief.getTopics()) {
            List<String> missing = new ArrayList<String>();
            for (String unit : units(brief, topic.getKey())) {
                if (!ownerOfUnit.containsKey(unit)) {
                    missing.add(unit);
                }
            }
            if (!missing.isEmpty()) {
                blocking.add(PlanFinding.blocking(TOPIC_UNUSED, missing.size() == units(brief, topic.getKey()).size()
                        ? describe(brief, topic.getKey()) + " is in no chapter."
                        : "Sub-topics " + String.join(", ", missing) + " of " + describe(brief, topic.getKey())
                        + " are in no chapter."));
            }
        }
        requirements(plan, brief, ownerOfUnit.keySet(), blocking, warnings);
        index = 0;
        Set<String> titles = new LinkedHashSet<String>();
        for (PlannedChapter chapter : plan.getChapters()) {
            index++;
            areas(chapter, index, brief, blocking);
            if (!titles.add(normal(chapter.getTitle()))) {
                blocking.add(PlanFinding.blocking(DUPLICATE_TITLES, "Two chapters are called \"" + chapter.getTitle()
                        + "\"; merge them or tell them apart."));
            }
            chapterEvidence(chapter, index, brief, warnings);
            sections(chapter, index, brief, warnings);
        }
        lengthFit(plan, brief, warnings);
        List<PlanFinding> all = new ArrayList<PlanFinding>(blocking);
        all.addAll(warnings);
        return all;
    }

    private void requirements(OutlinePlan plan, SynthesisBrief brief, Set<String> usedUnits,
                              List<PlanFinding> blocking, List<PlanFinding> warnings) {
        Set<String> gaps = new LinkedHashSet<String>(plan.getGapKeys());
        Set<String> treated = treatedRequirements(plan, brief, usedUnits);
        for (SynthesisBrief.RequirementEntry requirement : brief.getRequirements()) {
            String key = requirement.getKey();
            if (!gaps.contains(key) && !treated.contains(key)) {
                blocking.add(PlanFinding.blocking(REQUIREMENT_LOST, "Concept requirement " + key + " \""
                        + requirement.getCoverage().getLabel() + "\" is in no chapter and not listed as a gap."));
            }
        }
        int index = 0;
        for (PlannedChapter chapter : plan.getChapters()) {
            index++;
            Set<String> evidenced = evidencedRequirements(chapter, brief);
            for (String key : chapter.getConceptKeys()) {
                SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
                if (requirement != null && requirement.getCoverage().getStatus() == ConceptCoverage.Status.GAP
                        && !evidenced.contains(key)) {
                    blocking.add(PlanFinding.blocking(REQUIREMENT_WITHOUT_EVIDENCE, "Chapter " + index + " claims "
                            + "concept requirement " + key + " \"" + requirement.getCoverage().getLabel()
                            + "\", which has no evidence; list it as a gap."));
                }
            }
        }
        for (String key : gaps) {
            SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
            if (requirement != null && requirement.getCoverage().getStatus() == ConceptCoverage.Status.COVERED
                    && treated.contains(key)) {
                warnings.add(PlanFinding.warning(REQUIREMENT_LOST, "Concept requirement " + key + " \""
                        + requirement.getCoverage().getLabel() + "\" is covered by the research but listed as a "
                        + "gap."));
            }
        }
    }

    /** Requirements a chapter names or whose aligned topics are used. */
    private static Set<String> treatedRequirements(OutlinePlan plan, SynthesisBrief brief, Set<String> usedUnits) {
        Set<String> treated = new LinkedHashSet<String>();
        for (PlannedChapter chapter : plan.getChapters()) {
            treated.addAll(chapter.getConceptKeys());
        }
        for (SynthesisBrief.TopicEntry topic : brief.getTopics()) {
            for (String unit : units(brief, topic.getKey())) {
                if (!usedUnits.contains(unit)) {
                    continue;
                }
                if (!topic.getConceptKey().isEmpty()) {
                    treated.add(topic.getConceptKey());
                }
                SynthesisBrief.SubtopicEntry part = brief.subtopic(unit);
                if (part != null && !part.getConceptKey().isEmpty()) {
                    treated.add(part.getConceptKey()); // a used concept part treats its requirement
                }
            }
        }
        return treated;
    }

    /** Requirements with an aligned topic among the chapter's material. */
    private static Set<String> evidencedRequirements(PlannedChapter chapter, SynthesisBrief brief) {
        Set<String> evidenced = new LinkedHashSet<String>();
        for (String key : chapter.getMaterialKeys()) {
            SynthesisBrief.TopicEntry topic = brief.topic(brief.topicKeyOf(key));
            if (topic != null && !topic.getConceptKey().isEmpty()) {
                evidenced.add(topic.getConceptKey());
            }
            for (String unit : units(brief, key)) {
                SynthesisBrief.SubtopicEntry part = brief.subtopic(unit);
                if (part != null && !part.getConceptKey().isEmpty()) {
                    evidenced.add(part.getConceptKey());
                }
            }
        }
        return evidenced;
    }

    /**
     * Two whole major areas of different topic groups in one chapter (a single sub-topic moved to where it fits is
     * no merged area).
     */
    private static void areas(PlannedChapter chapter, int index, SynthesisBrief brief, List<PlanFinding> blocking) {
        Map<String, String> majorTopicOfGroup = new LinkedHashMap<String, String>();
        for (String key : chapter.getMaterialKeys()) {
            SynthesisBrief.TopicEntry topic = brief.topic(key);
            if (topic != null && topic.getProfile().isMajor() && !majorTopicOfGroup.containsKey(topic.getGroupKey())) {
                majorTopicOfGroup.put(topic.getGroupKey(), topic.getKey());
            }
        }
        if (majorTopicOfGroup.size() > 1) {
            List<String> described = new ArrayList<String>();
            for (String topicKey : majorTopicOfGroup.values()) {
                described.add(describe(brief, topicKey));
            }
            blocking.add(PlanFinding.blocking(AREAS_MERGED, "Chapter " + index + " \"" + chapter.getTitle()
                    + "\" combines independent major research areas: " + String.join(" and ", described)
                    + ". Each is large enough for a chapter of its own."));
        }
    }

    private static void chapterEvidence(PlannedChapter chapter, int index, SynthesisBrief brief,
                                        List<PlanFinding> warnings) {
        TopicAnalysis analysis = brief.getAnalysis();
        TopicAnalysisSettings settings = analysis.getSettings();
        Set<String> passages = passagesOf(chapter.getMaterialKeys(), brief);
        int distinct = analysis.distinctCount(passages);
        int sources = analysis.sourceCount(passages);
        if (!settings.isMaterialCovered(distinct) || sources < settings.getMinimumSources()) {
            warnings.add(PlanFinding.warning(THIN_CHAPTER, "Chapter " + index + " \"" + chapter.getTitle()
                    + "\" rests on " + distinct + " distinct passages from " + sources + " sources."));
        }
    }

    private static void sections(PlannedChapter chapter, int index, SynthesisBrief brief, List<PlanFinding> warnings) {
        if (chapter.getSections().isEmpty()) {
            warnings.add(PlanFinding.warning(NO_SECTIONS, "Chapter " + index + " \"" + chapter.getTitle()
                    + "\" has no sections yet."));
            return;
        }
        for (SynthesisBrief.TopicEntry topic : flatMajorTopics(chapter, brief)) {
            warnings.add(PlanFinding.warning(MAJOR_TOPIC_FLAT, describe(brief, topic.getKey()) + " has "
                    + topic.getSubtopics().size() + " sub-topics but only one section in chapter " + index + "."));
        }
    }

    private void lengthFit(OutlinePlan plan, SynthesisBrief brief, List<PlanFinding> warnings) {
        int target = length.targetWords(brief.getContext().getDeliverable());
        int sections = 0;
        for (PlannedChapter chapter : plan.getChapters()) {
            sections += chapter.getSections().size();
        }
        if (target <= 0 || sections == 0) {
            return;
        }
        long perSection = target / sections;
        if (perSection < length.getMinSectionWords()) {
            warnings.add(PlanFinding.warning(TOO_MANY_SECTIONS, sections + " sections leave about " + perSection
                    + " words each of the book's " + target + " words; fewer, broader sections would fit."));
        } else if (perSection > length.getMaxSectionWords()) {
            warnings.add(PlanFinding.warning(TOO_FEW_SECTIONS, sections + " sections would need about " + perSection
                    + " words each for the book's " + target + " words; the research may be too thin for the "
                    + "length."));
        }
    }

    // ------------------------------------------------------------------ safe fixes

    /** The plan with the safe corrections applied (see the class comment); the model is not asked again. */
    public Fixed fix(OutlinePlan plan, SynthesisBrief brief) {
        List<String> notes = new ArrayList<String>();
        OutlinePlan fixed = materialOnce(plan, brief, notes);
        fixed = placeUnused(fixed, brief, notes);
        fixed = requirementsToGaps(fixed, brief, notes);
        fixed = splitFlatMajorTopics(fixed, brief, notes);
        fixed = sectionsForEveryChapter(fixed, brief, notes);
        return new Fixed(fixed, notes);
    }

    /**
     * The chapter that keeps each unit: a concept part goes to the first chapter that names it (or its topic) AND
     * treats its concept requirement; every other unit stays with the first chapter naming it.
     * <p>
     * Live run 4 (2026-11-12): the first chapter named the whole topic, the chapters on occupational safety, law and
     * implementation named it again with their requirements; first-come ownership gave every concept part to the
     * first chapter and dropped the other three as "only repeated material".
     */
    static Map<String, Integer> owners(OutlinePlan plan, SynthesisBrief brief) {
        Map<String, Integer> owner = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < plan.getChapters().size(); i++) {
            PlannedChapter chapter = plan.getChapters().get(i);
            for (String key : chapter.getMaterialKeys()) {
                for (String unit : units(brief, key)) {
                    SynthesisBrief.SubtopicEntry part = brief.subtopic(unit);
                    if (!owner.containsKey(unit) && part != null && !part.getConceptKey().isEmpty()
                            && chapter.getConceptKeys().contains(part.getConceptKey())) {
                        owner.put(unit, i);
                    }
                }
            }
        }
        for (int i = 0; i < plan.getChapters().size(); i++) {
            for (String key : plan.getChapters().get(i).getMaterialKeys()) {
                for (String unit : units(brief, key)) {
                    if (!owner.containsKey(unit)) {
                        owner.put(unit, i);
                    }
                }
            }
        }
        return owner;
    }

    /**
     * An item used twice stays with its owner ({@link #owners}); chapters and sections left empty are dropped.
     */
    private static OutlinePlan materialOnce(OutlinePlan plan, SynthesisBrief brief, List<String> notes) {
        Map<String, Integer> owner = owners(plan, brief);
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (int i = 0; i < plan.getChapters().size(); i++) {
            PlannedChapter chapter = plan.getChapters().get(i);
            List<String> material = owned(chapter.getMaterialKeys(), brief, owner, i);
            if (material.isEmpty()) {
                notes.add("\"" + chapter.getTitle() + "\" only repeated material of earlier chapters and was dropped.");
                continue;
            }
            if (!material.equals(chapter.getMaterialKeys())) {
                notes.add("Material of \"" + chapter.getTitle() + "\" that an earlier chapter already uses was "
                        + "removed from it.");
            }
            List<PlannedSection> sections = new ArrayList<PlannedSection>();
            for (PlannedSection section : chapter.getSections()) {
                List<String> keys = owned(section.getMaterialKeys(), brief, owner, i);
                if (!keys.isEmpty()) {
                    sections.add(section.withMaterialKeys(keys));
                }
            }
            chapters.add(new PlannedChapter(chapter.getTitle(), chapter.getPurpose(), material,
                    chapter.getConceptKeys(), sections, chapter.isUntitled()));
        }
        return plan.withChapters(chapters);
    }

    /** The keys of {@code keys} whose units chapter {@code index} owns; a partly owned topic gives its sub-topics. */
    private static List<String> owned(List<String> keys, SynthesisBrief brief, Map<String, Integer> owner,
                                      int index) {
        Set<String> result = new LinkedHashSet<String>();
        for (String key : keys) {
            List<String> units = units(brief, key);
            List<String> mine = new ArrayList<String>();
            for (String unit : units) {
                Integer chapter = owner.get(unit);
                if (chapter != null && chapter == index) {
                    mine.add(unit);
                }
            }
            if (mine.size() == units.size() && !units.isEmpty()) {
                result.add(key);
            } else {
                result.addAll(mine);
            }
        }
        return new ArrayList<String>(result);
    }

    /**
     * An unused item joins the chapter that holds most distinct material of its topic group, as a section of its own
     * (untitled, so the label call names it); without such a chapter its topic becomes a chapter.
     */
    private static OutlinePlan placeUnused(OutlinePlan plan, SynthesisBrief brief, List<String> notes) {
        Set<String> used = new LinkedHashSet<String>();
        for (PlannedChapter chapter : plan.getChapters()) {
            for (String key : chapter.getMaterialKeys()) {
                used.addAll(units(brief, key));
            }
        }
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>(plan.getChapters());
        for (SynthesisBrief.TopicEntry topic : brief.getTopics()) {
            List<String> missing = new ArrayList<String>();
            for (String unit : units(brief, topic.getKey())) {
                if (!used.contains(unit)) {
                    missing.add(unit);
                }
            }
            for (String unit : new ArrayList<String>(missing)) {
                int target = chapterOfConcept(chapters, brief.subtopic(unit));
                if (target >= 0) {
                    PlannedChapter chapter = chapters.get(target);
                    List<String> material = new ArrayList<String>(chapter.getMaterialKeys());
                    material.add(unit);
                    List<PlannedSection> sections = new ArrayList<PlannedSection>(chapter.getSections());
                    sections.addAll(FallbackOutlinePlan.sections(brief, new PlannedChapter(chapter.getTitle(), "",
                            Collections.singletonList(unit), null, null, true)));
                    chapters.set(target, new PlannedChapter(chapter.getTitle(), chapter.getPurpose(), material,
                            chapter.getConceptKeys(), sections, chapter.isUntitled()));
                    notes.add(describe(brief, unit) + " was in no chapter; it joined \"" + chapter.getTitle()
                            + "\", the chapter of its concept requirement.");
                    missing.remove(unit);
                    used.add(unit);
                }
            }
            if (missing.isEmpty()) {
                continue;
            }
            List<String> keys = missing.size() == units(brief, topic.getKey()).size()
                    ? Collections.singletonList(topic.getKey()) : missing;
            int target = chapterOfGroup(chapters, brief, topic.getGroupKey());
            if (target < 0) {
                List<String> concepts = topic.getConceptKey().isEmpty() ? Collections.<String>emptyList()
                        : Collections.singletonList(topic.getConceptKey());
                PlannedChapter chapter = new PlannedChapter(topic.getProfile().getTitle(), "", keys, concepts, null,
                        true);
                chapters.add(chapter.withSections(FallbackOutlinePlan.sections(brief, chapter)));
                notes.add(describe(brief, topic.getKey()) + " was in no chapter; it became a chapter of its own.");
            } else {
                PlannedChapter chapter = chapters.get(target);
                List<String> material = new ArrayList<String>(chapter.getMaterialKeys());
                material.addAll(keys);
                List<PlannedSection> sections = new ArrayList<PlannedSection>(chapter.getSections());
                sections.addAll(FallbackOutlinePlan.sections(brief, new PlannedChapter(chapter.getTitle(), "", keys,
                        null, null, true)));
                chapters.set(target, new PlannedChapter(chapter.getTitle(), chapter.getPurpose(), material,
                        chapter.getConceptKeys(), sections, chapter.isUntitled()));
                notes.add(describe(brief, topic.getKey()) + " was in no chapter; it joined \"" + chapter.getTitle()
                        + "\", the chapter of its topic group.");
            }
            used.addAll(missing);
        }
        return plan.withChapters(chapters);
    }

    /** The first chapter treating the concept requirement of a concept part, or -1 (also for no concept part). */
    private static int chapterOfConcept(List<PlannedChapter> chapters, SynthesisBrief.SubtopicEntry part) {
        if (part == null || part.getConceptKey().isEmpty()) {
            return -1;
        }
        for (int i = 0; i < chapters.size(); i++) {
            if (chapters.get(i).getConceptKeys().contains(part.getConceptKey())) {
                return i;
            }
        }
        return -1;
    }

    /** The chapter with most distinct material of {@code groupKey}, or -1. */
    private static int chapterOfGroup(List<PlannedChapter> chapters, SynthesisBrief brief, String groupKey) {
        int best = -1;
        int bestDistinct = 0;
        for (int i = 0; i < chapters.size(); i++) {
            List<String> keys = new ArrayList<String>();
            for (String key : chapters.get(i).getMaterialKeys()) {
                SynthesisBrief.TopicEntry topic = brief.topic(brief.topicKeyOf(key));
                if (topic != null && topic.getGroupKey().equals(groupKey)) {
                    keys.add(key);
                }
            }
            int distinct = brief.getAnalysis().distinctCount(passagesOf(keys, brief));
            if (distinct > bestDistinct) {
                best = i;
                bestDistinct = distinct;
            }
        }
        return best;
    }

    /**
     * A requirement claimed without evidence leaves its chapter for the gaps; a requirement neither treated nor
     * listed joins the gaps (its coverage tells the reader whether evidence exists).
     */
    private static OutlinePlan requirementsToGaps(OutlinePlan plan, SynthesisBrief brief, List<String> notes) {
        Set<String> gaps = new LinkedHashSet<String>(plan.getGapKeys());
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (PlannedChapter chapter : plan.getChapters()) {
            Set<String> evidenced = evidencedRequirements(chapter, brief);
            List<String> concepts = new ArrayList<String>();
            for (String key : chapter.getConceptKeys()) {
                SynthesisBrief.RequirementEntry requirement = brief.requirement(key);
                if (requirement != null && requirement.getCoverage().getStatus() == ConceptCoverage.Status.GAP
                        && !evidenced.contains(key)) {
                    gaps.add(key);
                    notes.add("Concept requirement \"" + requirement.getCoverage().getLabel() + "\" has no evidence; "
                            + "it moved from \"" + chapter.getTitle() + "\" to the gaps.");
                } else {
                    concepts.add(key);
                }
            }
            chapters.add(new PlannedChapter(chapter.getTitle(), chapter.getPurpose(), chapter.getMaterialKeys(),
                    concepts, chapter.getSections(), chapter.isUntitled()));
        }
        OutlinePlan next = plan.withChapters(chapters);
        Set<String> used = new LinkedHashSet<String>();
        for (PlannedChapter chapter : chapters) {
            for (String key : chapter.getMaterialKeys()) {
                used.addAll(units(brief, key));
            }
        }
        Set<String> treated = treatedRequirements(next, brief, used);
        for (SynthesisBrief.RequirementEntry requirement : brief.getRequirements()) {
            if (!treated.contains(requirement.getKey()) && gaps.add(requirement.getKey())) {
                notes.add("Concept requirement \"" + requirement.getCoverage().getLabel() + "\" is treated by no "
                        + "chapter; it is listed as a gap.");
            }
        }
        // Gaps in requirement order.
        List<String> ordered = new ArrayList<String>();
        for (String key : brief.requirementKeys()) {
            if (gaps.contains(key)) {
                ordered.add(key);
            }
        }
        return next.withGapKeys(ordered);
    }

    /** A section holding a whole major topic with several sub-topics becomes one section per sub-topic. */
    private static OutlinePlan splitFlatMajorTopics(OutlinePlan plan, SynthesisBrief brief, List<String> notes) {
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (PlannedChapter chapter : plan.getChapters()) {
            List<SynthesisBrief.TopicEntry> flat = flatMajorTopics(chapter, brief);
            if (flat.isEmpty()) {
                chapters.add(chapter);
                continue;
            }
            List<PlannedSection> sections = new ArrayList<PlannedSection>();
            for (PlannedSection section : chapter.getSections()) {
                SynthesisBrief.TopicEntry whole = section.getMaterialKeys().size() == 1
                        ? brief.topic(section.getMaterialKeys().get(0)) : null;
                if (whole == null || !flat.contains(whole)) {
                    sections.add(section);
                    continue;
                }
                for (SynthesisBrief.SubtopicEntry subtopic : whole.getSubtopics()) {
                    sections.add(new PlannedSection(subtopic.getSubtopic().getTitle(), "",
                            Collections.singletonList(subtopic.getKey()), true));
                }
                notes.add(describe(brief, whole.getKey()) + " was one section of \"" + chapter.getTitle()
                        + "\"; it got a section per sub-topic.");
            }
            chapters.add(chapter.withSections(sections));
        }
        return plan.withChapters(chapters);
    }

    /** Major topics with several sub-topics that one section of {@code chapter} holds as a whole. */
    private static List<SynthesisBrief.TopicEntry> flatMajorTopics(PlannedChapter chapter, SynthesisBrief brief) {
        List<SynthesisBrief.TopicEntry> flat = new ArrayList<SynthesisBrief.TopicEntry>();
        for (PlannedSection section : chapter.getSections()) {
            if (section.getMaterialKeys().size() != 1) {
                continue;
            }
            SynthesisBrief.TopicEntry topic = brief.topic(section.getMaterialKeys().get(0));
            if (topic != null && topic.getProfile().isMajor() && topic.getSubtopics().size() > 1) {
                flat.add(topic);
            }
        }
        return flat;
    }

    private static OutlinePlan sectionsForEveryChapter(OutlinePlan plan, SynthesisBrief brief, List<String> notes) {
        List<PlannedChapter> chapters = new ArrayList<PlannedChapter>();
        for (PlannedChapter chapter : plan.getChapters()) {
            if (chapter.getSections().isEmpty()) {
                chapters.add(chapter.withSections(FallbackOutlinePlan.sections(brief, chapter)));
                notes.add("\"" + chapter.getTitle() + "\" had no sections; code split it by its material.");
            } else {
                chapters.add(chapter);
            }
        }
        return plan.withChapters(chapters);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The smallest pieces of material a key stands for: a topic with sub-topics stands for its sub-topics (its rest
     * goes with them), any other topic or sub-topic for itself.
     */
    static List<String> units(SynthesisBrief brief, String key) {
        SynthesisBrief.TopicEntry topic = brief.topic(key);
        if (topic != null) {
            if (topic.getSubtopics().isEmpty()) {
                return Collections.singletonList(key);
            }
            List<String> units = new ArrayList<String>();
            for (SynthesisBrief.SubtopicEntry subtopic : topic.getSubtopics()) {
                units.add(subtopic.getKey());
            }
            return units;
        }
        return brief.subtopic(key) != null ? Collections.singletonList(key) : Collections.<String>emptyList();
    }

    static Set<String> passagesOf(List<String> keys, SynthesisBrief brief) {
        Set<String> passages = new LinkedHashSet<String>();
        for (String key : keys) {
            passages.addAll(brief.passagesOf(key));
        }
        return passages;
    }

    private static String describe(SynthesisBrief brief, String key) {
        SynthesisBrief.TopicEntry topic = brief.topic(key);
        if (topic != null) {
            return "Topic " + key + " \"" + topic.getProfile().getTitle() + "\"";
        }
        SynthesisBrief.SubtopicEntry subtopic = brief.subtopic(key);
        return subtopic == null ? key : "Sub-topic " + key + " \"" + subtopic.getSubtopic().getTitle() + "\"";
    }

    private static String normal(String title) {
        return title.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
