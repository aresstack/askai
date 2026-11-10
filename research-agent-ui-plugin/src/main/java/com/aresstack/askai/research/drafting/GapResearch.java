package com.aresstack.askai.research.drafting;

/**
 * "Research this gap" (plan PR 29): one open point (a writer {@code GAP:} line or a thin section) becomes one
 * priority entry of the PR-14 search plan with the section title and the gap as its base query, labelled with the
 * section, and the queue runs it in {@code draft/running} without a phase change. New sources join the pool and
 * carry the plan entry that names the section, so a following "Rewrite section" can cite them.
 */
public final class GapResearch {

    /** The search plan queue of PR 14, narrowed to what the gap search needs. */
    public interface Queue {
        /** Add (idempotent per id) a priority entry; @return its id, or {@code null} without a plan. */
        String addPriority(String entryId, String phase, String query, String label) throws Exception;

        void run();
    }

    static final int MAX_QUERY_CHARS = 160;

    private final Queue queue;
    private final com.aresstack.askai.research.plan.SourcePreference preference;

    public GapResearch(Queue queue) {
        this(queue, com.aresstack.askai.research.plan.SourcePreference.NONE);
    }

    /** @param preference the mission's source preference: its publisher queries join the gap's query */
    public GapResearch(Queue queue, com.aresstack.askai.research.plan.SourcePreference preference) {
        this.queue = queue;
        this.preference = preference == null ? com.aresstack.askai.research.plan.SourcePreference.NONE : preference;
    }

    /** @return the plan entry id, or a refusal starting with "Not" */
    public String research(String sectionId, String sectionTitle, String gap) throws Exception {
        String query = query(sectionTitle, gap);
        if (query.isEmpty()) {
            return "Nothing to search: the gap is empty.";
        }
        String id = "gap-" + sectionId + "-" + Integer.toHexString(query.hashCode());
        String added = queue.addPriority(id, SectionWritingJob.PHASE, query, "Section: " + sectionTitle);
        if (added == null) {
            return "Not possible: there is no search plan yet.";
        }
        String base = query(sectionTitle, gap);
        for (String variant : preference.queriesFor(base, id.hashCode() & 0x7fff, 1)) {
            queue.addPriority(id + "-" + Integer.toHexString(variant.hashCode()), SectionWritingJob.PHASE, variant,
                    "Section: " + sectionTitle);
        }
        queue.run();
        return added;
    }

    static String query(String sectionTitle, String gap) {
        String text = ((sectionTitle == null ? "" : sectionTitle) + " " + (gap == null ? "" : gap))
                .replaceAll("\\s+", " ").trim();
        if (gap == null || gap.trim().isEmpty()) {
            return "";
        }
        if (text.length() <= MAX_QUERY_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(' ', MAX_QUERY_CHARS);
        return text.substring(0, cut > 0 ? cut : MAX_QUERY_CHARS);
    }
}
