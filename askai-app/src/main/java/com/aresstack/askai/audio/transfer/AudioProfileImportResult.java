package com.aresstack.askai.audio.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The outcome of committing an import: which profiles were persisted and which failed (never silent). */
public final class AudioProfileImportResult {

    private final List<String> importedNames;
    private final List<String> importedIds;
    private final List<String> failures;

    public AudioProfileImportResult(List<String> importedNames, List<String> importedIds,
                                    List<String> failures) {
        this.importedNames = unmodifiable(importedNames);
        this.importedIds = unmodifiable(importedIds);
        this.failures = unmodifiable(failures);
    }

    public List<String> getImportedNames() {
        return importedNames;
    }

    public List<String> getImportedIds() {
        return importedIds;
    }

    public List<String> getFailures() {
        return failures;
    }

    public int getImportedCount() {
        return importedNames.size();
    }

    public boolean hasFailures() {
        return !failures.isEmpty();
    }

    private static List<String> unmodifiable(List<String> source) {
        return Collections.unmodifiableList(
                new ArrayList<String>(source == null ? new ArrayList<String>() : source));
    }
}
