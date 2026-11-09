package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.manuscript.ManuscriptCitation;
import com.aresstack.askai.research.manuscript.SectionRevision;
import com.aresstack.askai.research.runtime.writer.EvidencePassage;
import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.runtime.writer.SectionDraft;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Adapts the section writer of plan PR 28 ({@code runtime.writer.SectionWriter}) to the writing job's port. The
 * assignment (evidence pack, synopsis, word targets) comes from an {@link Assignments} source fed by PR 27; this
 * adapter maps the draft's local markers to manuscript citations and keeps its gaps, model label and prompt hash.
 */
public final class RuntimeSectionWriter implements SectionWriter {

    /** Builds the writer assignment of one request (the evidence pack of PR 27 and the writer's targets). */
    public interface Assignments {
        SectionAssignment assignmentFor(SectionWriteRequest request) throws Exception;
    }

    private final com.aresstack.askai.research.runtime.writer.SectionWriter writer;
    private final Assignments assignments;

    public RuntimeSectionWriter(com.aresstack.askai.research.runtime.writer.SectionWriter writer,
                                Assignments assignments) {
        this.writer = writer;
        this.assignments = assignments;
    }

    public SectionRevision.Builder write(SectionWriteRequest request, Cancellation cancellation) throws Exception {
        if (cancellation.isCancelled()) {
            throw new Cancelled();
        }
        SectionDraft draft = writer.write(assignments.assignmentFor(request));
        if (cancellation.isCancelled()) {
            throw new Cancelled();
        }
        if (!draft.isOk()) {
            throw new IllegalStateException(draft.getFailure());
        }
        return toRevision(draft);
    }

    static SectionRevision.Builder toRevision(SectionDraft draft) {
        List<ManuscriptCitation> citations = new ArrayList<ManuscriptCitation>();
        List<String> evidenceIds = new ArrayList<String>();
        for (Map.Entry<Integer, EvidencePassage> entry : draft.getCitations().entrySet()) {
            EvidencePassage passage = entry.getValue();
            citations.add(new ManuscriptCitation(String.valueOf(entry.getKey()), passage.getSourceId(),
                    passage.getSourceChecksum(), passage.getText(), passage.getPassageId(),
                    Collections.<String>emptyList()));
            if (!passage.getPassageId().isEmpty()) {
                evidenceIds.add(passage.getPassageId());
            }
        }
        return SectionRevision.builder().text(draft.getText()).citations(citations).gaps(draft.getGaps())
                .author(SectionRevision.AUTHOR_WRITER).modelLabel(draft.getModelLabel())
                .promptHash(draft.getPromptHash()).evidenceIds(evidenceIds);
    }
}
