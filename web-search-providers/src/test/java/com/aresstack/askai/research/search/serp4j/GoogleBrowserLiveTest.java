package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.UrlResolution;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.googlebrowser.GoogleBrowserSearchConfiguration;
import com.aresstack.askai.research.search.security.EncryptedSecret;
import com.aresstack.askai.research.search.security.SecretCipher;
import com.aresstack.askai.research.search.security.SecretValueService;
import org.junit.Assume;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Live check of the Google search browser (askai #31) against real Google with a locally installed browser. Runs only
 * with {@code -Daskai.live.googleBrowser=true}; channel via {@code -Daskai.live.googleBrowser.channel} (default
 * msedge). Prints counts and resolution states only, no page content.
 */
public final class GoogleBrowserLiveTest {

    @Test
    public void searchesGoogleThroughSerp4jSearchBrowser() throws Exception {
        Assume.assumeTrue("live test disabled", Boolean.getBoolean("askai.live.googleBrowser"));
        GoogleBrowserSearchConfiguration configuration = new GoogleBrowserSearchConfiguration();
        configuration.setEnabled(true);
        configuration.setChannel(System.getProperty("askai.live.googleBrowser.channel", "msedge"));
        configuration.setHeadless(!"false".equals(System.getProperty("askai.live.googleBrowser.headless")));
        Serp4jProviderFactory factory = new Serp4jProviderFactory(new SecretValueService(new SecretCipher() {
            public EncryptedSecret encrypt(char[] plainText) {
                throw new UnsupportedOperationException("no secrets");
            }

            public char[] decrypt(EncryptedSecret encryptedSecret) {
                throw new UnsupportedOperationException("no secrets");
            }
        }));
        WebSearchProvider provider = factory.createGoogleBrowser(configuration);
        try {
            WebSearchResult result = provider.search(WebSearchRequest.builder("elasticsearch").build())
                    .get(120, TimeUnit.SECONDS);
            assertEquals(SearchEngine.GOOGLE, result.getSearchEngine());
            int direct = 0, resolved = 0, unresolved = 0;
            for (WebSearchHit hit : result.getHits()) {
                UrlResolution r = hit.getUrlResolution();
                if (r == UrlResolution.RESOLVED) {
                    resolved++;
                } else if (r == UrlResolution.UNRESOLVED) {
                    unresolved++;
                } else {
                    direct++;
                }
            }
            System.out.println("[google-browser-live] hits=" + result.getHits().size() + " direct=" + direct
                    + " resolved=" + resolved + " unresolved=" + unresolved);
            assertFalse("Google returned no hits", result.getHits().isEmpty());
        } finally {
            provider.close();
        }
    }
}
