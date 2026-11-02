package com.aresstack.askai.localmodels;

import com.aresstack.askai.agent.model.nlp.NlpCapability;
import com.aresstack.askai.agent.model.nlp.NlpModelDescriptor;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The dedicated NLP store scans manifests + artifacts into neutral descriptors; the catalog filters by capability+language. */
public class LocalNlpModelStoreTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    static File install(File root, String id, String language, byte[] artifact, String sha256)
            throws IOException {
        File dir = new File(new File(root, "opennlp"), id.replaceAll("[^a-zA-Z0-9._-]", "_"));
        Files.createDirectories(dir.toPath());
        Files.write(new File(dir, "model.bin").toPath(), artifact);
        String json = "{\"id\":\"" + id + "\",\"capability\":\"sentence-detection\",\"language\":\""
                + language + "\",\"implementation\":\"opennlp\",\"version\":\"1.5-model\","
                + "\"compatibleRuntime\":\"1.9.4\",\"artifact\":\"model.bin\",\"sha256\":\"" + sha256 + "\"}";
        Files.write(new File(dir, LocalNlpModelStore.MANIFEST_NAME).toPath(), json.getBytes(UTF8));
        return dir;
    }

    private static File tempRoot() throws IOException {
        return Files.createTempDirectory("askai-nlp-store").toFile();
    }

    @Test
    public void listsInstalledModelsAsDescriptorsWithAbsoluteArtifactPaths() throws IOException {
        File root = tempRoot();
        install(root, "apache-opennlp/sentence-de", "de", new byte[]{1, 2, 3}, "");
        install(root, "apache-opennlp/sentence-en", "en", new byte[]{4, 5}, "");

        LocalNlpModelStore store = new LocalNlpModelStore(root);
        List<NlpModelDescriptor> installed = store.listInstalled();
        assertEquals(2, installed.size());
        NlpModelDescriptor de = store.find("apache-opennlp/sentence-de");
        assertNotNull(de);
        assertEquals(NlpCapability.SENTENCE_DETECTION, de.getCapability());
        assertEquals("de", de.getLanguageCode());
        assertEquals("opennlp", de.getImplementation());
        assertTrue(new File(de.getArtifactPath()).isAbsolute());
        assertTrue(new File(de.getArtifactPath()).isFile());
    }

    @Test
    public void aManifestWithoutItsArtifactIsNotAUsableInstall() throws IOException {
        File root = tempRoot();
        File dir = new File(new File(root, "opennlp"), "broken");
        Files.createDirectories(dir.toPath());
        Files.write(new File(dir, LocalNlpModelStore.MANIFEST_NAME).toPath(),
                ("{\"id\":\"broken\",\"capability\":\"sentence-detection\",\"language\":\"de\","
                        + "\"artifact\":\"missing.bin\"}").getBytes(UTF8));
        assertTrue(new LocalNlpModelStore(root).listInstalled().isEmpty());
    }

    @Test
    public void catalogFiltersByCapabilityAndLanguage() throws IOException {
        File root = tempRoot();
        install(root, "apache-opennlp/sentence-de", "de", new byte[]{1}, "");
        install(root, "apache-opennlp/sentence-en", "en", new byte[]{2}, "");
        LocalNlpModelCatalog catalog = new LocalNlpModelCatalog(new LocalNlpModelStore(root));

        assertEquals(java.util.Arrays.asList("apache-opennlp/sentence-de"),
                catalog.listInstalledModels(NlpCapability.SENTENCE_DETECTION, "DE"));
        assertTrue(catalog.listInstalledModels(NlpCapability.SENTENCE_DETECTION, "fr").isEmpty());
    }

    @Test
    public void findReturnsNullForAnUnknownId() throws IOException {
        assertNull(new LocalNlpModelStore(tempRoot()).find("nope"));
    }
}
