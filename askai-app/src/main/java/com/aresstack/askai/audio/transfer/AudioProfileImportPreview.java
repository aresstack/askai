package com.aresstack.askai.audio.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The complete, non-destructive plan for an import: what will be added, what changes (new IDs, renamed on
 * name collision), what cannot be imported and why, plus general warnings (migrations, unknown block types,
 * unavailable optional backends). Nothing is persisted until the user confirms and {@code commit} runs.
 */
public final class AudioProfileImportPreview {

    private final int schemaVersion;
    private final boolean migrated;
    private final List<PlannedProfileImport> importable;
    private final List<RejectedProfileImport> rejected;
    private final List<String> warnings;

    public AudioProfileImportPreview(int schemaVersion, boolean migrated,
                                     List<PlannedProfileImport> importable,
                                     List<RejectedProfileImport> rejected, List<String> warnings) {
        this.schemaVersion = schemaVersion;
        this.migrated = migrated;
        this.importable = unmodifiable(importable);
        this.rejected = unmodifiable(rejected);
        this.warnings = unmodifiable(warnings);
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public boolean isMigrated() {
        return migrated;
    }

    public List<PlannedProfileImport> getImportable() {
        return importable;
    }

    public List<RejectedProfileImport> getRejected() {
        return rejected;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public int getFoundCount() {
        return importable.size() + rejected.size();
    }

    public int getValidCount() {
        return importable.size();
    }

    public int getInvalidCount() {
        return rejected.size();
    }

    public int getNewIdCount() {
        int count = 0;
        for (PlannedProfileImport planned : importable) {
            if (planned.isIdReassigned()) {
                count++;
            }
        }
        return count;
    }

    public int getNameCollisionCount() {
        int count = 0;
        for (PlannedProfileImport planned : importable) {
            if (planned.isNameReassigned()) {
                count++;
            }
        }
        return count;
    }

    public boolean hasImportableProfiles() {
        return !importable.isEmpty();
    }

    private static <T> List<T> unmodifiable(List<T> source) {
        return Collections.unmodifiableList(new ArrayList<T>(source == null ? new ArrayList<T>() : source));
    }
}
