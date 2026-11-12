package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.EngineHostRefusedException;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The PDF reader against a local HTTP server: it reads a PDF, follows redirects hop by hop under the URL policy,
 * keeps to the download limit and refuses what is no readable PDF with an honest reason.
 */
public class PdfPageReaderTest {

    private static final BrowserLimits LIMITS = new BrowserLimits(64 * 1024, 40_000, 100, 5_000);

    private HttpServer server;
    private String base;
    private byte[] reportPdf;
    private final List<String> requested = Collections.synchronizedList(new ArrayList<String>());

    @Before
    public void startServer() throws Exception {
        PdfPageReader.useConnector(null);
        reportPdf = TestPdfs.withText("Site Safety Report", "Wearables warn workers before a fall.");
        final byte[] untitled = TestPdfs.withText(null, "Gas sensors measure carbon monoxide.");
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requested.add(path);
            switch (path) {
                case "/report.pdf":
                    send(exchange, 200, "application/pdf", reportPdf);
                    break;
                case "/Leitfaden%20Baustelle.pdf":
                case "/Leitfaden Baustelle.pdf":
                    send(exchange, 200, "application/octet-stream", untitled);
                    break;
                case "/moved":
                    redirect(exchange, 302, "/report.pdf");
                    break;
                case "/moved-twice":
                    redirect(exchange, 301, base + "/moved");
                    break;
                case "/to-ftp":
                    redirect(exchange, 302, "ftp://127.0.0.1/report.pdf");
                    break;
                case "/to-engine":
                    redirect(exchange, 302, "https://www.google.com/search?q=wearables");
                    break;
                case "/loop":
                    redirect(exchange, 302, "/loop");
                    break;
                case "/page.html":
                    send(exchange, 200, "text/html; charset=utf-8",
                            "<!doctype html><title>Not a PDF</title>".getBytes(StandardCharsets.UTF_8));
                    break;
                case "/large.pdf":
                    byte[] large = new byte[(int) LIMITS.getMaxDownloadBytes() + 1];
                    System.arraycopy(reportPdf, 0, large, 0, reportPdf.length);
                    send(exchange, 200, "application/pdf", large);
                    break;
                case "/locked-out.pdf":
                    send(exchange, 403, "text/plain", "forbidden".getBytes(StandardCharsets.UTF_8));
                    break;
                case "/blank.pdf":
                    send(exchange, 200, "application/pdf", TestPdfs.blank());
                    break;
                default:
                    send(exchange, 404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
        PdfPageReader.useConnector(null);
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        OutputStream out = exchange.getResponseBody();
        out.write(body);
        out.close();
    }

    private static void redirect(HttpExchange exchange, int status, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private PdfPageReader reader(UrlSafetyPolicy policy) {
        return new PdfPageReader(LIMITS, policy);
    }

    private PdfPageReader reader() {
        return reader(UrlSafetyPolicy.allowingPrivateNetworks());
    }

    @Test
    public void readsTheDocumentTheBrowserShowsInItsViewer() throws Exception {
        PlaywrightPageState read = reader().read(
                PlaywrightPageState.viewedDocument(base + "/report.pdf", "application/pdf"));

        assertEquals(base + "/report.pdf", read.url);
        assertEquals("Site Safety Report", read.title);
        assertEquals("Wearables warn workers before a fall.", read.text);
        assertTrue(read.isDocument());
        assertTrue("the viewer is in the tab history", read.documentInTab);
        assertTrue(read.anchors.isEmpty());
    }

    @Test
    public void aDocumentWithoutMetadataTitleIsNamedAfterItsFile() throws Exception {
        PlaywrightPageState read = reader().read(
                PlaywrightPageState.downloadedDocument(base + "/Leitfaden%20Baustelle.pdf"));

        assertEquals("Leitfaden Baustelle.pdf", read.title);
        assertEquals("Gas sensors measure carbon monoxide.", read.text);
        assertEquals("the signature decides, not the declared octet-stream", PdfPageReader.PDF_CONTENT_TYPE,
                read.documentType);
        assertTrue("a download never replaced the tab's page", !read.documentInTab);
    }

    @Test
    public void followsRedirectsOneHopAtATime() throws Exception {
        PlaywrightPageState read = reader().read(PlaywrightPageState.downloadedDocument(base + "/moved-twice"));

        assertEquals(base + "/report.pdf", read.url);
        assertEquals("Wearables warn workers before a fall.", read.text);
        assertEquals(java.util.Arrays.asList("/moved-twice", "/moved", "/report.pdf"), requested);
    }

    @Test
    public void aRedirectToAForbiddenSchemeIsRefusedBeforeItIsFetched() {
        try {
            reader().read(PlaywrightPageState.downloadedDocument(base + "/to-ftp"));
            fail("an ftp redirect must be refused");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Blocked URL scheme: ftp"));
        }
    }

    @Test
    public void aWorkerNeverFollowsARedirectToASearchEngine() {
        UrlSafetyPolicy worker = UrlSafetyPolicy.allowingPrivateNetworks()
                .refusingSearchEngines(Collections.<String>emptySet());
        try {
            reader(worker).read(PlaywrightPageState.downloadedDocument(base + "/to-engine"));
            fail("a worker must not reach a search engine through a redirect");
        } catch (EngineHostRefusedException expected) {
            assertEquals(Collections.singletonList("/to-engine"), requested);
        } catch (BrowserException other) {
            fail("expected the engine refusal, got: " + other.getMessage());
        }
    }

    @Test
    public void endlessRedirectsStopAtTheRedirectLimit() {
        try {
            reader().read(PlaywrightPageState.downloadedDocument(base + "/loop"));
            fail("a redirect loop must end");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("redirects"));
        }
    }

    @Test
    public void aFileAboveTheDownloadLimitIsRefused() {
        try {
            reader().read(PlaywrightPageState.viewedDocument(base + "/large.pdf", "application/pdf"));
            fail("the download limit must hold");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("download limit"));
        }
    }

    @Test
    public void aDownloadThatIsNoPdfSaysWhatItIs() {
        try {
            reader().read(PlaywrightPageState.downloadedDocument(base + "/page.html"));
            fail("HTML is no PDF");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("is no PDF"));
            assertTrue(expected.getMessage(), expected.getMessage().contains("text/html"));
        }
    }

    @Test
    public void aRefusingServerIsReportedWithItsStatus() {
        try {
            reader().read(PlaywrightPageState.viewedDocument(base + "/locked-out.pdf", "application/pdf"));
            fail("HTTP 403 must fail");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HTTP 403"));
        }
    }

    @Test
    public void aScanWithoutTextIsReadAsEmptyNeverInvented() throws Exception {
        PlaywrightPageState read = reader().read(
                PlaywrightPageState.viewedDocument(base + "/blank.pdf", "application/pdf"));

        assertEquals("", read.text);
        assertEquals("blank.pdf", read.title);
    }

    @Test
    public void theHostConnectorOpensEveryHop() throws Exception {
        final List<String> opened = new ArrayList<String>();
        PdfPageReader.useConnector(url -> {
            opened.add(url);
            return (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        });

        reader().read(PlaywrightPageState.downloadedDocument(base + "/moved"));

        assertEquals(java.util.Arrays.asList(base + "/moved", base + "/report.pdf"), opened);
    }
}
