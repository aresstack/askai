package com.aresstack.askai.java8.hf.meta;

import com.aresstack.askai.java8.hf.HuggingFaceInstallPlan;
import com.aresstack.askai.java8.json.SimpleJson;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * The loader maps representative Hugging Face model-info shapes (modeled on the documented HF API) for a
 * transformers/safetensors model, a pure GGUF quant repo, a safetensors repo and a Gemma-4 GGUF repo. The
 * GGUF blocks deliberately use different key spellings to prove the loader tolerates several forms.
 */
public class HuggingFaceMetadataFixturesTest {

    private Map<String, Object> load(String fixture) {
        FixtureGateway gateway = new FixtureGateway(fixture);
        HuggingFaceInstallPlan plan = new HuggingFaceInstallPlan("owner/model", "main", "sha",
                "m:q4", Arrays.asList("TEXT"), Arrays.asList("completion"), "");
        return new HuggingFaceMetadataLoader(gateway).load(plan, "model.gguf").toInfoMap();
    }

    private java.util.List<String> licenses(String fixture) {
        FixtureGateway gateway = new FixtureGateway(fixture);
        HuggingFaceInstallPlan plan = new HuggingFaceInstallPlan("owner/model", "main", "sha",
                "m:q4", Arrays.asList("TEXT"), Arrays.asList("completion"), "");
        return new HuggingFaceMetadataLoader(gateway).load(plan, "model.gguf").licenses();
    }

    @Test
    public void transformersSafetensorsModel() {
        Map<String, Object> info = load("transformers-safetensors.json");
        assertEquals("qwen3", info.get("model_family"));
        assertEquals(32768, info.get("context_length"));
        assertEquals(4096, info.get("embedding_length"));
        assertEquals("8B", info.get("parameter_size"));
        assertEquals("Qwen/Qwen3-8B", info.get("base_name"));
        assertEquals(Collections.singletonList("apache-2.0"), licenses("transformers-safetensors.json"));
    }

    @Test
    public void ggufQuantRepo() {
        Map<String, Object> info = load("gguf-quant-repo.json");
        assertEquals("llama", info.get("model_family"));
        assertEquals("Q4_K_M", info.get("quantization_level"));
        assertEquals(131072, info.get("context_length"));
        assertEquals("8B", info.get("parameter_size"));
        assertEquals("meta-llama/Llama-3.1-8B-Instruct", info.get("base_name"));
    }

    @Test
    public void safetensorsGemmaWithNestedTextConfig() {
        Map<String, Object> info = load("safetensors-gemma.json");
        assertEquals("gemma2", info.get("model_family"));
        assertEquals(8192, info.get("context_length"));
        assertEquals(2304, info.get("embedding_length"));
        assertEquals("2.6B", info.get("parameter_size"));
    }

    @Test
    public void gemma4GgufWithAlternativeKeySpellings() {
        Map<String, Object> info = load("gemma4-gguf.json");
        assertEquals("gemma4", info.get("model_family"));
        assertEquals("Q8_0", info.get("quantization_level"));
        assertEquals(8192, info.get("context_length"));
        assertEquals(2048, info.get("embedding_length"));
        assertEquals("2B", info.get("parameter_size"));
    }

    /** Serves one stored fixture as the model-info; repo files are absent so config falls back to info.config. */
    private static final class FixtureGateway implements HuggingFaceMetadataGateway {
        private final Map<String, Object> info;

        @SuppressWarnings("unchecked")
        FixtureGateway(String fixture) {
            this.info = (Map<String, Object>) SimpleJson.parse(read("/hf/fixtures/" + fixture));
        }

        public String fetchFile(String repositoryId, String revision, String path) {
            return null; // force the config.json → info.config fallback and no LICENSE file
        }

        public Map<String, Object> fetchModelInfo(String repositoryId, String revision) {
            return info;
        }

        private static String read(String resource) {
            InputStream in = HuggingFaceMetadataFixturesTest.class.getResourceAsStream(resource);
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + resource);
            }
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
                return new String(out.toByteArray(), "UTF-8");
            } catch (Exception ex) {
                throw new IllegalStateException(ex);
            } finally {
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
