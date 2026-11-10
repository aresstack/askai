package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Merges a fresh proposal into the stored outline by provenance, so a regeneration keeps ids and never overwrites
 * what the user edited. The synthesized outline (Phase 3) no longer follows the concept node for node, so entries
 * match by the PASSAGES they rest on:
 * <ul>
 * <li>a fresh chapter matches the stored chapter whose passages overlap its own most (Jaccard, at least the
 * configured overlap); a fresh section matches the same way among the sections of the matched chapter;</li>
 * <li>a match keeps its id; a user-edited entry keeps its title and brief and is marked stale when its passages
 * changed (the user sees that the research behind it moved); a generated one takes the new title and brief;
 * provenance and flags are regenerated, other stale marks cleared;</li>
 * <li>entries the user added or edited that the proposal no longer produces are kept after the generated ones, in
 * their old order (a vanished generated chapter is kept with only those sections); unedited generated entries that
 * are gone are dropped. Generated entries are recognised by their provenance (a concept node or material ids);
 * entries the user added carry none;</li>
 * <li>the proposal's candidates and report replace the old ones.</li>
 * </ul>
 */
public final class OutlineProvenanceMerge {

    /** The stale mark of an edited entry whose research changed. */
    public static final String RESEARCH_CHANGED = "The research behind this entry changed in the regenerated outline; "
            + "your edits were kept. Review it.";

    /** The default minimum passage overlap (Jaccard) for a fresh entry to take a stored entry's place. */
    public static final double DEFAULT_MIN_OVERLAP = 0.5;

    private OutlineProvenanceMerge() {
    }

    public static Outline merge(Outline stored, Outline proposal) {
        return merge(stored, proposal, DEFAULT_MIN_OVERLAP);
    }

    public static Outline merge(Outline stored, Outline proposal, double minOverlap) {
        Map<Integer, Integer> matches = match(proposal.getChapters(), stored.getChapters(), minOverlap);
        Set<String> usedChapters = new HashSet<String>();
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        for (int i = 0; i < proposal.getChapters().size(); i++) {
            OutlineChapter fresh = proposal.getChapters().get(i);
            Integer matched = matches.get(i);
            if (matched == null) {
                chapters.add(fresh);
                continue;
            }
            OutlineChapter old = stored.getChapters().get(matched);
            usedChapters.add(old.getId());
            List<OutlineSection> sections = mergeSections(old.getSections(), fresh.getSections(), minOverlap);
            boolean edited = old.isUserEdited();
            chapters.add(new OutlineChapter(old.getId(), old.getOrder(), edited ? old.getTitle() : fresh.getTitle(),
                    edited ? old.getBrief() : fresh.getBrief(), fresh.getProvenance(), edited,
                    edited && changed(old, fresh) ? RESEARCH_CHANGED : "", sections));
        }
        for (OutlineChapter old : stored.getChapters()) {
            if (usedChapters.contains(old.getId())) {
                continue;
            }
            if (owned(old)) {
                chapters.add(old);
                continue;
            }
            // A generated chapter that is gone still carries the sections the user edited or added.
            List<OutlineSection> owned = new ArrayList<OutlineSection>();
            for (OutlineSection section : old.getSections()) {
                if (owned(section)) {
                    owned.add(section);
                }
            }
            if (!owned.isEmpty()) {
                chapters.add(old.withSections(owned));
            }
        }
        return new Outline(chapters, proposal.getCandidates(), proposal.getReport());
    }

    private static List<OutlineSection> mergeSections(List<OutlineSection> stored, List<OutlineSection> proposal,
                                                      double minOverlap) {
        Map<Integer, Integer> matches = match(proposal, stored, minOverlap);
        Set<String> used = new HashSet<String>();
        List<OutlineSection> sections = new ArrayList<OutlineSection>();
        for (int i = 0; i < proposal.size(); i++) {
            OutlineSection fresh = proposal.get(i);
            Integer matched = matches.get(i);
            if (matched == null) {
                sections.add(fresh);
                continue;
            }
            OutlineSection old = stored.get(matched);
            used.add(old.getId());
            boolean edited = old.isUserEdited();
            sections.add(new OutlineSection(old.getId(), old.getParentId(), old.getOrder(),
                    edited ? old.getTitle() : fresh.getTitle(), edited ? old.getBrief() : fresh.getBrief(),
                    fresh.getProvenance(), edited, edited && changed(old, fresh) ? RESEARCH_CHANGED : "",
                    !edited && fresh.isUntitled(), fresh.isThin()));
        }
        for (OutlineSection old : stored) {
            if (!used.contains(old.getId()) && owned(old)) {
                sections.add(old);
            }
        }
        return sections;
    }

    /** Fresh index to stored index, best overlaps first; each entry is used once. */
    private static <T extends OutlineEntry> Map<Integer, Integer> match(List<T> fresh, List<T> stored,
                                                                        double minOverlap) {
        List<double[]> pairs = new ArrayList<double[]>();
        for (int i = 0; i < fresh.size(); i++) {
            for (int j = 0; j < stored.size(); j++) {
                double overlap = overlap(fresh.get(i).getProvenance(), stored.get(j).getProvenance());
                if (overlap > 0 && overlap >= minOverlap) {
                    pairs.add(new double[]{overlap, i, j});
                }
            }
        }
        Collections.sort(pairs, new Comparator<double[]>() {
            public int compare(double[] a, double[] b) {
                int byOverlap = Double.compare(b[0], a[0]);
                if (byOverlap != 0) {
                    return byOverlap;
                }
                int byFresh = Double.compare(a[1], b[1]);
                return byFresh != 0 ? byFresh : Double.compare(a[2], b[2]);
            }
        });
        Map<Integer, Integer> matches = new LinkedHashMap<Integer, Integer>();
        Set<Integer> usedStored = new HashSet<Integer>();
        for (double[] pair : pairs) {
            int i = (int) pair[1];
            int j = (int) pair[2];
            if (!matches.containsKey(i) && !usedStored.contains(j)) {
                matches.put(i, j);
                usedStored.add(j);
            }
        }
        return matches;
    }

    /** Jaccard overlap of the passages of two provenances; 0 when either has none. */
    static double overlap(OutlineProvenance a, OutlineProvenance b) {
        if (a.getPassageIds().isEmpty() || b.getPassageIds().isEmpty()) {
            return 0;
        }
        Set<String> union = new HashSet<String>(a.getPassageIds());
        union.addAll(b.getPassageIds());
        Set<String> common = new HashSet<String>(a.getPassageIds());
        common.retainAll(new HashSet<String>(b.getPassageIds()));
        return common.size() / (double) union.size();
    }

    private static boolean changed(OutlineEntry old, OutlineEntry fresh) {
        return !new HashSet<String>(old.getProvenance().getPassageIds())
                .equals(new HashSet<String>(fresh.getProvenance().getPassageIds()));
    }

    /** An entry the user owns: added by hand (no generated provenance) or edited. */
    private static boolean owned(OutlineEntry entry) {
        OutlineProvenance provenance = entry.getProvenance();
        boolean generated = !provenance.getConceptNodeId().isEmpty() || !provenance.getClusterIds().isEmpty();
        return !generated || entry.isUserEdited();
    }
}
