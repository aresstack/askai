package com.aresstack.askai.localmodels;

import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;

/** The manual redirect loop: follows allowed redirects to the final 200 body; a non-2xx/3xx status fails hard. */
public class HttpNlpDownloadClientTest {

    private static final class FakeConnector implements HttpNlpDownloadClient.Connector {
        final Map<String, HttpNlpDownloadClient.Response> byUrl =
                new HashMap<String, HttpNlpDownloadClient.Response>();

        FakeConnector redirect(String from, int status, String to) {
            byUrl.put(from, new HttpNlpDownloadClient.Response(status, to, null));
            return this;
        }

        FakeConnector body(String url, byte[] bytes) {
            byUrl.put(url, new HttpNlpDownloadClient.Response(200, null, bytes));
            return this;
        }

        FakeConnector status(String url, int status) {
            byUrl.put(url, new HttpNlpDownloadClient.Response(status, null, null));
            return this;
        }

        public HttpNlpDownloadClient.Response open(String url) throws IOException {
            HttpNlpDownloadClient.Response r = byUrl.get(url);
            if (r == null) {
                throw new IOException("no fake response for " + url);
            }
            return r;
        }
    }

    @Test
    public void followsAllowedRedirectsToTheFinalBody() throws IOException {
        FakeConnector connector = new FakeConnector()
                .redirect("https://sourceforge.net/.../download", 302, "https://mirror.example/de-sent.bin")
                .body("https://mirror.example/de-sent.bin", new byte[]{1, 2, 3, 4});
        byte[] bytes = new HttpNlpDownloadClient(connector).fetch("https://sourceforge.net/.../download");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, bytes);
    }

    @Test
    public void anHttpErrorStatusFailsHard() {
        FakeConnector connector = new FakeConnector().status("https://x/404", 404);
        try {
            new HttpNlpDownloadClient(connector).fetch("https://x/404");
            fail("a 404 must fail the download");
        } catch (IOException expected) {
            // ok
        }
    }

    @Test
    public void aRedirectLoopIsBounded() {
        FakeConnector connector = new FakeConnector();
        connector.redirect("https://a", 302, "https://a"); // self-loop
        try {
            new HttpNlpDownloadClient(connector).fetch("https://a");
            fail("an endless redirect must be bounded");
        } catch (IOException expected) {
            // ok
        }
    }
}
