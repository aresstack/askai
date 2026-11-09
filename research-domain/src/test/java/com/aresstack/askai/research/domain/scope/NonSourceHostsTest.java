package com.aresstack.askai.research.domain.scope;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NonSourceHostsTest {

    @Test
    public void theDefaultHostsAreHostEntriesOfTheTypedBlacklist() {
        TypedBlacklist list = TypedBlacklist.of(NonSourceHosts.DEFAULT);
        for (String host : NonSourceHosts.DEFAULT) {
            assertTrue(host, TypedBlacklist.isHostEntry(host));
        }
        // the three sellers of one handbook that covered the root node on 2026-11-09
        assertTrue(list.matches("", "", "https://www.amazon.de/Bautagebuch-Dokumentation/dp/B07QHHSSHZ"));
        assertTrue(list.matches("", "", "https://www.thalia.de/shop/home/artikeldetails/A1070494655"));
        assertTrue(list.matches("", "", "https://www.beck-shop.de/seibel-zoeller-handbuch/product/36958153"));
        assertTrue(list.matches("", "", "https://de.scribd.com/document/681792181/201-060-BG-BAU-web"));
        assertFalse(list.matches("", "", "https://www.baua.de/DE/Themen/Arbeitsgestaltung/x"));
        assertFalse("a publisher's own site is a source", list.matches("", "", "https://www.beck.de/x"));
        assertFalse("a mention is not a host", list.matches("Buy it on amazon.de", "", "https://blog.example/x"));
    }

    @Test
    public void aSearchResultPageOfAWebsiteIsRecognisedByItsUrlShape() {
        assertTrue(NonSourceHosts.isSearchResultPage(
                "https://www.dguv.de/de/suche/index.jsp?query=p306-001&filter=language%3Ade&page=118"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://www.baua.de/SiteGlobals/Forms/Suche/x?q=wearables"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://example.org/search?term=x&q=sensor"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://example.org/en/search/results"));
        assertTrue(NonSourceHosts.isSearchResultPage("https://shop.example/?s=wearable"));
        assertFalse(NonSourceHosts.isSearchResultPage("https://www.baua.de/DE/Themen/Gefaehrdungsbeurteilung?pos=4"));
        assertFalse("a word inside a step is not a step", NonSourceHosts.isSearchResultPage(
                "https://example.org/research/searchlight-sensors"));
        assertFalse("an empty parameter searches nothing", NonSourceHosts.isSearchResultPage(
                "https://example.org/page?q="));
        assertFalse(NonSourceHosts.isSearchResultPage(""));
        assertFalse(NonSourceHosts.isSearchResultPage(null));
    }
}
