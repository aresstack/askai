package com.aresstack.askai.research.search;

/**
 * Host-side ENCODER of the host→runtime service-command envelope ({@code #RSC1#}) — the sibling of the
 * runtime→host run wire ({@code #RSX1#}). A service command is a typed control instruction carried over the
 * prompt channel (the only host→agent channel) but is NEVER a chat turn: the runtime dispatches it before
 * any model/TeamAgent/state logic. Encoded {@code key=value} with URL-encoded free-text values, so no field
 * ever contains a space. The runtime's parser of the same name is the ONLY consumer.
 *
 * <pre>#RSC1# manual_search request_id=&lt;uuid&gt; query=&lt;urlenc&gt;
 * #RSC1# set_language language=de|en
 * #RSC1# review_sources request_id=&lt;uuid&gt;</pre>
 */
public final class ResearchServiceCommandWire {

    /** Envelope marker; a prompt starting with this is a machine control command, never a chat message. */
    public static final String MARKER = "#RSC1# ";

    private ResearchServiceCommandWire() {
    }

    /**
     * Encode a user-triggered web search command. The query travels URL-encoded (never contains a space);
     * the language snapshot ("en"/"de") is AUTHORITATIVE for this search and re-synchronises the runtime's
     * session language on arrival.
     */
    public static String manualSearch(String requestId, String query, String languageCode) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "query", query);
        if (languageCode != null && !languageCode.isEmpty()) {
            sb.append(" language=").append("de".equalsIgnoreCase(languageCode) ? "de" : "en");
        }
        return sb.toString();
    }

    /**
     * Encode a live working-language switch (best-effort sync for the next TeamAgent turn; the language
     * snapshot on operation requests stays authoritative). Codes are single tokens, no encoding needed.
     */
    public static String setLanguage(String languageCode) {
        return MARKER + "set_language language="
                + ("de".equalsIgnoreCase(languageCode) ? "de" : "en");
    }

    /**
     * Encode the AUTHORITATIVE scope projection for the next turn. The host owns the scope draft; sending
     * it as context is what keeps the model from rebuilding it out of the chat history (and losing
     * decisions in the process). Like set_language this is not a chat turn and changes no state.
     */
    public static String setScope(String renderedScope) {
        StringBuilder sb = new StringBuilder(MARKER).append("set_scope");
        appendEncoded(sb, "scope", renderedScope);
        return sb.toString();
    }

    /**
     * Encode the EXPLICIT post-search review action (issue #29): the user pressed "Neue Quellen auswerten".
     * The runtime brackets the review with {@code manual_search_review started/finished} events carrying
     * this request id — a search itself never triggers the review implicitly anymore.
     */
    public static String reviewSources(String requestId, long capturedThrough) {
        // The pin travels WITH the request: the review must read exactly the material the host will
        // record as reviewed, not whatever has arrived by the time the model answers.
        return MARKER + "review_sources request_id=" + (requestId == null ? "" : requestId)
                + " captured_through=" + Math.max(0L, capturedThrough);
    }

    /**
     * Z3b-3: encode a probe-generation request (the JSON payload built by the scope-side codec).
     * An INTERNAL host->runtime seam: one model call in the runtime, one typed {@code #RSX1#
     * probes} answer back on this request id — no MCP tool, no chat turn, no state change.
     */
    public static String generateProbes(String requestId, String requestJson) {
        StringBuilder sb = new StringBuilder(MARKER).append("generate_probes")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "req", requestJson);
        return sb.toString();
    }

    /**
     * Z4b: encode an advice-chooser request (the finished offers as JSON). Internal seam like
     * generate_probes: one model call in the runtime, one typed {@code #RSX1# advice} answer back
     * on this request id — no chat turn, no state change.
     */
    public static String chooseAdvice(String requestId, String requestJson) {
        StringBuilder sb = new StringBuilder(MARKER).append("choose_advice")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "req", requestJson);
        return sb.toString();
    }

    private static void appendEncoded(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            sb.append(' ').append(key).append('=')
                    .append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ex) {
            // UTF-8 is guaranteed on every JVM; the field is simply omitted if it were missing.
        }
    }
}
