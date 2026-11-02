package com.aresstack.askai.hf.convert;

import com.aresstack.askai.hf.HuggingFaceModel;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Phase-3 regression: the ConverterService verdicts over synthetic (no-network) analyses. */
public class ConverterServiceTest {

    private final ConverterService service = new ConverterService();
    private final OllamaEnvironment env = new OllamaEnvironment("0.5.0");

    private RepositoryAnalysis safetensors(String arch, boolean cfg, boolean readable, boolean tokenizer) {
        RepositoryAnalysis.Builder b = RepositoryAnalysis.builder("x/y").verified(true)
                .addFormat(ModelFormat.SAFETENSORS)
                .hasConfigJson(cfg).configReadable(readable).hasTokenizer(tokenizer);
        if (arch != null) {
            b.architectures(Arrays.asList(arch));
        }
        return b.build();
    }

    @Test
    public void ggufIsExecutable() {
        SupportDecision d = service.classify(
                RepositoryAnalysis.builder("a/b").verified(true).addFormat(ModelFormat.GGUF).build(), env);
        assertTrue(d.isSupported());
        assertTrue(d.isExecutable());
    }

    @Test
    public void qualifyingSafetensorsIsSupportedButNotExecutable() {
        SupportDecision d = service.classify(safetensors("Gemma3ForCausalLM", true, true, true), env);
        assertTrue(d.isSupported());
        assertFalse(d.isExecutable());
        assertTrue(d.getReason().contains("0.5.0"));
    }

    @Test
    public void missingTokenizerIsUnsupported() {
        SupportDecision d = service.classify(safetensors("Gemma3ForCausalLM", true, true, false), env);
        assertFalse(d.isSupported());
        assertTrue(d.getReason().contains("Tokenizer"));
    }

    @Test
    public void unknownArchitectureIsUnsupportedAndNamed() {
        SupportDecision d = service.classify(safetensors("MambaForCausalLM", true, true, true), env);
        assertFalse(d.isSupported());
        assertTrue(d.getReason().contains("MambaForCausalLM"));
    }

    @Test
    public void unreadableConfigReportsGated() {
        SupportDecision d = service.classify(safetensors("Gemma3ForCausalLM", true, false, true), env);
        assertFalse(d.isSupported());
        assertTrue(d.getReason().toLowerCase().contains("gated"));
    }

    @Test
    public void onnxOnlyHasNoImportPath() {
        SupportDecision d = service.classify(
                RepositoryAnalysis.builder("o/x").verified(true).addFormat(ModelFormat.ONNX).build(), env);
        assertFalse(d.isSupported());
        assertTrue(d.getReason().contains("Importweg"));
    }

    @Test
    public void provisionalClassification() {
        assertTrue(service.provisionalClassify(model("a/b-GGUF", "gguf")).isSupported());
        assertTrue(service.provisionalClassify(model("a/b", "safetensors")).isChecking());
        assertFalse(service.provisionalClassify(model("a/b", "onnx")).isSupported());
    }

    private static HuggingFaceModel model(String id, String libraryTag) {
        return new HuggingFaceModel(id, "text-generation", 1, 1, Arrays.asList(libraryTag), libraryTag);
    }
}
