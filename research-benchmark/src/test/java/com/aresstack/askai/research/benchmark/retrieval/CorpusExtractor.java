package com.aresstack.askai.research.benchmark.retrieval;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns one captured HTML page into benchmark passages: the main content (article/main, else body) without navigation,
 * header, footer, sidebars, scripts and code listings (inline {@code <code>} stays, it carries identifiers); its paragraphs, list items and quotes in reading order, merged
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
        doc.select("script, style, noscript, nav, header, footer, aside, form, pre, table, figure, "
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
            if (!"p".equals(tag) && hasNestedBlock(block)) {
                continue; // containers: their inner paragraphs and items come on their own
            }
            String text = block.text().trim();
            if (words(text) < 4) {
                continue;
            }
            for (String piece : bounded(text)) {
                if (words(current.toString()) + words(piece) > MAX_WORDS) {
                    flush(sourceId, language, current, passages, true);
                }
                if (current.length() > 0) {
                    current.append(' ');
                }
                current.append(piece);
                if (words(current.toString()) >= MIN_WORDS) {
                    flush(sourceId, language, current, passages, false);
                }
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

    private static boolean hasNestedBlock(Element block) {
        for (Element inner : block.select("p, li")) {
            if (inner != block) {
                return true;
            }
        }
        return false;
    }

    /** Splits a block longer than {@value #MAX_WORDS} words at sentence ends, and at word count as a last resort. */
    static List<String> bounded(String text) {
        List<String> pieces = new ArrayList<String>();
        if (words(text) <= MAX_WORDS) {
            pieces.add(text);
            return pieces;
        }
        StringBuilder piece = new StringBuilder();
        for (String sentence : text.split("(?<=[.!?])\\s+")) {
            for (String part : byWords(sentence)) {
                if (piece.length() > 0 && words(piece.toString()) + words(part) > MAX_WORDS) {
                    pieces.add(piece.toString());
                    piece.setLength(0);
                }
                if (piece.length() > 0) {
                    piece.append(' ');
                }
                piece.append(part);
            }
        }
        if (piece.length() > 0) {
            pieces.add(piece.toString());
        }
        return pieces;
    }

    private static List<String> byWords(String sentence) {
        String[] w = sentence.trim().split("\\s+");
        List<String> parts = new ArrayList<String>();
        for (int from = 0; from < w.length; from += MAX_WORDS) {
            parts.add(String.join(" ", java.util.Arrays.copyOfRange(w, from, Math.min(w.length, from + MAX_WORDS))));
        }
        return parts;
    }

    static int words(String text) {
        String t = text == null ? "" : text.trim();
        return t.isEmpty() ? 0 : t.split("\\s+").length;
    }
}
