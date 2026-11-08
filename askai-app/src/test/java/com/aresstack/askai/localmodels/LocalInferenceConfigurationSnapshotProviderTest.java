package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.inference.InferenceConfigurationCodec;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationException;
import com.aresstack.askai.agent.model.inference.InferenceConfigurationSnapshot;
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
import static org.junit.Assert.fail;

/**
 * The host inference-snapshot provider resolves the central main model to its serving endpoint and writes an
 * atomic {@code inference-config.json}. The local/remote routing is asserted through the {@code EndpointSources}
 * seam so no sidecar is spawned: a {@code local/...} model resolves to the runtime base URL, everything else
 * to the configured remote Ollama base URL.
 */
public class LocalInferenceConfigurationSnapshotProviderTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    /** A recording endpoint seam: fixed model + base URLs, and it records which source was consulted. */
    private static final class RecordingSources
            implements LocalInferenceConfigurationSnapshotProvider.EndpointSources {
        private final String model;
        boolean localAsked;
        boolean remoteAsked;

        RecordingSources(String model) {
            this.model = model;
        }

        public String modelName() {
            return model;
        }

        public String localRuntimeBaseUrl() {
            localAsked = true;
            return "http://127.0.0.1:51000";
        }

        public String remoteOllamaBaseUrl() {
            remoteAsked = true;
            return "http://remote-ollama:11434";
        }
    }

    private static InferenceConfigurationValidationResult readBack(File file) throws IOException {
        return InferenceConfigurationCodec.parse(
                new String(Files.readAllBytes(file.toPath()), Charset.forName("UTF-8")));
    }

    @Test
    public void aRemoteOllamaModelResolvesToTheConfiguredRemoteBaseUrl() throws Exception {
        RecordingSources sources = new RecordingSources("qwen2.5:latest"); // not local/
        LocalInferenceConfigurationSnapshotProvider provider =
                new LocalInferenceConfigurationSnapshotProvider(sources);
        File dir = folder.newFolder("session");
        InferenceConfigurationSnapshot snapshot = provider.prepareForSession("s", dir);

        assertTrue("remote base URL consulted", sources.remoteAsked);
        assertFalse("the local runtime is NOT started for a remote model", sources.localAsked);
        assertEquals("qwen2.5:latest", snapshot.getModel());
        assertEquals("http://remote-ollama:11434", snapshot.getDocument().descriptor.baseUrl);
        assertEquals("/api/chat", snapshot.getDocument().descriptor.chatPath);
        // The written file exists at inference-config.json and decodes back to the same endpoint.
        assertEquals(new File(dir, "inference-config.json").getAbsolutePath(), snapshot.getAbsolutePath());
        InferenceConfigurationValidationResult onDisk = readBack(snapshot.getSnapshotFile());
        assertTrue(onDisk.describe(), onDisk.valid);
        assertEquals("http://remote-ollama:11434", onDisk.document.descriptor.baseUrl);
    }

    @Test
    public void aLocalDirectmlModelResolvesToTheRuntimeSidecarBaseUrl() throws Exception {
        RecordingSources sources = new RecordingSources("local/qwen3:latest");
        LocalInferenceConfigurationSnapshotProvider provider =
                new LocalInferenceConfigurationSnapshotProvider(sources);
        InferenceConfigurationSnapshot snapshot = provider.prepareForSession("s", folder.newFolder("session"));

        assertTrue("the local runtime base URL is consulted for a local/ model", sources.localAsked);
        assertFalse("the remote Ollama is NOT consulted for a local model", sources.remoteAsked);
        assertEquals("local/qwen3:latest", snapshot.getModel());
        assertEquals("http://127.0.0.1:51000", snapshot.getDocument().descriptor.baseUrl);
    }

    @Test
    public void failsVisiblyWithoutAMainModel() throws Exception {
        LocalInferenceConfigurationSnapshotProvider provider =
                new LocalInferenceConfigurationSnapshotProvider(new RecordingSources(""));
        try {
            provider.prepareForSession("s", folder.newFolder("session"));
            fail("expected a visible failure with no default research model");
        } catch (InferenceConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("no default research model"));
        }
    }

    @Test
    public void aLocalModelWhoseRuntimeWillNotStartFailsVisibly() throws Exception {
        LocalInferenceConfigurationSnapshotProvider.EndpointSources broken =
                new LocalInferenceConfigurationSnapshotProvider.EndpointSources() {
                    public String modelName() {
                        return "local/qwen:latest";
                    }

                    public String localRuntimeBaseUrl() throws IOException {
                        throw new IOException("sidecar failed to start");
                    }

                    public String remoteOllamaBaseUrl() {
                        return "";
                    }
                };
        try {
            new LocalInferenceConfigurationSnapshotProvider(broken)
                    .prepareForSession("s", folder.newFolder("session"));
            fail("expected a visible failure when the local runtime will not start");
        } catch (InferenceConfigurationException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("could not be started"));
        }
    }
}
