package com.aresstack.askai.java8.hf;

import com.aresstack.askai.java8.net.CertificateTrustConfiguration;
import com.aresstack.askai.java8.net.HttpClientConfiguration;
import com.aresstack.askai.java8.net.ProxyConfiguration;
import com.aresstack.askai.java8.net.SystemTrustSslSocketFactory;
import com.aresstack.askai.java8.json.SimpleJson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

public final class HuggingFaceClient implements HuggingFaceSearchGateway {

    // Probed in order; the last one (the JSON API) decides overall success. The bare site is probed
    // first so an HTML/policy page there can be compared against the API's JSON response.
    private static final String[] TEST_URLS = {
            "https://huggingface.co/",
            "https://huggingface.co/api/models?limit=1"
    };

    private static final int BODY_EXCERPT_LIMIT = 1200;

    private final ProxyConfiguration proxyConfiguration;
    private final CertificateTrustConfiguration trustConfiguration;
    private final HttpClientConfiguration httpClientConfiguration;
    private final String accessToken;

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration, String accessToken) {
        this(proxyConfiguration, CertificateTrustConfiguration.defaults(),
                HttpClientConfiguration.defaults(), accessToken);
    }

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration trustConfiguration, String accessToken) {
        this(proxyConfiguration, trustConfiguration, HttpClientConfiguration.defaults(), accessToken);
    }

    public HuggingFaceClient(ProxyConfiguration proxyConfiguration,
                             CertificateTrustConfiguration trustConfiguration,
                             HttpClientConfiguration httpClientConfiguration, String accessToken) {
        this.proxyConfiguration = proxyConfiguration == null ? ProxyConfiguration.defaults() : proxyConfiguration;
        this.trustConfiguration = trustConfiguration == null
                ? CertificateTrustConfiguration.defaults() : trustConfiguration;
        this.httpClientConfiguration = httpClientConfiguration == null
                ? HttpClientConfiguration.defaults() : httpClientConfiguration;
        this.accessToken = accessToken == null ? "" : accessToken.trim();
    }

    /**
     * Performs real HTTPS requests against HuggingFace using the exact same code path (proxy
     * resolution, TLS handshake, certificate validation, HTTP request) as search and download, so a
     * PKIX/certificate problem can be told apart from a proxy problem. For non-2xx responses the
     * relevant response headers and a body excerpt are captured and a heuristic decides whether the
     * block looks like a corporate proxy policy or a HuggingFace/Cloudflare origin response. Never
     * throws and never disables certificate validation; failures are reported through the result.
     */
    public HuggingFaceConnectionTestResult testConnection() {
        SystemTrustSslSocketFactory.Result trust = SystemTrustSslSocketFactory.build(trustConfiguration);

        String resolvedProxy;
        try {
            resolvedProxy = describeProxy(proxyConfiguration.resolveJavaProxy(TEST_URLS[TEST_URLS.length - 1]));
        } catch (IOException ex) {
            resolvedProxy = "unresolved (" + messageOf(ex) + ")";
        }

        // Probe every URL with an honest and a browser-like header profile. The honest profile is what
        // real operations send; the browser-like profile is diagnostic only. If browser-like is allowed
        // while the honest one is blocked, the proxy is blocking the application's client identity.
        List<HeaderProfile> profiles = testProfiles();

        List<HuggingFaceConnectionTestResult.Endpoint> endpoints =
                new ArrayList<HuggingFaceConnectionTestResult.Endpoint>();
        for (int u = 0; u < TEST_URLS.length; u++) {
            for (int p = 0; p < profiles.size(); p++) {
                endpoints.add(probe(TEST_URLS[u], profiles.get(p)));
            }
        }
        return new HuggingFaceConnectionTestResult(resolvedProxy, trust, buildNotes(), endpoints,
                httpClientConfiguration.isPreferIpv6());
    }

    /** A named set of request headers to probe with. */
    private static final class HeaderProfile {
        final String label;
        final Map<String, String> headers;

        HeaderProfile(String label, Map<String, String> headers) {
            this.label = label;
            this.headers = headers;
        }
    }

    private List<HeaderProfile> testProfiles() {
        List<HeaderProfile> profiles = new ArrayList<HeaderProfile>();

        Map<String, String> honest = new LinkedHashMap<String, String>();
        honest.put("Accept", "application/json");
        honest.put("User-Agent", HttpClientConfiguration.DEFAULT_USER_AGENT);
        profiles.add(new HeaderProfile("askai-java8 (honest default)", honest));

        // Browser-like headers for diagnosis only. Deliberately no Accept-Encoding, so the body stays
        // readable for block-page detection.
        Map<String, String> browser = new LinkedHashMap<String, String>();
        browser.put("User-Agent", HttpClientConfiguration.BROWSER_USER_AGENT);
        browser.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        browser.put("Accept-Language", "de-DE,de;q=0.8,en-US;q=0.5,en;q=0.3");
        browser.put("Upgrade-Insecure-Requests", "1");
        browser.put("Sec-Fetch-Dest", "document");
        browser.put("Sec-Fetch-Mode", "navigate");
        browser.put("Sec-Fetch-Site", "none");
        browser.put("Sec-Fetch-User", "?1");
        profiles.add(new HeaderProfile("browser-like (diagnostic)", browser));

        return profiles;
    }

    private List<String> buildNotes() {
        List<String> notes = new ArrayList<String>();
        notes.add("Configured User-Agent (search/download): " + httpClientConfiguration.getUserAgent());
        notes.add("Header profiles probed here: askai-java8 (honest default), browser-like (diagnostic)");
        notes.add("Browser-like headers are for DIAGNOSIS only; real operations keep the honest identity.");
        notes.add("Prefer IPv6: " + httpClientConfiguration.isPreferIpv6()
                + (httpClientConfiguration.isPreferIpv6()
                ? " (a restart is needed for changes; broken IPv6 egress causes connect timeouts)" : ""));
        HttpClientConfiguration.ProxyAuthMode mode = httpClientConfiguration.getProxyAuthMode();
        if (mode == HttpClientConfiguration.ProxyAuthMode.BASIC) {
            String user = httpClientConfiguration.getProxyAuthUsername();
            notes.add("Proxy auth: BASIC" + (user.length() > 0
                    ? " (user \"" + user + "\", Proxy-Authorization sent)"
                    : " (no username set — nothing sent)"));
        } else if (mode == HttpClientConfiguration.ProxyAuthMode.WINDOWS_INTEGRATED) {
            notes.add("Proxy auth: WINDOWS_INTEGRATED (prepared/diagnostic only — NTLM/Kerberos negotiation "
                    + "is not wired, so no credentials are sent. If the browser works via SSO, the proxy likely "
                    + "expects integrated auth that this client cannot perform yet.)");
        } else {
            notes.add("Proxy auth: NONE");
        }
        return notes;
    }

    private HuggingFaceConnectionTestResult.Endpoint probe(String url, HeaderProfile profile) {
        HttpURLConnection connection = null;
        try {
            connection = open(url, profile.headers);
            int status = connection.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            Map<String, List<String>> headerFields = connection.getHeaderFields();
            List<String> headers = collectRelevantHeaders(connection);
            InputStream inputStream = ok ? connection.getInputStream() : connection.getErrorStream();
            String body = readBodyExcerpt(inputStream);
            closeQuietly(inputStream);

            String contentType = safe(headerValue(headerFields, "Content-Type")).toLowerCase();
            boolean json = contentType.contains("application/json") || looksLikeJson(body);
            boolean blockPage = isBlockPage(body);
            String policyId = blockPage ? extractPolicyId(body) : null;
            String contentKind = describeContentKind(json, blockPage, contentType, body);

            String assessment = ok ? "" : classifyNon2xx(url, status, headerFields, body);
            List<String> detail = buildDetailLines(headerFields, headers, body);
            return HuggingFaceConnectionTestResult.Endpoint.http(url, profile.label, status, contentKind,
                    policyId, json, blockPage, assessment, detail);
        } catch (Exception ex) {
            return HuggingFaceConnectionTestResult.Endpoint.error(url, profile.label, isCertificateFailure(ex),
                    messageOf(ex));
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String describeContentKind(boolean json, boolean blockPage, String contentType, String body) {
        if (json) {
            return "JSON (HuggingFace API data)";
        }
        if (blockPage) {
            return "HTML block page (proxy/web-filter policy)";
        }
        if (contentType.contains("text/html") || safe(body).toLowerCase().contains("<html")) {
            return "HTML page";
        }
        return safe(body).length() == 0 ? "empty" : "other";
    }

    private static final String[] BLOCK_PAGE_MARKERS = {
            "access denied", "blockpage", "block page", "zugriff wurde verweigert", "zugriff verweigert",
            "richtlinien-id", "richtlinien id", "policy id", "policy-id", "this request was blocked",
            "web filter", "url filtering"
    };

    /** Detects a corporate proxy/web-filter block page (e.g. ZFD "Access Denied"/"Blockpage"). */
    private static boolean isBlockPage(String body) {
        String haystack = safe(body).toLowerCase();
        for (int i = 0; i < BLOCK_PAGE_MARKERS.length; i++) {
            if (haystack.contains(BLOCK_PAGE_MARKERS[i])) {
                return true;
            }
        }
        return false;
    }

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private static final Pattern POLICY_ID_PATTERN = Pattern.compile(
            "(?i)(?:richtlinien[- ]?id|policy[- ]?id|reference[- ]?id|ref[- ]?id)[^0-9a-f]{0,20}"
                    + "([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})");

    /** Extracts a policy/reference id (UUID) from a block page, preferring one next to a policy label. */
    private static String extractPolicyId(String body) {
        String text = safe(body);
        Matcher labelled = POLICY_ID_PATTERN.matcher(text);
        if (labelled.find()) {
            return labelled.group(1);
        }
        Matcher any = UUID_PATTERN.matcher(text);
        if (any.find()) {
            return any.group();
        }
        return null;
    }

    /**
     * Builds the per-probe detail block: the relevant response headers, and for HTML responses the
     * extracted title, highlighted policy/auth terms and visible text (scripts/styles/tags stripped).
     * For non-HTML responses the raw body excerpt is shown instead.
     */
    private List<String> buildDetailLines(Map<String, List<String>> headerFields, List<String> headers, String body) {
        List<String> lines = new ArrayList<String>();
        if (!headers.isEmpty()) {
            lines.add("Response headers:");
            for (int i = 0; i < headers.size(); i++) {
                lines.add("  " + headers.get(i));
            }
        }
        String contentType = safe(headerValue(headerFields, "Content-Type")).toLowerCase();
        boolean html = contentType.contains("text/html") || safe(body).toLowerCase().contains("<html")
                || safe(body).toLowerCase().contains("<!doctype html");
        if (html) {
            String title = extractTitle(body);
            if (title.length() > 0) {
                lines.add("Page title: \"" + title + "\"");
            }
            String highlighted = joinValues(highlightTerms(body));
            if (highlighted.length() > 0) {
                lines.add("Highlighted terms: " + highlighted);
            }
            String visible = extractVisibleText(body);
            if (visible.length() > 0) {
                lines.add("Visible page text (excerpt):");
                lines.add("  " + visible);
            }
        } else if (safe(body).length() > 0) {
            lines.add("Body (excerpt):");
            lines.add("  " + body);
        }
        return lines;
    }

    private static final String[] RELEVANT_HEADERS = {
            "Server", "Via", "Content-Type", "Content-Length", "Connection",
            "Proxy-Connection", "Proxy-Authenticate", "WWW-Authenticate", "Cache-Control",
            "Location", "Date", "X-Cache", "CF-RAY"
    };

    /**
     * Returns a curated list of "Name: value" header lines: a fixed allowlist plus any header whose
     * name starts with {@code X-} or {@code CF-} (proxy and CDN vendors expose themselves there).
     */
    private List<String> collectRelevantHeaders(HttpURLConnection connection) {
        List<String> lines = new ArrayList<String>();
        Map<String, List<String>> fields = connection.getHeaderFields();
        if (fields == null) {
            return lines;
        }
        for (Map.Entry entry : fields.entrySet()) {
            Object nameObject = entry.getKey();
            if (nameObject == null) {
                continue;
            }
            String name = String.valueOf(nameObject);
            if (!isRelevantHeader(name)) {
                continue;
            }
            Object valueObject = entry.getValue();
            String value = valueObject instanceof List ? joinValues((List) valueObject) : String.valueOf(valueObject);
            lines.add(name + ": " + value);
        }
        return lines;
    }

    private boolean isRelevantHeader(String name) {
        String lower = name.toLowerCase();
        if (lower.startsWith("x-") || lower.startsWith("cf-")) {
            return true;
        }
        for (int i = 0; i < RELEVANT_HEADERS.length; i++) {
            if (RELEVANT_HEADERS[i].toLowerCase().equals(lower)) {
                return true;
            }
        }
        return false;
    }

    private static String joinValues(List values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.valueOf(values.get(i)));
        }
        return builder.toString();
    }

    /**
     * Reads at most {@link #BODY_EXCERPT_LIMIT} characters from the stream and normalises whitespace so
     * the excerpt stays log-friendly. Does not consume the whole (possibly large) body.
     */
    private String readBodyExcerpt(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        byte[] buffer = new byte[4096];
        StringBuilder builder = new StringBuilder();
        int read;
        while (builder.length() < BODY_EXCERPT_LIMIT && (read = inputStream.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        String text = builder.toString().replaceAll("\\s+", " ").trim();
        if (text.length() > BODY_EXCERPT_LIMIT) {
            text = text.substring(0, BODY_EXCERPT_LIMIT) + " …";
        }
        return text;
    }

    private static final String[] HIGHLIGHT_TERMS = {
            "access denied", "access to this", "not authorized", "unauthorized", "authentication",
            "authorization", "sign in", "log in", "login", "captive portal", "policy", "category",
            "web filter", "url filtering", "blocked", "forbidden", "denied", "proxy", "fortinet",
            "fortiguard", "zscaler", "blue coat", "bluecoat", "forcepoint", "websense", "mcafee",
            "netskope", "squid", "gesperrt", "blockiert", "richtlinie", "anmeldung", "nicht erlaubt"
    };

    /** Extracts the {@code <title>} text from an HTML body excerpt, or "" when absent. */
    private static String extractTitle(String body) {
        if (body == null) {
            return "";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<title[^>]*>(.*?)</title>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                .matcher(body);
        if (matcher.find()) {
            return decodeEntities(matcher.group(1)).replaceAll("\\s+", " ").trim();
        }
        return "";
    }

    /**
     * Strips {@code <script>}/{@code <style>} blocks and all tags from an HTML body excerpt and returns
     * the collapsed visible text, capped to keep the log readable.
     */
    private static String extractVisibleText(String body) {
        if (body == null) {
            return "";
        }
        String text = body
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<!--.*?-->", " ")
                .replaceAll("(?is)<[^>]+>", " ");
        text = decodeEntities(text).replaceAll("\\s+", " ").trim();
        int cap = 600;
        if (text.length() > cap) {
            text = text.substring(0, cap) + " …";
        }
        return text;
    }

    /** Returns the distinct policy/auth terms present in the body, in a stable order, for highlighting. */
    private static List<String> highlightTerms(String body) {
        List<String> matches = new ArrayList<String>();
        String haystack = safe(body).toLowerCase();
        for (int i = 0; i < HIGHLIGHT_TERMS.length; i++) {
            String term = HIGHLIGHT_TERMS[i];
            if (haystack.contains(term) && !matches.contains(term)) {
                matches.add(term);
            }
        }
        return matches;
    }

    private static String decodeEntities(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'");
    }

    private static final String[] PROXY_VENDOR_SIGNATURES = {
            "zscaler", "bluecoat", "blue coat", "blue-coat", "forcepoint", "websense", "fortinet",
            "fortigate", "fortiguard", "mcafee", "web gateway", "webwasher", "squid", "ironport",
            "sophos", "palo alto", "check point", "checkpoint", "netskope", "trend micro", "sonicwall",
            "barracuda", "symantec web", "cisco web", "iboss"
    };

    private static final String[] PROXY_POLICY_PHRASES = {
            "access denied", "access to this", "this site is blocked", "site blocked", "blocked category",
            "blocked by", "content blocked", "web filter", "url filtering", "filtering policy",
            "your organization", "company policy", "corporate policy", "not permitted", "denied by policy",
            "proxy policy", "gesperrt", "blockiert", "richtlinie", "nicht erlaubt"
    };

    private static final String[] CLOUDFLARE_SIGNATURES = {
            "cloudflare", "cf-ray", "attention required", "error 1020", "ray id"
    };

    /**
     * Heuristically classifies a non-2xx response as a corporate proxy block vs. a HuggingFace/
     * Cloudflare origin response, based on status code, selected headers and the body excerpt. This is
     * a hint only; the raw headers and body are always shown so the user can judge for themselves.
     */
    private String classifyNon2xx(String url, int status, Map<String, List<String>> headerFields, String body) {
        String proxyAuthenticate = headerValue(headerFields, "Proxy-Authenticate");
        if (status == 407 || proxyAuthenticate != null) {
            return "CORPORATE PROXY requires authentication (HTTP 407 / Proxy-Authenticate present).";
        }

        String server = safe(headerValue(headerFields, "Server"));
        String via = safe(headerValue(headerFields, "Via"));
        String contentType = safe(headerValue(headerFields, "Content-Type")).toLowerCase();
        String cfRay = headerValue(headerFields, "CF-RAY");
        String haystack = (server + " " + via + " " + safe(body)).toLowerCase();

        String vendor = firstMatch(haystack, PROXY_VENDOR_SIGNATURES);
        if (vendor != null) {
            return "Looks like a CORPORATE PROXY block (matched proxy vendor \"" + vendor + "\" in headers/body).";
        }

        boolean cloudflare = cfRay != null || firstMatch(haystack, CLOUDFLARE_SIGNATURES) != null;
        if (cloudflare) {
            return "Looks like a HuggingFace/Cloudflare (origin/CDN) response (Cloudflare signature present), "
                    + "not the corporate proxy.";
        }

        String phrase = firstMatch(safe(body).toLowerCase(), PROXY_POLICY_PHRASES);
        if (phrase != null) {
            return "Looks like a CORPORATE PROXY policy page (matched \"" + phrase + "\" in the body).";
        }

        boolean json = contentType.contains("application/json") || looksLikeJson(body);
        if (json) {
            return "Looks like a HuggingFace origin response (JSON body) — likely auth/authorization "
                    + "(token or gated repo), not the proxy.";
        }

        boolean html = contentType.contains("text/html") || safe(body).toLowerCase().contains("<html");
        if (html && url.contains("/api/")) {
            return "Likely a CORPORATE PROXY page: HTML returned where the API normally returns JSON.";
        }

        return "Origin unclear — inspect the headers and body below.";
    }

    private static String headerValue(Map<String, List<String>> headerFields, String name) {
        if (headerFields == null) {
            return null;
        }
        for (Map.Entry entry : headerFields.entrySet()) {
            Object key = entry.getKey();
            if (key != null && name.equalsIgnoreCase(String.valueOf(key))) {
                Object value = entry.getValue();
                if (value instanceof List && !((List) value).isEmpty()) {
                    return String.valueOf(((List) value).get(0));
                }
                return value == null ? null : String.valueOf(value);
            }
        }
        return null;
    }

    private static String firstMatch(String haystack, String[] needles) {
        for (int i = 0; i < needles.length; i++) {
            if (haystack.contains(needles[i])) {
                return needles[i];
            }
        }
        return null;
    }

    private static boolean looksLikeJson(String body) {
        String trimmed = safe(body).trim();
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    /** Map a JSON array of strings to a List; anything else yields an empty list. */
    private static List<String> stringList(Object value) {
        List<String> result = new ArrayList<String>();
        if (value instanceof List) {
            List values = (List) value;
            for (int i = 0; i < values.size(); i++) {
                Object entry = values.get(i);
                if (entry != null) {
                    result.add(String.valueOf(entry));
                }
            }
        }
        return result;
    }

    /** Runs one all-ANDed search request built from the criteria (see {@link #buildSearchUrl}). */
    public HuggingFaceSearchPage searchModels(ModelSearchCriteria criteria) throws IOException {
        return fetchSearchPage(buildSearchUrl(criteria));
    }

    /**
     * Builds the {@code /api/models} request URL for one all-ANDed criteria. Extracted (and static)
     * so the exact query encoding — {@code filter=} tags vs. the {@code apps=} / {@code inference=warm}
     * / {@code gated=true} facets — is unit-testable without live HTTP.
     *
     * <p>Library, task pipeline_tag, language code, license id and "other" tag are plain tags emitted
     * as repeated {@code filter=} (the server ANDs them). Apps are the exception: HuggingFace filters
     * app compatibility through a dedicated {@code apps=<id>} parameter, not a tag, so a model like a
     * GGUF that Ollama can run is matched even though it carries no literal "ollama" tag. OR within a
     * group and cross-group OR are handled one level up by splitting into separate requests
     * ({@link HuggingFaceSearchUseCase}), so each criteria reaching here is meant to be all-ANDed.</p>
     */
    static String buildSearchUrl(ModelSearchCriteria criteria) {
        StringBuilder url = new StringBuilder("https://huggingface.co/api/models?");
        if (criteria.getSearchText().length() > 0) {
            url.append("search=").append(encode(criteria.getSearchText())).append('&');
        }
        appendFilters(url, criteria.getLibraries());
        appendFilters(url, criteria.getTasks());
        appendFilters(url, criteria.getLanguages());
        appendFilters(url, criteria.getLicenses());
        appendFilters(url, criteria.getOther());
        appendParams(url, "apps", criteria.getApps());
        if (criteria.isGated()) {
            url.append("gated=true&");
        }
        if (criteria.isInference()) {
            // Verified: inference=warm keeps only models with at least one warm inference provider
            // (equivalent to inference_provider=all); the general "Inference" switch of the HF website.
            url.append("inference=warm&");
        }
        url.append("sort=").append(encode(criteria.getSortOrder().getApiField())).append("&direction=-1");
        url.append("&limit=").append(criteria.getPageSize());
        return url.toString();
    }

    private static void appendFilters(StringBuilder url, List<String> values) {
        appendParams(url, "filter", values);
    }

    private static void appendParams(StringBuilder url, String key, List<String> values) {
        for (int i = 0; i < values.size(); i++) {
            url.append(key).append('=').append(encode(values.get(i))).append('&');
        }
    }

    /**
     * Continues pagination from a previous page's {@link HuggingFaceSearchPage#getNextPageUrl()}.
     * That URL already carries HuggingFace's opaque cursor plus every filter/sort parameter from the
     * original request, so no criteria re-encoding happens here.
     */
    public HuggingFaceSearchPage loadMore(String nextPageUrl) throws IOException {
        return fetchSearchPage(nextPageUrl);
    }

    private HuggingFaceSearchPage fetchSearchPage(String url) throws IOException {
        HttpURLConnection connection = open(url);
        String body;
        String nextPageUrl;
        InputStream inputStream = null;
        try {
            int status = connection.getResponseCode();
            inputStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            body = readText(inputStream);
            if (status < 200 || status >= 300) {
                throw new IOException("HuggingFace request failed with HTTP " + status + ": " + body);
            }
            nextPageUrl = parseNextLink(connection.getHeaderField("Link"));
        } finally {
            closeQuietly(inputStream);
            connection.disconnect();
        }
        Object parsed = SimpleJson.parse(body);
        List values = parsed instanceof List ? (List) parsed : new ArrayList();
        List<HuggingFaceModel> models = new ArrayList<HuggingFaceModel>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                models.add(new HuggingFaceModel(
                        firstNonEmpty(string(map, "id"), string(map, "modelId")),
                        string(map, "pipeline_tag"),
                        number(map, "downloads"),
                        number(map, "likes"),
                        stringList(map.get("tags")),
                        string(map, "library_name")));
            }
        }
        return new HuggingFaceSearchPage(models, nextPageUrl);
    }

    private static final Pattern LINK_NEXT_PATTERN = Pattern.compile("<([^>]+)>\\s*;\\s*rel=\"next\"");

    /**
     * @return the {@code rel="next"} URL from an RFC 5988 {@code Link} header value, or {@code null}
     *         when the header is absent or has no next relation (i.e. this was the last page).
     */
    private static String parseNextLink(String linkHeaderValue) {
        if (linkHeaderValue == null) {
            return null;
        }
        Matcher matcher = LINK_NEXT_PATTERN.matcher(linkHeaderValue);
        return matcher.find() ? matcher.group(1) : null;
    }

    public List<HuggingFaceFile> listFiles(String modelId) throws IOException {
        String url = "https://huggingface.co/api/models/" + encodePath(modelId) + "/tree/main?recursive=true";
        Object parsed = SimpleJson.parse(getText(url));
        List values = parsed instanceof List ? (List) parsed : new ArrayList();
        List<HuggingFaceFile> files = new ArrayList<HuggingFaceFile>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                String type = string(map, "type");
                String path = string(map, "path");
                if ((type.length() == 0 || "file".equals(type)) && path.toLowerCase().endsWith(".gguf")) {
                    // For LFS files (all GGUF), the "lfs" block carries the authoritative size and the
                    // sha256 oid; fall back to the top-level size when absent.
                    long size = number(map, "size");
                    String sha256 = "";
                    Object lfs = map.get("lfs");
                    if (lfs instanceof Map) {
                        Map lfsMap = (Map) lfs;
                        long lfsSize = number(lfsMap, "size");
                        if (lfsSize > 0) {
                            size = lfsSize;
                        }
                        String oid = string(lfsMap, "oid");
                        if (oid.toLowerCase().startsWith("sha256:")) {
                            sha256 = oid.substring("sha256:".length());
                        }
                    }
                    files.add(new HuggingFaceFile(modelId, path, size, sha256));
                }
            }
        }
        return files;
    }

    /**
     * Like {@link #listFiles} but WITHOUT the GGUF filter: every regular file with its LFS-aware
     * size and sha256 (when present) — the local-runtime installer verifies safetensors downloads
     * against these checksums.
     */
    public List<HuggingFaceFile> listAllFilesDetailed(String modelId) throws IOException {
        String url = "https://huggingface.co/api/models/" + encodePath(modelId) + "/tree/main?recursive=true";
        Object parsed = SimpleJson.parse(getText(url));
        List values = parsed instanceof List ? (List) parsed : new ArrayList();
        List<HuggingFaceFile> files = new ArrayList<HuggingFaceFile>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                String type = string(map, "type");
                String path = string(map, "path");
                if ((type.length() == 0 || "file".equals(type)) && path.length() > 0) {
                    long size = number(map, "size");
                    String sha256 = "";
                    Object lfs = map.get("lfs");
                    if (lfs instanceof Map) {
                        Map lfsMap = (Map) lfs;
                        long lfsSize = number(lfsMap, "size");
                        if (lfsSize > 0) {
                            size = lfsSize;
                        }
                        String oid = string(lfsMap, "oid");
                        if (oid.toLowerCase().startsWith("sha256:")) {
                            sha256 = oid.substring("sha256:".length());
                        }
                    }
                    files.add(new HuggingFaceFile(modelId, path, size, sha256));
                }
            }
        }
        return files;
    }

    /**
     * Lists every file path in the repository (not just GGUF), for format detection. Unlike
     * {@link #listFiles} this keeps all files (config.json, safetensors, tokenizer, etc.) so the
     * import classifier can see the real repository structure (spec §18), not only the tags.
     */
    public List<String> listAllFiles(String modelId) throws IOException {
        String url = "https://huggingface.co/api/models/" + encodePath(modelId) + "/tree/main?recursive=true";
        Object parsed = SimpleJson.parse(getText(url));
        List values = parsed instanceof List ? (List) parsed : new ArrayList();
        List<String> paths = new ArrayList<String>();
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            if (value instanceof Map) {
                Map map = (Map) value;
                String type = string(map, "type");
                String path = string(map, "path");
                if ((type.length() == 0 || "file".equals(type)) && path.length() > 0) {
                    paths.add(path);
                }
            }
        }
        return paths;
    }

    /**
     * Fetches a repository file's text content (e.g. {@code config.json}) from
     * {@code /<id>/resolve/main/<path>}. HuggingFace answers with a relative redirect to a cache URL;
     * {@link #getText} uses {@code HttpURLConnection}, which follows same-protocol redirects
     * automatically. Throws on a non-2xx final status (e.g. 401 for a gated repo without a token).
     */
    public String fetchFileText(String modelId, String path) throws IOException {
        return fetchFileText(modelId, "main", path);
    }

    /** Like {@link #fetchFileText(String, String)} but pinned to a concrete revision (branch/tag/commit). */
    public String fetchFileText(String modelId, String revision, String path) throws IOException {
        String rev = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        String url = "https://huggingface.co/" + encodePath(modelId) + "/resolve/"
                + encodePath(rev) + "/" + encodePath(path);
        return getText(url);
    }

    /**
     * Fetches the detailed model-info JSON ({@code /api/models/<id>/revision/<rev>}): {@code sha},
     * {@code tags}, {@code cardData}, {@code config}, {@code gguf}, {@code safetensors}, {@code siblings}.
     *
     * @return the parsed object, or an empty map when the response is not a JSON object.
     */
    /** The model-info fields AskAI needs, requested explicitly so they are populated in the response. */
    private static final String[] MODEL_INFO_EXPAND = {
            "sha", "baseModels", "cardData", "config", "gguf", "safetensors", "tags", "transformersInfo"};

    public Map<String, Object> fetchModelInfo(String modelId, String revision) throws IOException {
        String rev = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        StringBuilder url = new StringBuilder("https://huggingface.co/api/models/")
                .append(encodePath(modelId)).append("/revision/").append(encodePath(rev));
        for (int i = 0; i < MODEL_INFO_EXPAND.length; i++) {
            url.append(i == 0 ? '?' : '&').append("expand[]=").append(encode(MODEL_INFO_EXPAND[i]));
        }
        Object parsed = SimpleJson.parse(getText(url.toString()));
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) parsed;
            return map;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** Public GET of an arbitrary text/JSON URL through the same proxy/TLS plumbing (catalog endpoints). */
    public String fetchText(String url) throws IOException {
        return getText(url);
    }

    /**
     * Resolves a branch/tag to its current commit SHA, so downloads and metadata can be pinned to one
     * immutable commit (the repo's {@code main} may move between download and install).
     *
     * @return the resolved commit SHA, or {@code revision} itself when the API does not report one.
     */
    public String resolveRevisionSha(String modelId, String revision) throws IOException {
        String rev = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        String sha = string(fetchModelInfo(modelId, rev), "sha");
        return sha.length() > 0 ? sha : rev;
    }

    private static final int MAX_DOWNLOAD_ATTEMPTS = 5;
    /** Per-read timeout for downloads: long enough for slow links, short enough to not hang for an hour. */
    private static final int DOWNLOAD_READ_TIMEOUT_MILLIS = 60000;

    /**
     * Downloads a GGUF file with resume + retry, and only returns once the file is verifiably complete.
     *
     * <p>A dropped connection ends the input stream with EOF, which the naive loop mistook for
     * "complete" and produced a truncated file that Ollama later rejected. This implementation streams
     * to a {@code .part} file, retries on interruption (resuming with HTTP {@code Range}), and finalises
     * only when the byte count matches the expected size (and the SHA-256 matches when HuggingFace
     * provides it). If it cannot complete, it throws instead of returning a partial file — and the
     * {@code .part} file is kept so a later attempt can resume.</p>
     */
    public File download(HuggingFaceFile file, File targetDirectory, DownloadProgressListener listener) throws IOException {
        return download(file, targetDirectory, "main", listener);
    }

    /** Like {@link #download(HuggingFaceFile, File, DownloadProgressListener)} but pinned to a revision/SHA. */
    public File download(HuggingFaceFile file, File targetDirectory, String revision,
                         DownloadProgressListener listener) throws IOException {
        if (!targetDirectory.isDirectory() && !targetDirectory.mkdirs()) {
            throw new IOException("Could not create download directory: " + targetDirectory.getAbsolutePath());
        }
        File modelDirectory = new File(targetDirectory, sanitize(file.getModelId()));
        if (!modelDirectory.isDirectory() && !modelDirectory.mkdirs()) {
            throw new IOException("Could not create model directory: " + modelDirectory.getAbsolutePath());
        }
        String rev = revision == null || revision.trim().isEmpty() ? "main" : revision.trim();
        File targetFile = new File(modelDirectory, file.getFileName());
        File partFile = new File(modelDirectory, file.getFileName() + ".part");
        String url = "https://huggingface.co/" + encodePath(file.getModelId()) + "/resolve/"
                + encodePath(rev) + "/" + encodePath(file.getPath());
        long expectedSize = file.getSize();

        IOException last = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            long resumeFrom = partFile.isFile() ? partFile.length() : 0L;
            if (expectedSize > 0 && resumeFrom > expectedSize) {
                // The partial is larger than the whole file: it is stale/corrupt, so start over.
                deleteQuietly(partFile);
                resumeFrom = 0L;
            }
            try {
                DownloadPass pass = downloadOnce(url, partFile, resumeFrom, expectedSize, listener);
                long actual = partFile.length();
                long expected = expectedSize > 0 ? expectedSize : pass.total;
                if (expected > 0 && actual != expected) {
                    last = new IOException("Incomplete download: got " + actual + " of " + expected + " bytes");
                    continue; // resume on the next attempt
                }
                verifySha256(file, pass.sha256Hex);
                if (targetFile.isFile() && !targetFile.delete()) {
                    throw new IOException("Could not replace existing file: " + targetFile.getAbsolutePath());
                }
                if (!partFile.renameTo(targetFile)) {
                    throw new IOException("Could not finalise download: " + targetFile.getAbsolutePath());
                }
                return targetFile;
            } catch (Sha256MismatchException ex) {
                // A corrupt body will not fix itself by resuming; restart from scratch (limited attempts).
                deleteQuietly(partFile);
                last = ex;
            } catch (IOException ex) {
                // Keep the .part file so the next attempt resumes from where it stopped.
                last = ex;
            }
        }
        throw new IOException("Download failed after " + MAX_DOWNLOAD_ATTEMPTS + " attempts: "
                + (last == null ? "unknown error" : last.getMessage())
                + ". The partial file was kept for a later resume.", last);
    }

    /** The result of one download pass: the expected total size and the SHA-256 of the file so far. */
    private static final class DownloadPass {
        final long total;
        final String sha256Hex;

        DownloadPass(long total, String sha256Hex) {
            this.total = total;
            this.sha256Hex = sha256Hex;
        }
    }

    /**
     * Performs a single download pass into {@code partFile}, resuming from {@code resumeFrom} via a
     * {@code Range} request when possible. The SHA-256 is computed incrementally while writing (and,
     * on resume, over the bytes already on disk), so no separate re-read of the whole file is needed
     * afterwards — this avoids a long silent pause at 100% on large files.
     */
    private DownloadPass downloadOnce(String url, File partFile, long resumeFrom, long expectedSize,
                                      DownloadProgressListener listener) throws IOException {
        HttpURLConnection connection = open(url);
        // A file download, not JSON: ask for the raw bytes so Content-Length is the real size, and
        // cap the per-read wait low enough that a stalled connection (flaky Wi-Fi/hotspot) fails
        // fast and the loop resumes via Range instead of blocking near 100% for the long default.
        connection.setRequestProperty("Accept", "*/*");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setReadTimeout(DOWNLOAD_READ_TIMEOUT_MILLIS);
        if (resumeFrom > 0) {
            connection.setRequestProperty("Range", "bytes=" + resumeFrom + "-");
        }
        InputStream inputStream = null;
        FileOutputStream outputStream = null;
        try {
            int status = connection.getResponseCode();
            boolean append;
            long baseCompleted;
            if (resumeFrom > 0 && status == 206) {
                append = true;
                baseCompleted = resumeFrom;
            } else if (status == 200) {
                append = false; // server ignored Range or fresh start; overwrite from the beginning
                baseCompleted = 0L;
            } else if (status == 416) {
                // Requested range beyond the file: it is already fully downloaded when sizes match.
                if (expectedSize > 0 && partFile.length() == expectedSize) {
                    return new DownloadPass(expectedSize, sha256Hex(partFile));
                }
                deleteQuietly(partFile);
                throw new IOException("Server rejected the resume range (HTTP 416); restarting.");
            } else {
                String body = readBodyExcerpt(connection.getErrorStream());
                throw new IOException("HuggingFace download failed with HTTP " + status
                        + (body.length() > 0 ? ": " + body : ""));
            }

            MessageDigest digest = newSha256();
            if (append) {
                digestExistingBytes(partFile, resumeFrom, digest);
            }

            long total = totalFromResponse(connection, baseCompleted, expectedSize);
            inputStream = connection.getInputStream();
            outputStream = new FileOutputStream(partFile, append);
            byte[] buffer = new byte[1024 * 1024];
            long completed = baseCompleted;
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                outputStream.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                completed += read;
                if (listener != null) {
                    listener.onProgress(completed, total);
                }
            }
            outputStream.flush();
            return new DownloadPass(total, toHex(digest.digest()));
        } finally {
            closeQuietly(inputStream);
            closeQuietly(outputStream);
            connection.disconnect();
        }
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IOException("SHA-256 not available: " + ex.getMessage(), ex);
        }
    }

    /** Feed the first {@code count} bytes already on disk into the digest (resume case only). */
    private static void digestExistingBytes(File partFile, long count, MessageDigest digest) throws IOException {
        FileInputStream inputStream = new FileInputStream(partFile);
        try {
            byte[] buffer = new byte[1024 * 1024];
            long remaining = count;
            int read;
            while (remaining > 0 && (read = inputStream.read(buffer, 0,
                    (int) Math.min(buffer.length, remaining))) >= 0) {
                digest.update(buffer, 0, read);
                remaining -= read;
            }
        } finally {
            try {
                inputStream.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(bytes[i] & 0xff);
            if (hex.length() == 1) {
                builder.append('0');
            }
            builder.append(hex);
        }
        return builder.toString();
    }

    /** Derives the full expected size from Content-Range (preferred), else Content-Length, else the HF size. */
    private long totalFromResponse(HttpURLConnection connection, long baseCompleted, long expectedSize) {
        String contentRange = connection.getHeaderField("Content-Range");
        if (contentRange != null) {
            int slash = contentRange.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < contentRange.length()) {
                try {
                    return Long.parseLong(contentRange.substring(slash + 1).trim());
                } catch (NumberFormatException ignored) {
                    // fall through
                }
            }
        }
        long contentLength = connection.getContentLengthLong();
        if (contentLength >= 0) {
            return baseCompleted + contentLength;
        }
        return expectedSize;
    }

    /** Compare the already-computed SHA-256 with the one HuggingFace advertised (no file re-read). */
    private void verifySha256(HuggingFaceFile file, String actualSha256Hex) throws IOException {
        String expected = file.getSha256();
        if (expected == null || expected.length() == 0) {
            return; // HuggingFace did not provide a checksum; the size check already passed.
        }
        if (!expected.equalsIgnoreCase(actualSha256Hex)) {
            throw new Sha256MismatchException("Checksum mismatch: expected " + expected
                    + " but got " + actualSha256Hex);
        }
    }

    private static String sha256Hex(File file) throws IOException {
        MessageDigest digest = newSha256();
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(file);
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException ignored) {
                }
            }
        }
        return toHex(digest.digest());
    }

    private void deleteQuietly(File file) {
        if (file != null && file.isFile()) {
            file.delete();
        }
    }

    private static final class Sha256MismatchException extends IOException {
        Sha256MismatchException(String message) {
            super(message);
        }
    }

    private String getText(String url) throws IOException {
        HttpURLConnection connection = open(url);
        InputStream inputStream = null;
        try {
            int status = connection.getResponseCode();
            inputStream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String body = readText(inputStream);
            if (status < 200 || status >= 300) {
                throw new IOException("HuggingFace request failed with HTTP " + status + ": " + body);
            }
            return body;
        } finally {
            closeQuietly(inputStream);
            connection.disconnect();
        }
    }

    private HttpURLConnection open(String url) throws IOException {
        // Real operations keep the honest, configured identity (default: askai-java8).
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        headers.put("User-Agent", httpClientConfiguration.getUserAgent());
        return open(url, headers);
    }

    /**
     * Opens a connection using the same proxy resolution and TLS trust as all HuggingFace operations,
     * but with an explicit set of request headers. The connection test uses this to probe an honest and
     * a browser-like header profile, so a proxy that blocks the application's client identity can be
     * distinguished from one that blocks the URL itself.
     */
    private HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        Proxy proxy = proxyConfiguration.resolveJavaProxy(url);
        HttpURLConnection connection = (HttpURLConnection) (proxy == Proxy.NO_PROXY
                ? new URL(url).openConnection()
                : new URL(url).openConnection(proxy));
        if (connection instanceof HttpsURLConnection) {
            // Corporate proxies terminate TLS with a private CA that lives in the Windows
            // certificate store but not in the JVM cacerts. The configured trust sources decide
            // which anchors to add so the handshake with huggingface.co does not fail with
            // "PKIX path building failed".
            ((HttpsURLConnection) connection).setSSLSocketFactory(
                    SystemTrustSslSocketFactory.build(trustConfiguration).getSocketFactory());
        }
        connection.setConnectTimeout(30000);
        connection.setReadTimeout(3600000);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        applyProxyAuthorization(connection);
        if (accessToken.length() > 0) {
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        }
        return connection;
    }

    /**
     * Applies proxy credentials when BASIC auth is configured. WINDOWS_INTEGRATED is intentionally not
     * wired here (it would require NTLM/Kerberos negotiation); it is only surfaced diagnostically.
     */
    private void applyProxyAuthorization(HttpURLConnection connection) {
        if (!httpClientConfiguration.hasBasicCredentials()) {
            return;
        }
        String raw = httpClientConfiguration.getProxyAuthUsername() + ":"
                + httpClientConfiguration.getProxyAuthPassword();
        String encoded = java.util.Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        connection.setRequestProperty("Proxy-Authorization", "Basic " + encoded);
    }

    private String readText(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        byte[] buffer = new byte[8192];
        StringBuilder builder = new StringBuilder();
        int read;
        while ((read = inputStream.read(buffer)) >= 0) {
            builder.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
        }
        return builder.toString();
    }

    private static String describeProxy(Proxy proxy) {
        if (proxy == null || proxy == Proxy.NO_PROXY || proxy.type() == Proxy.Type.DIRECT) {
            return "DIRECT (no proxy)";
        }
        if (proxy.address() instanceof InetSocketAddress) {
            InetSocketAddress address = (InetSocketAddress) proxy.address();
            return proxy.type() + " " + address.getHostString() + ":" + address.getPort();
        }
        return String.valueOf(proxy);
    }

    /**
     * @return {@code true} when the throwable (or any of its causes) is a certificate/PKIX path
     *         building failure, i.e. a trust problem rather than a proxy or connectivity problem.
     */
    private static boolean isCertificateFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof java.security.cert.CertificateException
                    || current instanceof javax.net.ssl.SSLHandshakeException) {
                return true;
            }
            String className = current.getClass().getName();
            if (className.contains("CertPathBuilderException")
                    || className.contains("CertPathValidatorException")
                    || className.contains("ValidatorException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("pkix path building failed")
                        || lower.contains("unable to find valid certification path")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String messageOf(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        return message != null && message.trim().length() > 0
                ? message : throwable.getClass().getName();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (UnsupportedEncodingException ex) {
            return "";
        }
    }

    private String encodePath(String path) {
        String[] parts = (path == null ? "" : path).split("/");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append('/');
            }
            builder.append(encode(parts[i]).replace("+", "%20"));
        }
        return builder.toString();
    }

    private String sanitize(String value) {
        return (value == null ? "model" : value).replace('/', '_').replace('\\', '_');
    }

    private static String string(Map map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && first.length() > 0 ? first : second;
    }

    private static long number(Map map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private void closeQuietly(InputStream inputStream) {
        if (inputStream == null) {
            return;
        }
        try {
            inputStream.close();
        } catch (IOException ignored) {
        }
    }

    private void closeQuietly(FileOutputStream outputStream) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.close();
        } catch (IOException ignored) {
        }
    }
}
