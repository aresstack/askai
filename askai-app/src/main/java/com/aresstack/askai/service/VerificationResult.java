package com.aresstack.askai.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The result of a post-install verification: what {@code /api/show} actually reported for the newly
 * installed model, which of the requested required capabilities were confirmed, which are missing,
 * and an overall {@link VerificationStatus}. Immutable, UI-independent.
 *
 * <p>Only {@link #getReportedCapabilities()} describes the installed model; the expectations that led
 * to the install (from ollama.com / Hugging Face) are never merged into it.</p>
 */
public final class VerificationResult {

    private final String modelName;
    private final List<String> reportedCapabilities;
    private final List<String> confirmedRequired;
    private final List<String> missingRequired;
    private final VerificationStatus status;
    private final String errorMessage;

    public VerificationResult(String modelName, List<String> reportedCapabilities,
                              List<String> confirmedRequired, List<String> missingRequired,
                              VerificationStatus status, String errorMessage) {
        this.modelName = modelName == null ? "" : modelName;
        this.reportedCapabilities = immutable(reportedCapabilities);
        this.confirmedRequired = immutable(confirmedRequired);
        this.missingRequired = immutable(missingRequired);
        this.status = status == null ? VerificationStatus.UNKNOWN : status;
        this.errorMessage = errorMessage == null ? "" : errorMessage;
    }

    private static List<String> immutable(List<String> values) {
        return values == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getModelName() {
        return modelName;
    }

    /** @return the exact capabilities {@code /api/show} reported for the installed model (may be empty). */
    public List<String> getReportedCapabilities() {
        return reportedCapabilities;
    }

    public List<String> getConfirmedRequired() {
        return confirmedRequired;
    }

    public List<String> getMissingRequired() {
        return missingRequired;
    }

    public VerificationStatus getStatus() {
        return status;
    }

    /** @return the failure detail when {@link #getStatus()} is {@link VerificationStatus#FAILED}, else "". */
    public String getErrorMessage() {
        return errorMessage;
    }

    /** @return true only when every required capability was confirmed present by {@code /api/show}. */
    public boolean isRequiredSatisfied() {
        return status == VerificationStatus.VERIFIED;
    }

    /** @return a short human-readable summary of the reported capabilities (or a placeholder). */
    public String describeReported() {
        return reportedCapabilities.isEmpty() ? "none reported" : join(reportedCapabilities);
    }

    private static String join(List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(values.get(i));
        }
        return builder.toString();
    }
}
