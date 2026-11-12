package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.manuscript.GapSources;
import com.aresstack.askai.research.plan.SearchPlanEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Every gap entry id starts with this, followed by the section id and a dash ({@link #research}). */
    public static final String ENTRY_PREFIX = "gap-";

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
        String id = ENTRY_PREFIX + sectionId + "-" + Integer.toHexString(query.hashCode());
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

    /**
     * Whether a plan entry is a writer gap's search: one of its source-preference variants, or a re-run of either,
     * whose id keeps the gap's id in front ({@code SearchPlanQueue.againId}).
     */
    public static boolean isGapEntry(SearchPlanEntry entry) {
        return entry != null && SectionWritingJob.PHASE.equals(entry.getPhase())
                && entry.getId().startsWith(ENTRY_PREFIX);
    }

    /**
     * The sources the gap searches brought in, per section of {@code sectionIds} in their order: every gap entry
     * with accepted sources counts, whether it completed or failed later (its sources are in the pool either way).
     * The section of an entry is the longest section id the entry id names after {@link #ENTRY_PREFIX}, since a
     * section id may itself contain dashes.
     */
    public static List<GapSources> researched(Collection<SearchPlanEntry> entries, Collection<String> sectionIds) {
        Map<String, Set<String>> sources = new LinkedHashMap<String, Set<String>>();
        Map<String, Set<String>> queries = new LinkedHashMap<String, Set<String>>();
        Map<String, Long> last = new LinkedHashMap<String, Long>();
        for (String id : sectionIds) {
            sources.put(id, new LinkedHashSet<String>());
            queries.put(id, new LinkedHashSet<String>());
            last.put(id, 0L);
        }
        for (SearchPlanEntry entry : entries) {
            if (!isGapEntry(entry) || entry.getAcceptedSourceIds().isEmpty()) {
                continue;
            }
            String section = sectionOf(entry.getId(), sectionIds);
            if (section == null) {
                continue;
            }
            sources.get(section).addAll(entry.getAcceptedSourceIds());
            queries.get(section).add(entry.getQuery());
            last.put(section, Math.max(last.get(section), entry.getLastSourceAtMillis()));
        }
        List<GapSources> out = new ArrayList<GapSources>();
        for (Map.Entry<String, Set<String>> e : sources.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(new GapSources(e.getKey(), new ArrayList<String>(e.getValue()), last.get(e.getKey()),
                        new ArrayList<String>(queries.get(e.getKey()))));
            }
        }
        return out;
    }

    /** The longest section id that {@code entryId} names, or {@code null}. */
    static String sectionOf(String entryId, Collection<String> sectionIds) {
        String best = null;
        for (String id : sectionIds) {
            if (entryId.startsWith(ENTRY_PREFIX + id + "-") && (best == null || id.length() > best.length())) {
                best = id;
            }
        }
        return best;
    }
}
