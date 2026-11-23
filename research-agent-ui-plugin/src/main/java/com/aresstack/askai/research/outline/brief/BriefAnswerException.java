package com.aresstack.askai.research.outline.brief;

import java.util.Collections;
import java.util.List;

/** A label or brief answer the code refuses, with every reason (sent back once as the error list). */
final class BriefAnswerException extends Exception {

    private final List<String> errors;

    BriefAnswerException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = Collections.unmodifiableList(errors);
    }

    BriefAnswerException(String error) {
        this(Collections.singletonList(error));
    }

    List<String> errors() {
        return errors;
    }
}
