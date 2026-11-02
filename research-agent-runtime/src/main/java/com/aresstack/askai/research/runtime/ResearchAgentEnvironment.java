package com.aresstack.askai.research.runtime;

import java.util.Map;

/**
 * The validated ASKAI_* launch contract of the external agent. The research endpoint is mandatory (URL +
 * transport; token travels inside the URL path AND as a dedicated variable); browser variables are either
 * fully present or fully absent — an absent browser is a visible, non-fatal condition. {@link #toString()}
 * never contains tokens; error messages never dump the whole environment.
 */
final class ResearchAgentEnvironment {

    final String sessionId;
    final String projectId;
    final String researchUrl;
    final String researchTransport;
    final String browserUrl;      // null when no browser backend is available
    final String browserTransport;
    /** Internal service endpoint (manual_source_accept) for user searches; null when absent. */
    final String serviceUrl;
    final String serviceTransport;
    /** Path of the legacy-browser-search config document (same file the browser runtime receives), or null. */
    final String browserSearchConfigPath;
    /** Path of the reranker start snapshot (the mandatory local cross-encoder endpoint), or null. */
    final String rerankerConfigPath;
    /**
     * Optional per-language reranker snapshots (published by the host so the NEXT search after a language
     * switch resolves its language's reranker without a host round-trip). Null when not published — the
     * session-start default then serves every language (today both entries are the same selected model).
     */
    final String rerankerConfigEnPath;
    final String rerankerConfigDePath;
    /** Path of the initial-search strategy snapshot (legacy browser vs. API provider), or null. */
    final String searchStrategyConfigPath;
    /** Path of the structured-inference descriptor (the central main model for SERP repair), or null. */
    final String inferenceConfigPath;
    /** Actionable reason the main model is unavailable when no descriptor was published, or null. */
    final String inferenceUnavailableReason;

    private ResearchAgentEnvironment(String sessionId, String projectId, String researchUrl,
                                     String researchTransport, String browserUrl, String browserTransport,
                                     String browserSearchConfigPath, String rerankerConfigPath,
                                     String rerankerConfigEnPath, String rerankerConfigDePath,
                                     String searchStrategyConfigPath, String inferenceConfigPath,
                                     String inferenceUnavailableReason,
                                     String serviceUrl, String serviceTransport) {
        this.sessionId = sessionId;
        this.projectId = projectId;
        this.researchUrl = researchUrl;
        this.researchTransport = researchTransport;
        this.browserUrl = browserUrl;
        this.browserTransport = browserTransport;
        this.browserSearchConfigPath = browserSearchConfigPath;
        this.rerankerConfigPath = rerankerConfigPath;
        this.rerankerConfigEnPath = rerankerConfigEnPath;
        this.rerankerConfigDePath = rerankerConfigDePath;
        this.searchStrategyConfigPath = searchStrategyConfigPath;
        this.inferenceConfigPath = inferenceConfigPath;
        this.inferenceUnavailableReason = inferenceUnavailableReason;
        this.serviceUrl = serviceUrl;
        this.serviceTransport = serviceTransport;
    }

    static ResearchAgentEnvironment from(Map<String, String> env) {
        String researchUrl = required(env, "ASKAI_RESEARCH_MCP_URL");
        String researchTransport = orDefault(env.get("ASKAI_RESEARCH_MCP_TRANSPORT"), "streamable");
        String browserUrl = blankToNull(env.get("ASKAI_BROWSER_MCP_URL"));
        String serviceUrl = blankToNull(env.get("ASKAI_RESEARCH_SERVICE_MCP_URL"));
        return new ResearchAgentEnvironment(
                orDefault(env.get("ASKAI_SESSION_ID"), ""),
                orDefault(env.get("ASKAI_PROJECT_ID"), ""),
                researchUrl, researchTransport,
                browserUrl,
                browserUrl == null ? null : orDefault(env.get("ASKAI_BROWSER_MCP_TRANSPORT"), "streamable"),
                blankToNull(env.get("ASKAI_BROWSER_SEARCH_CONFIG")),
                blankToNull(env.get("ASKAI_RERANKER_CONFIG")),
                blankToNull(env.get("ASKAI_RERANKER_CONFIG_EN")),
                blankToNull(env.get("ASKAI_RERANKER_CONFIG_DE")),
                blankToNull(env.get("ASKAI_SEARCH_STRATEGY_CONFIG")),
                blankToNull(env.get("ASKAI_INFERENCE_CONFIG")),
                blankToNull(env.get("ASKAI_INFERENCE_UNAVAILABLE_REASON")),
                serviceUrl,
                serviceUrl == null ? null
                        : orDefault(env.get("ASKAI_RESEARCH_SERVICE_MCP_TRANSPORT"), "streamable"));
    }

    boolean hasService() {
        return serviceUrl != null;
    }

    boolean hasSearchStrategyConfig() {
        return searchStrategyConfigPath != null;
    }

    boolean hasReranker() {
        return rerankerConfigPath != null;
    }

    boolean hasInference() {
        return inferenceConfigPath != null;
    }

    boolean hasBrowser() {
        return browserUrl != null;
    }

    private static String required(Map<String, String> env, String key) {
        String value = blankToNull(env.get(key));
        if (value == null) {
            throw new IllegalStateException("Missing required launch variable: " + key);
        }
        return value;
    }

    private static String blankToNull(String v) {
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private static String orDefault(String v, String fallback) {
        return blankToNull(v) == null ? fallback : v.trim();
    }

    @Override
    public String toString() {
        // Tokens are embedded in the URLs; keep them out of any printable form.
        return "ResearchAgentEnvironment{sessionId=" + sessionId + ", projectId=" + projectId
                + ", research=<configured>, browser=" + (hasBrowser() ? "<configured>" : "absent") + "}";
    }
}
