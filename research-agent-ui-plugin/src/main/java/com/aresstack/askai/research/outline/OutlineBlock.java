package com.aresstack.askai.research.outline;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the outline for the model's Outline block (plan PR 21, seam of PR 16: ids, titles and coverage per
 * chapter) and for the {@code outline} artifact. Coverage counts the distinct passages and clusters in the
 * provenance of a chapter and its sections.
 */
public final class OutlineBlock {

    private OutlineBlock() {
    }

    /** The compact block: one line per chapter and section with its id. */
    public static String render(Outline outline, long workingRevision, int approvedRevision) {
        StringBuilder sb = new StringBuilder();
        sb.append("Outline working revision ").append(workingRevision);
        sb.append(approvedRevision > 0 ? ", last approved revision " + approvedRevision : ", not approved yet");
        sb.append('\n');
        if (outline.isEmpty()) {
            return sb.append("(no chapters yet)\n").toString();
        }
        for (OutlineChapter chapter : outline.getChapters()) {
            sb.append(chapter.getOrder()).append(". [").append(chapter.getId()).append("] ")
                    .append(chapter.getTitle().trim());
            sb.append(" (sections: ").append(chapter.getSections().size())
                    .append(", passages: ").append(passages(chapter)).append(')');
            if (chapter.isStale()) {
                sb.append(" STALE: ").append(chapter.getStaleReason());
            }
            sb.append('\n');
            for (OutlineSection section : chapter.getSections()) {
                sb.append("   ").append(chapter.getOrder()).append('.').append(section.getOrder())
                        .append(" [").append(section.getId()).append("] ").append(section.getTitle().trim());
                if (section.isStale()) {
                    sb.append(" STALE: ").append(section.getStaleReason());
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** The {@code outline} artifact: a markdown table of contents rendered from the store. */
    public static String markdown(Outline outline) {
        if (outline.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("# Outline\n\n");
        for (OutlineChapter chapter : outline.getChapters()) {
            sb.append("## ").append(chapter.getOrder()).append(". ").append(chapter.getTitle().trim()).append('\n');
            appendBrief(chapter, sb);
            for (OutlineSection section : chapter.getSections()) {
                sb.append("### ").append(chapter.getOrder()).append('.').append(section.getOrder()).append(' ')
                        .append(section.getTitle().trim()).append('\n');
                if (section.isThin()) {
                    sb.append("\n> Thin: fewer than two sources. Search for \"").append(section.getTitle().trim())
                            .append("\".\n");
                }
                appendBrief(section, sb);
            }
            sb.append('\n');
        }
        if (!outline.getCandidates().isEmpty()) {
            sb.append("## Candidates (not in the outline)\n\n");
            for (OutlineCandidate candidate : outline.getCandidates()) {
                sb.append("- ").append(candidate.getSuggestedTitle().trim().isEmpty() ? candidate.getClusterId()
                        : candidate.getSuggestedTitle().trim()).append(" (").append(candidate.getPassageIds().size())
                        .append(" passages, ").append(candidate.getSourceCount()).append(" sources)\n");
            }
        }
        return sb.toString();
    }

    private static void appendBrief(OutlineEntry entry, StringBuilder sb) {
        if (entry.isStale()) {
            sb.append("\n> Stale: ").append(entry.getStaleReason()).append('\n');
        }
        OutlineBrief brief = entry.getBrief();
        if (!brief.getPurpose().trim().isEmpty()) {
            sb.append('\n').append(brief.getPurpose().trim()).append('\n');
        }
        List<String> points = brief.getKeyPoints();
        if (!points.isEmpty()) {
            sb.append('\n');
            for (String point : points) {
                sb.append("- ").append(point.trim()).append('\n');
            }
        }
        if (brief.getWordTarget() > 0) {
            sb.append("\nAbout ").append(brief.getWordTarget()).append(" words.\n");
        }
        sb.append('\n');
    }

    private static int passages(OutlineChapter chapter) {
        Set<String> ids = new HashSet<String>(chapter.getProvenance().getPassageIds());
        for (OutlineSection section : chapter.getSections()) {
            ids.addAll(section.getProvenance().getPassageIds());
        }
        return ids.size();
    }
}
