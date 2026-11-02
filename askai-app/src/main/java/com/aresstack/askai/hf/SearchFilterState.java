package com.aresstack.askai.hf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The central, mutable, UI-independent filter selection shared by every tab of the filter dialog and
 * the search bar. Because all views mutate this one object, a selection made in the Main tab is
 * visible in the corresponding detail tab and vice-versa — synchronization is "same object", not
 * event plumbing.
 *
 * <p>It knows how to build an immutable {@link ModelSearchCriteria} for a given search text, and how
 * to serialize/deserialize itself to a single string for persistence (mirroring the existing
 * newline-delimited search-suggestions storage).</p>
 */
public final class SearchFilterState {

    /** The facet groups, each an ordered set of selected technical filter values. */
    public enum Group {
        TASKS, LIBRARIES, LANGUAGES, LICENSES, OTHER, APPS
    }

    private final Set<String> tasks = new LinkedHashSet<String>();
    private final Set<String> libraries = new LinkedHashSet<String>();
    private final Set<String> languages = new LinkedHashSet<String>();
    private final Set<String> licenses = new LinkedHashSet<String>();
    private final Set<String> other = new LinkedHashSet<String>();
    private final Set<String> apps = new LinkedHashSet<String>();
    private boolean gated;
    private boolean baseOnly;
    private boolean inference;
    private SortOrder sortOrder = SortOrder.TRENDING;
    private int pageSize = 30;
    private String searchText = "";

    /** @return a state with the first-run defaults (spec §21): only the GGUF library, sort Trending. */
    public static SearchFilterState defaults() {
        SearchFilterState state = new SearchFilterState();
        state.libraries.add("gguf");
        return state;
    }

    private Set<String> group(Group group) {
        switch (group) {
            case TASKS: return tasks;
            case LIBRARIES: return libraries;
            case LANGUAGES: return languages;
            case LICENSES: return licenses;
            case OTHER: return other;
            case APPS: return apps;
            default: throw new IllegalArgumentException("Unknown group: " + group);
        }
    }

    public boolean isSelected(Group group, String value) {
        return group(group).contains(value);
    }

    public void setSelected(Group group, String value, boolean selected) {
        if (selected) {
            group(group).add(value);
        } else {
            group(group).remove(value);
        }
    }

    public int count(Group group) {
        return group(group).size();
    }

    public List<String> values(Group group) {
        return new ArrayList<String>(group(group));
    }

    /** Clears just one facet group. */
    public void resetGroup(Group group) {
        group(group).clear();
    }

    /** Restores the first-run defaults across every field. */
    public void resetAll() {
        tasks.clear();
        libraries.clear();
        libraries.add("gguf");
        languages.clear();
        licenses.clear();
        other.clear();
        apps.clear();
        gated = false;
        baseOnly = false;
        inference = false;
        sortOrder = SortOrder.TRENDING;
        pageSize = 30;
    }

    public boolean isGated() {
        return gated;
    }

    public void setGated(boolean value) {
        this.gated = value;
    }

    /** @return whether to restrict to models with a warm inference provider (HF {@code inference=warm}). */
    public boolean isInference() {
        return inference;
    }

    public void setInference(boolean value) {
        this.inference = value;
    }

    public boolean isBaseOnly() {
        return baseOnly;
    }

    public void setBaseOnly(boolean value) {
        this.baseOnly = value;
    }

    public SortOrder getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(SortOrder value) {
        this.sortOrder = value == null ? SortOrder.TRENDING : value;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int value) {
        this.pageSize = value > 0 ? value : 30;
    }

    /** @return the last-used search text (optional persistence, spec §21), or "". */
    public String getSearchText() {
        return searchText;
    }

    public void setSearchText(String value) {
        this.searchText = value == null ? "" : value;
    }

    /** @return the total number of active facet selections across all groups (for a summary badge). */
    public int totalActiveFacets() {
        return tasks.size() + libraries.size() + languages.size() + licenses.size()
                + other.size() + apps.size();
    }

