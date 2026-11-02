package com.aresstack.askai.service;

import com.aresstack.askai.AskAiModel;
import com.aresstack.askai.config.AppConfigurationRepository;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The not-yet-wired capabilities (vision prompt, tool calling, MCP tools) must be flagged unavailable
 * so the Actions UI can disable them instead of failing after the click; the genuinely-working ones
 * stay available.
 */
public class FeatureActionAvailabilityTest {

    @Test
    public void experimentalActionsAreMarkedUnavailable() {
        OllamaFeatureActionService service = new OllamaFeatureActionService(
                new AskAiModel(new AppConfigurationRepository()));
        Map<String, Boolean> available = new HashMap<String, Boolean>();
        for (FeatureAction action : service.actions()) {
            available.put(action.getId(), action.isAvailable());
        }
        assertFalse("vision-prompt", available.get("vision-prompt"));
        assertFalse("tool-calling", available.get("tool-calling"));
        assertFalse("mcp-tools", available.get("mcp-tools"));
        assertTrue("server-health", available.get("server-health"));
        assertTrue("model-details", available.get("model-details"));
        assertTrue("pull-model", available.get("pull-model"));
    }

    @Test
    public void featureActionDefaultsToAvailable() {
        assertTrue(new FeatureAction("x", "X", "d").isAvailable());
        assertFalse(new FeatureAction("x", "X", "d", false).isAvailable());
    }
}
