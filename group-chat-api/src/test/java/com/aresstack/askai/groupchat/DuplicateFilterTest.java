package com.aresstack.askai.groupchat;

import org.junit.Test;

import static org.junit.Assert.*;

public final class DuplicateFilterTest {

    @Test
    public void firstTimeReturnsTrueOnlyOnce() {
        DuplicateFilter filter = new DuplicateFilter();
        assertTrue(filter.firstTime("m1"));
        assertFalse(filter.firstTime("m1"));
        assertTrue(filter.firstTime("m2"));
        assertFalse(filter.firstTime("m2"));
        assertFalse(filter.firstTime("m1"));
    }

    @Test
    public void nullIdIsNeverFirstTime() {
        DuplicateFilter filter = new DuplicateFilter();
        assertFalse(filter.firstTime(null));
    }

    @Test
    public void capacityBoundEvictsOldestEntries() {
        DuplicateFilter filter = new DuplicateFilter(3);
        assertTrue(filter.firstTime("a"));
        assertTrue(filter.firstTime("b"));
        assertTrue(filter.firstTime("c"));
        assertTrue(filter.firstTime("d")); // evicts "a"
        assertTrue("Evicted id is treated as new again", filter.firstTime("a"));
        assertFalse(filter.firstTime("d"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zeroCapacityRejected() {
        new DuplicateFilter(0);
    }
}
