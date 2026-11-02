package com.aresstack.serp4j.playwright.google;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Neutral parse result of one classic Google result page; independent of jsoup. Immutable. */
public final class GoogleSerpPage {

    private final List<Organic> organicResults;
    private final List<Link> relatedQueries;
    private final boolean structureRecognized;
    private final boolean nextPage;
    private final int unrecognizedBlocks;
    private final GoogleObstacle pageObstacle;

    GoogleSerpPage(List<Organic> organicResults, List<Link> relatedQueries, boolean structureRecognized,
                   boolean nextPage, int unrecognizedBlocks, GoogleObstacle pageObstacle) {
        this.organicResults = Collections.unmodifiableList(new ArrayList<Organic>(organicResults));
        this.relatedQueries = Collections.unmodifiableList(new ArrayList<Link>(relatedQueries));
        this.structureRecognized = structureRecognized;
        this.nextPage = nextPage;
        this.unrecognizedBlocks = unrecognizedBlocks;
        this.pageObstacle = Objects.requireNonNull(pageObstacle, "pageObstacle");
    }

    /** Organic and video results in page order (ads are skipped). */
    public List<Organic> getOrganicResults() {
        return organicResults;
    }

    /** "Related searches" ({@code Wird auch oft gesucht}). */
    public List<Link> getRelatedQueries() {
        return relatedQueries;
    }

    /**
     * Whether the page is a result page the parser understands: the container ({@code #search} / {@code #rso}) exists
     * and, if no result was extracted, it contains no result markup either ({@code h3}, linked result blocks).
     */
    public boolean isStructureRecognized() {
        return structureRecognized;
    }

    public boolean hasNextPage() {
        return nextPage;
    }

    /** Result blocks with content that the parser did not understand (SERP features not mapped yet). */
    public int getUnrecognizedBlocks() {
        return unrecognizedBlocks;
    }

    /** Obstacle markers found in the HTML itself. */
    public GoogleObstacle getPageObstacle() {
        return pageObstacle;
    }

    /**
     * A copy whose organic and sitelink URLs are replaced where {@code resolver} returns a non-null URL (a resolved
     * {@code /goto} link); everything else stays as it is.
     */
    GoogleSerpPage withResolvedUrls(java.util.function.Function<String, String> resolver) {
        List<Organic> organics = new ArrayList<Organic>(organicResults.size());
        for (Organic o : organicResults) {
            List<Link> links = new ArrayList<Link>(o.siteLinks.size());
            for (Link link : o.siteLinks) {
                links.add(new Link(link.text, resolvedOr(resolver, link.url), link.description));
            }
            String target = resolver.apply(o.url);
            Organic copy = target == null ? o : new Organic(o.rank, o.title, target, o.displayUrl, o.siteName,
                    o.snippet, o.dateText, o.video, o.siteLinks, o.originalUrl == null ? o.url : o.originalUrl);
            organics.add(copy.withSiteLinks(links));
        }
        return new GoogleSerpPage(organics, relatedQueries, structureRecognized, nextPage, unrecognizedBlocks,
                pageObstacle);
    }

    private static String resolvedOr(java.util.function.Function<String, String> resolver, String url) {
        String resolved = resolver.apply(url);
        return resolved == null ? url : resolved;
    }

    @Override
    public String toString() {
        return "GoogleSerpPage{organic=" + organicResults.size() + ", related=" + relatedQueries.size()
                + ", recognized=" + structureRecognized + ", obstacle=" + pageObstacle + '}';
    }

    /** A link with text. */
    public static final class Link {
        private final String text;
        private final String url;
        private final String description;

        Link(String text, String url, String description) {
            this.text = Objects.requireNonNull(text, "text");
            this.url = Objects.requireNonNull(url, "url");
            this.description = description;
        }

        public String getText() {
            return text;
        }

        public String getUrl() {
            return url;
        }

        /** Short description (sitelinks); may be {@code null}. */
        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return text + " <" + url + '>';
        }
    }

    /** One organic (or video) result. */
    public static final class Organic {
        private final int rank;
        private final String title;
        private final String url;
        private final String displayUrl;
        private final String siteName;
        private final String snippet;
        private final String dateText;
        private final boolean video;
        private final List<Link> siteLinks;

        /** The Google {@code /goto} URL this hit had before it was resolved, or {@code null}. */
        private final String originalUrl;

        Organic(int rank, String title, String url, String displayUrl, String siteName, String snippet,
                String dateText, boolean video, List<Link> siteLinks) {
            this(rank, title, url, displayUrl, siteName, snippet, dateText, video, siteLinks, null);
        }

        Organic(int rank, String title, String url, String displayUrl, String siteName, String snippet,
                String dateText, boolean video, List<Link> siteLinks, String originalUrl) {
            this.originalUrl = originalUrl;
            this.rank = rank;
            this.title = Objects.requireNonNull(title, "title");
            this.url = Objects.requireNonNull(url, "url");
            this.displayUrl = displayUrl;
            this.siteName = siteName;
            this.snippet = snippet;
            this.dateText = dateText;
            this.video = video;
            this.siteLinks = Collections.unmodifiableList(new ArrayList<Link>(siteLinks));
        }

        /** 1-based rank among the organic results of the page. */
        public int getRank() {
            return rank;
        }

        public String getTitle() {
            return title;
        }

        /** Absolute target URL; may be a Google {@code /goto} redirect that still needs resolving. */
        public String getUrl() {
            return url;
        }

        /** Breadcrumb shown under the title ({@code https://www.elastic.co › clients}); may be {@code null}. */
        public String getDisplayUrl() {
            return displayUrl;
        }

        public String getSiteName() {
            return siteName;
        }

        public String getSnippet() {
            return snippet;
        }

        /** Date text in front of the snippet ({@code 08.01.2024}); may be {@code null}. */
        public String getDateText() {
            return dateText;
        }

        public boolean isVideo() {
            return video;
        }

        public List<Link> getSiteLinks() {
            return siteLinks;
        }

        /** The provider URL before {@code /goto} resolution; {@code null} when the URL was never a redirect. */
        public String getOriginalUrl() {
            return originalUrl;
        }

        Organic withSiteLinks(List<Link> links) {
            return new Organic(rank, title, url, displayUrl, siteName, snippet, dateText, video, links, originalUrl);
        }

        @Override
        public String toString() {
            return "#" + rank + ' ' + title + " <" + url + '>';
        }
    }
}
