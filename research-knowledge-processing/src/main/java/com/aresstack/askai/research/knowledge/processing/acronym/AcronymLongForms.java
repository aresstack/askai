package com.aresstack.askai.research.knowledge.processing.acronym;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer for the assembler (PR 30) per acronym: the long forms eligible sources state, each with its source and
 * spans, and whether they conflict (more than one distinct long form, compared case- and whitespace-insensitively,
 * soft hyphens and enclosing quotes left out: {@link AcronymForms#normalizeLongForm}).
 */
public final class AcronymLongForms {

    /** One source stating one long form. */
    public static final class Statement {
        private final String sourceId;
        private final SourceAcronymRecord.LongForm longForm;

        public Statement(String sourceId, SourceAcronymRecord.LongForm longForm) {
            this.sourceId = sourceId;
            this.longForm = longForm;
        }

        public String sourceId() {
            return sourceId;
        }

        public SourceAcronymRecord.LongForm longForm() {
            return longForm;
        }
    }

    private final String acronym;
    private final List<Statement> statements;

    public AcronymLongForms(String acronym, List<Statement> statements) {
        this.acronym = acronym;
        this.statements = Collections.unmodifiableList(new ArrayList<Statement>(statements));
    }

    public String acronym() {
        return acronym;
    }

    /** Every statement in source order; empty when the eligible sources use the acronym without a long form. */
    public List<Statement> statements() {
        return statements;
    }

    /** The distinct long forms in first-seen order. */
    public List<String> distinctLongForms() {
        Map<String, String> distinct = new LinkedHashMap<String, String>();
        for (Statement statement : statements) {
            String key = AcronymForms.normalizeLongForm(statement.longForm().longForm());
            if (!distinct.containsKey(key)) {
                distinct.put(key, AcronymForms.cleanLongForm(statement.longForm().longForm()));
            }
        }
        return new ArrayList<String>(distinct.values());
    }

    public boolean hasLongForm() {
        return !statements.isEmpty();
    }

    public boolean conflicting() {
        return distinctLongForms().size() > 1;
    }
}
