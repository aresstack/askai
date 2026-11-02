package com.aresstack.askai.service;

import com.aresstack.askai.client.OllamaModelInfoView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Verifies what a freshly installed model can <em>actually</em> do by asking Ollama's {@code /api/show}
 * ({@link ModelInfoGateway}) right after a successful {@code /api/pull} or {@code /api/create}. Ollama
 * derives capabilities itself from the installed manifest, GGUF metadata, projector layers and
 * template; AskAI accepts only that result and never copies the modality hints from ollama.com or
 * Hugging Face onto the installed model.
 *
 * <p>The service holds no state and no cache: every {@link #verify} call re-queries {@code /api/show}
 * for the exact installed name, so a capability list that changed during pull/create is never served
 * stale. Kept out of the Swing layer so it can be reused and unit-tested.</p>
 */
public final class InstalledModelVerificationService {

    private final ModelInfoGateway gateway;

    public InstalledModelVerificationService(ModelInfoGateway gateway) {
        this.gateway = gateway;
    }

    /** Verifies without any hard requirement (general install: deviations are informational). */
    public VerificationResult verify(String modelName) {
        return verify(modelName, null);
    }

    /**
     * @param modelName            the exact installed model name to query via {@code /api/show}
     * @param requiredCapabilities capabilities that MUST be present for this install to count as a
     *                             successful provisioning (e.g. {@code ["audio"]} / {@code ["vision"]});
     *                             {@code null}/empty for a general install
     * @return the reported capabilities, which required ones were confirmed/missing, and a status
     */
    public VerificationResult verify(String modelName, List<String> requiredCapabilities) {
        List<String> required = normalize(requiredCapabilities);

        OllamaModelInfoView info;
        try {
            info = gateway.getModelInfo(modelName);
        } catch (Exception ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            return new VerificationResult(modelName, empty(), empty(), required,
                    VerificationStatus.FAILED, message);
        }

        List<String> reported = normalize(info == null ? null : info.getCapabilities());
        if (reported.isEmpty()) {
            // No capabilities field (empty, or an older Ollama): UNKNOWN, not "no capabilities".
            // Nothing may be enabled on this basis, so every required capability counts as unconfirmed.
            return new VerificationResult(modelName, reported, empty(), required,
                    VerificationStatus.UNKNOWN, "");
        }

        List<String> confirmed = new ArrayList<String>();
        List<String> missing = new ArrayList<String>();
        for (int i = 0; i < required.size(); i++) {
            String capability = required.get(i);
            if (reported.contains(capability)) {
                confirmed.add(capability);
            } else {
                missing.add(capability);
            }
        }
        VerificationStatus status = missing.isEmpty()
                ? VerificationStatus.VERIFIED
                : VerificationStatus.MISSING_REQUIRED;
        return new VerificationResult(modelName, reported, confirmed, missing, status, "");
    }

    /** Lower-cases, trims, drops blanks and de-duplicates while preserving first-seen order. */
    private static List<String> normalize(List<String> values) {
        List<String> result = new ArrayList<String>();
        if (values == null) {
            return result;
        }
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (value == null) {
                continue;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            if (normalized.length() > 0 && !result.contains(normalized)) {
                result.add(normalized);
            }
        }
        return result;
    }

    private static List<String> empty() {
        return new ArrayList<String>();
    }
}
