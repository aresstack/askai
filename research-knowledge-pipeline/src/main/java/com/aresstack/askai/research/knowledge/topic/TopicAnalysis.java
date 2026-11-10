package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 3a: the structured result of the topic analysis: topic profiles (largest distinct material first), topic
 * groups, concept coverage, and the near-duplicate grouping that lets later steps count distinct material of any
 * passage set. Immutable; the same input gives the same analysis.
 */
public final class TopicAnalysis {

    private final List<TopicProfile> topics;
    private final Map<String, TopicProfile> byId;
    private final List<TopicGroup> groups;
    private final List<ConceptCoverage> coverage;
    private final Map<String, String> duplicateGroupOf;
    private final Map<String, String> sourceOf;
    private final TopicAnalysisSettings settings;

    public TopicAnalysis(List<TopicProfile> topics, List<TopicGroup> groups, List<ConceptCoverage> coverage,
                         Map<String, String> duplicateGroupOf, Map<String, String> sourceOf,
                         TopicAnalysisSettings settings) {
        this.topics = Collections.unmodifiableList(new ArrayList<TopicProfile>(
                topics == null ? Collections.<TopicProfile>emptyList() : topics));
        Map<String, TopicProfile> index = new LinkedHashMap<String, TopicProfile>();
        for (TopicProfile topic : this.topics) {
            index.put(topic.getTopicId(), topic);
        }
        this.byId = Collections.unmodifiableMap(index);
        this.groups = Collections.unmodifiableList(new ArrayList<TopicGroup>(
                groups == null ? Collections.<TopicGroup>emptyList() : groups));
        this.coverage = Collections.unmodifiableList(new ArrayList<ConceptCoverage>(
                coverage == null ? Collections.<ConceptCoverage>emptyList() : coverage));
        this.duplicateGroupOf = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                duplicateGroupOf == null ? Collections.<String, String>emptyMap() : duplicateGroupOf));
        this.sourceOf = Collections.unmodifiableMap(new LinkedHashMap<String, String>(
                sourceOf == null ? Collections.<String, String>emptyMap() : sourceOf));
        this.settings = settings == null ? TopicAnalysisSettings.defaults() : settings;
    }

    /** All topics, largest distinct material first. */
    public List<TopicProfile> getTopics() {
        return topics;
    }

    /** The topics that meet the minimums, in the same order. */
    public List<TopicProfile> significantTopics() {
        List<TopicProfile> result = new ArrayList<TopicProfile>();
        for (TopicProfile topic : topics) {
            if (topic.isSignificant()) {
                result.add(topic);
            }
        }
        return result;
    }

    /** The topic with this id, or {@code null}. */
    public TopicProfile topic(String topicId) {
        return byId.get(topicId);
    }

    public List<TopicGroup> getGroups() {
        return groups;
    }

    /** One entry per concept node, in concept order. */
    public List<ConceptCoverage> getCoverage() {
        return coverage;
    }

    /** The coverage of one node, or {@code null}. */
    public ConceptCoverage coverage(String anchorId) {
        for (ConceptCoverage entry : coverage) {
            if (entry.getAnchorId().equals(anchorId)) {
                return entry;
            }
        }
        return null;
    }

    public TopicAnalysisSettings getSettings() {
        return settings;
    }

    /** Distinct material of any passage set: near duplicates count once; unknown passages count as their own. */
    public int distinctCount(Collection<String> passageIds) {
        Set<String> groupsSeen = new HashSet<String>();
        for (String id : passageIds) {
            String group = duplicateGroupOf.get(id);
            groupsSeen.add(group == null ? id : group);
        }
        return groupsSeen.size();
    }

    /** Distinct sources of any passage set (passages without a known source are not counted). */
    public int sourceCount(Collection<String> passageIds) {
        Set<String> sources = new HashSet<String>();
        for (String id : passageIds) {
            String source = sourceOf.get(id);
            if (source != null && !source.isEmpty()) {
                sources.add(source);
            }
        }
        return sources.size();
    }

    /** The source of a passage ("" when unknown). */
    public String sourceOf(String passageId) {
        String source = sourceOf.get(passageId);
        return source == null ? "" : source;
    }

    /** Total distinct material of the significant topics. */
    public int significantDistinctTotal() {
        int total = 0;
        for (TopicProfile topic : topics) {
            if (topic.isSignificant()) {
                total += topic.getDistinctPassageCount();
            }
        }
        return total;
    }
}
