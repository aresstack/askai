package com.aresstack.askai.hf.meta;

import com.aresstack.askai.hf.HuggingFaceInstallPlan;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Maps trusted HF metadata into /api/create, with source/confidence conflict resolution. */
public class HuggingFaceMetadataLoaderTest {

    private static HuggingFaceInstallPlan plan(String modelType) {
        return new HuggingFaceInstallPlan("owner/model-GGUF", "main", "m:q4",
                Arrays.asList("TEXT"), Arrays.asList("completion"), modelType);
    }

    @Test
    public void mapsStructuredConfigAndGenerationFields() {
        FakeGateway gw = new FakeGateway();
        gw.files.put("config.json", "{\"model_type\":\"qwen3\",\"max_position_embeddings\":32768,\"hidden_size\":4096}");
        gw.files.put("generation_config.json",
                "{\"temperature\":0.7,\"top_p\":0.9,\"top_k\":40,\"repetition_penalty\":1.05,"
                        + "\"max_new_tokens\":512,\"do_sample\":true,\"eos_token_id\":128001}");

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan("qwen3"), "model-Q4_K_M.gguf");
        Map<String, Object> info = metadata.toInfoMap();
        assertEquals("qwen3", info.get("model_family"));
        assertEquals(32768, info.get("context_length"));
        assertEquals(4096, info.get("embedding_length"));
        // A name-only quant is MEDIUM → not sent without a structured confirmation.
        assertFalse(info.containsKey("quantization_level"));

