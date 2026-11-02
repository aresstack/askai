package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationException;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshot;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Resolving the selected NLP model: success, not-configured, not-installed, checksum mismatch. */
public class LocalNlpConfigurationSnapshotProviderTest {

    private static File tempRoot() throws IOException {
        return Files.createTempDirectory("askai-nlp-resolve").toFile();
    }

    private static LocalNlpConfigurationSnapshotProvider.NlpSelectionStore select(final String modelId) {
        return new LocalNlpConfigurationSnapshotProvider.NlpSelectionStore() {
            public String selectedModelId(NlpCapability capability, String languageCode) {
                return modelId;
            }
        };
    }

    @Test
    public void resolvesTheSelectedInstalledModel() throws Exception {
        File root = tempRoot();
        LocalNlpModelStoreTest.install(root, "apache-opennlp/sentence-de", "de", new byte[]{1, 2, 3}, "");
        LocalNlpConfigurationSnapshotProvider provider = new LocalNlpConfigurationSnapshotProvider(
                new LocalNlpModelStore(root), select("apache-opennlp/sentence-de"));

        NlpConfigurationSnapshot snapshot = provider.resolve(NlpCapability.SENTENCE_DETECTION, "de");
        assertEquals("apache-opennlp/sentence-de", snapshot.getDescriptor().getModelId());
        assertTrue(new File(snapshot.getDescriptor().getArtifactPath()).isFile());
    }

    @Test
    public void notConfiguredIsATypedReason() throws Exception {
        LocalNlpConfigurationSnapshotProvider provider =
                new LocalNlpConfigurationSnapshotProvider(new LocalNlpModelStore(tempRoot()), select(""));
        assertReason(provider, NlpConfigurationException.Reason.MODEL_NOT_CONFIGURED);
    }

    @Test
    public void selectedButNotInstalledIsATypedReason() throws Exception {
        LocalNlpConfigurationSnapshotProvider provider = new LocalNlpConfigurationSnapshotProvider(
                new LocalNlpModelStore(tempRoot()), select("apache-opennlp/sentence-de"));
        assertReason(provider, NlpConfigurationException.Reason.MODEL_NOT_INSTALLED);
    }

    @Test
    public void aChecksumMismatchIsRejected() throws Exception {
        File root = tempRoot();
        // Manifest pins a WRONG sha256 for the artifact.
        LocalNlpModelStoreTest.install(root, "apache-opennlp/sentence-de", "de", new byte[]{1, 2, 3},
                "deadbeef");
        LocalNlpConfigurationSnapshotProvider provider = new LocalNlpConfigurationSnapshotProvider(
                new LocalNlpModelStore(root), select("apache-opennlp/sentence-de"));
        assertReason(provider, NlpConfigurationException.Reason.CHECKSUM_MISMATCH);
    }

    @Test
    public void aMatchingChecksumResolves() throws Exception {
        File root = tempRoot();
        File dir = LocalNlpModelStoreTest.install(root, "apache-opennlp/sentence-de", "de",
                new byte[]{9, 8, 7, 6}, "");
        String correct = LocalNlpModelStore.sha256Of(new File(dir, "model.bin"));
        // Re-install with the correct pinned hash.
        LocalNlpModelStoreTest.install(root, "apache-opennlp/sentence-de", "de", new byte[]{9, 8, 7, 6},
                correct);
        LocalNlpConfigurationSnapshotProvider provider = new LocalNlpConfigurationSnapshotProvider(
                new LocalNlpModelStore(root), select("apache-opennlp/sentence-de"));
        assertEquals(correct, provider.resolve(NlpCapability.SENTENCE_DETECTION, "de")
                .getDescriptor().getSha256());
    }

    private static void assertReason(LocalNlpConfigurationSnapshotProvider provider,
                                     NlpConfigurationException.Reason expected) {
        try {
            provider.resolve(NlpCapability.SENTENCE_DETECTION, "de");
            fail("expected " + expected);
        } catch (NlpConfigurationException ex) {
            assertEquals(expected, ex.getReason());
        }
    }
}
