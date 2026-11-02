package com.aresstack.askai.hf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Everything a HuggingFace model search can be parameterized by, independent of any UI. Swing
 * components build one of these and hand it to the search use case; they never build a HuggingFace
 * URL themselves.
 *
 * <p>Most fields are wired to a UI control and the search URL: {@code searchText}, {@code libraries},
 * {@code tasks}, {@code languages}, {@code licenses}, {@code other}, {@code apps} (sent as
 * {@code apps=}), {@code gated}, the plain {@code inference} "warm inference" switch (sent as
 * {@code inference=warm}), {@code baseOnly}, {@code sortOrder} and {@code pageSize}. The remaining
 * facets ({@code minParameters}/{@code maxParameters} range, {@code inferenceProviders}) are reserved
 * for later (no working server-side API yet), so adding them extends this class instead of reshaping it.</p>
 */
public final class ModelSearchCriteria {

    private final String searchText;
    private final List<String> tasks;
    private final List<String> libraries;
    private final List<String> languages;
    private final List<String> licenses;
    private final List<String> other;
    private final Long minParameters;
    private final Long maxParameters;
    private final List<String> apps;
    private final List<String> inferenceProviders;
    private final boolean baseOnly;
    private final boolean gated;
    private final boolean inference;
    private final SortOrder sortOrder;
    private final int pageSize;

    private ModelSearchCriteria(Builder builder) {
        this.searchText = builder.searchText == null ? "" : builder.searchText.trim();
        this.tasks = immutable(builder.tasks);
        this.libraries = immutable(builder.libraries);
        this.languages = immutable(builder.languages);
        this.licenses = immutable(builder.licenses);
        this.other = immutable(builder.other);
        this.minParameters = builder.minParameters;
        this.maxParameters = builder.maxParameters;
        this.apps = immutable(builder.apps);
        this.inferenceProviders = immutable(builder.inferenceProviders);
        this.baseOnly = builder.baseOnly;
        this.gated = builder.gated;
        this.inference = builder.inference;
        this.sortOrder = builder.sortOrder == null ? SortOrder.TRENDING : builder.sortOrder;
        this.pageSize = builder.pageSize > 0 ? builder.pageSize : 30;
    }

    private static List<String> immutable(List<String> values) {
        return values == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<String>(values));
    }

    public String getSearchText() {
        return searchText;
    }

    public List<String> getTasks() {
        return tasks;
    }

    public List<String> getLibraries() {
        return libraries;
    }

    public List<String> getLanguages() {
        return languages;
    }

    public List<String> getLicenses() {
        return licenses;
    }

    public List<String> getOther() {
        return other;
    }

    public Long getMinParameters() {
        return minParameters;
    }

    public Long getMaxParameters() {
        return maxParameters;
    }

    public List<String> getApps() {
        return apps;
    }

    public List<String> getInferenceProviders() {
        return inferenceProviders;
    }

    public boolean isBaseOnly() {
        return baseOnly;
    }

    /** @return whether to restrict to gated repositories (real HuggingFace {@code gated=true} param). */
    public boolean isGated() {
        return gated;
    }

    public boolean isInference() {
        return inference;
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public int getPageSize() {
        return pageSize;
    }

    /**
     * @return a copy of this criteria restricted to a single library (used when multiple libraries
     *         are selected: HuggingFace ANDs repeated {@code filter=} values instead of ORing them,
     *         so a multi-library selection is realized as one request per library, merged by id).
     */
    public ModelSearchCriteria withSingleLibrary(String library) {
        return toBuilder().libraries(Collections.singletonList(library)).build();
    }

    /** @return a builder pre-filled from this criteria, for producing reduced per-request copies. */
    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String searchText = "";
        private List<String> tasks = Collections.emptyList();
        private List<String> libraries = Collections.singletonList("gguf");
        private List<String> languages = Collections.emptyList();
        private List<String> licenses = Collections.emptyList();
        private List<String> other = Collections.emptyList();
        private Long minParameters;
        private Long maxParameters;
        private List<String> apps = Collections.emptyList();
        private List<String> inferenceProviders = Collections.emptyList();
        private boolean baseOnly;
        private boolean gated;
        private boolean inference;
        private SortOrder sortOrder = SortOrder.TRENDING;
        private int pageSize = 30;

        private Builder() {
        }

        private Builder(ModelSearchCriteria source) {
            this.searchText = source.searchText;
            this.tasks = source.tasks;
            this.libraries = source.libraries;
            this.languages = source.languages;
            this.licenses = source.licenses;
            this.other = source.other;
            this.minParameters = source.minParameters;
            this.maxParameters = source.maxParameters;
            this.apps = source.apps;
            this.inferenceProviders = source.inferenceProviders;
            this.baseOnly = source.baseOnly;
            this.gated = source.gated;
            this.inference = source.inference;
            this.sortOrder = source.sortOrder;
            this.pageSize = source.pageSize;
        }

        public Builder searchText(String value) {
            this.searchText = value;
            return this;
        }

        public Builder tasks(List<String> value) {
            this.tasks = value;
            return this;
        }

        public Builder libraries(List<String> value) {
            this.libraries = value;
            return this;
        }

        public Builder languages(List<String> value) {
            this.languages = value;
            return this;
        }

        public Builder licenses(List<String> value) {
            this.licenses = value;
            return this;
        }

        public Builder other(List<String> value) {
            this.other = value;
            return this;
        }

        public Builder minParameters(Long value) {
            this.minParameters = value;
            return this;
        }

        public Builder maxParameters(Long value) {
            this.maxParameters = value;
            return this;
        }

        public Builder apps(List<String> value) {
            this.apps = value;
            return this;
        }

        public Builder inferenceProviders(List<String> value) {
            this.inferenceProviders = value;
            return this;
        }

        public Builder baseOnly(boolean value) {
            this.baseOnly = value;
            return this;
        }

        public Builder gated(boolean value) {
            this.gated = value;
            return this;
        }

        public Builder inference(boolean value) {
            this.inference = value;
            return this;
        }

        public Builder sortOrder(SortOrder value) {
            this.sortOrder = value;
            return this;
        }

        public Builder pageSize(int value) {
            this.pageSize = value;
            return this;
        }

        public ModelSearchCriteria build() {
            return new ModelSearchCriteria(this);
        }
    }
}
