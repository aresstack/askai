package com.aresstack.askai.localmodels;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Local installation is resolved by the neutral catalog's runtime family, never by a name heuristic:
 * encoders and rerankers are installable in C2; native generation families report a clear typed
 * "not available yet" state; UNVERIFIED and non-catalogued repos are rejected.
 */
public class LocalModelInstallResolutionTest {

    @Test
    public void encodersResolveToTheEncoderStrategy() {
        for (String repo : new String[]{"sentence-transformers/all-MiniLM-L6-v2",
                "intfloat/e5-small-v2", "intfloat/e5-base-v2", "intfloat/e5-large-v2"}) {
            LocalModelInstallResolution r = LocalModelInstallResolution.resolve(repo);
            assertEquals(repo, LocalModelInstallResolution.Kind.ENCODER, r.getKind());
            assertTrue(repo, r.isInstallable());
        }
    }

    @Test
    public void rerankerResolvesToTheRerankerStrategy() {
        LocalModelInstallResolution r =
                LocalModelInstallResolution.resolve("cross-encoder/ms-marco-MiniLM-L6-v2");
        assertEquals(LocalModelInstallResolution.Kind.RERANKER, r.getKind());
        assertTrue(r.isInstallable());
    }

    @Test
    public void runnableGenerationFamiliesAreInstallable() {
        // Catalogued RUNNABLE generation families (per the published 0.2.0 catalog) install via the
        // local runtime's generation runtime (compile + package-backed smoke).
        for (String repo : new String[]{"Qwen/Qwen2.5-Coder-0.5B-Instruct",
                "HuggingFaceTB/SmolLM2-135M-Instruct", "HuggingFaceTB/SmolLM2-360M-Instruct",
                "google-t5/t5-small", "google/flan-t5-small", "Salesforce/codet5-small",
                "Salesforce/codet5-base-multi-sum"}) {
            LocalModelInstallResolution r = LocalModelInstallResolution.resolve(repo);
            assertEquals(repo, LocalModelInstallResolution.Kind.GENERATION, r.getKind());
            assertTrue(repo, r.isInstallable());
        }
    }

    @Test
    public void unverifiedModelsAreNotRunnable() {
        // The catalog decides runnability; unverified models (L12 reranker, Gemma-3-270m-it, Phi-3-mini in
        // the 0.2.0 release) are never offered for local installation.
        for (String repo : new String[]{"cross-encoder/ms-marco-MiniLM-L12-v2",
                "google/gemma-3-270m-it", "microsoft/Phi-3-mini-4k-instruct-onnx"}) {
            LocalModelInstallResolution r = LocalModelInstallResolution.resolve(repo);
            assertEquals(repo, LocalModelInstallResolution.Kind.NOT_RUNNABLE, r.getKind());
            assertFalse(repo, r.isInstallable());
        }
    }

    @Test
    public void unknownRepositoryIsNotInCatalog() {
        LocalModelInstallResolution r = LocalModelInstallResolution.resolve("some/unknown-model");
        assertEquals(LocalModelInstallResolution.Kind.NOT_IN_CATALOG, r.getKind());
        assertFalse(r.isInstallable());
    }
}
