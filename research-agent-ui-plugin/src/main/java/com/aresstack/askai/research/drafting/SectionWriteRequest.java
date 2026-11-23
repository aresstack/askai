package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;

/** One call of the section writer: a first draft, or a rewrite with the user's comment and the current text. */
public final class SectionWriteRequest {

    private final OutlineChapter chapter;
    private final OutlineSection section;
    private final String comment;
    private final String currentText;

    private SectionWriteRequest(OutlineChapter chapter, OutlineSection section, String comment, String currentText) {
        if (chapter == null || section == null) {
            throw new IllegalArgumentException("chapter and section are required");
        }
        this.chapter = chapter;
        this.section = section;
        this.comment = comment == null ? "" : comment.trim();
        this.currentText = currentText == null ? "" : currentText;
    }

    public static SectionWriteRequest draft(OutlineChapter chapter, OutlineSection section) {
        return new SectionWriteRequest(chapter, section, "", "");
    }

    public static SectionWriteRequest rewrite(OutlineChapter chapter, OutlineSection section, String comment,
                                              String currentText) {
        return new SectionWriteRequest(chapter, section, comment, currentText);
    }

    public OutlineChapter getChapter() {
        return chapter;
    }

    public OutlineSection getSection() {
        return section;
    }

    /** The user's comment of "Rewrite section" or "Rewrite chapter"; empty for a draft or a plain rewrite. */
    public String getComment() {
        return comment;
    }

    /** The current text of the section for a rewrite; empty for a first draft. */
    public String getCurrentText() {
        return currentText;
    }

    public boolean isRewrite() {
        return !currentText.isEmpty();
    }
}
