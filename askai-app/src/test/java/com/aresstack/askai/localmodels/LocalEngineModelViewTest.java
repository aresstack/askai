package com.aresstack.askai.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ModelCapability;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The local-engine card presentation is manifest-backed and family-aware; the "pending" state is driven by
 * the sidecar-reported generation linkage (a boolean), NOT a hardcoded family list; and it is fail-closed.
 */
public class LocalEngineModelViewTest {

    private static InstalledModelManifest manifest(String repo) {
        LocalRuntimeModelDescriptor descriptor = LocalModelCatalog.findByRepositoryId(repo);
        return InstalledModelManifest.forInstall(descriptor, "abc123def4567890", 1700000000000L);
    }

    @Test
    public void repositoryIsExtractedFromTheVirtualName() {
        assertEquals("cross-encoder/ms-marco-MiniLM-L6-v2",
                LocalEngineModelView.repositoryOf("local/cross-encoder/ms-marco-MiniLM-L6-v2:latest"));
        assertEquals("", LocalEngineModelView.repositoryOf("llama3:latest"));
    }

    @Test
    public void rerankerCardIsManifestBackedNotAHardcodedString() {
        String line = LocalEngineModelView.installedDetailLine(
                manifest("cross-encoder/ms-marco-MiniLM-L6-v2"), false);
        assertTrue(line, line.contains("Cross-encoder reranker"));
        assertTrue(line, line.contains("Capability: rerank"));
        assertTrue(line, line.contains("Backend: cpu, directml"));
        assertTrue(line, line.contains("Package: reranker.wdmlpack"));
        assertTrue(line, line.contains("Revision: abc123def456"));
        assertTrue(line, line.contains("Installed: "));
    }

    @Test
    public void encoderCardShowsEmbeddingCapability() {
        String line = LocalEngineModelView.installedDetailLine(manifest("intfloat/e5-base-v2"), false);
        assertTrue(line, line.contains("Capability: embedding"));
        assertTrue(line, line.contains("Package: encoder.wdmlpack"));
        assertTrue(LocalEngineModelView.hasCapability(
                "local/intfloat/e5-base-v2:latest", ModelCapability.EMBEDDING));
        assertFalse(LocalEngineModelView.hasCapability(
                "local/intfloat/e5-base-v2:latest", ModelCapability.RERANK));
    }

    @Test
    public void generationPendingIsDrivenByLinkageNotAFamilyList() {
        InstalledModelManifest qwen = manifest("Qwen/Qwen2.5-Coder-0.5B-Instruct");
        // NOT_LINKED -> pending; no backend success claim.
        assertTrue(LocalEngineModelView.isRuntimePending(qwen, false));
        String pending = LocalEngineModelView.installedDetailLine(qwen, false);
        assertTrue(pending, pending.contains("Runtime integration pending"));
        assertFalse(pending, pending.contains("Backend:"));
        // LINKED -> no longer pending; the same model now reads as runnable.
        assertFalse(LocalEngineModelView.isRuntimePending(qwen, true));
        String linked = LocalEngineModelView.installedDetailLine(qwen, true);
        assertFalse(linked, linked.contains("pending"));
        assertTrue(linked, linked.contains("Capability: completion, chat"));
        // An encoder is never pending regardless of linkage.
        assertFalse(LocalEngineModelView.isRuntimePending(manifest("intfloat/e5-small-v2"), false));
    }

    @Test
    public void runningLineShowsFamilyAndCapabilityButNeverClaimsAnActiveBackend() {
        String line = LocalEngineModelView.runningDetailLine(
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest");
        assertTrue(line, line.contains("Cross-encoder reranker"));
        assertTrue(line, line.contains("Capability: rerank"));
        assertFalse("running must not claim a supported/active backend", line.contains("Backend:"));
    }

    @Test
    public void failClosedForUnknownOrMissingMetadata() {
        assertEquals(LocalEngineModelView.METADATA_UNAVAILABLE,
                LocalEngineModelView.installedDetailLine(null, false));
        assertEquals(LocalEngineModelView.METADATA_UNAVAILABLE,
                LocalEngineModelView.runningDetailLine("local/foo/bar:latest"));
        assertFalse(LocalEngineModelView.hasLocalMetadata("local/foo/bar:latest"));
        assertTrue(LocalEngineModelView.hasLocalMetadata(
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest"));
    }
}
