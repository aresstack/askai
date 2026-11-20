package com.aresstack.askai.research.benchmark.writer;

import com.aresstack.askai.research.drafting.EvidenceAssignments;
import com.aresstack.askai.research.drafting.ProductiveSectionContexts;
import com.aresstack.askai.research.drafting.RuntimeSectionWriter;
import com.aresstack.askai.research.drafting.SectionWriteRequest;
import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.store.FileManuscriptStore;
import com.aresstack.askai.research.writer.SectionContextBuilder;
import com.aresstack.askai.research.writer.SectionContextRequest;

import java.util.Map;

/**
 * The writer assignment as the app builds it (stage 0 of the writer proposal, test parity). Only the pack's
 * retrieval is replaced by the exported pack; everything after it is the app's code: the section context of
 * {@link ProductiveSectionContexts} (synopsis from the chapter's purpose, the digest of the previous section's current
 * text, the other written sections for the repeat check, the book language), the pack's budgets
 * ({@link SectionContextBuilder#pack}) and the writer targets ({@link EvidenceAssignments#assignment}: passages per
 * part, word target capped by words per passage, top-up, repeat and synthesis criteria, citation format of the model).
 */
public final class WriterAbPipeline {

    /** The fingerprint of an exported pack's world; the assignment never reads the index, so any value fits. */
    static final String EXPORTED_WORLD = "writer-ab-export";

    private final WriterAbInput input;
    private final EvidenceAssignments.Targets targets;
    private final String writerModel;

    /**
     * @param targets     the writer settings of the arm and variant ({@link WriterAbVariant#targets})
     * @param writerModel the model the writer slot resolves to, which chooses the citation format
     */
    public WriterAbPipeline(WriterAbInput input, EvidenceAssignments.Targets targets, String writerModel) {
        this.input = input;
        this.targets = targets;
        this.writerModel = writerModel == null ? "" : writerModel;
    }

    public EvidenceAssignments.Targets getTargets() {
        return targets;
    }

    /** The assignments of a writing job on {@code manuscript}: what the app's writer gets for each request. */
    public RuntimeSectionWriter.Assignments assignments(final FileManuscriptStore manuscript) {
        final ProductiveSectionContexts contexts = contexts(manuscript);
        return new RuntimeSectionWriter.Assignments() {
            public SectionAssignment assignmentFor(SectionWriteRequest request) {
                return assignment(contexts, request);
            }
        };
    }

    /** The assignment of one section written alone, on an empty manuscript (no digest, no other sections). */
    public SectionAssignment alone(String sectionId, FileManuscriptStore emptyManuscript) {
        return assignment(contexts(emptyManuscript), SectionWriteRequest.draft(input.outlineChapter(),
                sectionOf(sectionId)));
    }

    private SectionAssignment assignment(ProductiveSectionContexts contexts, SectionWriteRequest request) {
        SectionContextRequest context = contexts.requestFor(request);
        WriterAbInput.Section section = input.section(request.getSection().getId());
        com.aresstack.askai.research.writer.WriterPackSettings settings =
                EvidenceAssignments.packSettings(request.getSection(), targets);
        // the exported pack holds what the app selects under the export's pack settings, and only under those
        if (!section.packSettings.isEmpty() && !section.packSettings.equals(settings.toString())) {
            throw new IllegalStateException("section " + section.id + " was exported with the pack settings "
                    + section.packSettings + ", this arm asks for " + settings + "; export the chapter again with "
                    + "-Daskai.benchmark.writerAb.export.settings for this arm");
        }
        return EvidenceAssignments.assignment(
                SectionContextBuilder.pack(context, settings, section.passages, section.glossary),
                request, targets, contexts.writtenSections(request), writerModel);
    }

    private ProductiveSectionContexts contexts(FileManuscriptStore manuscript) {
        final Map<String, String> checksums = input.sourceChecksums();
        return new ProductiveSectionContexts("writer-ab", EXPORTED_WORLD, null,
                new ProductiveSectionContexts.AcceptedSources() {
                    public Map<String, String> checksums() {
                        return checksums;
                    }
                }, manuscript, () -> input.language);
    }

    private com.aresstack.askai.research.outline.OutlineSection sectionOf(String id) {
        for (com.aresstack.askai.research.outline.OutlineSection section : input.outlineChapter().getSections()) {
            if (section.getId().equals(id)) {
                return section;
            }
        }
        throw new IllegalArgumentException("no section " + id);
    }
}
