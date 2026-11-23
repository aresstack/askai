package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.outline.Outline;
import com.aresstack.askai.research.outline.OutlineBrief;
import com.aresstack.askai.research.outline.OutlineChapter;
import com.aresstack.askai.research.outline.OutlineProvenance;
import com.aresstack.askai.research.outline.OutlineSection;
import com.aresstack.askai.research.store.FileManuscriptStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A Document tab session for tests: a book of two chapters (1: s1, s2, s3; 2: s4) on a real manuscript
 * store in a temporary folder, and a record of what the tab started.
 */
final class FakeDocumentSession implements DocumentSession {

    final ManuscriptWorkspace manuscript;
    final List<String> started = new ArrayList<String>();
    Outline outline = book();
    String refusal;
    boolean running;
    String writingStatus = "";
    boolean stale;
    String changeRequest = "";
    String bookStatus = "";
    String startRefusal;

    FakeDocumentSession(File folder) {
        manuscript = new ManuscriptWorkspace(new FileManuscriptStore(new File(folder, "manuscript")));
    }

    static Outline book() {
        return new Outline(Arrays.asList(
                chapter("c1", 1, "Wearables on site", section("s1", "c1", 1, "Fall detection"),
                        section("s2", "c1", 2, "Heat stress"), section("s3", "c1", 3, "Exoskeletons")),
                chapter("c2", 2, "Data protection", section("s4", "c2", 1, "Works council"))));
    }

    private static OutlineChapter chapter(String id, int order, String title, OutlineSection... sections) {
        return new OutlineChapter(id, order, title, new OutlineBrief("why " + title, Arrays.asList("k"), 400),
                OutlineProvenance.NONE, false, "", Arrays.asList(sections));
    }

    private static OutlineSection section(String id, String parent, int order, String title) {
        return new OutlineSection(id, parent, order, title,
                new OutlineBrief("on " + title, Collections.<String>emptyList(), 200), OutlineProvenance.NONE,
                false, "");
    }

    /** The writer (or the user) saved a revision of a section. */
    void write(String sectionId, String chapterId, String text) {
        com.aresstack.askai.research.manuscript.ManuscriptSection state = manuscript.store().section(sectionId);
        manuscript.save(sectionId, chapterId, state == null ? 0 : state.getRevision(),
                SectionRevision.builder().text(text).author(SectionRevision.AUTHOR_WRITER), System.currentTimeMillis());
    }

    public Outline approvedOutline() {
        return outline;
    }

    public ManuscriptWorkspace manuscript() {
        return manuscript;
    }

    public String writeRefusal() {
        return refusal;
    }

    public boolean writingRunning() {
        return running;
    }

    public String writingStatus() {
        return writingStatus;
    }

    public boolean hasStaleSections() {
        return stale;
    }

    public String changeRequest() {
        return changeRequest;
    }

    public String bookStatus() {
        return bookStatus;
    }

    public String bookReport() {
        return bookStatus.isEmpty() ? "" : "# Quality report\n\n" + bookStatus;
    }

    public String writeRemaining() {
        started.add("write-remaining");
        return startRefusal;
    }

    public void stopWriting() {
        started.add("stop");
    }

    public String rewriteStale() {
        started.add("rewrite-stale");
        return startRefusal;
    }

    public String rewriteSection(String sectionId, String comment) {
        started.add("rewrite-section " + sectionId + " " + comment);
        return startRefusal;
    }

    public String rewriteChapter(String chapterId, String comment) {
        started.add("rewrite-chapter " + chapterId + " " + comment);
        return startRefusal;
    }

    public String researchGap(String sectionId, String sectionTitle, String gap) {
        started.add("gap " + sectionId + " " + gap);
        return "gap-1";
    }
}
