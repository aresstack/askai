package com.aresstack.askai.research.drafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one outcome a writing job ends with (plan PR 29). It offers {@code finish-document} when every section has
 * text or the user's "Skip" mark, otherwise "Write remaining sections" and the list of failed sections. It never
 * names a phase command to run: the state stays {@code draft/running}.
 */
public final class WritingOutcome {

    public static final String ACTION_FINISH = "finish-document";
    public static final String ACTION_WRITE_REMAINING = "Write remaining sections";

    private final List<String> written;
    private final Map<String, String> failed;
    private final List<String> remaining;
    private final boolean stopped;
    private final String refusal;
    private final Map<String, String> differences;

    WritingOutcome(List<String> written, Map<String, String> failed, List<String> remaining, boolean stopped,
                   String refusal, Map<String, String> differences) {
        this.written = Collections.unmodifiableList(new ArrayList<String>(written));
        this.failed = Collections.unmodifiableMap(new LinkedHashMap<String, String>(failed));
        this.remaining = Collections.unmodifiableList(new ArrayList<String>(remaining));
        this.stopped = stopped;
        this.refusal = refusal == null ? "" : refusal;
        this.differences = Collections.unmodifiableMap(new LinkedHashMap<String, String>(differences));
    }

    static WritingOutcome refused(String reason) {
        return new WritingOutcome(Collections.<String>emptyList(), Collections.<String, String>emptyMap(),
                Collections.<String>emptyList(), false, reason, Collections.<String, String>emptyMap());
    }

    /** Section ids written by this job, in order. */
    public List<String> getWritten() {
        return written;
    }

    /** Section title -> reason of every section that failed twice in this job. */
    public Map<String, String> getFailed() {
        return failed;
    }

    /** Titles of the sections that still have neither text nor a "Skip" mark. */
    public List<String> getRemaining() {
        return remaining;
    }

    public boolean isStopped() {
        return stopped;
    }

    /** Why nothing was written ({@code ""} when the job ran). */
    public String getRefusal() {
        return refusal;
    }

    /** Section id -> line difference between the old and the new text of each rewritten section. */
    public Map<String, String> getDifferences() {
        return differences;
    }

    /** The action the outcome offers: {@link #ACTION_FINISH} or {@link #ACTION_WRITE_REMAINING}. */
    public String offeredAction() {
        return refusal.isEmpty() && remaining.isEmpty() ? ACTION_FINISH : ACTION_WRITE_REMAINING;
    }

    public String text() {
        if (!refusal.isEmpty()) {
            return refusal;
        }
        StringBuilder text = new StringBuilder();
        text.append(stopped ? "Writing stopped. " : "Writing finished. ")
                .append(written.size() == 1 ? "1 section" : written.size() + " sections").append(" written.");
        if (!failed.isEmpty()) {
            text.append(" Failed: ");
            int i = 0;
            for (Map.Entry<String, String> entry : failed.entrySet()) {
                text.append(i++ > 0 ? "; " : "").append(entry.getKey()).append(" (").append(entry.getValue())
                        .append(')');
            }
            text.append('.');
        }
        if (remaining.isEmpty()) {
            text.append(" Every section has text: you can finish the document.");
        } else {
            text.append(' ').append(remaining.size() == 1 ? "1 section is" : remaining.size() + " sections are")
                    .append(" still open. Use \"").append(ACTION_WRITE_REMAINING).append("\" to continue.");
        }
        return text.toString();
    }
}
