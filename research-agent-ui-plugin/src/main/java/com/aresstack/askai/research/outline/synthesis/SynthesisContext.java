package com.aresstack.askai.research.outline.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 3b: what every synthesis call knows about the book: title, mission, deliverable, language and the blacklist
 * recorded at {@code SUBMIT_SCOPE} (D4). Immutable.
 */
public final class SynthesisContext {

    private final String bookTitle;
    private final String mission;
    private final String deliverable;
    private final String language;
    private final List<String> recordedBlacklist;
    /** Repair plan step 5a: the user's explicit share of the book per concept card (anchor id), 0 to 1. */
    private final Map<String, Double> chapterShares;

    public SynthesisContext(String bookTitle, String mission, String deliverable, String language,
                            List<String> recordedBlacklist) {
        this(bookTitle, mission, deliverable, language, recordedBlacklist, null);
    }

    /**
     * @param chapterShares the user's explicit share of the book per concept card, keyed by the card's anchor id
     *                      (a chapter's concept node); {@code null} or empty = no explicit weight
     */
    public SynthesisContext(String bookTitle, String mission, String deliverable, String language,
                            List<String> recordedBlacklist, Map<String, Double> chapterShares) {
        this.bookTitle = clean(bookTitle);
        this.mission = clean(mission);
        this.deliverable = clean(deliverable);
        this.language = clean(language).isEmpty() ? "English" : clean(language);
        List<String> entries = new ArrayList<String>();
        if (recordedBlacklist != null) {
            for (String entry : recordedBlacklist) {
                if (!clean(entry).isEmpty()) {
                    entries.add(clean(entry));
                }
            }
        }
        this.recordedBlacklist = Collections.unmodifiableList(entries);
        Map<String, Double> shares = new LinkedHashMap<String, Double>();
        if (chapterShares != null) {
            for (Map.Entry<String, Double> entry : chapterShares.entrySet()) {
                Double share = entry.getValue();
                if (entry.getKey() != null && !entry.getKey().isEmpty() && share != null && share > 0
                        && share <= 1.0) {
                    shares.put(entry.getKey(), share);
                }
            }
        }
        this.chapterShares = Collections.unmodifiableMap(shares);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public String getBookTitle() {
        return bookTitle;
    }

    public String getMission() {
        return mission;
    }

    public String getDeliverable() {
        return deliverable;
    }

    public String getLanguage() {
        return language;
    }

    public List<String> getRecordedBlacklist() {
        return recordedBlacklist;
    }

    /** The explicit shares per concept card (anchor id), possibly empty. */
    public Map<String, Double> getChapterShares() {
        return chapterShares;
    }

    /** The explicit share of a chapter on concept card {@code conceptNodeId}; {@code NaN} without one. */
    double shareOf(String conceptNodeId, String chapterTitle) {
        Double share = conceptNodeId == null ? null : chapterShares.get(conceptNodeId);
        return share == null ? Double.NaN : share;
    }

    /** The common head of every prompt. */
    String header() {
        StringBuilder text = new StringBuilder();
        if (!bookTitle.isEmpty()) {
            text.append("Book: ").append(bookTitle).append('\n');
        }
        if (!mission.isEmpty()) {
            text.append("Mission: ").append(mission).append('\n');
        }
        text.append("Deliverable: ").append(deliverable.isEmpty() ? "no length given" : deliverable).append('\n');
        text.append("Write titles and purposes in ").append(language).append(".\n");
        if (!recordedBlacklist.isEmpty()) {
            text.append("Never cover these excluded topics: ").append(String.join("; ", recordedBlacklist))
                    .append('\n');
        }
        return text.toString();
    }
}
