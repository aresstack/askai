package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.ManuscriptSection;
import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.OutlineFingerprint;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.store.FileManuscriptStore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Document tab's editing state for one section at a time (plan PR 26, RA-P008), free of Swing: the loaded
 * revision, the local text and the outcome of Save. A save conflict keeps the local text and carries the saved
 * text, so the tab can show both; switching between Edit and Page never drops the local text.
 */
public final class SectionEditor {

    private static final Pattern MARKER = Pattern.compile("\\[(\\d+)]");

    /** One section of the approved outline as the tab lists it. */
    public static final class Item {
        public final String sectionId;
        public final String chapterId;
        public final String label;
        public final OutlineSection section;
        public final OutlineChapter chapter;

        Item(OutlineChapter chapter, OutlineSection section, String label) {
            this.sectionId = section.getId();
            this.chapterId = chapter.getId();
            this.label = label;
            this.section = section;
            this.chapter = chapter;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** The outcome of Save. */
    public static final class SaveResult {
        public final boolean saved;
        public final String message;
        /** The saved text of a conflict ("" otherwise); the local text stays in the editor. */
        public final String storedText;

        SaveResult(boolean saved, String message, String storedText) {
            this.saved = saved;
            this.message = message;
            this.storedText = storedText;
        }
    }

    private final ManuscriptWorkspace manuscript;
    private Item item;
    private int loadedRevision;
    private String localText = "";
    private boolean dirty;

    public SectionEditor(ManuscriptWorkspace manuscript) {
        this.manuscript = manuscript;
    }

    /** The sections of the approved outline in order, labelled "1.2 Title". */
    public static List<Item> items(Outline approved) {
        if (approved == null) {
            return Collections.emptyList();
        }
        List<Item> items = new ArrayList<Item>();
        int c = 0;
        for (OutlineChapter chapter : approved.getChapters()) {
            c++;
            int s = 0;
            for (OutlineSection section : chapter.getSections()) {
                s++;
                if (FileManuscriptStore.isValidSectionId(section.getId())) {
                    items.add(new Item(chapter, section, c + "." + s + " " + section.getTitle()));
                }
            }
        }
        return items;
    }

    /** Open a section: the local text becomes its current revision. */
    public void open(Item next) {
        item = next;
        SectionRevision current = next == null ? null : manuscript.store().current(next.sectionId);
        loadedRevision = current == null ? 0 : current.getNumber();
        ManuscriptSection state = next == null ? null : manuscript.store().section(next.sectionId);
        if (state != null) {
            loadedRevision = state.getRevision();
        }
        localText = current == null ? "" : current.getText();
        dirty = false;
    }

    public Item item() {
        return item;
    }

    public int loadedRevision() {
        return loadedRevision;
    }

    public String localText() {
        return localText;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** The editor changed the text. */
    public void edit(String text) {
        String value = text == null ? "" : text;
        if (!value.equals(localText)) {
            localText = value;
            dirty = true;
        }
    }

    /** The local markers of the text with the source each one cites ("[1] source-id"), or "no source". */
    public List<String> markers() {
        List<String> lines = new ArrayList<String>();
        SectionRevision current = item == null || loadedRevision == 0
                ? null : manuscript.store().revision(item.sectionId, loadedRevision);
        Matcher matcher = MARKER.matcher(localText);
        List<String> seen = new ArrayList<String>();
        while (matcher.find()) {
            String marker = matcher.group(1);
            if (seen.contains(marker)) {
                continue;
            }
            seen.add(marker);
            ManuscriptCitation citation = find(current, marker);
            lines.add("[" + marker + "] " + (citation == null ? "no source" : citation.getSourceId()));
        }
        return lines;
    }

    /**
     * Save the local text as a new revision based on the loaded one. The citations of the loaded revision whose
     * markers remain in the text are kept; the fingerprints are those of the approved outline entries.
     */
    public SaveResult save(long nowMillis) {
        if (item == null) {
            return new SaveResult(false, "Choose a section first.", "");
        }
        SectionRevision base = loadedRevision == 0 ? null : manuscript.store().revision(item.sectionId,
                loadedRevision);
        List<ManuscriptCitation> kept = new ArrayList<ManuscriptCitation>();
        if (base != null) {
            for (ManuscriptCitation citation : base.getCitations()) {
                if (localText.contains("[" + citation.getMarker() + "]")) {
                    kept.add(citation);
                }
            }
        }
        SectionRevision.Builder content = SectionRevision.builder().text(localText).citations(kept)
                .author(SectionRevision.AUTHOR_USER)
                .gaps(base == null ? null : base.getGaps())
                .evidenceIds(base == null ? null : base.getEvidenceIds())
                .sectionFingerprint(OutlineFingerprint.of(item.section))
                .chapterFingerprint(OutlineFingerprint.of(item.chapter));
        try {
            FileManuscriptStore.Write write = manuscript.save(item.sectionId, item.chapterId, loadedRevision,
                    content, nowMillis);
            loadedRevision = write.revision.getNumber();
            dirty = false;
            return new SaveResult(true, "Saved (revision " + loadedRevision + ").", "");
        } catch (FileManuscriptStore.SectionConflictException conflict) {
            loadedRevision = conflict.getCurrentRevision(); // the next save goes on top, keeping both texts
            return new SaveResult(false, conflict.getMessage(), conflict.getCurrentText());
        } catch (ManuscriptWorkspace.WriteRefusedException refused) {
            return new SaveResult(false, "Not saved: " + refused.getMessage(), "");
        } catch (RuntimeException failed) {
            return new SaveResult(false, "Not saved: " + failed.getMessage(), "");
        }
    }

    /** The stored state of the open section, or {@code null}. */
    public ManuscriptSection manuscriptSection() {
        return item == null ? null : manuscript.store().section(item.sectionId);
    }

    /** The revisions of the open section, oldest first. */
    public List<SectionRevision> revisions() {
        return item == null ? Collections.<SectionRevision>emptyList() : manuscript.store().revisions(item.sectionId);
    }

    private static ManuscriptCitation find(SectionRevision revision, String marker) {
        if (revision == null) {
            return null;
        }
        for (ManuscriptCitation citation : revision.getCitations()) {
            if (citation.getMarker().equals(marker)) {
                return citation;
            }
        }
        return null;
    }
}
