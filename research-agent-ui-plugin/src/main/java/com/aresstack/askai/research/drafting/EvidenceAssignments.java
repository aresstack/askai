package com.aresstack.askai.research.drafting;

import com.aresstack.askai.research.runtime.writer.SectionAssignment;
import com.aresstack.askai.research.writer.EvidencePack;
import com.aresstack.askai.research.writer.SectionContextBuilder;
import com.aresstack.askai.research.writer.SectionContextRequest;
import com.aresstack.askai.research.writer.SectionWriterInputs;
import com.aresstack.askai.research.writer.WriterPackSettings;

/**
 * The writer assignment of one section from its evidence pack (PR 27) and the writer's targets (PR 28). A rewrite
 * carries the user's comment and the current text; the writer splits the section by key points and keeps its word
 * target and budget. A section written in several parts gets a pack of the set passage count for each part, and each
 * part's call gets the set count of them (live run 4: a section of 1,100 words in three parts drew on eight passages,
 * section 2.1 on eight of its 457). Every section carries the current text of the manuscript's other written
 * sections, so the writer leaves out what the book already says (live run 4, book 6).
 */
public final class EvidenceAssignments implements RuntimeSectionWriter.Assignments {

    /** The project side of a pack request: source set, synopsis, previous digest, glossary, language. */
    public interface Contexts {
        SectionContextRequest requestFor(SectionWriteRequest request) throws Exception;

        /**
         * The current text of every other written section of the manuscript, which the writer's repeat check reads
         * (live run 4, book 6: the same sentences in sections 1.1, 1.2 and 1.3); none by default.
         */
        default java.util.List<String> writtenSections(SectionWriteRequest request) throws Exception {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * Word target per call, answer budget, evidence pack, claim-check, repeat-check and synthesis thresholds
     * (settings, read per section).
     */
    public static final class Targets {
        final WriterPackSettings pack;
        final int wordsPerCall;
        final int answerTokens;
        final com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria carry;
        final com.aresstack.askai.research.runtime.writer.RepeatCheck.Criteria repeats;
        final com.aresstack.askai.research.runtime.writer.SynthesisCriteria synthesis;

        public Targets(WriterPackSettings pack, int wordsPerCall, int answerTokens,
                       com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria carry,
                       com.aresstack.askai.research.runtime.writer.RepeatCheck.Criteria repeats,
                       com.aresstack.askai.research.runtime.writer.SynthesisCriteria synthesis) {
            this.synthesis = synthesis;
            this.pack = pack;
            this.wordsPerCall = wordsPerCall;
            this.answerTokens = answerTokens;
            this.carry = carry;
            this.repeats = repeats;
        }

        public WriterPackSettings getPack() {
            return pack;
        }

        public int getWordsPerCall() {
            return wordsPerCall;
        }

        public int getAnswerTokens() {
            return answerTokens;
        }
    }

    private final SectionContextBuilder packs;
    private final Contexts contexts;
    private final java.util.function.Supplier<Targets> targetSettings;

    /** @param targets read for every section, so a changed setting applies to the next section written */
    public EvidenceAssignments(SectionContextBuilder packs, Contexts contexts,
                               java.util.function.Supplier<Targets> targets) {
        this.packs = packs;
        this.contexts = contexts;
        this.targetSettings = targets;
    }

    public SectionAssignment assignmentFor(SectionWriteRequest request) throws Exception {
        Targets targets = targetSettings.get();
        int parts = SectionAssignment.partCount(request.getSection().getBrief().getWordTarget(),
                targets.wordsPerCall, request.getSection().getBrief().getKeyPoints().size());
        EvidencePack pack = packs.build(contexts.requestFor(request),
                targets.pack.withPassageCount(targets.pack.getPassageCount() * parts));
        SectionAssignment base = SectionWriterInputs.of(pack, request.getSection().getTitle(),
                request.getSection().getBrief().getPurpose(), request.getSection().getBrief().getKeyPoints(),
                request.getSection().getBrief().getWordTarget(), targets.wordsPerCall, targets.answerTokens,
                targets.carry, targets.pack.getPassageCount());
        SectionAssignment.Builder assignment = base.toBuilder().writtenSections(contexts.writtenSections(request))
                .repeats(targets.repeats).synthesis(targets.synthesis);
        if (request.isRewrite()) {
            assignment.rewriteComment(request.getComment()).currentText(request.getCurrentText());
        }
        return assignment.build();
    }
}
