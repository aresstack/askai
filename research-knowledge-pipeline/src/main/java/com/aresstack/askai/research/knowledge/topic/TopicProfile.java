package com.aresstack.askai.research.knowledge.topic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3: everything the outline synthesis needs to know about one topic cluster, measured in code. Material
 * (how much distinct evidence) and diversity (how many different aspects) are kept apart:
 * <ul>
 * <li>{@link #getPassageCount()} counts passages; {@link #getDistinctPassageCount()} counts near-duplicate groups,
 * so twenty copies of one statement count once ({@link #getRedundancyShare()} says how much repeats);</li>
 * <li>{@link #getMaterialChars()} is the text length of one passage per near-duplicate group;</li>
 * <li>{@link #getSubtopics()} are the separately recognisable aspects, {@link #getDispersion()} the mean cosine
 * distance of the members to the centroid.</li>
 * </ul>
 * Immutable.
 */
public final class TopicProfile {

    private final String topicId;
    private final String title;
    private final List<String> passageIds;
    private final List<String> representativePassageIds;
    private final int distinctPassageCount;
    private final int sourceCount;
    private final int domainCount;
    private final long materialChars;
    private final double dispersion;
    private final List<Subtopic> subtopics;
    private final String groupId;
    private final List<TopicRelation> related;
    private final String conceptAnchorId;
    private final double conceptSimilarity;
    private final boolean conceptAligned;
    private final boolean materialCovered;
    private final boolean sourceConfirmed;
    private final double evidenceShare;
    private final boolean major;
    private final ScopeAlignment scopeAlignment;

    public TopicProfile(String topicId, String title, List<String> passageIds, List<String> representativePassageIds,
                        int distinctPassageCount, int sourceCount, int domainCount, long materialChars,
                        double dispersion, List<Subtopic> subtopics, String groupId, List<TopicRelation> related,
                        String conceptAnchorId, double conceptSimilarity, boolean conceptAligned,
                        boolean materialCovered, boolean sourceConfirmed, double evidenceShare, boolean major,
                        ScopeAlignment scopeAlignment) {
        this.topicId = topicId == null ? "" : topicId;
        this.title = title == null ? "" : title;
        this.passageIds = copy(passageIds);
        this.representativePassageIds = copy(representativePassageIds);
        this.distinctPassageCount = distinctPassageCount;
        this.sourceCount = sourceCount;
        this.domainCount = domainCount;
        this.materialChars = materialChars;
        this.dispersion = dispersion;
        this.subtopics = Collections.unmodifiableList(new ArrayList<Subtopic>(
                subtopics == null ? Collections.<Subtopic>emptyList() : subtopics));
        this.groupId = groupId == null ? "" : groupId;
        this.related = Collections.unmodifiableList(new ArrayList<TopicRelation>(
                related == null ? Collections.<TopicRelation>emptyList() : related));
        this.conceptAnchorId = conceptAnchorId == null ? "" : conceptAnchorId;
        this.conceptSimilarity = conceptSimilarity;
        this.conceptAligned = conceptAligned;
        this.materialCovered = materialCovered;
        this.sourceConfirmed = sourceConfirmed;
        this.scopeAlignment = scopeAlignment == null ? ScopeAlignment.UNKNOWN : scopeAlignment;
        this.evidenceShare = evidenceShare;
        this.major = major;
    }

    private static List<String> copy(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<String>(
                values == null ? Collections.<String>emptyList() : values));
    }

    /** The clusterer's deterministic topic id. */
    public String getTopicId() {
        return topicId;
    }

    /** The evidence-bound title (the representative's first sentence); a model may rename it later. */
    public String getTitle() {
        return title;
    }

    public List<String> getPassageIds() {
        return passageIds;
    }

    public int getPassageCount() {
        return passageIds.size();
    }

    public List<String> getRepresentativePassageIds() {
        return representativePassageIds;
    }

    public int getDistinctPassageCount() {
        return distinctPassageCount;
    }

    /** {@code 1 - distinct / passages}: the part of the topic that repeats what another passage already says. */
    public double getRedundancyShare() {
        return passageIds.isEmpty() ? 0 : 1.0 - (double) distinctPassageCount / passageIds.size();
    }

    public int getSourceCount() {
        return sourceCount;
    }

    public int getDomainCount() {
        return domainCount;
    }

    public long getMaterialChars() {
        return materialChars;
    }

    /** Mean cosine distance of the members to the centroid: 0 = one statement, larger = more varied. */
    public double getDispersion() {
        return dispersion;
    }

    /** The recognisable aspects; empty when the topic has fewer than two of them. */
    public List<Subtopic> getSubtopics() {
        return subtopics;
    }

    public String getGroupId() {
        return groupId;
    }

    /** The other topics of its group, nearest first. */
    public List<TopicRelation> getRelated() {
        return related;
    }

    /** The concept node nearest to the centroid ("" without nodes), whether or not it is aligned. */
    public String getConceptAnchorId() {
        return conceptAnchorId;
    }

    public double getConceptSimilarity() {
        return conceptSimilarity;
    }

    /** Whether the nearest node passes threshold and margin. */
    public boolean isConceptAligned() {
        return conceptAligned;
    }

    /** Whether the topic meets the passage and source minimums. */
    public boolean isSignificant() {
        return materialCovered && sourceConfirmed;
    }

    /** Enough DISTINCT passages (near duplicates count once). */
    public boolean isMaterialCovered() {
        return materialCovered;
    }

    /** Enough sources from enough domains. */
    public boolean isSourceConfirmed() {
        return sourceConfirmed;
    }

    /** Where the topic lies relative to the search fence; separate from the concept alignment. */
    public ScopeAlignment getScopeAlignment() {
        return scopeAlignment;
    }

    /** Its share of the distinct material of all significant topics (0 for an insignificant topic). */
    public double getEvidenceShare() {
        return evidenceShare;
    }

    /** A significant topic with at least the major share of the distinct material. */
    public boolean isMajor() {
        return major;
    }

    /** A significant topic that no concept node covers: new from the research (which says nothing about the fence). */
    public boolean isNovel() {
        return isSignificant() && !conceptAligned;
    }
}
