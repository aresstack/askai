package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.runtime.writer.SectionAssignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns the evidence pack of PR 27 into the input of the section writer of PR 28 ({@code SectionWriter}). The word
 * target and the answer budget per call come from the writer settings of the Document row (D8).
 */
public final class SectionWriterInputs {

    private SectionWriterInputs() {
    }

    /**
     * @param pack          the section's evidence pack
     * @param title         the outline title of the section
     * @param brief         the section brief
     * @param keyPoints     the key points; a section above {@code wordsPerCall} is split by them
     * @param sectionWords  words the whole section aims for (0 = one call's target)
     * @param wordsPerCall  word target per call (about 1,100 or 450)
     * @param answerTokens  answer budget per call (3,072 / 1,200, plus reasoning on gpt-oss)
     */
    public static SectionAssignment of(EvidencePack pack, String title, String brief, List<String> keyPoints,
                                       int sectionWords, int wordsPerCall, int answerTokens) {
        List<com.aresstack.askai.research.runtime.writer.EvidencePassage> passages =
                new ArrayList<com.aresstack.askai.research.runtime.writer.EvidencePassage>();
        for (EvidencePassage p : pack.getPassages()) {
            passages.add(new com.aresstack.askai.research.runtime.writer.EvidencePassage(p.getNumber(),
                    p.getSourceId(), p.getSourceChecksum(), p.getPassageId(), p.getText()));
        }
        return SectionAssignment.builder(pack.getSectionId()).title(title).brief(brief).keyPoints(keyPoints)
                .passages(passages).synopsis(pack.getSynopsis()).previousDigest(pack.getPreviousSectionDigest())
                .glossary(pack.getGlossary()).bookLanguage(pack.getBookLanguage()).sectionWords(sectionWords)
                .wordsPerCall(wordsPerCall).answerTokens(answerTokens).build();
    }
}
