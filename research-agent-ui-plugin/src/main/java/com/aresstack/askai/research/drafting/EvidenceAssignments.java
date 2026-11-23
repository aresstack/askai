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
        final String citationFormat;
        final com.aresstack.askai.research.runtime.writer.TopUpCriteria topUp;
        final int wordsPerPassage;
        final boolean perSourceScales;
        final com.aresstack.askai.research.runtime.writer.PartContextCriteria partContext;

        /** With the citation format chosen by the writer model ({@code CitationFormat.AUTO}). */
        public Targets(WriterPackSettings pack, int wordsPerCall, int answerTokens,
                       com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria carry,
                       com.aresstack.askai.research.runtime.writer.RepeatCheck.Criteria repeats,
                       com.aresstack.askai.research.runtime.writer.SynthesisCriteria synthesis) {
            this(pack, wordsPerCall, answerTokens, carry, repeats, synthesis,
                    com.aresstack.askai.research.runtime.writer.CitationFormat.AUTO);
        }

        /**
         * @param citationFormat the citation format setting: {@code auto} follows the writer model, a format id
         *                       ({@code numbered}, {@code sentence-tags}) forces it
         */
        public Targets(WriterPackSettings pack, int wordsPerCall, int answerTokens,
                       com.aresstack.askai.research.runtime.writer.ClaimCarryCheck.Criteria carry,
                       com.aresstack.askai.research.runtime.writer.RepeatCheck.Criteria repeats,
                       com.aresstack.askai.research.runtime.writer.SynthesisCriteria synthesis,
                       String citationFormat) {
            this.citationFormat = citationFormat == null ? "" : citationFormat;
            this.synthesis = synthesis;
            this.pack = pack;
            this.wordsPerCall = wordsPerCall;
            this.answerTokens = answerTokens;
            this.carry = carry;
            this.repeats = repeats;
            this.topUp = com.aresstack.askai.research.runtime.writer.TopUpCriteria.off();
            this.wordsPerPassage = 0;
            this.perSourceScales = false;
            this.partContext = com.aresstack.askai.research.runtime.writer.PartContextCriteria.off();
        }

        private Targets(Targets base, com.aresstack.askai.research.runtime.writer.TopUpCriteria topUp,
                        int wordsPerPassage, boolean perSourceScales,
                        com.aresstack.askai.research.runtime.writer.PartContextCriteria partContext) {
            this.partContext = partContext == null
                    ? com.aresstack.askai.research.runtime.writer.PartContextCriteria.off() : partContext;
            this.perSourceScales = perSourceScales;
            this.citationFormat = base.citationFormat;
            this.synthesis = base.synthesis;
            this.pack = base.pack;
            this.wordsPerCall = base.wordsPerCall;
            this.answerTokens = base.answerTokens;
            this.carry = base.carry;
            this.repeats = base.repeats;
            this.topUp = topUp == null ? com.aresstack.askai.research.runtime.writer.TopUpCriteria.off() : topUp;
            this.wordsPerPassage = Math.max(0, wordsPerPassage);
        }

        /**
         * The same targets with the writer's yield (live run 5): top-up calls for a part below its word target, and
         * the words one passage of the actual pack carries, which caps the section's word target (0 = the outline's
         * target stays).
         */
        public Targets withYield(com.aresstack.askai.research.runtime.writer.TopUpCriteria topUp,
                                 int wordsPerPassage) {
            return new Targets(this, topUp, wordsPerPassage, perSourceScales, partContext);
        }

        /** The same targets with the per-source cap multiplied by the parts ({@code true}) or kept ({@code false}). */
        public Targets withPerSourceScaling(boolean scales) {
            return new Targets(this, topUp, wordsPerPassage, scales, partContext);
        }

        /** The same targets with the parts' section context (stage 1 of the writer proposal; off by default). */
        public Targets withPartContext(com.aresstack.askai.research.runtime.writer.PartContextCriteria value) {
            return new Targets(this, topUp, wordsPerPassage, perSourceScales, value);
        }

        public com.aresstack.askai.research.runtime.writer.PartContextCriteria getPartContext() {
            return partContext;
        }

        public boolean isPerSourceScaling() {
            return perSourceScales;
        }

        public com.aresstack.askai.research.runtime.writer.TopUpCriteria getTopUp() {
            return topUp;
        }

        public int getWordsPerPassage() {
            return wordsPerPassage;
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

        public String getCitationFormat() {
            return citationFormat;
        }
    }

    private final SectionContextBuilder packs;
    private final Contexts contexts;
    private final java.util.function.Supplier<Targets> targetSettings;
    private final java.util.function.Supplier<String> writerModel;

    /** @param targets read for every section, so a changed setting applies to the next section written */
    public EvidenceAssignments(SectionContextBuilder packs, Contexts contexts,
                               java.util.function.Supplier<Targets> targets) {
        this(packs, contexts, targets, () -> "");
    }

    /**
     * @param writerModel the model the writer slot resolves to, read for every section; it chooses the citation
     *                    format while the setting is {@code auto}
     */
    public EvidenceAssignments(SectionContextBuilder packs, Contexts contexts,
                               java.util.function.Supplier<Targets> targets,
                               java.util.function.Supplier<String> writerModel) {
        this.packs = packs;
        this.contexts = contexts;
        this.targetSettings = targets;
        this.writerModel = writerModel == null ? () -> "" : writerModel;
    }

    public SectionAssignment assignmentFor(SectionWriteRequest request) throws Exception {
        Targets targets = targetSettings.get();
        EvidencePack pack = packs.build(contexts.requestFor(request), packSettings(request.getSection(), targets));
        return assignment(pack, request, targets, contexts.writtenSections(request), writerModel.get());
    }

    /**
     * The pack settings of one section: the passage count scales with its key-point parts, the per-source cap only
     * when set (retest of #246: source breadth). The writer A/B exports its packs with these settings.
     */
    public static WriterPackSettings packSettings(com.aresstack.askai.research.outline.OutlineSection section,
                                                  Targets targets) {
        int parts = SectionAssignment.partCount(section.getBrief().getWordTarget(), targets.wordsPerCall,
                section.getBrief().getKeyPoints().size());
        return targets.pack.forParts(parts, targets.perSourceScales);
    }

    /**
     * The writer assignment from a built pack, without any store access: what the app sends the writer for this
     * section, and what the writer A/B sends it (stage 0 of the writer proposal, test parity).
     *
     * @param written     the current text of the manuscript's other written sections (repeat check)
     * @param writerModel the model the writer slot resolves to ("" when unknown), for the citation format
     */
    public static SectionAssignment assignment(EvidencePack pack, SectionWriteRequest request, Targets targets,
                                               java.util.List<String> written, String writerModel) {
        int outlineTarget = request.getSection().getBrief().getWordTarget();
        int wordTarget = wordTarget(outlineTarget, pack.getPassages().size(), targets.wordsPerPassage);
        SectionAssignment base = SectionWriterInputs.of(pack, request.getSection().getTitle(),
                request.getSection().getBrief().getPurpose(), request.getSection().getBrief().getKeyPoints(),
                wordTarget, targets.wordsPerCall, targets.answerTokens,
                targets.carry, targets.pack.getPassageCount());
        SectionAssignment.Builder assignment = base.toBuilder().writtenSections(written)
                .repeats(targets.repeats).synthesis(targets.synthesis).topUp(targets.topUp)
                .partContext(targets.partContext)
                .citationFormat(com.aresstack.askai.research.runtime.writer.CitationFormat.resolve(
                        targets.citationFormat, writerModel == null ? "" : writerModel));
        if (request.isRewrite()) {
            assignment.rewriteComment(request.getComment()).currentText(request.getCurrentText());
        }
        return assignment.build();
    }

    /**
     * The section's word target from the actual pack (live run 5: thin sections reached 62 % of a target set from
     * their material in the outline): the outline's target, at most the pack's passages times the words one passage
     * carries; never below one call's minimum. {@code wordsPerPassage} 0 keeps the outline's target.
     */
    static int wordTarget(int outlineTarget, int passages, int wordsPerPassage) {
        if (wordsPerPassage <= 0 || outlineTarget <= 0) {
            return outlineTarget;
        }
        long carried = Math.max(SectionAssignment.MIN_WORDS_PER_CALL, (long) passages * wordsPerPassage);
        return (int) Math.min(outlineTarget, carried);
    }
}
