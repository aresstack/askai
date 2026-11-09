package com.aresstack.askai.research.outline.generate;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plan PR 22: merges a fresh proposal into the stored outline by provenance, so a regeneration keeps ids and
 * never overwrites what the user edited.
 * <ul>
 * <li>a chapter matches by concept node; a section by concept node, a sub-cluster section by node and cluster;</li>
 * <li>a match keeps its id; a user-edited entry keeps its title (no longer untitled) and its brief, a generated
 * one takes the new title and brief; brief, provenance and flags are regenerated, stale marks cleared;</li>
 * <li>entries the user added (no concept node) and edited entries the proposal no longer produces are kept after
 * the generated ones, in their old order (a vanished generated chapter is kept with only those sections);
 * unedited generated entries that are gone are dropped;</li>
 * <li>the proposal's candidates replace the old ones.</li>
 * </ul>
 */
public final class OutlineProvenanceMerge {

    private OutlineProvenanceMerge() {
    }

    public static Outline merge(Outline stored, Outline proposal) {
        Set<String> usedChapters = new HashSet<String>();
        List<OutlineChapter> chapters = new ArrayList<OutlineChapter>();
        for (OutlineChapter fresh : proposal.getChapters()) {
            OutlineChapter old = null;
            for (OutlineChapter candidate : stored.getChapters()) {
                if (!usedChapters.contains(candidate.getId()) && !fresh.getProvenance().getConceptNodeId().isEmpty()
                        && fresh.getProvenance().getConceptNodeId()
                                .equals(candidate.getProvenance().getConceptNodeId())) {
                    old = candidate;
                    break;
                }
            }
            if (old == null) {
                chapters.add(fresh);
                continue;
            }
            usedChapters.add(old.getId());
            List<OutlineSection> sections = mergeSections(old.getSections(), fresh.getSections());
            chapters.add(new OutlineChapter(old.getId(), old.getOrder(),
                    old.isUserEdited() ? old.getTitle() : fresh.getTitle(),
                    old.isUserEdited() ? old.getBrief() : fresh.getBrief(), fresh.getProvenance(),
                    old.isUserEdited(), "", sections));
        }
        for (OutlineChapter old : stored.getChapters()) {
            if (usedChapters.contains(old.getId())) {
                continue;
            }
            if (keep(old.getProvenance().getConceptNodeId(), old.isUserEdited())) {
                chapters.add(old);
                continue;
            }
            // A generated chapter that is gone still carries the sections the user edited or added.
            List<OutlineSection> owned = new ArrayList<OutlineSection>();
            for (OutlineSection section : old.getSections()) {
                if (keep(section.getProvenance().getConceptNodeId(), section.isUserEdited())) {
                    owned.add(section);
                }
            }
            if (!owned.isEmpty()) {
                chapters.add(old.withSections(owned));
            }
        }
        return new Outline(chapters, proposal.getCandidates());
    }

    private static List<OutlineSection> mergeSections(List<OutlineSection> stored, List<OutlineSection> proposal) {
        Set<String> used = new HashSet<String>();
        List<OutlineSection> sections = new ArrayList<OutlineSection>();
        for (OutlineSection fresh : proposal) {
            OutlineSection old = null;
            for (OutlineSection candidate : stored) {
                if (!used.contains(candidate.getId()) && sameOrigin(candidate, fresh)) {
                    old = candidate;
                    break;
                }
            }
            if (old == null) {
                sections.add(fresh);
                continue;
            }
            used.add(old.getId());
            boolean edited = old.isUserEdited();
            sections.add(new OutlineSection(old.getId(), old.getParentId(), old.getOrder(),
                    edited ? old.getTitle() : fresh.getTitle(), edited ? old.getBrief() : fresh.getBrief(),
                    fresh.getProvenance(), edited, "", !edited && fresh.isUntitled(), fresh.isThin()));
        }
        for (OutlineSection old : stored) {
            if (!used.contains(old.getId()) && keep(old.getProvenance().getConceptNodeId(), old.isUserEdited())) {
                sections.add(old);
            }
        }
        return sections;
    }

    private static boolean sameOrigin(OutlineSection a, OutlineSection b) {
        return !a.getProvenance().getConceptNodeId().isEmpty()
                && a.getProvenance().getConceptNodeId().equals(b.getProvenance().getConceptNodeId())
                && a.getProvenance().getClusterIds().equals(b.getProvenance().getClusterIds());
    }

    private static boolean keep(String conceptNodeId, boolean userEdited) {
        return conceptNodeId.isEmpty() || userEdited;
    }
}
