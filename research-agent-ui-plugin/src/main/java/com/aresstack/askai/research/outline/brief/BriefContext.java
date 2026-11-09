package com.aresstack.askai.research.outline.brief;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What every label and brief call of a book knows (plan PR 24): the book title, the mission, the book language and
 * the blacklist recorded at {@code SUBMIT_SCOPE} (D4). Entries added later never reach these calls.
 */
public final class BriefContext {

    private final String bookTitle;
    private final String mission;
    private final String language;
    private final List<String> recordedBlacklist;

    public BriefContext(String bookTitle, String mission, String language, List<String> recordedBlacklist) {
        this.bookTitle = clean(bookTitle);
        this.mission = clean(mission);
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

    /** The language labels and briefs are written in, as a name ("German", "English"). */
    public String getLanguage() {
        return language;
    }

    public List<String> getRecordedBlacklist() {
        return recordedBlacklist;
    }

    String header() {
        StringBuilder text = new StringBuilder();
        if (!bookTitle.isEmpty()) {
            text.append("Book: ").append(bookTitle).append('\n');
        }
        if (!mission.isEmpty()) {
            text.append("Mission: ").append(mission).append('\n');
        }
        text.append("Write in ").append(language).append(".\n");
        if (!recordedBlacklist.isEmpty()) {
            text.append("Never cover these excluded topics: ").append(String.join("; ", recordedBlacklist))
                    .append('\n');
        }
        return text.toString();
    }
}
