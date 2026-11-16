package com.aresstack.askai.research.manuscript;

import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.store.FileManuscriptStore;

/**
 * The generated, read-only {@code document.md} (plan PR 26): the sections of the approved outline in order, each
 * with its current revision. Skipped and orphan sections are left out, a section without text shows its status.
 * PR 29's assembler replaces this with front matter, references and the report.
 */
public final class ManuscriptDocument {

    private ManuscriptDocument() {
    }

    public static String markdown(Outline approved, FileManuscriptStore store) {
        StringBuilder md = new StringBuilder();
        if (approved == null || approved.isEmpty()) {
            return "_The document is written after the outline is approved._\n";
        }
        int chapterNumber = 0;
        for (OutlineChapter chapter : approved.getChapters()) {
            chapterNumber++;
            md.append("# ").append(chapterNumber).append(' ').append(chapter.getTitle()).append("\n\n");
            int sectionNumber = 0;
            for (OutlineSection section : chapter.getSections()) {
                sectionNumber++;
                ManuscriptSection state = FileManuscriptStore.isValidSectionId(section.getId())
                        ? store.section(section.getId()) : null;
                if (state != null && (state.getStatus() == SectionStatus.SKIPPED
                        || state.getStatus() == SectionStatus.ORPHAN)) {
                    continue;
                }
                md.append("## ").append(chapterNumber).append('.').append(sectionNumber).append(' ')
                        .append(section.getTitle()).append("\n\n");
                SectionRevision current = state == null || !state.hasText()
                        ? null : store.revision(section.getId(), state.getRevision());
                if (current == null) {
                    md.append("_").append(state == null ? SectionStatus.PENDING.label() : state.getStatus().label())
                            .append(state != null && !state.getReason().isEmpty() ? ": " + state.getReason() : "")
                            .append("_\n\n");
                } else {
                    md.append(current.getText().trim()).append("\n\n");
                }
            }
        }
        return md.toString();
    }
}
