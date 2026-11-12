package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.UrlSafetyPolicy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Reads a PDF document the research browser reached but cannot read as a page. Firefox (and so Camoufox) shows a
 * PDF in its built-in viewer, where neither the research add-on nor any page script runs, and an attachment only
 * starts a download, which the browser context refuses. Without this reader both ended as a failed visit, so PDF
 * search hits never became sources.
 *
 * <p>The document is fetched once over the host's network path ({@link #useConnector}: proxy, certificates and
 * User-Agent from AskAI's network settings) and extracted with {@link PdfDocumentText}, the one PDF extraction of
 * AskAI. The fetch obeys the browser's rules: the {@link UrlSafetyPolicy} checks EVERY hop (redirects are followed
 * here one by one, never by {@link HttpURLConnection} itself), and the {@link BrowserLimits} bound size and
 * timeouts. Anything that is not a readable PDF fails with an honest {@link BrowserException}; an image-only scan
 * yields an empty text (there is no OCR), never invented content.</p>
 */
public final class PdfPageReader {

    /** Opens an unconnected connection for a URL; the host injects its network path. */
    public interface Connector {
        HttpURLConnection open(String url) throws IOException;
    }

    /** The Content-Type a read document reports, whatever the server declared. */
    static final String PDF_CONTENT_TYPE = "application/pdf";
    /** What the reader asks for: a PDF, anything else only as a fallback the signature check then judges. */
    private static final String ACCEPT = PDF_CONTENT_TYPE + ",*/*;q=0.8";
    /**
     * Redirect hops followed per document: the JDK's own limit for {@link HttpURLConnection}
     * ({@code http.maxRedirects}, default 20), so this manual redirect loop allows exactly what the automatic one
     * would.
     */
    private static final int MAX_REDIRECTS = Integer.getInteger("http.maxRedirects", 20);
    /** RFC 9110 15.4.8 and 15.4.9; {@link HttpURLConnection} names only the older redirect codes. */
    private static final int HTTP_TEMPORARY_REDIRECT = 307;
    private static final int HTTP_PERMANENT_REDIRECT = 308;
    private static final int COPY_BUFFER_BYTES = 8192;

    private static volatile Connector connector;

    private final BrowserLimits limits;
    private final UrlSafetyPolicy policy;

    PdfPageReader(BrowserLimits limits, UrlSafetyPolicy policy) {
        this.limits = limits;
        this.policy = policy;
    }

    /**
     * Lets document fetches go through the host's network settings (proxy, PAC, certificates, proxy login,
     * User-Agent). Without it the reader connects directly, which fails behind a company proxy.
     */
    public static void useConnector(Connector value) {
        connector = value;
    }

    /**
     * Reads {@code document} (a document state from the driver) and returns it with the extracted text.
     *
     * @throws BrowserException when a hop is not allowed, the server refuses, the file exceeds the download limit,
     *                          is no PDF, is password-protected or cannot be parsed
     */
    PlaywrightPageState read(PlaywrightPageState document) throws BrowserException {
        String url = document.url;
        for (int redirects = 0; ; redirects++) {
            policy.check(url);
            HttpURLConnection connection = null;
            try {
                connection = open(url);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(limits.getTimeoutMillis());
                connection.setReadTimeout(limits.getTimeoutMillis());
                connection.setRequestProperty("Accept", ACCEPT);
                int status = connection.getResponseCode();
                if (isRedirect(status)) {
                    if (redirects >= MAX_REDIRECTS) {
                        throw new BrowserException("PDF " + document.url + " could not be read: more than "
                                + MAX_REDIRECTS + " redirects");
                    }
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) {
                        throw new BrowserException("PDF " + url + " could not be read: HTTP " + status
                                + " without a Location header");
                    }
                    url = new URL(new URL(url), location.trim()).toString();
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new BrowserException("PDF " + url + " could not be read: HTTP " + status);
                }
                return extracted(document, url, connection.getContentType(), download(connection, url));
            } catch (IOException ex) {
                throw new BrowserException("PDF " + url + " could not be read: " + ex.getMessage(), ex);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
    }

    private PlaywrightPageState extracted(PlaywrightPageState document, String url, String declaredType,
                                          byte[] bytes) throws BrowserException {
        if (!PdfDocumentText.looksLikePdf(bytes, bytes.length)) {
            throw new BrowserException("The document at " + url + " is no PDF (Content-Type "
                    + (declaredType == null || declaredType.isEmpty() ? "unknown" : declaredType)
                    + "); the browser could not show it as a page either");
        }
        PdfDocumentText pdf = PdfDocumentText.extract(bytes);
        if (pdf.failure == PdfDocumentText.Failure.PASSWORD_PROTECTED) {
            throw new BrowserException("PDF " + url + " is password-protected; no text can be read");
        }
        if (pdf.failure == PdfDocumentText.Failure.UNPARSEABLE) {
            throw new BrowserException("PDF " + url + " could not be parsed: " + pdf.failureDetail);
        }
        if (pdf.text.isEmpty()) {
            System.err.println("[browser] PDF " + url + " has no extractable text (image-only or scanned, "
                    + "pages=" + pdf.pages + "; there is no OCR)");
        } else {
            System.err.println("[browser] PDF read: " + url + " pages=" + pdf.pages + " chars=" + pdf.text.length()
                    + (pdf.encrypted ? " (encrypted, text may be incomplete)" : ""));
        }
        String title = pdf.title.isEmpty() ? fileName(url) : pdf.title;
        return document.withDocumentText(url, title, pdf.text, PDF_CONTENT_TYPE);
    }

    /** The body, refused as soon as it exceeds the browser's download limit. */
    private byte[] download(HttpURLConnection connection, String url) throws IOException, BrowserException {
        long declared = connection.getContentLengthLong();
        if (declared > limits.getMaxDownloadBytes()) {
            throw tooLarge(url, declared);
        }
        InputStream in = connection.getInputStream();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > limits.getMaxDownloadBytes()) {
                    throw tooLarge(url, total);
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private BrowserException tooLarge(String url, long bytes) {
        return new BrowserException("PDF " + url + " is larger than the browser's download limit ("
                + bytes + " > " + limits.getMaxDownloadBytes() + " bytes)");
    }

    private static HttpURLConnection open(String url) throws IOException {
        Connector configured = connector;
        return configured != null ? configured.open(url) : (HttpURLConnection) new URL(url).openConnection();
    }

    private static boolean isRedirect(int status) {
        return status == HttpURLConnection.HTTP_MOVED_PERM
                || status == HttpURLConnection.HTTP_MOVED_TEMP
                || status == HttpURLConnection.HTTP_SEE_OTHER
                || status == HTTP_TEMPORARY_REDIRECT
                || status == HTTP_PERMANENT_REDIRECT;
    }

    /** The last path segment ("report.pdf"), the title of a PDF without a metadata title. */
    static String fileName(String url) {
        String path;
        try {
            path = new URL(url).getPath();
        } catch (IOException malformed) {
            path = url;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            // a '+' in a path is a plus sign, not an encoded space
            name = java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
        } catch (IllegalArgumentException | java.io.UnsupportedEncodingException undecodable) {
            // keep the raw segment
        }
        return name.isEmpty() ? url : name;
    }
}
