package com.aresstack.askai.research.host;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * A5e/A5f: the productive backend always hands the agent the mandatory reranker snapshot path, and a
 * session cannot start without a reranker snapshot provider — there is no reranker-less browser run.
 */
public class MandatoryRerankerWiringTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void agentLaunchEnvironmentCarriesBothConfigs() {
        Map<String, String> env = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "/tmp/search.json", "/tmp/reranker.json", "/tmp/inference.json");
        assertEquals("/tmp/search.json", env.get("ASKAI_BROWSER_SEARCH_CONFIG"));
        assertEquals("the mandatory reranker snapshot reaches the agent launch environment",
                "/tmp/reranker.json", env.get("ASKAI_RERANKER_CONFIG"));
        assertEquals("the optional inference descriptor reaches the agent launch environment",
                "/tmp/inference.json", env.get("ASKAI_INFERENCE_CONFIG"));
    }

    @Test
    public void agentLaunchEnvironmentOmitsInferenceWhenAbsent() {
        Map<String, String> env = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "/tmp/search.json", "/tmp/reranker.json", "");
        assertTrue("no inference descriptor → the key is omitted entirely (honest fallback)",
                !env.containsKey("ASKAI_INFERENCE_CONFIG"));
    }

    @Test
    public void agentLaunchEnvironmentCarriesTheInferenceUnavailableReasonInsteadOfADescriptor() {
        Map<String, String> env = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "/tmp/search.json", "/tmp/reranker.json", "", "No main model is selected.");
        assertTrue("no descriptor → the key is omitted", !env.containsKey("ASKAI_INFERENCE_CONFIG"));
        assertEquals("the actionable reason reaches the agent so its MODEL_UNAVAILABLE message can use it",
                "No main model is selected.", env.get("ASKAI_INFERENCE_UNAVAILABLE_REASON"));
    }

    @Test
    public void aPublishedInferenceDescriptorNeverCarriesAnUnavailableReason() {
        Map<String, String> env = ProductiveResearchBackendFactory.agentLaunchEnvironment(
                "/tmp/search.json", "/tmp/reranker.json", "/tmp/inference.json", "ignored reason");
        assertEquals("/tmp/inference.json", env.get("ASKAI_INFERENCE_CONFIG"));
        assertTrue("a present descriptor wins; no reason key",
                !env.containsKey("ASKAI_INFERENCE_UNAVAILABLE_REASON"));
    }

    @Test
    public void createSessionPassesTheSessionResearchLanguageToTheRerankerResolution() throws Exception {
        File exe = folder.newFile("java");
        File jar = folder.newFile("agent.jar");
        ResearchRuntimeConfig config = new ResearchRuntimeConfig("chrome", true, true);
        // Records the language the factory resolves the reranker with, then aborts the start (visible error).
        final String[] seenLanguage = new String[1];
        com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider recording =
                new com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider() {
                    public com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot
                            prepareForSession(String sessionId, File dir, String selected)
                            throws com.aresstack.askai.agent.model.reranker.RerankerConfigurationException {
                        throw new com.aresstack.askai.agent.model.reranker
                                .RerankerConfigurationException("language-less path must not be used");
                    }

                    @Override
                    public com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot
                            prepareForSession(String sessionId, File dir, String selected, String language)
                            throws com.aresstack.askai.agent.model.reranker.RerankerConfigurationException {
                        seenLanguage[0] = language;
                        throw new com.aresstack.askai.agent.model.reranker
                                .RerankerConfigurationException("recorded");
                    }
                };
        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                null, config, 1L, recording);
        factory.setResearchLanguageCode("de");
        try {
            factory.createSession("s1", folder.newFolder("session"));
            fail("the recording provider aborts the start");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("recorded"));
        }
        assertEquals("the session research language reaches the reranker resolution", "de", seenLanguage[0]);
    }

    @Test
    public void createSessionPublishesPerLanguageRerankerSnapshots() throws Exception {
        File exe = folder.newFile("java");
        File jar = folder.newFile("agent.jar");
        ResearchRuntimeConfig config = new ResearchRuntimeConfig("chrome", true, true);
        // Serves the session-default and "en" snapshots, then aborts on the "de" publication — proving the
        // factory resolves session-language + en + de (in that order) before the runtime is launched.
        final java.util.List<String> languages = new java.util.ArrayList<String>();
        com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider provider =
                new com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshotProvider() {
                    public com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot
                            prepareForSession(String sessionId, File dir, String selected)
                            throws com.aresstack.askai.agent.model.reranker.RerankerConfigurationException {
                        throw new com.aresstack.askai.agent.model.reranker
                                .RerankerConfigurationException("language-less path must not be used");
                    }

                    @Override
                    public com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot
                            prepareForSession(String sessionId, File dir, String selected, String language)
                            throws com.aresstack.askai.agent.model.reranker.RerankerConfigurationException {
                        languages.add(language);
                        if (languages.size() == 3) {
                            throw new com.aresstack.askai.agent.model.reranker
                                    .RerankerConfigurationException("stop-after-de");
                        }
                        com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor descriptor =
                                new com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor(
                                        com.aresstack.askai.agent.model.reranker.RerankerProvider.ASKAI_LOCAL,
                                        "http://127.0.0.1:1", "m",
                                        java.util.Collections.singletonList(com.aresstack.askai.agent.model
                                                .reranker.RerankerCapability.RERANK),
                                        com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics
                                                .RAW_LOGIT, 1000L,
                                        com.aresstack.askai.agent.model.reranker
                                                .RerankerSelectionConfiguration.topN(5));
                        return new com.aresstack.askai.agent.model.reranker.RerankerConfigurationSnapshot(
                                new File(dir, "reranker-config.json"),
                                com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument
                                        .current(0L, descriptor));
                    }
                };
        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                null, config, 1L, provider);
        factory.setResearchLanguageCode("de");
        try {
            factory.createSession("s1", folder.newFolder("session"));
            fail("the provider aborts on the de publication");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("stop-after-de"));
        }
        assertEquals("session-language default, then en, then de",
                java.util.Arrays.asList("de", "en", "de"), languages);
    }

    @Test
    public void createSessionWithoutARerankerProviderFailsVisibly() throws Exception {
        File exe = folder.newFile("java");
        File jar = folder.newFile("agent.jar");
        ResearchRuntimeConfig config = new ResearchRuntimeConfig("chrome", true, true);
        // A null provider models "the host did not publish the mandatory reranker service".
        ProductiveResearchBackendFactory factory = new ProductiveResearchBackendFactory(
                null, config, 1L, null);
        try {
            factory.createSession("s1", folder.newFolder("session"));
            fail("expected a visible failure without a reranker snapshot provider");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(),
                    expected.getMessage().toLowerCase().contains("reranker"));
        }
    }
}
