package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationCodec;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationValidationResult;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The host active-session registry re-publishes the inference descriptor to every RUNNING session's
 * directory when the central main model changes, and only to those still registered. Uses the inference
 * provider's {@code EndpointSources} seam so no sidecar is started.
 */
public class LocalActiveResearchSessionRegistryTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static LocalInferenceConfigurationSnapshotProvider remoteProvider(final String model) {
        return new LocalInferenceConfigurationSnapshotProvider(
                new LocalInferenceConfigurationSnapshotProvider.EndpointSources() {
                    public String modelName() {
                        return model;
                    }

                    public String localRuntimeBaseUrl() {
                        return "http://127.0.0.1:51000";
                    }

                    public String remoteOllamaBaseUrl() {
                        return "http://remote:11434";
                    }
                });
    }

    private static InferenceConfigurationValidationResult read(File dir) throws IOException {
        return InferenceConfigurationCodec.parse(new String(
                Files.readAllBytes(new File(dir, "inference-config.json").toPath()),
                Charset.forName("UTF-8")));
    }

    @Test
    public void refreshInferenceRewritesEveryRegisteredSession() throws Exception {
        LocalActiveResearchSessionRegistry registry =
                new LocalActiveResearchSessionRegistry(remoteProvider("qwen2.5:latest"), null, null);
        File a = folder.newFolder("a");
        File b = folder.newFolder("b");
        registry.register("s-a", a);
        registry.register("s-b", b);
        assertEquals(2, registry.activeSessionCount());

        registry.refreshInference();

        InferenceConfigurationValidationResult ra = read(a);
        InferenceConfigurationValidationResult rb = read(b);
        assertTrue(ra.describe(), ra.valid);
        assertTrue(rb.describe(), rb.valid);
        assertEquals("qwen2.5:latest", ra.document.descriptor.model);
        assertEquals("http://remote:11434", rb.document.descriptor.baseUrl);
    }

    @Test
    public void anUnregisteredSessionIsNoLongerRefreshed() throws Exception {
        LocalActiveResearchSessionRegistry registry =
                new LocalActiveResearchSessionRegistry(remoteProvider("m:latest"), null, null);
        File a = folder.newFolder("a");
        registry.register("s-a", a);
        registry.refreshInference();
        long firstRevision = read(a).document.configurationRevision;

        registry.unregister("s-a");
        assertEquals(0, registry.activeSessionCount());
        registry.refreshInference(); // must NOT touch the torn-down session

        assertEquals("the descriptor of an unregistered session is not rewritten",
                firstRevision, read(a).document.configurationRevision);
    }

    @Test
    public void republishUsesARisingConfigurationRevision() throws Exception {
        LocalActiveResearchSessionRegistry registry =
                new LocalActiveResearchSessionRegistry(remoteProvider("m:latest"), null, null);
        File a = folder.newFolder("a");
        registry.register("s-a", a);
        registry.refreshInference();
        long first = read(a).document.configurationRevision;
        registry.refreshInference();
        long second = read(a).document.configurationRevision;
        assertTrue("a re-published descriptor is recognisably newer (" + first + " -> " + second + ")",
                second > first);
    }

    @Test
    public void refreshRerankerWithoutAProviderIsANoOp() {
        LocalActiveResearchSessionRegistry registry =
                new LocalActiveResearchSessionRegistry(null, null, null);
        registry.register("s", folder.getRoot());
        registry.refreshReranker(); // must not throw
        assertFalse(registry.activeSessionCount() == 0);
    }
}
