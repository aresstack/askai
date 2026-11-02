package com.aresstack.askai.config;

import com.aresstack.askai.config.HuggingFaceSearchSuggestion.Target;
import com.aresstack.windirectml.catalog.LocalModelCatalog;
import com.aresstack.windirectml.catalog.LocalRuntimeModelDescriptor;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The shipped default suggestions are generated deterministically from the neutral catalog — there is no
 * second hardcoded model list in AskAI. The local group appears first, in catalog order, marked local;
 * UNVERIFIED entries (e.g. the L-12 reranker) never appear; and the migration only replaces an unchanged
 * historical default, never a user-customized list.
 */
public class LocalEngineSuggestionDefaultsTest {

    private static List<HuggingFaceSearchSuggestion> parse() {
        return HuggingFaceSearchSuggestion.parseList(AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS);
    }

    @Test
    public void localGroupIsExactlyTheRunnableCatalogInOrder() {
        List<String> expected = new ArrayList<String>();
        for (LocalRuntimeModelDescriptor d : LocalModelCatalog.runnable()) {
            expected.add(d.huggingFaceRepositoryId());
        }
        List<String> actualLocal = new ArrayList<String>();
        for (HuggingFaceSearchSuggestion s : parse()) {
            if (s.isLocalEngine()) {
                actualLocal.add(s.getTerm());
            }
        }
        assertEquals("local defaults must be the catalog runnable set, in catalog order",
                expected, actualLocal);
    }

    @Test
    public void localGroupComesFirstThenGeneral() {
        List<HuggingFaceSearchSuggestion> all = parse();
        int lastLocal = -1;
        int firstGeneral = Integer.MAX_VALUE;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).isLocalEngine()) {
                lastLocal = i;
            } else if (i < firstGeneral) {
                firstGeneral = i;
            }
        }
        assertTrue("the local group is contiguous and precedes the general group", lastLocal < firstGeneral);
        assertEquals(Target.ASKAI_LOCAL_ENGINE, all.get(0).getTarget());
    }

    @Test
    public void unverifiedModelsNeverAppearAsLocalDefaults() {
        for (HuggingFaceSearchSuggestion s : parse()) {
            assertFalse("the UNVERIFIED L-12 reranker must never be a local default",
                    s.getTerm().equalsIgnoreCase("cross-encoder/ms-marco-MiniLM-L12-v2"));
        }
    }

    @Test
    public void generalListRemainsAndIsNeverMarkedLocal() {
        boolean sawGeneral = false;
        for (HuggingFaceSearchSuggestion s : parse()) {
            if (s.getTerm().equals("openai/gpt-oss-20b")) {
                sawGeneral = true;
                assertEquals(Target.GENERAL, s.getTarget());
            }
        }
        assertTrue("the general Ollama/GGUF suggestions stay present", sawGeneral);
    }

    @Test
    public void migrationUpgradesTheUnchangedPriorDefaultButLeavesCustomListsUntouched() {
        // The exact prior shipped default (general list + plain local reranker line) upgrades.
        String priorDefault = "openai/gpt-oss-20b | text\nLiquidAI/LFM2-24B-A2B | text\n"
                + "deepseek-ai/DeepSeek-Coder-V2-Lite-Instruct | text\n"
                + "unsloth/Qwen3-Coder-30B-A3B-Instruct-GGUF | text\n"
                + "unsloth/GLM-4.7-Flash-GGUF | text\n"
                + "mistralai/Devstral-Small-2-24B-Instruct-2512 | text\n"
                + "llama-3.1-8b-instruct | text\ngemma-3-12b-it | text,vision\n"
                + "qwen2.5-14b-instruct | text\nqwen2.5-coder-14b | text\nphi-4 | text\n"
                + "mistral-nemo | text\ngemma-3n-e4b | text\nvoxtral-mini-3b | text,audio\n"
                + "ultravox | text,audio\ncross-encoder/ms-marco-MiniLM-L6-v2 | text";
        assertEquals(AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS,
                AppConfiguration.migrateSearchSuggestions(priorDefault));

        // A user-customized list (one line removed) is never rewritten.
        String custom = priorDefault + "\nmy/private-model | text";
        assertEquals(custom, AppConfiguration.migrateSearchSuggestions(custom));

        // A null persisted value falls back to the current default.
        assertEquals(AppConfiguration.DEFAULT_HF_SEARCH_SUGGESTIONS,
                AppConfiguration.migrateSearchSuggestions(null));
    }
}
