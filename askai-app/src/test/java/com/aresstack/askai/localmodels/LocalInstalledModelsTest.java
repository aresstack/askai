package com.aresstack.askai.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** The host manifest reader is fail-closed: only catalog-VALID manifests are returned to the UI. */
public class LocalInstalledModelsTest {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private File install(String dir, String json) throws Exception {
        File modelDir = new File(folder.getRoot(), dir);
        modelDir.mkdirs();
        Files.write(new File(modelDir, "askai-local-model.json").toPath(), json.getBytes(UTF_8));
        return modelDir;
    }

    private String validJson(String repo) {
        return LocalModelManifestCodec.toJson(InstalledModelManifest.forInstall(
                LocalModelCatalog.findByRepositoryId(repo), "rev", 1700000000000L));
    }

    @Test
    public void returnsAValidV2Manifest() throws Exception {
        install("all-MiniLM-L6-v2", validJson("sentence-transformers/all-MiniLM-L6-v2"));
        InstalledModelManifest read = LocalInstalledModels.readByVirtualName(folder.getRoot(),
                "local/sentence-transformers/all-MiniLM-L6-v2:latest");
        assertNotNull(read);
        assertEquals("MINILM_L6_V2", read.getRuntimeModelId());
    }

    @Test
    public void historicalV1RerankerStaysVisible() throws Exception {
        String v1 = "{\"schemaVersion\":1,"
                + "\"virtualName\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                + "\"huggingFaceRepository\":\"cross-encoder/ms-marco-MiniLM-L6-v2\","
                + "\"resolvedRevision\":\"deadbeef\",\"runtimeModelId\":\"MS_MARCO_MINILM_L6\","
                + "\"capabilities\":[\"rerank\"],\"backendSupport\":[\"cpu\",\"directml\"],"
                + "\"state\":\"RUNNABLE\"}";
        install("cross-encoder-ms-marco-MiniLM-L-6-v2", v1);
        assertNotNull(LocalInstalledModels.readByVirtualName(folder.getRoot(),
                "local/cross-encoder/ms-marco-MiniLM-L6-v2:latest"));
    }

    @Test
    public void invalidManifestIsNeverReturned() throws Exception {
        // A v2 manifest with an invented chat capability for MiniLM must not validate.
        String invented = LocalModelManifestCodec.toJson(InstalledModelManifest.forInstall(
                LocalModelCatalog.findByRepositoryId("sentence-transformers/all-MiniLM-L6-v2"),
                "rev", 1L)).replace("[\"embedding\"]", "[\"embedding\",\"chat\"]");
        install("tampered", invented);
        assertNull(LocalInstalledModels.readByVirtualName(folder.getRoot(),
                "local/sentence-transformers/all-MiniLM-L6-v2:latest"));

        // An unknown schema is not returned either.
        install("weird", "{\"schemaVersion\":9,\"virtualName\":\"local/x/y:latest\","
                + "\"huggingFaceRepository\":\"x/y\"}");
        assertNull(LocalInstalledModels.readByVirtualName(folder.getRoot(), "local/x/y:latest"));
    }

    @Test
    public void missingModelReturnsNull() {
        assertNull(LocalInstalledModels.readByVirtualName(folder.getRoot(), "local/ghost:latest"));
    }
}