    /** Builds an immutable criteria for the given free-text query from the current selection. */
    public ModelSearchCriteria toCriteria(String searchText) {
        return ModelSearchCriteria.builder()
                .searchText(searchText)
                .tasks(new ArrayList<String>(tasks))
                .libraries(new ArrayList<String>(libraries))
                .languages(new ArrayList<String>(languages))
                .licenses(new ArrayList<String>(licenses))
                .other(new ArrayList<String>(other))
                .apps(new ArrayList<String>(apps))
                .gated(gated)
                .baseOnly(baseOnly)
                .inference(inference)
                .sortOrder(sortOrder)
                .pageSize(pageSize)
                .build();
    }

    /**
     * Serializes to a single newline-delimited string for persistence, e.g.
     * {@code tasks=a,b\nlibraries=gguf\nsort=TRENDING\nbaseOnly=false\ngated=false\ninference=false\npageSize=30}.
     */
    public String serialize() {
        StringBuilder builder = new StringBuilder();
        builder.append("tasks=").append(join(tasks)).append('\n');
        builder.append("libraries=").append(join(libraries)).append('\n');
        builder.append("languages=").append(join(languages)).append('\n');
        builder.append("licenses=").append(join(licenses)).append('\n');
        builder.append("other=").append(join(other)).append('\n');
        builder.append("apps=").append(join(apps)).append('\n');
        builder.append("gated=").append(gated).append('\n');
        builder.append("baseOnly=").append(baseOnly).append('\n');
        builder.append("inference=").append(inference).append('\n');
        builder.append("sort=").append(sortOrder.name()).append('\n');
        builder.append("pageSize=").append(pageSize).append('\n');
        builder.append("search=").append(searchText);
        return builder.toString();
    }

    /** Parses a string produced by {@link #serialize()}; unknown/missing keys fall back to defaults. */
    public static SearchFilterState deserialize(String raw) {
        SearchFilterState state = new SearchFilterState();
        if (raw == null || raw.trim().length() == 0) {
            return defaults();
        }
        String[] lines = raw.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int eq = line.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            if ("tasks".equals(key)) {
                addAll(state.tasks, value);
            } else if ("libraries".equals(key)) {
                addAll(state.libraries, value);
            } else if ("languages".equals(key)) {
                addAll(state.languages, value);
            } else if ("licenses".equals(key)) {
                addAll(state.licenses, value);
            } else if ("other".equals(key)) {
                addAll(state.other, value);
            } else if ("apps".equals(key)) {
                addAll(state.apps, value);
            } else if ("gated".equals(key)) {
                state.gated = Boolean.parseBoolean(value);
            } else if ("baseOnly".equals(key)) {
                state.baseOnly = Boolean.parseBoolean(value);
            } else if ("inference".equals(key)) {
                state.inference = Boolean.parseBoolean(value);
            } else if ("sort".equals(key)) {
                state.sortOrder = parseSort(value);
            } else if ("pageSize".equals(key)) {
                state.setPageSize(parseInt(value));
            } else if ("search".equals(key)) {
                state.searchText = value;
            }
        }
        return state;
    }

    private static SortOrder parseSort(String value) {
        try {
            return SortOrder.valueOf(value);
        } catch (IllegalArgumentException ex) {
            return SortOrder.TRENDING;
        }
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return 30;
        }
    }

    private static void addAll(Set<String> target, String commaList) {
        if (commaList == null || commaList.length() == 0) {
            return;
        }
        List<String> parts = Arrays.asList(commaList.split(","));
        for (int i = 0; i < parts.size(); i++) {
            String value = parts.get(i).trim();
            if (value.length() > 0) {
                target.add(value);
            }
        }
    }

    private static String join(Set<String> values) {
        StringBuilder builder = new StringBuilder();
        boolean first = true;
        for (String value : values) {
            if (!first) {
                builder.append(',');
            }
            builder.append(value);
            first = false;
        }
        return builder.toString();
    }
}
