package com.aresstack.askai.research.domain.basket;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The basket (Korb): the collection of sources a book is written from, the only interface between the research
 * (Phase 3, later Research4j) and the writing (Phase 4, later Writing4j). It holds references to sources that exist
 * elsewhere, never copies; a {@link BasketReader} gives their passages. How the sources were found (the concept, a
 * search, the user's favourites, a folder) is not part of it. Immutable; a source is in it once.
 */
public final class SourceBasket {

    /** A reference to one source as its owner keeps it. */
    public static final class SourceRef {
        private final String sourceId;
        private final String url;
        private final String title;
        private final String revision;

        /**
         * @param sourceId the source's id in its owner's stock
         * @param revision the revision the basket means, {@code ""} for the current one
         */
        public SourceRef(String sourceId, String url, String title, String revision) {
            this.sourceId = clean(sourceId);
            this.url = clean(url);
            this.title = clean(title);
            this.revision = clean(revision);
            if (this.sourceId.isEmpty()) {
                throw new IllegalArgumentException("a source reference needs an id");
            }
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }

        public String getRevision() {
            return revision;
        }
    }

    private final List<SourceRef> sources;

    public SourceBasket(List<SourceRef> sources) {
        Map<String, SourceRef> once = new LinkedHashMap<String, SourceRef>();
        if (sources != null) {
            for (SourceRef source : sources) {
                if (source != null && !once.containsKey(source.getSourceId())) {
                    once.put(source.getSourceId(), source);
                }
            }
        }
        this.sources = Collections.unmodifiableList(new ArrayList<SourceRef>(once.values()));
    }

    public List<SourceRef> getSources() {
        return sources;
    }

    /** This basket with more sources, as a later search adds them; sources already in it stay as they are. */
    public SourceBasket with(List<SourceRef> more) {
        List<SourceRef> all = new ArrayList<SourceRef>(sources);
        if (more != null) {
            all.addAll(more);
        }
        return new SourceBasket(all);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
