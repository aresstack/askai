package com.aresstack.askai.hf;

import com.aresstack.askai.net.SystemTrustSslSocketFactory;

import java.util.Collections;
import java.util.List;

/**
 * The outcome of {@link HuggingFaceClient#testConnection()}: real HTTPS requests against HuggingFace
 * over the exact same code path (proxy resolution, TLS handshake, certificate validation, HTTP
 * request) used by search and download, probed with an honest and a browser-like header profile.
 *
 * <p>Reaching an HTTP status proves TLS and certificate validation succeeded, so a non-2xx status is
 * an HTTP/policy matter. Each probe records whether it received HuggingFace JSON or a corporate
 * block page (with its policy id), which lets {@link #conclusion()} state whether the proxy blocks the
 * application's client identity (allow-list the app) or the URL itself.</p>
 */
public final class HuggingFaceConnectionTestResult {

    /** The outcome for a single probed (URL, header profile) pair. */
    public static final class Endpoint {

        private final String url;
        private final String profileLabel;
        private final boolean handshakeSucceeded;
        private final int httpStatus;
        private final boolean certificateFailure;
        private final String errorSummary;
        private final String contentKind;
        private final String policyId;
        private final boolean json;
        private final boolean blockPage;
        private final String assessment;
        private final List<String> detailLines;

        private Endpoint(String url, String profileLabel, boolean handshakeSucceeded, int httpStatus,
                         boolean certificateFailure, String errorSummary, String contentKind, String policyId,
                         boolean json, boolean blockPage, String assessment, List<String> detailLines) {
            this.url = url;
            this.profileLabel = profileLabel;
            this.handshakeSucceeded = handshakeSucceeded;
            this.httpStatus = httpStatus;
            this.certificateFailure = certificateFailure;
            this.errorSummary = errorSummary;
            this.contentKind = contentKind == null ? "" : contentKind;
            this.policyId = policyId;
            this.json = json;
            this.blockPage = blockPage;
            this.assessment = assessment == null ? "" : assessment;
            this.detailLines = detailLines == null ? Collections.<String>emptyList() : detailLines;
        }

        static Endpoint http(String url, String profileLabel, int httpStatus, String contentKind, String policyId,
                             boolean json, boolean blockPage, String assessment, List<String> detailLines) {
            return new Endpoint(url, profileLabel, true, httpStatus, false, null, contentKind, policyId,
                    json, blockPage, assessment, detailLines);
        }

        static Endpoint error(String url, String profileLabel, boolean certificateFailure, String errorSummary) {
            return new Endpoint(url, profileLabel, false, -1, certificateFailure, errorSummary, null, null,
                    false, false, null, null);
        }

        public boolean isSuccess() {
            return handshakeSucceeded && httpStatus >= 200 && httpStatus < 300;
        }

        boolean isJsonSuccess() {
            return isSuccess() && json;
        }

        public int getHttpStatus() {
            return httpStatus;
        }

        public boolean isCertificateFailure() {
            return certificateFailure;
        }

        private void appendTo(StringBuilder builder) {
            builder.append("Endpoint: ").append(url).append("  [profile: ").append(profileLabel).append("]").append('\n');
            if (!handshakeSucceeded) {
                if (certificateFailure) {
                    builder.append("  TLS: FAILED (certificate/PKIX) — ").append(errorSummary).append('\n');
                } else {
                    builder.append("  TLS/connection: FAILED — ").append(errorSummary).append('\n');
                }
                return;
            }
            builder.append("  TLS: OK").append('\n');
            builder.append("  Proxy: OK (reached; HTTP response received)").append('\n');
            if (isSuccess()) {
                builder.append("  HTTP: ").append(httpStatus).append(" OK").append('\n');
            } else {
                builder.append("  HTTP: ").append(httpStatus).append(" — blocked / auth required").append('\n');
            }
            if (contentKind.length() > 0) {
                builder.append("  Content: ").append(contentKind).append('\n');
            }
            if (policyId != null) {
                builder.append("  Block-page policy id: ").append(policyId).append('\n');
            }
            if (assessment.length() > 0) {
                builder.append("  Assessment: ").append(assessment).append('\n');
            }
            for (int i = 0; i < detailLines.size(); i++) {
                builder.append("  ").append(detailLines.get(i)).append('\n');
            }
        }
    }

    private final String resolvedProxy;
    private final SystemTrustSslSocketFactory.Result trust;
    private final List<String> notes;
    private final List<Endpoint> endpoints;
    private final boolean preferIpv6;

    HuggingFaceConnectionTestResult(String resolvedProxy, SystemTrustSslSocketFactory.Result trust,
                                    List<String> notes, List<Endpoint> endpoints, boolean preferIpv6) {
        this.resolvedProxy = resolvedProxy;
        this.trust = trust;
        this.notes = notes == null ? Collections.<String>emptyList() : notes;
        this.endpoints = endpoints;
        this.preferIpv6 = preferIpv6;
    }

