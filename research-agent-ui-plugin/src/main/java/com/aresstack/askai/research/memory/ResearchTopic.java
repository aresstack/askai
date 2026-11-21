package com.aresstack.askai.research.memory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Component 7, record "Thema" of the shared data model: a subject the book can cover. A topic comes from a card of
 * the concept or is found during the research (topic finder 13, merged by 15, checked against the Weidezaun by 14).
 * Topics are no cards of the mind map. The id stays when other topics are merged into it. Immutable; a change is a
 * new record with the same id ({@code with...}), and the memory keeps the latest.
 * <p>
 * Field names of the data model: themaId, Name, Beschreibung, Herkunft, Karte, zusammengeführtAus, Fundstellen,
 * Zaun mit Grund, Gewicht.
 */
public final class ResearchTopic {

    /** Where the topic comes from (Herkunft). */
    public enum Origin { KONZEPT, GEFUNDEN }

    /** The topic check's verdict against the Weidezaun (Zaun). */
    public enum Fence { INNEN, AUSSEN, UNKLAR, UNGEPRUEFT }

    /** A place the topic was found (Fundstelle): source, passage and the quote that shows it. */
    public static final class Finding {
        private final String sourceId;
        private final String passageId;
        private final String quote;

        public Finding(String sourceId, String passageId, String quote) {
            this.sourceId = Texts.clean(sourceId);
            this.passageId = Texts.clean(passageId);
            this.quote = Texts.clean(quote);
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getPassageId() {
            return passageId;
        }

        public String getQuote() {
            return quote;
        }
    }

    private final String id;
    private final String name;
    private final String description;
    private final Origin origin;
    private final String cardAnchorId;
    private final List<String> mergedFrom;
    private final List<Finding> findings;
    private final Fence fence;
    private final String fenceReason;
    private final double weight;
    private final String model;
    private final long timeMillis;

    /**
     * @param cardAnchorId the anchor id of the concept card ({@code anchor-concept-<slug>}), {@code ""} for a topic
     *                     on no card
     * @param mergedFrom   ids of the topics merged into this one
     * @param weight       the share the mission gives the topic (0..1), 0 when it names none; the evidence counts
     *                     come from the memory ({@link TopicEvidence})
     * @param model        the model of the last step that changed the record, {@code ""} for a program step
     */
    public ResearchTopic(String id, String name, String description, Origin origin, String cardAnchorId,
                         List<String> mergedFrom, List<Finding> findings, Fence fence, String fenceReason,
                         double weight, String model, long timeMillis) {
        this.id = Texts.clean(id);
        this.name = Texts.clean(name);
        this.description = Texts.clean(description);
        this.origin = origin == null ? Origin.GEFUNDEN : origin;
        this.cardAnchorId = Texts.clean(cardAnchorId);
        this.mergedFrom = Texts.ids(mergedFrom);
        this.findings = Collections.unmodifiableList(new ArrayList<Finding>(
                findings == null ? Collections.<Finding>emptyList() : findings));
        this.fence = fence == null ? Fence.UNGEPRUEFT : fence;
        this.fenceReason = Texts.clean(fenceReason);
        this.weight = Double.isNaN(weight) ? 0 : Math.max(0, Math.min(1, weight));
        this.model = Texts.clean(model);
        this.timeMillis = timeMillis;
        if (this.id.isEmpty() || this.name.isEmpty()) {
            throw new IllegalArgumentException("a topic needs an id and a name");
        }
        if (this.mergedFrom.contains(this.id)) {
            throw new IllegalArgumentException("topic " + this.id + " cannot be merged into itself");
        }
    }

    /** A card of the concept: inside the Weidezaun by definition, since the cards draw it. */
    public static ResearchTopic ofCard(String id, String name, String cardAnchorId, double weight, long timeMillis) {
        return new ResearchTopic(id, name, "", Origin.KONZEPT, cardAnchorId, null, null, Fence.INNEN,
                "Karte des Konzepts", weight, "", timeMillis);
    }

    /** A topic found during the research, not yet checked against the Weidezaun. */
    public static ResearchTopic found(String id, String name, String description, List<Finding> findings,
                                      String model, long timeMillis) {
        return new ResearchTopic(id, name, description, Origin.GEFUNDEN, "", null, findings, Fence.UNGEPRUEFT, "",
                0, model, timeMillis);
    }

    public ResearchTopic withFence(Fence newFence, String reason, String byModel, long time) {
        return new ResearchTopic(id, name, description, origin, cardAnchorId, mergedFrom, findings, newFence, reason,
                weight, byModel, time);
    }

    /** This topic with others merged into it: their ids and findings join it, its own id stays. */
    public ResearchTopic withMerged(List<ResearchTopic> others, String byModel, long time) {
        List<String> ids = new ArrayList<String>(mergedFrom);
        List<Finding> all = new ArrayList<Finding>(findings);
        for (ResearchTopic other : others) {
            ids.add(other.getId());
            ids.addAll(other.getMergedFrom());
            all.addAll(other.getFindings());
        }
        return new ResearchTopic(id, name, description, origin, cardAnchorId, ids, all, fence, fenceReason, weight,
                byModel, time);
    }

    /** This topic with a merge taken back: the given ids leave {@code mergedFrom}, so they stand alone again. */
    public ResearchTopic withoutMerged(List<String> ids, long time) {
        List<String> kept = new ArrayList<String>(mergedFrom);
        kept.removeAll(ids);
        return new ResearchTopic(id, name, description, origin, cardAnchorId, kept, findings, fence, fenceReason,
                weight, "", time);
    }

    public ResearchTopic withWeight(double share, long time) {
        return new ResearchTopic(id, name, description, origin, cardAnchorId, mergedFrom, findings, fence,
                fenceReason, share, model, time);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Origin getOrigin() {
        return origin;
    }

    public String getCardAnchorId() {
        return cardAnchorId;
    }

    public List<String> getMergedFrom() {
        return mergedFrom;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public Fence getFence() {
        return fence;
    }

    public String getFenceReason() {
        return fenceReason;
    }

    public double getWeight() {
        return weight;
    }

    public String getModel() {
        return model;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    @Override
    public String toString() {
        return id + " [" + origin + ", " + fence + "] " + name;
    }
}
