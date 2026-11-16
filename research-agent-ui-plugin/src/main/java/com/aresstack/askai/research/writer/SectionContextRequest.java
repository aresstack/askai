package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.outline.OutlineEntry;
import com.aresstack.askai.research.outline.OutlineSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything the {@link SectionContextBuilder} reads for one section: the approved outline entries of the section
 * and its chapter, the sources it may use, the active embedding world, and the book-level blocks (synopsis,
 * previous-section digest, glossary of the approved concept labels, book language of the Phase-1 record).
 */
public final class SectionContextRequest {

    private final String projectId;
    private final String embeddingFingerprint;
    private final OutlineEntry chapter;
    private final OutlineSection section;
    private final EvidenceSourceSet sources;
    private final String synopsis;
    private final String previousSectionDigest;
    private final List<String> glossary;
    private final String bookLanguage;

    public SectionContextRequest(String projectId, String embeddingFingerprint, OutlineEntry chapter,
                                 OutlineSection section, EvidenceSourceSet sources, String synopsis,
                                 String previousSectionDigest, List<String> glossary, String bookLanguage) {
        if (section == null || sources == null) {
            throw new IllegalArgumentException("a section context needs a section and its source set");
        }
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("a section context needs the active embedding fingerprint");
        }
        this.projectId = projectId == null ? "" : projectId;
        this.embeddingFingerprint = embeddingFingerprint.trim();
        this.chapter = chapter;
        this.section = section;
        this.sources = sources;
        this.synopsis = synopsis == null ? "" : synopsis;
        this.previousSectionDigest = previousSectionDigest == null ? "" : previousSectionDigest;
        this.glossary = Collections.unmodifiableList(new ArrayList<String>(
                glossary == null ? Collections.<String>emptyList() : glossary));
        this.bookLanguage = bookLanguage == null ? "" : bookLanguage.trim();
    }

    public String getProjectId() {
        return projectId;
    }

    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    /** The chapter entry, or {@code null}. */
    public OutlineEntry getChapter() {
        return chapter;
    }

    public OutlineSection getSection() {
        return section;
    }

    public EvidenceSourceSet getSources() {
        return sources;
    }

    public String getSynopsis() {
        return synopsis;
    }

    public String getPreviousSectionDigest() {
        return previousSectionDigest;
    }

    public List<String> getGlossary() {
        return glossary;
    }

    public String getBookLanguage() {
        return bookLanguage;
    }
}