    /** @return {@code true} when any probe received HuggingFace JSON with a 2xx status. */
    public boolean isSuccess() {
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isJsonSuccess()) {
                return true;
            }
        }
        return false;
    }

    /** @return {@code true} when any probe failed the TLS handshake with a certificate/PKIX error. */
    public boolean isCertificateFailure() {
        for (int i = 0; i < endpoints.size(); i++) {
            if (endpoints.get(i).isCertificateFailure()) {
                return true;
            }
        }
        return false;
    }

    private Endpoint find(String urlContains, String profilePrefix) {
        for (int i = 0; i < endpoints.size(); i++) {
            Endpoint e = endpoints.get(i);
            if (e.url.contains(urlContains) && e.profileLabel.startsWith(profilePrefix)) {
                return e;
            }
        }
        return null;
    }

    /**
     * @return a plain-language conclusion comparing the honest and browser-like profiles on the API
     *         endpoint, or "" when the comparison is not available.
     */
    public String conclusion() {
        Endpoint honest = find("/api/", "askai");
        Endpoint browser = find("/api/", "browser-like");
        if (honest == null || browser == null) {
            return "";
        }
        // No HTTP response on either profile means the connection itself failed (timeout, refused,
        // DNS). That is a connectivity problem, not a proxy/policy block — do not claim "blocked".
        if (!honest.handshakeSucceeded && !browser.handshakeSucceeded && !isCertificateFailure()) {
            String detail = honest.errorSummary == null ? "" : " (" + honest.errorSummary + ")";
            String hint = preferIpv6
                    ? " Note: \"Prefer IPv6\" is enabled — on a network that advertises IPv6 without real"
                    + " IPv6 internet access, Java times out while browsers silently fall back to IPv4."
                    + " Disable Prefer IPv6 in the Network panel and restart the app."
                    : " Check DNS, firewall, and the IPv4 default route (e.g. a LAN cable capturing the"
                    + " route while the internet is on WLAN).";
            return "No HTTP response was received" + detail
                    + ". This is a network/connectivity problem, not a proxy block." + hint;
        }
        String policy = honest.policyId != null ? honest.policyId : browser.policyId;
        String policyNote = policy != null ? " (block-page policy id: " + policy + ")" : "";
        if (honest.isJsonSuccess()) {
            return "The honest application client (askai) is allowed on the HuggingFace API — "
                    + "no header change is needed.";
        }
        if (browser.isJsonSuccess()) {
            return "Proxy policy appears to block the application client identity (askai)" + policyNote
                    + ". The browser-like profile is allowed. Request allowlisting for AskAI/HuggingFace API — "
                    + "by User-Agent (askai) or by URL: https://huggingface.co/, "
                    + "https://huggingface.co/api/*, https://huggingface.co/*/resolve/*.";
        }
        return "Both the application and the browser-like profile are blocked on the API" + policyNote
                + " — the proxy blocks the URL/category itself, not just the client identity. Request "
                + "allowlisting for the HuggingFace URLs (https://huggingface.co/api/*, "
                + "https://huggingface.co/*/resolve/*).";
    }

    /** @return a one-line summary suitable for a dialog. */
    public String shortSummary() {
        if (isCertificateFailure()) {
            return "Certificate/PKIX failure — see the Network log for trust diagnostics.";
        }
        String conclusion = conclusion();
        if (conclusion.length() > 0) {
            return conclusion;
        }
        if (isSuccess()) {
            return "HuggingFace HTTPS OK — TLS, certificate trust and the API request all succeeded.";
        }
        return "HuggingFace HTTPS test finished — see the Network log for headers, page text and assessment.";
    }

    /**
     * @return a multi-line report for the Network pane log: the cross-profile conclusion, proxy and
     *         certificate-trust diagnostics, then per (URL, profile) status, content and page text.
     */
    public String describe() {
        StringBuilder builder = new StringBuilder();
        builder.append("HuggingFace HTTPS test").append('\n');
        builder.append("Proxy: ").append(resolvedProxy).append('\n');
        String conclusion = conclusion();
        if (conclusion.length() > 0) {
            builder.append("Conclusion: ").append(conclusion).append('\n');
        }
        for (int i = 0; i < notes.size(); i++) {
            builder.append(notes.get(i)).append('\n');
        }
        builder.append('\n');
        builder.append("Certificate trust:").append('\n');
        builder.append("JVM default=").append(trust.isJvmDefaultTrusted()).append('\n');
        builder.append("Windows-ROOT=").append(trust.isWindowsRootTrusted()).append('\n');
        builder.append("Windows CA stores=").append(trust.isWindowsCaStoresTrusted()).append('\n');
        builder.append("Windows exported certificates=").append(trust.getWindowsExportedCertificateCount()).append('\n');
        List<String> diagnostics = trust.getDiagnostics();
        for (int i = 0; i < diagnostics.size(); i++) {
            builder.append("  - ").append(diagnostics.get(i)).append('\n');
        }
        for (int i = 0; i < endpoints.size(); i++) {
            builder.append('\n');
            endpoints.get(i).appendTo(builder);
        }
        builder.append('\n');
        builder.append("Note: the assessment is a heuristic. A non-2xx status here means TLS/certificate "
                + "validation already succeeded; the block is an HTTP policy/auth/allowlisting matter.");
        return builder.toString();
    }
}
