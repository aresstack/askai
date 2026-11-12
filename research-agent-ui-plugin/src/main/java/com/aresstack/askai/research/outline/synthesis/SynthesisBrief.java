package com.aresstack.askai.research.outline.synthesis;

import com.aresstack.askai.research.knowledge.topic.ConceptCoverage;
import com.aresstack.askai.research.knowledge.topic.ScopeAlignment;
import com.aresstack.askai.research.knowledge.topic.Subtopic;
import com.aresstack.askai.research.knowledge.topic.TopicAnalysis;
import com.aresstack.askai.research.knowledge.topic.TopicProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3b: the compact, structured analysis the model works from, with short keys instead of ids: research topics
 * {@code T1, T2, ...} (largest distinct material first), their sub-topics {@code T1.1, T1.2, ...}, topic groups
 * {@code G1, ...} and concept requirements {@code C1, ...} (concept order). The brief resolves every key back to
 * topic ids and passages. Only significant topics are listed, at most the configured number; the significant topics
 * beyond that are {@link #getOmittedTopics() omitted} and placed by code. Topics the search fence reads as OUT
 * (clearly on the blacklist side) are {@link #getOutOfScopeTopics() kept out} of the outline and reported; a topic
 * that is only new to the concept stays in. Immutable.
 */
public final class SynthesisBrief {

    /** One research topic with its key. */
    public static final class TopicEntry {
        private final String key;
        private final TopicProfile profile;
        private final String groupKey;
        private final String conceptKey;
        private final List<SubtopicEntry> subtopics;
        private final List<String> excerpts;

        TopicEntry(String key, TopicProfile profile, String groupKey, String conceptKey,
                   List<SubtopicEntry> subtopics, List<String> excerpts) {
            this.key = key;
            this.profile = profile;
            this.groupKey = groupKey;
            this.conceptKey = conceptKey;
            this.subtopics = Collections.unmodifiableList(new ArrayList<SubtopicEntry>(subtopics));
            this.excerpts = Collections.unmodifiableList(new ArrayList<String>(excerpts));
        }

        public String getKey() {
            return key;
        }

        public TopicProfile getProfile() {
            return profile;
        }

        public String getGroupKey() {
            return groupKey;
        }

        /** The key of the aligned concept requirement, or "". */
        public String getConceptKey() {
            return conceptKey;
        }

        public List<SubtopicEntry> getSubtopics() {
            return subtopics;
        }

        public List<String> getExcerpts() {
            return excerpts;
        }
    }

    /** One sub-topic with its key ({@code T1.2}). */
    public static final class SubtopicEntry {
        private final String key;
        private final String topicKey;
        private final Subtopic subtopic;
        private final String excerpt;
        private final String conceptKey;

        SubtopicEntry(String key, String topicKey, Subtopic subtopic, String excerpt, String conceptKey) {
            this.key = key;
            this.topicKey = topicKey;
            this.subtopic = subtopic;
            this.excerpt = excerpt;
            this.conceptKey = conceptKey == null ? "" : conceptKey;
        }

        /** The key of the concept requirement whose material this concept part is, or "". */
        public String getConceptKey() {
            return conceptKey;
        }

        public String getKey() {
            return key;
        }

        public String getTopicKey() {
            return topicKey;
        }

        public Subtopic getSubtopic() {
            return subtopic;
        }

        public String getExcerpt() {
            return excerpt;
        }
    }

    /** One concept requirement with its key. */
    public static final class RequirementEntry {
        private final String key;
        private final ConceptCoverage coverage;

        RequirementEntry(String key, ConceptCoverage coverage) {
            this.key = key;
            this.coverage = coverage;
        }

        public String getKey() {
            return key;
        }

        public ConceptCoverage getCoverage() {
            return coverage;
        }
    }

    /** Looks up a passage text for the excerpts. */
    public interface Texts {
        String text(String passageId);
    }

    private final SynthesisContext context;
    private final TopicAnalysis analysis;
    private final List<TopicEntry> topics;
    private final List<TopicProfile> omittedTopics;
    private final List<TopicProfile> outOfScopeTopics;
    private final List<RequirementEntry> requirements;
    private final Map<String, List<String>> groupTopicKeys;
    private final Map<String, TopicEntry> topicByKey = new LinkedHashMap<String, TopicEntry>();
    private final Map<String, SubtopicEntry> subtopicByKey = new LinkedHashMap<String, SubtopicEntry>();
    private final Map<String, RequirementEntry> requirementByKey = new LinkedHashMap<String, RequirementEntry>();

    private SynthesisBrief(SynthesisContext context, TopicAnalysis analysis, List<TopicEntry> topics,
                           List<TopicProfile> omittedTopics, List<TopicProfile> outOfScopeTopics,
                           List<RequirementEntry> requirements, Map<String, List<String>> groupTopicKeys) {
        this.context = context;
        this.analysis = analysis;
        this.topics = Collections.unmodifiableList(topics);
        this.omittedTopics = Collections.unmodifiableList(omittedTopics);
        this.outOfScopeTopics = Collections.unmodifiableList(outOfScopeTopics);
        this.requirements = Collections.unmodifiableList(requirements);
        this.groupTopicKeys = Collections.unmodifiableMap(groupTopicKeys);
        for (TopicEntry topic : topics) {
            topicByKey.put(topic.getKey(), topic);
            for (SubtopicEntry subtopic : topic.getSubtopics()) {
                subtopicByKey.put(subtopic.getKey(), subtopic);
            }
        }
        for (RequirementEntry requirement : requirements) {
            requirementByKey.put(requirement.getKey(), requirement);
        }
    }

    /**
     * Builds the brief.
     * @param texts    passage texts for the excerpts
     * @param settings the prompt limits and the requirement depth
     */
    public static SynthesisBrief of(SynthesisContext context, TopicAnalysis analysis, Texts texts,
                                    OutlineSynthesisSettings settings) {
        OutlineSynthesisSettings limits = settings == null ? OutlineSynthesisSettings.defaults() : settings;
        Map<String, String> requirementKeyOfAnchor = new LinkedHashMap<String, String>();
        List<RequirementEntry> requirements = new ArrayList<RequirementEntry>();
        for (ConceptCoverage coverage : analysis.getCoverage()) {
            if (coverage.getDepth() <= limits.getRequirementDepth()) {
                String key = "C" + (requirements.size() + 1);
                requirements.add(new RequirementEntry(key, coverage));
                requirementKeyOfAnchor.put(coverage.getAnchorId(), key);
            }
        }
        // A topic aligned with a node below the requirement depth counts for that node's requirement ancestor.
        Map<String, String> parentOf = new LinkedHashMap<String, String>();
        for (ConceptCoverage coverage : analysis.getCoverage()) {
            parentOf.put(coverage.getAnchorId(), coverage.getParentId());
        }

        // Group keys in the order their first listed topic appears.
        Map<String, String> groupKeyOf = new LinkedHashMap<String, String>();
        List<TopicEntry> topics = new ArrayList<TopicEntry>();
        List<TopicProfile> omitted = new ArrayList<TopicProfile>();
        List<TopicProfile> outOfScope = new ArrayList<TopicProfile>();
        Map<String, List<String>> groupTopicKeys = new LinkedHashMap<String, List<String>>();
        int groupCount = 0;
        for (TopicProfile profile : analysis.significantTopics()) {
            if (profile.getScopeAlignment().getVerdict() == ScopeAlignment.Verdict.OUT) {
                outOfScope.add(profile);
                continue;
            }
            if (topics.size() >= limits.getMaxPromptTopics()) {
                omitted.add(profile);
                continue;
            }
            String key = "T" + (topics.size() + 1);
            String groupKey = groupKeyOf.get(profile.getGroupId());
            if (groupKey == null) {
                groupKey = "G" + (++groupCount);
                groupKeyOf.put(profile.getGroupId(), groupKey);
            }
            List<String> members = groupTopicKeys.get(groupKey);
            if (members == null) {
                members = new ArrayList<String>();
                groupTopicKeys.put(groupKey, members);
            }
            members.add(key);
            String conceptKey = profile.isConceptAligned()
                    ? requirementKeyOf(profile.getConceptAnchorId(), requirementKeyOfAnchor, parentOf) : "";
            List<SubtopicEntry> subtopics = new ArrayList<SubtopicEntry>();
            for (Subtopic subtopic : profile.getSubtopics()) {
                subtopics.add(new SubtopicEntry(key + "." + (subtopics.size() + 1), key, subtopic,
                        excerpt(texts, subtopic.getRepresentativePassageId(), limits.getExcerptChars()),
                        requirementKeyOf(subtopic.getConceptAnchorId(), requirementKeyOfAnchor, parentOf)));
            }
            List<String> excerpts = new ArrayList<String>();
            for (String passageId : profile.getRepresentativePassageIds()) {
                String excerpt = excerpt(texts, passageId, limits.getExcerptChars());
                if (!excerpt.isEmpty()) {
                    excerpts.add(excerpt);
                }
            }
            topics.add(new TopicEntry(key, profile, groupKey, conceptKey, subtopics, excerpts));
        }
        return new SynthesisBrief(context, analysis, topics, omitted, outOfScope, requirements, groupTopicKeys);
    }

    /** The requirement key of a concept node or of its nearest ancestor within the requirement depth, or "". */
    private static String requirementKeyOf(String anchorId, Map<String, String> requirementKeyOfAnchor,
                                           Map<String, String> parentOf) {
        String anchor = anchorId;
        Set<String> visited = new LinkedHashSet<String>();
        while (anchor != null && !anchor.isEmpty() && visited.add(anchor)) {
            String found = requirementKeyOfAnchor.get(anchor);
            if (found != null) {
                return found;
            }
            anchor = parentOf.get(anchor);
        }
        return "";
    }

    /**
     * The same brief with at most {@code maxTopics} listed topics (the rest joins the omitted ones), for a model
     * whose context cannot hold the full list.
     */
    public SynthesisBrief limitedTo(int maxTopics) {
        int keep = Math.max(1, maxTopics);
        if (topics.size() <= keep) {
            return this;
        }
        List<TopicEntry> kept = new ArrayList<TopicEntry>(topics.subList(0, keep));
        List<TopicProfile> omitted = new ArrayList<TopicProfile>();
        for (TopicEntry dropped : topics.subList(keep, topics.size())) {
            omitted.add(dropped.getProfile());
        }
        omitted.addAll(omittedTopics);
        Map<String, List<String>> groups = new LinkedHashMap<String, List<String>>();
        for (TopicEntry topic : kept) {
            List<String> members = groups.get(topic.getGroupKey());
            if (members == null) {
                members = new ArrayList<String>();
                groups.put(topic.getGroupKey(), members);
            }
            members.add(topic.getKey());
        }
        return new SynthesisBrief(context, analysis, kept, omitted, outOfScopeTopics, requirements, groups);
    }

    static String excerpt(Texts texts, String passageId, int chars) {
        if (texts == null || chars <= 0 || passageId == null || passageId.isEmpty()) {
            return "";
        }
        String text = texts.text(passageId);
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (clean.length() <= chars) {
            return clean;
        }
        int cut = clean.lastIndexOf(' ', chars);
        return clean.substring(0, cut > chars / 2 ? cut : chars) + "...";
    }

    public SynthesisContext getContext() {
        return context;
    }

    public TopicAnalysis getAnalysis() {
        return analysis;
    }

    /** The listed topics, {@code T1} first. */
    public List<TopicEntry> getTopics() {
        return topics;
    }

    /** Significant topics beyond the prompt limit (placed by code, never shown to the model). */
    public List<TopicProfile> getOmittedTopics() {
        return omittedTopics;
    }

    /** Significant topics the search fence reads as OUT: not in the outline, reported in its quality report. */
    public List<TopicProfile> getOutOfScopeTopics() {
        return outOfScopeTopics;
    }

    public List<RequirementEntry> getRequirements() {
        return requirements;
    }

    /** Topic keys per group key, in group order. */
    public Map<String, List<String>> getGroupTopicKeys() {
        return groupTopicKeys;
    }

    public TopicEntry topic(String key) {
        return topicByKey.get(key);
    }

    public SubtopicEntry subtopic(String key) {
        return subtopicByKey.get(key);
    }

    public RequirementEntry requirement(String key) {
        return requirementByKey.get(key);
    }

    /** Whether {@code key} names a listed topic or sub-topic. */
    public boolean isMaterial(String key) {
        return topicByKey.containsKey(key) || subtopicByKey.containsKey(key);
    }

    /** The topic key of a topic or sub-topic key ({@code T3.2} gives {@code T3}); "" for an unknown key. */
    public String topicKeyOf(String materialKey) {
        if (topicByKey.containsKey(materialKey)) {
            return materialKey;
        }
        SubtopicEntry subtopic = subtopicByKey.get(materialKey);
        return subtopic == null ? "" : subtopic.getTopicKey();
    }

    /** The passages of a topic or sub-topic key (empty for an unknown key). */
    public List<String> passagesOf(String materialKey) {
        TopicEntry topic = topicByKey.get(materialKey);
        if (topic != null) {
            return topic.getProfile().getPassageIds();
        }
        SubtopicEntry subtopic = subtopicByKey.get(materialKey);
        return subtopic == null ? Collections.<String>emptyList() : subtopic.getSubtopic().getPassageIds();
    }

    /**
     * The passages of a topic that are in none of its sub-topics (the sub-topic cut drops groups below the minimum).
     * A plan that uses sub-topics only still owns this rest; code adds it to the section of the topic's largest
     * sub-topic.
     */
    public List<String> restOf(String topicKey) {
        TopicEntry topic = topicByKey.get(topicKey);
        if (topic == null) {
            return Collections.emptyList();
        }
        Set<String> inSubtopics = new LinkedHashSet<String>();
        for (SubtopicEntry subtopic : topic.getSubtopics()) {
            inSubtopics.addAll(subtopic.getSubtopic().getPassageIds());
        }
        List<String> rest = new ArrayList<String>();
        for (String id : topic.getProfile().getPassageIds()) {
            if (!inSubtopics.contains(id)) {
                rest.add(id);
            }
        }
        return rest;
    }

    /** The stable id behind a material key (topic id or sub-topic id); "" for an unknown key. */
    public String idOf(String materialKey) {
        TopicEntry topic = topicByKey.get(materialKey);
        if (topic != null) {
            return topic.getProfile().getTopicId();
        }
        SubtopicEntry subtopic = subtopicByKey.get(materialKey);
        return subtopic == null ? "" : subtopic.getSubtopic().getSubtopicId();
    }

    /** Every key a plan may use: topic and sub-topic keys in order. */
    public List<String> materialKeys() {
        List<String> keys = new ArrayList<String>();
        for (TopicEntry topic : topics) {
            keys.add(topic.getKey());
            for (SubtopicEntry subtopic : topic.getSubtopics()) {
                keys.add(subtopic.getKey());
            }
        }
        return keys;
    }

    public List<String> requirementKeys() {
        return new ArrayList<String>(requirementByKey.keySet());
    }
}
