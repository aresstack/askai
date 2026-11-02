package com.aresstack.askai.ui.markdown;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/** Cache a bounded number of rendered diagrams and delegate cache misses. */
public final class CachingMermaidImageRenderer implements MermaidImageRenderer {

    private static final int DEFAULT_CACHE_SIZE = 32;
    // Cache sits above the normalizer: it keys on the original diagram code and stores only successful
    // renders, while the fault-tolerant normalization happens on a cache miss inside the delegate.
    private static final CachingMermaidImageRenderer SHARED = new CachingMermaidImageRenderer(
            new NormalizingMermaidImageRenderer(
                    new AresStackMermaidImageRenderer(), new MermaidRenderingSourceNormalizer()),
            DEFAULT_CACHE_SIZE);

    private final MermaidImageRenderer delegate;
    private final Map<CacheKey, BufferedImage> cache;

    public CachingMermaidImageRenderer(MermaidImageRenderer delegate, final int maximumEntries) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (maximumEntries < 1) {
            throw new IllegalArgumentException("maximumEntries must be positive");
        }
        this.delegate = delegate;
        this.cache = new LinkedHashMap<CacheKey, BufferedImage>(maximumEntries, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, BufferedImage> eldest) {
                return size() > maximumEntries;
            }
        };
    }

    public static CachingMermaidImageRenderer shared() {
        return SHARED;
    }

    @Override
    public BufferedImage render(String diagramCode, int width) {
        CacheKey key = new CacheKey(diagramCode, width);
        synchronized (cache) {
            BufferedImage cached = cache.get(key);
            if (cached != null) {
                return cached;
            }
        }

        BufferedImage rendered = delegate.render(diagramCode, width);
        if (rendered != null) {
            synchronized (cache) {
                cache.put(key, rendered);
            }
        }
        return rendered;
    }

    private static final class CacheKey {

        private final String diagramCode;
        private final int width;

        private CacheKey(String diagramCode, int width) {
            this.diagramCode = diagramCode == null ? "" : diagramCode;
            this.width = width;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey that = (CacheKey) other;
            return width == that.width && diagramCode.equals(that.diagramCode);
        }

        @Override
        public int hashCode() {
            return 31 * diagramCode.hashCode() + width;
        }
    }
}