        Map<String, Object> parameters = metadata.parameters();
        assertEquals(0.7, parameters.get("temperature"));
        assertEquals(0.9, parameters.get("top_p"));
        assertEquals(40, parameters.get("top_k"));
        assertEquals(1.05, parameters.get("repeat_penalty"));
        assertEquals(512, parameters.get("num_predict"));
        // Transformers-internal flags and token ids must not leak through.
        assertFalse(parameters.containsKey("do_sample"));
        assertFalse(parameters.containsKey("eos_token_id"));
    }

    @Test
    public void mapsTypicalPAndStopStrings() {
        FakeGateway gw = new FakeGateway();
        gw.files.put("generation_config.json",
                "{\"typical_p\":0.95,\"stop_strings\":[\"<|eot|>\",\"</s>\"],\"eos_token_id\":2}");
        Map<String, Object> parameters = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf").parameters();
        assertEquals(0.95, parameters.get("typical_p"));
        assertEquals(Arrays.asList("<|eot|>", "</s>"), parameters.get("stop"));
        // A numeric eos_token_id is never turned into a stop or parameter.
        assertFalse(parameters.containsKey("eos_token_id"));
    }

    @Test
    public void stopStringsAlsoAcceptsASingleString() {
        FakeGateway gw = new FakeGateway();
        gw.files.put("generation_config.json", "{\"stop_strings\":\"<|end|>\"}");
        Map<String, Object> parameters = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf").parameters();
        assertEquals(Collections.singletonList("<|end|>"), parameters.get("stop"));
    }

    @Test
    public void embeddingPipelineYieldsExclusiveEmbeddingBase() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("pipeline_tag", "feature-extraction");
        gw.modelInfo = info;

        // The frozen plan for an embedding model still carries "completion" (modalitiesOf → TEXT); the
        // loader must REPLACE it with an exclusive "embedding" base, not union the two.
        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf");
        assertEquals(Arrays.asList("embedding"), metadata.capabilities());
    }

    @Test
    public void toolsThinkingInsertAreNotDerivedFromHfTokenizerConfig() {
        // A HF chat template must NOT feign runtime capability: tools/thinking/insert come only from the
        // installed GGUF's own template (added by the service), never from the repo's tokenizer_config.
        FakeGateway gw = new FakeGateway();
        gw.files.put("tokenizer_config.json",
                "{\"chat_template\":\"{% if tool_calls %}<think>{{ reasoning_content }}</think>{% endif %}"
                        + " <|fim_prefix|>\"}");

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf");
        assertEquals(Arrays.asList("completion"), metadata.capabilities());
        // The raw HF Jinja is never sent as Ollama's template either.
        assertEquals("", metadata.template());
    }

    @Test
    public void plainChatModelGetsNoExtraCapabilities() {
        FakeGateway gw = new FakeGateway();
        gw.files.put("tokenizer_config.json",
                "{\"chat_template\":\"{% for m in messages %}{{ m.role }}: {{ m.content }}{% endfor %}\"}");

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf");
        assertEquals(Arrays.asList("completion"), metadata.capabilities());
    }

    @Test
    public void ggufMetadataConfirmsQuantizationAndParameterSize() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> gguf = new LinkedHashMap<String, Object>();
        gguf.put("quantization_level", "Q4_K_M");
        gguf.put("total", 8030000000L);
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("gguf", gguf);
        gw.modelInfo = info;

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "renamed.gguf");
        Map<String, Object> map = metadata.toInfoMap();
        assertEquals("Q4_K_M", map.get("quantization_level")); // structured GGUF value is HIGH → sent
        assertEquals("8B", map.get("parameter_size"));
    }

    @Test
    public void licenseFromCardDataIsSent() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> card = new LinkedHashMap<String, Object>();
        card.put("license", "apache-2.0");
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("cardData", card);
        gw.modelInfo = info;

        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf");
        assertEquals(Collections.singletonList("apache-2.0"), metadata.licenses());
    }

    @Test
    public void noMetadataDegradesToCapabilitiesAndFamily() {
        OllamaCreateMetadata metadata = new HuggingFaceMetadataLoader(new FakeGateway())
                .load(plan("gemma4"), "m.gguf");
        assertEquals(Arrays.asList("completion"), metadata.capabilities());
        assertEquals("gemma4", metadata.toInfoMap().get("model_family"));
        assertNull(metadata.toInfoMap().get("context_length"));
    }

    @Test
    public void formatsParameterSizes() {
        assertEquals("8B", HuggingFaceMetadataLoader.formatParameterSize(8030000000L));
        assertEquals("270M", HuggingFaceMetadataLoader.formatParameterSize(270000000L));
        assertEquals("1.5B", HuggingFaceMetadataLoader.formatParameterSize(1500000000L));
        assertNull(HuggingFaceMetadataLoader.formatParameterSize(0L));
    }

    @Test
    public void ggufMetadataOutranksRepositoryConfig() {
        FakeGateway gw = new FakeGateway();
        gw.files.put("config.json", "{\"model_type\":\"qwen3\",\"max_position_embeddings\":8192,\"hidden_size\":4096}");
        Map<String, Object> gguf = new LinkedHashMap<String, Object>();
        gguf.put("context_length", 32768); // the selected GGUF's real context beats config.json
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("gguf", gguf);
        gw.modelInfo = info;

        Map<String, Object> map = new HuggingFaceMetadataLoader(gw).load(plan("qwen3"), "m.gguf").toInfoMap();
        assertEquals(32768, map.get("context_length"));
    }

    @Test
    public void infoConfigIsUsedWhenConfigJsonIsMissing() {
        FakeGateway gw = new FakeGateway(); // no config.json file
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("hidden_size", 2048);
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("config", config);
        gw.modelInfo = info;

        Map<String, Object> map = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf").toInfoMap();
        assertEquals(2048, map.get("embedding_length"));
    }

    @Test
    public void topLevelBaseModelsMapsToBaseName() {
        FakeGateway gw = new FakeGateway();
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("baseModels", java.util.Collections.singletonList("Qwen/Qwen3-8B"));
        gw.modelInfo = info;

        Map<String, Object> map = new HuggingFaceMetadataLoader(gw).load(plan(""), "m.gguf").toInfoMap();
        assertEquals("Qwen/Qwen3-8B", map.get("base_name"));
    }

    @Test
    public void allFetchesArePinnedToTheResolvedSha() {
        FakeGateway gw = new FakeGateway();
        HuggingFaceInstallPlan pinned = new HuggingFaceInstallPlan("owner/model", "main", "abc123sha",
                "m:q4", Arrays.asList("TEXT"), Arrays.asList("completion"), "");
        new HuggingFaceMetadataLoader(gw).load(pinned, "m.gguf");
        assertEquals("abc123sha", gw.lastFileRevision);
        assertEquals("abc123sha", gw.lastInfoRevision);
    }

    /** In-memory gateway: config/generation files by name, one model-info map; records the revision used. */
    private static final class FakeGateway implements HuggingFaceMetadataGateway {
        final Map<String, String> files = new LinkedHashMap<String, String>();
        Map<String, Object> modelInfo = new LinkedHashMap<String, Object>();
        String lastFileRevision;
        String lastInfoRevision;

        public String fetchFile(String repositoryId, String revision, String path) {
            lastFileRevision = revision;
            return files.get(path);
        }

        public Map<String, Object> fetchModelInfo(String repositoryId, String revision) {
            lastInfoRevision = revision;
            return modelInfo;
        }
    }
}
