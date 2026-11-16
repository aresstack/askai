package com.aresstack.askai.research.document;

import com.aresstack.askai.research.manuscript.ManuscriptWorkspace;
import com.aresstack.askai.research.outline.Outline;

/**
 * What the Document tab reads from the research session and what it starts there. The tab's views speak
 * to this port only; the session adapter lives in {@link ResearchDocumentViewContribution}. Every action
 * answers {@code null} when it started, otherwise the reason it did not.
 */
public interface DocumentSession {

    /** The outline the book is written from, or {@code null} before one is approved. */
    Outline approvedOutline();

    /** The manuscript, or {@code null} without a productive project. */
    ManuscriptWorkspace manuscript();

    /** {@code null} while the manuscript may be edited, otherwise why it is read-only. */
    String writeRefusal();

    boolean writingRunning();

    /** The writing job's line: what it writes right now, or its last outcome; "" when there is none. */
    String writingStatus();

    boolean hasStaleSections();

    /** The latest "Request changes" comment; "" when none. */
    String changeRequest();

    /** The book's quality status in a few words ("incomplete draft"); "" before the first assembly. */
    String bookStatus();

    /** The full quality report as markdown; "" before the first assembly. */
    String bookReport();

    String writeRemaining();

    void stopWriting();

    String rewriteStale();

    String rewriteSection(String sectionId, String comment);

    String rewriteChapter(String chapterId, String comment);

    /** Research an open point of a section; answers the outcome line. */
    String researchGap(String sectionId, String sectionTitle, String gap);
}
