package com.aresstack.askai.hf;

import com.aresstack.askai.hf.SearchFilterState.Group;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Phase-2/4 regression: the shared filter state round-trips through serialize/deserialize. */
public class SearchFilterStateTest {

    @Test
    public void roundTripsEveryField() {
        SearchFilterState s = SearchFilterState.defaults();
        s.setSelected(Group.TASKS, "text-generation", true);
        s.setSelected(Group.LANGUAGES, "de", true);
        s.setSelected(Group.LICENSES, "license:apache-2.0", true);
        s.setSelected(Group.OTHER, "4-bit", true);
        s.setSelected(Group.APPS, "ollama", true);
        s.setGated(true);
        s.setBaseOnly(true);
        s.setInference(true);
        s.setSortOrder(SortOrder.MOST_LIKES);
        s.setPageSize(42);
        s.setSearchText("qwen2.5 coder");

        SearchFilterState back = SearchFilterState.deserialize(s.serialize());
        assertTrue(back.isSelected(Group.TASKS, "text-generation"));
        assertTrue(back.isSelected(Group.LANGUAGES, "de"));
        assertTrue(back.isSelected(Group.LICENSES, "license:apache-2.0"));
        assertTrue(back.isSelected(Group.OTHER, "4-bit"));
        assertTrue(back.isSelected(Group.APPS, "ollama"));
        assertTrue(back.isSelected(Group.LIBRARIES, "gguf"));
        assertTrue(back.isGated());
        assertTrue(back.isBaseOnly());
        assertTrue(back.isInference());
        assertEquals(SortOrder.MOST_LIKES, back.getSortOrder());
        assertEquals(42, back.getPageSize());
        assertEquals("qwen2.5 coder", back.getSearchText());
    }

    @Test
    public void toCriteriaReflectsSelection() {
        SearchFilterState s = SearchFilterState.defaults();
        s.setSelected(Group.TASKS, "image-text-to-text", true);
        s.setSelected(Group.LICENSES, "license:mit", true);
        s.setBaseOnly(true);
        ModelSearchCriteria criteria = s.toCriteria("gemma");
        assertEquals("gemma", criteria.getSearchText());
        assertTrue(criteria.getTasks().contains("image-text-to-text"));
        assertTrue(criteria.getLicenses().contains("license:mit"));
        assertTrue(criteria.getLibraries().contains("gguf"));
        assertTrue(criteria.isBaseOnly());
    }

    @Test
    public void resetGroupAndResetAll() {
        SearchFilterState s = SearchFilterState.defaults();
        s.setSelected(Group.TASKS, "text-generation", true);
        s.setSelected(Group.LICENSES, "license:mit", true);
        s.resetGroup(Group.TASKS);
        assertEquals(0, s.count(Group.TASKS));
        assertEquals(1, s.count(Group.LICENSES));

        s.setSortOrder(SortOrder.MOST_DOWNLOADS);
        s.setGated(true);
        s.setInference(true);
        s.resetAll();
        assertEquals(1, s.count(Group.LIBRARIES));
        assertTrue(s.isSelected(Group.LIBRARIES, "gguf"));
        assertEquals(0, s.count(Group.LICENSES));
        assertFalse(s.isGated());
        assertFalse(s.isInference());
        assertEquals(SortOrder.TRENDING, s.getSortOrder());
    }

    @Test
    public void emptyStringGivesDefaults() {
        SearchFilterState s = SearchFilterState.deserialize("");
        assertTrue(s.isSelected(Group.LIBRARIES, "gguf"));
        assertEquals(SortOrder.TRENDING, s.getSortOrder());
    }
}
