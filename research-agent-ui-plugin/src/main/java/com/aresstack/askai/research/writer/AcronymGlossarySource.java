package com.aresstack.askai.research.writer;

import com.aresstack.askai.research.knowledge.processing.acronym.SourceAcronymService;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Acronym lines for an evidence pack's glossary block (plan PR 38): acronyms of the pack's passages with the long
 * forms the admitted sources give, within the remaining glossary budget.
 */
public interface AcronymGlossarySource {

    List<String> lines(List<String> passageTexts, Collection<String> admittedSourceIds, int tokenBudget);

    /** No acronym lines. */
    AcronymGlossarySource NONE = new AcronymGlossarySource() {
        @Override
        public List<String> lines(List<String> passageTexts, Collection<String> admittedSourceIds,
                                  int tokenBudget) {
            return Collections.emptyList();
        }
    };

    /** The stored acronym findings of the sources. */
    static AcronymGlossarySource of(final SourceAcronymService service) {
        return new AcronymGlossarySource() {
            @Override
            public List<String> lines(List<String> passageTexts, Collection<String> admittedSourceIds,
                                      int tokenBudget) {
                return service.glossaryLines(passageTexts, admittedSourceIds, tokenBudget);
            }
        };
    }
}
