package com.aresstack.askai.research.drafting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * "Research this gap" (plan PR 29): one open point (a writer {@code GAP:} line or a thin section) becomes one
 * priority entry of the PR-14 search plan, labelled with the section, and the queue runs it in {@code draft/running}
 * without a phase change. New sources join the pool and carry the plan entry that names the section, so a following
 * "Rewrite section" can cite them.
 *
 * <p>The query is built like a first-level card's base query ({@code ConceptPlanNodes.gapQuery}): the gap's content
 * words, without the plan's stopwords and blacklist terms, and up to three content words of the book's subject (live
 * run 4, book 5: "Technologien und Herausforderungen Die digitale Technologie bietet viele Vorteile, …" searched a
 * generic section title and a whole sentence of the writer's own text).</p>
 */
public final class GapResearch {

    /** The search plan queue of PR 14, narrowed to what the gap search needs. */
    public interface Queue {
        /** Add (idempotent per id) a priority entry; @return its id, or {@code null} without a plan. */
        String addPriority(String entryId, String phase, String query, String label) throws Exception;

        void run();
    }

    private final Queue queue;
    private final com.aresstack.askai.research.plan.SourcePreference preference;
    private final String subject;
    private final List<String> blacklist;

    /** Without a source preference, a subject or a blacklist. */
    public GapResearch(Queue queue) {
        this(queue, com.aresstack.askai.research.plan.SourcePreference.NONE, "",
                Collections.<String>emptyList());
    }

    /**
     * @param preference the mission's source preference: its publisher queries join the gap's query
     * @param subject    the book title, or the mission when there is none: its first content words join the query
     * @param blacklist  the blacklist terms, kept out of the query as out of every plan query
     */
    public GapResearch(Queue queue, com.aresstack.askai.research.plan.SourcePreference preference, String subject,
                       List<String> blacklist) {
        this.queue = queue;
        this.preference = preference == null ? com.aresstack.askai.research.plan.SourcePreference.NONE : preference;
        this.subject = subject == null ? "" : subject;
        this.blacklist = blacklist == null ? Collections.<String>emptyList() : new ArrayList<String>(blacklist);
    }

    /** @return the plan entry id, or a refusal starting with "Not" or "Nothing" */
    public String research(String sectionId, String sectionTitle, String gap) throws Exception {
        String query = query(gap);
        if (query.isEmpty()) {
            return "Nothing to search: the gap names no content word.";
        }
        String id = "gap-" + sectionId + "-" + Integer.toHexString(query.hashCode());
        String added = queue.addPriority(id, SectionWritingJob.PHASE, query, "Section: " + sectionTitle);
        if (added == null) {
            return "Not possible: there is no search plan yet.";
        }
        for (String variant : preference.queriesFor(query, id.hashCode() & 0x7fff, 1)) {
            queue.addPriority(id + "-" + Integer.toHexString(variant.hashCode()), SectionWritingJob.PHASE, variant,
                    "Section: " + sectionTitle);
        }
        queue.run();
        return added;
    }

    /** The plan query of a gap; empty when the gap names no content word. */
    String query(String gap) {
        if (gap == null || gap.trim().isEmpty()) {
            return "";
        }
        return com.aresstack.askai.research.plan.ConceptPlanNodes.gapQuery(gap, subject, blacklist);
    }
}
