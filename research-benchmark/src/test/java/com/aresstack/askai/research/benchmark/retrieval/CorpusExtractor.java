package com.aresstack.askai.research.benchmark.retrieval;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one captured HTML page into benchmark passages: the main content (article/main, else body) without navigation,
 * header, footer, sidebars, scripts and code listings; its paragraphs, list items and quotes in reading order, merged
 * into passages of about {@value #MIN_WORDS} to {@value #MAX_WORDS} words that never cross a heading. Ids are
 * {@code <sourceId>-p<ordinal>}; the checked-in targets point at passages by terms, so a re-capture keeps working.
 */
public final class CorpusExtractor {

    static final int MIN_WORDS = 40;
    static final int MAX_WORDS = 160;

    private CorpusExtractor() {
    }

    public static List<RetrievalBenchmarkData.Passage> extract(String sourceId, String language, String html) {
        Document doc = Jsoup.parse(html == null ? "" : html);
        doc.select("script, style, noscript, nav, header, footer, aside, form, pre, code, table, figure, "
                + "[role=navigation], .sidebar, .toc, #toc, .navbar, .menu, .breadcrumb, .comments").remove();
        Element root = doc.selectFirst("article");
        if (root == null) {
            root = doc.selectFirst("main");
        }
        if (root == null) {
            root = doc.body();
        }
        List<RetrievalBenchmarkData.Passage> passages = new ArrayList<RetrievalBenchmarkData.Passage>();
        if (root == null) {
            return passages;
        }
        StringBuilder current = new StringBuilder();
        for (Element block : root.select("h1, h2, h3, h4, p, li, blockquote, dd")) {
            String tag = block.tagName();
            if (tag.startsWith("h")) {
                flush(sourceId, language, current, passages, true);
                continue;
            }
            if (!block.select("p, li").isEmpty() && !"p".equals(tag)) {
                continue; // containers: their inner paragraphs and items come on their own
            }
            String text = block.text().trim();
            if (words(text) < 4) {
                continue;
            }
            if (words(current.toString()) + words(text) > MAX_WORDS) {
                flush(sourceId, language, current, passages, true);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(text);
            if (words(current.toString()) >= MIN_WORDS) {
                flush(sourceId, language, current, passages, false);
            }
        }
        flush(sourceId, language, current, passages, true);
        return passages;
    }

    private static void flush(String sourceId, String language, StringBuilder current,
                              List<RetrievalBenchmarkData.Passage> passages, boolean force) {
        String text = current.toString().trim();
        if (text.isEmpty() || (!force && words(text) < MIN_WORDS)) {
            return;
        }
        if (words(text) >= 12) { // a heading-bounded scrap of a few words is no passage
            String id = sourceId + "-p" + passages.size();
            passages.add(new RetrievalBenchmarkData.Passage(id, sourceId, language, text));
        }
        current.setLength(0);
    }

    static int words(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
