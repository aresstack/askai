package com.aresstack.askai.research.outline.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    public SynthesisContext(String bookTitle, String mission, String deliverable, String language,
                            List<String> recordedBlacklist) {
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
