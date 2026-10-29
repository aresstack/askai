package com.aresstack.askai.java8.hf.meta;

import com.aresstack.askai.java8.hf.HuggingFaceInstallPlan;
import com.aresstack.askai.java8.json.SimpleJson;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The provenance ledger captures the repository facts, per-field sources and the sent create metadata. */
public class HuggingFaceImportProvenanceTest {

    private static HuggingFaceInstallPlan plan() {
        return new HuggingFaceInstallPlan("owner/model", "main", "sha-123", "m:q4",
                Arrays.asList("TEXT"), Arrays.asList("completion"), "qwen3");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void capturesProvenanceBlockFieldsAndSentMetadata() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("author", "owner");
        info.put("pipeline_tag", "text-generation");
        info.put("library_name", "gguf");
        info.put("tags", Arrays.asList("gguf", "base_model:owner/base"));
        info.put("baseModels", Arrays.asList("owner/base"));
        info.put("gated", Boolean.TRUE);
        info.put("private", Boolean.FALSE);
        info.put("config", singletonMap("model_type", "qwen3"));
        gw.modelInfo = info;

        HuggingFaceMetadataLoader.Result result =
                new HuggingFaceMetadataLoader(gw).loadWithProvenance(plan(), "/downloads/m.gguf", "file-sha", 42L);
        Map<String, Object> doc = result.provenance().asMap();

        assertEquals(4, ((Number) doc.get("schemaVersion")).intValue());
        assertEquals("owner/model", doc.get("repositoryId"));
        assertEquals("owner", doc.get("author"));
        assertEquals("main", doc.get("requestedRevision"));
        assertEquals("sha-123", doc.get("resolvedRevisionSha"));
        assertEquals("/downloads/m.gguf", doc.get("selectedFilePath"));
        assertEquals("file-sha", doc.get("selectedFileSha256"));
        assertEquals("text-generation", doc.get("pipelineTag"));
        assertEquals(Boolean.TRUE, doc.get("gated"));
        assertEquals(Arrays.asList("owner/base"), doc.get("baseModels"));

        Map<String, Object> fields = (Map<String, Object>) doc.get("fields");
        Map<String, Object> family = (Map<String, Object>) fields.get("model_family");
        assertEquals("qwen3", family.get("value"));
        assertEquals("CONFIG_JSON", family.get("source"));
        assertEquals("HIGH", family.get("confidence"));

        Map<String, Object> sent = (Map<String, Object>) doc.get("sentCreateMetadata");
        assertEquals("qwen3", sent.get("model_family"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void writesSchemaV4SidecarNextToTheFile() throws Exception {
        Map<String, Object> doc = new LinkedHashMap<String, Object>();
        doc.put("repositoryId", "owner/model");
        File gguf = File.createTempFile("askai-prov-", ".gguf");
        gguf.deleteOnExit();
        new HuggingFaceImportProvenance(doc).writeSidecar(gguf);

        File sidecar = HuggingFaceImportProvenance.sidecarFile(gguf);
        sidecar.deleteOnExit();
        assertTrue(sidecar.isFile());
        Map<String, Object> read = (Map<String, Object>) SimpleJson.parse(
                new String(Files.readAllBytes(sidecar.toPath()), "UTF-8"));
        assertEquals(4, ((Number) read.get("schemaVersion")).intValue());
        assertEquals("owner/model", read.get("repositoryId"));
    }

    private static Map<String, Object> singletonMap(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }

    private static final class FakeGateway implements HuggingFaceMetadataGateway {
        Map<String, Object> modelInfo = new LinkedHashMap<String, Object>();

        public String fetchFile(String repositoryId, String revision, String path) {
            return null; // force config from info.config
        }

        public Map<String, Object> fetchModelInfo(String repositoryId, String revision) {
            return modelInfo;
        }
    }
}
