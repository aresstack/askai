package com.aresstack.askai.research.outline;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Phase 3c: what the last "Generate outline" found, stored with the outline: the topic-analysis snapshot it was built
 * on, the concept requirements without (enough) evidence, the quality check's findings, notes on how the plan came
 * about (fallbacks, safe fixes) and the research topics kept out of the outline because the search fence reads them
 * as out of scope. The editor shows it; it never changes the chapters. Immutable.
 */
public final class OutlineReport {

    public static final OutlineReport EMPTY = new OutlineReport("", false, null, null, null, null);

    /** A concept requirement without enough evidence. */
    public static final class Gap {
        private final String requirement;
        private final String path;
        private final String status;
        private final String reason;

        public Gap(String requirement, String path, String status, String reason) {
            this.requirement = clean(requirement);
            this.path = clean(path);
            this.status = clean(status);
            this.reason = clean(reason);
        }

        public String getRequirement() {
            return requirement;
        }

        public String getPath() {
            return path;
        }

        /** The coverage status of the analysis (GAP, THIN or COVERED). */
        public String getStatus() {
            return status;
        }

        public String getReason() {
            return reason;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Gap)) {
                return false;
            }
            Gap that = (Gap) other;
            return requirement.equals(that.requirement) && path.equals(that.path) && status.equals(that.status)
                    && reason.equals(that.reason);
        }

        @Override
        public int hashCode() {
            return ((requirement.hashCode() * 31 + path.hashCode()) * 31 + status.hashCode()) * 31
                    + reason.hashCode();
        }
    }

    /** One finding of the quality check. */
    public static final class Finding {
        private final boolean blocking;
        private final String code;
        private final String message;

        public Finding(boolean blocking, String code, String message) {
            this.blocking = blocking;
            this.code = clean(code);
            this.message = clean(message);
        }

        /** Whether the check counts it as a problem the revision rounds could not solve (else a warning). */
        public boolean isBlocking() {
            return blocking;
        }

        public String getCode() {
            return code;
        }

        public String getMessage() {
            return message;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Finding)) {
                return false;
            }
            Finding that = (Finding) other;
            return blocking == that.blocking && code.equals(that.code) && message.equals(that.message);
        }

        @Override
        public int hashCode() {
            return ((blocking ? 1 : 0) * 31 + code.hashCode()) * 31 + message.hashCode();
        }
    }

    private final String analysisSnapshotId;
    private final boolean fromAnalysisOnly;
    private final List<Gap> gaps;
    private final List<Finding> findings;
    private final List<String> notes;
    private final List<String> outOfScopeTopics;

    public OutlineReport(String analysisSnapshotId, boolean fromAnalysisOnly, List<Gap> gaps, List<Finding> findings,
                         List<String> notes, List<String> outOfScopeTopics) {
        this.analysisSnapshotId = clean(analysisSnapshotId);
        this.fromAnalysisOnly = fromAnalysisOnly;
        this.gaps = copy(gaps);
        this.findings = copy(findings);
        this.notes = copy(notes);
        this.outOfScopeTopics = copy(outOfScopeTopics);
    }

    private static <T> List<T> copy(List<T> values) {
        return Collections.unmodifiableList(new ArrayList<T>(values == null ? Collections.<T>emptyList() : values));
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    /** The topic-analysis snapshot the outline was built on; "" for an outline that was never generated. */
    public String getAnalysisSnapshotId() {
        return analysisSnapshotId;
    }

    /** Whether the chapter plan came from the analysis alone (no model, or no valid model answer). */
    public boolean isFromAnalysisOnly() {
        return fromAnalysisOnly;
    }

    public List<Gap> getGaps() {
        return gaps;
    }

    public List<Finding> getFindings() {
        return findings;
    }

    public List<String> getNotes() {
        return notes;
    }

    public List<String> getOutOfScopeTopics() {
        return outOfScopeTopics;
    }

    public boolean isEmpty() {
        return analysisSnapshotId.isEmpty() && gaps.isEmpty() && findings.isEmpty() && notes.isEmpty()
                && outOfScopeTopics.isEmpty();
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof OutlineReport)) {
            return false;
        }
        OutlineReport that = (OutlineReport) other;
        return analysisSnapshotId.equals(that.analysisSnapshotId) && fromAnalysisOnly == that.fromAnalysisOnly
                && gaps.equals(that.gaps) && findings.equals(that.findings) && notes.equals(that.notes)
                && outOfScopeTopics.equals(that.outOfScopeTopics);
    }

    @Override
    public int hashCode() {
        int hash = analysisSnapshotId.hashCode();
        hash = hash * 31 + (fromAnalysisOnly ? 1 : 0);
        hash = hash * 31 + gaps.hashCode();
        hash = hash * 31 + findings.hashCode();
        hash = hash * 31 + notes.hashCode();
        return hash * 31 + outOfScopeTopics.hashCode();
    }
}
