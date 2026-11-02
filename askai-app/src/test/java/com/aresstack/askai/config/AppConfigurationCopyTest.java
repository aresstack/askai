package com.aresstack.askai.config;

import com.aresstack.askai.net.CertificateTrustConfiguration;
import com.aresstack.askai.net.HttpClientConfiguration;
import com.aresstack.askai.net.ProxyConfiguration;
import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * The network panel and the Hugging Face token field used to rebuild the configuration through a
 * constructor that reset the chat colors, AI model selections and search filters. Their copies must
 * replace only what they edit.
 */
public class AppConfigurationCopyTest {

    private static AppConfiguration customized() {
        return AppConfiguration.defaults()
                .withHuggingFaceSearchFilters("libs=gguf")
                .withChatColors(ChatColorSettings.defaults().withUserBackground(new Color(0x123456)))
                .withAiModelSelections(AiModelSelections.defaults().withMainModel("my-main-model"))
                .withUiTheme("classic");
    }

    @Test
    public void networkCopyKeepsEveryOtherSetting() {
        AppConfiguration before = customized();
        ProxyConfiguration proxy = ProxyConfiguration.disabled();
        CertificateTrustConfiguration trust = new CertificateTrustConfiguration(true, false, false);
        HttpClientConfiguration http = HttpClientConfiguration.defaults();

        AppConfiguration after = before.withNetworkConfiguration(proxy, trust, http);

        assertSame(proxy, after.getProxyConfiguration());
        assertSame(trust, after.getCertificateTrustConfiguration());
        assertSame(http, after.getHttpClientConfiguration());
        assertKeptBesidesNetworkAndToken(before, after);
        assertEquals(before.getHuggingFaceToken(), after.getHuggingFaceToken());
    }

    @Test
    public void tokenCopyKeepsEveryOtherSetting() {
        AppConfiguration before = customized();

        AppConfiguration after = before.withHuggingFaceToken("hf_secret");

        assertEquals("hf_secret", after.getHuggingFaceToken());
        assertKeptBesidesNetworkAndToken(before, after);
        assertSame(before.getProxyConfiguration(), after.getProxyConfiguration());
        assertSame(before.getCertificateTrustConfiguration(), after.getCertificateTrustConfiguration());
        assertSame(before.getHttpClientConfiguration(), after.getHttpClientConfiguration());
    }

    private static void assertKeptBesidesNetworkAndToken(AppConfiguration before, AppConfiguration after) {
        assertEquals(before.getOllamaBaseUrl(), after.getOllamaBaseUrl());
        assertEquals(before.getKeepAlive(), after.getKeepAlive());
        assertEquals(before.getDefaultQuantization(), after.getDefaultQuantization());
        assertEquals(before.getModelDownloadDirectory(), after.getModelDownloadDirectory());
        assertSame(before.getSpeechToTextConfiguration(), after.getSpeechToTextConfiguration());
        assertEquals(before.getHuggingFaceSearchSuggestionsRaw(), after.getHuggingFaceSearchSuggestionsRaw());
        assertEquals("libs=gguf", after.getHuggingFaceSearchFilters());
        assertEquals(new Color(0x123456), after.getChatColors().getUserBackground());
        assertEquals("my-main-model", after.getAiModelSelections().getMainModel());
        assertEquals("classic", after.getUiTheme());
    }
}
