package com.aresstack.askai.research.domain.scope;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pages that are never a research source, whatever the query: shop and marketplace listings (a book sold on
 * three sites is one book and zero information) and the search-result pages of other websites (a hit list is a
 * list of promises, not a source). The host entries ride the typed blacklist of the search run as a deterministic
 * host filter ({@link TypedBlacklist#isHostEntry}); the search-result-page rule is a URL shape check.
 * <p>
 * The live run of 2026-11-09 accepted amazon.de, thalia.de and beck-shop.de listings of one handbook as the
 * three "sources" that covered the root node, and two DGUV search-result pages as sources of their own.
 */
public final class NonSourceHosts {

    /** The hosts the search run skips when the setting is on (subdomains included). */
    public static final List<String> DEFAULT = Collections.unmodifiableList(Arrays.asList(
            // marketplaces
            "amazon.de", "amazon.com", "amazon.co.uk", "amazon.at", "amazon.fr", "amazon.it", "amazon.es",
            "ebay.de", "ebay.com", "ebay.at", "ebay.ch", "ebay-kleinanzeigen.de", "kleinanzeigen.de", "etsy.com",
            "aliexpress.com", "alibaba.com", "temu.com", "wish.com", "otto.de", "zalando.de", "idealo.de",
            "billiger.de", "geizhals.de", "preisvergleich.de", "check24.de",
            // book shops and used-book marketplaces
            "thalia.de", "thalia.at", "hugendubel.de", "buecher.de", "beck-shop.de", "booklooker.de", "zvab.com",
            "abebooks.de", "abebooks.com", "medimops.de", "rebuy.de", "momox.de", "weltbild.de", "weltbild.at",
            "orellfuessli.ch", "osiander.de", "bol.de", "bol.com", "lehmanns.de", "genialokal.de", "buchhandel.de",
            "buch24.de", "buchkatalog.de", "dussmann-das-kulturkaufhaus.de", "mayersche.de", "ebook.de",
            "kobo.com", "barnesandnoble.com", "bookdepository.com", "booktopia.com.au", "waterstones.com",
            "audible.de", "audible.com", "lovelybooks.de", "goodreads.com",
            // document re-upload sites (copies without licence or provenance)
            "scribd.com", "de.scribd.com", "dokumen.pub", "pdfcoffee.com", "yumpu.com", "studocu.com",
            "studylib.net", "docplayer.org", "docplayer.net", "dokumente.pub"));

    private NonSourceHosts() {
    }

    /**
     * Whether the URL is a search-result page of some website: a {@code /search}, {@code /suche}, {@code /find} or
     * {@code /results} path step, or a {@code q}, {@code query}, {@code search}, {@code suche} or {@code keyword}
     * parameter. A search engine's own result page is handled by the search provider, never here.
     */
    public static boolean isSearchResultPage(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }
        String value = url.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        String pathAndQuery = slash < 0 ? "" : value.substring(slash);
        int hash = pathAndQuery.indexOf('#');
        if (hash >= 0) {
            pathAndQuery = pathAndQuery.substring(0, hash);
        }
        int question = pathAndQuery.indexOf('?');
        String path = question < 0 ? pathAndQuery : pathAndQuery.substring(0, question);
        String query = question < 0 ? "" : pathAndQuery.substring(question + 1);
        for (String step : path.split("/")) {
            if (step.equals("search") || step.equals("suche") || step.equals("find") || step.equals("results")
                    || step.equals("suchergebnisse") || step.equals("searchresults") || step.equals("search.php")
                    || step.equals("search.html") || step.equals("suche.html") || step.equals("search.jsp")) {
                return true;
            }
        }
        if (query.isEmpty()) {
            return false;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String val = eq < 0 ? "" : pair.substring(eq + 1);
            if (val.isEmpty()) {
                continue;
            }
            if (name.equals("q") || name.equals("query") || name.equals("search") || name.equals("suche")
                    || name.equals("keyword") || name.equals("keywords") || name.equals("searchterm")
                    || name.equals("search_query") || name.equals("suchbegriff") || name.equals("s")) {
                return true;
            }
        }
        return false;
    }
}
