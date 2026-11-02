package com.aresstack.askai.localmodels;

import com.aresstack.windirectml.catalog.InstalledModelManifest;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;
import com.aresstack.windirectml.catalog.ManifestValidation;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Host JSON I/O round-trips the shared manifest, and the shared catalog rules still hold after a round-trip. */
public class LocalModelManifestCodecTest {

    @Test
    public void jsonRoundTripPreservesFactsAndStaysValid() {
        LocalRuntimeModelDescriptor minilm =
                LocalModelCatalog.findByRepositoryId("sentence-transformers/all-MiniLM-L6-v2");
        String json = LocalModelManifestCodec.toJson(
                InstalledModelManifest.forInstall(minilm, "rev123", 1700000000000L));
        InstalledModelManifest read = LocalModelManifestCodec.parse(json);
        assertEquals(2, read.getSchemaVersion());
        assertEquals(minilm.virtualModelName(), read.getVirtualName());
        assertEquals("rev123", read.getResolvedRevision());
        assertEquals("encoder.wdmlpack", read.getRuntimePackage());
        assertEquals(1700000000000L, read.getInstalledAt());
        assertEquals(ManifestValidation.VALID, read.validate(read.getSchemaVersion()));
    }

    @Test
    public void v1BackendSupportKeyIsStillRead() {
        String v1 = "{\"schemaVersion\":1,"
                + "\"virtualName\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                + "\"huggingFaceRepository\":\"cross-encoder/ms-marco-MiniLM-L6-v2\","
                + "\"runtimeModelId\":\"MS_MARCO_MINILM_L6\","
                + "\"capabilities\":[\"rerank\"],\"backendSupport\":[\"cpu\",\"directml\"],"
                + "\"state\":\"RUNNABLE\"}";
        InstalledModelManifest read = LocalModelManifestCodec.parse(v1);
        assertEquals(1, read.getSchemaVersion());
        assertEquals(java.util.Arrays.asList("cpu", "directml"), read.getSupportedBackends());
        assertEquals(ManifestValidation.VALID, read.validate(read.getSchemaVersion()));
    }

    @Test
    public void unreadableJsonParsesToNull() {
        assertNull(LocalModelManifestCodec.parse("{not json"));
    }

    @Test
    public void absentSchemaVersionIsTreatedAsHistoricalV1() {
        // No schemaVersion field at all -> historical v1 reranker.
        String noSchema = "{\"virtualName\":\"local/cross-encoder/ms-marco-MiniLM-L6-v2:latest\","
                + "\"huggingFaceRepository\":\"cross-encoder/ms-marco-MiniLM-L6-v2\","
                + "\"runtimeModelId\":\"MS_MARCO_MINILM_L6\",\"capabilities\":[\"rerank\"],"
                + "\"backendSupport\":[\"cpu\",\"directml\"],\"state\":\"RUNNABLE\"}";
        InstalledModelManifest read = LocalModelManifestCodec.parse(noSchema);
        assertEquals(1, read.getSchemaVersion());
        assertEquals(ManifestValidation.VALID, read.validate(read.getSchemaVersion()));
    }

    @Test
    public void presentButNonIntegerSchemaVersionIsMalformedNotV1() {
        String badString = "{\"schemaVersion\":\"kaputt\",\"virtualName\":\"local/x:latest\","
                + "\"huggingFaceRepository\":\"x/y\",\"capabilities\":[\"rerank\"]}";
        InstalledModelManifest read = LocalModelManifestCodec.parse(badString);
        assertEquals(InstalledModelManifest.SCHEMA_VERSION_MALFORMED, read.getSchemaVersion());
        assertEquals(ManifestValidation.INVALID_MANIFEST, read.validate(read.getSchemaVersion()));

        String fractional = "{\"schemaVersion\":1.5,\"virtualName\":\"local/x:latest\","
                + "\"huggingFaceRepository\":\"x/y\",\"capabilities\":[\"rerank\"]}";
        InstalledModelManifest frac = LocalModelManifestCodec.parse(fractional);
        assertEquals(InstalledModelManifest.SCHEMA_VERSION_MALFORMED, frac.getSchemaVersion());
        assertEquals(ManifestValidation.INVALID_MANIFEST, frac.validate(frac.getSchemaVersion()));
    }

    @Test
    public void integerSchemaVersionIsCarriedThrough() {
        LocalRuntimeModelDescriptor minilm =
                LocalModelCatalog.findByRepositoryId("sentence-transformers/all-MiniLM-L6-v2");
        InstalledModelManifest read = LocalModelManifestCodec.parse(
                LocalModelManifestCodec.toJson(InstalledModelManifest.forInstall(minilm, "rev", 5L)));
        assertEquals(2, read.getSchemaVersion());
    }
}
