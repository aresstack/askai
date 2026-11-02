package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpConfigurationSnapshot;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Explicit install: verified download installs; wrong hash/size/HTTP-error/interrupt leave nothing; idempotent; resolve needs no download. */
public class NlpModelInstallerTest {

    private static final byte[] ARTIFACT = new byte[]{10, 20, 30, 40, 50};

    private static File tempRoot() throws IOException {
        return Files.createTempDirectory("askai-nlp-install").toFile();
    }

    private static String shaOf(byte[] bytes) throws IOException {
        return LocalNlpModelStore.sha256(bytes);
    }

    private static NlpModelCatalogEntry entry(String sha, long size) {
        return new NlpModelCatalogEntry("apache-opennlp/sentence-de", NlpCapability.SENTENCE_DETECTION, "de",
                "opennlp", "1.5", "1.9.4", "https://curated/de-sent.bin/download", "de-sent.bin", sha, size);
    }

    /** A download client that serves fixed bytes, throws, or counts calls. */
    private static final class FakeClient implements NlpDownloadClient {
        byte[] bytes;
        IOException failure;
        int calls;

        FakeClient(byte[] bytes) {
            this.bytes = bytes;
        }

        public byte[] fetch(String url) throws IOException {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return bytes;
        }
    }

    @Test
    public void aVerifiedDownloadInstallsAndTheManifestHoldsTheHash() throws IOException {
        File root = tempRoot();
        LocalNlpModelStore store = new LocalNlpModelStore(root);
        NlpModelCatalogEntry entry = entry(shaOf(ARTIFACT), ARTIFACT.length);

        NlpModelInstaller.Outcome outcome =
                new NlpModelInstaller(new FakeClient(ARTIFACT), store).install(entry);
        assertEquals(NlpModelInstaller.Outcome.INSTALLED, outcome);

        NlpModelDescriptor installed = store.find("apache-opennlp/sentence-de");
        assertNotNull("store catalog finds the model", installed);
        assertEquals(shaOf(ARTIFACT), installed.getSha256());       // manifest holds the verified hash
        assertEquals("de", installed.getLanguageCode());
        assertTrue(new File(installed.getArtifactPath()).isFile());
    }

    @Test
    public void aWrongHashIsRejectedAndNothingIsInstalled() throws IOException {
        File root = tempRoot();
        LocalNlpModelStore store = new LocalNlpModelStore(root);
        try {
            new NlpModelInstaller(new FakeClient(ARTIFACT), store)
                    .install(entry("0000000000000000000000000000000000000000000000000000000000000000",
                            ARTIFACT.length));
            fail("a hash mismatch must abort");
        } catch (IOException expected) {
            // ok
        }
        assertNull(store.find("apache-opennlp/sentence-de"));
        assertTrue(store.listInstalled().isEmpty());
    }

    @Test
    public void aWrongSizeIsRejected() throws IOException {
        LocalNlpModelStore store = new LocalNlpModelStore(tempRoot());
        try {
            new NlpModelInstaller(new FakeClient(ARTIFACT), store)
                    .install(entry(shaOf(ARTIFACT), ARTIFACT.length + 1));
            fail("a size mismatch must abort");
        } catch (IOException expected) {
            // ok
        }
        assertTrue(store.listInstalled().isEmpty());
    }

    @Test
    public void anHttpErrorLeavesNoInstallation() throws IOException {
        LocalNlpModelStore store = new LocalNlpModelStore(tempRoot());
        FakeClient client = new FakeClient(ARTIFACT);
        client.failure = new IOException("HTTP 500");
        try {
            new NlpModelInstaller(client, store).install(entry(shaOf(ARTIFACT), ARTIFACT.length));
            fail("an HTTP error must abort");
        } catch (IOException expected) {
            // ok
        }
        assertTrue(store.listInstalled().isEmpty());
    }

    @Test
    public void anInterruptedDownloadLeavesNoActiveInstallation() throws IOException {
        LocalNlpModelStore store = new LocalNlpModelStore(tempRoot());
        FakeClient client = new FakeClient(ARTIFACT);
        client.failure = new IOException("connection reset mid-stream");
        try {
            new NlpModelInstaller(client, store).install(entry(shaOf(ARTIFACT), ARTIFACT.length));
            fail("an interrupted download must abort");
        } catch (IOException expected) {
            // ok
        }
        assertNull(store.find("apache-opennlp/sentence-de"));
    }

    @Test
    public void reinstallWithTheSameHashIsIdempotentAndDoesNotReDownload() throws IOException {
        File root = tempRoot();
        LocalNlpModelStore store = new LocalNlpModelStore(root);
        NlpModelCatalogEntry entry = entry(shaOf(ARTIFACT), ARTIFACT.length);
        FakeClient client = new FakeClient(ARTIFACT);

        assertEquals(NlpModelInstaller.Outcome.INSTALLED, new NlpModelInstaller(client, store).install(entry));
        assertEquals(1, client.calls);
        assertEquals(NlpModelInstaller.Outcome.ALREADY_INSTALLED,
                new NlpModelInstaller(client, store).install(entry));
        assertEquals("no second download", 1, client.calls);
    }

    @Test
    public void resolvingAnInstalledModelNeedsNoDownloadClient() throws Exception {
        File root = tempRoot();
        LocalNlpModelStore store = new LocalNlpModelStore(root);
        NlpModelCatalogEntry entry = entry(shaOf(ARTIFACT), ARTIFACT.length);
        new NlpModelInstaller(new FakeClient(ARTIFACT), store).install(entry);

        // The snapshot provider has NO download capability by construction — it resolves purely from the store.
        LocalNlpConfigurationSnapshotProvider provider = new LocalNlpConfigurationSnapshotProvider(store,
                new LocalNlpConfigurationSnapshotProvider.NlpSelectionStore() {
                    public String selectedModelId(NlpCapability capability, String languageCode) {
                        return "apache-opennlp/sentence-de";
                    }
                });
        NlpConfigurationSnapshot snapshot = provider.resolve(NlpCapability.SENTENCE_DETECTION, "de");
        assertEquals("apache-opennlp/sentence-de", snapshot.getDescriptor().getModelId());
    }
}
