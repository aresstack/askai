package com.aresstack.askai.research.outline.synthesis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Phase 3b: a model answer the contract refused, with the error list for the repair message. */
final class PlanAnswerException extends Exception {

    private final List<String> errors;

    PlanAnswerException(String error) {
        this(Collections.singletonList(error));
    }

    PlanAnswerException(List<String> errors) {
        super(String.join("; ", errors));
        this.errors = Collections.unmodifiableList(new ArrayList<String>(errors));
    }

    List<String> errors() {
        return errors;
    }
}
