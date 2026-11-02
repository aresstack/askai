package com.aresstack.askai.service;

import com.aresstack.askai.client.OllamaModelDetails;
import com.aresstack.askai.client.OllamaModelInfoView;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Post-install verification only trusts {@code /api/show}: it confirms required capabilities against
 * what Ollama actually reports, treats a missing capabilities field as UNKNOWN (never "none"), maps a
 * failed probe to FAILED, and never caches.
 */
public class InstalledModelVerificationServiceTest {

    /** Scripted {@link ModelInfoGateway}: returns fixed capabilities or throws, and counts calls. */
    private static final class FakeGateway implements ModelInfoGateway {
        private final List<String> capabilities;
        private final Exception failure;
        int calls;

        static FakeGateway reporting(List<String> capabilities) {
            return new FakeGateway(capabilities, null);
        }

        static FakeGateway failing(Exception failure) {
            return new FakeGateway(null, failure);
        }

        private FakeGateway(List<String> capabilities, Exception failure) {
            this.capabilities = capabilities;
            this.failure = failure;
        }

        public OllamaModelInfoView getModelInfo(String modelName) throws Exception {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return new OllamaModelInfoView(new OllamaModelDetails("gguf", "llama", "7B", "Q4_K_M"),
                    "", "", "", "", capabilities);
        }
    }

    private static InstalledModelVerificationService service(FakeGateway gateway) {
        return new InstalledModelVerificationService(gateway);
    }

    @Test
    public void pullSucceededAndShowSucceeded_generalInstallIsVerified() {
        VerificationResult result = service(FakeGateway.reporting(Arrays.asList("completion", "vision")))
                .verify("llama3.2-vision:latest");
        assertEquals(VerificationStatus.VERIFIED, result.getStatus());
        assertEquals(Arrays.asList("completion", "vision"), result.getReportedCapabilities());
        assertTrue(result.getMissingRequired().isEmpty());
    }

    @Test
    public void ggufCreateSucceededAndShowSucceeded_reportsExactCapabilities() {
        VerificationResult result = service(FakeGateway.reporting(Arrays.asList("completion")))
                .verify("my-import:latest", Collections.<String>emptyList());
        assertEquals(VerificationStatus.VERIFIED, result.getStatus());
        assertEquals(Collections.singletonList("completion"), result.getReportedCapabilities());
    }

    @Test
    public void requiredAudioPresent_isVerified() {
        VerificationResult result = service(FakeGateway.reporting(Arrays.asList("completion", "audio")))
                .verify("voxtral:latest", Collections.singletonList("audio"));
        assertEquals(VerificationStatus.VERIFIED, result.getStatus());
        assertTrue(result.isRequiredSatisfied());
        assertEquals(Collections.singletonList("audio"), result.getConfirmedRequired());
        assertTrue(result.getMissingRequired().isEmpty());
    }

    @Test
    public void requiredAudioMissing_isMissingRequired() {
        VerificationResult result = service(FakeGateway.reporting(Arrays.asList("completion", "vision")))
                .verify("llava:latest", Collections.singletonList("audio"));
        assertEquals(VerificationStatus.MISSING_REQUIRED, result.getStatus());
        assertFalse(result.isRequiredSatisfied());
        assertEquals(Collections.singletonList("audio"), result.getMissingRequired());
    }

    @Test
    public void emptyCapabilities_isUnknownNotEmpty() {
        VerificationResult result = service(FakeGateway.reporting(Collections.<String>emptyList()))
                .verify("old-model:latest", Collections.singletonList("audio"));
        assertEquals(VerificationStatus.UNKNOWN, result.getStatus());
        assertFalse(result.isRequiredSatisfied());
        // The required capability is reported as unconfirmed, so audio must not be enabled.
        assertEquals(Collections.singletonList("audio"), result.getMissingRequired());
    }

    @Test
    public void nullCapabilities_isUnknown() {
        VerificationResult result = service(FakeGateway.reporting(null)).verify("weird:latest");
        assertEquals(VerificationStatus.UNKNOWN, result.getStatus());
    }

    @Test
    public void showFailsAfterInstall_isFailed() {
        VerificationResult result = service(FakeGateway.failing(new RuntimeException("connection refused")))
                .verify("model:latest", Collections.singletonList("audio"));
        assertEquals(VerificationStatus.FAILED, result.getStatus());
        assertTrue(result.getErrorMessage(), result.getErrorMessage().contains("connection refused"));
    }

    @Test
    public void doesNotCache_reVerifiesEachCall() {
        FakeGateway gateway = FakeGateway.reporting(Arrays.asList("completion", "audio"));
        InstalledModelVerificationService verifier = service(gateway);
        verifier.verify("m:latest", Collections.singletonList("audio"));
        verifier.verify("m:latest", Collections.singletonList("audio"));
        // A capability list can change during pull/create, so /api/show is re-queried every time.
        assertEquals(2, gateway.calls);
    }

    @Test
    public void capabilityMatchingIsCaseInsensitive() {
        VerificationResult result = service(FakeGateway.reporting(Arrays.asList("Completion", "AUDIO")))
                .verify("m:latest", Collections.singletonList("Audio"));
        assertEquals(VerificationStatus.VERIFIED, result.getStatus());
    }
}
