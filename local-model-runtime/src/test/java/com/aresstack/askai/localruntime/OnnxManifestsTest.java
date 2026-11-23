package com.aresstack.askai.localruntime;

import com.aresstack.askai.localruntime.onnx.OnnxModelCatalog;
import com.aresstack.askai.localruntime.onnx.OnnxModelDescriptor;
import com.aresstack.windirectml.catalog.InstalledModelManifest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OnnxManifestsTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void installedOnnxModelIsPublishedWithItsCatalogCapability() throws Exception {
        OnnxModelDescriptor bge = OnnxModelCatalog.findByRepositoryId("BAAI/bge-m3");
        Path dir = installed(bge, OnnxManifests.forInstall(bge, "cpu", "abc123", 1L));
        LocalModel model = SidecarManifests.read(dir);
        assertNotNull(model);
        assertTrue(model.isOnnx());
        assertTrue(model.isEmbedding());
        assertFalse(model.isReranker());
        assertEquals(bge, model.onnx());
        assertEquals("local/BAAI/bge-m3:latest", model.virtualName());
    }

    @Test
    public void manifestClaimingAnotherCapabilityIsRejected() throws Exception {
        OnnxModelDescriptor bge = OnnxModelCatalog.findByRepositoryId("BAAI/bge-m3");
        InstalledModelManifest forged = new InstalledModelManifest(InstalledModelManifest.CURRENT_SCHEMA_VERSION,
                bge.virtualModelName(), bge.repositoryId(), "", bge.repositoryId(), "xlm-roberta", "onnx",
                List.of("rerank"), List.of("cpu"), "onnx", "RUNNABLE", 1L);
        assertNull(SidecarManifests.read(installed(bge, forged)));
    }

    @Test
    public void modelWithoutExportOrGraphIsRejected() throws Exception {
        OnnxModelDescriptor e5 = OnnxModelCatalog.findByRepositoryId("danielheinz/e5-base-sts-en-de");
        assertNull(SidecarManifests.read(installed(e5, OnnxManifests.forInstall(e5, "cpu", "", 1L))));
        OnnxModelDescriptor bge = OnnxModelCatalog.findByRepositoryId("BAAI/bge-m3");
        Path dir = installed(bge, OnnxManifests.forInstall(bge, "cpu", "", 1L));
        Files.delete(dir.resolve("model.onnx"));
        assertNull(SidecarManifests.read(dir));
    }

    private Path installed(OnnxModelDescriptor descriptor, InstalledModelManifest manifest) throws Exception {
        Path dir = temp.newFolder(descriptor.directoryName() + System.nanoTime()).toPath();
        Files.write(dir.resolve("model.onnx"), new byte[]{1});
        Files.write(dir.resolve("tokenizer.json"), "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve(SidecarManifests.FILE_NAME), json(manifest).getBytes(StandardCharsets.UTF_8));
        return dir;
    }

    private static String json(InstalledModelManifest m) {
        java.util.Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("schemaVersion", m.getSchemaVersion());
        map.put("virtualName", m.getVirtualName());
        map.put("huggingFaceRepository", m.getHuggingFaceRepository());
        map.put("resolvedRevision", m.getResolvedRevision());
        map.put("runtimeModelId", m.getRuntimeModelId());
        map.put("runtimeFamily", m.getRuntimeFamily());
        map.put("runtimePackage", m.getRuntimePackage());
        map.put("capabilities", m.getCapabilities());
        map.put("supportedBackends", m.getSupportedBackends());
        map.put("sourceFormat", m.getSourceFormat());
        map.put("state", m.getState());
        map.put("installedAt", m.getInstalledAt());
        return LocalJson.write(map);
    }
}
