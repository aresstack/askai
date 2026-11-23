package com.aresstack.askai.research.topics;

import com.aresstack.askai.research.memory.ResearchTopic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Component 13: one topic the topic finder proposes from one source (Vorschlag): a short name, one sentence, the place
 * it was found (passage and verbatim quote) and at most two questions the book could answer on it. The finder decides
 * nothing about the Weidezaun and merges nothing; the merge (15) turns proposals into topics of the memory, and the
 * proposals stay, so a merge can be undone. Immutable.
 */
public final class TopicProposal {

    /** At most this many questions per proposal (build plan). */
    public static final int MAX_QUESTIONS = 2;

    /** What the program found when it looked the quote up. */
    public enum FindingCheck {
        /** The quote stands verbatim in the passage the model named. */
        GEPRUEFT,
        /** The quote stands verbatim in another passage of the same call; the passage was corrected. */
        KORRIGIERT,
        /** The quote stands in none of the passages the model read. */
        NICHT_GEFUNDEN
    }

    private final String id;
    private final String sourceId;
    private final String sourceUrl;
    private final String passageId;
    private final String quote;
    private final FindingCheck findingCheck;
    private final String name;
    private final String description;
    private final List<String> questions;
    private final String model;
    private final int round;
    private final long timeMillis;

    /**
     * @param passageId the passage the finding is in; {@code ""} when the model named none it read
     * @param questions at most {@value #MAX_QUESTIONS}; blank ones and repeats are left out
     */
    public TopicProposal(String id, String sourceId, String sourceUrl, String passageId, String quote,
                         FindingCheck findingCheck, String name, String description, List<String> questions,
                         String model, int round, long timeMillis) {
        this.id = clean(id);
        this.sourceId = clean(sourceId);
        this.sourceUrl = clean(sourceUrl);
        this.passageId = clean(passageId);
        this.quote = clean(quote);
        this.findingCheck = findingCheck == null ? FindingCheck.NICHT_GEFUNDEN : findingCheck;
        this.name = clean(name);
        this.description = clean(description);
        Set<String> kept = new LinkedHashSet<String>();
        if (questions != null) {
            for (String question : questions) {
                String text = clean(question);
                if (!text.isEmpty()) {
                    kept.add(text);
                }
            }
        }
        if (kept.size() > MAX_QUESTIONS) {
            throw new IllegalArgumentException("a proposal has at most " + MAX_QUESTIONS + " questions");
        }
        this.questions = Collections.unmodifiableList(new ArrayList<String>(kept));
        this.model = clean(model);
        this.round = Math.max(1, round);
        this.timeMillis = timeMillis;
        if (this.id.isEmpty() || this.name.isEmpty() || this.sourceId.isEmpty()) {
            throw new IllegalArgumentException("a proposal needs an id, a source and a name");
        }
    }

    public String getId() {
        return id;
    }

    public String getSourceId() {
        return sourceId;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getPassageId() {
        return passageId;
    }

    public String getQuote() {
        return quote;
    }

    public FindingCheck getFindingCheck() {
        return findingCheck;
    }

    /** Whether the quote was found verbatim in a passage the model read (named or corrected). */
    public boolean hasFinding() {
        return findingCheck != FindingCheck.NICHT_GEFUNDEN;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getQuestions() {
        return questions;
    }

    public String getModel() {
        return model;
    }

    public int getRound() {
        return round;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /** The place in the form of the memory's topic record (Fundstelle). */
    public ResearchTopic.Finding toFinding() {
        return new ResearchTopic.Finding(sourceId, passageId, quote);
    }

    @Override
    public String toString() {
        return id + " " + name + " (" + sourceId + (passageId.isEmpty() ? "" : " / " + passageId) + ", "
                + findingCheck + ")";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
