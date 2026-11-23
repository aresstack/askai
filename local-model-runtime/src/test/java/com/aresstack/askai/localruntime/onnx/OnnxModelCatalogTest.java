package com.aresstack.askai.localruntime.onnx;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OnnxModelCatalogTest {

    @Test
    public void bgeM3IsARunnableClsXlmrEmbeddingWithExternalWeights() {
        OnnxModelDescriptor bge = OnnxModelCatalog.findByRepositoryId("baai/BGE-M3");
        assertNotNull(bge);
        assertTrue(bge.isRunnable());
        assertEquals(OnnxModelDescriptor.Capability.EMBEDDING, bge.capability());
        assertEquals("xlm-roberta", bge.architecture());
        assertEquals(OnnxModelDescriptor.Pooling.CLS, bge.pooling());
        assertEquals(8192, bge.maxTokens());
        assertEquals("model.onnx_data", bge.graphs().get(0).externalDataLocalName());
        assertEquals("local/BAAI/bge-m3:latest", bge.virtualModelName());
        assertEquals("BAAI--bge-m3", bge.directoryName());
    }

    @Test
    public void e5StsWaitsForAnExportAndUsesQueryPrefixesForBothSides() {
        OnnxModelDescriptor e5 = OnnxModelCatalog.findByRepositoryId("danielheinz/e5-base-sts-en-de");
        assertNotNull(e5);
        assertFalse(e5.isRunnable());
        assertTrue(e5.exportNote().contains("optimum-cli export onnx"));
        assertEquals("query: ", e5.queryPrefix());
        assertEquals("query: ", e5.passagePrefix());
        assertFalse(OnnxModelCatalog.runnable().contains(e5));
    }

    @Test
    public void quantizedGraphIsPreferredOnlyWhenWantedAndPresent() {
        OnnxModelDescriptor marco = OnnxModelCatalog.findByRepositoryId("cross-encoder/ms-marco-MiniLM-L6-v2");
        Set<String> all = Set.of("onnx/model.onnx", "onnx/model_quint8_avx2.onnx");
        assertTrue(marco.preferredGraph(true, all::contains).quantized());
        assertFalse(marco.preferredGraph(false, all::contains).quantized());
        assertFalse(marco.preferredGraph(true, Set.of("onnx/model.onnx")::contains).quantized());
        assertNull(marco.preferredGraph(true, Set.<String>of()::contains));
    }

    @Test
    public void nemotronIsListedButNotInstallable() {
        OnnxModelDescriptor nemotron = OnnxModelCatalog.findByRepositoryId("nvidia/llama-nemotron-rerank-1b-v2");
        assertNotNull(nemotron);
        assertFalse(nemotron.isRunnable());
        assertNull(OnnxModelCatalog.findByRepositoryId("unknown/model"));
        assertEquals(nemotron, OnnxModelCatalog.findByVirtualName("local/nvidia/llama-nemotron-rerank-1b-v2:latest"));
    }

    @Test
    public void optionsKeepDefaultsAndCapTokens() {
        OnnxRuntimeOptions defaults = OnnxRuntimeOptions.defaults();
        assertEquals(OnnxRuntimeOptions.ExecutionProvider.CPU, defaults.executionProvider());
        assertTrue(defaults.preferQuantized());
        assertEquals(8192, defaults.effectiveMaxTokens(8192));
        assertEquals(512, OnnxRuntimeOptions.builder().maxTokens(512).build().effectiveMaxTokens(8192));
        assertEquals(OnnxRuntimeOptions.ExecutionProvider.CPU, OnnxRuntimeOptions.ExecutionProvider.parse("bogus"));
        assertEquals(OnnxRuntimeOptions.ExecutionProvider.DIRECTML,
                OnnxRuntimeOptions.ExecutionProvider.parse("DirectML"));
    }
}
